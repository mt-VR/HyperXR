package com.hyperxr

import com.hyperxr.guardian.FloorRay
import com.hyperxr.guardian.GuardianArea
import com.hyperxr.guardian.GuardianWarning
import com.hyperxr.guardian.ObstacleMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class GuardianTest {
    /** A shaky hand tracing a 3 x 2 m rectangle, overshooting the start at the end. */
    private fun traceRect(a: GuardianArea) {
        val rnd = java.util.Random(2)
        a.beginTrace()
        val corners = arrayOf(floatArrayOf(-1.5f, -1f), floatArrayOf(1.5f, -1f), floatArrayOf(1.5f, 1f), floatArrayOf(-1.5f, 1f), floatArrayOf(-1.5f, -1f), floatArrayOf(-1.2f, -1f))
        for (c in 0 until corners.size - 1) {
            val p = corners[c]; val q = corners[c + 1]
            for (s in 0 until 40) {
                val t = s / 40f
                a.addTracePoint(p[0] + (q[0] - p[0]) * t + (rnd.nextFloat() - 0.5f) * 0.03f, p[1] + (q[1] - p[1]) * t + (rnd.nextFloat() - 0.5f) * 0.03f)
            }
        }
    }

    @Test fun tracedAreaBecomesACleanOutline() {
        val a = GuardianArea()
        traceRect(a)
        assertTrue(a.traceCanClose())
        assertTrue(a.finishTrace())
        assertEquals(6f, a.area(), 0.35f)
        assertTrue(a.size in 4..12)                          // a few corners, not hundreds of points
        assertEquals(1f, a.signedDistance(0f, 0f), 0.06f)    // centre: 1 m to the long sides
        assertTrue(a.signedDistance(2f, 0f) < -0.4f)         // outside: negative
        assertTrue(a.contains(1.3f, 0.8f))
        assertFalse(a.contains(1.7f, 0f))
    }

    @Test fun aScribbleIsNotAnArea() {
        val a = GuardianArea()
        a.beginTrace()
        for (i in 0 until 30) a.addTracePoint(i * 0.01f, (i % 2) * 0.02f)
        assertFalse(a.finishTrace())
        assertFalse(a.valid)
    }

    @Test fun aLoopDrawnByMistakeIsCutOff() {
        // a circle of radius 1.5 m with a small extra loop drawn halfway
        val p = ArrayList<Float>()
        for (i in 0..60) {
            val ang = i / 60f * 6.2832f
            p += 1.5f * cos(ang); p += 1.5f * sin(ang)
            if (i == 30) for (k in 0..12) { val b = k / 12f * 6.2832f; p += -1.5f + 0.2f * cos(b) - 0.2f; p += 0.2f * sin(b) }
        }
        val clean = GuardianArea.removeLoops(p.toFloatArray())
        val a = GuardianArea().also { it.set(clean) }
        assertEquals(Math.PI.toFloat() * 1.5f * 1.5f, a.area(), 0.4f)
    }

    private fun wallPoints(map: ObstacleMap, times: Int, extra: (MutableList<FloatArray>) -> Unit = {}) {
        repeat(times) {
            val pts = ArrayList<FloatArray>()
            // a wall 2 m in front (z = -2), floor at 0, eyes at 1.6
            for (x in -10..10) for (y in 1..20) pts += floatArrayOf(x * 0.1f, y * 0.1f, -2f)
            extra(pts)
            val flat = FloatArray(pts.size * 3)
            pts.forEachIndexed { i, v -> flat[i * 3] = v[0]; flat[i * 3 + 1] = v[1]; flat[i * 3 + 2] = v[2] }
            map.integrate(flat, pts.size, 0f, 0f, 0f, 1.6f, 0f, 0f)
        }
    }

    @Test fun wallsAreFoundAfterSeveralScans() {
        val m = ObstacleMap()
        wallPoints(m, 1)
        assertTrue(m.nearest(0f, 0f)[0].isNaN())             // one scan is not enough
        wallPoints(m, 3)
        assertEquals(2f, m.nearest(0f, 0f)[0], 0.06f)
        assertEquals(0.5f, m.nearest(0f, -1.5f)[0], 0.06f)
    }

    @Test fun ownBodyFloorAndCeilingAreIgnored() {
        val m = ObstacleMap()
        repeat(5) {
            val pts = floatArrayOf(
                0.2f, 1.0f, -0.2f,     // your own arm, 28 cm away
                1f, 0.05f, -1f,        // the floor
                1f, 2.2f, -1f,         // a lamp near the ceiling
            )
            m.integrate(pts, 3, 0f, 0f, 0f, 1.6f, 0f, 0f)
        }
        val n = m.nearest(0f, 0f)
        assertTrue(n[0].isNaN() && n[1].isNaN())
        // a hand stretched out 60 cm is ignored around the tracked joints
        val m2 = ObstacleMap()
        repeat(5) { m2.integrate(floatArrayOf(0f, 1.2f, -0.6f), 1, 0f, 0f, 0f, 1.6f, 0f, 0f, floatArrayOf(0f, 1.2f, -0.62f), 1) }
        assertTrue(m2.nearest(0f, 0f)[0].isNaN())
    }

    @Test fun aBedIsLowFurnitureNotAWall() {
        val m = ObstacleMap()
        repeat(5) {
            val pts = ArrayList<Float>()
            // bed top at 0.55 m, from 1 to 3 m in front
            for (x in -8..8) for (z in 10..30) { pts += x * 0.1f; pts += 0.55f; pts += -z * 0.1f }
            m.integrate(pts.toFloatArray(), pts.size / 3, 0f, 0f, 0f, 1.6f, 0f, 0f)
        }
        val n = m.nearest(0f, 0f)
        assertTrue(n[0].isNaN())                             // not a wall
        assertEquals(1f, n[1], 0.06f)                        // low obstacle 1 m away
        // standing right next to it (within 0.5 m) it is not counted
        assertTrue(m.nearest(0f, -0.7f)[1].isNaN() || m.nearest(0f, -0.7f)[1] > 0.5f)
        // the warning: low furniture never brings the camera fully
        val w = GuardianWarning()
        repeat(100) { w.update(0.02f, Float.NaN, 0.1f, Float.NaN) }
        assertTrue(w.camera < 0.7f && w.grid > 0.5f)
    }

    @Test fun somethingThatMovedAwayIsForgotten() {
        val m = ObstacleMap()
        // a person 1 m in front for a few scans
        repeat(5) {
            val pts = ArrayList<Float>()
            for (y in 2..17) { pts += 0f; pts += y * 0.1f; pts += -1f }
            m.integrate(pts.toFloatArray(), pts.size / 3, 0f, 0f, 0f, 1.6f, 0f, 0f)
        }
        assertEquals(1f, m.nearest(0f, 0f)[0], 0.06f)
        // then the wall behind, seen through where the person was
        wallPoints(m, 8)
        assertEquals(2f, m.nearest(0f, 0f)[0], 0.06f)
    }

    @Test fun warningRisesFastAndOutsideShowsTheCamera() {
        val w = GuardianWarning()
        repeat(30) { w.update(0.02f, 0.1f, Float.NaN, Float.NaN) }
        assertTrue(w.grid > 0.8f)
        val o = GuardianWarning()
        repeat(20) { o.update(0.02f, Float.NaN, Float.NaN, 0.3f) }   // 30 cm outside the area
        assertTrue(o.camera > 0.95f)
        val far = GuardianWarning()
        repeat(50) { far.update(0.02f, 3f, Float.NaN, -1.5f) }
        assertTrue(far.grid < 0.01f && far.camera < 0.01f)
    }

    @Test fun pointingAtTheFloor() {
        val h = FloorRay.hit(0f, 1.3f, 0f, 0f, -1f, -1f, 0f)!!
        assertEquals(0f, h[0], 1e-4f); assertEquals(-1.3f, h[1], 1e-4f)
        assertTrue(FloorRay.hit(0f, 1.3f, 0f, 0f, 0.2f, -1f, 0f) == null)
    }
}
