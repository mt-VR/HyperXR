package com.hyperxr

import com.hyperxr.headtracking.HeadStabilizer
import com.hyperxr.math.Quat
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class HeadStabilizerTest {
    private val dt = 1f / 60f

    /** Yaw [deg] plus a tremor: the head itself plus 9 Hz shake and sensor noise. */
    private fun sample(t: Float, deg: Float, shakeDeg: Float, rnd: java.util.Random): Quat {
        val shake = shakeDeg * sin(2 * Math.PI.toFloat() * 9f * t) + (rnd.nextGaussian().toFloat() * shakeDeg * 0.3f)
        return Quat.fromAxisAngle(0f, 1f, 0f, Math.toRadians((deg + shake).toDouble()).toFloat())
    }

    private fun yawDeg(q: Quat) = Math.toDegrees(2.0 * kotlin.math.atan2(q.y.toDouble(), q.w.toDouble())).toFloat()

    @Test
    fun stillHeadStopsShaking() {
        val s = HeadStabilizer().apply { strength = 0.6f }
        val rnd = java.util.Random(3)
        var rawSpread = 0f; var outSpread = 0f
        var prevRaw = 0f; var prevOut = 0f
        for (i in 0 until 600) {
            val q = sample(i * dt, 0f, 0.25f, rnd)
            val raw = yawDeg(q)
            s.apply(q, dt)
            val out = yawDeg(q)
            if (i > 60) { rawSpread += kotlin.math.abs(raw - prevRaw); outSpread += kotlin.math.abs(out - prevOut) }
            prevRaw = raw; prevOut = out
        }
        assertTrue("frame-to-frame shake cut at least 4x: raw $rawSpread, shown $outSpread", outSpread * 4 < rawSpread)
    }

    @Test
    fun fastTurnIsNotDelayed() {
        val s = HeadStabilizer().apply { strength = 1f }
        val rnd = java.util.Random(5)
        var worst = 0f
        for (i in 0 until 120) {
            val target = i * 3f   // 180 deg/s
            val q = sample(i * dt, target, 0f, rnd)
            s.apply(q, dt)
            if (i > 10) worst = maxOf(worst, kotlin.math.abs(yawDeg(q) - target))
        }
        assertTrue("fast turn within 1.3 deg of the sensor: $worst", worst < 1.3f)
    }

    @Test
    fun offChangesNothing() {
        val s = HeadStabilizer().apply { strength = 0f }
        val q = Quat.fromAxisAngle(0f, 1f, 0f, 0.3f)
        val before = q.copy()
        s.apply(q, dt); s.apply(q, dt)
        assertTrue(q.angleTo(before) < 1e-6f)
    }

    @Test
    fun correctionMapsSensorToShown() {
        val s = HeadStabilizer()
        val rnd = java.util.Random(7)
        for (i in 0 until 30) {
            val raw = sample(i * dt, i * 0.05f, 0.3f, rnd)
            val sensor = raw.copy()
            s.apply(raw, dt)
            val viaCorrection = s.correction.mul(sensor)
            assertTrue(viaCorrection.angleTo(raw) < 1e-3f)
        }
    }
}
