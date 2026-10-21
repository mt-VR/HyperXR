package com.hyperxr.math

import kotlin.math.sqrt

/**
 * Mutable 3D vector. Mutating operations return `this` so hot loops can reuse
 * instances instead of allocating per frame.
 */
class Vec3(@JvmField var x: Float = 0f, @JvmField var y: Float = 0f, @JvmField var z: Float = 0f) {

    fun set(x: Float, y: Float, z: Float): Vec3 { this.x = x; this.y = y; this.z = z; return this }
    fun set(o: Vec3): Vec3 = set(o.x, o.y, o.z)
    fun copy(): Vec3 = Vec3(x, y, z)

    fun add(o: Vec3): Vec3 = set(x + o.x, y + o.y, z + o.z)
    fun sub(o: Vec3): Vec3 = set(x - o.x, y - o.y, z - o.z)
    fun scale(s: Float): Vec3 = set(x * s, y * s, z * s)
    fun addScaled(o: Vec3, s: Float): Vec3 = set(x + o.x * s, y + o.y * s, z + o.z * s)

    fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z
    fun lengthSq(): Float = x * x + y * y + z * z
    fun length(): Float = sqrt(lengthSq())

    fun normalize(): Vec3 {
        val l = length()
        return if (l > 1e-8f) scale(1f / l) else this
    }

    fun cross(o: Vec3, out: Vec3 = Vec3()): Vec3 =
        out.set(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    fun distanceTo(o: Vec3): Float {
        val dx = x - o.x; val dy = y - o.y; val dz = z - o.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun lerp(o: Vec3, t: Float): Vec3 = set(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)

    override fun toString(): String = "(%.3f, %.3f, %.3f)".format(x, y, z)
    override fun equals(other: Any?): Boolean = other is Vec3 && other.x == x && other.y == y && other.z == z
    override fun hashCode(): Int = 31 * (31 * x.hashCode() + y.hashCode()) + z.hashCode()

    companion object {
        fun sub(a: Vec3, b: Vec3, out: Vec3 = Vec3()): Vec3 = out.set(a.x - b.x, a.y - b.y, a.z - b.z)
        fun add(a: Vec3, b: Vec3, out: Vec3 = Vec3()): Vec3 = out.set(a.x + b.x, a.y + b.y, a.z + b.z)
    }
}
