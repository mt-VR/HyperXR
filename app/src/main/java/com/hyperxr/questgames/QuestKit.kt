package com.hyperxr.questgames

import com.hyperxr.math.Vec3
import kotlin.math.sqrt

internal fun rgb(r: Float, g: Float, b: Float, a: Float = 1f) = floatArrayOf(r, g, b, a)

/** Something that can be picked up and thrown: a ball, a dart, a puzzle piece. */
internal class Prop(var x: Float, var y: Float, var z: Float, val r: Float, val kind: Int = 0) {
    val home = floatArrayOf(x, y, z)
    var vx = 0f; var vy = 0f; var vz = 0f
    var held = -1
    var alive = true
    var resting = false
    var tag = 0
    var yaw = 0f
    fun reset() { x = home[0]; y = home[1]; z = home[2]; vx = 0f; vy = 0f; vz = 0f; held = -1; resting = false }
    fun speed() = sqrt(vx * vx + vy * vy + vz * vz)
}

/**
 * Picking things up for the HyperMagic games: a closed hand, a pinch or the trigger near a prop
 * grabs it; opening throws it with the hand's speed (a little boosted, arms on phones move less).
 * Hands 0 and 1 are the tracked hands, 2 is the controller.
 */
internal class Grabber(val host: GameHost, val reach: Float = 0.14f, val throwBoost: Float = 1.25f) {
    val pos = Array(3) { FloatArray(3) }
    val vel = Array(3) { FloatArray(3) }
    val closed = BooleanArray(3)
    val present = BooleanArray(3)
    private val was = BooleanArray(3)
    val holding = arrayOfNulls<Prop>(3)

    /** Reads the hands; returns the props released this frame (thrown). */
    fun update(props: List<Prop>, onGrab: (Prop, Int) -> Unit = { _, _ -> }, onThrow: (Prop, Int) -> Unit = { _, _ -> }) {
        for (i in 0..2) {
            was[i] = closed[i]
            if (i < 2) {
                val g = host.hands[i]
                present[i] = g.usable
                if (g.usable) {
                    pos[i][0] = g.palm.x; pos[i][1] = g.palm.y; pos[i][2] = g.palm.z
                    vel[i][0] = g.velocity.x; vel[i][1] = g.velocity.y; vel[i][2] = g.velocity.z
                    closed[i] = g.fist || g.pinch
                } else closed[i] = false
            } else {
                val p = host.pad
                present[2] = p.connected
                if (p.connected) {
                    pos[2][0] = p.pose.position.x; pos[2][1] = p.pose.position.y; pos[2][2] = p.pose.position.z
                    vel[2][0] = p.velocity.x; vel[2][1] = p.velocity.y; vel[2][2] = p.velocity.z
                    closed[2] = p.trigger
                } else closed[2] = false
            }
            val h = holding[i]
            if (closed[i] && !was[i] && h == null) {
                var best: Prop? = null; var bd = reach * reach
                for (p in props) {
                    if (!p.alive || p.held >= 0) continue
                    val dx = p.x - pos[i][0]; val dy = p.y - pos[i][1]; val dz = p.z - pos[i][2]
                    val d = dx * dx + dy * dy + dz * dz - p.r * p.r
                    if (d < bd) { bd = d; best = p }
                }
                if (best != null) { best.held = i; holding[i] = best; best.resting = false; onGrab(best, i); if (i == 2) host.pad.buzz(15) }
            }
            if (h != null) {
                if (!closed[i] || !present[i]) {
                    h.held = -1; holding[i] = null
                    h.vx = vel[i][0] * throwBoost; h.vy = vel[i][1] * throwBoost; h.vz = vel[i][2] * throwBoost
                    onThrow(h, i)
                } else { h.x = pos[i][0]; h.y = pos[i][1]; h.z = pos[i][2] }
            }
        }
    }

    fun drop(p: Prop) { for (i in 0..2) if (holding[i] === p) holding[i] = null; p.held = -1 }
}

/** Gravity, a floor at [floor] and a bit of bounce; props at rest stop. */
internal fun Prop.physics(dt: Float, floor: Float = 0f, bounce: Float = 0.35f, gravity: Float = 9.81f, drag: Float = 0.995f) {
    if (held >= 0 || resting || !alive) return
    vy -= gravity * dt
    x += vx * dt; y += vy * dt; z += vz * dt
    vx *= drag; vz *= drag
    if (y - r < floor) {
        y = floor + r
        if (vy < -0.6f) { vy = -vy * bounce; vx *= 0.8f; vz *= 0.8f }
        else { vy = 0f; vx *= 0.9f; vz *= 0.9f; if (vx * vx + vz * vz < 0.0004f) resting = true }
    }
}

internal fun dist(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
    val dx = ax - bx; val dy = ay - by; val dz = az - bz
    return sqrt(dx * dx + dy * dy + dz * dz)
}

/** Ray from (o) along (d) against a sphere: distance, or -1. */
internal fun rayHit(o: Vec3, d: Vec3, cx: Float, cy: Float, cz: Float, r: Float): Float = raySphere(o.x, o.y, o.z, d.x, d.y, d.z, cx, cy, cz, r)

/** Finger guns: a pinch fires where the index points, the trigger where the controller points. */
internal class Shooter(val host: GameHost) {
    private val was = BooleanArray(2)
    private var padWas = false
    val origin = Vec3(); val dir = Vec3()

    /** Calls [fire] with the ray for every shot this frame. */
    fun update(fire: (Vec3, Vec3, Int) -> Unit) {
        for (i in 0..1) {
            val g = host.hands[i]
            val p = g.usable && g.pinch
            if (p && !was[i]) { origin.set(g.tip); dir.set(g.dir).normalize(); fire(origin, dir, i) }
            was[i] = p
        }
        val t = host.pad.connected && host.pad.trigger
        if (t && !padWas) { origin.set(host.pad.aim.origin); dir.set(host.pad.aim.direction).normalize(); fire(origin, dir, 2) }
        padWas = t
    }
}

/** A beat clock for the rhythm games: [beat] counts up at [bpm], [onBeat] is true on the frame a beat starts. */
internal class BeatClock(val bpm: Float) {
    var time = 0f; private set
    var beat = 0; private set
    var onBeat = false; private set
    val period get() = 60f / bpm
    fun step(dt: Float) {
        time += dt
        val b = (time / period).toInt()
        onBeat = b != beat
        beat = b
    }
    /** How close [time] is to a beat: 0 on it, 0.5 between two. */
    fun offBeat(): Float { val f = (time / period) % 1f; return minOf(f, 1f - f) }
}

/** The tracked hand's palm or the controller (when present): index 0, 1 hands, 2 controller. */
internal fun GameHost.handPos(i: Int, out: Vec3): Boolean = when (i) {
    0, 1 -> if (hands[i].usable) { out.set(hands[i].palm); true } else false
    else -> if (pad.connected) { out.set(pad.pose.position); true } else false
}

internal fun GameHost.handSpeed(i: Int): Float = when (i) {
    0, 1 -> if (hands[i].usable) hands[i].speed else 0f
    else -> if (pad.connected) pad.velocity.length() else 0f
}
