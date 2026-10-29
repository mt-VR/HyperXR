package com.hyperxr.questgames

import com.hyperxr.math.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Action games: finger guns (pinch where the index points) or the controller's trigger, blades
// held in a closed hand, spells from an open palm.

/** Quintal dos Zumbis: hold the yard at dusk; slow shambling figures come from the fog. */
class ZombieYardGame(host: GameHost) : QuestGame(host) {
    override val title = "Quintal dos Zumbis"
    override val look = GameSky.Look(
        zenith = rgb(0.08f, 0.1f, 0.18f), horizon = rgb(0.5f, 0.3f, 0.25f), ground = rgb(0.1f, 0.12f, 0.08f),
        sunDir = floatArrayOf(-0.5f, 0.08f, -0.8f), sunColor = rgb(1f, 0.45f, 0.2f), sunSize = 0.05f, clouds = 0.3f,
        floor = GameSky.FLOOR_PLAIN, floorColor = rgb(0.16f, 0.2f, 0.12f), fog = 0.06f, objectFog = 0.05f,
        light = rgb(0.9f, 0.6f, 0.45f), ambient = rgb(0.2f, 0.2f, 0.28f),
    )
    private class Z(var x: Float, var z: Float, val speed: Float) { var hp = 2; var t = 0f; var dead = 0f }
    private val zs = ArrayList<Z>()
    private val shooter = Shooter(host)
    private var wave = 0; private var lives = 4; private var spawnLeft = 0; private var spawnT = 0f; private var rest = 2f

    override fun start() { status = "Atire nos zumbis: pinça apontando o dedo (ou gatilho). Tiro na cabeça derruba de uma vez" }

    override fun update(dt: Float) {
        if (spawnLeft == 0 && zs.none { it.dead == 0f }) { rest -= dt; if (rest <= 0f) { wave++; spawnLeft = 3 + wave * 2; rest = 3f } }
        spawnT -= dt
        if (spawnLeft > 0 && spawnT <= 0f) { spawnLeft--; spawnT = 1.2f; val a = (Math.random().toFloat() - 0.5f) * 2.2f; zs += Z(sin(a) * 18f, -cos(a) * 18f, 0.6f + wave * 0.08f + Math.random().toFloat() * 0.3f) }
        shooter.update { o, d, i ->
            host.play(host.sShoot, o.x, o.y, o.z, 0.7f); if (i == 2) host.pad.buzz(30)
            var best = 40f; var hit: Z? = null; var head = false
            for (z in zs) if (z.dead == 0f) {
                val th = rayHit(o, d, z.x, 1.6f, z.z, 0.15f); val tb = rayHit(o, d, z.x, 1.05f, z.z, 0.3f)
                if (th in 0f..best) { best = th; hit = z; head = true }
                else if (tb in 0f..best) { best = tb; hit = z; head = false }
            }
            hit?.let { z ->
                z.hp -= if (head) 2 else 1
                host.particles.burst(Particles.BLOB, z.x, if (head) 1.6f else 1.05f, z.z, 12, 1.5f, rgb(0.35f, 0.5f, 0.15f), 0.7f, 0.02f)
                if (z.hp <= 0) { z.dead = 0.01f; score += if (head) 150 else 100; host.play(host.sHit, z.x, 1f, z.z, 0.6f, 0.6f) }
            }
        }
        for (z in zs) {
            if (z.dead > 0f) { z.dead += dt; continue }
            z.t += dt
            val d = sqrt(z.x * z.x + z.z * z.z).coerceAtLeast(0.1f)
            z.x -= z.x / d * z.speed * dt; z.z -= z.z / d * z.speed * dt
            if (d < 0.8f) { z.dead = 0.01f; lives--; host.play(host.sMiss, 0f, 1f, 0f); host.glow(host.head.x, host.head.y, host.head.z, 0.3f, rgb(1f, 0f, 0f), 1f) }
        }
        zs.removeAll { it.dead > 1.2f }
        if (lives <= 0 && !over) { over = true; status = "O quintal caiu na onda $wave: $score pontos" }
        else if (!over) status = "Onda $wave · vidas $lives · $score"
    }

    override fun draw() {
        // fence and the house's porch light
        for (k in -10..10) host.drawBox(k * 0.8f, 0.5f, -10f, 0.12f, 1f, 0.04f, rgb(0.5f, 0.42f, 0.32f), tex = "wood", tile = 0.5f)
        host.drawBox(0f, 0.9f, -10f, 16f, 0.08f, 0.04f, rgb(0.5f, 0.42f, 0.32f))
        host.glow(0f, 2.4f, 2f, 1.2f, rgb(1f, 0.8f, 0.5f), 0.25f)
        for (z in zs) {
            val fall = min(1f, z.dead * 2f)
            val yaw = atan2(-z.x, -z.z)
            val sway = sin(z.t * 3f) * 0.08f
            val o = com.hyperxr.math.Quat.fromAxisAngle(1f, 0f, 0f, -fall * 1.4f)
            host.drawEllipsoid(z.x, 1.05f - fall * 0.7f, z.z, 0.42f, 0.7f, 0.3f, rgb(0.3f, 0.38f, 0.3f), yaw = yaw, orientation = if (fall > 0f) o else null, gloss = 0.1f)
            host.drawSphere(z.x + sway, 1.6f - fall * 1.2f, z.z, 0.15f, rgb(0.5f, 0.6f, 0.4f), gloss = 0.1f)
            // arms out in front
            if (fall == 0f) for (s in listOf(-1f, 1f)) host.drawBeam(z.x + s * 0.2f, 1.3f, z.z, z.x + s * 0.18f - sin(yaw) * 0.5f, 1.35f + sway, z.z - cos(yaw) * 0.5f, 0.05f, rgb(0.45f, 0.55f, 0.38f), unlit = false)
        }
    }
}

/** Sabre de Energia: close your hand to light a blade; a training drone fires bolts, send them back. */
class SaberTrainingGame(host: GameHost) : QuestGame(host) {
    override val title = "Sabre de Energia"
    override val look = GameSky.Look(
        zenith = rgb(0.02f, 0.02f, 0.05f), horizon = rgb(0.06f, 0.08f, 0.15f), ground = rgb(0.02f, 0.02f, 0.04f),
        stars = 1f, floor = GameSky.FLOOR_GRID, floorColor = rgb(0.03f, 0.03f, 0.06f), grid = floatArrayOf(0.3f, 0.5f, 0.9f, 1f),
        fog = 0.02f, light = rgb(0.7f, 0.75f, 1f), ambient = rgb(0.25f, 0.27f, 0.35f),
    )
    private val drone = floatArrayOf(0f, 1.6f, -2.5f)
    private val bolts = ArrayList<FloatArray>()  // x y z vx vy vz reflected
    private var cool = 2f; private var lives = 5; private var t = 0f
    private val base = Vec3(); private val tip = Vec3()
    private var saberHand = -1

    override fun start() { status = "Feche a mão para acender a lâmina (ou use o controle). Rebata os tiros do drone de volta nele" }

    private fun saber(): Boolean {
        for (i in listOf(1, 0)) { val g = host.hands[i]; if (g.usable && g.fist) { saberHand = i; base.set(g.palm); g.along(1.0f, tip); return true } }
        if (host.pad.connected) { saberHand = 2; base.set(host.pad.pose.position); tip.set(host.pad.aim.origin).addScaled(host.pad.aim.direction, 1.0f); return true }
        saberHand = -1; return false
    }

    override fun update(dt: Float) {
        t += dt
        drone[0] = sin(t * 0.7f) * 1.2f; drone[1] = 1.6f + sin(t * 1.3f) * 0.3f; drone[2] = -2.5f + cos(t * 0.5f) * 0.4f
        cool -= dt
        if (cool <= 0f) {
            cool = (1.8f - t / 120f).coerceAtLeast(0.7f)
            val dx = host.head.x + (Math.random().toFloat() - 0.5f) * 0.4f - drone[0]; val dy = host.head.y - 0.25f - drone[1]; val dz = host.head.z - drone[2]
            val l = sqrt(dx * dx + dy * dy + dz * dz)
            bolts += floatArrayOf(drone[0], drone[1], drone[2], dx / l * 3.5f, dy / l * 3.5f, dz / l * 3.5f, 0f)
            host.play(host.sShoot, drone[0], drone[1], drone[2], 0.4f, 1.8f)
        }
        val lit = saber()
        for (b in bolts) {
            b[0] += b[3] * dt; b[1] += b[4] * dt; b[2] += b[5] * dt
            if (lit && b[6] == 0f && segDist(b[0], b[1], b[2]) < 0.07f) {
                // deflected back toward the drone
                b[6] = 1f
                val dx = drone[0] - b[0]; val dy = drone[1] - b[1]; val dz = drone[2] - b[2]; val l = sqrt(dx * dx + dy * dy + dz * dz)
                b[3] = dx / l * 6f; b[4] = dy / l * 6f; b[5] = dz / l * 6f
                host.play(host.sHit, b[0], b[1], b[2], 0.6f, 1.6f); if (saberHand == 2) host.pad.buzz(30)
                host.particles.burst(Particles.SPARK, b[0], b[1], b[2], 12, 2f, rgb(1f, 0.6f, 0.2f), 0.3f, 0.006f)
            }
            if (b[6] == 1f && dist(b[0], b[1], b[2], drone[0], drone[1], drone[2]) < 0.25f) { b[6] = 2f; score += 100; host.play(host.sBoom, drone[0], drone[1], drone[2], 0.4f, 2f) }
            if (b[6] == 0f && dist(b[0], b[1], b[2], host.head.x, host.head.y - 0.25f, host.head.z) < 0.25f) { b[6] = 2f; lives--; host.play(host.sMiss, host.head.x, host.head.y, host.head.z) }
        }
        bolts.removeAll { it[6] == 2f || it[2] > 2f || it[2] < -8f }
        if (lives <= 0 && !over) { over = true; status = "Treino encerrado: $score pontos" } else if (!over) status = "Vidas $lives · $score"
    }

    private fun segDist(x: Float, y: Float, z: Float): Float {
        val ax = tip.x - base.x; val ay = tip.y - base.y; val az = tip.z - base.z
        val l2 = ax * ax + ay * ay + az * az
        val k = (((x - base.x) * ax + (y - base.y) * ay + (z - base.z) * az) / l2).coerceIn(0f, 1f)
        return dist(x, y, z, base.x + ax * k, base.y + ay * k, base.z + az * k)
    }

    override fun draw() {
        host.drawSphere(drone[0], drone[1], drone[2], 0.15f, rgb(0.2f, 0.2f, 0.24f), gloss = 0.9f, metal = 0.6f)
        host.drawSphere(drone[0], drone[1], drone[2] + 0.13f, 0.04f, rgb(1f, 0.3f, 0.2f), unlit = true)
        if (saberHand >= 0) {
            host.drawCylinder(base.x, base.y, base.z, 0.02f, 0.18f, rgb(0.6f, 0.6f, 0.65f), orientation = host.along(tip.x - base.x, tip.y - base.y, tip.z - base.z), metal = 0.8f)
            host.glowBeam(base.x, base.y, base.z, tip.x, tip.y, tip.z, 0.025f, rgb(0.3f, 0.7f, 1f), 1f)
        }
        for (b in bolts) host.glowBeam(b[0], b[1], b[2], b[0] - b[3] * 0.06f, b[1] - b[4] * 0.06f, b[2] - b[5] * 0.06f, 0.015f, rgb(1f, 0.35f, 0.2f), 1f)
    }
}

/** Feitiços: hold an open palm up to grow a fireball, throw it at the crystals; pinch for a frost shard. */
class SpellsGame(host: GameHost) : QuestGame(host) {
    override val title = "Feitiços"
    override val look = GameSky.Look(
        zenith = rgb(0.15f, 0.1f, 0.3f), horizon = rgb(0.45f, 0.3f, 0.5f), ground = rgb(0.15f, 0.12f, 0.15f),
        stars = 0.6f, floor = GameSky.FLOOR_PLAIN, floorColor = rgb(0.25f, 0.2f, 0.25f), fog = 0.02f,
        light = rgb(0.9f, 0.8f, 1f), ambient = rgb(0.35f, 0.3f, 0.45f),
    )
    private val balls = ArrayList<Prop>()   // tag 0 fire, 1 frost
    private val charge = FloatArray(2)
    private val held = arrayOfNulls<Prop>(2)
    private val crystals = ArrayList<FloatArray>()  // x y z alive kind
    private var time = 90f
    private val wasPinch = BooleanArray(2)

    override fun start() {
        for (k in 0 until 8) { val a = (k - 3.5f) * 0.3f; crystals += floatArrayOf(sin(a) * 6f, 0.8f + (k % 3) * 0.7f, -cos(a) * 6f, 1f, (k % 2).toFloat()) }
        status = "Palma aberta para cima: cresce uma bola de fogo. Jogue nos cristais vermelhos. Pinça: estilhaço de gelo nos azuis"
    }

    override fun update(dt: Float) {
        time -= dt
        for (i in 0..1) {
            val g = host.hands[i]
            if (!g.usable) { charge[i] = 0f; held[i] = null; continue }
            val open = !g.fist && !g.pinch
            val palmUp = g.dir.y > -0.2f
            val h = held[i]
            if (h == null && open && palmUp) {
                charge[i] += dt
                if (charge[i] > 0.6f) { held[i] = Prop(g.palm.x, g.palm.y + 0.1f, g.palm.z, 0.08f).also { it.tag = 0; balls += it; it.held = i } }
            } else if (h != null) {
                h.x = g.palm.x; h.y = g.palm.y + 0.1f; h.z = g.palm.z
                // a fast flick throws it
                if (g.speed > 1.6f) { h.held = -1; h.vx = g.velocity.x * 2f; h.vy = g.velocity.y * 2f + 1f; h.vz = g.velocity.z * 2f; held[i] = null; charge[i] = 0f; host.play(host.sShoot, h.x, h.y, h.z, 0.5f, 0.6f) }
            } else charge[i] = 0f
            if (g.pinch && !wasPinch[i]) { balls += Prop(g.tip.x, g.tip.y, g.tip.z, 0.05f).also { it.tag = 1; it.vx = g.dir.x * 9f; it.vy = g.dir.y * 9f; it.vz = g.dir.z * 9f }; host.play(host.sTick, g.tip.x, g.tip.y, g.tip.z, 0.6f, 2f) }
            wasPinch[i] = g.pinch
        }
        if (host.pad.triggerDown) { val d = host.pad.aim.direction; balls += Prop(host.pad.aim.origin.x, host.pad.aim.origin.y, host.pad.aim.origin.z, 0.08f).also { it.tag = 0; it.vx = d.x * 10f; it.vy = d.y * 10f; it.vz = d.z * 10f } }
        for (b in balls) {
            if (b.held >= 0) continue
            b.vy -= 3f * dt; b.x += b.vx * dt; b.y += b.vy * dt; b.z += b.vz * dt
            for (c in crystals) if (c[3] > 0f && dist(b.x, b.y, b.z, c[0], c[1], c[2]) < 0.35f) {
                b.alive = false
                if (c[4].toInt() == b.tag) { c[3] = 0f; score += 150; host.play(host.sBoom, c[0], c[1], c[2], 0.6f, 1.3f); host.particles.burst(Particles.DEBRIS, c[0], c[1], c[2], 30, 3f, if (b.tag == 0) FIRE else ICE, 1f, 0.03f) }
                else host.play(host.sMiss, c[0], c[1], c[2], 0.3f)
            }
            if (b.y < 0f || b.z < -15f) b.alive = false
        }
        balls.removeAll { !it.alive }
        // crystals come back over time
        for (c in crystals) if (c[3] <= 0f) { c[3] -= dt; if (c[3] < -6f) c[3] = 1f }
        if (time <= 0f && !over) { over = true; status = "Fim do tempo: $score pontos" } else if (!over) status = "${time.toInt()} s · $score pontos"
    }

    override fun draw() {
        for (c in crystals) if (c[3] > 0f) {
            val col = if (c[4] == 0f) FIRE else ICE
            host.drawEllipsoid(c[0], c[1], c[2], 0.25f, 0.5f, 0.25f, col, yaw = host.time + c[0], gloss = 1f)
            host.glow(c[0], c[1], c[2], 0.6f, col, 0.35f)
        }
        for (b in balls) {
            val col = if (b.tag == 0) FIRE else ICE
            host.drawSphere(b.x, b.y, b.z, b.r, col, unlit = true, shadow = false)
            host.glow(b.x, b.y, b.z, b.r * 3f, col, 0.8f)
        }
        for (i in 0..1) if (charge[i] > 0f && held[i] == null && host.hands[i].usable) {
            val p = host.hands[i].palm; host.glow(p.x, p.y + 0.1f, p.z, 0.05f + charge[i] * 0.2f, FIRE, charge[i])
        }
    }

    companion object { val FIRE = rgb(1f, 0.45f, 0.1f); val ICE = rgb(0.4f, 0.8f, 1f) }
}

/** Tiro ao Prato: clay discs fly across the field; shoot them in the air. Two shots per pull. */
class SkeetGame(host: GameHost) : QuestGame(host) {
    override val title = "Tiro ao Prato"
    override val look = GameSky.Look(
        zenith = rgb(0.35f, 0.6f, 0.95f), horizon = rgb(0.85f, 0.88f, 0.9f), ground = rgb(0.4f, 0.5f, 0.3f),
        sunDir = floatArrayOf(0.3f, 0.6f, -0.5f), sunColor = rgb(1f, 0.95f, 0.85f), clouds = 0.3f,
        floor = GameSky.FLOOR_PLAIN, floorColor = rgb(0.4f, 0.52f, 0.28f), fog = 0.004f,
    )
    private val discs = ArrayList<Prop>()
    private val shooter = Shooter(host)
    private var pulls = 0; private var next = 2f; private var shotsLeft = 2; private var hits = 0

    override fun start() { status = "Os pratos voam pelo campo: atire neles no ar (pinça ou gatilho). 15 lançamentos" }

    override fun update(dt: Float) {
        next -= dt
        if (next <= 0f && pulls < 15 && discs.none { it.alive }) {
            pulls++; next = 2.5f; shotsLeft = 2
            val side = if (pulls % 2 == 0) -1f else 1f
            repeat(if (pulls > 8 && pulls % 3 == 0) 2 else 1) { k ->
                discs += Prop(side * 12f, 0.8f, -14f + k * 2f, 0.11f).also { it.vx = -side * (7f + k); it.vy = 7f + k; it.vz = 2.5f }
            }
            host.play(host.sSplash, side * 12f, 1f, -14f, 0.5f, 1.5f)
        }
        shooter.update { o, d, i ->
            if (shotsLeft <= 0) return@update
            shotsLeft--
            host.play(host.sBoom, o.x, o.y, o.z, 0.4f, 1.2f); if (i == 2) host.pad.buzz(40)
            for (p in discs) if (p.alive && rayHit(o, d, p.x, p.y, p.z, 0.35f) >= 0f) {
                p.alive = false; hits++; score += 100
                host.particles.burst(Particles.DEBRIS, p.x, p.y, p.z, 25, 3f, rgb(1f, 0.5f, 0.2f), 1.5f, 0.02f, gravity = 9f)
            }
        }
        for (p in discs) if (p.alive) { p.vy -= 9.81f * dt * 0.55f; p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt; if (p.y < 0f) p.alive = false }
        discs.removeAll { !it.alive }
        if (pulls >= 15 && discs.isEmpty() && next < 0.5f && !over) { over = true; status = "$hits pratos acertados" } else if (!over) status = "Lançamento $pulls de 15 · acertos $hits · tiros $shotsLeft"
    }

    override fun draw() {
        for (s in listOf(-1f, 1f)) host.drawBox(s * 12f, 1.2f, -14f, 1.2f, 2.4f, 1.2f, rgb(0.85f, 0.85f, 0.8f), tex = "concrete", tile = 1f)
        for (p in discs) host.drawCylinder(p.x, p.y, p.z, 0.11f, 0.03f, rgb(1f, 0.5f, 0.15f), gloss = 0.4f)
    }
}

/** Paintball: duck behind the barrels, pop up and splat the bots before they splat you. */
class PaintballGame(host: GameHost) : QuestGame(host) {
    override val title = "Paintball"
    override val look = GameSky.Look(
        zenith = rgb(0.4f, 0.6f, 0.9f), horizon = rgb(0.9f, 0.88f, 0.82f), ground = rgb(0.5f, 0.42f, 0.3f),
        sunDir = floatArrayOf(0.5f, 0.6f, 0.3f), sunColor = rgb(1f, 0.95f, 0.8f), clouds = 0.4f,
        floor = GameSky.FLOOR_PLAIN, floorColor = rgb(0.55f, 0.45f, 0.3f), fog = 0.008f,
    )
    private class Bot(val x: Float, val z: Float) { var up = 0f; var cool = 2f; var out = false; var t = 0f }
    private val bots = listOf(Bot(-3f, -9f), Bot(0f, -11f), Bot(3.2f, -8.5f), Bot(-1.6f, -13f), Bot(1.8f, -12f))
    private val shots = ArrayList<FloatArray>()  // x y z vx vy vz fromPlayer
    private val shooter = Shooter(host)
    private val splats = ArrayList<FloatArray>()
    private var paint = 0f
    private var hitsTaken = 0
    private var standH = 1.6f; private var t = 0f

    override fun start() { status = "Atire nos robôs quando eles aparecerem; agache atrás do barril para se proteger" }

    override fun update(dt: Float) {
        t += dt
        if (t < 1f) standH = host.head.y
        paint = max(0f, paint - dt * 0.3f)
        shooter.update { o, d, _ ->
            shots += floatArrayOf(o.x, o.y, o.z, d.x * 25f, d.y * 25f, d.z * 25f, 1f)
            host.play(host.sShoot, o.x, o.y, o.z, 0.4f, 1.6f)
        }
        val hidden = host.head.y < standH - 0.35f
        for (b in bots) if (!b.out) {
            b.t += dt
            b.up = if (sin(b.t * 0.9f + b.x) > 0.2f) min(1f, b.up + dt * 3f) else max(0f, b.up - dt * 3f)
            b.cool -= dt
            if (b.up > 0.9f && b.cool <= 0f) {
                b.cool = 1.6f + Math.random().toFloat()
                val tx = host.head.x - b.x; val ty = host.head.y - 0.1f - 1.3f; val tz = host.head.z - b.z; val l = sqrt(tx * tx + ty * ty + tz * tz)
                shots += floatArrayOf(b.x, 1.3f, b.z, tx / l * 12f, ty / l * 12f, tz / l * 12f, 0f)
            }
        }
        for (s in shots) {
            s[4] -= 4f * dt; s[0] += s[3] * dt; s[1] += s[4] * dt; s[2] += s[5] * dt
            if (s[6] == 1f) for (b in bots) if (!b.out && b.up > 0.5f && dist(s[0], s[1], s[2], b.x, 1.25f, b.z) < 0.35f) {
                b.out = true; s[1] = -1f; score += 200; splats += floatArrayOf(b.x, 1.25f, b.z + 0.3f, 0.3f)
                host.play(host.sSplash, b.x, 1.25f, b.z, 0.8f, 1.4f)
            }
            if (s[6] == 0f && !hidden && dist(s[0], s[1], s[2], host.head.x, host.head.y - 0.2f, host.head.z) < 0.3f) { s[1] = -1f; hitsTaken++; paint = 1f; host.play(host.sSplash, host.head.x, host.head.y, host.head.z, 1f, 0.8f) }
            // the barrel in front of you stops shots
            if (s[6] == 0f && abs(s[0]) < 0.45f && abs(s[2] + 0.9f) < 0.3f && s[1] < 1.05f) { s[1] = -1f; splats += floatArrayOf(s[0], s[1].coerceAtLeast(0.6f), -0.6f, 0.12f) }
        }
        shots.removeAll { it[1] < 0f || it[2] < -20f || it[2] > 3f }
        if (hitsTaken >= 5 && !over) { over = true; status = "Você ficou todo pintado! $score pontos" }
        else if (bots.all { it.out } && !over) { over = true; score += (5 - hitsTaken) * 100; status = "Venceu com $hitsTaken manchas" }
        else if (!over) status = "Robôs ${bots.count { !it.out }} · manchas em você $hitsTaken de 5" + if (hidden) " · protegido" else ""
    }

    override fun draw() {
        host.drawCylinder(0f, 0.5f, -0.9f, 0.4f, 1f, rgb(0.25f, 0.45f, 0.8f), gloss = 0.4f, metal = 0.3f)
        for (b in bots) {
            host.drawCylinder(b.x, 0.5f, b.z + 0.6f, 0.45f, 1f, rgb(0.8f, 0.3f, 0.25f), gloss = 0.4f)
            if (b.out) continue
            val y = 0.4f + b.up * 0.9f
            host.drawRounded(b.x, y, b.z, 0.45f, 0.6f, 0.35f, rgb(0.9f, 0.9f, 0.92f), gloss = 0.6f)
            host.drawSphere(b.x, y + 0.42f, b.z, 0.16f, rgb(0.2f, 0.2f, 0.25f), gloss = 0.8f)
        }
        for (s in shots) host.drawSphere(s[0], s[1], s[2], 0.025f, if (s[6] == 1f) rgb(0.2f, 1f, 0.4f) else rgb(1f, 0.3f, 0.8f), unlit = true, shadow = false)
        for (sp in splats) host.drawSphere(sp[0], sp[1], sp[2], sp[3], rgb(0.2f, 1f, 0.4f, 0.9f), unlit = true, shadow = false)
        if (paint > 0f) host.glow(host.head.x, host.head.y, host.head.z, 0.25f, rgb(1f, 0.3f, 0.8f), paint)
    }
}

/** Gravidade Zero: float in an arena; push off the walls with your hands, grab the disc, throw it into the goal ring. */
class ZeroGGame(host: GameHost) : QuestGame(host) {
    override val title = "Gravidade Zero"
    override val look = GameSky.Look(
        zenith = rgb(0.01f, 0.01f, 0.04f), horizon = rgb(0.05f, 0.08f, 0.15f), ground = rgb(0.01f, 0.01f, 0.03f),
        stars = 1f, nebula = floatArrayOf(0.2f, 0.3f, 0.6f, 0.7f), fog = 0.005f, light = rgb(0.8f, 0.85f, 1f), ambient = rgb(0.35f, 0.38f, 0.5f),
    )
    override val shadowRadius = 0f
    private val world = GorillaRig.World()
    private val rig = GorillaRig(world).apply { gravity = 0f; groundFriction = 1f; maxJumpSpeed = 3.5f; velocityLimit = 0.3f; jumpMultiplier = 1f; armLength = 1.2f }
    private val disc = Prop(0f, 3f, -4f, 0.14f)
    private var holding = -1
    private var offsetY = Float.NaN
    private val headL = FloatArray(3)
    private val handL = Array(2) { FloatArray(3) }
    private val inputs = arrayOfNulls<FloatArray>(2)
    private var goals = 0; private var time = 120f
    private val goal = floatArrayOf(0f, 3f, -11f)

    override fun start() {
        // a box arena, ground far below so nothing touches it
        world.ground = { _, _ -> -50f }
        world.box(0f, -0.5f, -4f, 8f, 1f, 16f); world.box(0f, 7.5f, -4f, 8f, 1f, 16f)
        world.box(-4.5f, 3.5f, -4f, 1f, 8f, 16f); world.box(4.5f, 3.5f, -4f, 1f, 8f, 16f)
        world.box(0f, 3.5f, 4.5f, 8f, 8f, 1f); world.box(0f, 3.5f, -12.5f, 8f, 8f, 1f)
        world.box(-1.5f, 2.5f, -5f, 1.2f, 1.2f, 1.2f); world.box(1.8f, 4.5f, -7f, 1.2f, 1.2f, 1.2f)
        rig.teleport(0f, 0.6f, 0f)
        status = "Sem gravidade: empurre as paredes com as mãos para se mover, pegue o disco e jogue no anel do fundo"
    }

    override fun update(dt: Float) {
        time -= dt
        if (offsetY.isNaN()) { if (host.head.y < 0.5f) return; offsetY = host.head.y - 1.0f }
        headL[0] = host.head.x; headL[1] = host.head.y - offsetY; headL[2] = host.head.z
        for (i in 0..1) { val g = host.hands[i]; if (!g.usable) { inputs[i] = null; continue }; handL[i][0] = g.palm.x; handL[i][1] = g.palm.y - offsetY; handL[i][2] = g.palm.z; inputs[i] = handL[i] }
        rig.step(dt, headL, inputs)
        // drift slows a touch so it stays playable
        for (k in 0..2) rig.velocity[k] *= 0.995f
        // the disc: grab with a closed hand when close, throw by opening
        for (i in 0..1) {
            val g = host.hands[i]
            if (!g.usable) continue
            val hx = rig.hand[i][0]; val hy = rig.hand[i][1]; val hz = rig.hand[i][2]
            if (holding < 0 && g.fist && dist(hx, hy, hz, disc.x, disc.y, disc.z) < 0.25f) holding = i
            if (holding == i) {
                disc.x = hx; disc.y = hy; disc.z = hz
                if (!g.fist) { holding = -1; disc.vx = g.velocity.x * 2f + rig.velocity[0]; disc.vy = g.velocity.y * 2f + rig.velocity[1]; disc.vz = g.velocity.z * 2f + rig.velocity[2] }
            }
        }
        if (holding < 0) {
            disc.x += disc.vx * dt; disc.y += disc.vy * dt; disc.z += disc.vz * dt
            val p = floatArrayOf(disc.x, disc.y, disc.z); val n = FloatArray(3)
            if (world.resolve(p, disc.r, n)) {
                val vn = disc.vx * n[0] + disc.vy * n[1] + disc.vz * n[2]
                disc.vx -= 1.8f * vn * n[0]; disc.vy -= 1.8f * vn * n[1]; disc.vz -= 1.8f * vn * n[2]
                disc.x = p[0]; disc.y = p[1]; disc.z = p[2]
                host.play(host.sTick, gx(disc.x), gy(disc.y), gz(disc.z), 0.4f)
            }
            if (dist(disc.x, disc.y, disc.z, goal[0], goal[1], goal[2]) < 0.6f) {
                goals++; score += 300
                host.play(host.sWin, gx(goal[0]), gy(goal[1]), gz(goal[2]))
                disc.x = 0f; disc.y = 3f; disc.z = -4f; disc.vx = 0f; disc.vy = 0f; disc.vz = 0f
            }
        }
        if (time <= 0f && !over) { over = true; status = "$goals gols!" } else if (!over) status = "${time.toInt()} s · gols $goals"
    }

    private fun gx(x: Float) = x - rig.body[0]
    private fun gy(y: Float) = y - rig.body[1] + (if (offsetY.isNaN()) 0f else offsetY)
    private fun gz(z: Float) = z - rig.body[2]

    override fun draw() {
        for (b in world.boxes) {
            val cx = (b[0] + b[3]) / 2; val cy = (b[1] + b[4]) / 2; val cz = (b[2] + b[5]) / 2
            host.drawBox(gx(cx), gy(cy), gz(cz), b[3] - b[0], b[4] - b[1], b[5] - b[2], rgb(0.15f, 0.18f, 0.26f, if (b[4] - b[1] > 6f || b[5] - b[2] > 10f) 0.55f else 1f), gloss = 0.6f)
        }
        host.drawTorus(gx(goal[0]), gy(goal[1]), gz(goal[2]), 0.6f, rgb(1f, 0.7f, 0.2f), orientation = com.hyperxr.math.Quat.fromAxisAngle(1f, 0f, 0f, 1.5708f), glow = true)
        host.drawCylinder(gx(disc.x), gy(disc.y), gz(disc.z), disc.r, 0.03f, rgb(0.3f, 0.9f, 1f), unlit = true)
        host.glow(gx(disc.x), gy(disc.y), gz(disc.z), 0.3f, rgb(0.3f, 0.9f, 1f), 0.6f)
        for (i in 0..1) if (host.hands[i].usable) host.drawSphere(gx(rig.hand[i][0]), gy(rig.hand[i][1]), gz(rig.hand[i][2]), 0.06f, if (rig.touching[i]) rgb(1f, 0.8f, 0.3f) else rgb(0.8f, 0.85f, 0.95f), gloss = 0.6f)
    }
}
