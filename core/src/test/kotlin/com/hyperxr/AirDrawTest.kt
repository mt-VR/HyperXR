package com.hyperxr

import com.hyperxr.rendering.StrokeSampler
import com.hyperxr.rendering.TubeGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class AirDrawTest {
    @Test fun tubeSurroundsTheLineAtItsRadius() {
        // a quarter circle, so the frames have to turn without twisting
        val pts = FloatArray(30 * 3) { k -> val i = k / 3; val a = i / 29f * 1.57f; when (k % 3) { 0 -> cos(a) * 0.3f; 1 -> 1.2f; else -> sin(a) * 0.3f } }
        val m = TubeGeometry.build(pts, 0.01f, sides = 6)!!
        assertEquals((30 * 6 + 2) * 8, m.vertices.size)
        assertEquals(29 * 6 * 6 + 2 * 6 * 3, m.indices.size)
        for (i in 0 until 30) for (s in 0 until 6) {
            val o = (i * 6 + s) * 8
            val dx = m.vertices[o] - pts[i * 3]; val dy = m.vertices[o + 1] - pts[i * 3 + 1]; val dz = m.vertices[o + 2] - pts[i * 3 + 2]
            assertEquals(0.01f, sqrt(dx * dx + dy * dy + dz * dz), 1e-4f)
            val nl = sqrt(m.vertices[o + 3] * m.vertices[o + 3] + m.vertices[o + 4] * m.vertices[o + 4] + m.vertices[o + 5] * m.vertices[o + 5])
            assertEquals(1f, nl, 1e-3f)
        }
        assertTrue(m.indices.all { it in 0 until 30 * 6 + 2 })
        assertNull(TubeGeometry.build(floatArrayOf(0f, 0f, 0f), 0.01f))
    }

    @Test fun samplerKeepsStepsAndSmooths() {
        val s = StrokeSampler(step = 0.01f, smoothing = 0.5f)
        assertTrue(s.add(0f, 0f, 0f))
        assertTrue(!s.add(0.001f, 0f, 0f))           // too close: no new point
        repeat(40) { s.add(it * 0.005f, 0f, 0f) }
        val p = s.toArray()
        for (i in 1 until p.size / 3) assertTrue(abs(p[i * 3] - p[i * 3 - 3]) >= 0.0099f)
    }
}
