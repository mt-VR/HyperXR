package com.hyperxr.math

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Unit quaternion (x, y, z, w). Right-handed, OpenXR convention (-Z forward, +Y up). */
class Quat(
    @JvmField var x: Float = 0f,
    @JvmField var y: Float = 0f,
    @JvmField var z: Float = 0f,
    @JvmField var w: Float = 1f,
) {
    fun set(x: Float, y: Float, z: Float, w: Float): Quat { this.x = x; this.y = y; this.z = z; this.w = w; return this }
    fun set(o: Quat): Quat = set(o.x, o.y, o.z, o.w)
    fun copy(): Quat = Quat(x, y, z, w)
    fun identity(): Quat = set(0f, 0f, 0f, 1f)

    fun normalize(): Quat {
        val l = sqrt(x * x + y * y + z * z + w * w)
        return if (l > 1e-8f) set(x / l, y / l, z / l, w / l) else identity()
    }

    fun conjugate(out: Quat = Quat()): Quat = out.set(-x, -y, -z, w)

    /** out = this * o (apply o first, then this). */
    fun mul(o: Quat, out: Quat = Quat()): Quat = out.set(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z,
    )

    fun rotate(v: Vec3, out: Vec3 = Vec3()): Vec3 {
        // t = 2 * cross(q.xyz, v); v' = v + w*t + cross(q.xyz, t)
        val tx = 2f * (y * v.z - z * v.y)
        val ty = 2f * (z * v.x - x * v.z)
        val tz = 2f * (x * v.y - y * v.x)
        return out.set(
            v.x + w * tx + (y * tz - z * ty),
            v.y + w * ty + (z * tx - x * tz),
            v.z + w * tz + (x * ty - y * tx),
        )
    }

    fun dot(o: Quat): Float = x * o.x + y * o.y + z * o.z + w * o.w

    /** Angle in radians between two orientations. */
    fun angleTo(o: Quat): Float {
        val d = kotlin.math.abs(dot(o)).coerceAtMost(1f)
        return 2f * acos(d)
    }

    fun slerp(o: Quat, t: Float, out: Quat = Quat()): Quat {
        var bx = o.x; var by = o.y; var bz = o.z; var bw = o.w
        var cosHalf = dot(o)
        if (cosHalf < 0f) { bx = -bx; by = -by; bz = -bz; bw = -bw; cosHalf = -cosHalf }
        if (cosHalf > 0.9995f) {
            return out.set(x + (bx - x) * t, y + (by - y) * t, z + (bz - z) * t, w + (bw - w) * t).normalize()
        }
        val half = acos(cosHalf)
        val sinHalf = sqrt(1f - cosHalf * cosHalf)
        val ra = sin((1 - t) * half) / sinHalf
        val rb = sin(t * half) / sinHalf
        return out.set(x * ra + bx * rb, y * ra + by * rb, z * ra + bz * rb, w * ra + bw * rb)
    }

    override fun toString(): String = "Quat(%.4f, %.4f, %.4f, %.4f)".format(x, y, z, w)

    companion object {
        fun fromAxisAngle(ax: Float, ay: Float, az: Float, radians: Float, out: Quat = Quat()): Quat {
            val l = sqrt(ax * ax + ay * ay + az * az)
            if (l < 1e-8f) return out.identity()
            val s = sin(radians / 2f) / l
            return out.set(ax * s, ay * s, az * s, cos(radians / 2f))
        }

        /** Integrates an angular velocity (rad/s, body frame) over dt into q. */
        fun integrate(q: Quat, wx: Float, wy: Float, wz: Float, dt: Float, out: Quat = Quat()): Quat {
            val mag = sqrt(wx * wx + wy * wy + wz * wz)
            if (mag * dt < 1e-9f) return out.set(q)
            val dq = fromAxisAngle(wx, wy, wz, mag * dt)
            return q.mul(dq, out).normalize()
        }

        /** Rotation that maps unit vector a onto unit vector b. */
        fun fromTo(a: Vec3, b: Vec3, out: Quat = Quat()): Quat {
            val d = a.dot(b)
            if (d < -0.999999f) {
                val axis = if (kotlin.math.abs(a.x) < 0.9f) Vec3(1f, 0f, 0f).cross(a) else Vec3(0f, 1f, 0f).cross(a)
                axis.normalize()
                return fromAxisAngle(axis.x, axis.y, axis.z, Math.PI.toFloat(), out)
            }
            val c = a.cross(b)
            return out.set(c.x, c.y, c.z, 1f + d).normalize()
        }
    }
}
