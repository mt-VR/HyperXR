package com.hyperxr.xr

import com.hyperxr.handtracking.ArmSolver
import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.HandSide
import com.hyperxr.handtracking.HandState
import com.hyperxr.handtracking.Handedness
import com.hyperxr.handtracking.TrackingState
import com.hyperxr.math.Pose
import com.hyperxr.math.Vec3
import com.hyperxr.rendering.EyeView
import com.hyperxr.rendering.FrameScene
import com.hyperxr.rendering.SkinnedHand
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The hands in VR: a human arm and hand with skin (SkinnedHand) moved by the tracked joints. The
 * forearm points where the camera saw it (skin segmentation on the NPU, HandState.armDir) when it
 * was seen in the last moments, otherwise where a two-bone IK from your shoulder puts it; the upper
 * arm, in a T-shirt sleeve, goes from the elbow to the shoulder.
 */
class RealHand {
    private var mesh: SkinnedHand? = null
    private var failed = false
    private val rest = Array(2) { Array(JOINTS) { Vec3() } }
    private val restFrames = Array(2) { FloatArray(SkinnedHand.BONES * 12) }
    private val restLen = Array(2) { FloatArray(SkinnedHand.BONES) }
    private val cur = Array(JOINTS) { Vec3() }
    private val bones = Array(2) { FloatArray(SkinnedHand.BONES * 16) }
    private val drawn = BooleanArray(2)
    private val fade = FloatArray(2)
    private val armDir = Array(2) { Vec3(0f, -0.6f, 0.8f) }
    private val armInit = BooleanArray(2)
    private val lastZ = Array(2) { Array(SkinnedHand.BONES) { Vec3(0f, 0f, 1f) } }
    private var light = floatArrayOf(-0.4f, -1f, -0.3f); private var lightColor = floatArrayOf(1f, 1f, 1f); private var ambient = floatArrayOf(0.3f, 0.3f, 0.35f)
    /** Skin tone (sRGB 0..1) and the sleeve's colour. */
    var skin = floatArrayOf(0.80f, 0.59f, 0.48f)
    var sleeve = floatArrayOf(0.17f, 0.2f, 0.27f)

    private val fwd = Vec3(); private val right = Vec3(); private val pole = Vec3(); private val t = Vec3()
    private val px = Vec3(); private val pn = Vec3(); private val out = Vec3()
    private val y = Vec3(); private val z = Vec3(); private val x = Vec3(); private val ik = Vec3(); private val outv = Vec3()
    private val tmpFrame = FloatArray(12)

    private fun ensure(): SkinnedHand? {
        if (mesh != null || failed) return mesh
        return try {
            SkinnedHand(com.hyperxr.HyperXRApp.instance.assets).also { m ->
                mesh = m
                for (s in 0..1) {
                    val mirror = if (s == 0) -1f else 1f   // 0 = left (mirrored), 1 = right
                    for (j in 0 until JOINTS) rest[s][j].set(m.restJoints[j * 3] * mirror, m.restJoints[j * 3 + 1], m.restJoints[j * 3 + 2])
                    val outward = Vec3(mirror, 0f, 0f)
                    for (b in 0 until SkinnedHand.BONES) {
                        val (ja, jb) = PAIRS[b]
                        restLen[s][b] = t.set(rest[s][jb]).sub(rest[s][ja]).length()
                        frame(rest[s], b, outward, restFrames[s], b * 12, null)
                    }
                }
            }
        } catch (e: Throwable) {
            failed = true
            com.hyperxr.diagnostics.Diagnostics.log("VR hands: mesh failed (${e.message})")
            null
        }
    }

    /** Places both arms for this frame (GL thread, before the eyes are drawn). */
    fun submit(scene: FrameScene, hands: Array<HandState>, head: Pose) {
        ensure() ?: run { drawn.fill(false); return }
        scene.lightDir.copyInto(light); scene.lightColor.copyInto(lightColor); scene.ambient.copyInto(ambient)
        // the head's yaw only: shoulders don't tilt when you look down
        head.orientation.rotate(fwd.set(0f, 0f, -1f), fwd); fwd.y = 0f
        if (fwd.length() < 1e-3f) fwd.set(0f, 0f, -1f)
        fwd.normalize()
        right.set(-fwd.z, 0f, fwd.x)
        val now = System.nanoTime()
        // which hand each one really is (its shape and place, not only the detector's label), so a
        // left hand never gets the right arm from the right shoulder
        val dt = if (lastNs == 0L) 0.016f else ((now - lastNs) / 1e9f).coerceIn(0f, 0.1f)
        lastNs = now
        drawn.fill(false)
        for ((i, h) in hands.withIndex()) {
            if (!h.isUsable) { deciders[i].reset(); continue }
            lateral[i] = t.set(h.joints[HandJoint.WRIST]).sub(head.position).dot(right)
            isRight[i] = deciders[i].update(h.handedness == Handedness.RIGHT, h.joints, lateral[i], dt)
        }
        if (hands.size == 2 && hands[0].isUsable && hands[1].isUsable) {
            val (r0, r1) = HandSide.separate(isRight[0], lateral[0], isRight[1], lateral[1])
            isRight[0] = r0; isRight[1] = r1
        }
        for ((i, h) in hands.withIndex()) {
            if (!h.isUsable) continue
            val s = if (isRight[i]) 1 else 0
            // a hand that changed side starts its arm afresh
            if (owner[s] != i) { owner[s] = i; armInit[s] = false }
            drawn[s] = true
            fade[s] = if (h.state == TrackingState.LOST_PREDICTING) 0.8f else 1f
            val side = if (s == 1) 1f else -1f
            for (j in 0 until HandJoint.COUNT) cur[j].set(h.joints[j])
            val shoulder = cur[SHOULDER].set(head.position).addScaled(right, 0.18f * side).addScaled(fwd, -0.04f)
            shoulder.y -= 0.24f
            val wrist = cur[HandJoint.WRIST]
            // the forearm: what the camera saw if recent, else the IK guess; turned smoothly
            solveIk(shoulder, wrist, side, ik)
            val seen = h.armNs != 0L && now - h.armNs < 400_000_000L && h.armConfidence > 0.25f
            val target = if (seen) t.set(h.armDir).scale(h.armConfidence.coerceAtMost(1f)).addScaled(ik, 1f - h.armConfidence.coerceAtMost(1f)).normalize() else ik
            if (!armInit[s]) { armDir[s].set(target); armInit[s] = true }
            else armDir[s].scale(0.8f).addScaled(target, 0.2f).normalize()
            cur[ELBOW].set(wrist).addScaled(armDir[s], ArmSolver.FOREARM)
            // hand size against the model's, across the palm and along it
            val lenScale = t.set(cur[HandJoint.MIDDLE_MCP]).sub(wrist).length() / restLen[s][3].coerceAtLeast(1e-3f)
            val wid = t.set(cur[HandJoint.INDEX_MCP]).sub(cur[HandJoint.PINKY_MCP]).length() / rest[s][HandJoint.INDEX_MCP].let { a -> t.set(a).sub(rest[s][HandJoint.PINKY_MCP]).length() }.coerceAtLeast(1e-3f)
            val sw = ((lenScale + wid) * 0.5f).coerceIn(0.8f, 1.3f)
            val outward = outv.set(right).scale(side)
            val f = tmpFrame
            for (b in 0 until SkinnedHand.BONES) {
                frame(cur, b, outward, f, 0, lastZ[s][b])
                val (ja, jb) = PAIRS[b]
                val len = out.set(cur[jb]).sub(cur[ja]).length()
                val sl = (len / restLen[s][b].coerceAtLeast(1e-4f)).coerceIn(0.4f, 2.2f)
                val sx = if (b <= 2) 1f else sw
                skinMatrix(restFrames[s], b * 12, f, sx, sl, bones[s], b * 16)
            }
        }
        for (side in 0..1) if (!drawn[side]) owner[side] = -1
    }

    private val deciders = Array(2) { HandSide.Decider() }
    private val isRight = BooleanArray(2)
    private val lateral = FloatArray(2)
    private val owner = IntArray(2) { -1 }
    private var lastNs = 0L

    /** Draws the arms placed by [submit], depth-tested over the scene and the panels. GL thread. */
    fun render(eye: EyeView) {
        val m = mesh ?: return
        if (!drawn[0] && !drawn[1]) return
        android.opengl.GLES30.glEnable(android.opengl.GLES30.GL_DEPTH_TEST)
        android.opengl.GLES30.glEnable(android.opengl.GLES30.GL_CULL_FACE)
        android.opengl.GLES30.glDisable(android.opengl.GLES30.GL_BLEND)
        for (s in 0..1) if (drawn[s]) m.draw(if (s == 1) m.right else m.left, bones[s], eye, light, lightColor, ambient, skin, sleeve, fade[s])
    }

    private fun solveIk(shoulder: Vec3, wrist: Vec3, side: Float, outDir: Vec3) {
        val l1 = ArmSolver.UPPER_ARM; val l2 = ArmSolver.FOREARM
        t.set(wrist).sub(shoulder)
        var d = t.length()
        if (d < 1e-4f) { outDir.set(0f, -1f, 0f); return }
        t.scale(1f / d)
        d = d.coerceAtMost(l1 + l2 - 1e-3f)
        val along = (l1 * l1 - l2 * l2 + d * d) / (2 * d)
        val hgt = sqrt((l1 * l1 - along * along).coerceAtLeast(0f))
        // elbows hang down and a little out and back
        pole.set(0f, -1f, 0f).addScaled(right, 0.45f * side).addScaled(fwd, -0.25f)
        pole.addScaled(t, -pole.dot(t))
        if (pole.length() < 1e-4f) pole.set(0f, -1f, 0f)
        pole.normalize()
        // elbow = shoulder + t*along + pole*hgt; direction from the wrist to it
        outDir.set(shoulder).addScaled(t, along).addScaled(pole, hgt).sub(wrist).normalize()
    }

    /**
     * Bone [b]'s frame from joints [j]: origin at its first joint, y along it, z across from the
     * reference (palm side for fingers, palm normal for the thumb, outward for the arm), into [o]
     * at [off] as x, y, z, origin (12 floats).
     */
    private fun frame(j: Array<Vec3>, b: Int, outward: Vec3, o: FloatArray, off: Int, prevZ: Vec3?) {
        val (ja, jb) = PAIRS[b]
        y.set(j[jb]).sub(j[ja])
        if (y.length() < 1e-5f) y.set(0f, 0f, -1f)
        y.normalize()
        val ref = when (REF[b]) {
            R_OUT -> outward
            R_PX -> px.set(j[HandJoint.INDEX_MCP]).sub(j[HandJoint.PINKY_MCP]).normalize()
            else -> { px.set(j[HandJoint.INDEX_MCP]).sub(j[HandJoint.WRIST]); t.set(j[HandJoint.PINKY_MCP]).sub(j[HandJoint.WRIST]); px.cross(t, pn); pn.normalize() }
        }
        ref.cross(y, z)
        if (z.length() < 0.05f) {
            // the reference lies along the bone: keep last frame's twist
            if (prevZ != null) z.set(prevZ).addScaled(y, -prevZ.dot(y)) else z.set(if (abs(y.y) < 0.9f) 0f else 1f, if (abs(y.y) < 0.9f) 1f else 0f, 0f).cross(y, z)
        }
        z.normalize()
        prevZ?.set(z)
        y.cross(z, x)
        o[off] = x.x; o[off + 1] = x.y; o[off + 2] = x.z
        o[off + 3] = y.x; o[off + 4] = y.y; o[off + 5] = y.z
        o[off + 6] = z.x; o[off + 7] = z.y; o[off + 8] = z.z
        o[off + 9] = j[ja].x; o[off + 10] = j[ja].y; o[off + 11] = j[ja].z
    }

    /** cur * scale(sx, sl, sx) * rest^-1, column-major 4x4 into [m] at [mo]. */
    private fun skinMatrix(r: FloatArray, ro: Int, c: FloatArray, sx: Float, sl: Float, m: FloatArray, mo: Int) {
        // K = C * S * R^T (3x3), columns of C/R are x, y, z
        val s0 = sx; val s1 = sl; val s2 = sx
        for (col in 0..2) for (row in 0..2) {
            // (C S R^T)[row][col] = sum_k C[row][k] * s_k * R[col][k]
            val v = c[row] * s0 * r[ro + col] + c[3 + row] * s1 * r[ro + 3 + col] + c[6 + row] * s2 * r[ro + 6 + col]
            m[mo + col * 4 + row] = v
        }
        m[mo + 3] = 0f; m[mo + 7] = 0f; m[mo + 11] = 0f; m[mo + 15] = 1f
        val ax = r[ro + 9]; val ay = r[ro + 10]; val az = r[ro + 11]
        for (row in 0..2) m[mo + 12 + row] = c[9 + row] - (m[mo + row] * ax + m[mo + 4 + row] * ay + m[mo + 8 + row] * az)
    }

    companion object {
        private const val JOINTS = 23
        private const val ELBOW = 21
        private const val SHOULDER = 22
        private const val R_OUT = 0; private const val R_PX = 1; private const val R_PN = 2
        private val PAIRS: Array<Pair<Int, Int>> = buildList {
            add(SHOULDER to ELBOW); add(ELBOW to HandJoint.WRIST); add(ELBOW to HandJoint.WRIST); add(HandJoint.WRIST to HandJoint.MIDDLE_MCP)
            val chains = arrayOf(intArrayOf(1, 2, 3, 4), intArrayOf(5, 6, 7, 8), intArrayOf(9, 10, 11, 12), intArrayOf(13, 14, 15, 16), intArrayOf(17, 18, 19, 20))
            for (c in chains) for (k in 0..2) add(c[k] to c[k + 1])
        }.toTypedArray()
        private val REF = IntArray(SkinnedHand.BONES) { b -> when (b) { 0, 1 -> R_OUT; 4, 5, 6 -> R_PN; else -> R_PX } }
    }
}
