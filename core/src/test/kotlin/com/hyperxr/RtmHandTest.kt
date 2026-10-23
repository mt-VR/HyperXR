package com.hyperxr

import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.RtmHand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RtmHandTest {
    /** A flat open hand (back towards the camera, right hand: thumb on the left), fingers up. */
    private fun hand(right: Boolean = true, bend: Float = 0f): FloatArray {
        val p = FloatArray(HandJoint.COUNT * 2)
        val s = if (right) 1f else -1f
        fun set(j: Int, x: Float, y: Float) { p[j * 2] = 320f + x * s; p[j * 2 + 1] = 300f + y }
        set(0, 0f, 100f)
        set(1, -30f, 80f); set(2, -55f, 55f); set(3, -70f, 30f); set(4, -80f, 8f)
        // palm length 100 px (wrist to middle knuckle); a bent finger looks shorter
        for ((f, x) in floatArrayOf(-30f, -10f, 10f, 30f).withIndex()) {
            val b = 5 + f * 4
            set(b, x, 0f); set(b + 1, x, -45f * (1 - bend)); set(b + 2, x, -72f * (1 - bend)); set(b + 3, x, -92f * (1 - bend))
        }
        return p
    }

    @Test fun decodesSimccBinsIntoTheCrop() {
        val sx = FloatArray(21 * 512); val sy = FloatArray(21 * 512)
        for (j in 0 until 21) { sx[j * 512 + 256] = 0.9f; sy[j * 512 + 128] = 0.8f }   // joint at (128, 64) of the 256 crop
        val out = FloatArray(42); val conf = FloatArray(21)
        RtmHand.decode(sx, sy, RtmHand.Crop(500f, 400f, 200f), out, conf)
        assertEquals(500f, out[0], 1e-3f)                    // centre column
        assertEquals(350f, out[1], 1e-3f)                    // a quarter down the crop
        assertEquals(0.8f, conf[0], 1e-6f)
    }

    @Test fun presenceSeparatesHandsFromBackground() {
        assertTrue(RtmHand.presence(FloatArray(21) { 0.70f }) > 0.9f)
        assertTrue(RtmHand.presence(FloatArray(21) { 0.64f }) > 0.85f)
        assertTrue(RtmHand.presence(FloatArray(21) { 0.40f }) == 0f)
        assertTrue(RtmHand.presence(FloatArray(21) { 0.50f }) < 0.3f)
    }

    @Test fun cropFollowsTheJointsAndNeverCollapses() {
        val c = RtmHand.cropFromJoints(hand())
        assertTrue(c.side >= 260f)                            // fist-proof minimum: 2.6 palms
        val fist = hand(bend = 0.95f)
        assertTrue(RtmHand.cropFromJoints(fist).side >= 260f)
    }

    @Test fun bentFingersGoAwayFromTheCamera() {
        val z = FloatArray(21)
        RtmHand.liftDepth(hand(), z)
        assertTrue(z[8] < 15f)                                // straight finger: little depth
        RtmHand.liftDepth(hand(bend = 0.6f), z)
        assertTrue(z[8] > 30f)                                // bent: the tip is well behind the knuckle
        assertTrue(z[12] > z[10])
    }

    @Test fun windingTellsRightFromLeft() {
        assertTrue(RtmHand.rightScore(hand(right = true)) > 0.7f)
        assertTrue(RtmHand.rightScore(hand(right = false)) < 0.3f)
    }

    @Test fun pipelineFormHasAPalmOf9cm() {
        val image = FloatArray(63); val world = FloatArray(63)
        RtmHand.toPipeline(hand(), 640, 480, image, world)
        assertEquals(320f / 640f, image[0], 1e-4f)
        val dx = world[HandJoint.MIDDLE_MCP * 3] - world[0]; val dy = world[HandJoint.MIDDLE_MCP * 3 + 1] - world[1]
        assertEquals(0.09f, kotlin.math.hypot(dx, dy), 1e-4f)
    }
}
