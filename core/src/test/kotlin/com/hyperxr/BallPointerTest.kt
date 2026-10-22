package com.hyperxr

import com.hyperxr.input.BallPointer
import com.hyperxr.math.Ray
import com.hyperxr.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Test

class BallPointerTest {
    private val eye = Vec3(0f, 1.6f, 0f)

    /** Where the ray hits the plane z = -1.5 (a window 1.5 m away). */
    private fun hit(r: Ray): Vec3 { val t = (-1.5f - r.origin.z) / r.direction.z; return r.pointAt(t) }

    @Test fun targetIsWhatTheEyeSeesUnderTheBall() {
        val p = BallPointer()
        val ball = Vec3(0.1f, 1.4f, -0.5f)
        val h = hit(p.update(ball, false, eye, 0, Ray()))
        // the ball 0.5 m away projects 3x farther at 1.5 m
        assertEquals(0.3f, h.x, 1e-4f)
        assertEquals(1.6f - 0.6f, h.y, 1e-4f)
    }

    @Test fun closingTheFingersDoesNotMoveTheClick() {
        val p = BallPointer()
        var t = 0L
        val r = Ray()
        // hovering a button: the ball is steady
        repeat(20) { p.update(Vec3(0.1f, 1.4f, -0.5f), false, eye, t, r); t += 16_000_000L }
        val before = hit(r).copy()
        // the fingers close over 150 ms: the midpoint drifts 2 cm, then the pinch registers
        repeat(9) { k -> p.update(Vec3(0.1f + k * 0.0025f, 1.4f - k * 0.0012f, -0.5f), false, eye, t, r); t += 16_000_000L }
        p.update(Vec3(0.12f, 1.39f, -0.5f), true, eye, t, r)
        val atPress = hit(r)
        assertEquals(before.x, atPress.x, 0.01f)
        assertEquals(before.y, atPress.y, 0.01f)
        // dragging: the aim follows the hand from there
        t += 16_000_000L
        p.update(Vec3(0.17f, 1.39f, -0.5f), true, eye, t, r)
        assertEquals(before.x + 0.15f, hit(r).x, 0.01f)
    }
}
