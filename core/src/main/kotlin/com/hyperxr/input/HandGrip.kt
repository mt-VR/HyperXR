package com.hyperxr.input

import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3

/**
 * Holding things with a tracked hand in 6DoF. [handPose] turns four joints into a rigid pose of
 * the hand (centre of the palm, -z along the fingers, +x towards the thumb side of a right hand);
 * a [GrabDrag] keeps an object fixed to that pose from the moment it was grabbed, so it follows
 * every move and turn of the hand.
 */
object HandGrip {
    private val fx = Vec3(); private val fy = Vec3(); private val fz = Vec3(); private val side = Vec3()

    fun handPose(wrist: Vec3, indexMcp: Vec3, pinkyMcp: Vec3, middleMcp: Vec3, out: Pose): Pose {
        out.position.set(wrist).add(middleMcp).scale(0.5f)
        fz.set(wrist).sub(middleMcp)                       // +z: back along the hand
        if (fz.length() < 1e-6f) fz.set(0f, 0f, 1f)
        fz.normalize()
        side.set(indexMcp).sub(pinkyMcp)
        fz.cross(side, fy)                                  // out of the back of the hand (or the palm)
        if (fy.length() < 1e-6f) fy.set(0f, 1f, 0f)
        fy.normalize()
        fy.cross(fz, fx).normalize()
        Quat.fromBasis(fx, fy, fz, out.orientation)
        return out
    }
}

/** An object stuck to a moving pose (a hand) from [begin] on. */
class GrabDrag {
    private val offset = Pose()
    private val inv = Pose()
    var active = false; private set

    fun begin(hand: Pose, obj: Pose) {
        hand.inverse(inv)
        inv.compose(obj, offset)
        active = true
    }

    /** Where the object is now that the hand is at [hand]. */
    fun update(hand: Pose, out: Pose): Pose { hand.compose(offset, out); return out }

    fun end() { active = false }
}
