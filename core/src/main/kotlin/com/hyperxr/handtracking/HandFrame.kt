package com.hyperxr.handtracking

import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3

/**
 * A rigid frame attached to the hand, for holding objects: X across the knuckles (index side),
 * Y along the hand (wrist to middle knuckle), Z out of the palm plane. Origin: the pinch point
 * (between thumb and index tips) or the palm centre.
 */
object HandFrame {
    private val x = Vec3(); private val y = Vec3(); private val z = Vec3()

    fun pinchPoint(h: HandState, out: Vec3): Vec3 = out.set(h.joints[HandJoint.THUMB_TIP]).add(h.joints[HandJoint.INDEX_TIP]).scale(0.5f)

    fun palmCenter(h: HandState, out: Vec3): Vec3 =
        out.set(h.joints[HandJoint.WRIST]).add(h.joints[HandJoint.INDEX_MCP]).add(h.joints[HandJoint.MIDDLE_MCP]).add(h.joints[HandJoint.PINKY_MCP]).scale(0.25f)

    /** Orientation of the hand; returns false for a degenerate (collapsed) skeleton. */
    fun orientation(h: HandState, out: Quat): Boolean {
        x.set(h.joints[HandJoint.INDEX_MCP]).sub(h.joints[HandJoint.PINKY_MCP])
        y.set(h.joints[HandJoint.MIDDLE_MCP]).sub(h.joints[HandJoint.WRIST])
        if (x.length() < 1e-4f || y.length() < 1e-4f) return false
        y.normalize()
        // make X orthogonal to Y
        x.addScaled(y, -x.dot(y)).normalize()
        x.cross(y, z).normalize()
        Quat.fromBasis(x, y, z, out)
        return true
    }

    fun compute(h: HandState, usePinch: Boolean, out: Pose): Boolean {
        if (!orientation(h, out.orientation)) return false
        if (usePinch) pinchPoint(h, out.position) else palmCenter(h, out.position)
        return true
    }
}
