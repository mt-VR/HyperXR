package com.hyperxr

import com.hyperxr.handtracking.GunMenuGesture
import com.hyperxr.handtracking.HandJoint
import com.hyperxr.input.RadialMenu
import com.hyperxr.math.Pose
import com.hyperxr.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GunMenuGestureTest {
    /**
     * A right hand held upright in front of the face (fingers up along +y, palm facing -z), moved
     * by [at]. [index] / [others] stretched or closed; [thumbOut] sticks the thumb out sideways.
     */
    private fun hand(index: Boolean, others: Boolean = false, thumbOut: Boolean = true, at: Vec3 = Vec3()): Array<Vec3> {
        val j = Array(HandJoint.COUNT) { Vec3() }
        j[HandJoint.WRIST].set(0f, 0f, 0f)
        fun finger(mcp: Int, x: Float, open: Boolean) {
            j[mcp].set(x, 0.09f, 0f)
            if (open) { j[mcp + 1].set(x, 0.13f, 0f); j[mcp + 2].set(x, 0.155f, 0f); j[mcp + 3].set(x, 0.175f, 0f) }
            else { j[mcp + 1].set(x, 0.11f, -0.03f); j[mcp + 2].set(x, 0.09f, -0.04f); j[mcp + 3].set(x, 0.075f, -0.03f) }
        }
        finger(HandJoint.INDEX_MCP, 0.03f, index)
        finger(HandJoint.MIDDLE_MCP, 0.01f, others)
        finger(HandJoint.RING_MCP, -0.01f, others)
        finger(HandJoint.PINKY_MCP, -0.03f, others)
        j[HandJoint.THUMB_CMC].set(0.025f, 0.03f, 0f); j[HandJoint.THUMB_MCP].set(0.045f, 0.05f, 0f); j[HandJoint.THUMB_IP].set(0.06f, 0.07f, 0f)
        if (thumbOut) j[HandJoint.THUMB_TIP].set(0.09f, 0.09f, 0f) else j[HandJoint.THUMB_TIP].set(0.02f, 0.09f, -0.04f)
        for (v in j) v.add(at)
        return j
    }

    private val ms = 1_000_000L

    @Test fun gunCloseMoveOpenOpensWhereTheIndexOpens() {
        val g = GunMenuGesture()
        assertFalse(g.update(hand(index = true), 0))
        assertEquals(GunMenuGesture.Phase.AIMING, g.phase)
        assertFalse(g.update(hand(index = false), 200 * ms))
        assertEquals(GunMenuGesture.Phase.COCKED, g.phase)
        // the hand moves 30 cm to the right with the index closed, then opens it
        assertFalse(g.update(hand(index = false, at = Vec3(0.3f, 0f, 0f)), 600 * ms))
        assertTrue(g.update(hand(index = true, at = Vec3(0.3f, 0f, 0f)), 900 * ms))
        assertEquals(0.33f, g.at.x, 1e-4f)
        assertEquals(0.175f, g.at.y, 1e-4f)
        assertEquals(GunMenuGesture.Phase.IDLE, g.phase)
    }

    @Test fun anOpenHandOrAPlainFistDoesNothing() {
        val g = GunMenuGesture()
        assertFalse(g.update(hand(index = true, others = true), 0))
        assertEquals(GunMenuGesture.Phase.IDLE, g.phase)
        // a fist with the thumb wrapped, then the index opening: no gun was made first
        g.update(hand(index = false, thumbOut = false), 0)
        assertFalse(g.update(hand(index = true, thumbOut = false), 300 * ms))
        assertFalse(g.update(hand(index = false, thumbOut = false), 600 * ms))
        assertFalse(g.update(hand(index = true, thumbOut = false), 900 * ms))
    }

    @Test fun tooQuickACloseOrTooLongAWaitCancels() {
        val g = GunMenuGesture()
        g.update(hand(index = true), 0)
        g.update(hand(index = false), 50 * ms)           // closed before the gun was held
        assertEquals(GunMenuGesture.Phase.IDLE, g.phase)
        g.update(hand(index = true), 100 * ms); g.update(hand(index = false), 400 * ms)
        assertEquals(GunMenuGesture.Phase.COCKED, g.phase)
        assertFalse(g.update(hand(index = true), 5_000 * ms))
        // opening the whole hand also cancels
        g.update(hand(index = true), 6_000 * ms); g.update(hand(index = false), 6_300 * ms)
        g.update(hand(index = true, others = true), 6_500 * ms)
        assertEquals(GunMenuGesture.Phase.IDLE, g.phase)
    }

    @Test fun lostHandCancels() {
        val g = GunMenuGesture()
        g.update(hand(index = true), 0); g.update(hand(index = false), 300 * ms)
        g.update(null, 400 * ms)
        assertFalse(g.update(hand(index = true), 500 * ms))
    }

    @Test fun pointedMenuChoosesWithTheIndexTip() {
        val m = RadialMenu(listOf("a", "b", "c", "d"))
        val head = Pose(Vec3(0f, 0f, 0.5f))
        m.openAt(Vec3(0f, 0f, 0f), head, 0)
        assertTrue(m.pointed)
        assertEquals(RadialMenu.Phase.OPEN, m.phase)
        // tip up = item 0, close the index there to choose it
        assertNull(m.updatePointed(Vec3(0f, 0.08f, 0f), false, 10 * ms))
        assertEquals(0, m.selected)
        assertEquals(0, m.updatePointed(Vec3(0f, 0.08f, 0f), true, 20 * ms))
        assertEquals(RadialMenu.Phase.IDLE, m.phase)
        // choosing from the centre closes without an action; going away closes too
        m.openAt(Vec3(), head, 0)
        assertNull(m.updatePointed(Vec3(), true, 10 * ms)); assertEquals(RadialMenu.Phase.IDLE, m.phase)
        m.openAt(Vec3(), head, 0)
        assertNull(m.updatePointed(Vec3(0.5f, 0f, 0f), false, 10 * ms)); assertEquals(RadialMenu.Phase.IDLE, m.phase)
    }
}
