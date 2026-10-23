package com.hyperxr.headtracking

import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

/**
 * 6DoF continuity after a tracking loss. When ARCore finds itself again it can come back with its
 * world shifted (it re-localised somewhere else, or started a new map), which moved every window
 * and panel away from where it was. Instead of taking that jump, the tracking origin is moved so
 * the head continues from the pose that was being shown; only yaw and position change, so gravity
 * (the floor) stays level.
 */
object TrackingRebase {
    /** A head this far (metres) or turned this much (radians) from where it was shown has jumped. */
    const val JUMP_M = 0.25f
    const val JUMP_YAW = 0.26f

    fun yaw(q: Quat): Float {
        val f = q.rotate(Vec3(0f, 0f, -1f))
        return atan2(-f.x, -f.z)
    }

    private fun wrap(a: Float): Float {
        var r = a
        while (r > PI) r -= (2 * PI).toFloat()
        while (r < -PI) r += (2 * PI).toFloat()
        return r
    }

    /** True when [rel] (head in tracking space now) is not a continuation of [shown]. */
    fun jumped(shown: Pose, rel: Pose): Boolean =
        shown.position.distanceTo(rel.position) > JUMP_M || abs(wrap(yaw(rel.orientation) - yaw(shown.orientation))) > JUMP_YAW

    /**
     * The origin (tracking space in ARCore's world) under which [headWorld] reads as [shown]:
     * origin⁻¹ · headWorld has [shown]'s position and yaw.
     */
    fun origin(headWorld: Pose, shown: Pose, out: Pose = Pose()): Pose {
        val dYaw = wrap(yaw(headWorld.orientation) - yaw(shown.orientation))
        Quat.fromAxisAngle(0f, 1f, 0f, dYaw, out.orientation)
        val p = out.orientation.rotate(shown.position)
        out.position.set(headWorld.position.x - p.x, headWorld.position.y - p.y, headWorld.position.z - p.z)
        return out
    }
}
