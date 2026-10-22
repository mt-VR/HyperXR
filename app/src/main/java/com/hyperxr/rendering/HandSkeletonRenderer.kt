package com.hyperxr.rendering

import com.hyperxr.handtracking.Gesture
import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.HandState
import com.hyperxr.handtracking.Handedness
import com.hyperxr.handtracking.TrackingState
import com.hyperxr.math.Mat4
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3

/**
 * Tracked hands drawn as a very thin skeleton: hairline bones (2 mm) and tiny joint balls (5 mm),
 * green for the right hand and blue for the left (the side HyperXR itself decided). No shadows:
 * lines this thin only need to be seen. Allocation-free per frame.
 */
class HandSkeletonRenderer {
    private val up = Vec3(0f, 1f, 0f)
    private val dir = Vec3()
    private val mid = Vec3()
    private val q = Quat()
    private val ident = Quat()

    fun submit(scene: FrameScene, renderer: SceneRenderer, hands: Array<HandState>) {
        for (h in hands) {
            if (!h.isUsable) continue
            val alpha = if (h.state == TrackingState.LOST_PREDICTING) 0.45f else 1f
            val pinched = h.gesture == Gesture.PINCH
            val c = if (h.handedness == Handedness.RIGHT) RIGHT else LEFT
            for (b in HandJoint.BONES) {
                val a = h.joints[b[0]]; val e = h.joints[b[1]]
                dir.set(e).sub(a)
                val len = dir.length()
                if (len < 1e-4f) continue
                dir.scale(1f / len)
                mid.set(a).add(e).scale(0.5f)
                Quat.fromTo(up, dir, q)
                val d = scene.addDraw()
                d.mesh = renderer.cylinder
                d.late = true
                // bones a lighter tint of the hand's colour so the balls stand out
                d.color[0] = c[0] * 0.5f + 0.5f; d.color[1] = c[1] * 0.5f + 0.5f; d.color[2] = c[2] * 0.5f + 0.5f; d.color[3] = alpha * 0.9f
                d.gloss = 0.2f
                d.castShadow = false
                val r = BONE_RADIUS * 2f // unit cylinder has radius 0.5
                Mat4.compose(mid, q, r, len, r, d.matrix)
            }
            for (j in 0 until HandJoint.COUNT) {
                val d = scene.addDraw()
                d.mesh = renderer.sphere
                d.late = true
                if (pinched && (j == HandJoint.THUMB_TIP || j == HandJoint.INDEX_TIP)) {
                    d.color[0] = 1f; d.color[1] = 1f; d.color[2] = 1f  // pinch: the touching tips light up
                } else { d.color[0] = c[0]; d.color[1] = c[1]; d.color[2] = c[2] }
                d.color[3] = alpha
                d.gloss = 0.4f
                d.castShadow = false
                val r = BALL_RADIUS * 2f
                Mat4.compose(h.joints[j], ident, r, r, r, d.matrix)
            }
        }
    }

    companion object {
        const val BONE_RADIUS = 0.001f
        const val BALL_RADIUS = 0.0025f
        val RIGHT = floatArrayOf(0.20f, 0.92f, 0.38f)  // green
        val LEFT = floatArrayOf(0.22f, 0.52f, 1.00f)   // blue
    }
}
