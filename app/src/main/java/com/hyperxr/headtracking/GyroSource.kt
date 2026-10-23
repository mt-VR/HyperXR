package com.hyperxr.headtracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorDirectChannel
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.MemoryFile
import android.os.Process
import com.hyperxr.diagnostics.Diagnostics
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The gyroscope at the fastest rate the phone offers, into [history]. Head trackers read the
 * rotation since any past time from it.
 *
 * Two ways in, the faster one first:
 *  - direct channel (phones that support it at RATE_VERY_FAST, ~800 Hz): the sensor hub writes
 *    samples straight into shared memory and they are read when a pose is asked for, so the
 *    newest sample is never waiting in a message queue or for a thread to wake up;
 *  - listener (every phone): fastest rate, no batching, on its own high-priority thread.
 */
class GyroSource(context: Context) : SensorEventListener {
    val history = GyroHistory()
    private val sm = context.getSystemService(SensorManager::class.java)
    private val thread = HandlerThread("hx-gyro", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile var rateHz = 0f; private set
    @Volatile var direct = false; private set
    private var n = 0
    private var since = 0L

    private var channel: SensorDirectChannel? = null
    private var memory: MemoryFile? = null
    private var token = 0
    private var slot = 0
    private var lastSerial = -1L
    private val event = ByteArray(EVENT_SIZE)
    private val eventBuf = ByteBuffer.wrap(event).order(ByteOrder.nativeOrder())

    val available: Boolean get() = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
    val mode: String get() = if (direct) "canal direto" else "listener"

    fun start() {
        val g = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return
        if (startDirect(g)) return
        // 0 µs = as fast as the sensor goes; no batching (maxReportLatency 0) so samples arrive at once
        sm.registerListener(this, g, 0, 0, handler)
    }

    private fun startDirect(g: Sensor): Boolean {
        if (g.highestDirectReportRateLevel < SensorDirectChannel.RATE_VERY_FAST) return false
        if (!g.isDirectChannelTypeSupported(SensorDirectChannel.TYPE_MEMORY_FILE)) return false
        return try {
            val mem = MemoryFile("hx-gyro", EVENT_SIZE * SLOTS)
            val ch = sm.createDirectChannel(mem)
            val tk = ch.configure(g, SensorDirectChannel.RATE_VERY_FAST)
            if (tk <= 0) { ch.close(); mem.close(); return false }
            memory = mem; channel = ch; token = tk; slot = 0; lastSerial = -1L
            direct = true
            history.beforeRead = { poll() }
            // also drained every 4 ms when nobody reads, so the ring never overruns
            handler.post(object : Runnable {
                override fun run() { if (!direct) return; poll(); handler.postDelayed(this, 4) }
            })
            Diagnostics.log("Gyro: direct channel at RATE_VERY_FAST")
            true
        } catch (t: Throwable) {
            Diagnostics.log("Gyro: direct channel unavailable (${t.message}), using the listener")
            false
        }
    }

    /** Reads every new sample from the shared ring (the sensor writes them in order, with serials). */
    @Synchronized private fun poll() {
        val mem = memory ?: return
        try {
            var guard = SLOTS
            while (guard-- > 0) {
                mem.readBytes(event, slot * EVENT_SIZE, 0, EVENT_SIZE)
                val size = eventBuf.getInt(0); val tk = eventBuf.getInt(4)
                val serial = eventBuf.getInt(12).toLong() and 0xffffffffL
                if (size != EVENT_SIZE || tk != token) return
                if (lastSerial >= 0 && serial != ((lastSerial + 1) and 0xffffffffL)) {
                    if (serial <= lastSerial && lastSerial - serial < SLOTS) return  // nothing new here yet
                    // overrun or first read: resynchronise on this slot
                }
                lastSerial = serial
                val ts = eventBuf.getLong(16)
                history.add(ts, eventBuf.getFloat(24), eventBuf.getFloat(28), eventBuf.getFloat(32))
                count(ts)
                slot = (slot + 1) % SLOTS
            }
        } catch (_: Throwable) {}
    }

    private fun count(ts: Long) {
        n++
        if (since == 0L) since = ts
        if (ts - since > 1_000_000_000L) { rateHz = n * 1e9f / (ts - since); n = 0; since = ts }
    }

    fun stop() {
        if (direct) {
            direct = false
            history.beforeRead = null
            runCatching { channel?.configure(sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE), SensorDirectChannel.RATE_STOP) }
            runCatching { channel?.close() }; runCatching { memory?.close() }
            channel = null; memory = null
        }
        sm.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        history.add(e.timestamp, e.values[0], e.values[1], e.values[2])
        count(e.timestamp)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun shutdown() { stop(); thread.quitSafely() }

    private companion object {
        /** sensors_event_t as written to a direct channel. */
        const val EVENT_SIZE = 104
        const val SLOTS = 1024
    }
}
