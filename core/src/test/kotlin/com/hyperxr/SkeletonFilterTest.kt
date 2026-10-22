package com.hyperxr

import com.hyperxr.handtracking.DepthSource
import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.HandSkeleton
import com.hyperxr.handtracking.HandTracker
import com.hyperxr.handtracking.Handedness
import com.hyperxr.handtracking.Kalman1D
import com.hyperxr.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkeletonFilterTest {
    private fun hand(c: Vec3, scale: Float = 1f): Array<Vec3> {
        val j = Array(HandJoint.COUNT) { Vec3() }
        j[0].set(c)
        val xs = floatArrayOf(-0.03f, -0.01f, 0.01f, 0.03f)
        val fingers = arrayOf(intArrayOf(5, 6, 7, 8), intArrayOf(9, 10, 11, 12), intArrayOf(13, 14, 15, 16), intArrayOf(17, 18, 19, 20))
        for ((f, idx) in fingers.withIndex()) for ((k, ji) in idx.withIndex()) j[ji].set(c.x + xs[f] * scale, c.y + (0.09f + 0.03f * k) * scale, c.z)
        for (k in 1..4) j[k].set(c.x - (0.03f + 0.015f * k) * scale, c.y + 0.02f * k * scale, c.z)
        return j
    }

    @Test fun kalmanTracksVelocityAndRemovesNoise() {
        val k = Kalman1D(q = 0.01f)
        val rnd = java.util.Random(3)
        var se = 0.0; var raw = 0.0; var n = 0
        for (i in 0 until 300) {
            val t = i / 60f
            val truth = 0.5f * t
            val z = truth + (rnd.nextFloat() - 0.5f) * 0.01f
            k.predict(1 / 60f)
            k.update(z, 0.003f * 0.003f)
            if (i > 60) { se += (k.x - truth).toDouble().let { it * it }; raw += (z - truth).toDouble().let { it * it }; n++ }
        }
        val rms = kotlin.math.sqrt(se / n); val rawRms = kotlin.math.sqrt(raw / n)
        assertTrue("noise not reduced: $rms vs raw $rawRms", rms < rawRms * 0.6)
        assertEquals(0.5f, k.v, 0.08f)
        // coasting with a damped velocity settles
        repeat(30) { k.predict(1 / 60f); k.dampVelocity(0.75f) }
        assertTrue(kotlin.math.abs(k.v) < 0.01f)
    }

    @Test fun skeletonKeepsBoneLengthsAndDirections() {
        val s = HandSkeleton()
        val ref = hand(Vec3(0f, 0f, -0.4f))
        s.learn(ref, trusted = true)
        // depth noise: the same hand observed 25% stretched
        val obs = hand(Vec3(0f, 0f, -0.4f), scale = 1.25f)
        val dirBefore = Vec3.sub(obs[HandJoint.INDEX_TIP], obs[HandJoint.INDEX_DIP]).normalize()
        s.fit(obs)
        for (c in 1 until HandJoint.COUNT) {
            val p = HandSkeleton.PARENT[c]
            assertEquals("bone $c", ref[c].distanceTo(ref[p]), obs[c].distanceTo(obs[p]), 1e-5f)
        }
        val dirAfter = Vec3.sub(obs[HandJoint.INDEX_TIP], obs[HandJoint.INDEX_DIP]).normalize()
        assertTrue(dirBefore.dot(dirAfter) > 0.9999f)
        // untrusted (size-prior) observations do not change the learned lengths
        val len = s.lengths[HandJoint.INDEX_TIP]
        s.learn(hand(Vec3(), scale = 2f), trusted = false)
        assertEquals(len, s.lengths[HandJoint.INDEX_TIP], 0f)
    }

    @Test fun trackerOutputKeepsHandSizeUnderDepthNoise() {
        val t = HandTracker(Handedness.RIGHT)
        var now = 0L
        t.update(hand(Vec3(0f, 0f, -0.4f)), 0.9f, DepthSource.DEPTH_MAP, now)
        val palm0 = t.state.joints[HandJoint.WRIST].distanceTo(t.state.joints[HandJoint.MIDDLE_MCP])
        val rnd = java.util.Random(9)
        var worst = 0f
        repeat(120) {
            now += 33_000_000L
            // the hand size wobbles ±15 % frame to frame (typical monocular depth noise)
            val scale = 1f + (rnd.nextFloat() - 0.5f) * 0.3f
            t.update(hand(Vec3(0f, 0f, -0.4f), scale), 0.9f, DepthSource.HAND_SIZE_PRIOR, now)
            val palm = t.state.joints[HandJoint.WRIST].distanceTo(t.state.joints[HandJoint.MIDDLE_MCP])
            worst = maxOf(worst, kotlin.math.abs(palm - palm0) / palm0)
        }
        assertTrue("hand size changed by ${worst * 100}%", worst < 0.01f)
    }
}
