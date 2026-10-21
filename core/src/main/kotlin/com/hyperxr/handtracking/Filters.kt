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
        val result = lowpass(x, xPrev, alpha(cutoff, dtSeconds))
        xPrev = result
        return result
    }

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
