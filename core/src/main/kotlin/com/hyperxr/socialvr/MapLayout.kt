package com.hyperxr.socialvr

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** A loaded piece, by its bounds (metres, y up, as the GLB places it). */
data class PieceBounds(val name: String, val ground: Boolean, val minX: Float, val minY: Float, val minZ: Float, val maxX: Float, val maxY: Float, val maxZ: Float) {
    val width get() = maxX - minX
    val depth get() = maxZ - minZ
    val height get() = maxY - minY
    /** Radius of the footprint. */
    val radius get() = 0.5f * kotlin.math.sqrt(width * width + depth * depth)
}

/** Where a piece goes: its origin moves to (x, y, z) and it turns by [yaw] (radians, about y). */
data class Placement(val name: String, val x: Float, val y: Float, val z: Float, val yaw: Float)

/**
 * Lays a map out of a pack's pieces (each authored around its own origin): the ground pieces in
 * the middle with their top at the floor, the other pieces standing on the floor on rings around
 * a free plaza, the big ones first, each turned towards the middle. The same pieces always give
 * the same map, on every phone in the room.
 */
object MapLayout {
    /** Radius of the free plaza in the middle (where players arrive). */
    const val PLAZA = 3.5f
    /** Free space between neighbours on a ring and between rings. */
    const val GAP = 1.2f
    /** Pieces larger than this (metres) are scenery around the plaza, left as authored. */
    const val HUGE = 40f

    fun arrange(pieces: List<PieceBounds>): List<Placement> {
        val out = ArrayList<Placement>()
        // the floor: centred, its top at y = 0
        for (g in pieces.filter { it.ground }) {
            val cx = (g.minX + g.maxX) / 2; val cz = (g.minZ + g.maxZ) / 2
            out += Placement(g.name, -cx, -g.maxY, -cz, 0f)
        }
        val props = pieces.filter { !it.ground }.sortedWith(compareByDescending<PieceBounds> { it.radius }.thenBy { it.name })
        var ringR = PLAZA
        var i = 0
        while (i < props.size) {
            // one ring: as many pieces as fit around it; it is as deep as its biggest piece
            val ring = ArrayList<PieceBounds>()
            var used = 0f
            var deepest = 0f
            val circumference = (2 * PI * (ringR + props[i].radius)).toFloat()
            while (i < props.size) {
                val p = props[i]
                val arc = 2 * p.radius.coerceAtMost(HUGE) + GAP
                if (ring.isNotEmpty() && used + arc > circumference) break
                ring += p; used += arc; deepest = max(deepest, p.radius.coerceAtMost(HUGE)); i++
            }
            val radius = ringR + deepest
            // spread the ring's pieces evenly, each by the share of the circle it needs
            var angle = 0.0
            val scale = (2 * PI) / max(used, circumference).toDouble()
            for (p in ring) {
                val arc = 2 * p.radius.coerceAtMost(HUGE) + GAP
                val a = angle + arc * scale / 2
                angle += arc * scale
                val x = (sin(a) * radius).toFloat(); val z = (-cos(a) * radius).toFloat()
                val cx = (p.minX + p.maxX) / 2; val cz = (p.minZ + p.maxZ) / 2
                val yaw = atan2(x, z)   // its front (-z) towards the middle
                // move the piece's footprint centre onto the spot, its bottom onto the floor
                val c = cos(yaw); val s = sin(yaw)
                out += Placement(p.name, x - (cx * c + cz * s), -p.minY, z - (-cx * s + cz * c), yaw)
            }
            ringR = radius + deepest + GAP
        }
        return out
    }
}
