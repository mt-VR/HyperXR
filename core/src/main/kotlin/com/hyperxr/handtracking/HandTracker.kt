package com.hyperxr.handtracking

import com.hyperxr.math.Vec3

/**
 * Temporal layer for one hand. Each accepted frame goes through:
 *  1. skeleton fit: the observation is fitted to the user's bone lengths (no stretching bones);
 *  2. per-joint constant-velocity Kalman filter, weighted by how trustworthy the depth is;
 *  3. One Euro filter on the Kalman estimate (removes the remaining jitter when the hand is still,
 *     stays responsive when it moves);
 *  4. skeleton fit again, then prediction to display time with the Kalman velocity.
 * Plausibility checks, loss coasting and jump-free recovery work on top of that.
 */
class HandTracker(handedness: Handedness, minCutoff: Float = 1.2f, beta: Float = 0.02f) {
    val state = HandState(handedness)
    val skeleton = HandSkeleton()

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
    /** Measurement standard deviation (m) with a depth map, and with the hand-size prior. */
    var depthMapSigma = 0.005f
    var sizePriorSigma = 0.012f

    private val filters = Array(HandJoint.COUNT * 3) { OneEuroFilter(minCutoff, beta) }
    private val kalman = Array(HandJoint.COUNT * 3) { Kalman1D(q = 3f) }
    private val meas = Array(HandJoint.COUNT) { Vec3() }
    private val offset = Array(HandJoint.COUNT) { Vec3() }
    private val gestures = GestureRecognizer()
    private var lastSeenNs = 0L
    private var lastUpdateNs = 0L
    private var recoveryStartNs = 0L

    fun setFilterParams(minCutoff: Float, beta: Float) {
        filters.forEach { it.minCutoff = minCutoff; it.beta = beta }
    }

    /** Kalman process noise (m/s^2)^2: higher follows fast hands better, lower smooths more. */
    fun setProcessNoise(q: Float) { kalman.forEach { it.q = q } }

    /** Pinch closes when thumb-index distance / palm length drops below this ratio (scale-free). */
    var pinchRatio: Float
        get() = gestures.pinchOn
        set(v) { gestures.pinchOn = v; gestures.pinchOff = v * 1.45f }

    /**
     * @param rawPinchRatio pinch ratio measured on this frame's model landmarks (preferred over the
     *   filtered camera-space joints, which lag and carry depth noise); NaN to use the joints.
     */
    fun update(joints: Array<Vec3>?, score: Float, depthSource: DepthSource, timestampNs: Long, rawPinchRatio: Float = Float.NaN) {
        val dt = if (lastUpdateNs == 0L) 0f else ((timestampNs - lastUpdateNs) / 1e9f).coerceIn(0f, 0.25f)
        lastUpdateNs = timestampNs
        state.timestampNs = timestampNs

        val accepted = joints != null && score >= minScore && isPlausible(joints)
        if (!accepted) { handleMissing(timestampNs, dt); return }
        joints!!

        for (i in 0 until HandJoint.COUNT) meas[i].set(joints[i])
        skeleton.learn(meas, trusted = depthSource == DepthSource.DEPTH_MAP)
        skeleton.fit(meas)

        when (state.state) {
            TrackingState.NOT_TRACKED -> {
                resetFilters()
                offset.forEach { it.set(0f, 0f, 0f) }
                state.state = TrackingState.TRACKING
            }
            TrackingState.LOST_PREDICTING -> {
                // Measure the jump between the extrapolated pose and the new observation at the wrist.
                val jump = meas[HandJoint.WRIST].distanceTo(state.joints[HandJoint.WRIST])
                if (jump > jumpThreshold) {
                    // Re-acquire: restart the filters on the new pose but keep the visual position
                    // continuous by fading out the offset between old and new.
                    for (i in 0 until HandJoint.COUNT) offset[i].set(state.joints[i]).sub(meas[i])
                    resetFilters()
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

        // less trusted observations (size prior, low score) count for less in the Kalman update
        val sigma = (if (depthSource == DepthSource.DEPTH_MAP) depthMapSigma else sizePriorSigma) / score.coerceIn(0.3f, 1f)
        val r = sigma * sigma
        for (i in 0 until HandJoint.COUNT) {
            val m = meas[i]
            val kx = kalman[i * 3]; val ky = kalman[i * 3 + 1]; val kz = kalman[i * 3 + 2]
            kx.predict(dt); ky.predict(dt); kz.predict(dt)
            kx.update(m.x, r); ky.update(m.y, r); kz.update(m.z, r)
            state.joints[i].set(
                filters[i * 3].filter(kx.x, dt),
                filters[i * 3 + 1].filter(ky.x, dt),
                filters[i * 3 + 2].filter(kz.x, dt),
            )
        }
        skeleton.fit(state.joints)
        val p = predictionSeconds
        for (i in 0 until HandJoint.COUNT) {
            state.joints[i].x += kalman[i * 3].v * p + offset[i].x * fade
            state.joints[i].y += kalman[i * 3 + 1].v * p + offset[i].y * fade
            state.joints[i].z += kalman[i * 3 + 2].v * p + offset[i].z * fade
        }
        if (p != 0f) skeleton.fit(state.joints)
        if (fade == 0f) offset.forEach { it.set(0f, 0f, 0f) }
        lastSeenNs = timestampNs
        state.confidence = score
        state.depthSource = depthSource
        gestures.update(state, rawPinchRatio)
    }

    private fun resetFilters() {
        filters.forEach { it.reset() }
        kalman.forEach { it.reset() }
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
                state.state = TrackingState.LOST_PREDICTING
                // Kalman prediction with a velocity that decays quickly: short gaps stay continuous,
                // long ones leave the hand where it was last seen instead of flying off.
                val decay = kotlin.math.exp(-dt / coastDecaySeconds)
                for (i in 0 until HandJoint.COUNT) {
                    val kx = kalman[i * 3]; val ky = kalman[i * 3 + 1]; val kz = kalman[i * 3 + 2]
                    state.joints[i].x += kx.v * dt
                    state.joints[i].y += ky.v * dt
                    state.joints[i].z += kz.v * dt
                    kx.predict(dt); ky.predict(dt); kz.predict(dt)
                    kx.dampVelocity(decay); ky.dampVelocity(decay); kz.dampVelocity(decay)
                }
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
    var pinchOn = 0.38f
    var pinchOff = 0.55f
    private val pinch = Hysteresis(pinchOn, pinchOff, activeWhenBelow = true)

    fun reset() = pinch.reset()

    fun update(s: HandState, rawRatio: Float = Float.NaN) {
        val j = s.joints
        val palm = j[HandJoint.WRIST].distanceTo(j[HandJoint.MIDDLE_MCP]).coerceAtLeast(1e-4f)
        val ratio = if (rawRatio.isFinite()) rawRatio else j[HandJoint.THUMB_TIP].distanceTo(j[HandJoint.INDEX_TIP]) / palm
        pinch.onThreshold = pinchOn; pinch.offThreshold = pinchOff
        pinch.update(ratio)
        s.pinchRatio = ratio
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
