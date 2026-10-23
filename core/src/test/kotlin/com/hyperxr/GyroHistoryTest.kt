package com.hyperxr

import com.hyperxr.headtracking.GyroHistory
import com.hyperxr.math.Quat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GyroHistoryTest {
    private fun angle(q: Quat) = 2f * kotlin.math.acos(kotlin.math.abs(q.w).coerceAtMost(1f))

    @Test fun constantTurnBetweenTwoTimes() {
        val g = GyroHistory()
        // 1 rad/s about Y, sampled at 500 Hz for 0.4 s
        for (i in 0..200) g.add(1_000_000_000L + i * 2_000_000L, 0f, 1f, 0f)
        val d = Quat()
        assertTrue(g.delta(1_100_000_000L, 1_300_000_000L, d))
        assertEquals(0.2f, angle(d), 1e-3f)
        assertTrue(d.y > 0f)
        // interpolated between samples
        assertTrue(g.delta(1_100_000_000L, 1_101_000_000L, d))
        assertEquals(0.001f, angle(d), 1e-4f)
    }

    @Test fun extrapolatesPastTheLastSample() {
        val g = GyroHistory()
        for (i in 0..100) g.add(i * 2_000_000L, 2f, 0f, 0f)
        val d = Quat()
        // 20 ms beyond the newest sample at 2 rad/s: 0.04 rad more
        assertTrue(g.delta(100 * 2_000_000L, 100 * 2_000_000L + 20_000_000L, d))
        assertEquals(0.04f, angle(d), 1e-3f)
        // extrapolation is capped (a stalled sensor must not spin the view)
        assertTrue(g.delta(100 * 2_000_000L, 100 * 2_000_000L + 500_000_000L, d))
        assertEquals(0.12f, angle(d), 1e-3f)
    }

    /**
     * Prediction 33 ms ahead (two 60 Hz frames) during a head turn that speeds up and slows down
     * (smooth 90° turn in 0.4 s, peak 6 rad/s), gyro at 400 Hz with 0.01 rad/s noise. Error
     * against the true orientation, worst and RMS over the turn; then the same with the head still
     * to check that the prediction does not add jitter.
     */
    private fun predictionError(secondOrder: Boolean, still: Boolean): Pair<Float, Float> {
        val g = GyroHistory().also { it.secondOrder = secondOrder }
        val rnd = java.util.Random(3)
        val dur = 0.4; val total = Math.PI / 2
        fun angleAt(t: Double): Double {
            if (still) return 0.0
            val u = (t / dur).coerceIn(0.0, 1.0)
            return total * (u - kotlin.math.sin(2 * Math.PI * u) / (2 * Math.PI))  // smooth start and stop
        }
        fun rateAt(t: Double): Double {
            if (still) return 0.0
            val u = t / dur
            return if (u < 0 || u > 1) 0.0 else total / dur * (1 - kotlin.math.cos(2 * Math.PI * u))
        }
        var worst = 0f; var sum = 0.0; var n = 0
        val p = Quat(); val tq = Quat()
        var t = -0.1
        while (t < dur + 0.1) {
            val ns = ((t + 1.0) * 1e9).toLong()
            g.add(ns, 0f, (rateAt(t) + rnd.nextGaussian() * 0.01).toFloat(), 0f)
            if (t > -0.05) {
                val ahead = 0.033
                val base = Quat(); g.at(ns, base)
                g.at(ns + (ahead * 1e9).toLong(), p)
                // predicted rotation from now to the photon time vs the truth
                val dPred = base.conjugate(Quat()).mul(p, Quat())
                val trueAngle = (angleAt(t + ahead) - angleAt(t)).toFloat()
                Quat.fromAxisAngle(0f, 1f, 0f, trueAngle, tq)
                val e = Math.toDegrees(dPred.angleTo(tq).toDouble()).toFloat()
                worst = maxOf(worst, e); sum += (e * e).toDouble(); n++
            }
            t += 1 / 400.0
        }
        return worst to kotlin.math.sqrt(sum / n).toFloat()
    }

    @Test fun secondOrderPredictionFollowsTurnsBetter() {
        val (oldWorst, oldRms) = predictionError(false, false)
        val (newWorst, newRms) = predictionError(true, false)
        val (oldStill, _) = predictionError(false, true)
        val (newStill, _) = predictionError(true, true)
        println("Gyro prediction 33 ms: constant rate worst %.2f° rms %.2f° | second order worst %.2f° rms %.2f° | still: %.3f° vs %.3f°".format(oldWorst, oldRms, newWorst, newRms, oldStill, newStill))
        assertTrue(newRms < oldRms * 0.5f)
        assertTrue(newWorst < oldWorst)
        assertTrue("still head must stay still: $newStill", newStill < 0.1f)
    }
}
