package com.alpha.assistant.device

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject

/** One foreground session consumes the latest reliable sample at most every 50 ms. */
internal class AccelerometerStream(private val context: Context) : SensorEventListener, AutoCloseable {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    val samples = Channel<JSONObject>(Channel.CONFLATED)
    private var last = 0L
    private var sequence = 0L

    fun start(): Boolean {
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        return manager.registerListener(this, sensor, 50_000, Handler(Looper.getMainLooper()))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
            event.timestamp - last < 50_000_000 || event.values.size < 3) return
        val (x, y, z) = event.values
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        val (horizontal, vertical) = when (displays?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation) {
            Surface.ROTATION_90 -> -y to x
            Surface.ROTATION_180 -> -x to -y
            Surface.ROTATION_270 -> y to -x
            else -> x to y
        }
        last = event.timestamp
        samples.trySend(JSONObject().put("type", "sensor").put("sensor", "accelerometer")
            .put("sequence", ++sequence).put("timestampMillis", last / 1_000_000)
            .put("x", horizontal.toDouble()).put("y", vertical.toDouble()).put("z", z.toDouble()))
    }

    override fun close() {
        manager?.unregisterListener(this)
        samples.cancel()
    }
}
