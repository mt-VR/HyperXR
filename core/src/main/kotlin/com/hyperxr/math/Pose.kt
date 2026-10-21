package com.hyperxr.math

/** Rigid pose: orientation + position, in meters. */
class Pose(@JvmField val position: Vec3 = Vec3(), @JvmField val orientation: Quat = Quat()) {
    fun set(o: Pose): Pose { position.set(o.position); orientation.set(o.orientation); return this }
    fun copy(): Pose = Pose(position.copy(), orientation.copy())

    /** Transforms a local point into this pose's parent space. */
    fun transformPoint(local: Vec3, out: Vec3 = Vec3()): Vec3 = orientation.rotate(local, out).add(position)

    /** out = this * child */
    fun compose(child: Pose, out: Pose = Pose()): Pose {
        val p = transformPoint(child.position)
        orientation.mul(child.orientation, out.orientation)
        out.position.set(p)
        return out
    }

    fun inverse(out: Pose = Pose()): Pose {
        orientation.conjugate(out.orientation)
        out.orientation.rotate(position, out.position).scale(-1f)
        return out
    }

    fun forward(out: Vec3 = Vec3()): Vec3 = orientation.rotate(FORWARD, out)

    override fun toString(): String = "Pose(p=$position, q=$orientation)"

    companion object {
        val FORWARD = Vec3(0f, 0f, -1f)
        val UP = Vec3(0f, 1f, 0f)
    }
}

/** Ray used by pointers (gaze, hand ray, Joy-Con ray). Direction is normalized. */
class Ray(@JvmField val origin: Vec3 = Vec3(), @JvmField val direction: Vec3 = Vec3(0f, 0f, -1f)) {
    fun pointAt(t: Float, out: Vec3 = Vec3()): Vec3 = out.set(origin).addScaled(direction, t)
}
