package com.hyperxr

import com.hyperxr.handtracking.ArmFromMask
import com.hyperxr.handtracking.ArmSolver
import com.hyperxr.handtracking.HandJoint
import com.hyperxr.math.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArmTrackingTest {
    private fun hand(wx: Float, wy: Float, mx: Float, my: Float): FloatArray {
        val img = FloatArray(HandJoint.COUNT * 3)
        img[HandJoint.WRIST * 3] = wx; img[HandJoint.WRIST * 3 + 1] = wy
        img[HandJoint.MIDDLE_MCP * 3] = mx; img[HandJoint.MIDDLE_MCP * 3 + 1] = my
        return img
    }

    /** A band of arm pixels from the wrist toward (dx, dy), [half] wide. */
    private fun band(n: Int, wx: Float, wy: Float, dx: Float, dy: Float, half: Float): FloatArray {
        val m = FloatArray(n * n)
        val l = sqrt(dx * dx + dy * dy); val ux = dx / l; val uy = dy / l
        for (y in 0 until n) for (x in 0 until n) {
            val px = (x + 0.5f) / n - wx; val py = (y + 0.5f) / n - wy
            val along = px * ux + py * uy
            val across = abs(-px * uy + py * ux)
            if (along > -0.02f && across < half) m[y * n + x] = 0.95f
        }
        return m
    }

    @Test
    fun findsTheForearmDirectionInTheMask() {
        val n = 128
        // fingers up and to the left, the forearm goes down and to the right (30 degrees off the hand axis)
        val img = hand(0.5f, 0.45f, 0.45f, 0.33f)
        val back = atan2(0.45f - 0.33f, 0.5f - 0.45f)
        val arm = back - 0.5f
        val mask = band(n, 0.5f, 0.45f, kotlin.math.cos(arm), kotlin.math.sin(arm), 0.05f)
        val r = ArmFromMask.Result()
        assertTrue(ArmFromMask.forearm(mask, n, img, 1f, r))
        val found = atan2(r.py - 0.45f, r.px - 0.5f)
        assertTrue("direction $found vs $arm", abs(found - arm) < 0.12f)
        assertTrue("visible ${r.visible}", r.visible > 2.5f)
        assertTrue(r.confidence > 0.4f)
    }

    @Test
    fun noArmWhenTheMaskIsEmpty() {
        val r = ArmFromMask.Result()
        assertFalse(ArmFromMask.forearm(FloatArray(64 * 64), 64, hand(0.5f, 0.5f, 0.5f, 0.4f), 1f, r))
    }

    @Test
    fun solvesTheElbowFromTheCameraRay() {
        val cam = Vec3(0f, 0f, 0f)
        val shoulder = Vec3(0.18f, -0.24f, 0.04f)
        // a real arm: shoulder -> elbow (upper arm 0.29) -> wrist (forearm 0.26)
        val elbow = Vec3(0.24f, -0.46f, -0.13f)
        val up = elbow.copy().sub(shoulder).normalize()
        elbow.set(shoulder).addScaled(up, ArmSolver.UPPER_ARM)
        val fdir = Vec3(-0.25f, 0.35f, -0.9f).normalize()
        val wrist = elbow.copy().addScaled(fdir, ArmSolver.FOREARM)
        // the camera sees a point 10 cm down the forearm from the wrist
        val seen = wrist.copy().addScaled(fdir, -0.10f)
        val ray = seen.copy().sub(cam).normalize()
        val out = Vec3()
        assertTrue(ArmSolver.elbowDirection(cam, wrist, ray, shoulder, null, out))
        val truth = elbow.copy().sub(wrist).normalize()
        assertTrue("dot ${out.dot(truth)}", out.dot(truth) > 0.97f)
    }
}
