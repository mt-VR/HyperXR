package com.hyperxr.handtracking

import com.hyperxr.math.Vec3

/**
 * Kinematic hand skeleton with per-user bone lengths. Landmark detectors produce bones that
 * stretch and shrink from frame to frame (mostly along the camera axis); fitting the observation to
 * fixed bone lengths keeps the hand its real size and removes that noise while preserving every
 * joint direction.
 *
 * Lengths start from the first good observation and then adapt slowly (bounded step per frame),
 * so a single bad frame cannot deform the hand.
 */
class HandSkeleton {
    /** Bone lengths in metres, indexed by child joint (index 0, the wrist, is unused). */
    val lengths = FloatArray(HandJoint.COUNT)
    var calibrated = false; private set
    private var samples = 0
    private val tmp = Vec3()
    private val orig = Array(HandJoint.COUNT) { Vec3() }

    fun reset() { calibrated = false; samples = 0 }

    /**
     * Updates bone lengths from an observation whose scale is trusted ([trusted] = metric depth),
     * or initializes them from any observation the first time.
     */
    fun learn(j: Array<Vec3>, trusted: Boolean) {
        if (!calibrated) {
            for (c in 1 until HandJoint.COUNT) lengths[c] = j[c].distanceTo(j[PARENT[c]]).coerceIn(MIN_BONE, MAX_BONE)
            calibrated = true
            samples = 1
            return
        }
        if (!trusted) return
        samples++
        // bounded steps: converges to the median-like value, robust to outliers
        val step = if (samples < 60) 0.002f else 0.0005f
        for (c in 1 until HandJoint.COUNT) {
            val m = j[c].distanceTo(j[PARENT[c]])
            if (m < MIN_BONE || m > MAX_BONE) continue
            lengths[c] += (m - lengths[c]).coerceIn(-step, step)
        }
    }

    /** Rescales all bones so the palm (wrist to middle knuckle) has length [palm]. */
    fun scaleToPalm(palm: Float) {
        if (!calibrated) return
        val cur = lengths[HandJoint.MIDDLE_MCP]
        if (cur <= 1e-4f) return
        val k = palm / cur
        for (c in 1 until HandJoint.COUNT) lengths[c] *= k
    }

    /**
     * Fits [j] in place to the skeleton: every bone keeps its observed direction and takes its
     * calibrated length, laid out from the wrist outwards.
     */
    fun fit(j: Array<Vec3>) {
        if (!calibrated) return
        for (i in 0 until HandJoint.COUNT) orig[i].set(j[i])
        for (c in ORDER) {
            val p = PARENT[c]
            tmp.set(orig[c]).sub(orig[p])
            val len = tmp.length()
            if (len < 1e-6f) { j[c].set(j[p]); continue }
            tmp.scale(lengths[c] / len)
            j[c].set(j[p]).add(tmp)
        }
    }

    companion object {
        const val MIN_BONE = 0.008f
        const val MAX_BONE = 0.12f
        /** Parent of each joint in the 21-landmark hand. */
        val PARENT = intArrayOf(0, 0, 1, 2, 3, 0, 5, 6, 7, 0, 9, 10, 11, 0, 13, 14, 15, 0, 17, 18, 19)
        /** Joints ordered so every parent comes before its children. */
        val ORDER = intArrayOf(1, 5, 9, 13, 17, 2, 6, 10, 14, 18, 3, 7, 11, 15, 19, 4, 8, 12, 16, 20)
    }
}
