package com.hyperxr.handtracking

import com.hyperxr.math.Vec3

/**
 * Decides which tracker (left/right) each detection feeds. The detector's handedness label flips
 * between frames (back of the hand, fist, edge views) and sometimes labels both hands the same;
 * trusting it made hands jump between trackers and vanish. A detection is matched to the tracked
 * hand nearest to it; the label only decides when nothing is being tracked nearby.
 */
object HandAssignment {
    /** Distance cap (metres): farther than this a tracked hand does not attract a detection. */
    const val REACH = 0.25f
    /** Cost of contradicting the label (metres-equivalent). */
    const val LABEL_PENALTY = 0.12f

    fun cost(wrist: Vec3, label: Handedness, tracker: HandTracker): Float {
        val s = tracker.state
        val d = if (s.isUsable) minOf(wrist.distanceTo(s.joints[HandJoint.WRIST]), REACH) else REACH
        return d + if (label != s.handedness) LABEL_PENALTY else 0f
    }

    /** @return the side for each of the [n] (≤ 2) detections; never the same side twice. */
    fun assign(n: Int, wrist: (Int) -> Vec3, label: (Int) -> Handedness, trackers: Map<Handedness, HandTracker>): Array<Handedness> {
        val l = trackers.getValue(Handedness.LEFT)
        val r = trackers.getValue(Handedness.RIGHT)
        return when (n) {
            0 -> emptyArray()
            1 -> arrayOf(if (cost(wrist(0), label(0), l) <= cost(wrist(0), label(0), r)) Handedness.LEFT else Handedness.RIGHT)
            else -> {
                val straight = cost(wrist(0), label(0), l) + cost(wrist(1), label(1), r)
                val crossed = cost(wrist(0), label(0), r) + cost(wrist(1), label(1), l)
                if (straight <= crossed) arrayOf(Handedness.LEFT, Handedness.RIGHT) else arrayOf(Handedness.RIGHT, Handedness.LEFT)
            }
        }
    }
}
