package com.hyperxr.bench

import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import com.hyperxr.physics.Body
import com.hyperxr.physics.Material
import com.hyperxr.physics.RigidWorld
import com.hyperxr.physics.Shape
import org.junit.Test

class PhysBench {
    @Test fun labLikeScene() {
        val w = RigidWorld()
        w.add(Body(Shape.box(0.55f, 0.02f, 0.3f), Material.WOOD, Body.Type.STATIC)).teleport(Vec3(0f, -0.02f, 0f), Quat())
        for (level in 0 until 6) for (k in -1..1) {
            val b = w.add(Body(Shape.box(0.0375f, 0.0075f, 0.0125f), Material.WOOD))
            val rot = level % 2 == 1; val off = k * 0.0255f
            b.teleport(Vec3(if (rot) off - 0.36f else -0.36f, 0.0075f + level * 0.0152f, if (rot) 0f else off), if (rot) Quat.fromAxisAngle(0f, 1f, 0f, 1.5708f) else Quat())
        }
        for (i in 0 until 10) w.add(Body(Shape.box(0.012f, 0.024f, 0.004f), Material.PLASTIC)).teleport(Vec3(-0.12f + i * 0.032f, 0.024f, -0.17f), Quat())
        for (i in 0 until 8) w.add(Body(Shape.sphere(0.03f), Material.RUBBER)).teleport(Vec3(0.1f + i * 0.07f - 0.3f, 0.03f, 0.1f), Quat())
        val hand = (0 until 42).map { w.add(Body(Shape.capsule(0.009f, 0.012f), Material.HAND, Body.Type.KINEMATIC)) }
        // hands sweeping through the scene so everything stays awake
        var t = 0f
        repeat(60) { w.step(1 / 60f) }
        val n = 240
        val t0 = System.nanoTime()
        repeat(n) {
            t += 1 / 60f
            hand.forEachIndexed { i, b -> w.moveKinematic(b, Vec3(-0.4f + 0.8f * ((t * 0.3f + i * 0.01f) % 1f), 0.03f + (i % 7) * 0.01f, -0.1f + (i % 5) * 0.05f), Quat(), 1 / 60f) }
            w.step(1 / 60f)
        }
        val ms = (System.nanoTime() - t0) / 1e6 / n
        println("bench: %.2f ms per 60 Hz frame, %d bodies, %d contacts".format(ms, w.bodies.size, w.contactCount))
    }
}
