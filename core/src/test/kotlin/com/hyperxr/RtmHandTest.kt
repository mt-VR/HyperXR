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
        // knuckles on a real palm's arc (index, middle, ring, pinky)
        val kx = floatArrayOf(-30f, 0f, 22f, 45f); val ky = floatArrayOf(8f, 0f, 9f, 24f)
        for (f in 0..3) {
            val b = 5 + f * 4; val x = kx[f]; val y = ky[f]
            set(b, x, y); set(b + 1, x, y - 45f * (1 - bend)); set(b + 2, x, y - 72f * (1 - bend)); set(b + 3, x, y - 92f * (1 - bend))
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
        assertTrue(z[8] - z[5] < 15f)                         // straight finger: little depth past its knuckle
        RtmHand.liftDepth(hand(bend = 0.6f), z)
        assertTrue(z[8] - z[5] > 30f)                         // bent: the tip is well behind the knuckle
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
        val dz = world[HandJoint.MIDDLE_MCP * 3 + 2] - world[2]
        assertEquals(0.09f, kotlin.math.sqrt(dx * dx + dy * dy + dz * dz), 1e-4f)
    }

    /** The palm template, 100 px long, tilted by [tilt] degrees about the image x axis or y axis, then rotated in the image. */
    private fun tiltedPalm(tilt: Float, aboutX: Boolean, roll: Float, mirror: Boolean = false): FloatArray {
        val tx = floatArrayOf(0f, -0.30f, 0f, 0.22f, 0.45f); val ty = floatArrayOf(0f, -0.92f, -1f, -0.91f, -0.76f)
        val joints = intArrayOf(0, 5, 9, 13, 17)
        val p = FloatArray(42)
        val c = kotlin.math.cos(Math.toRadians(tilt.toDouble())).toFloat()
        val cr = kotlin.math.cos(Math.toRadians(roll.toDouble())).toFloat(); val sr = kotlin.math.sin(Math.toRadians(roll.toDouble())).toFloat()
        for ((k, j) in joints.withIndex()) {
            var x = tx[k] * (if (mirror) -1 else 1) * 100f; var y = ty[k] * 100f
            if (aboutX) y *= c else x *= c
            p[j * 2] = 400f + x * cr - y * sr; p[j * 2 + 1] = 300f + x * sr + y * cr
        }
        return p
    }

    @Test fun palmSizeHoldsWhenThePalmTilts() {
        for (aboutX in listOf(true, false)) for (tilt in listOf(0f, 30f, 55f)) for (roll in listOf(0f, 40f, -110f)) for (mirror in listOf(false, true)) {
            assertEquals("tilt $tilt aboutX $aboutX roll $roll", 100f, RtmHand.palmScale(tiltedPalm(tilt, aboutX, roll, mirror)), 1.5f)
        }
        // wrist to middle knuckle alone would have read 57 px at 55 degrees
        val z = FloatArray(21)
        RtmHand.liftDepth(tiltedPalm(55f, true, 0f), z)
        assertEquals(kotlin.math.sqrt(100f * 100f - 57.36f * 57.36f), z[HandJoint.MIDDLE_MCP], 1.5f)
    }
}
