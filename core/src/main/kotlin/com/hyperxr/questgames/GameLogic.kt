package com.hyperxr.questgames

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Game rules and physics for HyperXR's ten games inspired by the most famous Quest games
 * (mechanics only: names, art and code are HyperXR's own; models are Kenney's CC0 kits).
 * Everything here is plain math in game space (metres, y up, the player at the origin facing -z),
 * so it is tested on the JVM; the app module draws it and feeds it the hands and controllers.
 */

/** Segment (a -> b) against a sphere: the first hit fraction along the segment, or -1. */
fun segmentSphere(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float, r: Float): Float {
    val dx = bx - ax; val dy = by - ay; val dz = bz - az
    val fx = ax - cx; val fy = ay - cy; val fz = az - cz
    val a = dx * dx + dy * dy + dz * dz
    val c = fx * fx + fy * fy + fz * fz - r * r
    if (c <= 0f) return 0f
    if (a < 1e-9f) return -1f
    val b = 2 * (fx * dx + fy * dy + fz * dz)
    val disc = b * b - 4 * a * c
    if (disc < 0f) return -1f
    val t = (-b - sqrt(disc)) / (2 * a)
    return if (t in 0f..1f) t else -1f
}

/** Segment against an axis-aligned box (centre, half size): true when they touch. */
fun segmentBox(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float, h: Float): Boolean {
    var t0 = 0f; var t1 = 1f
    val o = floatArrayOf(ax - cx, ay - cy, az - cz)
    val d = floatArrayOf(bx - ax, by - ay, bz - az)
    for (i in 0..2) {
        if (abs(d[i]) < 1e-9f) { if (abs(o[i]) > h) return false; continue }
        var ta = (-h - o[i]) / d[i]; var tb = (h - o[i]) / d[i]
        if (ta > tb) { val s = ta; ta = tb; tb = s }
        t0 = max(t0, ta); t1 = min(t1, tb)
        if (t0 > t1) return false
    }
    return true
}

// =============================================================================== 1. rhythm slicing
/** One block of the rhythm game: when it reaches you, where, which hand, which way to cut. */
class Note(val time: Float, val lane: Int, val row: Int, val hand: Int, val dir: Int) {
    var done = false
    var hit = false
    companion object { const val UP = 0; const val DOWN = 1; const val LEFT = 2; const val RIGHT = 3; const val ANY = 4 }
}

/**
 * The block chart for a song: hands alternate on the beat, with pairs on strong beats, cut
 * directions that flow (a down cut is followed by an up cut with the same hand), and density
 * rising through the song. Deterministic for a seed.
 */
object BeatChart {
    fun generate(bpm: Float, lengthS: Float, seed: Long = 1, startS: Float = 3f): List<Note> {
        val rnd = java.util.Random(seed)
        val beat = 60f / bpm
        val out = ArrayList<Note>()
        // the last up/down cut per hand: sideways cuts in between do not break the alternation
        val lastDir = intArrayOf(Note.UP, Note.UP)
        var t = startS
        var i = 0
        while (t < lengthS - 2f) {
            val progress = t / lengthS
            val bar = i % 8
            val hand = i % 2
            fun dirFor(h: Int): Int {
                // keep the swing flowing: alternate down/up, with a sideways cut now and then
                if (rnd.nextFloat() < 0.2f + progress * 0.2f) return if (h == 0) Note.LEFT else Note.RIGHT
                val d = if (lastDir[h] == Note.DOWN) Note.UP else Note.DOWN
                lastDir[h] = d
                return d
            }
            val lane = if (hand == 0) rnd.nextInt(2) else 2 + rnd.nextInt(2)
            out += Note(t, lane, rnd.nextInt(3), hand, dirFor(hand))
            if (bar == 0 && progress > 0.25f) out += Note(t, if (hand == 0) 2 + rnd.nextInt(2) else rnd.nextInt(2), rnd.nextInt(3), 1 - hand, dirFor(1 - hand))
            // denser later: half beats appear after a third of the song
            t += if (progress > 0.33f && rnd.nextFloat() < 0.35f + progress * 0.3f) beat / 2 else beat
            i++
        }
        return out
    }

    /** Lane and row to a position: 4 lanes 0.3 m apart, rows at 0.85, 1.15, 1.45 m. */
    fun laneX(lane: Int) = (lane - 1.5f) * 0.3f
    fun rowY(row: Int) = 0.85f + row * 0.3f
}

/**
 * Was the block cut? The hand's path this frame (a -> b) must pass through the block, fast
 * enough (1.1 m/s), and in the block's direction (within ~60 degrees). Returns 0 for a miss,
 * otherwise a score from the swing's speed and angle.
 */
object SliceJudge {
    fun judge(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, dt: Float, cx: Float, cy: Float, cz: Float, half: Float, dir: Int): Int {
        if (dt <= 0f || !segmentBox(ax, ay, az, bx, by, bz, cx, cy, cz, half * 1.3f)) return 0
        val vx = (bx - ax) / dt; val vy = (by - ay) / dt
        val speed = hypot(vx, vy)
        if (speed < 1.1f) return 0
        val ok = when (dir) {
            Note.UP -> vy / speed > 0.5f
            Note.DOWN -> vy / speed < -0.5f
            Note.LEFT -> vx / speed < -0.5f
            Note.RIGHT -> vx / speed > 0.5f
            else -> true
        }
        if (!ok) return 0
        return (60 + min(speed, 4f) * 15).toInt()
    }
}

/**
 * The rhythm game's music, synthesised: a four-on-the-floor kick, snare on 2 and 4, hats on the
 * off-beats, a bass line and an arpeggiated chord loop (Am - F - C - G), at [bpm].
 */
class BeatMusic(private val bpm: Float = 128f, private val rate: Int = 48000) {
    @Volatile var volume = 0.8f
    private var n = 0L
    private val rnd = java.util.Random(3)
    private val chords = arrayOf(floatArrayOf(57f, 60f, 64f), floatArrayOf(53f, 57f, 60f), floatArrayOf(48f, 52f, 55f), floatArrayOf(55f, 59f, 62f))
    private var lp = 0f
    private fun hz(midi: Float) = 440f * Math.pow(2.0, ((midi - 69f) / 12f).toDouble()).toFloat()

    /** Seconds of music rendered so far. */
    val time get() = n.toFloat() / rate

    fun render(out: FloatArray, frames: Int) {
        val spb = 60f / bpm
        for (i in 0 until frames) {
            val t = (n + i).toFloat() / rate
            val beatPos = t / spb
            val beat = beatPos.toInt()
            val inBeat = (beatPos - beat) * spb
            val bar = beat / 4
            val chord = chords[bar % 4]
            // kick: a falling sine
            val kf = 50f + 120f * exp(-inBeat * 25f)
            var s = sin(2 * PI.toFloat() * kf * inBeat) * exp(-inBeat * 9f) * 0.9f
            // snare on 2 and 4: noise burst
            if (beat % 2 == 1) s += (rnd.nextFloat() * 2 - 1) * exp(-inBeat * 18f) * 0.35f
            // hats on the off-beat
            val half = (beatPos * 2 - (beatPos * 2).toInt()) * spb / 2
            if (((beatPos * 2).toInt()) % 2 == 1) s += (rnd.nextFloat() * 2 - 1) * exp(-half * 60f) * 0.12f
            // bass: root on eighths
            val bassF = hz(chord[0] - 24f)
            s += (if (sin(2 * PI.toFloat() * bassF * t) > 0) 1f else -1f) * 0.08f * (0.6f + 0.4f * exp(-half * 8f))
            // arpeggio on sixteenths
            val sixteenth = (beatPos * 4).toInt()
            val note = chord[sixteenth % 3] + 12f
            val st = (beatPos * 4 - sixteenth) * spb / 4
            s += sin(2 * PI.toFloat() * hz(note) * t) * exp(-st * 14f) * 0.12f
            lp += (s - lp) * 0.6f
            val v = lp * volume
            out[i * 2] = v / sqrt(1f + v * v); out[i * 2 + 1] = out[i * 2]
        }
        n += frames
    }
}

// =============================================================================== 2. fruit slicing
/** Something thrown up in front of you: ballistic, spinning. */
class Thrown(var x: Float, var y: Float, var z: Float, var vx: Float, var vy: Float, var vz: Float, val kind: Int, val radius: Float, val bomb: Boolean) {
    var spin = 0f; val spinRate = (Math.random().toFloat() - 0.5f) * 8f
    var cut = false
    fun step(dt: Float) { vy -= 9.81f * 0.55f * dt; x += vx * dt; y += vy * dt; z += vz * dt; spin += spinRate * dt }
}

object FruitThrower {
    /** A throw from below the view that peaks between 1.3 and 1.9 m, 1 to 1.6 m in front. */
    fun toss(rnd: java.util.Random, kinds: Int, bombChance: Float): Thrown {
        val peak = 1.3f + rnd.nextFloat() * 0.6f
        val g = 9.81f * 0.55f
        val y0 = 0.3f
        val vy = sqrt(2 * g * (peak - y0))
        val x0 = (rnd.nextFloat() - 0.5f) * 1.4f
        val z = -(1.0f + rnd.nextFloat() * 0.6f)
        val bomb = rnd.nextFloat() < bombChance
        return Thrown(x0, y0, z, -x0 * 0.4f + (rnd.nextFloat() - 0.5f) * 0.5f, vy, 0f, rnd.nextInt(kinds), if (bomb) 0.12f else 0.1f, bomb)
    }

    /** A swipe: a hand's path this frame through the fruit, fast enough (1.3 m/s). */
    fun sliced(t: Thrown, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, dt: Float): Boolean {
        if (dt <= 0f) return false
        val speed = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az)) / dt
        return speed > 1.3f && segmentSphere(ax, ay, az, bx, by, bz, t.x, t.y, t.z, t.radius + 0.04f) >= 0f
    }
}

// =============================================================================== 3. boxing
/**
 * The boxing opponent: circles you, guards, and throws punches that are telegraphed (a wind-up
 * you can see) so you can slip (move your head) or block (gloves in front of your face).
 */
class Boxer(seed: Long = 4) {
    enum class State { MOVE, WINDUP, PUNCH, RECOVER, STUNNED, DOWN }
    private val rnd = java.util.Random(seed)
    var state = State.MOVE; private set
    var t = 0f; private set
    var health = 100f
    var playerHealth = 100f
    /** Where the opponent stands (x, z) and where its fist is going (at the player's head when it started). */
    var x = 0f; var z = -1.1f
    var targetX = 0f; var targetY = 1.6f; var targetZ = 0f
    var punchHand = 0
    private var angle = 0f
    private var nextPunch = 2f
    var lastResult = ""; private set

    val headY get() = 1.62f

    fun update(dt: Float, headX: Float, headY: Float, headZ: Float, guarded: Boolean) {
        t += dt
        when (state) {
            State.MOVE -> {
                // circle around the player at about 1.1 m, facing them
                angle += dt * (0.25f + 0.2f * sin(t * 0.7f))
                val r = 1.05f + 0.1f * sin(t * 1.3f)
                x = headX + sin(angle) * r * 0.5f; z = headZ - cos(angle * 0.5f) * r
                nextPunch -= dt
                if (nextPunch <= 0f) { state = State.WINDUP; t = 0f; punchHand = rnd.nextInt(2); targetX = headX; targetY = headY; targetZ = headZ }
            }
            State.WINDUP -> if (t > WINDUP) { state = State.PUNCH; t = 0f }
            State.PUNCH -> if (t > PUNCH) {
                // lands where the head was at the wind-up: slipping moves the head out of the way
                val moved = hypot(headX - targetX, headZ - targetZ) + abs(headY - targetY)
                lastResult = when {
                    moved > SLIP -> "Esquivou!"
                    guarded -> { playerHealth -= 3f; "Bloqueou" }
                    else -> { playerHealth -= 12f; "Levou um soco" }
                }
                state = State.RECOVER; t = 0f
            }
            State.RECOVER -> if (t > 0.5f) { state = State.MOVE; t = 0f; nextPunch = 1.2f + rnd.nextFloat() * 2f * (health / 100f + 0.3f) }
            State.STUNNED -> if (t > 1.2f) { state = State.MOVE; t = 0f; nextPunch = 1.5f }
            State.DOWN -> Unit
        }
    }

    /** A punch from the player: [speed] m/s at the head (true) or body. Returns the damage done. */
    fun hit(speed: Float, head: Boolean): Float {
        if (state == State.DOWN || speed < 1.8f) return 0f
        // the guard: most body shots are blocked unless thrown during its own punch
        val open = state == State.WINDUP || state == State.RECOVER || state == State.STUNNED
        val dmg = (speed - 1.5f).coerceAtMost(4f) * (if (head) 4f else 2.2f) * (if (open) 1.6f else 0.8f)
        health -= dmg
        if (health <= 0f) { health = 0f; state = State.DOWN; t = 0f } else if (head && speed > 3.5f && open) { state = State.STUNNED; t = 0f }
        return dmg
    }

    /** 0..1 how far the punch has travelled (for drawing the glove). */
    fun punchExtent(): Float = when (state) {
        State.WINDUP -> -0.3f * (t / WINDUP)
        State.PUNCH -> (t / PUNCH)
        State.RECOVER -> 1f - t / 0.5f
        else -> 0f
    }

    companion object { const val WINDUP = 0.55f; const val PUNCH = 0.18f; const val SLIP = 0.22f }
}

// =============================================================================== 5. mini golf
/** A mini golf hole: walls as segments (x0, z0, x1, z1), the cup, the tee. Metres. */
class GolfHole(val walls: FloatArray, val cupX: Float, val cupZ: Float, val teeX: Float, val teeZ: Float, val par: Int, val boxes: FloatArray = FloatArray(0))

class GolfBall {
    var x = 0f; var z = 0f; var vx = 0f; var vz = 0f
    var inCup = false
    val radius = 0.021f
    val moving get() = hypot(vx, vz) > 0.01f

    /** One physics step: rolling friction, wall bounces, the cup's capture. */
    fun step(h: GolfHole, dt: Float) {
        if (inCup) return
        val sub = 4
        val d = dt / sub
        repeat(sub) {
            val sp = hypot(vx, vz)
            if (sp > 0f) {
                val ns = max(0f, sp - FRICTION * d)
                vx *= ns / sp; vz *= ns / sp
            }
            x += vx * d; z += vz * d
            collide(h.walls)
            // obstacle boxes (x, z, half x, half z) act as four walls each
            for (b in 0 until h.boxes.size / 4) {
                val bx = h.boxes[b * 4]; val bz = h.boxes[b * 4 + 1]; val hx = h.boxes[b * 4 + 2]; val hz = h.boxes[b * 4 + 3]
                collide(floatArrayOf(bx - hx, bz - hz, bx + hx, bz - hz, bx + hx, bz - hz, bx + hx, bz + hz, bx + hx, bz + hz, bx - hx, bz + hz, bx - hx, bz + hz, bx - hx, bz - hz))
            }
            val dc = hypot(x - h.cupX, z - h.cupZ)
            if (dc < CUP_RADIUS && hypot(vx, vz) < CAPTURE_SPEED) { inCup = true; vx = 0f; vz = 0f; x = h.cupX; z = h.cupZ }
            else if (dc < CUP_RADIUS * 1.6f && dc > 1e-4f) {
                // the cup's lip pulls a slow ball in a little
                vx += (h.cupX - x) / dc * 0.4f * d; vz += (h.cupZ - z) / dc * 0.4f * d
            }
        }
    }

    private fun collide(w: FloatArray) {
        for (i in 0 until w.size / 4) {
            val ax = w[i * 4]; val az = w[i * 4 + 1]; val bx = w[i * 4 + 2]; val bz = w[i * 4 + 3]
            val dx = bx - ax; val dz = bz - az
            val len2 = dx * dx + dz * dz
            val t = (((x - ax) * dx + (z - az) * dz) / len2).coerceIn(0f, 1f)
            val px = ax + dx * t; val pz = az + dz * t
            val ex = x - px; val ez = z - pz
            val dist = hypot(ex, ez)
            if (dist < radius && dist > 1e-6f) {
                val nx = ex / dist; val nz = ez / dist
                x = px + nx * radius; z = pz + nz * radius
                val vn = vx * nx + vz * nz
                if (vn < 0f) { vx -= (1 + RESTITUTION) * vn * nx; vz -= (1 + RESTITUTION) * vn * nz }
            }
        }
    }

    /** The putter's head moved from (ax, az) to (bx, bz) this frame: a hit gives the ball its speed. */
    fun putt(ax: Float, az: Float, bx: Float, bz: Float, dt: Float): Boolean {
        if (inCup || moving || dt <= 0f) return false
        val t = segmentSphere(ax, 0f, az, bx, 0f, bz, x, 0f, z, radius + 0.025f)
        if (t < 0f) return false
        val vx0 = (bx - ax) / dt; val vz0 = (bz - az) / dt
        val sp = hypot(vx0, vz0)
        if (sp < 0.15f) return false
        val k = min(sp * 1.3f, MAX_SPEED) / sp
        vx = vx0 * k; vz = vz0 * k
        return true
    }

    companion object {
        const val FRICTION = 0.55f
        const val RESTITUTION = 0.72f
        const val CUP_RADIUS = 0.054f
        const val CAPTURE_SPEED = 1.3f
        const val MAX_SPEED = 4.5f
    }
}

object GolfCourses {
    /** Walls of a rectangle from (x0, z0) to (x1, z1). */
    private fun rect(x0: Float, z0: Float, x1: Float, z1: Float) = floatArrayOf(x0, z0, x1, z0, x1, z0, x1, z1, x1, z1, x0, z1, x0, z1, x0, z0)

    /** Three holes laid out in front of the player (the tee 0.6 m ahead). */
    val holes = listOf(
        // 1: a straight lane
        GolfHole(rect(-0.3f, -3.4f, 0.3f, -0.4f), 0f, -3.0f, 0f, -0.7f, 2),
        // 2: an L to the right
        GolfHole(floatArrayOf(-0.3f, -0.4f, 0.3f, -0.4f, 0.3f, -0.4f, 0.3f, -2.0f, 0.3f, -2.0f, 1.9f, -2.0f, 1.9f, -2.0f, 1.9f, -2.6f,
            1.9f, -2.6f, -0.3f, -2.6f, -0.3f, -2.6f, -0.3f, -0.4f), 1.6f, -2.3f, 0f, -0.7f, 3),
        // 3: a wide room with a block in the middle
        GolfHole(rect(-0.8f, -3.6f, 0.8f, -0.4f), 0.4f, -3.2f, 0f, -0.7f, 3, floatArrayOf(0f, -2.0f, 0.35f, 0.08f)),
    )
}

// =============================================================================== 6. slow time
/** Time moves when you move: from the head's and hands' speeds to a time scale (0.05..1). */
object SlowTime {
    fun scale(headSpeed: Float, handSpeed: Float): Float {
        val m = max(headSpeed * 1.4f, handSpeed * 0.6f)
        return (0.05f + m / 1.2f).coerceIn(0.05f, 1f)
    }
}

// =============================================================================== 8. table tennis
/** The table tennis ball (game space: the table's centre at (0, 0.76, -1.6), length along z). */
class PingPongBall {
    var x = 0f; var y = 1f; var z = -0.5f
    var vx = 0f; var vy = 0f; var vz = 0f
    var bouncesPlayerSide = 0; var bouncesOpponentSide = 0
    var lastHitByPlayer = false
    var inPlay = false

    fun step(dt: Float): String? {
        if (!inPlay) return null
        val sub = 4; val d = dt / sub
        repeat(sub) {
            vy -= G * d
            // air drag (a light ball slows down noticeably)
            val sp = sqrt(vx * vx + vy * vy + vz * vz)
            val k = (1f - DRAG * sp * d).coerceAtLeast(0.9f)
            vx *= k; vy *= k; vz *= k
            x += vx * d; y += vy * d; z += vz * d
            // the table
            if (y < TABLE_Y + R && vy < 0f && abs(x) < TABLE_W / 2 && z < TABLE_NEAR && z > TABLE_FAR) {
                y = TABLE_Y + R; vy = -vy * BOUNCE; vx *= 0.97f; vz *= 0.97f
                if (z > TABLE_Z) bouncesPlayerSide++ else bouncesOpponentSide++
                if (lastHitByPlayer && z > TABLE_Z) return "Ponto do adversário: a bola quicou do seu lado"
                if (!lastHitByPlayer && z < TABLE_Z) return "Seu ponto: a bola quicou do lado dele"
                if (bouncesPlayerSide > 1) return "Ponto do adversário: dois quiques do seu lado"
                if (bouncesOpponentSide > 1) return "Seu ponto: dois quiques do lado dele"
            }
            // the net
            if (abs(z - TABLE_Z) < R && y < TABLE_Y + NET_H && abs(x) < TABLE_W / 2 + 0.15f) { vz = -vz * 0.2f; z += if (vz > 0) R else -R }
            if (y < 0.05f) return if (lastHitByPlayer) (if (bouncesOpponentSide > 0) "Seu ponto" else "Ponto do adversário: fora") else (if (bouncesPlayerSide > 0) "Ponto do adversário" else "Seu ponto: fora")
        }
        return null
    }

    /**
     * The paddle (centre, normal, velocity) touched the ball: it leaves along the paddle's
     * normal with the paddle's speed added, like a real stroke.
     */
    fun paddleHit(px: Float, py: Float, pz: Float, nx: Float, ny: Float, nz: Float, pvx: Float, pvy: Float, pvz: Float): Boolean {
        val dx = x - px; val dy = y - py; val dz = z - pz
        val dn = dx * nx + dy * ny + dz * nz
        if (abs(dn) > 0.04f) return false
        val ix = dx - dn * nx; val iy = dy - dn * ny; val iz = dz - dn * nz
        if (sqrt(ix * ix + iy * iy + iz * iz) > PADDLE_R) return false
        val rvx = vx - pvx; val rvy = vy - pvy; val rvz = vz - pvz
        val rn = rvx * nx + rvy * ny + rvz * nz
        // only a ball coming towards the paddle face it is on
        if (dn * rn >= 0f) return false
        vx -= (1 + 0.85f) * rn * nx; vy -= (1 + 0.85f) * rn * ny; vz -= (1 + 0.85f) * rn * nz
        // a gentle lift so returns clear the net
        vy += 0.6f
        lastHitByPlayer = true; bouncesPlayerSide = 0; bouncesOpponentSide = 0
        return true
    }

    /**
     * The opponent's return: aims at a point on the player's half and solves the launch
     * velocity that gets there in [time] seconds (gravity only; drag is small at this range).
     */
    fun opponentReturn(targetX: Float, targetZ: Float, time: Float) {
        vx = (targetX - x) / time
        vz = (targetZ - z) / time
        vy = ((TABLE_Y + R) - y + 0.5f * G * time * time) / time
        lastHitByPlayer = false; bouncesPlayerSide = 0; bouncesOpponentSide = 0
    }

    companion object {
        const val G = 9.81f
        const val DRAG = 0.12f
        const val BOUNCE = 0.88f
        const val R = 0.02f
        const val TABLE_Y = 0.76f
        const val TABLE_W = 1.525f
        const val TABLE_Z = -1.9f
        const val TABLE_NEAR = TABLE_Z + 1.37f
        const val TABLE_FAR = TABLE_Z - 1.37f
        const val NET_H = 0.1525f
        const val PADDLE_R = 0.085f
    }
}

// =============================================================================== 9. fishing
/** The fishing line: cast, wait, the bite, the fight, the catch. */
class FishingLine(seed: Long = 8) {
    enum class State { IDLE, FLYING, WAITING, NIBBLE, BITE, HOOKED, CAUGHT, ESCAPED }
    class Fish(val name: String, val weightKg: Float, val strength: Float)

    private val rnd = java.util.Random(seed)
    var state = State.IDLE; private set
    var t = 0f; private set
    var bx = 0f; var by = 0f; var bz = 0f
    private var bvx = 0f; private var bvy = 0f; private var bvz = 0f
    var distance = 0f; private set
    var tension = 0f; private set
    var fish: Fish? = null; private set
    private var biteAt = 0f
    private var pull = 0f
    var waterY = 0f

    /** Casts from the rod tip with a launch velocity (from the swing). */
    fun cast(x: Float, y: Float, z: Float, vx: Float, vy: Float, vz: Float) {
        if (state != State.IDLE && state != State.CAUGHT && state != State.ESCAPED) return
        bx = x; by = y; bz = z; bvx = vx; bvy = vy; bvz = vz
        state = State.FLYING; t = 0f; fish = null; tension = 0f
    }

    /** [reeling] = the reel button is held this frame; [strike] = it was just pressed. */
    fun update(dt: Float, rodX: Float, rodZ: Float, reeling: Boolean, strike: Boolean): String? {
        t += dt
        when (state) {
            State.FLYING -> {
                bvy -= 9.81f * dt; bx += bvx * dt; by += bvy * dt; bz += bvz * dt
                if (by <= waterY) { by = waterY; state = State.WAITING; t = 0f; biteAt = 2.5f + rnd.nextFloat() * 7f; distance = hypot(bx - rodX, bz - rodZ) }
            }
            State.WAITING -> {
                if (reeling) reelIn(dt, rodX, rodZ, 0.6f)
                if (t > biteAt) { state = State.NIBBLE; t = 0f }
            }
            State.NIBBLE -> {
                if (strike) { state = State.WAITING; t = 0f; biteAt = 2f + rnd.nextFloat() * 5f; return "Cedo demais: espere a boia afundar" }
                if (t > 0.8f + rnd.nextFloat() * 0.02f) { state = State.BITE; t = 0f }
            }
            State.BITE -> {
                if (strike) {
                    fish = pickFish(); state = State.HOOKED; t = 0f; tension = 0.3f
                    return "Fisgou! Recolha a linha sem deixar arrebentar"
                }
                if (t > BITE_WINDOW) { state = State.WAITING; t = 0f; biteAt = 2f + rnd.nextFloat() * 6f; return "O peixe fugiu da isca" }
            }
            State.HOOKED -> {
                val f = fish!!
                // the fish pulls in bursts; reeling against a pull raises the tension fast
                pull = (0.5f + 0.5f * sin(t * (1.3f + f.strength))) * f.strength
                tension += ((if (reeling) 0.55f + pull else -0.45f) + pull * 0.3f) * dt
                tension = tension.coerceAtLeast(0f)
                if (reeling) reelIn(dt, rodX, rodZ, 1.1f - pull * 0.6f) else distance += pull * 0.25f * dt
                if (tension > 1f) { state = State.ESCAPED; t = 0f; return "A linha arrebentou!" }
                if (distance < 1.2f) { state = State.CAUGHT; t = 0f; return "Pegou: ${f.name}, ${"%.1f".format(f.weightKg)} kg" }
            }
            else -> Unit
        }
        return null
    }

    private fun reelIn(dt: Float, rodX: Float, rodZ: Float, speed: Float) {
        distance = max(0.5f, distance - speed * dt)
        val dx = bx - rodX; val dz = bz - rodZ
        val d = hypot(dx, dz).coerceAtLeast(1e-3f)
        bx = rodX + dx / d * distance; bz = rodZ + dz / d * distance
        if (state == State.WAITING && distance <= 1.0f) { state = State.IDLE }
    }

    private fun pickFish(): Fish {
        val r = rnd.nextFloat()
        return when {
            r < 0.45f -> Fish("Tilápia", 0.4f + rnd.nextFloat() * 1.2f, 0.25f)
            r < 0.75f -> Fish("Tucunaré", 1.0f + rnd.nextFloat() * 3f, 0.45f)
            r < 0.93f -> Fish("Dourado", 2f + rnd.nextFloat() * 6f, 0.65f)
            else -> Fish("Pirarucu", 10f + rnd.nextFloat() * 40f, 0.85f)
        }
    }

    /** How far the bobber is pulled under (0..1), for drawing. */
    fun bobberDip(): Float = when (state) {
        State.NIBBLE -> 0.3f * (0.5f + 0.5f * sin(t * 30f))
        State.BITE -> 1f
        State.HOOKED -> 0.8f
        else -> 0f
    }

    companion object { const val BITE_WINDOW = 1.1f }
}

// =============================================================================== 10. diorama platformer
/**
 * A little hero on a tabletop world of blocks: walk with the stick (relative to where you
 * look), jump, collect coins, reach the flag. The world is a height map of columns
 * ([heights], cells of 1 unit, 0 = a gap to fall into) with coins and a goal.
 */
class Diorama(val w: Int, val d: Int, val heights: IntArray, val coins: MutableList<IntArray>, val goalX: Int, val goalZ: Int, val startX: Int, val startZ: Int) {
    var x = startX + 0.5f; var y = heights[startZ * w + startX].toFloat(); var z = startZ + 0.5f
    var vy = 0f
    var onGround = true
    var collected = 0
    var falls = 0
    var won = false
    var facing = 0f

    fun heightAt(fx: Float, fz: Float): Int {
        val ix = fx.toInt(); val iz = fz.toInt()
        if (fx < 0 || fz < 0 || ix >= w || iz >= d) return 0
        return heights[iz * w + ix]
    }

    /** One step: [mx]/[mz] the wanted walk direction in world cells (length up to 1), [jump] pressed. */
    fun step(dt: Float, mx: Float, mz: Float, jump: Boolean): String? {
        if (won) return null
        val len = hypot(mx, mz)
        if (len > 0.1f) facing = kotlin.math.atan2(mx, mz)
        val nx = x + mx * SPEED * dt; val nz = z + mz * SPEED * dt
        // walls: a column higher than half a block above the feet blocks the way
        if (heightAt(nx, z) <= y + 0.5f || heightAt(nx, z) == 0) x = nx
        if (heightAt(x, nz) <= y + 0.5f || heightAt(x, nz) == 0) z = nz
        if (jump && onGround) { vy = JUMP; onGround = false }
        vy -= GRAVITY * dt
        y += vy * dt
        val ground = heightAt(x, z)
        if (ground > 0 && y <= ground && vy <= 0f) {
            if (ground - y < 0.6f) { y = ground.toFloat(); vy = 0f; onGround = true }
        } else if (ground > 0 && y > ground + 0.01f) onGround = false
        // a step up of half a block is climbed by walking
        if (ground > 0 && onGround && ground > y && ground - y <= 0.5f) y = ground.toFloat()
        if (y < -3f) { falls++; x = startX + 0.5f; z = startZ + 0.5f; y = heights[startZ * w + startX].toFloat(); vy = 0f; onGround = true; return "Caiu! De volta ao começo" }
        val it = coins.iterator()
        while (it.hasNext()) {
            val c = it.next()
            if (hypot(c[0] + 0.5f - x, c[1] + 0.5f - z) < 0.5f && abs(heights[c[1] * w + c[0]] + 0.5f - y) < 0.9f) { it.remove(); collected++; return "Moeda!" }
        }
        if (x.toInt() == goalX && z.toInt() == goalZ && onGround) { won = true; return "Chegou à bandeira!" }
        return null
    }

    companion object {
        const val SPEED = 3.2f
        const val JUMP = 6.2f
        const val GRAVITY = 16f

        /** The first world: islands, a bridge of steps, a tower with the flag. */
        fun level1(): Diorama {
            val map = arrayOf(
                "1111..222..3333",
                "1111..222..3333",
                "11111122211333.",
                "1111..222..3334",
                "1111..2.2...334",
                "..1.......2.345",
            )
            val w = map[0].length; val d = map.size
            val h = IntArray(w * d)
            for (zz in 0 until d) for (xx in 0 until w) h[zz * w + xx] = map[zz][xx].let { if (it == '.') 0 else it - '0' }
            val coins = mutableListOf(intArrayOf(1, 1), intArrayOf(7, 0), intArrayOf(8, 4), intArrayOf(12, 1), intArrayOf(13, 4))
            return Diorama(w, d, h, coins, 14, 5, 0, 0)
        }
    }
}

// =============================================================================== 7. space pirate drones
/** A drone circling the player and firing slow bolts; positions in game space. */
class Drone(seed: Long) {
    private val rnd = java.util.Random(seed)
    var x = 0f; var y = 1.6f; var z = -6f
    var hp = 1
    private var a = rnd.nextFloat() * 6.28f
    private val r = 4.5f + rnd.nextFloat() * 4f
    private val h = 1.2f + rnd.nextFloat() * 1.6f
    private val speed = 0.25f + rnd.nextFloat() * 0.3f
    var fireIn = 2f + rnd.nextFloat() * 3f

    /** Moves; returns true when it fires this step. */
    fun step(dt: Float, arc: Float = 1.2f): Boolean {
        a += speed * dt
        // stays in an arc in front of the player
        val ang = sin(a) * arc
        x = sin(ang) * r; z = -cos(ang) * r; y = h + sin(a * 2.3f) * 0.4f
        fireIn -= dt
        if (fireIn <= 0f) { fireIn = 2.5f + rnd.nextFloat() * 3f; return true }
        return false
    }
}

/** A projectile (bolt, bullet) flying straight. */
class Bolt(var x: Float, var y: Float, var z: Float, val vx: Float, val vy: Float, val vz: Float, val fromPlayer: Boolean) {
    var life = 4f
    fun step(dt: Float) { x += vx * dt; y += vy * dt; z += vz * dt; life -= dt }
}

/** Ray against a sphere: the distance along the (unit) ray, or -1. */
fun raySphere(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, cx: Float, cy: Float, cz: Float, r: Float): Float {
    val lx = cx - ox; val ly = cy - oy; val lz = cz - oz
    val tca = lx * dx + ly * dy + lz * dz
    if (tca < 0f) return -1f
    val d2 = lx * lx + ly * ly + lz * lz - tca * tca
    if (d2 > r * r) return -1f
    return tca - sqrt(r * r - d2)
}

/**
 * Climbing (Escalada): you hold the wall, not the floor. Grab a hold and your body hangs from it:
 * moving the hand down pulls you up. [offset] is where your body is moved to in the world
 * (game space = your room + offset). Grabbers: the two hands and the controller.
 *
 * Released with nothing held, you fall after a short grace (time to reach the next hold) and land
 * on the ground or the highest ledge below you. Hanging by one grip tires it ([stamina]); with
 * two grips or standing it recovers; at zero the grip slips.
 */
class ClimbRig(val holds: List<FloatArray>, val ledges: FloatArray, val summitY: Float) {
    class Grip { var hold = -1; var ax = 0f; var ay = 0f; var az = 0f }
    val grips = Array(3) { Grip() }
    var ox = 0f; var oy = 0f; var oz = 0f
    var vy = 0f
    var standing = true; private set
    var stamina = 1f; private set
    var grace = 0f; private set
    var maxHeight = 0f; private set
    var falls = 0; private set
    var won = false; private set

    /** The nearest hold to a point in the world within [reach], or -1. */
    fun nearest(x: Float, y: Float, z: Float, reach: Float): Int {
        var best = -1; var bd = reach * reach
        for ((i, h) in holds.withIndex()) {
            val d = (h[0] - x) * (h[0] - x) + (h[1] - y) * (h[1] - y) + (h[2] - z) * (h[2] - z)
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    /**
     * One step. For each grabber: its position in the room (x, y, z) and whether it is closed
     * (fist, trigger); [present] false when it is not tracked. Returns the index of a hold just
     * grabbed (for the sound), or -1.
     */
    fun step(dt: Float, px: FloatArray, py: FloatArray, pz: FloatArray, closed: BooleanArray, present: BooleanArray, reach: FloatArray, graceTime: Float): Int {
        var grabbed = -1
        for (i in grips.indices) {
            val g = grips[i]
            if (!present[i] || !closed[i]) { g.hold = -1; continue }
            if (g.hold >= 0) continue
            val wx = px[i] + ox; val wy = py[i] + oy; val wz = pz[i] + oz
            val h = nearest(wx, wy, wz, reach[i])
            if (h >= 0) { g.hold = h; g.ax = wx - holds[h][0]; g.ay = wy - holds[h][1]; g.az = wz - holds[h][2]; grabbed = h }
        }
        val held = grips.withIndex().filter { it.value.hold >= 0 }
        if (held.isNotEmpty()) {
            // the body goes where the held hands stay on their holds
            var nx = 0f; var ny = 0f; var nz = 0f
            for ((i, g) in held) {
                val h = holds[g.hold]
                nx += h[0] + g.ax - px[i]; ny += h[1] + g.ay - py[i]; nz += h[2] + g.az - pz[i]
            }
            nx /= held.size; ny /= held.size; nz /= held.size
            vy = if (dt > 0f) ((ny - oy) / dt).coerceIn(-6f, 6f) else 0f
            ox = nx; oy = maxOf(ny, ground()); oz = nz
            standing = false
            grace = graceTime
            stamina = (stamina + (if (held.size >= 2) 0.25f else -0.09f) * dt).coerceIn(0f, 1f)
            if (stamina <= 0f) { for (g in grips) g.hold = -1; grace = 0f }
            if (oy + 1.6f >= summitY) won = true
        } else if (!standing) {
            if (grace > 0f) grace -= dt
            else {
                vy -= 9.81f * dt
                val land = ground()
                oy += vy * dt
                if (oy <= land) { if (vy < -3f) falls++; oy = land; vy = 0f; standing = true }
            }
        } else stamina = (stamina + 0.5f * dt).coerceAtMost(1f)
        maxHeight = maxOf(maxHeight, oy)
        return grabbed
    }

    /** The ground or the highest ledge at or below the body. */
    fun ground(): Float {
        var g = 0f
        for (l in ledges) if (l <= oy + 0.02f && l > g) g = l
        return g
    }

    companion object {
        /**
         * A route up a cliff [height] metres high: holds every 35 to 50 cm, zigzagging within reach,
         * a second hold beside most of them, on the wall plane z = [wallZ]; ledges every 9 m.
         */
        fun route(seed: Long, height: Float = 27f, wallZ: Float = -0.42f): ClimbRig {
            val rnd = java.util.Random(seed)
            val holds = ArrayList<FloatArray>()
            var y = 0.9f; var x = 0f
            while (y < height + 1.2f) {
                holds += floatArrayOf(x, y, wallZ)
                if (rnd.nextFloat() < 0.8f) holds += floatArrayOf((x + (if (rnd.nextBoolean()) 0.45f else -0.45f)).coerceIn(-1.1f, 1.1f), y + (rnd.nextFloat() - 0.5f) * 0.2f, wallZ)
                y += 0.35f + rnd.nextFloat() * 0.15f
                x = (x + (rnd.nextFloat() - 0.5f) * 0.6f).coerceIn(-0.9f, 0.9f)
            }
            val ledges = floatArrayOf(9f, 18f)
            return ClimbRig(holds, ledges, height)
        }
    }
}
