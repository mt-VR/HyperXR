package com.hyperxr.input

import com.hyperxr.math.Pose
import com.hyperxr.math.Vec3
import kotlin.math.atan2

/**
 * Drag-and-drop between windows: a payload (e.g. a link) is picked by one pointer and dropped
 * where that same pointer is released. Other pointers never end the drag.
 */
class DragDrop<P : Any> {
    var payload: P? = null
        private set
    var pointerId = -1
        private set
    val active: Boolean get() = payload != null

    data class Drop<P>(val payload: P, val target: Any?)

    fun begin(p: P, pointer: Int) { payload = p; pointerId = pointer }
    fun cancel() { payload = null; pointerId = -1 }

    /** Call every frame for the dragging pointer; returns the drop when it is released. */
    fun update(pointer: Int, down: Boolean, target: Any?): Drop<P>? {
        val p = payload ?: return null
        if (pointer != pointerId || down) return null
        cancel()
        return Drop(p, target)
    }
}

/**
 * Pinch-and-hold radial menu. Holding a pinch away from any button opens the menu centred on the
 * pinch point; moving the hand towards an item highlights it; releasing the pinch confirms the
 * highlighted item (or cancels when the hand is back in the centre). No aiming at buttons needed.
 */
class RadialMenu(val items: List<String>, var holdNs: Long = 450_000_000L, var deadZone: Float = 0.035f) {
    enum class Phase { IDLE, ARMING, OPEN }

    var phase = Phase.IDLE
        private set
    /** Index of the highlighted item, or -1. */
    var selected = -1
        private set
    val center = Vec3()
    /** Orientation of the menu plane (faces the head when opened). */
    val plane = Pose()
    private var pinchStartNs = 0L
    private val start = Vec3()
    private val local = Vec3()

    /**
     * @param pinching pinch state of the hand
     * @param pinchPoint midpoint of thumb and index tips (world)
     * @param overTarget true when the hand's pointer is on a button/window (the pinch belongs to it)
     * @return the confirmed item index on release, or null
     */
    fun update(pinching: Boolean, pinchPoint: Vec3, head: Pose, overTarget: Boolean, nowNs: Long): Int? {
        when (phase) {
            Phase.IDLE -> if (pinching && !overTarget) {
                phase = Phase.ARMING; pinchStartNs = nowNs; start.set(pinchPoint)
            }
            Phase.ARMING -> when {
                !pinching -> phase = Phase.IDLE
                pinchPoint.distanceTo(start) > deadZone -> phase = Phase.IDLE // it was a drag, not a hold
                nowNs - pinchStartNs >= holdNs -> {
                    phase = Phase.OPEN
                    center.set(pinchPoint)
                    plane.position.set(pinchPoint)
                    val dx = pinchPoint.x - head.position.x
                    val dz = pinchPoint.z - head.position.z
                    com.hyperxr.math.Quat.fromAxisAngle(0f, 1f, 0f, atan2(-dx, -dz), plane.orientation)
                    selected = -1
                }
            }
            Phase.OPEN -> {
                plane.inverse().transformPoint(pinchPoint, local)
                val r = kotlin.math.sqrt(local.x * local.x + local.y * local.y)
                selected = if (r < deadZone) -1 else sectorOf(local.x, local.y)
                if (!pinching) {
                    phase = Phase.IDLE
                    val pick = selected
                    selected = -1
                    return if (pick >= 0) pick else null
                }
            }
        }
        return null
    }

    /** Items are laid out clockwise starting at the top. */
    fun sectorOf(x: Float, y: Float): Int {
        val n = items.size
        var a = atan2(x, y) // 0 at top, clockwise positive
        if (a < 0) a += (2 * Math.PI).toFloat()
        val step = (2 * Math.PI / n).toFloat()
        return ((a + step / 2) / step).toInt() % n
    }

    /** Position of item [i] in the menu plane (metres, x right, y up). */
    fun itemOffset(i: Int, radius: Float = 0.09f): Pair<Float, Float> {
        val a = 2 * Math.PI * i / items.size
        return (kotlin.math.sin(a) * radius).toFloat() to (kotlin.math.cos(a) * radius).toFloat()
    }
}

/**
 * Boundary warning from a depth image: finds how close real obstacles are in front of the user
 * (ignoring the floor at the bottom of the image) and turns it into a passthrough fade.
 */
object BoundaryMonitor {
    /**
     * @param depthCm depth in centimetres per pixel (0 = no data), row-major [w] x [h]
     * @return distance in metres of the nearest obstacle (5th percentile of valid pixels), or NaN
     */
    fun nearest(depthCm: ByteArray, w: Int, h: Int, floorFraction: Float = 0.3f): Float {
        val rows = (h * (1f - floorFraction)).toInt().coerceIn(1, h)
        val hist = IntArray(256)
        var n = 0
        for (y in 0 until rows) for (x in 0 until w) {
            val v = depthCm[y * w + x].toInt() and 0xff
            if (v in 1..254) { hist[v]++; n++ }
        }
        if (n < w * rows / 20) return Float.NaN // too little data to trust
        val target = (n * 0.05f).toInt().coerceAtLeast(1)
        var acc = 0
        for (v in 1..254) { acc += hist[v]; if (acc >= target) return v / 100f }
        return Float.NaN
    }

    /** 0 when farther than [warnM], 1 at [fullM] or closer, smooth in between. */
    fun fade(distanceM: Float, warnM: Float = 1.0f, fullM: Float = 0.35f): Float {
        if (distanceM.isNaN()) return 0f
        val t = ((warnM - distanceM) / (warnM - fullM)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }
}
