package com.hyperxr.handtracking

import com.hyperxr.math.Vec3

/**
 * How a published hand keeps moving on the display between camera results: the centre's
 * velocity, how much of it to use, and the correction of the last result still blending in.
 * A copy travels with each published [HandState] so the render thread can [extrapolate] safely.
 */
class HandMotion {
    val velocity = Vec3()
    var gain = 0f
    val residual = Array(HandJoint.COUNT) { Vec3() }
    var blendSeconds = 0.03f
    var moving = false

    fun set(o: HandMotion) {
        velocity.set(o.velocity); gain = o.gain; blendSeconds = o.blendSeconds; moving = o.moving
        for (i in 0 until HandJoint.COUNT) residual[i].set(o.residual[i])
    }

    /**
     * How far past "now" the display draws (the frame drawn now reaches the eyes this much later).
     * The tracker uses it to know exactly where the hand was being drawn when a new result
     * arrives, so the new result starts from there instead of jumping.
     */
    var displayAheadS = 0.012f
    /**
     * Between camera results the hand keeps its velocity only for about this long (seconds): a
     * hand that stopped is seen ~50 ms late, and carrying the old velocity the whole time made it
     * swing past the stop and back. 0 = keep the velocity unchanged.
     */
    var coastTauS = 0.015f

    /** Distance factor (seconds of velocity) carried over [t] seconds. */
    fun carried(t: Float): Float = if (coastTauS <= 0f) t else coastTauS * (1f - kotlin.math.exp(-t / coastTauS))

    /**
     * [src]'s joints [sinceSeconds] after its update, drawn [aheadSeconds] in the future: moved
     * as a whole by the velocity (not at all while still), with the last correction fading out
     * over the time since the update only (so it is continuous whatever the display horizon).
     */
    fun extrapolate(src: HandState, sinceSeconds: Float, out: Array<Vec3>, aheadSeconds: Float = displayAheadS) {
        val s = if (moving) carried(sinceSeconds + aheadSeconds) * gain else 0f
        val r = kotlin.math.exp(-sinceSeconds / blendSeconds)
        for (i in 0 until HandJoint.COUNT) out[i].set(src.joints[i]).addScaled(velocity, s).addScaled(residual[i], r)
    }
}

/**
 * Temporal layer for one hand. Each accepted frame goes through:
 *  1. skeleton fit: the observation is fitted to the user's bone lengths (no stretching bones);
 *  2. split into the hand's centre (palm) and its shape (each joint relative to the palm). On a
 *     phone most of the tremor is the whole hand moving together: its depth comes from the
 *     hand-size prior and jumps along the camera ray. So:
 *       - the distance along the ray from the camera is smoothed hard ([depthCutoffHz]),
 *       - the centre is smoothed by One Euro filters that open up with speed (still = steady,
 *         moving = little lag),
 *       - the shape has its own lighter One Euro filters (fingers stay quick; the pinch itself is
 *         decided on the model's raw landmarks, so it is never delayed by filtering);
 *  3. prediction to display time with one smoothed velocity of the centre, applied to the hand
 *     as a whole and faded out when the hand is (nearly) still: prediction is what turned
 *     measurement noise into visible shaking, because a still hand's noisy velocity was
 *     multiplied by 30-60 ms;
 *  4. [extrapolate] carries the result forward between camera frames the same way.
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
    /** Most the centre filter's lag is made up for by prediction (more = quicker but swings past a stop). */
    var maxLagCompensationS = 0.03f
    /** A correction larger than this (metres) is a real jump: shown at once instead of blended. */
    var residualLimit = 0.06f

    /** Centre of the hand across the view (Hz at rest; [lateralBeta] opens it with speed). */
    var lateralCutoffHz = 1.0f
    var lateralBeta = 6f
    /** Distance from the camera: the noisiest part with the hand-size prior. */
    var depthCutoffHz = 0.45f
    var depthBeta = 4f
    /** With a depth map the distance is measured, not guessed: less smoothing. */
    var depthMapDepthCutoffHz = 1.2f
    /** Shape of the hand (joints relative to the palm). */
    var shapeCutoffHz = 1.1f
    var shapeBeta = 2.5f
    /** Prediction fades in between these hand speeds (m/s). */
    var stillSpeed = 0.06f
    var movingSpeed = 0.30f
    var velocityCutoffHz = 3f
    var velocityBeta = 12f

    private val lateral = Array(3) { OneEuroFilter() }
    private val depth = OneEuroFilter()
    private val shape = Array(HandJoint.COUNT * 3) { OneEuroFilter() }
    private val meas = Array(HandJoint.COUNT) { Vec3() }
    private val offset = Array(HandJoint.COUNT) { Vec3() }
    /** Velocity, prediction gain and blending correction for the display (see [HandMotion]). */
    val motion = HandMotion()
    private val residual get() = motion.residual
    /** Time constant over which each new camera result is blended in on the display. */
    var blendSeconds: Float
        get() = motion.blendSeconds
        set(v) { motion.blendSeconds = v }
    private val rawRoot = Vec3()
    private val root = Vec3()
    private val prevRoot = Vec3()
    private var haveRoot = false
    /** Smoothed velocity of the hand's centre (m/s). */
    val velocity get() = motion.velocity
    /** 0 (still: no prediction) .. 1 (moving: full prediction). */
    var predictionGain: Float
        get() = motion.gain
        private set(v) { motion.gain = v }
    private val gestures = GestureRecognizer()
    private var lastSeenNs = 0L
    private var lastUpdateNs = 0L
    private var recoveryStartNs = 0L

    init { setFilterParams(minCutoff, beta) }

    /**
     * [minCutoff] (the "smoothing" setting, Hz) scales every stage: lower = steadier, higher =
     * quicker. [beta] is kept for compatibility; speed adaptation uses the per-stage betas.
     */
    fun setFilterParams(minCutoff: Float, @Suppress("UNUSED_PARAMETER") beta: Float) {
        val k = (minCutoff / 1.2f).coerceIn(0.3f, 4f)
        lateralCutoffHz = 1.0f * k; depthCutoffHz = 0.45f * k; depthMapDepthCutoffHz = 1.2f * k; shapeCutoffHz = 1.1f * k
    }

    /** Former Kalman process noise: now how fast the centre filter opens up with speed. */
    fun setProcessNoise(q: Float) { lateralBeta = 2f * q.coerceIn(0.5f, 20f) }

    /** Pinch closes when thumb-index distance / palm length drops below this ratio (scale-free). */
    var pinchRatio: Float
        get() = gestures.pinchOn
        set(v) { gestures.pinchOn = v; gestures.pinchOff = v * 1.45f }

    /**
     * @param rawPinchRatio pinch ratio measured on this frame's model landmarks (preferred over the
     *   filtered joints); NaN to use the joints.
     * @param viewOrigin where the camera was when the image was taken (same space as [joints]), so
     *   depth along its ray can be smoothed on its own; null treats all directions alike.
     */
    fun update(joints: Array<Vec3>?, score: Float, depthSource: DepthSource, timestampNs: Long, rawPinchRatio: Float = Float.NaN, viewOrigin: Vec3? = null) {
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

        // where the display has been drawing the hand for this instant (last result carried
        // forward): the new result is blended in from there instead of jumping to it
        // (exactly what [HandMotion.extrapolate] gave for this instant, the fading correction included)
        val shownGain = if (motion.moving) predictionGain else 0f
        val ahead = motion.displayAheadS
        val keep = if (dt > 0f) kotlin.math.exp(-dt / motion.blendSeconds) else 0f
        for (i in 0 until HandJoint.COUNT) {
            residual[i].scale(keep).add(state.joints[i]).addScaled(velocity, motion.carried(dt + ahead) * shownGain)
        }

        // centre of the palm, and its distance along the camera ray smoothed on its own
        palmCentre(meas, rawRoot)
        val corrected = Vec3().set(rawRoot)
        if (viewOrigin != null) {
            val dx = rawRoot.x - viewOrigin.x; val dy = rawRoot.y - viewOrigin.y; val dz = rawRoot.z - viewOrigin.z
            val d = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            if (d > 1e-3f) {
                depth.minCutoff = if (depthSource == DepthSource.DEPTH_MAP) depthMapDepthCutoffHz else depthCutoffHz
                depth.beta = depthBeta
                // less trusted observations are smoothed more
                depth.minCutoff *= score.coerceIn(0.4f, 1f)
                val df = depth.filter(d, dt) / d
                corrected.set(viewOrigin.x + dx * df, viewOrigin.y + dy * df, viewOrigin.z + dz * df)
            }
        }
        for (a in 0..2) { lateral[a].minCutoff = lateralCutoffHz; lateral[a].beta = lateralBeta }
        root.set(lateral[0].filter(corrected.x, dt), lateral[1].filter(corrected.y, dt), lateral[2].filter(corrected.z, dt))

        // the shape, relative to the palm
        for (i in 0 until HandJoint.COUNT) {
            val m = meas[i]
            for (a in 0..2) { shape[i * 3 + a].minCutoff = shapeCutoffHz; shape[i * 3 + a].beta = shapeBeta }
            state.joints[i].set(
                root.x + shape[i * 3].filter(m.x - rawRoot.x, dt),
                root.y + shape[i * 3 + 1].filter(m.y - rawRoot.y, dt),
                root.z + shape[i * 3 + 2].filter(m.z - rawRoot.z, dt),
            )
        }
        skeleton.fit(state.joints)

        // velocity of the centre (from the depth-smoothed measurement, not the filtered centre,
        // so it does not carry the filter's lag), smoothed at [velocityCutoffHz]
        if (haveRoot && dt > 0f) {
            // opens up with speed (One Euro style): steady when still, quick when moving
            val a = alpha(velocityCutoffHz + velocityBeta * velocity.length().coerceAtMost(1.5f), dt)
            velocity.set(
                velocity.x + ((corrected.x - prevRoot.x) / dt - velocity.x) * a,
                velocity.y + ((corrected.y - prevRoot.y) / dt - velocity.y) * a,
                velocity.z + ((corrected.z - prevRoot.z) / dt - velocity.z) * a,
            )
        } else velocity.set(0f, 0f, 0f)
        prevRoot.set(corrected); haveRoot = true
        predictionGain = smoothstep(stillSpeed, movingSpeed, velocity.length())

        // ahead by the image's age, plus the time the centre filter trails a moving hand
        // (a first-order filter is 1/(2π·cutoff) behind a steady motion), only while moving
        val g = predictionGain
        val px = (predictionSeconds + lag(lateral[0])) * g
        val py = (predictionSeconds + lag(lateral[1])) * g
        val pz = (predictionSeconds + lag(lateral[2])) * g
        for (i in 0 until HandJoint.COUNT) {
            state.joints[i].x += velocity.x * px + offset[i].x * fade
            state.joints[i].y += velocity.y * py + offset[i].y * fade
            state.joints[i].z += velocity.z * pz + offset[i].z * fade
        }
        if (fade == 0f) offset.forEach { it.set(0f, 0f, 0f) }
        // residual = what was shown minus what the new estimate shows now; dropped after a
        // re-acquisition or a jump
        var big = state.state != TrackingState.TRACKING || dt == 0f
        val newShift = motion.carried(ahead) * g
        for (i in 0 until HandJoint.COUNT) {
            residual[i].sub(state.joints[i]).addScaled(velocity, -newShift)
            if (residual[i].length() > residualLimit) big = true
        }
        if (big) residual.forEach { it.set(0f, 0f, 0f) }
        lastSeenNs = timestampNs
        state.confidence = score
        state.depthSource = depthSource
        motion.moving = state.state == TrackingState.TRACKING || state.state == TrackingState.RECOVERING
        gestures.update(state, rawPinchRatio)
    }

    /**
     * The hand [sinceSeconds] after its last update, for the display between camera frames: moved
     * as a whole by the centre's velocity, and not at all while it is still.
     */
    fun extrapolate(sinceSeconds: Float, out: Array<Vec3>, aheadSeconds: Float = motion.displayAheadS) =
        motion.extrapolate(state, sinceSeconds, out, aheadSeconds)

    private fun lag(f: OneEuroFilter) = (1f / (2f * Math.PI.toFloat() * f.lastCutoff)).coerceAtMost(maxLagCompensationS)

    private fun palmCentre(j: Array<Vec3>, out: Vec3): Vec3 {
        out.set(0f, 0f, 0f)
        for (k in PALM) out.add(j[k])
        return out.scale(1f / PALM.size)
    }

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * Math.PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    private fun resetFilters() {
        lateral.forEach { it.reset() }
        depth.reset()
        shape.forEach { it.reset() }
        residual.forEach { it.set(0f, 0f, 0f) }
        haveRoot = false
        velocity.set(0f, 0f, 0f)
        predictionGain = 0f
    }

    private fun handleMissing(now: Long, dt: Float) {
        when (state.state) {
            TrackingState.TRACKING, TrackingState.RECOVERING, TrackingState.LOST_PREDICTING -> {
                if (now - lastSeenNs > lostTimeoutNs) {
                    state.state = TrackingState.NOT_TRACKED
                    motion.moving = false
                    state.confidence = 0f
                    state.gesture = Gesture.NONE
                    state.pinchStrength = 0f
                    gestures.reset()
                    return
                }
                state.state = TrackingState.LOST_PREDICTING
                motion.moving = false
                residual.forEach { it.set(0f, 0f, 0f) }
                // carried by the centre's velocity, which decays quickly: short gaps stay
                // continuous, long ones leave the hand where it was last seen instead of flying off
                val s = dt * predictionGain
                for (i in 0 until HandJoint.COUNT) state.joints[i].addScaled(velocity, s)
                velocity.scale(kotlin.math.exp(-dt / coastDecaySeconds))
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

private val PALM = intArrayOf(HandJoint.WRIST, HandJoint.INDEX_MCP, HandJoint.MIDDLE_MCP, HandJoint.RING_MCP, HandJoint.PINKY_MCP)

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
