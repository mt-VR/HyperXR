package com.hyperxr

import com.hyperxr.input.DirectTouch
import com.hyperxr.input.InteractionSurface
import com.hyperxr.input.PointerEvent
import com.hyperxr.input.PointerPhase
import com.hyperxr.input.SurfaceMath
import com.hyperxr.math.Pose
import com.hyperxr.math.Ray
import com.hyperxr.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class CurvedSurfaceTest {
    /** 1.6 m arc, radius 1.5 m, centred 1.5 m in front of a viewer at the origin. */
    private class Curved : InteractionSurface {
        override val surfaceId = "curved"
        override val pose = Pose(Vec3(0f, 0f, -1.5f))
        override val widthMeters = 1.6f
        override val heightMeters = 0.9f
        override val curvatureRadius = 1.5f
        val events = mutableListOf<PointerEvent>()
        override fun onPointerEvent(event: PointerEvent) { events += event }
    }

    @Test fun rayFromTheAxisHitsAtTheRadiusEverywhere() {
        val s = Curved()
        val uv = FloatArray(2)
        // from the cylinder axis every direction within the arc is exactly r away
        for (deg in listOf(-25f, 0f, 20f)) {
            val a = Math.toRadians(deg.toDouble()).toFloat()
            val t = SurfaceMath.raycast(s, Ray(Vec3(), Vec3(sin(a), 0f, -cos(a))), uv)
            assertEquals(1.5f, t, 1e-4f)
            // arc length position maps linearly to u
            assertEquals(0.5f + 1.5f * a / 1.6f, uv[0], 1e-4f)
        }
        // outside the arc (beyond ±30.6°) nothing is hit
        val a = Math.toRadians(40.0).toFloat()
        assertTrue(SurfaceMath.raycast(s, Ray(Vec3(), Vec3(sin(a), 0f, -cos(a))), uv).isNaN())
        // from behind the surface nothing is hit
        assertTrue(SurfaceMath.raycast(s, Ray(Vec3(0f, 0f, -3f), Vec3(0f, 0f, 1f)), uv).isNaN())
    }

    @Test fun directTouchWorksOnTheCurvedEdge() {
        val s = Curved()
        val dt = DirectTouch(0)
        val a = Math.toRadians(20.0).toFloat()
        var t = 0L
        // finger approaches the surface radially at 20 degrees
        for (d in listOf(1.40f, 1.45f, 1.49f, 1.496f, 1.47f)) {
            dt.update(Vec3(sin(a) * d, 0f, -cos(a) * d), false, listOf(s), t); t += 20_000_000L
        }
        val phases = s.events.map { it.phase }.filter { it == PointerPhase.DOWN || it == PointerPhase.UP }
        assertEquals(listOf(PointerPhase.DOWN, PointerPhase.UP), phases)
        assertEquals(0.5f + 1.5f * a / 1.6f, s.events.first { it.phase == PointerPhase.DOWN }.u, 1e-3f)
    }
}
