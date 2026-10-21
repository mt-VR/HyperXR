package com.hyperxr.input

import com.hyperxr.math.Pose
import com.hyperxr.math.Ray
import com.hyperxr.math.Vec3

/** A flat interactive rectangle in world space (panel, window, button group). Local +Z faces the user. */
interface InteractionSurface {
    val surfaceId: String
    val pose: Pose
    val widthMeters: Float
    val heightMeters: Float
    val interactive: Boolean get() = true

    /** u,v in [0,1] with v=0 at the top edge. */
    fun onPointerEvent(event: PointerEvent)
}

enum class PointerPhase { HOVER, DOWN, MOVE, UP, CANCEL, EXIT }

enum class PointerSource { GAZE, SCREEN_TAP, HAND_RAY, DIRECT_TOUCH, JOYCON, OPENXR_CONTROLLER }

data class PointerEvent(
    val pointerId: Int,
    val source: PointerSource,
    val phase: PointerPhase,
    val u: Float,
    val v: Float,
    /** Distance from the pointer to the surface (m); for direct touch the finger depth (negative = pushed through). */
    val distance: Float,
    val timestampNs: Long,
    val secondary: Boolean = false,
)

/** Plane hit math shared by every pointer type. */
object SurfaceMath {
    private val tmpLocal = ThreadLocal.withInitial { Vec3() }
    private val tmpInv = ThreadLocal.withInitial { Pose() }

    /** Converts a world point into surface-local coordinates (x right, y up, z towards viewer). */
    fun toLocal(s: InteractionSurface, world: Vec3, out: Vec3): Vec3 {
        val inv = s.pose.inverse(tmpInv.get())
        return inv.transformPoint(world, out)
    }

    fun localToUv(s: InteractionSurface, local: Vec3, out: FloatArray): Boolean {
        out[0] = local.x / s.widthMeters + 0.5f
        out[1] = 0.5f - local.y / s.heightMeters
        return out[0] in 0f..1f && out[1] in 0f..1f
    }

    /** Ray/plane intersection. Returns hit distance or NaN. */
    fun raycast(s: InteractionSurface, ray: Ray, uvOut: FloatArray): Float {
        val n = s.pose.orientation.rotate(Vec3(0f, 0f, 1f))
        val denom = n.dot(ray.direction)
        if (denom > -1e-5f) return Float.NaN // parallel or hitting the back face
        val toPlane = Vec3.sub(s.pose.position, ray.origin)
        val t = toPlane.dot(n) / denom
        if (t < 0f) return Float.NaN
        val hit = ray.pointAt(t)
        val local = toLocal(s, hit, tmpLocal.get())
        return if (localToUv(s, local, uvOut)) t else Float.NaN
    }
}

/**
 * Direct touch for one fingertip against all surfaces: proximity (hover), probable contact,
 * press/release with hysteresis, drag, and duplicate-click prevention.
 *
 * Thresholds widen automatically when the hand depth is an estimate (hand-size prior),
 * because contact detection is only as good as the depth.
 */
class DirectTouch(val pointerId: Int) {
    var hoverDistance = 0.10f
    var pressDistance = 0.008f
    var releaseDistance = 0.022f
    /** Pressing from behind the panel is ignored; pushing this far through cancels. */
    var pushThroughLimit = 0.08f
    var debounceNs = 150_000_000L
    var uncertainDepthScale = 2.0f

    var hovered: InteractionSurface? = null
        private set
    var pressed: InteractionSurface? = null
        private set
    /** Probability-like contact score [0,1] for visual feedback (glow intensity). */
    var contactScore = 0f
        private set

    private var lastPressNs = Long.MIN_VALUE / 2
    private var lastSide = Float.POSITIVE_INFINITY
    private val local = Vec3()
    private val uv = FloatArray(2)

    fun update(tip: Vec3?, depthUncertain: Boolean, surfaces: List<InteractionSurface>, now: Long) {
        val scale = if (depthUncertain) uncertainDepthScale else 1f
        if (tip == null) { cancel(now); return }

        val captured = pressed
        if (captured != null) {
            SurfaceMath.toLocal(captured, tip, local)
            SurfaceMath.localToUv(captured, local, uv)
            val z = local.z
            contactScore = 1f
            when {
                z < -pushThroughLimit -> { emit(captured, PointerPhase.CANCEL, z, now); pressed = null }
                z > releaseDistance * scale -> { emit(captured, PointerPhase.UP, z, now); pressed = null }
                else -> emit(captured, PointerPhase.MOVE, z, now)
            }
            lastSide = z
            return
        }

        // Nearest surface in front of the fingertip whose bounds contain its projection.
        var best: InteractionSurface? = null
        var bestZ = Float.POSITIVE_INFINITY
        for (s in surfaces) {
            if (!s.interactive) continue
            SurfaceMath.toLocal(s, tip, local)
            if (local.z < -0.03f || local.z > hoverDistance * scale) continue
            if (!SurfaceMath.localToUv(s, local, uv)) continue
            if (local.z < bestZ) { bestZ = local.z; best = s }
        }

        if (best !== hovered) {
            hovered?.let { emit(it, PointerPhase.EXIT, Float.NaN, now) }
            hovered = best
            // Unknown previous side: a press needs one observed frame in front of the surface first.
            lastSide = Float.NaN
        }
        val target = best ?: run { contactScore = 0f; return }
        SurfaceMath.toLocal(target, tip, local)
        SurfaceMath.localToUv(target, local, uv)
        val z = local.z
        contactScore = (1f - (z - pressDistance * scale) / (hoverDistance * scale)).coerceIn(0f, 1f)

        // Press only when crossing the threshold from the front (prevents phantom presses from behind)
        val crossedFromFront = lastSide > pressDistance * scale && z <= pressDistance * scale
        if (crossedFromFront && now - lastPressNs >= debounceNs) {
            lastPressNs = now
            pressed = target
            emit(target, PointerPhase.DOWN, z, now)
        } else {
            emit(target, PointerPhase.HOVER, z, now)
        }
        lastSide = z
    }

    private fun cancel(now: Long) {
        pressed?.let { emit(it, PointerPhase.CANCEL, Float.NaN, now) }
        hovered?.let { if (it !== pressed) emit(it, PointerPhase.EXIT, Float.NaN, now) }
        pressed = null; hovered = null; contactScore = 0f
        lastSide = Float.POSITIVE_INFINITY
    }

    private fun emit(s: InteractionSurface, phase: PointerPhase, z: Float, now: Long) {
        s.onPointerEvent(PointerEvent(pointerId, PointerSource.DIRECT_TOUCH, phase, uv[0], uv[1], z, now))
    }
}

/**
 * Ray pointer (gaze, hand ray, Joy-Con). Button state comes from the caller; the pointer
 * performs hit testing, hover/exit, press capture (drag continues on the captured surface even
 * when the ray leaves it) and edge detection so a held button never re-fires.
 */
class RayPointer(val pointerId: Int, val source: PointerSource) {
    var hovered: InteractionSurface? = null
        private set
    var captured: InteractionSurface? = null
        private set
    var hitDistance = Float.NaN
        private set
    private var wasDown = false
    private var secondaryDown = false
    private val uv = FloatArray(2)

    fun update(ray: Ray?, primaryDown: Boolean, surfaces: List<InteractionSurface>, now: Long, secondary: Boolean = false) {
        if (ray == null) {
            captured?.let { send(it, PointerPhase.CANCEL, now) }
            hovered?.let { send(it, PointerPhase.EXIT, now) }
            captured = null; hovered = null; wasDown = false
            return
        }
        val cap = captured
        if (cap != null) {
            val d = SurfaceMath.raycast(cap, ray, uv)
            if (d.isNaN()) planeUv(cap, ray)
            hitDistance = d
            if (primaryDown) send(cap, PointerPhase.MOVE, now)
            else { send(cap, PointerPhase.UP, now); captured = null }
            wasDown = primaryDown
            return
        }

        var best: InteractionSurface? = null
        var bestD = Float.POSITIVE_INFINITY
        var bu = 0f; var bv = 0f
        for (s in surfaces) {
            if (!s.interactive) continue
            val d = SurfaceMath.raycast(s, ray, uv)
            if (!d.isNaN() && d < bestD) { bestD = d; best = s; bu = uv[0]; bv = uv[1] }
        }
        uv[0] = bu; uv[1] = bv
        if (best !== hovered) {
            hovered?.let { send(it, PointerPhase.EXIT, now) }
            hovered = best
        }
        hitDistance = if (best != null) bestD else Float.NaN
        val target = best
        if (target != null) {
            secondaryDown = secondary
            if (primaryDown && !wasDown) { captured = target; send(target, PointerPhase.DOWN, now) }
            else send(target, PointerPhase.HOVER, now)
        }
        wasDown = primaryDown
    }

    /** Keeps drag coordinates meaningful when the ray leaves the captured surface bounds. */
    private fun planeUv(s: InteractionSurface, ray: Ray) {
        val n = s.pose.orientation.rotate(Vec3(0f, 0f, 1f))
        val denom = n.dot(ray.direction)
        if (kotlin.math.abs(denom) < 1e-5f) return
        val t = Vec3.sub(s.pose.position, ray.origin).dot(n) / denom
        val local = SurfaceMath.toLocal(s, ray.pointAt(t), Vec3())
        uv[0] = local.x / s.widthMeters + 0.5f
        uv[1] = 0.5f - local.y / s.heightMeters
    }

    private fun send(s: InteractionSurface, phase: PointerPhase, now: Long) {
        s.onPointerEvent(PointerEvent(pointerId, source, phase, uv[0], uv[1], hitDistance, now, secondaryDown))
    }
}
