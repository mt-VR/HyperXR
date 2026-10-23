package com.hyperxr.math

/**
 * Rotational timewarp: the head turns between the moment a frame is rendered and the moment it
 * is shown. Right before the final pass the gyroscope gives the newer orientation, and the image
 * is re-aimed by the rotation between the two, which removes the render time from what the eyes
 * see as rotation latency.
 */
object Timewarp {
    private val inv = Quat()
    private val d = Quat()

    /**
     * Column-major 3x3 mapping a view direction of [now] (the head as it is when the image is
     * shown) to the view space of [rendered]. Rotations above [maxRadians] (tracking jumps,
     * recenter) are not warped: [out] is the identity and false is returned.
     */
    fun matrix(rendered: Quat, now: Quat, out: FloatArray, maxRadians: Float = 0.35f): Boolean {
        rendered.conjugate(inv).mul(now, d).normalize()
        if (d.w < 0f) d.set(-d.x, -d.y, -d.z, -d.w)
        val angle = 2f * kotlin.math.acos(d.w.coerceIn(-1f, 1f))
        if (angle > maxRadians || angle.isNaN()) { identity(out); return false }
        val x = d.x; val y = d.y; val z = d.z; val w = d.w
        out[0] = 1 - 2 * (y * y + z * z); out[1] = 2 * (x * y + z * w); out[2] = 2 * (x * z - y * w)
        out[3] = 2 * (x * y - z * w); out[4] = 1 - 2 * (x * x + z * z); out[5] = 2 * (y * z + x * w)
        out[6] = 2 * (x * z + y * w); out[7] = 2 * (y * z - x * w); out[8] = 1 - 2 * (x * x + y * y)
        return true
    }

    fun identity(out: FloatArray) {
        out.fill(0f); out[0] = 1f; out[4] = 1f; out[8] = 1f
    }
}
