package com.hyperxr.handtracking

import com.hyperxr.math.Vec3

/** 21-landmark hand topology (wrist + 4 joints per finger), the de-facto standard for landmark models. */
object HandJoint {
    const val WRIST = 0
    const val THUMB_CMC = 1; const val THUMB_MCP = 2; const val THUMB_IP = 3; const val THUMB_TIP = 4
    const val INDEX_MCP = 5; const val INDEX_PIP = 6; const val INDEX_DIP = 7; const val INDEX_TIP = 8
    const val MIDDLE_MCP = 9; const val MIDDLE_PIP = 10; const val MIDDLE_DIP = 11; const val MIDDLE_TIP = 12
    const val RING_MCP = 13; const val RING_PIP = 14; const val RING_DIP = 15; const val RING_TIP = 16
    const val PINKY_MCP = 17; const val PINKY_PIP = 18; const val PINKY_DIP = 19; const val PINKY_TIP = 20
    const val COUNT = 21

    /** Bone list used by the aura renderer and by skeleton sanity checks. */
    val BONES: Array<IntArray> = arrayOf(
        intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 4),
        intArrayOf(0, 5), intArrayOf(5, 6), intArrayOf(6, 7), intArrayOf(7, 8),
        intArrayOf(5, 9), intArrayOf(9, 10), intArrayOf(10, 11), intArrayOf(11, 12),
        intArrayOf(9, 13), intArrayOf(13, 14), intArrayOf(14, 15), intArrayOf(15, 16),
        intArrayOf(13, 17), intArrayOf(0, 17), intArrayOf(17, 18), intArrayOf(18, 19), intArrayOf(19, 20),
    )

    /** Approximate bone radii in meters for an adult hand, used by the aura and direct-touch colliders. */
    fun radiusFor(joint: Int): Float = when (joint) {
        WRIST -> 0.022f
        THUMB_CMC, INDEX_MCP, MIDDLE_MCP, RING_MCP, PINKY_MCP -> 0.013f
        THUMB_TIP, INDEX_TIP, MIDDLE_TIP, RING_TIP, PINKY_TIP -> 0.008f
        else -> 0.010f
    }
}

enum class Handedness { LEFT, RIGHT }

/** Raw output of a landmark model for one hand in one camera frame. */
class LandmarkObservation(
    val handedness: Handedness,
    /** Normalized image coordinates in [0,1], plus model-relative z (same scale as x, wrist-centred). */
    val image: FloatArray = FloatArray(HandJoint.COUNT * 3),
    /** Model presence/landmark score in [0,1]. */
    var score: Float = 0f,
    var timestampNs: Long = 0L,
)

/** Pinhole intrinsics of the frame the landmarks were computed on (in normalized units if fx/fy normalized). */
data class CameraIntrinsics(val fx: Float, val fy: Float, val cx: Float, val cy: Float, val width: Int, val height: Int)

/** Where the 3D estimate came from; consumers use it to decide what interactions are trustworthy. */
enum class DepthSource { NONE, DEPTH_MAP, HAND_SIZE_PRIOR }

enum class TrackingState { NOT_TRACKED, TRACKING, LOST_PREDICTING, RECOVERING }

/** Fused, filtered hand state in the tracking (world) space. */
class HandState(val handedness: Handedness) {
    val joints: Array<Vec3> = Array(HandJoint.COUNT) { Vec3() }
    var state: TrackingState = TrackingState.NOT_TRACKED
    var confidence: Float = 0f
    var depthSource: DepthSource = DepthSource.NONE
    /** 0..1 thumb-index closure. */
    var pinchStrength: Float = 0f
    var gesture: Gesture = Gesture.NONE
    var timestampNs: Long = 0L

    /** Includes the short coasting phase after a missed detection (gesture and position are held). */
    val isUsable: Boolean get() = state != TrackingState.NOT_TRACKED
}

enum class Gesture(val id: Int) { NONE(0), OPEN_PALM(1), PINCH(2), POINT(3), FIST(4), GRAB(5) }
