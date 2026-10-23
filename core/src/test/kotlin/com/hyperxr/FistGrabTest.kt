package com.hyperxr

import com.hyperxr.input.InteractionSurface
import com.hyperxr.input.PointerEvent
import com.hyperxr.input.PointerPhase
import com.hyperxr.input.PointerSource
import com.hyperxr.input.RayPointer
import com.hyperxr.math.Pose
import com.hyperxr.math.Ray
import com.hyperxr.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FistGrabTest {
    private class Surface(override val acceptsGrab: Boolean) : InteractionSurface {
        override val surfaceId = "s$acceptsGrab"
        override val pose = Pose(Vec3(0f, 1.5f, -1f))
        override val widthMeters = 1f
        override val heightMeters = 1f
        val events = ArrayList<PointerEvent>()
        override fun onPointerEvent(event: PointerEvent) { events += event }
    }

    private val ray = Ray().apply { origin.set(0f, 1.5f, 0f); direction.set(0f, 0f, -1f) }

    @Test fun aFistGrabsPanelsAndIsIgnoredByContent() {
        val panel = Surface(acceptsGrab = true)
        val p = RayPointer(5, PointerSource.HAND_RAY).apply { grabbing = true }
        p.update(ray, false, listOf(panel), 0L); p.update(ray, true, listOf(panel), 1L)
        val down = panel.events.first { it.phase == PointerPhase.DOWN }
        assertTrue(down.grab)

        val page = Surface(acceptsGrab = false)
        val q = RayPointer(6, PointerSource.HAND_RAY).apply { grabbing = true }
        q.update(ray, false, listOf(page), 0L); q.update(ray, true, listOf(page), 1L)
        assertEquals(0, page.events.count { it.phase == PointerPhase.DOWN })
    }

    @Test fun aPinchStillClicksContent() {
        val page = Surface(acceptsGrab = false)
        val p = RayPointer(5, PointerSource.HAND_RAY)
        p.update(ray, false, listOf(page), 0L); p.update(ray, true, listOf(page), 1L)
        val down = page.events.first { it.phase == PointerPhase.DOWN }
        assertTrue(!down.grab)
    }
}
