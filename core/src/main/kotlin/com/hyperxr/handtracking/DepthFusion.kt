package com.hyperxr.handtracking

import com.hyperxr.math.Pose
import com.hyperxr.math.Vec3
import kotlin.math.sqrt

/** Samples metric depth (meters along the camera axis) at pixel coordinates; NaN when invalid. */
fun interface DepthSampler {
    fun depthAt(px: Float, py: Float): Float
}

/**
 * Lifts 2D landmarks (+ model-relative z) to metric 3D camera space and then to world space.
 *
 * Depth of the palm comes from, in order of preference:
 *  1. a depth map (ARCore Depth API) sampled at the palm landmarks (median, robust to edges);
 *  2. a hand-size prior: the 3D palm length of the landmark skeleton (in pixels) compared with the
 *     average adult palm length. This is an estimate with a known bias for small/large hands and is
 *     flagged as such ([DepthSource.HAND_SIZE_PRIOR]) with reduced confidence.
 * We never invent depth when neither is available; [fuse] returns false instead.
 */
class DepthFusion(var palmLengthMeters: Float = 0.09f) {
    companion object {
        /** How much a fingertip's own depth sample counts against the palm-based estimate. */
        const val TIP_DEPTH_WEIGHT = 0.6f
        const val TIP_NEARER_M = 0.06f
        const val TIP_FARTHER_M = 0.035f
    }

    /** Depth samples outside [priorZ * min, priorZ * max] are treated as background hits. */
    var minDepthRatio = 0.6f
    var maxDepthRatio = 1.55f
    private val palmIdx = intArrayOf(HandJoint.WRIST, HandJoint.INDEX_MCP, HandJoint.MIDDLE_MCP, HandJoint.RING_MCP, HandJoint.PINKY_MCP)
    private val depthScratch = FloatArray(palmIdx.size)
    private val tmp = Vec3()

    class Result {
        val cameraSpace: Array<Vec3> = Array(HandJoint.COUNT) { Vec3() }
        var depthSource = DepthSource.NONE
        var confidence = 0f
    }

    /**
     * @param intr intrinsics in pixels of the image the landmarks refer to.
     * @param depth optional depth sampler in the same pixel space.
     * @param cameraPose camera pose in world space (OpenGL convention: -Z forward, +Y up), or null for camera space output.
     * @param out receives world-space joints in [Result.cameraSpace] transformed by cameraPose.
     */
    fun fuse(obs: LandmarkObservation, intr: CameraIntrinsics, depth: DepthSampler?, cameraPose: Pose?, out: Result): Boolean {
        val w = intr.width.toFloat()
        val h = intr.height.toFloat()
        val img = obs.image

        var palmZ = Float.NaN
        var source = DepthSource.NONE
        var conf = obs.score

        // Distance implied by the hand's size in the image (always available, used as a sanity bound).
        val lenPx = maxOf(
            dist3(img, HandJoint.WRIST, HandJoint.MIDDLE_MCP, w, h),
            dist3(img, HandJoint.INDEX_MCP, HandJoint.PINKY_MCP, w, h) * (0.09f / 0.075f),
        )
        if (lenPx < 4f) return false
        val priorZ = intr.fx * palmLengthMeters / lenPx

        if (depth != null) {
            // Depth maps are coarse: palm samples often hit the background between the fingers or
            // the wall behind the hand. Only samples consistent with the hand's image size count.
            var n = 0
            for (j in palmIdx) {
                val d = depth.depthAt(img[j * 3] * w, img[j * 3 + 1] * h)
                if (d.isFinite() && d > 0.05f && d < 3f && d > priorZ * minDepthRatio && d < priorZ * maxDepthRatio) depthScratch[n++] = d
            }
            if (n >= 2) {
                depthScratch.sort(0, n)
                palmZ = depthScratch[n / 2]
                source = DepthSource.DEPTH_MAP
                conf *= 0.5f + 0.5f * n / palmIdx.size
            }
        }
        if (palmZ.isNaN()) {
            palmZ = priorZ
            source = DepthSource.HAND_SIZE_PRIOR
            conf *= 0.6f
        }
        if (palmZ !in 0.08f..2.5f) return false

        // Mean relative z of palm joints is the reference plane for per-joint depth offsets.
        var zRef = 0f
        for (j in palmIdx) zRef += img[j * 3 + 2]
        zRef /= palmIdx.size

        for (i in 0 until HandJoint.COUNT) {
            val u = img[i * 3] * w
            val v = img[i * 3 + 1] * h
            // relative z is in normalized-width units; convert to metres at the palm distance
            val dz = (img[i * 3 + 2] - zRef) * w * palmZ / intr.fx
            var zi = (palmZ + dz).coerceAtLeast(0.05f)
            // the index tip (what presses buttons) and the thumb tip (the other side of a pinch)
            // measured where they are, not only from the palm
            if (source == DepthSource.DEPTH_MAP && depth != null && (i == HandJoint.THUMB_TIP || i == HandJoint.INDEX_TIP)) {
                val d = tipDepth(depth, u, v, w, h, zi)
                if (!d.isNaN()) zi = zi + (d - zi) * TIP_DEPTH_WEIGHT
            }
            val x = (u - intr.cx) * zi / intr.fx
            val y = -(v - intr.cy) * zi / intr.fy
            val p = out.cameraSpace[i].set(x, y, -zi)
            if (cameraPose != null) {
                cameraPose.transformPoint(tmp.set(p), p)
            }
        }
        out.depthSource = source
        out.confidence = conf.coerceIn(0f, 1f)
        return true
    }

    /**
     * Depth at a fingertip: the nearest consistent sample of a small cross around it (a fingertip
     * is thin, so the coarse depth map often has the wall behind it there). Samples farther than
     * a little behind the model's estimate are the background; much nearer ones are noise.
     */
    private fun tipDepth(depth: DepthSampler, u: Float, v: Float, w: Float, h: Float, model: Float): Float {
        var best = Float.NaN
        val r = maxOf(2f, w / 160f)
        for (k in 0 until 5) {
            val du = when (k) { 1 -> r; 2 -> -r; else -> 0f }
            val dv = when (k) { 3 -> r; 4 -> -r; else -> 0f }
            val d = depth.depthAt((u + du).coerceIn(0f, w - 1), (v + dv).coerceIn(0f, h - 1))
            if (!d.isFinite() || d < model - TIP_NEARER_M || d > model + TIP_FARTHER_M) continue
            if (best.isNaN() || d < best) best = d
        }
        return best
    }

    private fun dist3(img: FloatArray, a: Int, b: Int, w: Float, h: Float): Float {
        val dx = (img[a * 3] - img[b * 3]) * w
        val dy = (img[a * 3 + 1] - img[b * 3 + 1]) * h
        val dz = (img[a * 3 + 2] - img[b * 3 + 2]) * w
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
