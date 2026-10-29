package com.hyperxr

import com.hyperxr.questgames.GorillaRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GorillaRigTest {
    private val head = floatArrayOf(0f, 1.0f, 0f)

    @Test fun pushingTheGroundLiftsTheBody() {
        val rig = GorillaRig(GorillaRig.World())
        // hand touches the ground, then goes 20 cm "into" it: the body rises instead
        rig.step(0.016f, head, arrayOf(floatArrayOf(0.2f, 0.05f, -0.3f), null))
        assertTrue(rig.touching[0])
        rig.step(0.016f, head, arrayOf(floatArrayOf(0.2f, -0.15f, -0.3f), null))
        assertTrue(rig.body[1] > 0.15f)
    }

    @Test fun sweepingAlongTheGroundWalks() {
        val rig = GorillaRig(GorillaRig.World())
        rig.step(0.016f, head, arrayOf(floatArrayOf(0f, 0.05f, -0.4f), null))
        // pull the planted hand back toward the body: the body moves forward (-z)
        for (i in 1..10) rig.step(0.016f, head, arrayOf(floatArrayOf(0f, 0.05f, -0.4f + i * 0.04f), null))
        assertTrue(rig.body[2] < -0.3f)
    }

    @Test fun fastPushThrowsButCapped() {
        val rig = GorillaRig(GorillaRig.World())
        rig.step(0.016f, head, arrayOf(floatArrayOf(0f, 0.05f, -0.3f), floatArrayOf(0.3f, 0.05f, -0.3f)))
        for (i in 1..4) rig.step(0.016f, head, arrayOf(floatArrayOf(0f, 0.05f - i * 0.1f, -0.3f), floatArrayOf(0.3f, 0.05f - i * 0.1f, -0.3f)))
        // hands come off the ground (up and away)
        rig.step(0.016f, head, arrayOf(floatArrayOf(0f, 1.5f, -0.3f), floatArrayOf(0.3f, 1.5f, -0.3f)))
        val v = rig.velocity[1]
        assertTrue("v=$v", v > 0f)
        assertTrue(v <= rig.maxJumpSpeed + 1e-3f)
    }

    @Test fun shakeDoesNotThrow() {
        val rig = GorillaRig(GorillaRig.World())
        rig.step(0.016f, head, arrayOf(floatArrayOf(0f, 0.05f, -0.3f), null))
        rig.step(0.016f, head, arrayOf(floatArrayOf(0f, 0.045f, -0.3f), null))
        rig.step(0.016f, head, arrayOf(floatArrayOf(0f, 0.3f, -0.3f), null))
        assertEquals(0f, rig.velocity[0], 1e-4f)
        assertTrue(rig.velocity[1] <= 0f)
    }

    @Test fun wallStopsTheHand() {
        val w = GorillaRig.World().apply { box(0f, 1f, -1f, 2f, 2f, 0.2f) }
        val rig = GorillaRig(w)
        rig.step(0.016f, head, arrayOf(floatArrayOf(0f, 1.2f, -0.85f), null))
        assertTrue(rig.touching[0])
        assertTrue(rig.hand[0][2] >= -0.9f + rig.handRadius - 1e-3f)
    }
}
