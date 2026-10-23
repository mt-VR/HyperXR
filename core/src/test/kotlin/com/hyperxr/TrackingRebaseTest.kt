package com.hyperxr

import com.hyperxr.headtracking.TrackingRebase
import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingRebaseTest {
    @Test fun headContinuesFromWhereItWasShownAfterARelocalisation() {
        // shown before the loss: 0.4 m to the right, turned 20 degrees, looking a bit down
        val shown = Pose(Vec3(0.4f, 1.6f, -0.2f), Quat.fromAxisAngle(0f, 1f, 0f, 0.35f).mul(Quat.fromAxisAngle(1f, 0f, 0f, -0.2f)))
        // ARCore comes back with its world shifted 3 m and turned 90 degrees
        val headWorld = Pose(Vec3(3.1f, 0.9f, 2.2f), Quat.fromAxisAngle(0f, 1f, 0f, 0.35f + 1.57f).mul(Quat.fromAxisAngle(1f, 0f, 0f, -0.2f)))
        val origin = TrackingRebase.origin(headWorld, shown)
        val rel = origin.inverse().compose(headWorld)
        assertEquals(shown.position.x, rel.position.x, 1e-4f)
        assertEquals(shown.position.y, rel.position.y, 1e-4f)
        assertEquals(shown.position.z, rel.position.z, 1e-4f)
        assertTrue(shown.orientation.angleTo(rel.orientation) < 1e-3f)
        // the origin only turns about the vertical: the floor stays level
        val up = origin.orientation.rotate(Vec3(0f, 1f, 0f))
        assertEquals(1f, up.y, 1e-5f)
    }

    @Test fun smallDifferencesAreNotJumps() {
        val a = Pose(Vec3(0f, 1.6f, 0f), Quat.fromAxisAngle(0f, 1f, 0f, 0.1f))
        assertFalse(TrackingRebase.jumped(a, Pose(Vec3(0.05f, 1.62f, 0.03f), Quat.fromAxisAngle(0f, 1f, 0f, 0.2f))))
        assertTrue(TrackingRebase.jumped(a, Pose(Vec3(0.6f, 1.6f, 0f), Quat.fromAxisAngle(0f, 1f, 0f, 0.1f))))
        assertTrue(TrackingRebase.jumped(a, Pose(Vec3(0f, 1.6f, 0f), Quat.fromAxisAngle(0f, 1f, 0f, 0.6f))))
    }
}
