package com.hyperxr

import com.hyperxr.input.TapSynthesizer
import com.hyperxr.input.TapSynthesizer.Companion.DOWN
import com.hyperxr.input.TapSynthesizer.Companion.MOVE
import com.hyperxr.input.TapSynthesizer.Companion.UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TapSynthesizerTest {
    private data class Ev(val action: Int, val x: Float, val y: Float, val down: Long, val time: Long)

    @Test fun slowWobblyPinchBecomesACleanTap() {
        val t = TapSynthesizer()
        val out = ArrayList<Ev>()
        val sink = TapSynthesizer.Sink { a, x, y, d, e -> out += Ev(a, x, y, d, e) }
        t.down(500f, 300f, 1000, 1600)
        // 600 ms of a pinch with the pointer wobbling ~10 px
        for (i in 1..36) t.move(500f + (i % 3) * 5f, 300f - (i % 2) * 6f, 1000L + i * 16, sink)
        t.up(507f, 296f, 1600, sink)
        assertEquals(listOf(DOWN, UP), out.map { it.action })
        assertTrue(out.all { it.x == 500f && it.y == 300f })
        assertTrue("tap must be short: ${out[1].time - out[0].time}", out[1].time - out[0].time < 100)
    }

    @Test fun movingBecomesADragFromTheStartPoint() {
        val t = TapSynthesizer()
        val out = ArrayList<Ev>()
        val sink = TapSynthesizer.Sink { a, x, y, d, e -> out += Ev(a, x, y, d, e) }
        t.down(500f, 600f, 0, 1600)
        for (i in 1..20) t.move(500f, 600f - i * 10f, i * 16L, sink)
        t.up(500f, 400f, 340, sink)
        assertEquals(DOWN, out.first().action)
        assertEquals(600f, out.first().y, 0f)
        assertTrue(out.drop(1).dropLast(1).all { it.action == MOVE })
        assertEquals(UP, out.last().action)
        assertEquals(400f, out.last().y, 0f)
    }

    @Test fun holdingStillBecomesALongPress() {
        val t = TapSynthesizer()
        val out = ArrayList<Ev>()
        var long = false
        t.onLongHold = { _, _ -> long = true }
        val sink = TapSynthesizer.Sink { a, x, y, d, e -> out += Ev(a, x, y, d, e) }
        t.down(100f, 100f, 0, 1600)
        for (i in 1..80) t.move(101f, 100f, i * 16L, sink)
        assertTrue(long)
        assertEquals(DOWN, out.first().action)
        t.up(101f, 100f, 1400, sink)
        assertEquals(UP, out.last().action)
    }
}
