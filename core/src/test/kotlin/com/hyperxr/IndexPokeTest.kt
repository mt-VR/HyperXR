package com.hyperxr

import com.hyperxr.handtracking.HandJoint
import com.hyperxr.input.PointPush
import com.hyperxr.math.Vec3
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexPokeTest {
    private val eye = Vec3(0f, 0f, 0f)

    /**
     * A right hand 40 cm in front of the eyes (towards -z), palm down. [indexAhead]: how far the
     * index tip reaches beyond the other fingertips; [thumbToIndex]: thumb tip distance.
     */
    private fun hand(z: Float, indexAhead: Float, thumbToIndex: Float = 0.06f): Array<Vec3> {
        val j = Array(HandJoint.COUNT) { Vec3() }
        j[HandJoint.WRIST].set(0f, -0.05f, z + 0.09f)
        for ((f, x) in listOf(HandJoint.INDEX_MCP to -0.02f, HandJoint.MIDDLE_MCP to 0f, HandJoint.RING_MCP to 0.018f, HandJoint.PINKY_MCP to 0.034f)) {
            j[f].set(x, -0.05f, z)
            val len = if (f == HandJoint.INDEX_MCP) 0.075f + indexAhead else 0.075f
            for (k in 1..3) j[f + k].set(x, -0.05f, z - len * k / 3f)
        }
        j[HandJoint.THUMB_CMC].set(-0.03f, -0.05f, z + 0.07f)
        j[HandJoint.THUMB_MCP].set(-0.04f, -0.05f, z + 0.04f)
        j[HandJoint.THUMB_IP].set(-0.045f, -0.05f, z + 0.01f)
        j[HandJoint.THUMB_TIP].set(-0.02f - thumbToIndex, -0.05f, j[HandJoint.INDEX_TIP].z)
        return j
    }

    private fun run(p: PointPush, frames: (Int) -> Array<Vec3>, n: Int): Boolean {
        var pressed = false
        for (i in 0 until n) if (p.update(frames(i), eye, false, i * 33_000_000L)) pressed = true
        return pressed
    }

    @Test
    fun leadingIndexPushClicks() {
        val p = PointPush().apply { leadingIndexCounts = true }
        // still for 10 frames, then 4 cm forward over 6 frames
        assertTrue(run(p, { i -> hand(-0.40f - (if (i < 10) 0f else minOf(0.04f, (i - 9) * 0.007f)), 0.03f) }, 20))
    }

    @Test
    fun openHandMovingForwardDoesNotClick() {
        val p = PointPush().apply { leadingIndexCounts = true }
        assertFalse(run(p, { i -> hand(-0.40f - (if (i < 10) 0f else minOf(0.05f, (i - 9) * 0.008f)), 0f) }, 22))
    }

    @Test
    fun pinchDoesNotClick() {
        val p = PointPush().apply { leadingIndexCounts = true }
        assertFalse(run(p, { i -> hand(-0.40f - (if (i < 10) 0f else minOf(0.05f, (i - 9) * 0.008f)), 0.03f, thumbToIndex = 0.01f) }, 22))
    }

    @Test
    fun withoutTheOptionAFlatLeadingIndexIsIgnored() {
        val p = PointPush()
        assertFalse(run(p, { i -> hand(-0.40f - (if (i < 10) 0f else minOf(0.04f, (i - 9) * 0.007f)), 0.03f) }, 20))
    }
}
