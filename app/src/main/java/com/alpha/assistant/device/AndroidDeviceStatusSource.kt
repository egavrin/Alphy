package com.alpha.assistant.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.LocaleList
import com.alpha.assistant.device.DeviceStatusSnapshot.BatteryStatus
import com.alpha.assistant.device.DeviceStatusSnapshot.ConnectivityStatus
import java.util.IllformedLocaleException
import java.util.Locale

internal class AndroidDeviceStatusSource(
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    fun currentTimeEpochMillis(): Long = nowEpochMillis()

    /** The foreground host owns the coroutine and cancels it when the mini-app stops. */
    suspend fun currentLocation(context: Context): DeviceReading<DeviceLocation> =
        withDeviceLocationPermission(context) { allowPrecise -> readAndroidLocation(context, allowPrecise) }

    suspend fun readSensor(context: Context, sensor: DeviceSensor): DeviceReading<DeviceSensorSample> =
        readAndroidSensor(context, sensor)

    fun capture(context: Context): DeviceStatusSnapshot {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val capturedAt = currentTimeEpochMillis()
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (scale > 0 && level in 0..scale) {
            (level.toLong() * 100 / scale).toInt()
        } else {
            null
        }
        val status = when (battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> BatteryStatus.CHARGING
            BatteryManager.BATTERY_STATUS_DISCHARGING -> BatteryStatus.DISCHARGING
            BatteryManager.BATTERY_STATUS_FULL -> BatteryStatus.FULL
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> BatteryStatus.NOT_CHARGING
            else -> BatteryStatus.UNKNOWN
        }
        val externalPowerConnected = when (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
            0 -> false
            BatteryManager.BATTERY_PLUGGED_AC,
            BatteryManager.BATTERY_PLUGGED_USB,
            BatteryManager.BATTERY_PLUGGED_WIRELESS,
            BatteryManager.BATTERY_PLUGGED_DOCK -> true
            else -> null
        }
        return DeviceStatusSnapshot(
            capturedAt, percent, status, externalPowerConnected,
            localeTag = readDeviceLocale(context.resources.configuration.locales),
            connectivityStatus = readDeviceConnectivity(context),
        )
    }
}

internal fun readDeviceLocale(locales: LocaleList): String? {
    val locale = locales[0] ?: return null
    if (locale.language.isEmpty() || locale.language == "und") return null
    return try {
        Locale.Builder()
            .setLanguage(locale.language)
            .setScript(locale.script)
            .setRegion(locale.country)
            .build()
            .toLanguageTag()
    } catch (_: IllformedLocaleException) {
        null
    }
}

internal fun readDeviceConnectivity(
    context: Context,
    readDefaultNetwork: (() -> Boolean?)? = null,
): ConnectivityStatus {
    if (context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) {
        return ConnectivityStatus.UNAVAILABLE
    }
    return try {
        val present = if (readDefaultNetwork != null) {
            readDefaultNetwork()
        } else {
            context.getSystemService(ConnectivityManager::class.java)?.let { it.activeNetwork != null }
        }
        when (present) {
            true -> ConnectivityStatus.DEFAULT_NETWORK_PRESENT
            false -> ConnectivityStatus.NO_ACCESSIBLE_DEFAULT_NETWORK
            null -> ConnectivityStatus.UNAVAILABLE
        }
    } catch (_: SecurityException) {
        ConnectivityStatus.UNAVAILABLE
    }
}
