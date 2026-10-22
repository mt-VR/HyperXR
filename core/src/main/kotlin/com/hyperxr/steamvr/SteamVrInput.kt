package com.hyperxr.steamvr

import com.hyperxr.handtracking.HandJoint
import com.hyperxr.input.ControllerButton
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import com.hyperxr.steamvr.HxvrProtocol as P
import kotlin.math.acos
import kotlin.math.atan2

/**
 * Turns what the phone tracks (head from ARCore, hands from the camera, Joy-Con buttons) into
 * the emulated Quest 3 Touch controllers SteamVR sees.
 */
object SteamVrInput {
    /**
     * Finger closure 0 (straight) .. 1 (fist) for thumb, index, middle, ring, pinky: the bend
     * accumulated along each finger's joints.
     */
    fun curls(j: Array<Vec3>): FloatArray {
        fun bend(a: Int, b: Int, c: Int): Float {
            val u = Vec3.sub(j[b], j[a]); val v = Vec3.sub(j[c], j[b])
            val lu = u.length(); val lv = v.length()
            if (lu < 1e-5f || lv < 1e-5f) return 0f
            return acos((u.dot(v) / (lu * lv)).coerceIn(-1f, 1f))
        }
        fun finger(mcp: Int): Float {
            val total = bend(HandJoint.WRIST, mcp, mcp + 1) + bend(mcp, mcp + 1, mcp + 2) + bend(mcp + 1, mcp + 2, mcp + 3)
            return (total / 4.2f).coerceIn(0f, 1f)  // ~240 degrees summed is a closed fist
        }
        val thumb = (bend(HandJoint.THUMB_CMC, HandJoint.THUMB_MCP, HandJoint.THUMB_IP) + bend(HandJoint.THUMB_MCP, HandJoint.THUMB_IP, HandJoint.THUMB_TIP)) / 1.8f
        return floatArrayOf(thumb.coerceIn(0f, 1f), finger(HandJoint.INDEX_MCP), finger(HandJoint.MIDDLE_MCP), finger(HandJoint.RING_MCP), finger(HandJoint.PINKY_MCP))
    }

    /**
     * Grip pose of a tracked hand, where a Touch controller held in it would be: centre of the
     * palm, -Z along the fingers, +Y out of the back of the hand.
     */
    fun handPose(j: Array<Vec3>, right: Boolean, outPos: Vec3, outRot: Quat) {
        val wrist = j[HandJoint.WRIST]; val mid = j[HandJoint.MIDDLE_MCP]
        outPos.set(wrist).lerp(mid, 0.5f)
        val fwd = Vec3.sub(mid, wrist).normalize()
        val across = Vec3.sub(j[HandJoint.INDEX_MCP], j[HandJoint.PINKY_MCP]).normalize()
        // back of the hand: palm down with fingers ahead, index→pinky points +X on the left hand
        // and -X on the right, so the cross product order flips between hands
        val back = (if (right) fwd.cross(across) else across.cross(fwd)).normalize()
        val z = Vec3(-fwd.x, -fwd.y, -fwd.z)
        val y = z.cross(back).cross(z).normalize()  // back made exactly perpendicular to z
        val x = y.cross(z).normalize()
        Quat.fromBasis(x, y, z, outRot)
    }

    /**
     * A hand without a controller drives the emulated one: pinch is the trigger, closing the
     * other three fingers is the grip, a fully closed thumb over the fist is the A/X button.
     */
    fun fromHand(j: Array<Vec3>, right: Boolean, pinch: Float, c: P.Controller) {
        val pos = Vec3(); val rot = Quat()
        handPose(j, right, pos, rot)
        c.pose[0] = pos.x; c.pose[1] = pos.y; c.pose[2] = pos.z
        c.pose[3] = rot.x; c.pose[4] = rot.y; c.pose[5] = rot.z; c.pose[6] = rot.w
        val cu = curls(j)
        cu.copyInto(c.curls)
        c.trigger = pinch.coerceIn(0f, 1f)
        c.grip = ((cu[2] + cu[3] + cu[4]) / 3f - 0.35f).div(0.45f).coerceIn(0f, 1f)
        var b = 0
        if (c.trigger > 0.2f) b = b or P.BTN_TRIGGER_TOUCH
        if (c.grip > 0.2f) b = b or P.BTN_GRIP_TOUCH
        if (c.grip > 0.8f && cu[0] > 0.7f) b = b or P.BTN_PRIMARY
        c.buttons = b
        c.joyX = 0f; c.joyY = 0f
        c.flags = P.CTRL_CONNECTED or P.CTRL_HAND_TRACKING or P.CTRL_POSE_VALID
    }

    /**
     * A Joy-Con as a Touch controller. Face buttons by position: bottom/right = A (X on the left
     * controller), top/left = B (Y). R/L = grip, ZR/ZL = trigger, stick and its click, + or −
     * = menu, Home/Capture = system. Fingers follow the buttons for games with hand models.
     */
    fun fromJoyCon(down: (ControllerButton) -> Boolean, stickX: Float, stickY: Float, c: P.Controller) {
        var b = 0
        if (down(ControllerButton.A) || down(ControllerButton.B)) b = b or P.BTN_PRIMARY
        if (down(ControllerButton.X) || down(ControllerButton.Y)) b = b or P.BTN_SECONDARY
        if (down(ControllerButton.PLUS) || down(ControllerButton.MINUS)) b = b or P.BTN_MENU
        if (down(ControllerButton.HOME) || down(ControllerButton.CAPTURE)) b = b or P.BTN_SYSTEM
        if (down(ControllerButton.STICK_R) || down(ControllerButton.STICK_L)) b = b or P.BTN_STICK_CLICK
        val trigger = down(ControllerButton.ZR) || down(ControllerButton.ZL)
        val grip = down(ControllerButton.R) || down(ControllerButton.L)
        if (trigger) b = b or P.BTN_TRIGGER_TOUCH
        if (grip) b = b or P.BTN_GRIP_TOUCH
        if (stickX != 0f || stickY != 0f) b = b or P.BTN_STICK_TOUCH
        if (b and (P.BTN_PRIMARY or P.BTN_SECONDARY) == 0 && stickX == 0f && stickY == 0f) b = b or P.BTN_THUMBREST_TOUCH
        c.buttons = b
        c.trigger = if (trigger) 1f else 0f
        c.grip = if (grip) 1f else 0f
        c.joyX = stickX; c.joyY = stickY
        c.curls[0] = if (b and (P.BTN_PRIMARY or P.BTN_SECONDARY or P.BTN_STICK_TOUCH or P.BTN_THUMBREST_TOUCH) != 0) 0.8f else 0.2f
        c.curls[1] = c.trigger
        for (i in 2..4) c.curls[i] = c.grip
        c.flags = P.CTRL_CONNECTED or P.CTRL_POSE_VALID
    }

    /** Copies a pose into the 7-float wire layout. */
    fun putPose(pos: Vec3, rot: Quat, out: FloatArray) {
        out[0] = pos.x; out[1] = pos.y; out[2] = pos.z; out[3] = rot.x; out[4] = rot.y; out[5] = rot.z; out[6] = rot.w
    }
}

/**
 * SteamVR's standing play space from ARCore's world: recentering puts the user at the origin,
 * facing -Z, with the eyes at [eyeHeight] above the floor.
 */
class PlaySpace(var eyeHeight: Float = 1.65f) {
    private val origin = Vec3()
    private val yawInv = Quat()
    private var ready = false

    fun recenter(head: Vec3, headRot: Quat) {
        val f = headRot.rotate(Vec3(0f, 0f, -1f))
        val yaw = atan2(-f.x, -f.z)
        Quat.fromAxisAngle(0f, 1f, 0f, -yaw, yawInv)
        origin.set(head)
        ready = true
    }

    val isSet: Boolean get() = ready

    fun apply(pos: Vec3, rot: Quat, outPos: Vec3, outRot: Quat) {
        yawInv.rotate(Vec3.sub(pos, origin), outPos)
        outPos.y += eyeHeight
        yawInv.mul(rot, outRot)
    }

    companion object {
        /** Yaw (radians) of a rotation about +Y, for tests and diagnostics. */
        fun yawOf(q: Quat): Float { val f = q.rotate(Vec3(0f, 0f, -1f)); return atan2(-f.x, -f.z) }
        fun yaw(radians: Float) = Quat.fromAxisAngle(0f, 1f, 0f, radians)
    }
}
