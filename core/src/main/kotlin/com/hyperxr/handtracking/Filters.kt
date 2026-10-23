package com.hyperxr.handtracking

import kotlin.math.PI
import kotlin.math.abs

/**
 * One Euro filter (Casiez et al., CHI 2012): adaptive low-pass that removes jitter at low speeds
 * while keeping lag low during fast motion. Allocation-free.
 */
class OneEuroFilter(var minCutoff: Float = 1.0f, var beta: Float = 0.02f, var dCutoff: Float = 1.0f) {
    private var hasPrev = false
    private var xPrev = 0f
    private var dxPrev = 0f

    fun reset() { hasPrev = false }

    fun filter(x: Float, dtSeconds: Float): Float {
        if (!hasPrev || dtSeconds <= 0f) {
            hasPrev = true; xPrev = x; dxPrev = 0f
            return x
        }
        val dx = (x - xPrev) / dtSeconds
        val edx = lowpass(dx, dxPrev, alpha(dCutoff, dtSeconds))
        dxPrev = edx
        val cutoff = minCutoff + beta * abs(edx)
        lastCutoff = cutoff
        val result = lowpass(x, xPrev, alpha(cutoff, dtSeconds))
        xPrev = result
        return result
    }

    /** Cutoff (Hz) used for the last sample: the filter trails a steady motion by 1/(2π·cutoff) s. */
    var lastCutoff = minCutoff; private set

    /** Last filtered derivative (units/s), used for motion prediction. */
    val velocity: Float get() = dxPrev

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    private fun lowpass(x: Float, prev: Float, a: Float) = a * x + (1f - a) * prev
}

/** Hysteresis threshold: avoids flicker (and duplicate clicks) when a value hovers around a threshold. */
class Hysteresis(var onThreshold: Float, var offThreshold: Float, private val activeWhenBelow: Boolean = true) {
    var active = false
        private set

    /** Returns true only on the transition into the active state. */
    fun update(value: Float): Boolean {
        val wasActive = active
        active = if (activeWhenBelow) {
            if (active) value < offThreshold else value < onThreshold
        } else {
            if (active) value > offThreshold else value > onThreshold
        }
        return active && !wasActive
    }

    fun reset() { active = false }
}

/**
 * Constant-velocity Kalman filter for one coordinate: state (position, velocity), measurement of
 * position. [q] is the white-acceleration spectral density (how quickly a joint may change speed)
 * and the measurement noise is given per update, so confident (depth-map) observations pull harder
 * than size-prior ones. Gives a statistically weighted estimate plus a velocity for prediction
 * across dropped frames. Allocation-free.
 */
class Kalman1D(var q: Float = 40f) {
    var x = 0f; private set
    var v = 0f; private set
    // covariance [[p00, p01], [p01, p11]]
    private var p00 = 1f
    private var p01 = 0f
    private var p11 = 1f
    var initialized = false; private set

    fun reset() { initialized = false }

    fun predict(dt: Float) {
        if (!initialized || dt <= 0f) return
        x += v * dt
        val dt2 = dt * dt
        // P = F P F' + Q, Q for white acceleration: q * [[dt^3/3, dt^2/2], [dt^2/2, dt]]
        val n00 = p00 + 2 * dt * p01 + dt2 * p11 + q * dt2 * dt / 3f
        val n01 = p01 + dt * p11 + q * dt2 / 2f
        val n11 = p11 + q * dt
        p00 = n00; p01 = n01; p11 = n11
    }

    /** [r] = measurement variance (units^2). */
    fun update(z: Float, r: Float) {
        if (!initialized) {
            x = z; v = 0f; p00 = r; p01 = 0f; p11 = 1f; initialized = true
            return
        }
        val s = p00 + r
        val k0 = p00 / s
        val k1 = p01 / s
        val y = z - x
        x += k0 * y
        v += k1 * y
        val n00 = (1 - k0) * p00
        val n01 = (1 - k0) * p01
        val n11 = p11 - k1 * p01
        p00 = n00; p01 = n01; p11 = n11
    }

    /** Velocity decays while no measurements arrive (coasting), so the estimate settles. */
    fun dampVelocity(factor: Float) { v *= factor }
}
