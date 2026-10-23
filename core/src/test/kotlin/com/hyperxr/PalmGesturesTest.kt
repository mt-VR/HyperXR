package com.hyperxr

import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.HandMeasure
import com.hyperxr.handtracking.PalmMenuDetector
import com.hyperxr.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A synthetic hand 40 cm in front of the eyes (head at the origin looking down -z), fingers up.
 * A right hand showing its palm to the face has the thumb side (index knuckle) at +x.
 */
class PalmGesturesTest {
    private val head = Vec3(0f, 0f, 0f)

    private fun hand(thumbSideX: Float, open: Boolean = true): Array<Vec3> {
        val j = Array(HandJoint.COUNT) { Vec3() }
        val wx = 0f; val wy = -0.1f; val z = -0.4f
        j[HandJoint.WRIST].set(wx, wy, z)
        val cols = floatArrayOf(0.025f, 0.005f, -0.013f, -0.03f)  // index, middle, ring, pinky (thumb side +)
        for ((f, cx) in cols.withIndex()) {
            val mcp = HandJoint.INDEX_MCP + f * 4
            val x = wx + cx * thumbSideX
            j[mcp].set(x, wy + 0.08f, z)
            j[mcp + 1].set(x, wy + 0.12f, z)
            if (open) { j[mcp + 2].set(x, wy + 0.145f, z); j[mcp + 3].set(x, wy + 0.165f, z) }
            else { j[mcp + 2].set(x, wy + 0.11f, z + 0.02f); j[mcp + 3].set(x, wy + 0.085f, z + 0.02f) }
        }
        for (k in 1..4) j[k].set(wx + (0.03f + 0.012f * k) * thumbSideX, wy + 0.02f * k, z)
        return j
    }

    @Test fun rightPalmTowardTheFace() {
        assertTrue(PalmMenuDetector.isPalmMenuPose(hand(1f), right = true, head = head, pinchStrength = 0f))
    }

    @Test fun backOfTheHandDoesNotOpen() {
        assertFalse(PalmMenuDetector.isPalmMenuPose(hand(-1f), right = true, head = head, pinchStrength = 0f))
    }

    @Test fun leftHandIsTheMirror() {
        assertTrue(PalmMenuDetector.isPalmMenuPose(hand(-1f), right = false, head = head, pinchStrength = 0f))
        assertFalse(PalmMenuDetector.isPalmMenuPose(hand(1f), right = false, head = head, pinchStrength = 0f))
    }

    @Test fun fistOrPinchDoesNotOpen() {
        assertFalse(PalmMenuDetector.isPalmMenuPose(hand(1f, open = false), right = true, head = head, pinchStrength = 0f))
        assertFalse(PalmMenuDetector.isPalmMenuPose(hand(1f), right = true, head = head, pinchStrength = 0.9f))
    }

    @Test fun farHandDoesNotOpen() {
        val far = hand(1f).also { a -> a.forEach { it.z -= 0.6f } }
        assertFalse(PalmMenuDetector.isPalmMenuPose(far, right = true, head = head, pinchStrength = 0f))
    }

    @Test fun holdsBeforeOpeningAndClosing() {
        val d = PalmMenuDetector()
        val up = hand(1f); val away = hand(-1f)
        var t = 0f
        while (t < 0.3f) { assertFalse(d.update(up, true, head, 0f, 1 / 60f)); t += 1 / 60f }
        repeat(5) { d.update(up, true, head, 0f, 1 / 60f) }
        assertTrue(d.active)
        // a short glitch does not close it
        repeat(5) { d.update(away, true, head, 0f, 1 / 60f) }
        assertTrue(d.active)
        d.update(up, true, head, 0f, 1 / 60f)
        repeat(25) { d.update(away, true, head, 0f, 1 / 60f) }
        assertFalse(d.active)
    }

    @Test fun palmUpOpensTheRadialMenu() {
        // palm toward the face counts; the back of the hand does not
        assertTrue(PalmMenuDetector.isPalmUp(hand(1f), right = true, head = head))
        assertFalse(PalmMenuDetector.isPalmUp(hand(-1f), right = true, head = head))
        // a hand lying flat with the palm to the sky (fingers forward)
        val flat = Array(HandJoint.COUNT) { Vec3() }
        flat[HandJoint.WRIST].set(0f, 1.2f, -0.3f)
        flat[HandJoint.INDEX_MCP].set(0.025f, 1.2f, -0.38f)   // right hand palm up: thumb side to the right
        flat[HandJoint.MIDDLE_MCP].set(0.005f, 1.2f, -0.38f)
        flat[HandJoint.PINKY_MCP].set(-0.03f, 1.2f, -0.38f)
        assertTrue(PalmMenuDetector.isPalmUp(flat, right = true, head = Vec3(0f, 1.6f, 0f)))
        // the same hand turned over (palm to the floor) does not
        val down = Array(HandJoint.COUNT) { Vec3().set(flat[it]) }
        for (v in down) v.x = -v.x
        assertFalse(PalmMenuDetector.isPalmUp(down, right = true, head = Vec3(0f, 1.6f, 3f)))
    }

    @Test fun measuresAndFormats() {
        val a = hand(1f); val b = hand(1f).also { h -> h.forEach { it.x += 0.345f } }
        val d = HandMeasure.pinchPoint(a, Vec3()).distanceTo(HandMeasure.pinchPoint(b, Vec3()))
        assertEquals("34,5 cm", HandMeasure.format(d))
        assertEquals("8 mm", HandMeasure.format(0.008f))
        assertEquals("1,27 m", HandMeasure.format(1.27f))
    }
}
