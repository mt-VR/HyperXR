package com.hyperxr

import com.hyperxr.headtracking.HeadTranslationPredictor
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Eye position at photon time from 30 Hz camera poses that are 40 ms old, with the photon time
 * 33 ms after now (73 ms ahead of the pose). The orientation at photon time is taken as known
 * (the gyro supplies it). "Old" is the earlier method: the camera's own smoothed velocity times
 * the span.
 */
class HeadTranslationTest {
    private val eye = Vec3(0f, 0.075f, -0.08f)

    private class Case(val yawAmpDeg: Float, val walk: Float, val noise: Float)

    private fun yaw(c: Case, t: Double) = Math.toRadians(c.yawAmpDeg * sin(2 * Math.PI * 0.7 * t)).toFloat()
    private fun truth(c: Case, t: Double, q: Quat, pos: Vec3) {
        Quat.fromAxisAngle(0f, 1f, 0f, yaw(c, t), q)
        pos.set(0f, 1.6f, (-c.walk * t).toFloat()).add(q.rotate(eye))
    }

    /** Returns RMS error (mm) and RMS second difference of the shown position (mm, at 60 Hz). */
    private fun run(c: Case, newMethod: Boolean): Pair<Float, Float> {
        val pr = HeadTranslationPredictor()
        val rnd = Random(11)
        val qPose = Quat(); val pPose = Vec3(); var poseT = 0.0
        val oldVel = Vec3(); val oldPrev = Vec3(); var oldPrevT = -1.0
        val qTrue = Quat(); val pTrue = Vec3(); val out = Vec3()
        val p1 = Vec3(); val p2 = Vec3(); var k = 0
        var err = 0.0; var ne = 0; var jit = 0.0; var nj = 0
        var nextPose = 0.0
        var t = 0.0
        while (t < 8.0) {
            if (t >= nextPose) {
                nextPose += 1 / 30.0
                poseT = t - 0.04
                truth(c, poseT, qPose, pPose)
                pPose.x += (rnd.nextGaussian() * c.noise).toFloat(); pPose.y += (rnd.nextGaussian() * c.noise).toFloat(); pPose.z += (rnd.nextGaussian() * c.noise).toFloat()
                pr.onPose(pPose, qPose, (poseT * 1e9).toLong())
                if (oldPrevT >= 0) {
                    val dt = (poseT - oldPrevT).toFloat()
                    oldVel.set(oldVel.x * 0.6f + (pPose.x - oldPrev.x) / dt * 0.4f, oldVel.y * 0.6f + (pPose.y - oldPrev.y) / dt * 0.4f, oldVel.z * 0.6f + (pPose.z - oldPrev.z) / dt * 0.4f)
                }
                oldPrev.set(pPose); oldPrevT = poseT
            }
            val target = t + 0.033
            truth(c, target, qTrue, pTrue)
            val ahead = (target - poseT).toFloat()
            if (newMethod) pr.predict(pPose, qPose, qTrue, ahead, out)
            else out.set(pPose).addScaled(oldVel, ahead.coerceIn(0f, 0.06f))
            if (t > 1.0) {
                val e = out.distanceTo(pTrue); err += (e * e).toDouble(); ne++
                if (k >= 2) {
                    val ax = out.x - 2 * p1.x + p2.x; val ay = out.y - 2 * p1.y + p2.y; val az = out.z - 2 * p1.z + p2.z
                    jit += (ax * ax + ay * ay + az * az).toDouble(); nj++
                }
                p2.set(p1); p1.set(out); k++
            }
            t += 1 / 60.0
        }
        return (sqrt(err / ne) * 1000).toFloat() to (sqrt(jit / nj) * 1000).toFloat()
    }

    @Test fun lookingAroundFollowsTheGyro() {
        val c = Case(40f, 0f, 0.001f)
        val old = run(c, false); val new = run(c, true)
        println("Looking around (±40° at 0.7 Hz): old error %.1f mm shake %.2f mm | new error %.1f mm shake %.2f mm".format(old.first, old.second, new.first, new.second))
        assertTrue(new.first < old.first * 0.5f)
    }

    @Test fun walkingStillPredicted() {
        val c = Case(0f, 0.8f, 0.001f)
        val old = run(c, false); val new = run(c, true)
        println("Walking 0.8 m/s: old error %.1f mm | new error %.1f mm".format(old.first, new.first))
        assertTrue(new.first < old.first * 1.2f)
    }

    @Test fun stillHeadDoesNotShake() {
        val c = Case(0f, 0f, 0.0015f)
        val old = run(c, false); val new = run(c, true)
        println("Still head (1.5 mm tracking noise): old shake %.2f mm | new shake %.2f mm".format(old.second, new.second))
        assertTrue(new.second < old.second * 0.6f)
    }
}
