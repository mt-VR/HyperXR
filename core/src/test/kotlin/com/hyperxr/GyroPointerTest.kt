package com.hyperxr

import com.hyperxr.input.GyroPointer
import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GyroPointerTest {
    private fun spin(p: GyroPointer, gx: Float, gy: Float, gz: Float, seconds: Float) {
        var t = 0L
        repeat((seconds * 100).toInt()) { t += 10_000_000L; p.onGyro(gx, gy, gz, 0.01f, t) }
    }

    @Test fun flatControllerTurnsAndTilts() {
        val p = GyroPointer()
        p.onAccel(0f, 9.8f, 0f)                       // lying flat: up is +y
        p.recenter(Pose())
        spin(p, 0f, 0.5f, 0f, 1f)                     // turned left by 0.5 rad
        assertEquals(0.5f, p.yaw, 1e-3f)
        val d = p.direction(Vec3())
        assertTrue(d.x < -0.4f)                        // points to the left
        spin(p, 0.3f, 0f, 0f, 1f)                     // tilted up by 0.3 rad
        assertEquals(0.3f, p.pitch, 1e-3f)
        assertTrue(p.direction(Vec3()).y > 0.29f)
    }

    @Test fun heldUprightTheTurnIsAboutTheControllersOwnUp() {
        val p = GyroPointer()
        // standing on its end: gravity along +z of the controller
        repeat(200) { p.onAccel(0f, 0f, 9.8f) }
        spin(p, 0f, 0f, 0.4f, 1f)
        assertEquals(0.4f, p.yaw, 1e-2f)
        assertEquals(0f, p.pitch, 1e-3f)
        // and a spin about its old y does not turn the pointer
        spin(p, 0f, 0.5f, 0f, 1f)
        assertEquals(0.4f, p.yaw, 1e-2f)
    }

    @Test fun invertAndSensitivity() {
        val p = GyroPointer().apply { onAccel(0f, 1f, 0f); invertX = true; invertY = true; sensitivity = 2f }
        spin(p, 0.1f, 0.1f, 0f, 1f)
        assertEquals(-0.2f, p.yaw, 1e-3f)
        assertEquals(-0.2f, p.pitch, 1e-3f)
    }

    @Test fun recenterFollowsTheHeadAndPitchIsLimited() {
        val p = GyroPointer().apply { onAccel(0f, 1f, 0f) }
        spin(p, 5f, 0f, 0f, 1f)
        assertEquals(GyroPointer.MAX_PITCH, p.pitch, 1e-4f)
        val head = Pose(Vec3(), Quat.fromAxisAngle(0f, 1f, 0f, 1.0f))
        p.recenter(head)
        assertEquals(0f, p.pitch, 0f)
        val d = p.direction(Vec3())
        val f = head.orientation.rotate(Vec3(0f, 0f, -1f))
        assertEquals(f.x, d.x, 1e-4f); assertEquals(f.z, d.z, 1e-4f)
        assertFalse(p.active(10_000_000_000L))
    }
}
