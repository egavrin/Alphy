package com.alpha.assistant.device

internal sealed interface DeviceReading<out T> {
    data class Available<T>(val value: T) : DeviceReading<T>
    data object Unknown : DeviceReading<Nothing>
    data object Unavailable : DeviceReading<Nothing>
    data object PermissionDenied : DeviceReading<Nothing>
}

internal data class DeviceLocation(
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
    val horizontalAccuracyMeters: Float?,
)

internal enum class DeviceSensor {
    ACCELEROMETER,
    GYROSCOPE,
    AMBIENT_LIGHT,
}

internal sealed interface DeviceSensorSample {
    /** Device-axis acceleration in meters per second squared. */
    data class Acceleration(val x: Float, val y: Float, val z: Float) : DeviceSensorSample

    /** Device-axis angular velocity in radians per second. */
    data class AngularVelocity(val x: Float, val y: Float, val z: Float) : DeviceSensorSample

    data class AmbientLight(val lux: Float) : DeviceSensorSample
}
