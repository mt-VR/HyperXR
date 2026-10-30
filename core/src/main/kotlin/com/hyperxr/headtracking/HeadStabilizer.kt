package com.hyperxr.headtracking

import com.hyperxr.math.Quat
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min

/**
 * Takes the tremble out of the head's rotation without making it lag: a One Euro filter on
 * rotation. While the head is nearly still, the view follows the sensor through a low cut-off, so
 * gyroscope noise and the small tremor of the hands holding the viewer do not shake the picture;
 * as soon as the head turns, the cut-off rises with the speed and the view follows at once.
 *
 * The filtered orientation is never more than [maxLagRad] behind the sensor, so a slow, deliberate
 * turn is not dragged either. [correction] maps the sensor's orientation to the shown one: the
 * timewarp applies it to the later sensor reading too, or it would put the tremble back.
 */
class HeadStabilizer {
    /** 0 (off) .. 1 (strongest). */
    var strength = 0.6f

    /** Shown orientation = correction * sensor orientation. */
    val correction = Quat()

    private val out = Quat()
    /** The sensor over the last ~[WINDOW_S] seconds, to tell a turn from a tremble. */
    private val past = Array(HISTORY) { Quat() }
    private val pastT = FloatArray(HISTORY)
    private var head = 0
    private var count = 0
    private var clock = 0f
    private var speed = 0f
    private var primed = false
    private val inv = Quat()

    fun reset() { primed = false; count = 0; correction.identity() }

    /** Filters [raw] in place; [dt] in seconds since the last call. */
    fun apply(raw: Quat, dt: Float) {
        if (strength <= 0f || dt <= 0f) { correction.identity(); return }
        clock += dt
        past[head].set(raw); pastT[head] = clock
        head = (head + 1) % HISTORY; count = min(HISTORY, count + 1)
        if (!primed) {
            out.set(raw); speed = 0f; primed = true
            correction.identity(); return
        }
        // the head's speed as its net turn over the last ~0.1 s: a tremble goes back and forth and
        // cancels out over that time, a real turn does not (so the tremble cannot open the filter)
        var oldest = (head - count + HISTORY) % HISTORY
        while (count > 1 && clock - pastT[oldest] > WINDOW_S) { oldest = (oldest + 1) % HISTORY; if (oldest == (head - 1 + HISTORY) % HISTORY) break }
        val span = clock - pastT[oldest]
        val v = if (span > 1e-4f) past[oldest].angleTo(raw) / span else 0f
        speed += (v - speed) * alpha(dt, SPEED_CUTOFF_HZ)
        val s = strength.coerceIn(0f, 1f)
        val minCutoff = MAX_MIN_CUTOFF_HZ + (MIN_MIN_CUTOFF_HZ - MAX_MIN_CUTOFF_HZ) * s
        // a slow drift of a few degrees per second is still "still": only real turns open it
        val cutoff = minCutoff + BETA * kotlin.math.max(0f, speed - STILL_RAD_S)
        out.slerp(raw, alpha(dt, cutoff), out)
        // never far behind: a deliberate slow turn is followed, only the tremble is taken out
        val lag = out.angleTo(raw)
        val maxLag = maxLagRad * s
        if (lag > maxLag && lag > 1e-6f) out.slerp(raw, 1f - maxLag / lag, out)
        out.normalize()
        // correction = out * raw^-1
        raw.conjugate(inv)
        out.mul(inv, correction)
        raw.set(out)
    }

    private fun alpha(dt: Float, hz: Float): Float = 1f - exp(-dt * 2f * PI.toFloat() * hz)

    /** At most this far behind the sensor (radians, at full strength). */
    var maxLagRad = Math.toRadians(1.2).toFloat()

    companion object {
        /** Cut-off while still: 0.6 Hz at full strength, 4 Hz at the weakest. */
        const val MIN_MIN_CUTOFF_HZ = 0.6f
        const val MAX_MIN_CUTOFF_HZ = 4f
        /** How fast the cut-off rises with the head's speed (Hz per rad/s). */
        const val BETA = 25f
        const val SPEED_CUTOFF_HZ = 8f
        const val WINDOW_S = 0.1f
        const val STILL_RAD_S = 0.05f
        private const val HISTORY = 16
    }
}
