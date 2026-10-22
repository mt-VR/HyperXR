package com.hyperxr

import com.hyperxr.input.StickCursor
import com.hyperxr.math.Pose
import com.hyperxr.math.Ray
import com.hyperxr.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StickCursorTest {
    @Test fun stickMovesCursorWithDeadZoneAndClamp() {
        val c = StickCursor()
        c.update(0.08f, -0.05f, 1f)
        assertEquals(0f, c.yawDeg, 0f); assertEquals(0f, c.pitchDeg, 0f)
        c.update(1f, 0f, 0.1f)
        assertEquals(7.5f, c.yawDeg, 0.01f)
        repeat(100) { c.update(1f, 1f, 0.1f) }
        assertEquals(c.maxYawDeg, c.yawDeg, 0f); assertEquals(c.maxPitchDeg, c.pitchDeg, 0f)
        // slow near the centre (fine aiming), full speed at the edge
        assertTrue(c.curve(0.3f) < 0.3f * 0.6f)
        assertEquals(1f, c.curve(1f), 1e-6f)
    }

    @Test fun rayGoesThroughCursorRelativeToHead() {
        val c = StickCursor()
        repeat(4) { c.update(1f, 0f, 0.1f) } // 30 degrees right
        val r = c.ray(Pose(Vec3(0f, 1.6f, 0f)), Ray())
        assertEquals(1.6f, r.origin.y, 1e-6f)
        assertEquals(0.5f, r.direction.x, 1e-3f) // sin 30
        assertEquals(-0.866f, r.direction.z, 1e-3f)
        c.recenter()
        val r2 = c.ray(Pose(Vec3()), Ray())
        assertEquals(-1f, r2.direction.z, 1e-6f)
    }
}
