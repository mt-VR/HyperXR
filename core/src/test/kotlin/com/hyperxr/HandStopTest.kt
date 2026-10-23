package com.hyperxr

import com.hyperxr.handtracking.DepthSource
import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.HandTracker
import com.hyperxr.handtracking.Handedness
import com.hyperxr.math.Vec3
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * A hand that reaches 20 cm in a quarter second and stops (and back), seen as a phone sees it:
 * 30 Hz, images 50 ms old, joint noise plus slowly wandering depth. The drawn hand must not swing
 * past where the real hand stopped and come back (that reads as the hand swaying).
 */
class HandStopTest {
    private val camera = Vec3(0f, 1.6f, 0f)

    private fun pos(t: Double): Float {
        val c = t % 2.5
        fun ease(u: Double) = u * u * (3 - 2 * u)
        return when {
            c < 0.25 -> (0.2 * ease(c / 0.25)).toFloat()
            c < 1.25 -> 0.2f
            c < 1.5 -> (0.2 * (1 - ease((c - 1.25) / 0.25))).toFloat()
            else -> 0f
        }
    }

    @Test fun stopsWithoutSwingingPast() {
        val tr = HandTracker(Handedness.RIGHT)
        tr.predictionSeconds = 0.05f * 0.5f
        val rnd = Random(3)
        val obs = Array(HandJoint.COUNT) { Vec3() }
        val shown = Array(HandJoint.COUNT) { Vec3() }
        var nextObs = 0.0; var t = 0.0
        var overshoot = 0.0; var depthErr = 0.0; var err = 0.0; var ne = 0
        while (t < 20.0) {
            if (t >= nextObs) {
                nextObs += 1 / 30.0
                val x = pos(t - 0.05)
                depthErr = depthErr * 0.9 + rnd.nextGaussian() * 0.0035
                for (i in 0 until HandJoint.COUNT) obs[i].set(x + i * 0.004f, 1.4f + (i % 5) * 0.02f, -0.4f)
                val ray = Vec3().set(obs[0]).sub(camera).normalize()
                for (o in obs) {
                    o.x += (rnd.nextGaussian() * 0.003).toFloat() + ray.x * depthErr.toFloat()
                    o.y += (rnd.nextGaussian() * 0.003).toFloat() + ray.y * depthErr.toFloat()
                    o.z += (rnd.nextGaussian() * 0.003).toFloat() + ray.z * depthErr.toFloat()
                }
                tr.update(obs, 0.9f, DepthSource.HAND_SIZE_PRIOR, (t * 1e9).toLong(), viewOrigin = camera)
            }
            if (t > 1.0) {
                tr.extrapolate((t - tr.state.timestampNs / 1e9).toFloat().coerceIn(0f, 0.05f), shown, 0.012f)
                val x = shown[HandJoint.WRIST].x
                val c = t % 2.5
                if (c in 0.25..1.25) overshoot = maxOf(overshoot, (x - 0.2).toDouble())
                if (c in 1.5..2.5) overshoot = maxOf(overshoot, (-x).toDouble())
                val e = x - pos(t + 0.012); err += e * e; ne++
            }
            t += 1 / 90.0
        }
        val overMm = overshoot * 1000; val errMm = Math.sqrt(err / ne) * 1000
        println("Reach and stop: swings past the stop by ${"%.1f".format(overMm)} mm, error ${"%.1f".format(errMm)} mm")
        // before damped coasting and half-age prediction: 68.9 mm past the stop, 30 mm error
        assertTrue("overshoot $overMm mm", overMm < 30.0)
        assertTrue("error $errMm mm", errMm < 33.0)
    }
}
