package com.hyperxr.input

import com.hyperxr.math.Ray
import com.hyperxr.math.Vec3

/**
 * The pinch ball as the pointer: the target is what the user sees under the ball, i.e. the ray
 * from the eyes through the ball. No beam, no shoulder model: put the ball over a button and pinch.
 *
 * Closing the fingers moves the ball (thumb and index tips converge), which would shift the aim
 * exactly when clicking. When a pinch starts, the aim freezes at where the ball was [lookbackNs]
 * earlier (before the fingers started closing); while the pinch is held the aim follows the
 * hand's movement from there, so drags and scrolls still work.
 */
class BallPointer(var lookbackNs: Long = 150_000_000L) {
    private val size = 24
    private val pts = Array(size) { Vec3() }
    private val times = LongArray(size)
    private var count = 0
    private var head = 0
    private val frozen = Vec3()
    private val ref = Vec3()
    private val aim = Vec3()
    var pressed = false; private set

    fun reset() { count = 0; pressed = false }

    private fun push(p: Vec3, now: Long) {
        pts[head].set(p); times[head] = now
        head = (head + 1) % size
        if (count < size) count++
    }

    /** Ball position at about [t] (the newest sample not after it, else the oldest one). */
    private fun at(t: Long, out: Vec3): Vec3 {
        var best = -1
        for (k in 0 until count) {
            val idx = (head - 1 - k + size) % size
            if (times[idx] <= t) { best = idx; break }
            best = idx // keeps the oldest if none is old enough
        }
        return out.set(pts[best])
    }

    fun update(ball: Vec3, pinching: Boolean, eye: Vec3, now: Long, out: Ray): Ray {
        push(ball, now)
        if (pinching && !pressed) {
            at(now - lookbackNs, frozen)
            ref.set(ball)
            pressed = true
        } else if (!pinching) pressed = false
        if (pressed) aim.set(frozen).add(ball).sub(ref) else aim.set(ball)
        out.origin.set(eye)
        out.direction.set(aim).sub(eye).normalize()
        return out
    }
}
