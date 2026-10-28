package com.hyperxr.xr

import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.HandState
import com.hyperxr.handtracking.TrackingState
import com.hyperxr.math.Mat4
import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import com.hyperxr.rendering.FrameScene
import com.hyperxr.rendering.GpuMesh
import com.hyperxr.rendering.SceneRenderer

/**
 * A controller held in the hand (Joy-Con, gamepad, VR Box remote): it shows as a VR controller
 * where the hand is, the hand drawn closed around its handle (not the tracked fingers), and
 * the pointer goes out of its tip. Hand tracking keeps running underneath: it is where the
 * controller's position and rotation come from.
 *
 * Controller frame: forward -Z (where it points), +Y up, the handle going down and back.
 */
class HeldController {
    private var ring: GpuMesh? = null
    private val pos = Vec3(); private val tmp = Vec3(); private val q = Quat(); private val q2 = Quat()

    /** Adds the controller model at [pose] (grip point) to the scene. [pressed]: its select button is down. */
    fun submit(scene: FrameScene, renderer: SceneRenderer, pose: Pose, rightSide: Boolean, pressed: Boolean) {
        val ringMesh = ring ?: com.hyperxr.questgames.GameMeshes.torus(0.09f).also { ring = it }
        // handle: down and back from the grip point
        part(scene, renderer.cylinder, pose, 0f, -0.02f, 0.025f, HANDLE_TILT, 0.034f, 0.12f, 0.03f, BODY, 0.5f)
        // head with the face buttons, at the top of the handle
        part(scene, renderer.cylinder, pose, 0f, 0.045f, -0.005f, 0.2f, 0.06f, 0.022f, 0.06f, BODY, 0.6f)
        part(scene, renderer.sphere, pose, if (rightSide) -0.012f else 0.012f, 0.058f, -0.012f, 0f, 0.013f, 0.008f, 0.013f, if (pressed) ACCENT else BUTTON, 0.7f)
        part(scene, renderer.sphere, pose, if (rightSide) 0.012f else -0.012f, 0.058f, 0.006f, 0f, 0.011f, 0.007f, 0.011f, BUTTON, 0.7f)
        part(scene, renderer.sphere, pose, 0f, 0.058f, 0.018f, 0f, 0.012f, 0.008f, 0.012f, STICK, 0.3f)
        // the tracking ring around the head, and the trigger under it
        part(scene, ringMesh, pose, 0f, 0.05f, -0.02f, 0.5f, 0.11f, 0.11f, 0.11f, RING, 0.8f)
        part(scene, renderer.cube, pose, 0f, 0.012f, -0.035f, -0.5f, 0.016f, 0.03f, 0.012f, if (pressed) ACCENT else TRIGGER, 0.6f)
    }

    private fun part(scene: FrameScene, mesh: GpuMesh, pose: Pose, x: Float, y: Float, z: Float, pitch: Float, sx: Float, sy: Float, sz: Float, color: FloatArray, gloss: Float) {
        val d = scene.addDraw()
        d.mesh = mesh
        color.copyInto(d.color)
        d.gloss = gloss
        d.metal = 0.2f
        d.late = true   // over the UI like the hands
        pose.orientation.rotate(tmp.set(x, y, z), pos).add(pose.position)
        pose.orientation.mul(Quat.fromAxisAngle(1f, 0f, 0f, pitch, q2), q)
        Mat4.compose(pos, q, sx, sy, sz, d.matrix)
    }

    /**
     * The hand closed around the handle, in the world: the fingers wrap the grip, the thumb rests
     * on the face buttons. Replaces the tracked joints of [out] for drawing.
     */
    fun holdingHand(pose: Pose, rightSide: Boolean, out: HandState) {
        val s = if (rightSide) 1f else -1f
        fun put(j: Int, x: Float, y: Float, z: Float) { pose.orientation.rotate(tmp.set(x * s, y, z), out.joints[j]).add(pose.position) }
        put(HandJoint.WRIST, 0.035f, -0.085f, 0.07f)
        // fingers: knuckles on the outer side, wrapping round the front of the handle to its inner side
        val fingers = arrayOf(
            intArrayOf(HandJoint.INDEX_MCP, HandJoint.INDEX_PIP, HandJoint.INDEX_DIP, HandJoint.INDEX_TIP),
            intArrayOf(HandJoint.MIDDLE_MCP, HandJoint.MIDDLE_PIP, HandJoint.MIDDLE_DIP, HandJoint.MIDDLE_TIP),
            intArrayOf(HandJoint.RING_MCP, HandJoint.RING_PIP, HandJoint.RING_DIP, HandJoint.RING_TIP),
            intArrayOf(HandJoint.PINKY_MCP, HandJoint.PINKY_PIP, HandJoint.PINKY_DIP, HandJoint.PINKY_TIP),
        )
        for ((f, j) in fingers.withIndex()) {
            // down the handle, finger by finger
            val y = 0.01f - f * 0.021f
            val z = 0.012f + f * 0.012f
            val r = if (f == 3) 0.028f else 0.034f
            put(j[0], 0.04f, y, z + 0.01f)
            put(j[1], 0.012f, y - 0.004f, z - r)
            put(j[2], -0.022f, y - 0.006f, z - r * 0.6f)
            put(j[3], -0.03f, y - 0.008f, z - 0.002f)
        }
        // the thumb along the top, its tip on the buttons
        put(HandJoint.THUMB_CMC, 0.018f, -0.045f, 0.06f)
        put(HandJoint.THUMB_MCP, 0.006f, 0.0f, 0.045f)
        put(HandJoint.THUMB_IP, 0.0f, 0.04f, 0.025f)
        put(HandJoint.THUMB_TIP, -0.004f, 0.062f, 0.002f)
        out.state = TrackingState.TRACKING
        out.confidence = 1f
    }

    fun release() { ring?.release(); ring = null }

    companion object {
        private const val HANDLE_TILT = 0.45f
        private val BODY = floatArrayOf(0.14f, 0.14f, 0.16f, 1f)
        private val BUTTON = floatArrayOf(0.32f, 0.33f, 0.36f, 1f)
        private val STICK = floatArrayOf(0.08f, 0.08f, 0.09f, 1f)
        private val TRIGGER = floatArrayOf(0.22f, 0.22f, 0.25f, 1f)
        private val RING = floatArrayOf(0.88f, 0.89f, 0.92f, 1f)
        private val ACCENT = floatArrayOf(0.25f, 0.55f, 1f, 1f)
    }
}
