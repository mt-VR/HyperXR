package com.hyperxr.diagnostics

import kotlin.math.sqrt

/**
 * Fixed-capacity ring buffer of latency samples (nanoseconds). Recording never allocates,
 * so it can be called from render/sensor threads every frame.
 */
class LatencyStats(val name: String, capacity: Int = 512) {
    private val samples = LongArray(capacity)
    private var count = 0
    private var head = 0
    private var total = 0L

    @Synchronized
    fun record(nanos: Long) {
        if (nanos < 0) return
        samples[head] = nanos
        head = (head + 1) % samples.size
        if (count < samples.size) count++
        total++
    }

    @Synchronized
    fun snapshot(): Summary {
        if (count == 0) return Summary(name, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, total)
        val sorted = samples.copyOf(count).also { it.sort() }
        var sum = 0.0
        for (v in sorted) sum += v
        val mean = sum / count
        var varSum = 0.0
        for (v in sorted) { val d = v - mean; varSum += d * d }
        fun pct(p: Double): Double = sorted[((count - 1) * p).toInt().coerceIn(0, count - 1)] / 1e6
        return Summary(
            name, count,
            meanMs = mean / 1e6,
            p50Ms = pct(0.50), p95Ms = pct(0.95), p99Ms = pct(0.99),
            minMs = sorted.first() / 1e6, maxMs = sorted.last() / 1e6,
            stdDevMs = sqrt(varSum / count) / 1e6,
            totalSamples = total,
        )
    }

    @Synchronized
    fun reset() { count = 0; head = 0; total = 0 }

    data class Summary(
        val name: String, val count: Int,
        val meanMs: Double, val p50Ms: Double, val p95Ms: Double, val p99Ms: Double,
        val minMs: Double, val maxMs: Double, val stdDevMs: Double, val totalSamples: Long,
    ) {
        fun format(): String = if (count == 0) "%-22s  no samples".format(name) else
            "%-22s n=%-4d mean=%.3f p50=%.3f p95=%.3f p99=%.3f min=%.3f max=%.3f sd=%.3f ms"
                .format(name, count, meanMs, p50Ms, p95Ms, p99Ms, minMs, maxMs, stdDevMs)
    }
}

/** Pipeline stages measured separately, as required by the latency methodology in docs/PERFORMANCE.md. */
enum class Stage(val label: String) {
    CAMERA_CAPTURE("camera capture->app"),
    HAND_INFERENCE("hand inference"),
    HAND_POSE("hand pose estimation"),
    SENSOR_FUSION("sensor fusion"),
    SIMULATION("simulation update"),
    LUA_FRAME("lua frame"),
    RENDER_CPU("render cpu"),
    FRAME_SUBMIT("frame submit"),
    FRAME_INTERVAL("frame interval"),
    INPUT_TO_PHOTON("input->frame submit"),
}

object Diagnostics {
    private val stats = Stage.entries.associateWith { LatencyStats(it.label) }
    private val events = ArrayDeque<String>()

    fun record(stage: Stage, nanos: Long) = stats.getValue(stage).record(nanos)
    fun stats(stage: Stage): LatencyStats = stats.getValue(stage)
    fun all(): List<LatencyStats.Summary> = Stage.entries.map { stats.getValue(it).snapshot() }
    fun resetAll() = stats.values.forEach { it.reset() }

    /** Short human-readable log for the "system messages" panel and diagnostics screen. */
    @Synchronized
    fun log(message: String) {
        events.addLast("${System.currentTimeMillis()} $message")
        while (events.size > 200) events.removeFirst()
    }

    @Synchronized
    fun recentEvents(): List<String> = events.toList()

    inline fun <T> measure(stage: Stage, block: () -> T): T {
        val t0 = System.nanoTime()
        try { return block() } finally { record(stage, System.nanoTime() - t0) }
    }
}
