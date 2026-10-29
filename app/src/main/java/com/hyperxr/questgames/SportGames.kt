package com.hyperxr.questgames

import com.hyperxr.math.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Throwing and aiming games, with the hands (grab = close the hand or pinch) or the controller.

private val SUNNY = GameSky.Look(
    zenith = rgb(0.3f, 0.55f, 0.95f), horizon = rgb(0.85f, 0.9f, 0.95f), ground = rgb(0.35f, 0.5f, 0.3f),
    sunDir = floatArrayOf(0.4f, 0.7f, 0.3f), sunColor = rgb(1f, 0.95f, 0.85f), clouds = 0.5f,
    floor = GameSky.FLOOR_PLAIN, floorColor = rgb(0.33f, 0.5f, 0.26f), fog = 0.008f,
)

/** Arqueiro: the left hand holds the bow, the right pinches the string near it, pulls back and lets go. */
class ArcheryGame(host: GameHost) : QuestGame(host) {
    override val title = "Arqueiro"
    override val look = SUNNY
    private class Arrow(var x: Float, var y: Float, var z: Float, var vx: Float, var vy: Float, var vz: Float) { var stuck = false; var life = 6f }
    private class Goblin(var x: Float, var z: Float) { var alive = true; var t = 0f }
    private val arrows = ArrayList<Arrow>()
    private val goblins = ArrayList<Goblin>()
    private var drawing = false
    private val bow = Vec3(); private val nock = Vec3(); private val dirV = Vec3()
    private var spawn = 2f; private var wave = 0; private var lives = 5

    override fun start() { status = "Mão esquerda segura o arco; faça a pinça com a direita perto dele, puxe para trás e solte" }

    override fun update(dt: Float) {
        val bowOk = host.handPos(0, bow) || host.handPos(2, bow)
        val right = host.hands[1]
        if (bowOk && right.usable) {
            nock.set(right.tip)
            if (!drawing && right.pinch && dist(nock.x, nock.y, nock.z, bow.x, bow.y, bow.z) < 0.15f) drawing = true
            if (drawing && !right.pinch) {
                drawing = false
                val pull = dist(nock.x, nock.y, nock.z, bow.x, bow.y, bow.z)
                if (pull > 0.12f) {
                    dirV.set(bow).sub(nock).normalize()
                    val sp = 8f + pull * 30f
                    arrows += Arrow(bow.x, bow.y, bow.z, dirV.x * sp, dirV.y * sp, dirV.z * sp)
                    host.play(host.sShoot, bow.x, bow.y, bow.z, 0.6f, 0.7f)
                }
            }
        } else drawing = false
        // controller alone: the trigger shoots where it points
        if (!right.usable && host.pad.triggerDown) {
            val d = host.pad.aim.direction
            arrows += Arrow(host.pad.aim.origin.x, host.pad.aim.origin.y, host.pad.aim.origin.z, d.x * 16f, d.y * 16f, d.z * 16f)
            host.play(host.sShoot, bow.x, bow.y, bow.z, 0.6f, 0.7f)
        }
        spawn -= dt
        if (spawn <= 0f) { wave++; spawn = (3.2f - wave * 0.05f).coerceAtLeast(1.2f); val a = (Math.random().toFloat() - 0.5f) * 1.6f; goblins += Goblin(sin(a) * 25f, -cos(a) * 25f) }
        for (g in goblins) if (g.alive) {
            g.t += dt
            val d = sqrt(g.x * g.x + g.z * g.z).coerceAtLeast(0.1f)
            g.x -= g.x / d * dt * 1.4f; g.z -= g.z / d * dt * 1.4f
            if (d < 1.5f) { g.alive = false; lives--; host.play(host.sMiss, 0f, 1f, 0f) }
        }
        for (a in arrows) {
            if (a.stuck) { a.life -= dt; continue }
            a.vy -= 9.81f * dt * 0.6f
            a.x += a.vx * dt; a.y += a.vy * dt; a.z += a.vz * dt
            a.life -= dt
            if (a.y < 0f) { a.stuck = true; a.y = 0f }
            for (g in goblins) if (g.alive && abs(a.x - g.x) < 0.35f && abs(a.z - g.z) < 0.35f && a.y < 1.3f) {
                g.alive = false; a.stuck = true; score += if (a.y > 0.95f) 150 else 100
                host.particles.burst(Particles.BLOB, g.x, 0.8f, g.z, 20, 2f, rgb(0.4f, 0.8f, 0.3f), 0.8f, 0.03f)
                host.play(host.sHit, g.x, 0.8f, g.z)
            }
        }
        arrows.removeAll { it.life <= 0f }
        goblins.removeAll { !it.alive }
        if (lives <= 0 && !over) { over = true; status = "Os goblins passaram: $score pontos" }
        else if (!over) status = "Onda $wave · vidas $lives · $score"
    }

    override fun draw() {
        if (host.handPos(0, bow) || host.handPos(2, bow)) {
            // the bow: an arc above and below the hand, the string to the nock
            for (k in -4..4) {
                val a0 = k / 4f * 0.9f; val a1 = (k + 1) / 4f * 0.9f
                if (k == 4) break
                host.drawBeam(bow.x, bow.y + sin(a0) * 0.45f, bow.z + (1f - cos(a0)) * 0.15f, bow.x, bow.y + sin(a1) * 0.45f, bow.z + (1f - cos(a1)) * 0.15f, 0.015f, rgb(0.5f, 0.3f, 0.15f), unlit = false)
            }
            val sx = if (drawing) nock.x else bow.x; val sy = if (drawing) nock.y else bow.y; val sz = if (drawing) nock.z else bow.z + 0.12f
            host.drawBeam(bow.x, bow.y + 0.4f, bow.z + 0.08f, sx, sy, sz, 0.002f, rgb(0.9f, 0.9f, 0.9f))
            host.drawBeam(bow.x, bow.y - 0.4f, bow.z + 0.08f, sx, sy, sz, 0.002f, rgb(0.9f, 0.9f, 0.9f))
        }
        for (a in arrows) {
            val l = sqrt(a.vx * a.vx + a.vy * a.vy + a.vz * a.vz).coerceAtLeast(0.1f)
            host.drawBeam(a.x, a.y, a.z, a.x - a.vx / l * 0.6f, a.y - a.vy / l * 0.6f, a.z - a.vz / l * 0.6f, 0.008f, rgb(0.6f, 0.45f, 0.3f), unlit = false)
        }
        for (g in goblins) {
            val bob = abs(sin(g.t * 8f)) * 0.08f
            host.drawEllipsoid(g.x, 0.55f + bob, g.z, 0.4f, 0.55f, 0.35f, rgb(0.35f, 0.6f, 0.25f), gloss = 0.2f)
            host.drawSphere(g.x, 1.05f + bob, g.z, 0.2f, rgb(0.4f, 0.65f, 0.3f), gloss = 0.2f)
        }
        for (i in 0 until 6) host.drawModel("survival/tree", sin(i * 1.1f) * 14f, 0f, -cos(i * 1.1f) * 14f - 4f, scale = 3f)
    }
}

/** Estilingue: pinch the pouch, pull back and let go to knock over the towers with the critters on them. */
class SlingshotGame(host: GameHost) : QuestGame(host) {
    override val title = "Estilingue"
    override val look = SUNNY
    private val fork = Vec3(0f, 1.15f, -0.45f)
    private val pouch = Vec3()
    private var aiming = -1
    private val stone = Prop(0f, 0f, 0f, 0.05f).apply { alive = false }
    private val blocks = ArrayList<Prop>()
    private val critters = ArrayList<Prop>()
    private var shots = 8

    override fun start() {
        // three towers of crates with a critter on each
        for ((tx, tz) in listOf(-2.5f to -9f, 0f to -11f, 2.8f to -10f)) {
            for (k in 0 until 4) blocks += Prop(tx, 0.25f + k * 0.5f, tz, 0.25f)
            critters += Prop(tx, 2.25f, tz, 0.18f)
        }
        status = "Faça a pinça na pedra entre os braços do estilingue, puxe e solte. $shots pedras"
    }

    override fun update(dt: Float) {
        val p = Vec3()
        for (i in 0..1) {
            val g = host.hands[i]
            if (!g.usable) continue
            if (aiming < 0 && g.pinch && dist(g.tip.x, g.tip.y, g.tip.z, fork.x, fork.y, fork.z) < 0.15f && shots > 0) aiming = i
            if (aiming == i) {
                pouch.set(g.tip)
                if (!g.pinch) {
                    aiming = -1
                    val pull = dist(pouch.x, pouch.y, pouch.z, fork.x, fork.y, fork.z)
                    if (pull > 0.08f) {
                        val k = 22f * pull + 4f
                        stone.alive = true; stone.resting = false; stone.x = fork.x; stone.y = fork.y; stone.z = fork.z
                        stone.vx = (fork.x - pouch.x) / pull * k; stone.vy = (fork.y - pouch.y) / pull * k + 2f; stone.vz = (fork.z - pouch.z) / pull * k
                        shots--
                        host.play(host.sShoot, fork.x, fork.y, fork.z, 0.6f, 0.5f)
                    }
                }
            }
        }
        if (host.pad.triggerDown && shots > 0 && !stone.alive) {
            val d = host.pad.aim.direction
            stone.alive = true; stone.resting = false; stone.x = fork.x; stone.y = fork.y; stone.z = fork.z
            stone.vx = d.x * 14f; stone.vy = d.y * 14f + 2f; stone.vz = d.z * 14f; shots--
        }
        if (stone.alive) {
            stone.physics(dt)
            for (b in blocks + critters) if (b.alive && dist(stone.x, stone.y, stone.z, b.x, b.y, b.z) < stone.r + b.r) {
                b.vx += stone.vx * 0.5f; b.vy += 2f; b.vz += stone.vz * 0.5f; b.resting = false
                stone.vx *= 0.4f; stone.vz *= 0.4f
                host.play(host.sPunch, b.x, b.y, b.z, 0.7f)
            }
            if (stone.resting || stone.z < -20f) stone.alive = false
        }
        // blocks fall, knock each other; critters that touch the ground are out
        for (b in blocks) b.physics(dt, bounce = 0.1f)
        for (c in critters) if (c.alive) {
            c.physics(dt, bounce = 0.1f)
            val support = blocks.any { abs(it.x - c.x) < 0.3f && abs(it.z - c.z) < 0.3f && it.y + it.r < c.y && c.y - it.y < 0.6f }
            if (!support && c.vy == 0f && c.y < 0.3f) { c.alive = false; score += 300; host.play(host.sWin, c.x, c.y, c.z, 0.5f, 1.5f); host.particles.burst(Particles.SPARK, c.x, 0.3f, c.z, 25, 2f, rgb(1f, 0.8f, 0.2f), 0.8f, 0.01f) }
            else if (!support && c.resting) c.resting = false
        }
        for (b in blocks) if (!b.resting) for (o in blocks) if (o !== b && o.resting && dist(b.x, b.y, b.z, o.x, o.y, o.z) < b.r + o.r && b.speed() > 1f) { o.resting = false; o.vx += b.vx * 0.3f; o.vz += b.vz * 0.3f }
        val left = critters.count { it.alive }
        if (left == 0 && !over) { over = true; score += shots * 150; status = "Todos derrubados! Sobraram $shots pedras" }
        else if (shots == 0 && !stone.alive && !over && blocks.all { it.resting }) { over = true; status = "Acabaram as pedras: faltaram $left" }
        else if (!over) status = "Pedras $shots · bichinhos de pé $left"
    }

    override fun draw() {
        host.drawBeam(fork.x, 0.8f, fork.z, fork.x, fork.y - 0.1f, fork.z, 0.03f, rgb(0.5f, 0.32f, 0.18f), unlit = false)
        host.drawBeam(fork.x, fork.y - 0.1f, fork.z, fork.x - 0.12f, fork.y + 0.05f, fork.z, 0.025f, rgb(0.5f, 0.32f, 0.18f), unlit = false)
        host.drawBeam(fork.x, fork.y - 0.1f, fork.z, fork.x + 0.12f, fork.y + 0.05f, fork.z, 0.025f, rgb(0.5f, 0.32f, 0.18f), unlit = false)
        val px = if (aiming >= 0) pouch else Vec3(fork.x, fork.y + 0.02f, fork.z + 0.02f)
        host.drawBeam(fork.x - 0.12f, fork.y + 0.05f, fork.z, px.x, px.y, px.z, 0.006f, rgb(0.8f, 0.3f, 0.2f))
        host.drawBeam(fork.x + 0.12f, fork.y + 0.05f, fork.z, px.x, px.y, px.z, 0.006f, rgb(0.8f, 0.3f, 0.2f))
        if (aiming >= 0) host.drawSphere(px.x, px.y, px.z, 0.05f, rgb(0.5f, 0.5f, 0.52f))
        if (stone.alive) host.drawSphere(stone.x, stone.y, stone.z, stone.r, rgb(0.5f, 0.5f, 0.52f))
        for (b in blocks) host.drawModel("platformer/crate", b.x, b.y - b.r, b.z, scale = 0.5f)
        for (c in critters) if (c.alive) {
            host.drawSphere(c.x, c.y, c.z, c.r, rgb(0.95f, 0.75f, 0.2f), gloss = 0.4f)
            host.drawSphere(c.x - 0.06f, c.y + 0.05f, c.z + 0.15f, 0.03f, rgb(0.05f, 0.05f, 0.05f), shadow = false)
            host.drawSphere(c.x + 0.06f, c.y + 0.05f, c.z + 0.15f, 0.03f, rgb(0.05f, 0.05f, 0.05f), shadow = false)
        }
    }
}

/** Basquete: grab a ball from the rack and shoot it through the hoop; the hoop moves later on. */
class BasketballGame(host: GameHost) : QuestGame(host) {
    override val title = "Basquete"
    override val look = GameSky.Look(zenith = rgb(0.55f, 0.6f, 0.7f), horizon = rgb(0.8f, 0.78f, 0.75f), ground = rgb(0.6f, 0.45f, 0.3f),
        sunDir = floatArrayOf(0.2f, 0.9f, 0.3f), floor = GameSky.FLOOR_PLAIN, floorColor = rgb(0.72f, 0.5f, 0.3f), fog = 0.01f)
    private val balls = List(4) { Prop(-0.5f + it * 0.33f, 0.95f, -0.45f, 0.12f) }
    private val grab = Grabber(host, reach = 0.18f, throwBoost = 1.4f)
    private val hoop = floatArrayOf(0f, 3.05f, -4.2f)
    private var time = 60f
    private val scored = HashSet<Prop>()

    override fun start() { status = "Pegue uma bola (feche a mão), arremesse na cesta. 60 segundos!" }

    override fun update(dt: Float) {
        time -= dt
        hoop[0] = if (time < 30f) sin(host.time * 0.8f) * 1.2f else 0f
        grab.update(balls, onThrow = { b, _ -> scored.remove(b) })
        for (b in balls) {
            val was = b.y
            b.physics(dt, bounce = 0.6f)
            // backboard
            if (b.z < hoop[2] - 0.45f && b.y > 2.6f && b.y < 3.9f && abs(b.x - hoop[0]) < 0.9f) { b.vz = abs(b.vz) * 0.6f; b.z = hoop[2] - 0.45f }
            // through the ring, going down
            if (b !in scored && was > hoop[1] && b.y <= hoop[1] && dist(b.x, hoop[1], b.z, hoop[0], hoop[1], hoop[2]) < 0.23f) {
                scored += b; score += if (dist(b.home[0], 0f, b.home[2], hoop[0], 0f, hoop[2]) > 4f) 3 else 2
                host.play(host.sCoin, hoop[0], hoop[1], hoop[2]); host.particles.burst(Particles.SPARK, hoop[0], hoop[1], hoop[2], 30, 2f, rgb(1f, 0.6f, 0.2f), 0.6f, 0.01f)
            }
            if (b.resting && b.held < 0 && dist(b.x, 0f, b.z, 0f, 0f, 0f) > 1.2f) b.reset()
        }
        if (time <= 0f && !over) { over = true; status = "Tempo! $score pontos" } else if (!over) status = "${time.toInt()} s · $score pontos"
    }

    override fun draw() {
        host.drawBox(0f, 0.85f, -0.45f, 1.4f, 0.04f, 0.3f, rgb(0.3f, 0.3f, 0.33f), metal = 0.6f)
        host.drawBox(hoop[0], 3.4f, hoop[2] - 0.5f, 1.8f, 1.05f, 0.05f, rgb(0.95f, 0.95f, 0.97f), gloss = 0.6f)
        host.drawBox(hoop[0], 3.3f, hoop[2] - 0.47f, 0.6f, 0.45f, 0.01f, rgb(0.9f, 0.3f, 0.2f), unlit = true)
        host.drawTorus(hoop[0], hoop[1], hoop[2], 0.23f, rgb(1f, 0.4f, 0.1f), thick = 0.15f)
        host.drawCylinder(hoop[0], 1.5f, hoop[2] - 0.7f, 0.07f, 3f, rgb(0.3f, 0.3f, 0.33f), metal = 0.7f)
        for (b in balls) host.drawSphere(b.x, b.y, b.z, b.r, rgb(0.95f, 0.45f, 0.12f), gloss = 0.3f)
    }
}

/** Boliche: pick up the ball, swing and let go low to roll it down the lane at the ten pins. */
class BowlingGame(host: GameHost) : QuestGame(host) {
    override val title = "Boliche"
    override val look = GameSky.Look(zenith = rgb(0.12f, 0.1f, 0.2f), horizon = rgb(0.3f, 0.2f, 0.45f), ground = rgb(0.1f, 0.08f, 0.15f),
        floor = GameSky.FLOOR_PLAIN, floorColor = rgb(0.12f, 0.1f, 0.18f), fog = 0.01f, light = rgb(0.9f, 0.85f, 1f), ambient = rgb(0.35f, 0.3f, 0.45f))
    private val ball = Prop(0.3f, 0.9f, -0.35f, 0.11f)
    private val grab = Grabber(host, reach = 0.18f, throwBoost = 1.5f)
    private val pins = ArrayList<Prop>()
    private var frame = 1; private var roll = 0; private var settle = 0f; private var rolling = false
    private val laneZ = -14f

    override fun start() { setPins(); status = "Pegue a bola, balance o braço e solte perto do chão" }

    private fun setPins() {
        pins.clear()
        var k = 0
        for (row in 0 until 4) for (c in 0..row) { pins += Prop((c - row / 2f) * 0.3f, 0.19f, laneZ - row * 0.26f, 0.06f).also { it.tag = k++ } }
    }

    override fun update(dt: Float) {
        grab.update(listOf(ball), onThrow = { _, _ -> rolling = true; ball.vz = min(ball.vz, -2f) })
        if (ball.held < 0) {
            ball.physics(dt, bounce = 0.1f, drag = 0.999f)
            ball.x = ball.x.coerceIn(-0.55f, 0.55f)
        }
        for (p in pins) {
            if (p.tag >= 100) { p.physics(dt, bounce = 0.2f); continue }
            if (dist(ball.x, ball.y, ball.z, p.x, p.y, p.z) < ball.r + 0.1f) knock(p, ball.vx + (p.x - ball.x) * 4f, ball.vz * 0.6f)
        }
        for (p in pins) if (p.tag >= 100 && p.speed() > 1f) for (o in pins) if (o.tag < 100 && dist(p.x, p.y, p.z, o.x, o.y, o.z) < 0.16f) knock(o, p.vx, p.vz)
        if (rolling && (ball.z < laneZ - 1.2f || ball.resting)) { settle += dt; if (settle > 2f) endRoll() }
    }

    private fun knock(p: Prop, vx: Float, vz: Float) {
        if (p.tag >= 100) return
        p.tag += 100; p.vx = vx; p.vz = vz; p.vy = 1.5f; p.resting = false
        host.play(host.sPunch, p.x, p.y, p.z, 0.5f, 1.4f)
    }

    private fun endRoll() {
        settle = 0f; rolling = false
        val down = pins.count { it.tag >= 100 }
        score += down
        roll++
        if (down == 10 || roll == 2) {
            if (down == 10) { score += if (roll == 1) 10 else 5; host.play(host.sWin, 0f, 1f, laneZ) }
            frame++; roll = 0; setPins()
        } else pins.removeAll { it.tag >= 100 }
        ball.reset()
        if (frame > 10 && !over) { over = true; status = "Jogo completo: $score pontos" }
        else if (!over) status = "Quadro $frame · bola ${roll + 1} · $score pontos" + if (down == 10) " · STRIKE!" else ""
    }

    override fun draw() {
        host.drawBox(0f, -0.01f, laneZ / 2f, 1.2f, 0.02f, -laneZ + 2f, rgb(0.85f, 0.65f, 0.4f), gloss = 0.8f, tex = "wood", tile = 2f)
        for (s in listOf(-1f, 1f)) host.drawBox(s * 0.68f, 0.03f, laneZ / 2f, 0.15f, 0.06f, -laneZ + 2f, rgb(0.15f, 0.15f, 0.18f))
        for (k in 0 until 8) host.glow(0f, 0.01f, -2f - k * 1.5f, 0.3f, rgb(0.4f, 0.6f, 1f), 0.2f)
        host.drawBox(0f, 0.8f, -0.35f, 0.5f, 0.04f, 0.3f, rgb(0.25f, 0.25f, 0.3f), metal = 0.5f)
        host.drawSphere(ball.x, ball.y, ball.z, ball.r, rgb(0.2f, 0.3f, 0.85f), gloss = 0.9f)
        for (p in pins) {
            val tilt = if (p.tag >= 100) 1.3f else 0f
            host.drawEllipsoid(p.x, p.y, p.z, 0.11f, 0.38f, 0.11f, rgb(0.97f, 0.97f, 0.97f), orientation = com.hyperxr.math.Quat.fromAxisAngle(1f, 0f, 0f, tilt), gloss = 0.7f)
        }
    }
}

/** Dardos: pinch a dart from the tray and throw it at the board; 301 down to zero. */
class DartsGame(host: GameHost) : QuestGame(host) {
    override val title = "Dardos"
    override val look = GameSky.Look(zenith = rgb(0.25f, 0.18f, 0.12f), horizon = rgb(0.4f, 0.3f, 0.2f), ground = rgb(0.2f, 0.14f, 0.1f),
        floor = GameSky.FLOOR_PLAIN, floorColor = rgb(0.3f, 0.2f, 0.12f), fog = 0.02f, light = rgb(1f, 0.85f, 0.65f), ambient = rgb(0.4f, 0.32f, 0.25f))
    private val darts = List(3) { Prop(-0.15f + it * 0.15f, 1.0f, -0.4f, 0.04f) }
    private val grab = Grabber(host, reach = 0.12f, throwBoost = 1.6f)
    private val board = floatArrayOf(0f, 1.73f, -2.37f)
    private var left = 301
    private var thrown = 0

    override fun start() { status = "Pegue um dardo com a pinça e arremesse no alvo. Chegue a zero, começando de 301" }

    override fun update(dt: Float) {
        grab.update(darts)
        for (d in darts) {
            if (d.held >= 0 || d.resting) continue
            if (d.tag == 1) continue
            d.vy -= 9.81f * dt; d.x += d.vx * dt; d.y += d.vy * dt; d.z += d.vz * dt
            if (d.z <= board[2] + 0.02f && abs(d.x - board[0]) < 0.4f && abs(d.y - board[1]) < 0.4f) {
                d.z = board[2] + 0.02f; d.tag = 1; d.resting = true
                val r = dist(d.x, d.y, 0f, board[0], board[1], 0f)
                val pts = when { r < 0.016f -> 50; r < 0.04f -> 25; r < 0.1f -> 20; r < 0.17f -> 10; r < 0.22f -> 5; else -> 1 }
                if (left - pts >= 0) left -= pts
                score = 301 - left; thrown++
                host.play(host.sTick, d.x, d.y, d.z, 0.8f, 1.2f)
                status = "$pts pontos! Faltam $left"
            } else if (d.y < 0f || d.z < board[2] - 0.5f) { d.resting = true; thrown++ }
        }
        if (darts.all { it.resting && it.held < 0 }) {
            // three darts thrown: collect them back
            if (thrown >= 3) { thrown = 0; darts.forEach { it.reset(); it.tag = 0 } }
        }
        if (left == 0 && !over) { over = true; status = "Zerado! Parabéns" }
    }

    override fun draw() {
        host.drawBox(0f, 1.6f, board[2] - 0.05f, 2.4f, 3.2f, 0.05f, rgb(0.35f, 0.24f, 0.16f), tex = "wood", tile = 1f)
        val rings = listOf(0.22f to rgb(0.1f, 0.1f, 0.1f), 0.17f to rgb(0.85f, 0.15f, 0.15f), 0.1f to rgb(0.95f, 0.9f, 0.75f), 0.04f to rgb(0.15f, 0.6f, 0.2f), 0.016f to rgb(0.85f, 0.15f, 0.15f))
        for ((k, rc) in rings.withIndex()) host.drawCylinder(board[0], board[1], board[2] + 0.005f * k, rc.first, 0.01f, rc.second, orientation = com.hyperxr.math.Quat.fromAxisAngle(1f, 0f, 0f, 1.5708f))
        host.drawBox(0f, 0.95f, -0.4f, 0.5f, 0.03f, 0.15f, rgb(0.2f, 0.2f, 0.22f))
        for (d in darts) {
            val vz = if (d.held >= 0 || d.tag == 1) -1f else d.vz
            host.drawBeam(d.x, d.y, d.z, d.x, d.y, d.z - vz.coerceIn(-1f, 1f) * -0.12f, 0.005f, rgb(0.7f, 0.7f, 0.75f), unlit = false)
            host.drawBox(d.x, d.y, d.z + 0.1f, 0.03f, 0.03f, 0.02f, rgb(0.9f, 0.2f, 0.3f))
        }
    }
}

/** Queimada: robots throw balls at you; dodge with your body, catch with a closed hand and throw back. */
class DodgeballGame(host: GameHost) : QuestGame(host) {
    override val title = "Queimada"
    override val look = SUNNY
    private class Bot(var x: Float, val z: Float) { var cool = 2f; var out = false; var t = 0f }
    private val bots = List(4) { Bot(-2.4f + it * 1.6f, -7f) }
    private val balls = ArrayList<Prop>()
    private val grab = Grabber(host, reach = 0.2f, throwBoost = 1.5f)
    private var lives = 3

    override fun start() { status = "Desvie das bolas; feche a mão para pegar uma e jogue de volta nos robôs" }

    override fun update(dt: Float) {
        for (b in bots) if (!b.out) {
            b.t += dt; b.x += sin(b.t * 1.3f + b.z) * dt * 0.8f
            b.cool -= dt
            if (b.cool <= 0f) {
                b.cool = 2.5f + Math.random().toFloat() * 2f
                val tx = host.head.x; val ty = host.head.y - 0.3f; val tz = host.head.z
                val dx = tx - b.x; val dz = tz - b.z; val d = sqrt(dx * dx + dz * dz)
                val t = d / 6f
                balls += Prop(b.x, 1.2f, b.z, 0.11f).also { it.vx = dx / t; it.vz = dz / t; it.vy = (ty - 1.2f) / t + 4.9f * t; it.tag = 1 }
            }
        }
        grab.update(balls, onGrab = { p, _ -> p.tag = 0; score += 20 }, onThrow = { p, _ -> p.tag = 2 })
        for (p in balls) {
            p.physics(dt, bounce = 0.5f)
            if (p.tag == 1 && dist(p.x, p.y, p.z, host.head.x, host.head.y - 0.35f, host.head.z) < 0.35f) { p.tag = 0; lives--; host.play(host.sMiss, host.head.x, host.head.y, host.head.z) }
            if (p.tag == 2) for (b in bots) if (!b.out && dist(p.x, p.y, p.z, b.x, 1f, b.z) < 0.5f) { b.out = true; p.tag = 0; score += 200; host.play(host.sBoom, b.x, 1f, b.z, 0.5f, 1.5f); host.particles.burst(Particles.DEBRIS, b.x, 1f, b.z, 20, 2f, rgb(0.7f, 0.7f, 0.75f), 1f, 0.03f) }
            if (p.resting && p.tag != 0) p.tag = 0
        }
        balls.removeAll { it.resting && it.held < 0 && it.z < -3f }
        if (lives <= 0 && !over) { over = true; status = "Você foi queimado! $score pontos" }
        else if (bots.all { it.out } && !over) { over = true; score += lives * 200; status = "Venceu! $score pontos" }
        else if (!over) status = "Vidas $lives · robôs ${bots.count { !it.out }}"
    }

    override fun draw() {
        host.drawBox(0f, 0.005f, -4f, 6f, 0.01f, 9f, rgb(0.5f, 0.6f, 0.75f), tex = "concrete", tile = 1f)
        host.drawBox(0f, 0.012f, -3.5f, 6f, 0.01f, 0.06f, rgb(1f, 1f, 1f), unlit = true)
        for (b in bots) if (!b.out) {
            host.drawRounded(b.x, 0.9f, b.z, 0.5f, 0.9f, 0.35f, rgb(0.85f, 0.85f, 0.9f), gloss = 0.7f, metal = 0.4f)
            host.drawSphere(b.x, 1.55f, b.z, 0.2f, rgb(0.3f, 0.3f, 0.35f), gloss = 0.8f)
            host.drawSphere(b.x, 1.58f, b.z + 0.17f, 0.05f, rgb(0.2f, 0.9f, 1f), unlit = true)
        }
        for (p in balls) host.drawSphere(p.x, p.y, p.z, p.r, if (p.tag == 1) rgb(1f, 0.3f, 0.3f) else rgb(0.95f, 0.85f, 0.2f), gloss = 0.4f)
    }
}
