package com.alpha.assistant.device

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.device.DeviceStatusSnapshot.BatteryStatus
import com.alpha.assistant.device.DeviceStatusSnapshot.ConnectivityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class DeviceStatusSnapshotWorkflowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun reportsBatteryStatusAndExternalPowerSeparately() {
        assertEquals(
            DeviceStatusSnapshot(123L, 50, BatteryStatus.CHARGING, true),
            capture(batteryIntent(1, 2, BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_PLUGGED_AC)),
        )
        assertEquals(
            DeviceStatusSnapshot(123L, 0, BatteryStatus.DISCHARGING, false),
            capture(batteryIntent(0, 100, BatteryManager.BATTERY_STATUS_DISCHARGING, 0)),
        )
        assertEquals(
            DeviceStatusSnapshot(123L, 100, BatteryStatus.FULL, true),
            capture(batteryIntent(Int.MAX_VALUE, Int.MAX_VALUE, BatteryManager.BATTERY_STATUS_FULL, BatteryManager.BATTERY_PLUGGED_WIRELESS)),
        )
        assertEquals(
            DeviceStatusSnapshot(123L, 66, BatteryStatus.NOT_CHARGING, true),
            capture(batteryIntent(2, 3, BatteryManager.BATTERY_STATUS_NOT_CHARGING, BatteryManager.BATTERY_PLUGGED_USB)),
        )
    }

    @Test fun reportsUnknownForAbsentOrMalformedExtras() {
        val unknown = DeviceStatusSnapshot(123L, null, BatteryStatus.UNKNOWN, null)
        assertEquals(unknown, capture(null))
        assertEquals(unknown, capture(Intent(Intent.ACTION_BATTERY_CHANGED)))
        assertEquals(
            unknown,
            capture(Intent(Intent.ACTION_BATTERY_CHANGED).apply {
                putExtra(BatteryManager.EXTRA_LEVEL, "0")
                putExtra(BatteryManager.EXTRA_SCALE, 100)
                putExtra(BatteryManager.EXTRA_STATUS, "charging")
                putExtra(BatteryManager.EXTRA_PLUGGED, false)
            }),
        )
        assertEquals(
            DeviceStatusSnapshot(123L, 50, BatteryStatus.UNKNOWN, true),
            capture(batteryIntent(50, 100, BatteryManager.BATTERY_STATUS_UNKNOWN, BatteryManager.BATTERY_PLUGGED_DOCK)),
        )
        assertEquals(
            DeviceStatusSnapshot(123L, 50, BatteryStatus.UNKNOWN, null),
            capture(batteryIntent(50, 100, 99, 99)),
        )
    }

    @Test fun rejectsInvalidRatiosAndPreservesOtherFields() {
        for ((level, scale) in listOf(0 to 0, 1 to -1, -1 to 100, 101 to 100)) {
            assertEquals(
                DeviceStatusSnapshot(123L, null, BatteryStatus.DISCHARGING, false),
                capture(batteryIntent(level, scale, BatteryManager.BATTERY_STATUS_DISCHARGING, 0)),
            )
        }
        assertNull(capture(Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, 0)).batteryLevelPercent)
    }

    @Test fun readsFreshStatusAndClockWithoutRegisteringAReceiver() {
        var now = 321L
        val source = AndroidDeviceStatusSource { now }
        val firstIntent = batteryIntent(30, 100, BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_PLUGGED_USB)
            .putExtra("unrelated", "excluded from snapshot")
        val sticky = StickyBatteryContext(firstIntent)
        assertEquals(0, sticky.reads)

        val first = source.capture(sticky)
        firstIntent.putExtra(BatteryManager.EXTRA_LEVEL, 99)
        sticky.battery = batteryIntent(40, 100, BatteryManager.BATTERY_STATUS_DISCHARGING, 0)
        now = 654L
        val second = source.capture(sticky)

        assertEquals(DeviceStatusSnapshot(321L, 30, BatteryStatus.CHARGING, true), first)
        assertEquals(DeviceStatusSnapshot(654L, 40, BatteryStatus.DISCHARGING, false), second)
        assertEquals(2, sticky.reads)
    }

    @Test fun readsTheRealAndroidBatterySnapshot() {
        val before = System.currentTimeMillis()
        val snapshot = AndroidDeviceStatusSource().capture(context)
        val after = System.currentTimeMillis()

        assertTrue(snapshot.capturedAtEpochMillis in before..after)
        val level = snapshot.batteryLevelPercent
        assertTrue(level == null || level in 0..100)
    }

    @Test fun projectsThePrimaryHostLocaleAndReportsInvalidLocalesAsUnknown() {
        val locale = Locale.forLanguageTag("sr-Latn-RS-u-nu-latn")
        val source = AndroidDeviceStatusSource { 123L }
        val snapshot = source.capture(StickyBatteryContext(null, locale))
        assertEquals("sr-Latn-RS", snapshot.localeTag)
        assertEquals("sr-Latn-RS", readDeviceLocale(LocaleList(locale, Locale.US)))
        assertNull(readDeviceLocale(LocaleList.getEmptyLocaleList()))
        assertNull(readDeviceLocale(LocaleList(Locale.ROOT, Locale.US)))
        @Suppress("DEPRECATION")
        val malformed = Locale("invalid_language")
        assertNull(readDeviceLocale(LocaleList(malformed)))
    }

    @Test fun distinguishesDefaultNetworkPresenceFromAnUnavailableRead() {
        val host = StickyBatteryContext(null)
        assertEquals(ConnectivityStatus.UNAVAILABLE, readDeviceConnectivity(host) {
            error("A denied request must not read the default network")
        })

        host.networkPermissionGranted = true
        assertEquals(ConnectivityStatus.UNAVAILABLE, AndroidDeviceStatusSource().capture(host).connectivityStatus)
        assertEquals(ConnectivityStatus.UNAVAILABLE, readDeviceConnectivity(host) { throw SecurityException() })
        assertEquals(ConnectivityStatus.DEFAULT_NETWORK_PRESENT, readDeviceConnectivity(host) { true })
        assertEquals(ConnectivityStatus.NO_ACCESSIBLE_DEFAULT_NETWORK, readDeviceConnectivity(host) { false })
    }

    private fun capture(intent: Intent?) = AndroidDeviceStatusSource { 123L }.capture(StickyBatteryContext(intent))

    private fun batteryIntent(level: Int, scale: Int, status: Int, plugged: Int) =
        Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, level)
            .putExtra(BatteryManager.EXTRA_SCALE, scale)
            .putExtra(BatteryManager.EXTRA_STATUS, status)
            .putExtra(BatteryManager.EXTRA_PLUGGED, plugged)

    private fun withLocale(locale: Locale): Context =
        context.createConfigurationContext(Configuration().apply { setLocale(locale) })

    private inner class StickyBatteryContext(var battery: Intent?, locale: Locale = Locale.ROOT) :
        ContextWrapper(withLocale(locale)) {
        var reads = 0
            private set
        var networkPermissionGranted = false

        override fun checkSelfPermission(permission: String): Int {
            if (permission != Manifest.permission.ACCESS_NETWORK_STATE) return super.checkSelfPermission(permission)
            return if (networkPermissionGranted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }

        override fun getSystemService(name: String): Any? =
            if (name == Context.CONNECTIVITY_SERVICE) null else super.getSystemService(name)

        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? {
            assertNull(receiver)
            assertEquals(1, filter.countActions())
            assertEquals(Intent.ACTION_BATTERY_CHANGED, filter.getAction(0))
            reads += 1
            return battery
        }

        override fun unregisterReceiver(receiver: BroadcastReceiver) {
            error("The snapshot must not register a receiver")
        }
    }
}
