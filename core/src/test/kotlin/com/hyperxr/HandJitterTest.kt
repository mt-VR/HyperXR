package com.hyperxr

import com.hyperxr.handtracking.DepthSource
import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.HandTracker
import com.hyperxr.handtracking.Handedness
import com.hyperxr.math.Vec3
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tremor and lag of the hand tracker on synthetic observations that look like a phone's: 30 Hz,
 * each joint with 3 mm noise, the whole hand with 8 mm of common depth noise along the camera ray
 * (depth from the hand-size prior), images 50 ms old when the result arrives. The display samples
 * the hand at 90 Hz, as the renderer does.
 */
class HandJitterTest {
    private val camera = Vec3(0f, 1.6f, 0f)

    private fun template(): Array<Vec3> {
        val j = Array(HandJoint.COUNT) { Vec3() }
        j[HandJoint.WRIST].set(0f, 0f, 0f)
        val cols = floatArrayOf(0.025f, 0.005f, -0.013f, -0.03f)
        for ((f, cx) in cols.withIndex()) {
            val mcp = HandJoint.INDEX_MCP + f * 4
            j[mcp].set(cx, 0.08f, 0f); j[mcp + 1].set(cx, 0.12f, -0.005f); j[mcp + 2].set(cx, 0.145f, -0.01f); j[mcp + 3].set(cx, 0.165f, -0.015f)
        }
        for (k in 1..4) j[k].set(0.03f + 0.012f * k, 0.02f * k, -0.005f * k)
        return j
    }

    private val shape = template()

    /** True wrist position at time t (s): 40 cm ahead, swaying sideways with [amp] at [hz]. */
    private fun root(t: Double, amp: Float, hz: Float) = Vec3(amp * sin(2 * PI * hz * t).toFloat(), 1.4f, -0.4f)

    private fun truth(t: Double, amp: Float, hz: Float, out: Array<Vec3>) {
        val r = root(t, amp, hz)
        for (i in 0 until HandJoint.COUNT) out[i].set(shape[i]).add(r)
    }

    class Result(val jitterMm: Float, val errorMm: Float)

    /**
     * Runs 10 s. [display] samples the hand for the screen at time t from the tracker (latest
     * update at its own time), returning the joints the renderer would draw.
     */
    private fun run(amp: Float, hz: Float, display: (HandTracker, Double, Array<Vec3>) -> Unit): Result {
        val tracker = HandTracker(Handedness.RIGHT)
        tracker.predictionSeconds = 0.05f * 0.75f
        val rnd = Random(7)
        val obs = Array(HandJoint.COUNT) { Vec3() }
        val shown = Array(HandJoint.COUNT) { Vec3() }
        val real = Array(HandJoint.COUNT) { Vec3() }
        val prev = Vec3(); val prev2 = Vec3(); var havePrev = 0
        var jitter = 0.0; var nj = 0; var err = 0.0; var ne = 0
        var nextObs = 0.0
        var t = 0.0
        while (t < 10.0) {
            if (t >= nextObs) {
                nextObs += 1 / 30.0
                truth(t - 0.05, amp, hz, obs)    // the image is 50 ms old
                val ray = Vec3().set(obs[0]).sub(camera).normalize()
                val common = (rnd.nextGaussian() * 0.008).toFloat()
                for (o in obs) {
                    o.x += (rnd.nextGaussian() * 0.003).toFloat() + ray.x * common
                    o.y += (rnd.nextGaussian() * 0.003).toFloat() + ray.y * common
                    o.z += (rnd.nextGaussian() * 0.003).toFloat() + ray.z * common
                }
                tracker.update(obs, 0.9f, DepthSource.HAND_SIZE_PRIOR, (t * 1e9).toLong(), viewOrigin = camera)
            }
            if (t > 1.0) {
                display(tracker, t, shown)
                val tip = shown[HandJoint.INDEX_TIP]
                // shaking = second difference (constant motion gives ~0; a still hand's noise shows up)
                if (havePrev >= 2) {
                    val ax = tip.x - 2 * prev.x + prev2.x; val ay = tip.y - 2 * prev.y + prev2.y; val az = tip.z - 2 * prev.z + prev2.z
                    jitter += (ax * ax + ay * ay + az * az).toDouble(); nj++
                }
                prev2.set(prev); prev.set(tip); havePrev++
                truth(t + DISPLAY_AHEAD, amp, hz, real)
                val e = tip.distanceTo(real[HandJoint.INDEX_TIP]); err += (e * e).toDouble(); ne++
            }
            t += 1 / 90.0
        }
        return Result((sqrt(jitter / nj) * 1000).toFloat(), (sqrt(err / ne) * 1000).toFloat())
    }

    private val DISPLAY_AHEAD = 0.012

    /** What the app draws: the tracker's latest state carried forward to the display time. */
    private fun shown(tr: HandTracker, t: Double, out: Array<Vec3>) {
        val since = (t - tr.state.timestampNs / 1e9).toFloat().coerceIn(0f, 0.05f)
        tr.extrapolate(since, out, DISPLAY_AHEAD.toFloat())
    }

    @Test fun stillHandDoesNotTremble() {
        val r = run(0f, 1f, ::shown)
        println("Still hand: tremor ${"%.2f".format(r.jitterMm)} mm at 90 Hz, error ${"%.1f".format(r.errorMm)} mm")
        // the tracker before this rewrite: 9.61 mm tremor, 9.5 mm error
        assertTrue("tremor ${r.jitterMm} mm", r.jitterMm < 0.45f)
    }

    @Test fun movingHandFollowsClosely() {
        val r = run(0.15f, 1f, ::shown)
        println("Moving hand (0.94 m/s peak): tremor ${"%.2f".format(r.jitterMm)} mm, error ${"%.1f".format(r.errorMm)} mm")
        // the tracker before this rewrite: 66.6 mm error; before continuous blending: 11.6 mm tremor
        assertTrue("error ${r.errorMm} mm", r.errorMm < 40f)
        assertTrue("tremor ${r.jitterMm} mm", r.jitterMm < 8f)
    }
}
