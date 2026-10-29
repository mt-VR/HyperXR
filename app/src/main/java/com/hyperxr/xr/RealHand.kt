package com.hyperxr.xr

import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.HandState
import com.hyperxr.handtracking.TrackingState
import com.hyperxr.math.Pose
import com.hyperxr.math.Vec3
import com.hyperxr.rendering.FrameScene
import com.hyperxr.rendering.SceneRenderer
import kotlin.math.sqrt

/**
 * The hands in VR: a solid hand in skin tone built on the tracked joints (rounded finger bones,
 * the palm, the thumb's base), and the whole arm behind it, the forearm from the wrist to an elbow
 * solved from your shoulder (two-bone IK) and the sleeved upper arm to the shoulder, so the hand
 * is attached to you instead of floating.
 */
class RealHand {
    private val a = Vec3(); private val b = Vec3(); private val t = Vec3()
    private val x = Vec3(); private val y = Vec3(); private val z = Vec3()
    private val shoulder = Vec3(); private val elbow = Vec3(); private val wrist = Vec3()
    private val fwd = Vec3(); private val right = Vec3(); private val pole = Vec3()

    fun submit(scene: FrameScene, r: SceneRenderer, hands: Array<HandState>, head: Pose) {
        // the head's yaw only: shoulders don't tilt when you look down
        head.orientation.rotate(Vec3(0f, 0f, -1f), fwd); fwd.y = 0f
        if (fwd.length() < 1e-3f) fwd.set(0f, 0f, -1f)
        fwd.normalize()
        right.set(-fwd.z, 0f, fwd.x)
        for (h in hands) {
            if (!h.isUsable) continue
            val j = h.joints
            val fade = if (h.state == TrackingState.LOST_PREDICTING) 0.75f else 1f
            val skin = floatArrayOf(SKIN[0] * fade, SKIN[1] * fade, SKIN[2] * fade, 1f)
            // fingers: four bones each, rounded joints
            for ((f, chain) in FINGERS.withIndex()) {
                val rad = RADII[f]
                for (k in 0 until chain.size - 1) {
                    val taper = 1f - k * 0.12f
                    bone(scene, r, j[chain[k]], j[chain[k + 1]], rad * taper, skin)
                    ball(scene, r, j[chain[k + 1]], rad * taper * 0.98f, skin)
                }
                ball(scene, r, j[chain[0]], rad * 1.1f, skin)
            }
            palm(scene, r, j, skin)
            // the arm: shoulder beside and below the head, elbow solved between it and the wrist
            val side = if (h.handedness == com.hyperxr.handtracking.Handedness.RIGHT) 1f else -1f
            shoulder.set(head.position).addScaled(right, 0.18f * side).addScaled(fwd, -0.04f); shoulder.y -= 0.24f
            wrist.set(j[HandJoint.WRIST])
            solveElbow(side)
            bone(scene, r, wrist, elbow, 0.033f, skin)
            ball(scene, r, elbow, 0.04f, SLEEVE)
            // the sleeve covers the forearm's last third and the upper arm
            t.set(elbow).sub(wrist).scale(0.66f).add(wrist)
            bone(scene, r, t, elbow, 0.041f, SLEEVE)
            bone(scene, r, elbow, shoulder, 0.048f, SLEEVE)
        }
    }

    private fun solveElbow(side: Float) {
        val l1 = 0.29f; val l2 = 0.27f
        t.set(wrist).sub(shoulder)
        var d = t.length()
        if (d < 1e-4f) { elbow.set(shoulder); elbow.y -= l1; return }
        t.scale(1f / d)
        d = d.coerceAtMost(l1 + l2 - 1e-3f)
        val along = (l1 * l1 - l2 * l2 + d * d) / (2 * d)
        val h = sqrt((l1 * l1 - along * along).coerceAtLeast(0f))
        // elbows hang down and a little out and back
        pole.set(0f, -1f, 0f).addScaled(right, 0.45f * side).addScaled(fwd, -0.25f)
        val dot = pole.dot(t)
        pole.addScaled(t, -dot)
        if (pole.length() < 1e-4f) pole.set(0f, -1f, 0f)
        pole.normalize()
        elbow.set(shoulder).addScaled(t, along).addScaled(pole, h)
    }

    /** The palm: a rounded slab from the wrist to the knuckles, as wide as index to pinky. */
    private fun palm(scene: FrameScene, r: SceneRenderer, j: Array<Vec3>, color: FloatArray) {
        val w = j[HandJoint.WRIST]
        a.set(j[HandJoint.INDEX_MCP]).add(j[HandJoint.PINKY_MCP]).scale(0.5f)   // knuckle centre
        z.set(a).sub(w)
        val len = z.length().coerceAtLeast(0.02f); z.scale(1f / len)
        x.set(j[HandJoint.INDEX_MCP]).sub(j[HandJoint.PINKY_MCP])
        val width = x.length().coerceAtLeast(0.03f)
        x.addScaled(z, -x.dot(z)).normalize()
        z.cross(x, y); y.normalize()
        b.set(w).add(a).scale(0.5f)
        val d = scene.addDraw()
        d.mesh = r.sphere
        color.copyInto(d.color)
        d.gloss = 0.25f; d.late = true
        val m = d.matrix
        val sx = width * 0.62f; val sy = 0.026f; val sz = len * 0.62f
        m[0] = x.x * sx; m[1] = x.y * sx; m[2] = x.z * sx; m[3] = 0f
        m[4] = y.x * sy; m[5] = y.y * sy; m[6] = y.z * sy; m[7] = 0f
        m[8] = z.x * sz; m[9] = z.y * sz; m[10] = z.z * sz; m[11] = 0f
        m[12] = b.x; m[13] = b.y; m[14] = b.z; m[15] = 1f
        // the thumb's fleshy base
        bone(scene, r, w, j[HandJoint.THUMB_MCP], 0.017f, color)
        ball(scene, r, w, 0.026f, color)
    }

    /** A rounded bone from p to q (a cylinder; the joints are spheres). */
    private fun bone(scene: FrameScene, r: SceneRenderer, p: Vec3, q: Vec3, radius: Float, color: FloatArray) {
        t.set(q).sub(p)
        val len = t.length()
        if (len < 1e-4f) return
        y.set(t).scale(1f / len)
        // any axis perpendicular to the bone
        b.set(if (kotlin.math.abs(y.y) < 0.9f) 0f else 1f, if (kotlin.math.abs(y.y) < 0.9f) 1f else 0f, 0f)
        b.cross(y, x); x.normalize()
        x.cross(y, z); z.normalize()
        val d = scene.addDraw()
        d.mesh = r.cylinder
        color.copyInto(d.color)
        d.gloss = 0.25f; d.late = true
        val m = d.matrix
        val s = radius * 2
        m[0] = x.x * s; m[1] = x.y * s; m[2] = x.z * s; m[3] = 0f
        m[4] = y.x * len; m[5] = y.y * len; m[6] = y.z * len; m[7] = 0f
        m[8] = z.x * s; m[9] = z.y * s; m[10] = z.z * s; m[11] = 0f
        m[12] = (p.x + q.x) / 2; m[13] = (p.y + q.y) / 2; m[14] = (p.z + q.z) / 2; m[15] = 1f
    }

    private fun ball(scene: FrameScene, r: SceneRenderer, p: Vec3, radius: Float, color: FloatArray) {
        val d = scene.addDraw()
        d.mesh = r.sphere
        color.copyInto(d.color)
        d.gloss = 0.25f; d.late = true
        val m = d.matrix
        java.util.Arrays.fill(m, 0f)
        val s = radius * 2
        m[0] = s; m[5] = s; m[10] = s; m[12] = p.x; m[13] = p.y; m[14] = p.z; m[15] = 1f
    }

    companion object {
        val SKIN = floatArrayOf(0.94f, 0.78f, 0.68f, 1f)
        val SLEEVE = floatArrayOf(0.2f, 0.22f, 0.27f, 1f)
        private val FINGERS = arrayOf(
            intArrayOf(HandJoint.THUMB_CMC, HandJoint.THUMB_MCP, HandJoint.THUMB_IP, HandJoint.THUMB_TIP),
            intArrayOf(HandJoint.INDEX_MCP, HandJoint.INDEX_PIP, HandJoint.INDEX_DIP, HandJoint.INDEX_TIP),
            intArrayOf(HandJoint.MIDDLE_MCP, HandJoint.MIDDLE_PIP, HandJoint.MIDDLE_DIP, HandJoint.MIDDLE_TIP),
            intArrayOf(HandJoint.RING_MCP, HandJoint.RING_PIP, HandJoint.RING_DIP, HandJoint.RING_TIP),
            intArrayOf(HandJoint.PINKY_MCP, HandJoint.PINKY_PIP, HandJoint.PINKY_DIP, HandJoint.PINKY_TIP),
        )
        private val RADII = floatArrayOf(0.0115f, 0.0098f, 0.0102f, 0.0094f, 0.0082f)
    }
}
