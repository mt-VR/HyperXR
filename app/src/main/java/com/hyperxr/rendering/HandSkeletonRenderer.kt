package com.hyperxr.rendering

import com.hyperxr.handtracking.Gesture
import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.HandState
import com.hyperxr.handtracking.TrackingState
import com.hyperxr.math.Mat4
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3

/**
 * Tracked hands drawn as a skeleton: a sphere per joint and a capsule-like bone per segment, lit
 * and shadow-casting, so contact with virtual objects reads correctly. Allocation-free per frame.
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
            for (b in HandJoint.BONES) {
                val a = h.joints[b[0]]; val c = h.joints[b[1]]
                dir.set(c).sub(a)
                val len = dir.length()
                if (len < 1e-4f) continue
                dir.scale(1f / len)
                mid.set(a).add(c).scale(0.5f)
                Quat.fromTo(up, dir, q)
                val d = scene.addDraw()
                d.mesh = renderer.cylinder
                d.late = true
                d.color[0] = 0.86f; d.color[1] = 0.87f; d.color[2] = 0.95f; d.color[3] = alpha
                d.gloss = 0.35f
                d.castShadow = alpha == 1f
                val r = BONE_RADIUS * 2f // unit cylinder has radius 0.5
                Mat4.compose(mid, q, r, len, r, d.matrix)
            }
            for (j in 0 until HandJoint.COUNT) {
                val d = scene.addDraw()
                d.mesh = renderer.sphere
                d.late = true
                val tip = j == HandJoint.THUMB_TIP || j == HandJoint.INDEX_TIP || j == HandJoint.MIDDLE_TIP || j == HandJoint.RING_TIP || j == HandJoint.PINKY_TIP
                when {
                    pinched && (j == HandJoint.THUMB_TIP || j == HandJoint.INDEX_TIP) -> { d.color[0] = 0.94f; d.color[1] = 0.36f; d.color[2] = 0.75f }
                    tip -> { d.color[0] = 0.97f; d.color[1] = 0.97f; d.color[2] = 1f }
                    else -> { d.color[0] = 0.49f; d.color[1] = 0.42f; d.color[2] = 1f }
                }
                d.color[3] = alpha
                d.gloss = 0.5f
                d.castShadow = alpha == 1f
                val r = HandJoint.radiusFor(j) * 0.62f * 2f
                Mat4.compose(h.joints[j], ident, r, r, r, d.matrix)
            }
        }
    }

    companion object { const val BONE_RADIUS = 0.0042f }
}
