package com.hyperxr

import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.HandSide
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandSideTest {
    /** A relaxed right hand: palm down (-y), fingers forward (-z), thumb to the left, fingers a little bent. */
    private fun rightHand(): Array<Vec3> {
        val p = arrayOf(
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(-0.019f, -0.006f, -0.020f), floatArrayOf(-0.041f, -0.012f, -0.046f), floatArrayOf(-0.052f, -0.02f, -0.068f), floatArrayOf(-0.058f, -0.028f, -0.088f),
            floatArrayOf(-0.024f, 0f, -0.088f), floatArrayOf(-0.027f, -0.008f, -0.128f), floatArrayOf(-0.029f, -0.02f, -0.150f), floatArrayOf(-0.030f, -0.034f, -0.166f),
            floatArrayOf(-0.004f, 0f, -0.092f), floatArrayOf(-0.004f, -0.008f, -0.136f), floatArrayOf(-0.004f, -0.021f, -0.162f), floatArrayOf(-0.004f, -0.036f, -0.180f),
            floatArrayOf(0.015f, 0f, -0.087f), floatArrayOf(0.017f, -0.008f, -0.128f), floatArrayOf(0.019f, -0.02f, -0.152f), floatArrayOf(0.020f, -0.034f, -0.168f),
            floatArrayOf(0.032f, -0.002f, -0.078f), floatArrayOf(0.037f, -0.009f, -0.108f), floatArrayOf(0.040f, -0.018f, -0.125f), floatArrayOf(0.043f, -0.029f, -0.139f),
        )
        return Array(HandJoint.COUNT) { Vec3(p[it][0], p[it][1], p[it][2]) }
    }

    private fun mirror(h: Array<Vec3>) = Array(h.size) { Vec3(-h[it].x, h[it].y, h[it].z) }

    private fun turned(h: Array<Vec3>, q: Quat) = Array(h.size) { q.rotate(h[it].copy()) }

    @Test fun tellsRightFromLeftByShape() {
        assertTrue(HandSide.chirality(rightHand()) > 0.2f)
        assertTrue(HandSide.chirality(mirror(rightHand())) < -0.2f)
    }

    @Test fun shapeDoesNotDependOnHowTheHandIsTurned() {
        for (q in listOf(Quat.fromAxisAngle(0f, 0f, 1f, 3.14f), Quat.fromAxisAngle(1f, 0f, 0f, 1.2f), Quat.fromAxisAngle(0.3f, 1f, 0.2f, 2.2f))) {
            assertTrue(HandSide.chirality(turned(rightHand(), q)) > 0.2f)
            assertTrue(HandSide.chirality(turned(mirror(rightHand()), q)) < -0.2f)
        }
    }

    @Test fun aWrongLabelIsCorrectedAndOneBadFrameDoesNotFlip() {
        val d = HandSide.Decider()
        // a right hand on the right, labelled left by the detector
        assertTrue(d.update(false, rightHand(), 0.2f, 0.016f))
        // one frame of contrary evidence (a left-looking shape on the left) does not flip it
        assertTrue(d.update(false, mirror(rightHand()), -0.2f, 0.016f))
        // a steady contrary picture does, after a moment
        var r = true
        repeat(30) { r = d.update(false, mirror(rightHand()), -0.2f, 0.016f) }
        assertFalse(r)
    }

    @Test fun twoHandsOnOneSideAreSplitByPlace() {
        assertEquals(true to false, HandSide.separate(true, 0.1f, true, -0.05f))
        assertEquals(false to true, HandSide.separate(false, -0.2f, false, 0.05f))
    }
}
