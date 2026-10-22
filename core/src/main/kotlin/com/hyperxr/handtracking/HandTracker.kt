package com.hyperxr.handtracking

import com.hyperxr.math.Vec3

/**
 * Temporal layer for one hand: filtering, plausibility checks, loss prediction and jump-free
 * recovery. Input is world-space joints (from [DepthFusion]); output is [state].
 */
class HandTracker(handedness: Handedness, minCutoff: Float = 1.2f, beta: Float = 0.02f) {
    val state = HandState(handedness)

    var minScore = 0.3f
    /**
     * How long the hand is kept (coasting, still usable) after the detector misses it. Phone
     * detectors drop single frames constantly (motion blur, a finger crossing the palm); hiding the
     * hand on every miss made it flicker and disappear.
     */
    var lostTimeoutNs = 450_000_000L
    /** Coasting velocity decays with this time constant, so a lost hand settles instead of drifting. */
    var coastDecaySeconds = 0.06f
    /** Positions further than this from the prediction are treated as a re-acquisition. */
    var jumpThreshold = 0.12f
    var recoveryNs = 120_000_000L
    var predictionSeconds = 0.0f

    private val filters = Array(HandJoint.COUNT * 3) { OneEuroFilter(minCutoff, beta) }
    private val raw = Array(HandJoint.COUNT) { Vec3() }
    private val offset = Array(HandJoint.COUNT) { Vec3() }
    private val coast = FloatArray(HandJoint.COUNT * 3)
    private val gestures = GestureRecognizer()
    private var lastSeenNs = 0L
    private var lastUpdateNs = 0L
    private var recoveryStartNs = 0L

    fun setFilterParams(minCutoff: Float, beta: Float) {
        filters.forEach { it.minCutoff = minCutoff; it.beta = beta }
    }

    /** Pinch closes when thumb-index distance / palm length drops below this ratio (scale-free). */
    var pinchRatio: Float
        get() = gestures.pinchOn
        set(v) { gestures.pinchOn = v; gestures.pinchOff = v * 1.6f }

    /** Feed one frame. [joints] is null when the detector did not find this hand. */
    fun update(joints: Array<Vec3>?, score: Float, depthSource: DepthSource, timestampNs: Long) {
        val dt = if (lastUpdateNs == 0L) 0f else ((timestampNs - lastUpdateNs) / 1e9f).coerceIn(0f, 0.25f)
        lastUpdateNs = timestampNs
        state.timestampNs = timestampNs

        val accepted = joints != null && score >= minScore && isPlausible(joints)
        if (!accepted) { handleMissing(timestampNs, dt); return }
        joints!!

        when (state.state) {
            TrackingState.NOT_TRACKED -> {
                filters.forEach { it.reset() }
                offset.forEach { it.set(0f, 0f, 0f) }
                state.state = TrackingState.TRACKING
            }
            TrackingState.LOST_PREDICTING -> {
                // Measure the jump between the extrapolated pose and the new observation at the wrist.
                val jump = joints[HandJoint.WRIST].distanceTo(state.joints[HandJoint.WRIST])
                if (jump > jumpThreshold) {
                    // Re-acquire: restart the filters on the new pose but keep the visual position
                    // continuous by fading out the offset between old and new.
                    for (i in 0 until HandJoint.COUNT) offset[i].set(state.joints[i]).sub(joints[i])
                    filters.forEach { it.reset() }
                } else {
                    offset.forEach { it.set(0f, 0f, 0f) }
                }
                recoveryStartNs = timestampNs
                state.state = TrackingState.RECOVERING
            }
            TrackingState.RECOVERING -> if (timestampNs - recoveryStartNs > recoveryNs) state.state = TrackingState.TRACKING
            TrackingState.TRACKING -> Unit
        }

        val fade = if (state.state == TrackingState.RECOVERING) {
            (1f - (timestampNs - recoveryStartNs).toFloat() / recoveryNs).coerceIn(0f, 1f)
        } else 0f

        for (i in 0 until HandJoint.COUNT) {
            val src = joints[i]
            val fx = filters[i * 3].filter(src.x, dt)
            val fy = filters[i * 3 + 1].filter(src.y, dt)
            val fz = filters[i * 3 + 2].filter(src.z, dt)
            raw[i].set(fx, fy, fz)
            val p = predictionSeconds
            state.joints[i].set(
                fx + filters[i * 3].velocity * p + offset[i].x * fade,
                fy + filters[i * 3 + 1].velocity * p + offset[i].y * fade,
                fz + filters[i * 3 + 2].velocity * p + offset[i].z * fade,
            )
        }
        if (fade == 0f) offset.forEach { it.set(0f, 0f, 0f) }
        lastSeenNs = timestampNs
        state.confidence = score
        state.depthSource = depthSource
        gestures.update(state)
    }

    private fun handleMissing(now: Long, dt: Float) {
        when (state.state) {
            TrackingState.TRACKING, TrackingState.RECOVERING, TrackingState.LOST_PREDICTING -> {
                if (now - lastSeenNs > lostTimeoutNs) {
                    state.state = TrackingState.NOT_TRACKED
                    state.confidence = 0f
                    state.gesture = Gesture.NONE
                    state.pinchStrength = 0f
                    gestures.reset()
                    return
                }
                if (state.state != TrackingState.LOST_PREDICTING) {
                    for (k in coast.indices) coast[k] = filters[k].velocity.coerceIn(-1.5f, 1.5f)
                    state.state = TrackingState.LOST_PREDICTING
                }
                // Extrapolate with a velocity that decays quickly: short gaps stay continuous, long
                // ones leave the hand where it was last seen instead of flying off.
                val decay = kotlin.math.exp(-dt / coastDecaySeconds)
                for (i in 0 until HandJoint.COUNT) {
                    state.joints[i].x += coast[i * 3] * dt
                    state.joints[i].y += coast[i * 3 + 1] * dt
                    state.joints[i].z += coast[i * 3 + 2] * dt
                }
                for (k in coast.indices) coast[k] *= decay
                state.confidence *= 0.85f
            }
            TrackingState.NOT_TRACKED -> Unit
        }
    }

    /** Rejects anatomically impossible skeletons (bad detections) before they reach the filters. */
    private fun isPlausible(j: Array<Vec3>): Boolean {
        val palm = j[HandJoint.WRIST].distanceTo(j[HandJoint.MIDDLE_MCP])
        if (palm < 0.04f || palm > 0.16f) return false
        for (bone in HandJoint.BONES) {
            if (j[bone[0]].distanceTo(j[bone[1]]) > 0.13f) return false
        }
        return true
    }
}

/** Gesture classification from 3D joints, with hysteresis so states do not flicker. */
class GestureRecognizer {
    // Thresholds are ratios to the palm length, so pinch works the same whatever the estimated
    // hand scale or distance (an absolute millimetre threshold breaks when depth is off).
    var pinchOn = 0.25f
    var pinchOff = 0.40f
    private val pinch = Hysteresis(pinchOn, pinchOff, activeWhenBelow = true)

    fun reset() = pinch.reset()

    fun update(s: HandState) {
        val j = s.joints
        val palm = j[HandJoint.WRIST].distanceTo(j[HandJoint.MIDDLE_MCP]).coerceAtLeast(1e-4f)
        val ratio = j[HandJoint.THUMB_TIP].distanceTo(j[HandJoint.INDEX_TIP]) / palm
        pinch.onThreshold = pinchOn; pinch.offThreshold = pinchOff
        pinch.update(ratio)
        s.pinchStrength = (1f - (ratio - pinchOn) / (1.0f - pinchOn)).coerceIn(0f, 1f)

        val index = extended(j, HandJoint.INDEX_MCP, HandJoint.INDEX_PIP, HandJoint.INDEX_TIP)
        val middle = extended(j, HandJoint.MIDDLE_MCP, HandJoint.MIDDLE_PIP, HandJoint.MIDDLE_TIP)
        val ring = extended(j, HandJoint.RING_MCP, HandJoint.RING_PIP, HandJoint.RING_TIP)
        val pinky = extended(j, HandJoint.PINKY_MCP, HandJoint.PINKY_PIP, HandJoint.PINKY_TIP)
        val extendedCount = listOf(index, middle, ring, pinky).count { it }

        s.gesture = when {
            pinch.active -> Gesture.PINCH
            index && !middle && !ring && !pinky -> Gesture.POINT
            extendedCount == 0 -> if (j[HandJoint.THUMB_TIP].distanceTo(j[HandJoint.MIDDLE_PIP]) < 0.05f) Gesture.FIST else Gesture.GRAB
            extendedCount == 4 -> Gesture.OPEN_PALM
            else -> Gesture.NONE
        }
    }

    /** A finger is extended when its tip is clearly further from the wrist than its PIP joint. */
    private fun extended(j: Array<Vec3>, mcp: Int, pip: Int, tip: Int): Boolean {
        val w = j[HandJoint.WRIST]
        return w.distanceTo(j[tip]) > w.distanceTo(j[pip]) * 1.15f && w.distanceTo(j[tip]) > w.distanceTo(j[mcp]) * 1.35f
    }
}
