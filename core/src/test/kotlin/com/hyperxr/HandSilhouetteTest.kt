package com.hyperxr

import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.HandSilhouette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandSilhouetteTest {
    /** An open hand in the middle of a 4:3 picture, fingers pointing up. */
    private fun hand(): FloatArray {
        val j = FloatArray(HandJoint.COUNT * 3)
        fun set(i: Int, x: Float, y: Float) { j[i * 3] = x; j[i * 3 + 1] = y }
        set(0, 0.5f, 0.75f)
        set(1, 0.44f, 0.70f); set(2, 0.40f, 0.65f); set(3, 0.37f, 0.60f); set(4, 0.35f, 0.56f)
        val xs = floatArrayOf(0.44f, 0.49f, 0.54f, 0.59f)
        for ((f, x) in xs.withIndex()) { val b = 5 + f * 4; set(b, x, 0.55f); set(b + 1, x, 0.47f); set(b + 2, x, 0.42f); set(b + 3, x, 0.38f) }
        return j
    }

    @Test fun coversTheHandAndNothingAround() {
        val n = 256
        val m = ByteArray(n * n)
        HandSilhouette.raster(hand(), 4f / 3f, n, m)
        fun at(x: Float, y: Float) = m[(y * n).toInt() * n + (x * n).toInt()].toInt() and 0xff
        assertEquals(255, at(0.49f, 0.62f))      // palm
        assertEquals(255, at(0.54f, 0.45f))      // a finger
        // between two spread fingertips: below what the cutout shader ever draws (0.45), whatever the AI says
        assertTrue(at(0.515f, 0.40f) < (0.45f * 255).toInt())
        assertEquals(255, at(0.54f, 0.38f))      // a fingertip itself is fully inside
        assertEquals(0, at(0.70f, 0.60f))        // background beside the hand
        assertEquals(0, at(0.5f, 0.95f))         // the arm further down
        assertTrue(at(0.5f, 0.80f) > 0)          // the wrist
    }
}
