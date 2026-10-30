package com.hyperxr.headtracking

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Leaning and short steps for phones without 6DoF tracking, from the linear accelerometer, after
 * PhoneXR's SensorSixDof (Samrat Beketov, used with permission). Integrated acceleration always
 * runs away within seconds, so: the sensor's bias is learnt while the head is still and taken
 * off, the speed dies out quickly, and the position eases back to the centre by itself. Moves are
 * felt, and nothing flies away.
 *
 * Input is the acceleration already turned into the VR world (metres per second squared, gravity
 * removed); [offset] is how far the body is from where it started, metres.
 */
class LeanTracker {
    val offset = FloatArray(3)
    private val velocity = FloatArray(3)
    private val bias = FloatArray(3)
    private var stillFrames = 0

    fun reset() { offset.fill(0f); velocity.fill(0f); stillFrames = 0 }

    /** [ax], [ay], [az]: world acceleration; [dt] seconds since the last sample. */
    fun update(ax: Float, ay: Float, az: Float, dt: Float) {
        if (dt <= 0f) return
        val d = dt.coerceAtMost(0.05f)
        val magnitude = sqrt(ax * ax + ay * ay + az * az)
        val still = magnitude < STILL
        stillFrames = if (still) stillFrames + 1 else 0
        val a = floatArrayOf(ax, ay, az)
        // while still, what the sensor reads is its own bias: learnt slowly and taken off
        if (still) for (i in 0..2) bias[i] += (a[i] - bias[i]) * 0.02f
        val speedFade = exp(-d / SPEED_FADE_S)
        val homeFade = exp(-d / HOME_S)
        for (i in 0..2) {
            val acc = a[i] - bias[i]
            velocity[i] = if (stillFrames >= STILL_FRAMES) 0f else (velocity[i] + acc * d) * speedFade
            offset[i] = ((offset[i] + velocity[i] * d * GAIN) * homeFade).coerceIn(-MAX_REACH, MAX_REACH)
        }
    }

    companion object {
        /** m/s² under which the head counts as still. */
        const val STILL = 0.15f
        /** Still readings in a row before the speed is stopped. */
        const val STILL_FRAMES = 6
        /** How quickly a move's speed dies out, seconds. */
        const val SPEED_FADE_S = 0.35f
        /** How quickly the body eases back to the centre, seconds: no drift can build up. */
        const val HOME_S = 1.6f
        /** A little more movement than measured, so a lean is felt. */
        const val GAIN = 1.4f
        /** Never further than this from the centre, metres. */
        const val MAX_REACH = 0.6f
    }
}
