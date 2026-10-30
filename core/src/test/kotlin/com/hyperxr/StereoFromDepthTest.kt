package com.hyperxr

import com.hyperxr.media.StereoFromDepth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StereoFromDepthTest {
    @Test
    fun nearSquareMovesApartBetweenTheEyesAndFarBackgroundStays() {
        val w = 400; val h = 50
        // white background (far), a red square in the middle (near)
        val src = IntArray(w * h) { i -> val x = i % w; if (x in 180 until 220) 0xFFFF0000.toInt() else -1 }
        val near = FloatArray(w * h) { i -> if ((i % w) in 180 until 220) 1f else 0f }
        val (l, r) = StereoFromDepth.eyes(src, w, h, near, w, h)
        fun firstRed(p: IntArray) = (0 until w).first { p[25 * w + it] == 0xFFFF0000.toInt() }
        assertTrue("near moves right in the left eye, left in the right eye", firstRed(l) > 180 && firstRed(r) < 180)
        // no holes: every pixel has a colour
        assertTrue(l.none { it == 0 } && r.none { it == 0 })
        assertEquals(-1, l[25 * w + 5])
    }

    @Test
    fun normalizeIgnoresOutliers() {
        val d = FloatArray(1000) { it / 1000f } .also { it[0] = -500f; it[999] = 900f }
        val n = StereoFromDepth.normalize(d)
        assertTrue(n.all { it in 0f..1f })
        assertTrue(n[500] in 0.45f..0.55f)
    }
}
