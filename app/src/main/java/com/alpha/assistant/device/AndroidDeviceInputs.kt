package com.alpha.assistant.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun withDeviceLocationPermission(
    context: Context,
    read: suspend (allowPrecise: Boolean) -> DeviceReading<DeviceLocation>,
): DeviceReading<DeviceLocation> {
    currentCoroutineContext().ensureActive()
    val permission = when {
        context.hasLocationPermission(Manifest.permission.ACCESS_FINE_LOCATION) -> Manifest.permission.ACCESS_FINE_LOCATION
        context.hasLocationPermission(Manifest.permission.ACCESS_COARSE_LOCATION) -> Manifest.permission.ACCESS_COARSE_LOCATION
        else -> return DeviceReading.PermissionDenied
    }
    val result = read(permission == Manifest.permission.ACCESS_FINE_LOCATION)
    return if (context.hasLocationPermission(permission)) result else DeviceReading.PermissionDenied
}

private fun Context.hasLocationPermission(permission: String): Boolean =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

internal suspend fun readAndroidLocation(context: Context, allowPrecise: Boolean): DeviceReading<DeviceLocation> =
    readDeviceSample(10_000) { publish ->
        val manager = context.getSystemService(LocationManager::class.java) ?: return@readDeviceSample null
        if (!manager.isLocationEnabled) return@readDeviceSample null

        val enabled = manager.getProviders(true)
        val provider = when {
            LocationManager.FUSED_PROVIDER in enabled -> LocationManager.FUSED_PROVIDER
            allowPrecise && LocationManager.GPS_PROVIDER in enabled -> LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in enabled -> LocationManager.NETWORK_PROVIDER
            else -> return@readDeviceSample null
        }

        val cancellation = CancellationSignal()
        val stop = { cancellation.cancel() }
        try {
            LocationManagerCompat.getCurrentLocation(manager, provider, cancellation, context.mainExecutor) { location ->
                publish(location?.toDeviceReading() ?: DeviceReading.Unavailable)
            }
            stop
        } catch (_: SecurityException) {
            publish(DeviceReading.PermissionDenied)
            stop
        } catch (failure: RuntimeException) {
            stop()
            if (failure is IllegalArgumentException) null else throw failure
        }
    }

internal fun Location.toDeviceReading(): DeviceReading<DeviceLocation> {
    val latitude = latitude
    val longitude = longitude
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return DeviceReading.Unknown
    val accuracy = if (hasAccuracy()) accuracy.takeIf { it.isFinite() && it >= 0f } else null
    return DeviceReading.Available(DeviceLocation(latitude, longitude, accuracy))
}

internal suspend fun readAndroidSensor(context: Context, kind: DeviceSensor): DeviceReading<DeviceSensorSample> =
    readDeviceSample(2_000) { publish ->
        val manager = context.getSystemService(SensorManager::class.java) ?: return@readDeviceSample null
        val sensorType = when (kind) {
            DeviceSensor.ACCELEROMETER -> Sensor.TYPE_ACCELEROMETER
            DeviceSensor.GYROSCOPE -> Sensor.TYPE_GYROSCOPE
            DeviceSensor.AMBIENT_LIGHT -> Sensor.TYPE_LIGHT
        }
        val sensor = manager.getDefaultSensor(sensorType) ?: return@readDeviceSample null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val reading = if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                    DeviceReading.Unknown
                } else {
                    kind.toDeviceReading(event.values)
                }
                publish(reading)
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        if (!manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)) return@readDeviceSample null
        val stop = { manager.unregisterListener(listener) }
        stop
    }

internal fun DeviceSensor.toDeviceReading(values: FloatArray): DeviceReading<DeviceSensorSample> {
    val count = if (this == DeviceSensor.AMBIENT_LIGHT) 1 else 3
    if (values.size < count || (0 until count).any { !values[it].isFinite() }) return DeviceReading.Unknown
    val sample = when (this) {
        DeviceSensor.ACCELEROMETER -> DeviceSensorSample.Acceleration(values[0], values[1], values[2])
        DeviceSensor.GYROSCOPE -> DeviceSensorSample.AngularVelocity(values[0], values[1], values[2])
        DeviceSensor.AMBIENT_LIGHT -> {
            val lux = values[0]
            if (lux < 0f) return DeviceReading.Unknown
            DeviceSensorSample.AmbientLight(lux)
        }
    }
    return DeviceReading.Available(sample)
}

/** A registration supplies its cleanup callback, or null if it cannot start. */
internal suspend fun <T> readDeviceSample(
    timeoutMillis: Long,
    register: ((DeviceReading<T>) -> Unit) -> (() -> Unit)?,
): DeviceReading<T> {
    currentCoroutineContext().ensureActive()
    return try {
        withTimeoutOrNull(timeoutMillis) {
            val result = CompletableDeferred<DeviceReading<T>>()
            val stop = register { result.complete(it) }
            try {
                if (stop == null) DeviceReading.Unavailable else result.await()
            } finally {
                result.cancel()
                stop?.invoke()
            }
        } ?: DeviceReading.Unavailable
    } catch (_: SecurityException) {
        currentCoroutineContext().ensureActive()
        DeviceReading.PermissionDenied
    }
}
