package com.hyperxr

import com.hyperxr.questgames.ClimbRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClimbRigTest {
    private val px = FloatArray(3); private val py = FloatArray(3); private val pz = FloatArray(3)
    private val closed = BooleanArray(3); private val present = booleanArrayOf(true, false, false)
    private val reach = floatArrayOf(0.15f, 0.15f, 0.2f)

    @Test fun pullingDownLiftsTheBody() {
        val rig = ClimbRig(listOf(floatArrayOf(0f, 1.5f, -0.4f)), floatArrayOf(), 20f)
        px[0] = 0f; py[0] = 1.5f; pz[0] = -0.4f; closed[0] = true
        assertEquals(0, rig.step(0.016f, px, py, pz, closed, present, reach, 0.5f))
        // the hand comes down 40 cm: the body goes up 40 cm
        py[0] = 1.1f
        rig.step(0.016f, px, py, pz, closed, present, reach, 0.5f)
        assertEquals(0.4f, rig.oy, 1e-4f)
    }

    @Test fun letGoFallsAfterTheGraceAndLandsOnALedge() {
        val rig = ClimbRig(listOf(floatArrayOf(0f, 11.5f, -0.4f)), floatArrayOf(9f), 20f)
        rig.oy = 10f
        px[0] = 0f; py[0] = 1.5f; pz[0] = -0.4f; closed[0] = true
        rig.step(0.016f, px, py, pz, closed, present, reach, 0.5f)
        assertTrue(rig.grips[0].hold == 0)
        closed[0] = false
        var t = 0f
        while (t < 0.45f) { rig.step(0.016f, px, py, pz, closed, present, reach, 0.5f); t += 0.016f }
        assertEquals(10f, rig.oy, 0.05f)   // still in the grace time
        repeat(200) { rig.step(0.016f, px, py, pz, closed, present, reach, 0.5f) }
        assertEquals(9f, rig.oy, 1e-4f)
        assertTrue(rig.standing)
    }

    @Test fun oneHandTiresAndSlips() {
        val rig = ClimbRig(listOf(floatArrayOf(0f, 1.5f, -0.4f)), floatArrayOf(), 20f)
        px[0] = 0f; py[0] = 1.5f; pz[0] = -0.4f; closed[0] = true
        repeat(60 * 15) { rig.step(1f / 60f, px, py, pz, closed, present, reach, 0f) }
        assertEquals(-1, rig.grips[0].hold)
    }

    @Test fun routeReachesTheTopWithinArmLength() {
        val rig = ClimbRig.route(5)
        val main = rig.holds.sortedBy { it[1] }
        assertTrue(main.last()[1] >= 27f)
        for ((a, b) in main.zipWithNext()) assertTrue(b[1] - a[1] < 0.6f)
    }

    @Test fun lettingGoOfOneHandDoesNotJerkTheBody() {
        val rig = ClimbRig(listOf(floatArrayOf(-0.2f, 1.5f, -0.4f), floatArrayOf(0.2f, 1.5f, -0.4f)), floatArrayOf(), 20f)
        val both = booleanArrayOf(true, true, false)
        px[0] = -0.2f; py[0] = 1.5f; pz[0] = -0.4f; px[1] = 0.2f; py[1] = 1.5f; pz[1] = -0.4f
        closed[0] = true; closed[1] = true
        rig.step(0.016f, px, py, pz, closed, both, reach, 0.5f)
        // the two tracked hands drift apart by 4 cm (tracking noise): the body follows their average
        py[0] = 1.46f; py[1] = 1.5f
        rig.step(0.016f, px, py, pz, closed, both, reach, 0.5f)
        val before = rig.oy
        // let go with the left: the body stays put instead of jumping 2 cm
        closed[0] = false
        rig.step(0.016f, px, py, pz, closed, both, reach, 0.5f)
        assertEquals(before, rig.oy, 1e-4f)
        closed[0] = false; closed[1] = false
    }
}
