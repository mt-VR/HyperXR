package com.hyperxr

import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import com.hyperxr.physics.Body
import com.hyperxr.physics.Material
import com.hyperxr.physics.RigidWorld
import com.hyperxr.physics.Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicsTest {
    private fun world(): RigidWorld = RigidWorld().also { w ->
        w.add(Body(Shape.box(2f, 0.05f, 2f), Material.WOOD, Body.Type.STATIC)).position.set(0f, -0.05f, 0f)
        w.bodies.last().teleport(Vec3(0f, -0.05f, 0f), Quat())
    }

    private fun run(w: RigidWorld, seconds: Float) = repeat((seconds * 60).toInt()) { w.step(1 / 60f) }

    @Test fun sphereFallsAndRestsOnTheFloor() {
        val w = world()
        val s = w.add(Body(Shape.sphere(0.03f), Material.WOOD))
        s.teleport(Vec3(0f, 0.5f, 0f), Quat())
        run(w, 2f)
        assertEquals(0.03f, s.position.y, 0.003f)
        assertTrue(s.linearVelocity.length() < 0.05f)
    }

    @Test fun boxRestsFlatAndSleeps() {
        val w = world()
        val b = w.add(Body(Shape.box(0.03f, 0.03f, 0.03f), Material.WOOD))
        b.teleport(Vec3(0f, 0.2f, 0f), Quat.fromAxisAngle(0f, 1f, 0f, 0.4f))
        run(w, 3f)
        assertEquals(0.03f, b.position.y, 0.003f)
        // still upright (local Y stays up): no spurious tipping on a single contact point
        val up = b.orientation.rotate(Vec3(0f, 1f, 0f))
        assertTrue("tipped: $up", up.y > 0.99f)
        assertTrue("never slept", b.sleeping)
    }

    @Test fun stackOfBlocksStands() {
        val w = world()
        val blocks = (0 until 5).map { i ->
            w.add(Body(Shape.box(0.04f, 0.02f, 0.04f), Material.WOOD)).also { it.teleport(Vec3(0f, 0.02f + i * 0.0405f, 0f), Quat()) }
        }
        run(w, 4f)
        val top = blocks.last()
        assertEquals(0f, top.position.x, 0.01f)
        assertEquals(0f, top.position.z, 0.01f)
        assertEquals(0.02f + 4 * 0.04f, top.position.y, 0.01f)
    }

    @Test fun rubberBouncesHigherThanSteel() {
        fun peak(m: Material): Float {
            val w = world()
            val s = w.add(Body(Shape.sphere(0.03f), m)).also { it.teleport(Vec3(0f, 0.5f, 0f), Quat()) }
            var falling = true; var peak = 0f
            repeat(120) {
                w.step(1 / 60f)
                if (falling && s.linearVelocity.y > 0f) falling = false
                if (!falling) peak = maxOf(peak, s.position.y)
            }
            return peak
        }
        val rubber = peak(Material.RUBBER)
        val steel = peak(Material.STEEL)
        assertTrue("rubber $rubber vs steel $steel", rubber > steel + 0.1f)
        assertTrue("rubber should bounce a good fraction of the drop: $rubber", rubber > 0.2f)
    }

    @Test fun frictionStopsASlidingBox() {
        val w = world()
        val b = w.add(Body(Shape.box(0.05f, 0.02f, 0.05f), Material.WOOD)).also { it.teleport(Vec3(0f, 0.02f, 0f), Quat()) }
        b.linearVelocity.set(1f, 0f, 0f)
        run(w, 2f)
        // v^2 / (2 mu g) = 1 / (2 * 0.55 * 9.81) ~ 0.093 m
        assertEquals(0.093f, b.position.x, 0.03f)
        assertTrue(b.linearVelocity.length() < 0.01f)
    }

    @Test fun kinematicFingerPushesABox() {
        val w = world()
        val b = w.add(Body(Shape.box(0.03f, 0.03f, 0.03f), Material.WOOD)).also { it.teleport(Vec3(0f, 0.03f, 0f), Quat()) }
        val finger = w.add(Body(Shape.capsule(0.009f, 0.02f), Material.HAND, Body.Type.KINEMATIC))
        finger.teleport(Vec3(-0.1f, 0.03f, 0f), Quat())
        val p = Vec3()
        for (i in 0 until 60) {
            p.set(-0.1f + i * 0.004f, 0.03f, 0f)
            w.moveKinematic(finger, p, Quat(), 1 / 60f)
            w.step(1 / 60f)
        }
        assertTrue("box not pushed: ${b.position.x}", b.position.x > 0.08f)
    }

    @Test fun heldObjectsFollowTheHandAndHeavyOnesLag() {
        fun lag(m: Material): Float {
            val w = world()
            // a 12 cm ball: ~54 g of foam, ~7 kg of steel (heavier than a hand's grip can lift briskly)
            val s = w.add(Body(Shape.sphere(0.06f), m)).also { it.teleport(Vec3(0f, 0.06f, 0f), Quat()) }
            val h = w.hold(s)
            h.targetOrientation.set(s.orientation)
            var worst = 0f
            for (i in 0 until 90) {
                h.targetPosition.set(0f, 0.3f + 0.2f * kotlin.math.sin(i / 10f), 0f)
                h.holderVelocity.set(0f, 0.2f * kotlin.math.cos(i / 10f) * 6f, 0f)
                w.step(1 / 60f)
                if (i > 30) worst = maxOf(worst, s.position.distanceTo(h.targetPosition))
            }
            return worst
        }
        val foam = lag(Material.FOAM)
        val steel = lag(Material.STEEL)
        assertTrue("foam should follow closely: $foam", foam < 0.03f)
        assertTrue("steel ($steel) should lag more than foam ($foam)", steel > foam * 2)
    }

    @Test fun releasedObjectKeepsItsMomentum() {
        val w = world()
        val s = w.add(Body(Shape.sphere(0.03f), Material.PLASTIC)).also { it.teleport(Vec3(0f, 0.5f, 0f), Quat()) }
        val h = w.hold(s)
        h.targetOrientation.set(s.orientation)
        for (i in 0 until 30) {
            h.targetPosition.set(i * 0.03f, 0.5f, 0f)
            h.holderVelocity.set(1.8f, 0f, 0f)
            w.step(1 / 60f)
        }
        w.release(h)
        assertTrue("thrown object should keep moving: ${s.linearVelocity.x}", s.linearVelocity.x > 1f)
        assertFalse(s.sleeping)
    }

    @Test fun capsuleLiesOnItsSideWithoutRollingAway() {
        val w = world()
        val c = w.add(Body(Shape.capsule(0.03f, 0.05f), Material.ALUMINIUM))
        c.teleport(Vec3(0f, 0.1f, 0f), Quat.fromAxisAngle(0f, 0f, 1f, (Math.PI / 2).toFloat()))
        run(w, 3f)
        assertEquals(0.03f, c.position.y, 0.004f)
        assertTrue(kotlin.math.abs(c.position.x) < 0.02f)
    }

    @Test fun crossedBlockTowerStands() {
        // 8 levels of 3 wooden blocks (7.5 x 2.5 x 1.5 cm), alternating direction, like a Jenga tower
        val w = world()
        val hx = 0.0375f; val hy = 0.0075f; val hz = 0.0125f
        val all = ArrayList<Body>()
        for (level in 0 until 8) {
            val rotated = level % 2 == 1
            for (k in -1..1) {
                val b = w.add(Body(Shape.box(hx, hy, hz), Material.WOOD))
                val y = hy + level * (2 * hy + 0.0002f)
                val off = k * (2 * hz + 0.0005f)
                if (rotated) b.teleport(Vec3(off, y, 0f), Quat.fromAxisAngle(0f, 1f, 0f, (Math.PI / 2).toFloat()))
                else b.teleport(Vec3(0f, y, off), Quat())
                all += b
            }
        }
        val start = all.map { it.position.copy() }
        run(w, 5f)
        val worst = all.indices.maxOf { all[it].position.distanceTo(start[it]) }
        assertTrue("tower moved by ${worst * 1000} mm", worst < 0.006f)
    }
}
