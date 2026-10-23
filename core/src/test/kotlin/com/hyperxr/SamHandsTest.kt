package com.hyperxr

import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.SamHands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SamHandsTest {
    /** An open hand around (cx, cy) in a 640x480 frame, normalised. */
    private fun hand(cx: Float, cy: Float, scale: Float = 1f): FloatArray {
        val j = FloatArray(HandJoint.COUNT * 2)
        fun set(i: Int, x: Float, y: Float) { j[i * 2] = (cx + x * scale) / 640f; j[i * 2 + 1] = (cy + y * scale) / 480f }
        set(0, 0f, 60f)
        set(1, -25f, 45f); set(2, -40f, 30f); set(3, -50f, 15f); set(4, -58f, 2f)
        for ((f, x) in floatArrayOf(-24f, -8f, 8f, 24f).withIndex()) { val b = 5 + f * 4; set(b, x, 0f); set(b + 1, x, -25f); set(b + 2, x, -40f); set(b + 3, x, -52f) }
        return j
    }

    @Test fun cropHoldsTheHandsAndPromptsLandOnThem() {
        val h = hand(320f, 240f)
        val c = SamHands.crop(listOf(h), 640, 480)
        assertTrue(c.x0 < 320f - 58f && c.x0 + c.side > 320f + 24f)
        assertTrue(c.y0 < 240f - 52f && c.y0 + c.side > 240f + 60f)
        val p = FloatArray(16); val l = FloatArray(8)
        SamHands.prompts(h, c, 640, 480, p, l)
        assertEquals(2f, l[0]); assertEquals(3f, l[1]); for (i in 2 until 8) assertEquals(1f, l[i])
        // all inside the 1024 space; the box contains every point
        for (i in 0 until 8) for (k in 0..1) assertTrue(p[i * 2 + k] in 0f..1024f)
        for (i in 2 until 8) { assertTrue(p[i * 2] > p[0] && p[i * 2] < p[2]); assertTrue(p[i * 2 + 1] > p[1] && p[i * 2 + 1] < p[3]) }
    }

    /** A network mask that is a disc where the hand was: composed at the hand's new place and size. */
    @Test fun maskFollowsTheHandBetweenRuns() {
        val then = hand(200f, 240f)
        val c = SamHands.crop(listOf(then), 640, 480)
        val a0 = SamHands.anchor(then, 640, 480)
        val logits = FloatArray(SamHands.MASK_SIZE * SamHands.MASK_SIZE) { i ->
            val x = c.x0 + (i % SamHands.MASK_SIZE + 0.5f) / SamHands.MASK_SIZE * c.side
            val y = c.y0 + (i / SamHands.MASK_SIZE + 0.5f) / SamHands.MASK_SIZE * c.side
            if (kotlin.math.hypot(x - a0.x, y - a0.y) < 40f) 8f else -8f
        }
        val out = ByteArray(256 * 256)
        val now = hand(420f, 260f, scale = 1.5f)            // moved right and came closer
        SamHands.compose(listOf(SamHands.HandMask(c, logits, a0)), listOf(SamHands.anchor(now, 640, 480)), 640, 480, 256, out)
        fun at(fx: Float, fy: Float) = out[(fy / 480f * 256).toInt() * 256 + (fx / 640f * 256).toInt()].toInt() and 0xff
        val a1 = SamHands.anchor(now, 640, 480)
        assertTrue(at(a1.x, a1.y) > 240)                    // the disc is at the hand's new place
        assertTrue(at(a1.x + 50f, a1.y) > 240)             // and 1.5x as big (60 px radius now)
        assertTrue(at(a1.x + 72f, a1.y) < 15)
        assertTrue(at(a0.x, a0.y) < 15)                    // nothing left where it was
    }
}
