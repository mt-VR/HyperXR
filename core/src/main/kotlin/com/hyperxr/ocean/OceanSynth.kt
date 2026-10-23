package com.hyperxr.ocean

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Ocean app's sound, synthesised sample by sample (no recordings), stereo:
 *  - the sea: deep filtered noise that swells and breaks in slow, irregular sets of waves, plus a
 *    soft hiss of foam after each break; rougher seas are louder and lower;
 *  - rain: bright filtered noise with single drops ticking in it;
 *  - thunder: a crack (only for near strikes) and a long low rumble that rolls in a few swells,
 *    arriving after the flash by the strike's distance.
 * [volume] is the master level; the rest follows the app's state.
 */
class OceanSynth(private val rate: Int = 48000, seed: Long = 5) {
    @Volatile var volume = 0.7f
    @Volatile var sea = 1f
    @Volatile var storm = 0f
    @Volatile var rain = 0f
    private val pending = java.util.concurrent.ConcurrentLinkedQueue<FloatArray>()

    /** Thunder from a strike [distance] m away, starting after [delay] seconds. Any thread. */
    fun thunder(distance: Float, delay: Float) { pending += floatArrayOf(distance, delay) }

    private val rnd = java.util.Random(seed)
    private fun white() = rnd.nextFloat() * 2f - 1f

    private class Voice(val distance: Float, var delay: Float, rnd: java.util.Random) {
        var t = 0f
        var lp1 = 0f; var lp2 = 0f
        val rolls = FloatArray(5) { 0.5f + rnd.nextFloat() * 0.5f }
        val rollAt = FloatArray(5) { 0.3f + it * (0.6f + rnd.nextFloat() * 0.9f) }
        val length = 4f + (distance / 1500f).coerceAtMost(5f)
    }
    private val voices = ArrayList<Voice>()

    private val brown = FloatArray(2); private val seaLp = FloatArray(2); private val foamLp = FloatArray(2)
    private val rainLp = FloatArray(2); private val rainHi = FloatArray(2)
    private var setPhase = 0.0
    private var period = 8.0
    private var drop = 0f; private var dropFreq = 3000f; private var dropPhase = 0f; private var dropPan = 0.5f

    /** Fills [out] with [frames] interleaved stereo samples in -1..1. */
    fun render(out: FloatArray, frames: Int) {
        while (true) { val th = pending.poll() ?: break; if (voices.size < 6) voices += Voice(th[0], th[1], rnd) }
        val dt = 1f / rate
        val vol = volume; val seaAmt = sea; val st = storm; val rn = rain
        // filter coefficients scale with the sample rate (tuned at 48 kHz)
        val k = 48000f / rate
        for (i in 0 until frames) {
            // the wave period wanders a little so sets never repeat exactly
            period = (period + (rnd.nextDouble() - 0.5) * 0.0004 * k).coerceIn(6.5, 11.0)
            setPhase += dt / period
            val ph = setPhase % 1.0
            // a break: a quick rise, then a long fall
            val swell = (if (ph < 0.18) ph / 0.18 else exp(-(ph - 0.18) * 3.2)).toFloat()
            val wash = (0.35f + 0.65f * swell * swell) * (0.6f + st * 0.6f)
            var l = 0f; var r = 0f
            for (c in 0..1) {
                // brown noise (integrated white) for the body of the sea
                brown[c] = (brown[c] + white() * 0.02f * k) * (1f - 0.003f * k)
                val cutoff = (0.02f + 0.05f * swell + st * 0.02f) * k
                seaLp[c] += (brown[c] * 3f - seaLp[c]) * cutoff
                // foam hiss after the break: high-passed noise following the swell
                val w = white()
                foamLp[c] += (w - foamLp[c]) * 0.08f * k
                val foam = (w - foamLp[c]) * swell * swell * 0.05f * (1f + st)
                var s = (seaLp[c] * wash + foam) * seaAmt
                if (rn > 0f) {
                    // rain: noise without its lows, softened a little at the very top
                    val wr = white()
                    rainLp[c] += (wr - rainLp[c]) * 0.1f * k
                    rainHi[c] += ((wr - rainLp[c]) - rainHi[c]) * 0.6f
                    s += rainHi[c] * rn * 0.09f
                }
                if (c == 0) l = s else r = s
            }
            // single drops: short pitched ticks, panned
            if (rn > 0f && rnd.nextFloat() < rn * 0.0012f * k) { drop = 1f; dropFreq = 2000f + rnd.nextFloat() * 4000f; dropPan = rnd.nextFloat(); dropPhase = 0f }
            if (drop > 0.001f) {
                dropPhase += (2 * PI * dropFreq / rate).toFloat()
                val d = sin(dropPhase) * drop * 0.06f * rn
                l += d * (1f - dropPan); r += d * dropPan
                drop *= 1f - 0.004f * k
            }
            var th = 0f
            val it = voices.iterator()
            while (it.hasNext()) {
                val v = it.next()
                if (v.delay > 0f) { v.delay -= dt; continue }
                v.t += dt
                if (v.t > v.length) { it.remove(); continue }
                val near = (1500f / v.distance).coerceIn(0.15f, 1.6f)
                val n = white()
                // the rumble: very low noise, rolling in a few swells, darker when far
                v.lp1 += (n - v.lp1) * (0.004f + 0.01f * near.coerceAtMost(1f)) * k
                v.lp2 += (v.lp1 - v.lp2) * 0.02f * k
                var env = 0f
                for (j in 0..4) { val u = v.t - v.rollAt[j]; if (u > -0.2f) env += v.rolls[j] * exp(-u * u * 3f) }
                env *= exp(-v.t / v.length * 2f)
                th += v.lp2 * 60f * env * near
                // the crack of a near strike: a short burst of bright noise
                if (v.distance < 1300f && v.t < 0.25f) th += n * exp(-v.t * 18f) * 0.5f * near
            }
            out[i * 2] = softClip((l + th) * vol)
            out[i * 2 + 1] = softClip((r + th * 0.95f) * vol)
        }
    }

    private fun softClip(x: Float): Float = x / sqrt(1f + x * x)
}
