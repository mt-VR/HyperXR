package com.hyperxr.handtracking

import com.hyperxr.math.Vec3

/**
 * Opens the quick menu with a finger gun: make a gun with the index pointing up (thumb out, the
 * other three fingers closed), close the index (the "pointer"), take the hand where the menu
 * should be and open the index again: the menu opens at the fingertip. Closing the whole hand,
 * waiting too long or never making the gun first cancels it.
 */
class GunMenuGesture(
    /** How long the gun must be held before closing the index counts. */
    var aimNs: Long = 150_000_000L,
    /** How long the index may stay closed while the hand moves to the spot. */
    var cockedNs: Long = 4_000_000_000L,
) {
    enum class Phase { IDLE, AIMING, COCKED }

    var phase = Phase.IDLE; private set
    /** Where the menu opens (the index tip), valid when [update] returned true. */
    val at = Vec3()
    private var since = 0L

    fun reset() { phase = Phase.IDLE }

    /** One frame; [j] null when the hand is not tracked. True on the frame the menu should open. */
    fun update(j: Array<Vec3>?, nowNs: Long): Boolean {
        if (j == null || !othersClosed(j)) { phase = Phase.IDLE; return false }
        when (phase) {
            Phase.IDLE -> if (isGunUp(j)) { phase = Phase.AIMING; since = nowNs }
            Phase.AIMING -> when {
                indexClosed(j) -> if (nowNs - since >= aimNs) { phase = Phase.COCKED; since = nowNs } else phase = Phase.IDLE
                indexOpen(j) && !pointsUp(j) -> phase = Phase.IDLE
            }
            Phase.COCKED -> when {
                nowNs - since > cockedNs -> phase = Phase.IDLE
                indexOpen(j) -> { at.set(j[HandJoint.INDEX_TIP]); phase = Phase.IDLE; return true }
            }
        }
        return false
    }

    companion object {
        /** Fingertip distance from the wrist over its knuckle's: about 1.8-2 stretched, near 1 closed. */
        fun reach(j: Array<Vec3>, tip: Int): Float {
            val w = j[HandJoint.WRIST]
            val k = j[tip - 3].distanceTo(w)
            return if (k < 1e-6f) 0f else j[tip].distanceTo(w) / k
        }

        fun indexOpen(j: Array<Vec3>) = reach(j, HandJoint.INDEX_TIP) > 1.55f
        fun indexClosed(j: Array<Vec3>) = reach(j, HandJoint.INDEX_TIP) < 1.3f

        /** Middle, ring and pinky closed. */
        fun othersClosed(j: Array<Vec3>) =
            reach(j, HandJoint.MIDDLE_TIP) < 1.35f && reach(j, HandJoint.RING_TIP) < 1.35f && reach(j, HandJoint.PINKY_TIP) < 1.4f

        /** The thumb sticks out from the hand (not wrapped over the fingers). */
        fun thumbOut(j: Array<Vec3>): Boolean {
            val palm = j[HandJoint.WRIST].distanceTo(j[HandJoint.MIDDLE_MCP])
            return j[HandJoint.THUMB_TIP].distanceTo(j[HandJoint.MIDDLE_MCP]) > 0.75f * palm
        }

        /** The index points up (world y). */
        fun pointsUp(j: Array<Vec3>): Boolean {
            val a = j[HandJoint.INDEX_MCP]; val b = j[HandJoint.INDEX_TIP]
            val dx = b.x - a.x; val dy = b.y - a.y; val dz = b.z - a.z
            val l = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            return l > 1e-6f && dy / l > 0.7f
        }

        fun isGunUp(j: Array<Vec3>) = indexOpen(j) && othersClosed(j) && thumbOut(j) && pointsUp(j)
    }
}
