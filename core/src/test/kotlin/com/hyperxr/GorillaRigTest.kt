package com.hyperxr

import com.hyperxr.questgames.GorillaRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GorillaRigTest {
    private val head = floatArrayOf(0f, 1.0f, 0f)
    private val dt = 1f / 72f

    /** A rig that has fallen onto flat ground and rests there (no hands). */
    private fun resting(w: GorillaRig.World = GorillaRig.World()): GorillaRig {
        val rig = GorillaRig(w)
        repeat(150) { rig.step(dt, head, arrayOf(null, null)) }
        return rig
    }

    /** A hand at world height [y] (and x, z), as the play-space position the rig takes. */
    private fun local(rig: GorillaRig, x: Float, y: Float, z: Float) = floatArrayOf(x - rig.body[0], y - rig.body[1], z - rig.body[2])

    @Test fun theBodyRestsOnTheGround() {
        val rig = resting()
        assertTrue(rig.grounded)
        // the body collider sits on the ground: the head is bodyDrop + bodyRadius up
        assertEquals(rig.bodyDrop + rig.bodyRadius, head[1] + rig.body[1], 0.02f)
        assertEquals(0f, rig.velocity[1], 0.05f)
    }

    @Test fun pushingTheGroundLiftsTheBody() {
        val rig = resting()
        val y0 = rig.body[1]
        // the hand comes down onto the ground and keeps going "into" it: the body rises instead
        val start = local(rig, 0.2f, 0.2f, -0.3f)
        for (i in 0..40) rig.step(dt, head, arrayOf(floatArrayOf(start[0], start[1] - i * 0.01f, start[2]), null))
        assertTrue(rig.touching[0])
        assertTrue("rose ${rig.body[1] - y0}", rig.body[1] - y0 > 0.15f)
        // the hand stayed on the ground
        assertEquals(rig.handRadius, rig.hand[0][1], 0.01f)
    }

    @Test fun sweepingAlongTheGroundWalks() {
        val rig = resting()
        val z0 = rig.body[2]
        val p = local(rig, 0f, rig.handRadius + 0.005f, -0.45f)
        // touch down, then pull the planted hand back toward the body: the body moves forward (-z)
        for (i in 0..5) rig.step(dt, head, arrayOf(floatArrayOf(p[0], p[1] - i * 0.004f, p[2]), null))
        for (i in 1..20) rig.step(dt, head, arrayOf(floatArrayOf(p[0], p[1] - 0.02f, p[2] + i * 0.015f), null))
        assertTrue("moved ${rig.body[2] - z0}", rig.body[2] - z0 < -0.2f)
    }

    @Test fun aFastPushThrowsButCapped() {
        val rig = resting()
        val a = local(rig, -0.15f, 0.06f, -0.3f); val b = local(rig, 0.15f, 0.06f, -0.3f)
        for (i in 0..2) rig.step(dt, head, arrayOf(floatArrayOf(a[0], a[1] - i * 0.005f, a[2]), floatArrayOf(b[0], b[1] - i * 0.005f, b[2])))
        // a hard push down: 6 cm a frame (over 4 m/s)
        for (i in 1..5) rig.step(dt, head, arrayOf(floatArrayOf(a[0], a[1] - i * 0.06f, a[2]), floatArrayOf(b[0], b[1] - i * 0.06f, b[2])))
        // the hands come off the ground
        rig.step(dt, head, arrayOf(floatArrayOf(a[0], 1.2f, a[2]), floatArrayOf(b[0], 1.2f, b[2])))
        val v = rig.velocity[1]
        assertTrue("v=$v", v > 1f)
        assertTrue(v <= rig.maxJumpSpeed + 1e-3f)
    }

    @Test fun shakeDoesNotThrow() {
        val rig = resting()
        val p = local(rig, 0f, rig.handRadius + 0.002f, -0.3f)
        val rnd = java.util.Random(3)
        // hand tracking noise of a few millimetres on a hand resting on the ground
        for (i in 0..60) rig.step(dt, head, arrayOf(floatArrayOf(p[0] + (rnd.nextFloat() - 0.5f) * 0.006f, p[1] + (rnd.nextFloat() - 0.5f) * 0.006f, p[2]), null))
        rig.step(dt, head, arrayOf(floatArrayOf(p[0], p[1] + 0.4f, p[2]), null))
        assertTrue(kotlin.math.abs(rig.velocity[0]) < 0.3f)
        assertTrue(rig.velocity[1] < 0.3f)
    }

    @Test fun aWallStopsTheHand() {
        val w = GorillaRig.World().apply { box(0f, 1f, -1f, 2f, 2f, 0.2f) }
        val rig = resting(w)
        val p = local(rig, 0f, 0.6f, -0.8f)
        // pressing slowly (under velocityLimit: a fast push would throw you back off the wall)
        var minZ = 0f
        for (i in 0..60) { rig.step(dt, head, arrayOf(floatArrayOf(p[0], p[1], p[2] - i * 0.004f), null)); minZ = minOf(minZ, rig.hand[0][2]) }
        assertTrue(rig.touching[0])
        // the wall's face is at z = -0.9: the hand stays a radius in front of it
        assertTrue("hand z $minZ", minZ >= -0.9f + rig.handRadius - 0.005f)
        // pushing into the wall pushed the body back
        assertTrue(rig.body[2] > 0.05f)
    }

    @Test fun climbingABar() {
        // a bar at 1.2 m in front: hook the hand over it and pull down to rise
        val w = GorillaRig.World().apply { capsule(-1f, 1.2f, -0.5f, 1f, 1.2f, -0.5f, 0.04f) }
        val rig = resting(w)
        val y0 = rig.body[1]
        val p = local(rig, 0f, 1.32f, -0.5f)
        for (i in 0..50) rig.step(dt, head, arrayOf(floatArrayOf(p[0], p[1] - i * 0.008f, p[2]), null))
        assertTrue(rig.touching[0])
        assertTrue("rose ${rig.body[1] - y0}", rig.body[1] - y0 > 0.25f)
    }

    @Test fun sphereCastHitsWhatIsAheadNotWhatItStartsIn() {
        val w = GorillaRig.World().apply { box(0f, 1f, -2f, 2f, 2f, 1f) }
        val h = GorillaRig.Hit()
        // straight ahead into the box face at z = -1.5
        assertTrue(w.sphereCast(0f, 1f, 0f, 0.1f, 0f, 0f, -1f, 5f, h))
        assertEquals(1.4f, h.distance, 0.002f)
        assertEquals(1f, h.normal[2], 0.01f)
        assertEquals(-1.5f, h.point[2], 0.002f)
        // parallel to the ground, a radius above it: no hit
        assertFalse(w.sphereCast(0f, 0.1001f, 0f, 0.1f, 1f, 0f, 0f, 3f, h))
        // starting inside the box: Unity's casts ignore it
        assertFalse(w.sphereCast(0f, 1f, -2f, 0.1f, 0f, 0f, -1f, 0.3f, h))
        // grazing down onto the ground along a long move is still caught
        assertTrue(w.sphereCast(0f, 0.105f, 0f, 0.1f, 1f, -0.01f, 0f, 3f, h))
        assertEquals(1f, h.normal[1], 0.01f)
    }

    @Test fun aSurfaceWithoutGripStopsTheHandButHoldsNothing() {
        fun pressed(slip: Float): GorillaRig {
            val w = GorillaRig.World().apply { box(0f, 1f, -1f, 4f, 2f, 0.2f, slip = slip) }
            val rig = resting(w)
            val p = local(rig, 0f, 0.7f, -0.8f)
            // keep pressing into the wall while sweeping sideways
            for (i in 0..30) rig.step(dt, head, arrayOf(floatArrayOf(p[0] + i * 0.01f, p[1], p[2] - i * 0.004f), null))
            return rig
        }
        val rock = pressed(-1f)
        val glass = pressed(GorillaRig.NO_GRIP)
        // on rock the planted hand drags you sideways; on glass it slides and you don't move
        assertTrue("rock ${rock.body[0]}", rock.body[0] < -0.1f)
        assertEquals(0f, glass.body[0], 0.01f)
        assertFalse(glass.touching[0])
        // and the glass still stopped the hand
        assertTrue(glass.hand[0][2] >= -0.9f + glass.handRadius - 0.005f)
    }

    /** A 1.5 m pipe held at [grip] pointing along [d]: its end and its middle, as the rig's two contacts (play space). */
    private fun pipe(rig: GorillaRig, gx: Float, gy: Float, gz: Float, dx: Float, dy: Float, dz: Float): Array<FloatArray?> {
        val l = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        val ux = dx / l; val uy = dy / l; val uz = dz / l
        val bx = gx - ux * 0.25f; val by = gy - uy * 0.25f; val bz = gz - uz * 0.25f
        return arrayOf(floatArrayOf(bx + ux * 1.5f, by + uy * 1.5f, bz + uz * 1.5f), floatArrayOf(bx + ux * 0.825f, by + uy * 0.825f, bz + uz * 0.825f))
    }

    @Test fun aPipeThatAppearsInTheGroundDoesNotLaunchYou() {
        val rig = resting().apply { armLength = 2.4f; handRadius = 0.04f }
        // the hands come into view with the pipe's end already deep in the ground
        rig.step(dt, head, pipe(rig, 0f, 0.6f, -0.1f, 0f, -0.75f, 0.9f))
        rig.step(dt, head, pipe(rig, 0f, 0.6f, -0.1f, 0f, -0.75f, 0.9f))
        assertTrue(rig.velocity[1] < 0.5f)
        assertEquals(rig.bodyDrop + rig.bodyRadius, head[1] + rig.body[1], 0.05f)
    }

    @Test fun aPipePlantedBehindPushesYouForward() {
        val rig = resting().apply { armLength = 2.4f; handRadius = 0.04f }
        val z0 = rig.body[2]
        // the pipe points down and back; lower it until its end is on the ground, then pull the
        // hands back: the planted end stays and you go forward
        var gy = 1.4f
        var gz = -0.1f
        for (i in 0..90) {
            if (!rig.touching[0]) gy -= 0.01f else gz += 0.012f
            rig.step(dt, head, pipe(rig, 0f, gy, gz, 0f, -0.75f, 0.9f))
        }
        assertTrue("moved ${rig.body[2] - z0}", rig.body[2] - z0 < -0.2f)
    }

    @Test fun aPipeHookedOverAHighBarLiftsYou() {
        // a person, not a gorilla: eyes 1.35 m over the feet
        val w = GorillaRig.World().apply { capsule(-1.4f, 2.3f, -1.2f, 1.4f, 2.3f, -1.2f, 0.04f) }
        val rig = GorillaRig(w).apply { armLength = 2.4f; handRadius = 0.04f; bodyDrop = 1.05f; bodyRadius = 0.3f }
        repeat(150) { rig.step(dt, head, arrayOf(null, null)) }
        val y0 = rig.body[1]
        // the pipe goes up and forward over the bar, then the hands pull down
        for (i in 0..70) rig.step(dt, head, pipe(rig, 0f, 1.3f - i * 0.012f, -0.3f, 0f, 1f, -1f))
        assertTrue(rig.touching[0] || rig.touching[1])
        assertTrue("rose ${rig.body[1] - y0}", rig.body[1] - y0 > 0.3f)
    }
}
