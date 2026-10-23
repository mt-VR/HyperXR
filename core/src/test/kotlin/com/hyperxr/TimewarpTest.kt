package com.hyperxr

import com.hyperxr.math.Quat
import com.hyperxr.math.Timewarp
import com.hyperxr.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimewarpTest {
    private fun apply(m: FloatArray, v: Vec3) = Vec3(
        m[0] * v.x + m[3] * v.y + m[6] * v.z,
        m[1] * v.x + m[4] * v.y + m[7] * v.z,
        m[2] * v.x + m[5] * v.y + m[8] * v.z,
    )

    @Test
    fun matchesTheRotationBetweenRenderAndDisplay() {
        val rendered = Quat.fromAxisAngle(0f, 1f, 0f, 0.3f)
        val now = Quat.fromAxisAngle(1f, 0f, 0f, 0.05f).mul(Quat.fromAxisAngle(0f, 1f, 0f, 0.32f))
        val m = FloatArray(9)
        assertTrue(Timewarp.matrix(rendered, now, m))
        val dirNow = Vec3(0.2f, -0.1f, -1f).normalize()
        val expected = rendered.conjugate().rotate(now.rotate(dirNow))
        val got = apply(m, dirNow)
        assertEquals(expected.x, got.x, 1e-4f); assertEquals(expected.y, got.y, 1e-4f); assertEquals(expected.z, got.z, 1e-4f)
    }

    @Test
    fun noHeadMotionIsIdentityAndJumpsAreNotWarped() {
        val q = Quat.fromAxisAngle(0.3f, 0.8f, 0.1f, 1.2f)
        val m = FloatArray(9)
        assertTrue(Timewarp.matrix(q, q.copy(), m))
        val v = apply(m, Vec3(0.3f, 0.4f, -1f))
        assertEquals(0.3f, v.x, 1e-5f); assertEquals(0.4f, v.y, 1e-5f); assertEquals(-1f, v.z, 1e-5f)
        assertFalse(Timewarp.matrix(q, Quat.fromAxisAngle(0f, 1f, 0f, 1.5f).mul(q), m))
        assertEquals(1f, m[0], 0f); assertEquals(0f, m[1], 0f)
    }
}
