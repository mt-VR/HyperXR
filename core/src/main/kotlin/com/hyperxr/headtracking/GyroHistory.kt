package com.hyperxr.headtracking

import com.hyperxr.math.Quat

/**
 * The gyroscope integrated at its full rate (400-1000 Hz on most phones) into a running
 * device-frame orientation, with the last half second kept. It answers "how did the phone turn
 * between t1 and t2", which is what makes head rotation low-latency:
 *  - 6DoF: ARCore's pose is ~30 Hz and tens of ms old; its orientation plus the gyro rotation
 *    since the camera frame's timestamp gives the orientation now;
 *  - 3DoF: the fused rotation vector plus the gyro rotation since its sample.
 * Beyond the last sample the latest angular velocity extrapolates (prediction to photon time).
 */
class GyroHistory(private val capacity: Int = 1024) {
    private val t = LongArray(capacity)
    private val q = FloatArray(capacity * 4)
    private val w = FloatArray(3)
    private var head = -1
    private var count = 0
    private val acc = Quat()
    private val a = Quat(); private val b = Quat()

    @Synchronized fun add(timeNs: Long, wx: Float, wy: Float, wz: Float) {
        if (count > 0) {
            val dt = (timeNs - t[head]) / 1e9f
            if (dt <= 0f) return
            // trapezoid: mean of the previous and the new rate over the interval
            if (dt < 0.05f) Quat.integrate(acc, (w[0] + wx) / 2, (w[1] + wy) / 2, (w[2] + wz) / 2, dt, acc)
        } else acc.identity()
        head = (head + 1) % capacity
        if (count < capacity) count++
        t[head] = timeNs
        q[head * 4] = acc.x; q[head * 4 + 1] = acc.y; q[head * 4 + 2] = acc.z; q[head * 4 + 3] = acc.w
        w[0] = wx; w[1] = wy; w[2] = wz
    }

    @get:Synchronized val lastTimeNs: Long get() = if (count == 0) 0L else t[head]
    @get:Synchronized val size: Int get() = count

    /** Integrated orientation at [timeNs] (interpolated; extrapolated past the newest sample). */
    @Synchronized fun at(timeNs: Long, out: Quat): Boolean {
        if (count == 0) return false
        if (timeNs >= t[head]) {
            out.set(q[head * 4], q[head * 4 + 1], q[head * 4 + 2], q[head * 4 + 3])
            val dt = ((timeNs - t[head]) / 1e9f).coerceAtMost(0.06f)
            if (dt > 0f) Quat.integrate(out, w[0], w[1], w[2], dt, out)
            return true
        }
        // newest to oldest: find the two samples around timeNs
        var i = head
        repeat(count - 1) {
            val prev = (i - 1 + capacity) % capacity
            if (t[prev] <= timeNs) {
                val f = (timeNs - t[prev]).toFloat() / (t[i] - t[prev]).coerceAtLeast(1)
                a.set(q[prev * 4], q[prev * 4 + 1], q[prev * 4 + 2], q[prev * 4 + 3])
                b.set(q[i * 4], q[i * 4 + 1], q[i * 4 + 2], q[i * 4 + 3])
                a.slerp(b, f, out)
                return true
            }
            i = prev
        }
        return false  // older than the history
    }

    /** Device-frame rotation from [fromNs] to [toNs]: orientation(to) = orientation(from) * delta. */
    fun delta(fromNs: Long, toNs: Long, out: Quat): Boolean {
        val qa = Quat(); val qb = Quat()
        if (!at(fromNs, qa) || !at(toNs, qb)) return false
        qa.conjugate(out).mul(qb, out)
        out.normalize()
        return true
    }
}
