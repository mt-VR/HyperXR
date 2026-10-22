package com.hyperxr.headtracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process

/**
 * The gyroscope at the fastest rate the phone offers (HIGH_SAMPLING_RATE_SENSORS is declared),
 * on its own high-priority thread, into [history]. Head trackers read the rotation since any
 * past time from it.
 */
class GyroSource(context: Context) : SensorEventListener {
    val history = GyroHistory()
    private val sm = context.getSystemService(SensorManager::class.java)
    private val thread = HandlerThread("hx-gyro", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile var rateHz = 0f; private set
    private var n = 0
    private var since = 0L

    val available: Boolean get() = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null

    fun start() {
        val g = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return
        // 0 µs = as fast as the sensor goes; no batching (maxReportLatency 0) so samples arrive at once
        sm.registerListener(this, g, 0, 0, handler)
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        history.add(e.timestamp, e.values[0], e.values[1], e.values[2])
        n++
        if (since == 0L) since = e.timestamp
        if (e.timestamp - since > 1_000_000_000L) { rateHz = n * 1e9f / (e.timestamp - since); n = 0; since = e.timestamp }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun shutdown() { stop(); thread.quitSafely() }
}
