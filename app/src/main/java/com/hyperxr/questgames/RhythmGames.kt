package com.hyperxr.questgames

import com.hyperxr.math.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Rhythm and fitness games. Each is HyperMagic's own take on a Quest mechanic (named in the
// catalog as its inspiration); art, rules and code are original.

/** Ritmo Neon: you glide down a neon corridor; shoot the figures on the beat, lean away from shots. */
class NeonRailGame(host: GameHost) : QuestGame(host) {
    override val title = "Ritmo Neon"
    override val look = GameSky.Look(
        zenith = rgb(0.02f, 0f, 0.06f), horizon = rgb(0.35f, 0.05f, 0.4f), ground = rgb(0.02f, 0f, 0.05f),
        sunDir = floatArrayOf(0f, 0.15f, -1f), sunColor = rgb(1f, 0.3f, 0.7f), sunSize = 0.15f, stars = 0.8f,
        floor = GameSky.FLOOR_GRID, floorColor = rgb(0.02f, 0f, 0.04f), grid = floatArrayOf(0.9f, 0.2f, 0.9f, 2f),
        fog = 0.03f, objectFog = 0.02f, light = rgb(0.8f, 0.7f, 1f), ambient = rgb(0.3f, 0.25f, 0.4f),
    )
    override val shadowRadius = 0f
    private class Foe(var z: Float, val x: Float, val y: Float) { var alive = true; var fired = false; var hitAt = 0f }
    private val foes = ArrayList<Foe>()
    private val shots = ArrayList<Bolt>()
    private val clock = BeatClock(110f)
    private val shooter = Shooter(host)
    private var travel = 0f
    private var combo = 0
    private var lives = 3
    private val length = 420f

    override fun start() { status = "Atire nos inimigos (pinça apontando o dedo ou gatilho) no ritmo; incline o corpo para desviar dos tiros" }

    override fun update(dt: Float) {
        clock.step(dt)
        travel += dt * 4f
        // a new wave every two beats
        if (clock.onBeat && clock.beat % 2 == 0 && travel < length - 30f) {
            val n = 1 + (clock.beat / 24).coerceAtMost(2)
            repeat(n) { k -> foes += Foe(travel + 22f + k * 2f, (k - (n - 1) / 2f) * 1.6f + sin(clock.beat * 0.7f) * 0.8f, 1.2f + (clock.beat % 3) * 0.3f) }
        }
        shooter.update { o, d, _ ->
            host.play(host.sShoot, o.x, o.y, o.z, 0.5f, 1.4f)
            var best = 40f; var hit: Foe? = null
            for (f in foes) if (f.alive) { val t = rayHit(o, d, f.x, f.y, -(f.z - travel), 0.45f); if (t in 0f..best) { best = t; hit = f } }
            hit?.let { f ->
                f.alive = false; f.hitAt = host.time
                val onBeat = clock.offBeat() < 0.15f
                combo = if (onBeat) combo + 1 else 0
                score += if (onBeat) 100 + combo * 10 else 50
                host.particles.burst(Particles.SPARK, f.x, f.y, -(f.z - travel), 30, 3f, rgb(0.3f, 1f, 1f), 0.6f, 0.01f, gravity = 0f)
                host.play(host.sHit, f.x, f.y, -(f.z - travel), 0.6f, if (onBeat) 1.5f else 1f)
            }
        }
        for (f in foes) {
            val z = -(f.z - travel)
            if (f.alive && !f.fired && z > -9f) {
                f.fired = true
                val dx = host.head.x - f.x; val dy = host.head.y - f.y; val dz = host.head.z - z
                val l = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                shots += Bolt(f.x, f.y, z, dx / l * 4f, dy / l * 4f, dz / l * 4f, false)
            }
        }
        for (b in shots) {
            b.step(dt)
            if (dist(b.x, b.y, b.z, host.head.x, host.head.y, host.head.z) < 0.18f) { b.life = 0f; lives--; combo = 0; host.play(host.sMiss, host.head.x, host.head.y, host.head.z) }
        }
        shots.removeAll { it.life <= 0f || it.z > 2f }
        foes.removeAll { (!it.alive && host.time - it.hitAt > 0.3f) || -(it.z - travel) > 2f }
        if (lives <= 0 && !over) { over = true; status = "Fim: $score pontos" }
        else if (travel >= length && !over) { over = true; score += lives * 300; status = "Fase completa! $score pontos" }
        else if (!over) status = "Combo $combo · vidas $lives · ${(travel / length * 100).toInt()}%"
    }

    override fun draw() {
        // the corridor: neon frames passing by
        val first = (travel / 4f).toInt()
        for (k in 0 until 16) {
            val z = -((first + k) * 4f - travel)
            val pulse = 0.5f + 0.5f * max(0f, 1f - clock.offBeat() * 6f)
            val c = if ((first + k) % 2 == 0) rgb(1f, 0.2f, 0.8f) else rgb(0.2f, 0.9f, 1f)
            host.glowBeam(-3f, 0f, z, -3f, 4f, z, 0.04f, c, 0.4f + pulse * 0.4f)
            host.glowBeam(3f, 0f, z, 3f, 4f, z, 0.04f, c, 0.4f + pulse * 0.4f)
            host.glowBeam(-3f, 4f, z, 3f, 4f, z, 0.04f, c, 0.4f + pulse * 0.4f)
        }
        for (f in foes) {
            val z = -(f.z - travel)
            if (!f.alive) continue
            host.drawEllipsoid(f.x, f.y - 0.3f, z, 0.35f, 0.6f, 0.25f, rgb(0.95f, 0.2f, 0.5f), gloss = 0.9f)
            host.drawSphere(f.x, f.y + 0.25f, z, 0.16f, rgb(0.95f, 0.2f, 0.5f), gloss = 0.9f)
            host.glow(f.x, f.y, z, 0.6f, rgb(1f, 0.2f, 0.6f), 0.3f)
        }
        for (b in shots) { host.drawSphere(b.x, b.y, b.z, 0.05f, rgb(1f, 0.9f, 0.3f), unlit = true, shadow = false); host.glow(b.x, b.y, b.z, 0.15f, rgb(1f, 0.8f, 0.2f), 0.8f) }
    }
}

/** Orbes: coloured orbs come along trails; meet the left (blue) and right (pink) ones with that hand. */
class OrbsGame(host: GameHost) : QuestGame(host) {
    override val title = "Orbes"
    override val look = GameSky.Look(
        zenith = rgb(0.01f, 0.02f, 0.08f), horizon = rgb(0.1f, 0.05f, 0.25f), ground = rgb(0f, 0f, 0.03f),
        stars = 1f, nebula = floatArrayOf(0.2f, 0.1f, 0.5f, 0.8f), floor = GameSky.FLOOR_GRID, floorColor = rgb(0f, 0f, 0.03f),
        grid = floatArrayOf(0.3f, 0.4f, 1f, 1.5f), fog = 0.02f, light = rgb(0.8f, 0.8f, 1f), ambient = rgb(0.3f, 0.3f, 0.45f),
    )
    override val shadowRadius = 0f
    private class Orb(val hand: Int, val x: Float, val y: Float, var z: Float) { var done = false }
    private val orbs = ArrayList<Orb>()
    private val clock = BeatClock(100f)
    private var combo = 0; private var misses = 0; private var hits = 0
    private val p = Vec3()

    override fun start() { status = "Encoste nos orbes: azuis com a mão esquerda, rosa com a direita. Siga as trilhas" }

    override fun update(dt: Float) {
        clock.step(dt)
        if (clock.onBeat && clock.beat < 160) {
            val b = clock.beat
            val hand = if ((b / 2) % 2 == 0) 0 else 1
            val x = (if (hand == 0) -0.3f else 0.3f) + sin(b * 0.6f) * 0.25f
            val y = 1.2f + cos(b * 0.45f) * 0.3f
            orbs += Orb(hand, x, y, -12f)
        }
        for (o in orbs) {
            if (o.done) continue
            o.z += dt * 6f
            if (o.z > -0.6f && o.z < 0.3f) {
                val i = o.hand
                val ok = host.handPos(i, p) || (i == 1 && host.handPos(2, p))
                if (ok && dist(p.x, p.y, p.z, o.x, o.y, o.z) < 0.18f) {
                    o.done = true; hits++; combo++; score += 50 + combo * 5
                    host.particles.burst(Particles.SPARK, o.x, o.y, o.z, 20, 2f, if (i == 0) BLUE else PINK, 0.5f, 0.008f, gravity = 0f)
                    host.play(host.sTick, o.x, o.y, o.z, 0.7f, if (i == 0) 1f else 1.3f)
                }
            }
            if (o.z > 0.4f && !o.done) { o.done = true; misses++; combo = 0 }
        }
        orbs.removeAll { it.done && it.z > 0.5f || it.z > 1f }
        if (clock.beat >= 170 && !over) { over = true; status = "Fim! $hits acertos, $misses perdidos" }
        else if (!over) status = "Combo $combo · acertos $hits · perdidos $misses"
    }

    override fun draw() {
        for ((k, o) in orbs.withIndex()) {
            if (o.done) continue
            val c = if (o.hand == 0) BLUE else PINK
            host.drawSphere(o.x, o.y, o.z, 0.09f, c, gloss = 0.9f, shadow = false)
            host.glow(o.x, o.y, o.z, 0.25f, c, 0.6f)
            // trail to the next orb of the same hand
            val next = orbs.drop(k + 1).firstOrNull { it.hand == o.hand && !it.done }
            if (next != null && next.z - o.z > -3f) host.glowBeam(o.x, o.y, o.z, next.x, next.y, next.z, 0.015f, c, 0.4f)
        }
    }

    companion object { val BLUE = rgb(0.2f, 0.6f, 1f); val PINK = rgb(1f, 0.3f, 0.7f) }
}

/** Formas na Parede: walls with a shape cut out come at you; fit your head and hands through, punch the blocks. */
class ShapeWallGame(host: GameHost) : QuestGame(host) {
    override val title = "Formas na Parede"
    override val look = GameSky.Look(
        zenith = rgb(0.4f, 0.75f, 1f), horizon = rgb(0.95f, 0.85f, 0.7f), ground = rgb(0.9f, 0.8f, 0.6f),
        sunDir = floatArrayOf(0.3f, 0.7f, 0.5f), sunColor = rgb(1f, 0.95f, 0.8f), clouds = 0.5f,
        floor = GameSky.FLOOR_PLAIN, floorColor = rgb(0.95f, 0.85f, 0.65f), fog = 0.01f,
    )
    override val shadowRadius = 3f
    /** Pose holes: head (x,y) and two hands (x,y), radius 0.22; or a block to punch (kind 1). */
    private class Wall(var z: Float, val kind: Int, val head: FloatArray, val hl: FloatArray, val hr: FloatArray) { var judged = false; var ok = false }
    private val walls = ArrayList<Wall>()
    private var t = 0f
    private var next = 2f
    private var lives = 5
    private var headH = 1.6f
    private val p = Vec3()

    override fun start() { status = "Encaixe a cabeça e as mãos nos buracos da parede; blocos laranja: soque!" }

    override fun update(dt: Float) {
        t += dt
        if (t < 1f) headH = host.head.y
        next -= dt
        if (next <= 0f && t < 120f) {
            next = (2.6f - t / 80f).coerceAtLeast(1.4f)
            val r = (t * 13).toInt()
            walls += when (r % 4) {
                0 -> Wall(-14f, 0, floatArrayOf(0f, headH), floatArrayOf(-0.55f, headH + 0.1f), floatArrayOf(0.55f, headH + 0.1f))
                1 -> Wall(-14f, 0, floatArrayOf(0.35f, headH - 0.45f), floatArrayOf(-0.1f, headH - 0.6f), floatArrayOf(0.7f, headH - 0.2f))
                2 -> Wall(-14f, 0, floatArrayOf(-0.35f, headH - 0.2f), floatArrayOf(-0.6f, headH + 0.4f), floatArrayOf(0.1f, headH + 0.4f))
                else -> Wall(-14f, 1, floatArrayOf(0f, headH - 0.35f), floatArrayOf(-0.25f, headH - 0.35f), floatArrayOf(0.25f, headH - 0.35f))
            }
        }
        for (w in walls) {
            w.z += dt * 4.5f
            if (!w.judged && w.z > -0.05f) {
                w.judged = true
                w.ok = if (w.kind == 1) {
                    // a punch: a fast hand near the block in the last moment
                    (0..2).any { i -> host.handPos(i, p) && host.handSpeed(i) > 1.4f && abs(p.x - w.head[0]) < 0.5f }
                } else {
                    val headOk = abs(host.head.x - w.head[0]) < 0.24f && abs(host.head.y - w.head[1]) < 0.24f
                    val lOk = !host.handPos(0, p) || (abs(p.x - w.hl[0]) < 0.25f && abs(p.y - w.hl[1]) < 0.25f)
                    val rOk = !host.handPos(1, p) || (abs(p.x - w.hr[0]) < 0.25f && abs(p.y - w.hr[1]) < 0.25f)
                    headOk && lOk && rOk
                }
                if (w.ok) { score += 100; host.play(host.sCoin, 0f, w.head[1], 0f) } else { lives--; host.play(host.sMiss, 0f, w.head[1], 0f) }
            }
        }
        walls.removeAll { it.z > 1.5f }
        if (lives <= 0 && !over) { over = true; status = "Fim: $score pontos" }
        else if (t > 125f && !over) { over = true; status = "Você passou por tudo! $score pontos" }
        else if (!over) status = "Vidas $lives · $score pontos"
    }

    override fun draw() {
        for (w in walls) {
            if (w.kind == 1) {
                host.drawRounded(w.head[0], w.head[1], w.z, 0.5f, 0.5f, 0.3f, if (w.judged && w.ok) rgb(1f, 1f, 1f, 0.3f) else rgb(1f, 0.55f, 0.1f), gloss = 0.6f)
                continue
            }
            // the wall as a grid of panes with the shape left open
            val col = if (w.judged) (if (w.ok) rgb(0.4f, 1f, 0.5f, 0.5f) else rgb(1f, 0.3f, 0.3f, 0.6f)) else rgb(0.35f, 0.55f, 1f, 0.75f)
            var yy = 0.15f
            while (yy < 2.6f) {
                var xx = -1.5f
                while (xx < 1.6f) {
                    val open = listOf(w.head, w.hl, w.hr).any { abs(it[0] - xx) < 0.24f && abs(it[1] - yy) < 0.24f }
                    if (!open) host.drawBox(xx, yy, w.z, 0.19f, 0.19f, 0.08f, col, gloss = 0.7f, shadow = false)
                    xx += 0.2f
                }
                yy += 0.2f
            }
        }
    }
}

/** Tambores do Norte: four drums on a longboat; hit each drum as its rune reaches it. */
class DrumBoatGame(host: GameHost) : QuestGame(host) {
    override val title = "Tambores do Norte"
    override val look = GameSky.Look(
        zenith = rgb(0.25f, 0.35f, 0.55f), horizon = rgb(0.7f, 0.6f, 0.55f), ground = rgb(0.1f, 0.2f, 0.3f),
        sunDir = floatArrayOf(-0.4f, 0.25f, -0.8f), sunColor = rgb(1f, 0.7f, 0.4f), sunSize = 0.04f, clouds = 0.7f,
        floor = GameSky.FLOOR_WATER, floorColor = rgb(0.08f, 0.2f, 0.3f), fog = 0.01f,
    )
    private val drumX = floatArrayOf(-0.6f, -0.2f, 0.2f, 0.6f)
    private val drumY = 0.95f
    private val drumZ = -0.55f
    private class Rune(val lane: Int, var z: Float) { var done = false }
    private val runes = ArrayList<Rune>()
    private val clock = BeatClock(96f)
    private val wasDown = BooleanArray(3)
    private val flash = FloatArray(4)
    private var streak = 0; private var miss = 0
    private val p = Vec3()

    override fun start() { status = "Bata no tambor quando a runa chegar nele; acerte em sequência para o barco remar mais rápido" }

    override fun update(dt: Float) {
        clock.step(dt)
        if (clock.onBeat && clock.beat < 150) {
            val lane = ((clock.beat * 7 + clock.beat / 3) % 4)
            runes += Rune(lane, -10f)
            if (clock.beat % 8 == 4) runes += Rune((lane + 2) % 4, -10f)
        }
        for (r in runes) if (!r.done) r.z += dt * 10f * (60f / clock.bpm) * 1.6f
        for (i in 0 until 4) flash[i] = max(0f, flash[i] - dt * 3f)
        // a hand coming down onto a drum head hits it
        for (i in 0..2) {
            if (!host.handPos(i, p)) { wasDown[i] = false; continue }
            val down = p.y < drumY + 0.05f && p.z > drumZ - 0.25f && p.z < drumZ + 0.25f
            if (down && !wasDown[i]) {
                val lane = drumX.indices.minByOrNull { abs(drumX[it] - p.x) } ?: 0
                if (abs(drumX[lane] - p.x) < 0.2f) hit(lane)
            }
            wasDown[i] = down
        }
        for (r in runes) if (!r.done && r.z > drumZ + 0.4f) { r.done = true; miss++; streak = 0 }
        runes.removeAll { it.done && it.z > 1f || it.z > 2f }
        if (clock.beat > 160 && !over) { over = true; status = "Viagem completa: $score pontos, $miss erros" }
        else if (!over) status = "Sequência $streak · velocidade ${min(5, 1 + streak / 8)} · erros $miss"
    }

    private fun hit(lane: Int) {
        flash[lane] = 1f
        host.play(host.sPunch, drumX[lane], drumY, drumZ, 0.9f, 0.8f + lane * 0.12f)
        val r = runes.filter { !it.done && it.lane == lane }.minByOrNull { abs(it.z - drumZ) }
        if (r != null && abs(r.z - drumZ) < 0.45f) {
            r.done = true; streak++; score += 50 + min(streak, 40) * 5
            host.particles.burst(Particles.SPARK, drumX[lane], drumY + 0.1f, drumZ, 16, 1.5f, rgb(1f, 0.8f, 0.3f), 0.4f, 0.008f, gravity = 2f)
        } else streak = 0
    }

    override fun draw() {
        // the longboat around you, oars moving with the streak
        host.drawBox(0f, 0.25f, -1f, 1.6f, 0.4f, 5f, rgb(0.45f, 0.3f, 0.18f), tex = "wood", tile = 1f)
        val row = host.time * (1f + min(5, streak / 8))
        for (s in listOf(-1f, 1f)) for (k in 0 until 4) {
            val z = -2.5f + k * 1f; val a = sin(row * 2f + k) * 0.5f
            host.drawBeam(s * 0.8f, 0.45f, z, s * 2.2f, 0.1f + a * 0.3f, z + a, 0.025f, rgb(0.6f, 0.45f, 0.3f), unlit = false)
        }
        for (i in 0 until 4) {
            host.drawCylinder(drumX[i], drumY - 0.15f, drumZ, 0.17f, 0.3f, rgb(0.55f, 0.3f, 0.15f), tex = "wood", tile = 0.5f)
            host.drawCylinder(drumX[i], drumY + 0.005f, drumZ, 0.165f, 0.01f, rgb(0.92f, 0.85f, 0.7f), gloss = 0.3f)
            if (flash[i] > 0f) host.glow(drumX[i], drumY, drumZ, 0.3f, rgb(1f, 0.8f, 0.3f), flash[i])
            // the lane the runes slide down
            host.glowBeam(drumX[i], drumY + 0.02f, drumZ - 0.2f, drumX[i], drumY + 0.02f, -10f, 0.006f, rgb(0.4f, 0.7f, 1f), 0.25f)
        }
        for (r in runes) if (!r.done) {
            host.drawRounded(drumX[r.lane], drumY + 0.05f, r.z, 0.12f, 0.03f, 0.12f, rgb(0.3f, 0.8f, 1f), gloss = 0.8f)
            host.glow(drumX[r.lane], drumY + 0.05f, r.z, 0.15f, rgb(0.3f, 0.8f, 1f), 0.6f)
        }
    }
}

/** Treino de Alvos: a workout; punch the targets with the hand of their colour, squat under the bars. */
class FitTargetsGame(host: GameHost) : QuestGame(host) {
    override val title = "Treino de Alvos"
    override val look = GameSky.Look(
        zenith = rgb(0.3f, 0.55f, 0.9f), horizon = rgb(1f, 0.75f, 0.55f), ground = rgb(0.3f, 0.35f, 0.3f),
        sunDir = floatArrayOf(0.2f, 0.2f, -1f), sunColor = rgb(1f, 0.7f, 0.4f), sunSize = 0.05f, clouds = 0.4f,
        floor = GameSky.FLOOR_PLAIN, floorColor = rgb(0.35f, 0.45f, 0.3f), fog = 0.008f,
    )
    private class Item(val kind: Int, val x: Float, val y: Float, var z: Float) { var done = false } // 0 left target, 1 right, 2 bar
    private val items = ArrayList<Item>()
    private val clock = BeatClock(120f)
    private var standH = 1.6f
    private var calories = 0f
    private var lives = 6
    private val p = Vec3()

    override fun start() { status = "Soque os alvos: azul com a mão esquerda, verde com a direita. Barra vermelha: agache!" }

    override fun update(dt: Float) {
        clock.step(dt)
        if (clock.time < 1f) standH = host.head.y
        if (clock.onBeat && clock.beat < 220) {
            val b = clock.beat
            if (b % 16 == 15) items += Item(2, 0f, standH - 0.25f, -12f)
            else if (b % 2 == 0) { val k = (b / 2) % 2; items += Item(k, (if (k == 0) -0.35f else 0.35f) + sin(b * 0.4f) * 0.15f, standH - 0.3f + cos(b * 0.3f) * 0.2f, -12f) }
        }
        calories += dt * (0.08f + host.headSpeed * 0.2f)
        for (it in items) {
            if (it.done) continue
            it.z += dt * 6f
            if (it.kind == 2) {
                if (it.z > -0.1f) { it.done = true; if (host.head.y > it.y - 0.05f) { lives--; host.play(host.sMiss, 0f, it.y, 0f) } else { score += 80; host.play(host.sCoin, 0f, it.y, 0f) } }
            } else if (it.z > -0.7f && it.z < 0.2f) {
                val hand = it.kind
                if ((host.handPos(hand, p) || (hand == 1 && host.handPos(2, p))) && dist(p.x, p.y, p.z, it.x, it.y, it.z) < 0.2f) {
                    it.done = true
                    val power = host.handSpeed(if (host.hands[hand].usable) hand else 2)
                    score += (40 + power * 20).toInt()
                    host.particles.burst(Particles.SPARK, it.x, it.y, it.z, 24, 2f + power, if (hand == 0) BLUE else GREEN, 0.5f, 0.01f)
                    host.play(host.sPunch, it.x, it.y, it.z, 0.8f)
                }
            }
            if (it.z > 0.5f && !it.done) { it.done = true; if (it.kind != 2) lives-- }
        }
        items.removeAll { it.done || it.z > 1f }
        if (lives <= 0 && !over) { over = true; status = "Treino encerrado: $score pontos, ~${calories.toInt()} kcal" }
        else if (clock.beat > 230 && !over) { over = true; status = "Treino completo! $score pontos, ~${calories.toInt()} kcal" }
        else if (!over) status = "Vidas $lives · ~${calories.toInt()} kcal · $score"
    }

    override fun draw() {
        for (it in items) {
            if (it.kind == 2) { host.drawBox(0f, it.y, it.z, 3f, 0.12f, 0.12f, rgb(1f, 0.25f, 0.2f), gloss = 0.7f); continue }
            val c = if (it.kind == 0) BLUE else GREEN
            host.drawTorus(it.x, it.y, it.z, 0.13f, c, orientation = com.hyperxr.math.Quat.fromAxisAngle(1f, 0f, 0f, 1.5708f))
            host.drawSphere(it.x, it.y, it.z, 0.06f, c, gloss = 0.8f)
        }
        host.drawBox(0f, -0.01f, -3f, 4f, 0.02f, 8f, rgb(0.5f, 0.5f, 0.55f), tex = "concrete", tile = 1f)
    }

    companion object { val BLUE = rgb(0.25f, 0.55f, 1f); val GREEN = rgb(0.3f, 0.95f, 0.45f) }
}

/** Raquete Cósmica: a ball in a dome; hit it with your palm to break the glowing panels on the wall. */
class DomeRacketGame(host: GameHost) : QuestGame(host) {
    override val title = "Raquete Cósmica"
    override val look = GameSky.Look(
        zenith = rgb(0.02f, 0.02f, 0.06f), horizon = rgb(0.08f, 0.1f, 0.2f), ground = rgb(0.03f, 0.03f, 0.06f),
        stars = 1f, nebula = floatArrayOf(0.1f, 0.4f, 0.6f, 0.6f), floor = GameSky.FLOOR_GRID, floorColor = rgb(0.02f, 0.02f, 0.05f),
        grid = floatArrayOf(0.2f, 0.7f, 1f, 1f), fog = 0.01f, light = rgb(0.8f, 0.9f, 1f), ambient = rgb(0.3f, 0.35f, 0.45f),
    )
    override val shadowRadius = 2f
    private val ball = Prop(0f, 1.3f, -1.5f, 0.07f)
    private val panels = ArrayList<FloatArray>()  // x, y, alive
    private val wallZ = -4f
    private var serve = 1.5f
    private var misses = 0
    private val p = Vec3()
    private var cooldown = 0f

    override fun start() {
        for (r in 0 until 4) for (c in 0 until 6) panels += floatArrayOf(-1.25f + c * 0.5f, 0.6f + r * 0.5f, 1f)
        status = "Bata na bola com a palma da mão (ou o controle) para quebrar os painéis da parede"
    }

    override fun update(dt: Float) {
        cooldown -= dt
        if (serve > 0f) { serve -= dt; ball.x = 0f; ball.y = 1.3f; ball.z = -1.2f; if (serve <= 0f) { ball.vx = 0f; ball.vy = 0.5f; ball.vz = -3f }; return }
        ball.vy -= 3f * dt   // low gravity in the dome
        ball.x += ball.vx * dt; ball.y += ball.vy * dt; ball.z += ball.vz * dt
        if (ball.x < -1.6f || ball.x > 1.6f) { ball.vx = -ball.vx; ball.x = ball.x.coerceIn(-1.6f, 1.6f) }
        if (ball.y < 0.07f) { ball.vy = abs(ball.vy) * 0.8f; ball.y = 0.07f }
        if (ball.y > 2.7f) { ball.vy = -abs(ball.vy); ball.y = 2.7f }
        if (ball.z < wallZ) {
            ball.vz = abs(ball.vz); ball.z = wallZ
            panels.firstOrNull { it[2] > 0f && abs(it[0] - ball.x) < 0.25f && abs(it[1] - ball.y) < 0.25f }?.let {
                it[2] = 0f; score += 100
                host.particles.burst(Particles.DEBRIS, it[0], it[1], wallZ, 20, 2f, rgb(0.3f, 0.8f, 1f), 1f, 0.03f)
                host.play(host.sBoom, it[0], it[1], wallZ, 0.5f, 1.6f)
            } ?: host.play(host.sTick, ball.x, ball.y, wallZ, 0.4f)
        }
        // hit with a palm or the controller
        if (cooldown <= 0f) for (i in 0..2) {
            if (!host.handPos(i, p)) continue
            if (dist(p.x, p.y, p.z, ball.x, ball.y, ball.z) < 0.16f) {
                val v = if (i < 2) host.hands[i].velocity else host.pad.velocity
                ball.vx = v.x * 1.4f + (ball.x - p.x) * 3f
                ball.vy = v.y * 1.4f + 1f
                ball.vz = min(-3f, v.z * 1.6f - 2.5f)
                cooldown = 0.25f
                host.play(host.sHit, ball.x, ball.y, ball.z, 0.8f)
                if (i == 2) host.pad.buzz(25)
            }
        }
        if (ball.z > 1.2f) { misses++; serve = 1.5f; host.play(host.sMiss, 0f, 1f, 0f) }
        val left = panels.count { it[2] > 0f }
        if (left == 0 && !over) { over = true; score += max(0, 5 - misses) * 200; status = "Parede destruída com $misses erros!" }
        else if (misses >= 6 && !over) { over = true; status = "Fim: faltaram $left painéis" }
        else if (!over) status = "Painéis $left · erros $misses de 6"
    }

    override fun draw() {
        host.drawBox(0f, 1.4f, wallZ - 0.05f, 3.4f, 2.8f, 0.1f, rgb(0.08f, 0.1f, 0.18f), gloss = 0.5f)
        for (pn in panels) if (pn[2] > 0f) {
            host.drawBox(pn[0], pn[1], wallZ + 0.02f, 0.45f, 0.45f, 0.04f, rgb(0.2f, 0.7f, 1f), unlit = true)
            host.glow(pn[0], pn[1], wallZ + 0.05f, 0.3f, rgb(0.2f, 0.7f, 1f), 0.25f)
        }
        host.drawSphere(ball.x, ball.y, ball.z, ball.r, rgb(1f, 0.95f, 0.5f), unlit = true)
        host.glow(ball.x, ball.y, ball.z, 0.2f, rgb(1f, 0.9f, 0.4f), 0.7f)
    }
}
