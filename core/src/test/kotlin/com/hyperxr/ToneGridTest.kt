package com.hyperxr

import com.hyperxr.rendering.ToneGrid
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneGridTest {
    private val w = 128
    private val h = 72

    /** A dim room: a dark left half, a mid-grey right half, a bright window top right. */
    private fun dimRoom(): FloatArray = FloatArray(w * h * 3) { i ->
        val p = i / 3; val x = p % w; val y = p / w
        when {
            x > 100 && y < 20 -> 0.95f
            x < w / 2 -> 0.06f + 0.02f * ((x + y) % 3)
            else -> 0.22f
        }
    }

    private fun out(g: ToneGrid, img: FloatArray, x: Int, y: Int): Float {
        val i = (y * w + x) * 3
        val o = FloatArray(3)
        g.apply((x + 0.5f) / w, (y + 0.5f) / h, floatArrayOf(img[i], img[i + 1], img[i + 2]), o)
        return o[1]
    }

    @Test
    fun darkRoomIsLiftedAndHighlightsAreKept() {
        val img = dimRoom()
        val g = ToneGrid()
        g.fit(img, null, w, h)
        val dark = out(g, img, 20, 50)
        val mid = out(g, img, 90, 50)
        val window = out(g, img, 120, 5)
        assertTrue("shadows lifted: $dark", dark > 0.12f)
        assertTrue("mid tones brighter: $mid", mid > 0.3f)
        assertTrue("window not clipped away: $window", window in 0.8f..1f)
        assertTrue("order kept: $dark < $mid", mid > dark)
    }

    @Test
    fun brightSceneIsLeftAlmostAlone() {
        val img = FloatArray(w * h * 3) { 0.45f + 0.1f * ((it / 3) % 7) / 7f }
        val g = ToneGrid()
        g.fit(img, null, w, h)
        val i = (36 * w + 64) * 3
        val o = out(g, img, 64, 36)
        assertTrue("barely changed: ${img[i + 1]} -> $o", kotlin.math.abs(o - img[i + 1]) < 0.12f)
    }

    @Test
    fun strengthZeroIsIdentityLike() {
        val img = dimRoom()
        val g = ToneGrid().apply { strength = 0f }
        g.fit(img, null, w, h)
        val i = (50 * w + 20) * 3
        val o = out(g, img, 20, 50)
        assertTrue("off: ${img[i + 1]} -> $o", kotlin.math.abs(o - img[i + 1]) < 0.03f)
    }
}
