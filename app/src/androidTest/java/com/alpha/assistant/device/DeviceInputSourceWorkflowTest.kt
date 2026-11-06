package com.alpha.assistant.device

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.location.Location
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceInputSourceWorkflowTest {
    // Failure cases: missing or revoked permission, absent or denied registration, timeout,
    // cancellation, duplicate callbacks, invalid coordinates, and invalid sensor samples.
    @Test fun timeUsesTheHostClockForEachRead() {
        var now = 123L
        val source = AndroidDeviceStatusSource { now }
        assertEquals(123L, source.currentTimeEpochMillis())
        now = 456L
        assertEquals(456L, source.currentTimeEpochMillis())
    }

    @Test fun locationChecksPermissionBeforeReadingAndBeforeReturning() = runBlocking {
        val context = LocationPermissionContext()
        val location = DeviceReading.Available(DeviceLocation(1.0, 2.0, 20f))
        assertEquals(DeviceReading.PermissionDenied, AndroidDeviceStatusSource().currentLocation(context))
        assertEquals(DeviceReading.PermissionDenied, withDeviceLocationPermission(context) {
            error("A denied request must not start a location read")
        })

        context.coarse = true
        assertEquals(location, withDeviceLocationPermission(context) { allowPrecise ->
            assertFalse(allowPrecise)
            location
        })
        context.fine = true
        assertEquals(DeviceReading.PermissionDenied, withDeviceLocationPermission(context) { allowPrecise ->
            assertTrue(allowPrecise)
            context.fine = false
            location
        })
        assertEquals(DeviceReading.PermissionDenied, withDeviceLocationPermission(context) {
            context.coarse = false
            location
        })
    }

    @Test fun locationExposesOnlyValidCoordinatesAndAccuracy() {
        val location = Location("fixture").apply {
            latitude = 52.5
            longitude = -0.2
        }
        assertEquals(DeviceReading.Available(DeviceLocation(52.5, -0.2, null)), location.toDeviceReading())
        location.accuracy = 15f
        assertEquals(DeviceReading.Available(DeviceLocation(52.5, -0.2, 15f)), location.toDeviceReading())
        location.accuracy = Float.POSITIVE_INFINITY
        assertEquals(DeviceReading.Available(DeviceLocation(52.5, -0.2, null)), location.toDeviceReading())
        location.longitude = 181.0
        assertEquals(DeviceReading.Unknown, location.toDeviceReading())
        location.latitude = Double.NaN
        location.longitude = 0.0
        assertEquals(DeviceReading.Unknown, location.toDeviceReading())
    }

    @Test fun sensorSamplesHaveClosedTypesAndRejectInvalidValues() {
        assertEquals(
            DeviceReading.Available(DeviceSensorSample.Acceleration(0f, 1f, -2f)),
            DeviceSensor.ACCELEROMETER.toDeviceReading(floatArrayOf(0f, 1f, -2f)),
        )
        assertEquals(
            DeviceReading.Available(DeviceSensorSample.AngularVelocity(1f, 0f, -1f)),
            DeviceSensor.GYROSCOPE.toDeviceReading(floatArrayOf(1f, 0f, -1f)),
        )
        assertEquals(
            DeviceReading.Available(DeviceSensorSample.AmbientLight(0f)),
            DeviceSensor.AMBIENT_LIGHT.toDeviceReading(floatArrayOf(0f)),
        )
        assertEquals(DeviceReading.Unknown, DeviceSensor.ACCELEROMETER.toDeviceReading(floatArrayOf(1f, 2f)))
        assertEquals(DeviceReading.Unknown, DeviceSensor.GYROSCOPE.toDeviceReading(floatArrayOf(1f, Float.NaN, 0f)))
        assertEquals(DeviceReading.Unknown, DeviceSensor.AMBIENT_LIGHT.toDeviceReading(floatArrayOf(-1f)))
    }

    @Test fun oneSampleReadStopsAfterSuccessAndIgnoresDuplicateCallbacks() = runBlocking {
        var stopped = 0
        val stop: () -> Unit = { stopped += 1 }
        val sample = readDeviceSample<Int>(2_000) { publish ->
            publish(DeviceReading.Available(1))
            publish(DeviceReading.Available(2))
            stop
        }
        assertEquals(DeviceReading.Available(1), sample)
        assertEquals(1, stopped)
    }

    @Test fun absentDeniedAndTimedOutReadsHaveTypedResults() = runBlocking {
        assertEquals(DeviceReading.Unavailable, readDeviceSample<Int>(2_000) { null })
        assertEquals(DeviceReading.PermissionDenied, readDeviceSample<Int>(2_000) { throw SecurityException() })
        var stopped = 0
        assertEquals(DeviceReading.Unavailable, readDeviceSample<Int>(50) { { stopped += 1 } })
        assertEquals(1, stopped)
    }

    @Test fun cancellingTheCallerStopsTheReadAndDiscardsLateResults() = runBlocking {
        var stopped = 0
        lateinit var publish: (DeviceReading<Int>) -> Unit
        val stop: () -> Unit = { stopped += 1 }
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            readDeviceSample<Int>(2_000) { callback ->
                publish = callback
                stop
            }
        }
        request.cancelAndJoin()
        publish(DeviceReading.Available(1))
        assertTrue(request.isCancelled)
        assertEquals(1, stopped)
    }

    private class LocationPermissionContext : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        var fine = false
        var coarse = false

        override fun checkSelfPermission(permission: String): Int {
            val granted = when (permission) {
                Manifest.permission.ACCESS_FINE_LOCATION -> fine
                Manifest.permission.ACCESS_COARSE_LOCATION -> coarse
                else -> error("Unexpected location permission")
            }
            return if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
    }
}
