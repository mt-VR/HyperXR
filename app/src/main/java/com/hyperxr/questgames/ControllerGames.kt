package com.hyperxr.questgames

import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private fun rgba(r: Float, g: Float, b: Float, a: Float = 1f) = floatArrayOf(r, g, b, a)
private val WHITE = rgba(1f, 1f, 1f)

/** The blaster in the controller's hand, and where its barrel points. */
private fun drawBlaster(host: GameHost, tint: FloatArray? = null, gloss: Float = 0.6f) {
    val p = host.pad.pose
    host.drawModel("blaster/blaster-a", p.position.x, p.position.y, p.position.z, scale = 0.9f, orientation = p.orientation, tint = tint, gloss = gloss)
}

// ================================================================================ 6. Tempo Parado
/**
 * A white world of clean blocks and hard shadows. Time moves only when you move. Red glass
 * figures walk in and fire slow bullets with long trails: shoot them, dodge the bullets.
 */
class SlowTimeGame(host: GameHost) : QuestGame(host) {
    override val title = "Tempo Parado"
    // hands work too: pinch to shoot where the index points, punch the ones that come close
    override val look = GameSky.Look(
        zenith = rgba(0.9f, 0.91f, 0.93f), horizon = rgba(0.97f, 0.97f, 0.98f), ground = rgba(0.92f, 0.92f, 0.93f),
        sunDir = floatArrayOf(0.45f, 0.8f, 0.35f), floor = GameSky.FLOOR_PLAIN, floorY = -0.01f, floorColor = rgba(0.86f, 0.86f, 0.88f),
        fog = 0.012f, objectFog = 0.012f, light = rgba(0.75f, 0.75f, 0.76f), ambient = rgba(0.62f, 0.63f, 0.66f),
    )
    override val shadowRadius = 9f
    override val shadowZ = -4f
    private class Enemy(var x: Float, var z: Float, val seed: Int) { var cooldown = 2f + seed % 3; var alive = true; var shatter = 0f; var aim = 0f; var walk = 0f }
    private val enemies = ArrayList<Enemy>()
    private val bullets = ArrayList<Bolt>()
    private var wave = 0
    private var scale = 0.05f
    private var tracer = 0f
    private val tracerEnd = Vec3()
    private val tracerStart = Vec3()
    private val rnd = java.util.Random()
    private var waveFlash = 0f

    override fun start() { nextWave(); status = "O tempo só anda quando você se mexe. Mire com o controle e aperte o gatilho" }

    private fun nextWave() {
        wave++
        repeat(2 + wave) { i ->
            val a = (rnd.nextFloat() - 0.5f) * 2.4f
            val r = 7f + rnd.nextFloat() * 4f
            enemies += Enemy(sin(a) * r, -cos(a) * r, i)
        }
    }

    override fun update(dt: Float) {
        // the time scale: from how fast the head and the controller are moving
        val handSpeed = host.hands.filter { it.usable }.maxOfOrNull { it.speed } ?: 0f
        val target = SlowTime.scale(host.headSpeed, maxOf(host.pad.velocity.length(), handSpeed * 0.7f))
        scale += (target - scale) * min(1f, dt * 10f)
        host.timeScale = scale
        val g = dt * scale
        if (host.pad.triggerDown) shoot(host.pad.aim.origin, host.pad.aim.direction)
        for (i in 0..1) {
            val hd = host.hands[i]
            val pinchNow = hd.usable && hd.pinch
            if (pinchNow && !wasPinch[i]) shoot(hd.tip, hd.dir)
            wasPinch[i] = pinchNow
            // a punch: a fast hand into an enemy's chest
            if (hd.usable && hd.speed > 2.2f) for (e in enemies) if (e.alive && hypot(hd.palm.x - e.x, hd.palm.z - e.z) < 0.45f && hd.palm.y in 0.7f..1.9f) shatter(e)
        }
        for (e in enemies) {
            if (!e.alive) { e.shatter += g; continue }
            val dx = host.head.x - e.x; val dz = host.head.z - e.z
            val d = hypot(dx, dz)
            if (d > 3f) { e.x += dx / d * 1.1f * g; e.z += dz / d * 1.1f * g; e.walk += g }
            e.cooldown -= g
            e.aim = (1f - e.cooldown / 1.2f).coerceIn(0f, 1f)
            if (e.cooldown <= 0f && d < 10f) {
                e.cooldown = 2.5f + rnd.nextFloat() * 2f
                // aimed at the head where it is now: it travels slowly, you can step aside
                val sx = e.x + dx / d * 0.45f; val sy = 1.35f; val sz = e.z + dz / d * 0.45f
                val tx = host.head.x - sx; val ty = host.head.y - sy; val tz = host.head.z - sz
                val l = sqrt(tx * tx + ty * ty + tz * tz)
                bullets += Bolt(sx, sy, sz, tx / l * 5f, ty / l * 5f, tz / l * 5f, false)
                host.particles.burst(Particles.SPARK, sx, sy, sz, 8, 1.5f, rgba(1f, 0.6f, 0.3f), 0.3f, 0.006f, gravity = 0f)
            }
        }
        for (b in bullets) {
            b.step(g)
            if (!b.fromPlayer && (b.x - host.head.x) * (b.x - host.head.x) + (b.y - host.head.y) * (b.y - host.head.y) + (b.z - host.head.z) * (b.z - host.head.z) < 0.17f * 0.17f) {
                over = true; status = "Atingido! Onda $wave, $score derrubados"; host.play(host.sMiss, host.head.x, host.head.y, host.head.z)
            }
        }
        bullets.removeAll { it.life <= 0f }
        enemies.removeAll { !it.alive && it.shatter > 0.1f }
        if (tracer > 0f) tracer -= dt
        if (waveFlash > 0f) waveFlash -= dt
        if (!over) {
            status = "Onda $wave · tempo ${(scale * 100).toInt()}%"
            if (enemies.none { it.alive }) { nextWave(); waveFlash = 1f; host.play(host.sWin, 0f, 1.5f, -1f, 0.5f) }
        }
    }

    private val wasPinch = BooleanArray(2)
    private val shotRay = com.hyperxr.math.Ray()

    private fun shoot(origin: com.hyperxr.math.Vec3, dir: com.hyperxr.math.Vec3) {
        shotRay.origin.set(origin); shotRay.direction.set(dir).normalize()
        val r = shotRay
        host.play(host.sShoot, r.origin.x, r.origin.y, r.origin.z)
        host.pad.buzz(30)
        var best = 30f; var hit: Enemy? = null
        for (e in enemies) {
            if (!e.alive) continue
            for ((cy, rr) in listOf(1.15f to 0.3f, 1.62f to 0.16f)) {
                val t = raySphere(r.origin.x, r.origin.y, r.origin.z, r.direction.x, r.direction.y, r.direction.z, e.x, cy, e.z, rr)
                if (t >= 0f && t < best) { best = t; hit = e }
            }
        }
        // bullets in the air can be shot down too
        val shotBullet = bullets.firstOrNull { !it.fromPlayer && raySphere(r.origin.x, r.origin.y, r.origin.z, r.direction.x, r.direction.y, r.direction.z, it.x, it.y, it.z, 0.08f).let { t -> t >= 0f && t < best } }
        tracer = 0.12f
        tracerStart.set(r.origin).addScaled(r.direction, 0.15f)
        tracerEnd.set(r.origin).addScaled(r.direction, best)
        host.particles.burst(Particles.SPARK, tracerStart.x, tracerStart.y, tracerStart.z, 6, 1.2f, rgba(1f, 0.85f, 0.5f), 0.12f, 0.005f, gravity = 0f)
        if (shotBullet != null) {
            bullets.remove(shotBullet)
            host.particles.burst(Particles.DEBRIS, shotBullet.x, shotBullet.y, shotBullet.z, 6, 1.5f, rgba(0.1f, 0.1f, 0.1f), 1f, 0.012f, gravity = 9f)
            return
        }
        hit?.let { shatter(it) }
    }

    private fun shatter(e: Enemy) {
        run {
            e.alive = false; score++
            host.play(host.sBoom, e.x, 1.2f, e.z, 0.7f, 1.5f)
            // shatters into glass shards (they fly as slowly as time goes)
            for (k in 0 until 3) host.particles.burst(Particles.DEBRIS, e.x, 0.5f + k * 0.5f, e.z, 16, 2.6f, RED_GLASS, 2.5f, 0.05f + k * 0.01f, gravity = 9f)
            host.particles.burst(Particles.DEBRIS, e.x, 1.6f, e.z, 10, 2f, RED_DEEP, 2.5f, 0.07f, gravity = 9f)
        }
    }

    private val white = rgba(0.97f, 0.97f, 0.98f)
    private val gunDark = rgba(0.12f, 0.12f, 0.13f)
    private val trailC = rgba(0.85f, 0.25f, 0.2f, 0.35f)

    override fun draw() {
        // the arena: a white floor and plain white blocks, pillars and walls (all casting hard shadows)
        host.drawBox(0f, -0.02f, -4f, 30f, 0.04f, 30f, white, gloss = 0.15f, tex = "white", tile = 2f)
        for (side in floatArrayOf(-1f, 1f)) {
            for (i in 0..3) host.drawBox(side * 5.5f, 1.75f, -2f - i * 4f, 0.7f, 3.5f, 0.7f, white, gloss = 0.1f)
            host.drawBox(side * 3.8f, 0.5f, 2.2f, 2.2f, 1f, 0.8f, white, gloss = 0.1f)
            host.drawBox(side * 7.5f, 1.2f, -9f, 3f, 2.4f, 0.4f, white, gloss = 0.1f)
            host.drawBox(side * 2.6f, 0.45f, -5.5f, 0.8f, 0.9f, 0.8f, white, gloss = 0.1f)
        }
        host.drawBox(0f, 2.2f, -17f, 24f, 4.4f, 0.5f, white, gloss = 0.1f)
        for (i in -2..2) host.drawBox(i * 4f, 4.6f, -10f, 0.5f, 0.4f, 16f, white, gloss = 0.1f)
        host.drawBox(0f, 2.8f, 4.5f, 12f, 5.6f, 0.4f, white, gloss = 0.1f)
        // the red figures, with their dark guns raised as they aim
        val m = host.model("chars/character-male-a")
        val h = m?.let { it.boundsMax[1] - it.boundsMin[1] }?.takeIf { it > 0.01f } ?: 1f
        for (e in enemies) {
            if (!e.alive) continue
            val yaw = atan2(host.head.x - e.x, host.head.z - e.z)
            val sway = sin(e.walk * 5f) * 0.04f
            host.drawModel("chars/character-male-a", e.x, abs(sway) * 0.5f, e.z, yaw = yaw, roll = sway, scale = 1.75f / h, tint = RED_GLASS, gloss = 1f)
            val fx = sin(yaw); val fz = cos(yaw)
            val gx = e.x + fx * 0.4f + fz * 0.22f; val gz = e.z + fz * 0.4f - fx * 0.22f
            host.drawModel("blaster/blaster-b", gx, 1.1f + e.aim * 0.25f, gz, yaw = yaw + Math.PI.toFloat(), scale = 0.8f, tint = gunDark, gloss = 0.7f)
        }
        // the bullets: dark slugs with a long red streak behind
        for (b in bullets) {
            val s = sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz).coerceAtLeast(1e-3f)
            host.drawEllipsoid(b.x, b.y, b.z, 0.03f, 0.07f, 0.03f, rgba(0.1f, 0.1f, 0.12f), orientation = host.along(b.vx, b.vy, b.vz), gloss = 0.9f)
            host.drawBeam(b.x, b.y, b.z, b.x - b.vx / s * 1.4f, b.y - b.vy / s * 1.4f, b.z - b.vz / s * 1.4f, 0.006f, trailC)
        }
        drawBlaster(host, gunDark, 0.7f)
        if (tracer > 0f) host.drawBeam(tracerStart.x, tracerStart.y, tracerStart.z, tracerEnd.x, tracerEnd.y, tracerEnd.z, 0.005f, rgba(0.15f, 0.15f, 0.15f, tracer / 0.12f * 0.6f))
        if (waveFlash > 0f) host.glow(host.head.x, host.head.y, host.head.z, 0.3f, WHITE, waveFlash * 0.6f)
    }

    override fun release() { host.timeScale = 1f }

    companion object {
        private val RED_GLASS = rgba(0.95f, 0.12f, 0.1f)
        private val RED_DEEP = rgba(0.6f, 0.04f, 0.05f)
    }
}

// ================================================================================ 7. Caçador de Drones
/**
 * A platform high above a neon city at night. Drones circle and fire glowing shots: blast them
 * with the trigger, hold the other button for the energy shield.
 */
class DroneGame(host: GameHost) : QuestGame(host) {
    override val title = "Caçador de Drones"
    override val needsController = true
    override val look = GameSky.Look(
        zenith = rgba(0.005f, 0.005f, 0.03f), horizon = rgba(0.16f, 0.04f, 0.22f), ground = rgba(0.02f, 0f, 0.04f),
        sunDir = floatArrayOf(0.1f, 0.06f, -1f), sunColor = rgba(1f, 0.25f, 0.55f), sunSize = 0.12f, stars = 1f, nebula = floatArrayOf(0.3f, 0.08f, 0.5f, 0.7f),
        floor = GameSky.FLOOR_GRID, floorY = -12f, floorColor = rgba(0.01f, 0f, 0.025f), grid = floatArrayOf(0.85f, 0.15f, 0.85f, 5f),
        fog = 0.012f, objectFog = 0.006f, light = rgba(0.7f, 0.75f, 1f), ambient = rgba(0.22f, 0.2f, 0.32f),
    )
    override val shadowRadius = 2.6f
    override val shadowZ = 0f
    private val drones = ArrayList<Drone>()
    private val bolts = ArrayList<Bolt>()
    private class Blast(val x: Float, val y: Float, val z: Float, var t: Float)
    private val blasts = ArrayList<Blast>()
    private var wave = 0
    private var lives = 3
    private val models = listOf("space/craft_speederA", "space/craft_speederB", "space/craft_racer")
    private var hurt = 0f
    private var muzzle = 0f
    private var shieldHit = 0f

    override fun start() { nextWave(); status = "Gatilho: atirar. Outro botão: escudo" }

    private fun nextWave() { wave++; repeat(2 + wave) { drones += Drone(System.nanoTime() + it) } }

    override fun update(dt: Float) {
        val pad = host.pad
        if (pad.triggerDown && !pad.alt) {
            val r = pad.aim
            bolts += Bolt(r.origin.x + r.direction.x * 0.2f, r.origin.y + r.direction.y * 0.2f, r.origin.z + r.direction.z * 0.2f, r.direction.x * 30f, r.direction.y * 30f, r.direction.z * 30f, true)
            host.play(host.sShoot, r.origin.x, r.origin.y, r.origin.z, 0.8f, 1.4f); pad.buzz(20)
            muzzle = 0.08f
        }
        for (d in drones) if (d.step(dt)) {
            val tx = host.head.x - d.x; val ty = host.head.y - 0.1f - d.y; val tz = host.head.z - d.z
            val l = sqrt(tx * tx + ty * ty + tz * tz)
            bolts += Bolt(d.x, d.y, d.z, tx / l * 4.5f, ty / l * 4.5f, tz / l * 4.5f, false)
        }
        val shieldC = Vec3().set(host.pad.aim.origin).addScaled(host.pad.aim.direction, 0.15f)
        val it = bolts.iterator()
        while (it.hasNext()) {
            val b = it.next()
            val px = b.x; val py = b.y; val pz = b.z
            b.step(dt)
            if (b.fromPlayer) {
                val hit = drones.firstOrNull { segmentSphere(px, py, pz, b.x, b.y, b.z, it.x, it.y, it.z, 0.4f) >= 0f }
                if (hit != null) {
                    drones.remove(hit); blasts += Blast(hit.x, hit.y, hit.z, 0f); score += 10; host.play(host.sBoom, hit.x, hit.y, hit.z); it.remove()
                    host.particles.burst(Particles.SPARK, hit.x, hit.y, hit.z, 40, 7f, rgba(1f, 0.6f, 0.2f), 0.6f, 0.012f, gravity = 3f)
                    host.particles.burst(Particles.SPARK, hit.x, hit.y, hit.z, 16, 4f, rgba(0.3f, 0.9f, 1f), 0.4f, 0.008f, gravity = 1f)
                    host.particles.burst(Particles.DEBRIS, hit.x, hit.y, hit.z, 14, 4f, rgba(0.3f, 0.32f, 0.38f), 2.5f, 0.04f, gravity = 9f)
                    continue
                }
            } else {
                if (pad.alt && (b.x - shieldC.x) * (b.x - shieldC.x) + (b.y - shieldC.y) * (b.y - shieldC.y) + (b.z - shieldC.z) * (b.z - shieldC.z) < 0.3f * 0.3f) {
                    host.play(host.sTick, b.x, b.y, b.z); pad.buzz(15); it.remove(); shieldHit = 0.3f
                    host.particles.burst(Particles.SPARK, b.x, b.y, b.z, 18, 3f, rgba(0.3f, 0.85f, 1f), 0.35f, 0.006f, gravity = 0f)
                    continue
                }
                if ((b.x - host.head.x) * (b.x - host.head.x) + (b.y - host.head.y + 0.25f) * (b.y - host.head.y + 0.25f) + (b.z - host.head.z) * (b.z - host.head.z) < 0.3f * 0.3f) {
                    lives--; hurt = 0.5f; host.play(host.sMiss, host.head.x, host.head.y, host.head.z); pad.buzz(120); it.remove(); continue
                }
            }
            if (b.life <= 0f) it.remove()
        }
        blasts.forEach { it.t += dt }; blasts.removeAll { it.t > 0.6f }
        if (hurt > 0f) hurt -= dt
        if (muzzle > 0f) muzzle -= dt
        if (shieldHit > 0f) shieldHit -= dt
        if (drones.isEmpty()) nextWave()
        status = "Onda $wave · vidas $lives"
        if (lives <= 0) { over = true; status = "Fim de jogo na onda $wave" }
    }

    private val hull = rgba(0.16f, 0.17f, 0.22f)
    private val cyan = rgba(0.2f, 0.85f, 1f)
    private val magenta = rgba(1f, 0.2f, 0.8f)
    private val enemyShot = rgba(1f, 0.25f, 0.15f)
    private val playerShot = rgba(0.25f, 0.85f, 1f)

    override fun draw() {
        // the platform: a dark metal disc with a glowing rim, on a column into the city below
        host.drawCylinder(0f, -0.06f, 0f, 2.2f, 0.12f, hull, gloss = 0.7f, metal = 0.8f, tex = "metal", tile = 0.8f)
        host.drawCylinder(0f, -0.005f, 0f, 1.2f, 0.01f, rgba(0.08f, 0.09f, 0.12f), gloss = 0.9f, shadow = false)
        host.drawTorus(0f, 0f, 0f, 2.2f, cyan, unlit = true, thick = 0.4f)
        host.drawTorus(0f, 0f, 0f, 2.2f, cyan, glow = true, thick = 1.6f)
        host.drawTorus(0f, 0.002f, 0f, 1.2f, magenta, glow = true, thick = 0.8f)
        host.drawCylinder(0f, -6f, 0f, 0.6f, 11.8f, hull, gloss = 0.6f, metal = 0.8f, shadow = false)
        for (i in 1..4) host.drawTorus(0f, -i * 2.4f, 0f, 0.62f, if (i % 2 == 0) cyan else magenta, glow = true, thick = 0.6f)
        // the skyline: dark towers with neon strips, far away
        val rnd = java.util.Random(9)
        for (i in 0 until 14) {
            val a = i / 14f * 6.28f + rnd.nextFloat() * 0.3f
            val r = 55f + rnd.nextFloat() * 40f
            val hgt = 14f + rnd.nextFloat() * 30f
            val x = sin(a) * r; val z = -cos(a) * r
            val w = 5f + rnd.nextFloat() * 6f
            host.drawBox(x, -12f + hgt / 2, z, w, hgt, w, rgba(0.03f, 0.03f, 0.06f), yaw = a, gloss = 0.5f, shadow = false)
            val c = if (i % 3 == 0) magenta else if (i % 3 == 1) cyan else rgba(1f, 0.6f, 0.2f)
            for (k in 1..3) {
                val y = -12f + hgt * k / 3.5f
                host.drawBox(x, y, z, w * 1.02f, 0.25f, w * 1.02f, c, yaw = a, unlit = true, shadow = false)
            }
            host.glow(x, -12f + hgt + 0.5f, z, 1.2f, rgba(1f, 0.15f, 0.1f), 0.5f + 0.5f * sin(host.time * 2f + i))
        }
        // the drones: hovering, a red eye that brightens before they fire, engines glowing
        for ((i, d) in drones.withIndex()) {
            val yaw = atan2(host.head.x - d.x, host.head.z - d.z)
            val bob = sin(host.time * 3f + i) * 0.05f
            host.drawModel(models[i % models.size], d.x, d.y - 0.15f + bob, d.z, yaw = yaw, scale = 0.5f, gloss = 0.7f)
            val fx = sin(yaw); val fz = cos(yaw)
            host.glow(d.x + fx * 0.25f, d.y + bob, d.z + fz * 0.25f, 0.07f, rgba(1f, 0.1f, 0.05f), 0.9f)
            host.glow(d.x - fx * 0.3f, d.y - 0.05f + bob, d.z - fz * 0.3f, 0.12f, rgba(0.3f, 0.6f, 1f), 0.6f + 0.2f * sin(host.time * 20f + i))
        }
        for (b in bolts) {
            if (b.fromPlayer) {
                val s = sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz).coerceAtLeast(1e-3f)
                host.glowBeam(b.x, b.y, b.z, b.x - b.vx / s * 0.6f, b.y - b.vy / s * 0.6f, b.z - b.vz / s * 0.6f, 0.008f, playerShot)
            } else {
                // enemy shots: slow balls of red plasma
                host.drawSphere(b.x, b.y, b.z, 0.03f, rgba(1f, 0.85f, 0.75f), unlit = true, shadow = false)
                host.glow(b.x, b.y, b.z, 0.09f, enemyShot, 0.9f)
                host.glow(b.x, b.y, b.z, 0.2f, enemyShot, 0.3f)
            }
        }
        for (b in blasts) {
            val f = 1f - b.t / 0.6f
            host.glow(b.x, b.y, b.z, 0.3f + b.t * 2.2f, rgba(1f, 0.55f, 0.2f), f)
            host.glow(b.x, b.y, b.z, 0.15f + b.t * 1f, rgba(1f, 0.95f, 0.8f), f * f)
        }
        drawBlaster(host, gloss = 0.8f)
        val o = host.pad.aim.origin; val d = host.pad.aim.direction
        host.glow(o.x + d.x * 0.18f, o.y + d.y * 0.18f, o.z + d.z * 0.18f, 0.02f, cyan, 0.8f)
        if (muzzle > 0f) host.glow(o.x + d.x * 0.22f, o.y + d.y * 0.22f, o.z + d.z * 0.22f, 0.09f, playerShot, muzzle / 0.08f)
        if (host.pad.alt) {
            // the energy shield: a translucent disc with a bright rim
            val q = Quat.fromTo(0f, 1f, 0f, d.x, d.y, d.z)
            val cx = o.x + d.x * 0.15f; val cy = o.y + d.y * 0.15f; val cz = o.z + d.z * 0.15f
            host.drawCylinder(cx, cy, cz, 0.3f, 0.006f, rgba(0.25f, 0.75f, 1f, 0.18f + shieldHit), q, unlit = true, shadow = false)
            host.drawTorus(cx, cy, cz, 0.3f, cyan, q, unlit = true, thick = 0.25f)
            host.drawTorus(cx, cy, cz, 0.3f, cyan, q, glow = true, thick = 1.2f)
        }
        if (hurt > 0f) host.glow(host.head.x, host.head.y, host.head.z, 0.3f, rgba(1f, 0.1f, 0.1f), hurt * 1.6f)
    }
}

// ================================================================================ 8. Tênis de Mesa
/** Table tennis in a bright club room, against the computer: the controller is your paddle. Trigger serves. First to 11. */
class PingPongGame(host: GameHost) : QuestGame(host) {
    override val title = "Tênis de Mesa"
    override val needsController = true
    override val look = GameSky.Look(
        zenith = rgba(0.85f, 0.86f, 0.88f), horizon = rgba(0.8f, 0.82f, 0.85f), ground = rgba(0.5f, 0.45f, 0.4f),
        sunDir = floatArrayOf(0.25f, 1f, 0.35f), fog = 0f, objectFog = 0f, light = rgba(0.85f, 0.84f, 0.82f), ambient = rgba(0.55f, 0.55f, 0.58f),
    )
    override val shadowRadius = 3.2f
    override val shadowZ = PingPongBall.TABLE_Z
    private val ball = PingPongBall()
    private var mine = 0; private var theirs = 0
    private var aiX = 0f
    private val rnd = java.util.Random()
    private var pause = 0f
    private val paddle = Vec3(); private val normal = Vec3(); private val up = Vec3()

    override fun start() { status = "Gatilho: sacar. Rebata com o controle como raquete" }

    private fun paddlePose() {
        val p = host.pad.pose
        p.orientation.rotate(Pose.FORWARD, normal)
        p.orientation.rotate(Pose.UP, up)
        // the blade sits above your grip, its face looking where the controller points
        paddle.set(p.position).addScaled(up, 0.12f).addScaled(normal, 0.02f)
    }

    override fun update(dt: Float) {
        paddlePose()
        if (!ball.inPlay) {
            if (pause > 0f) { pause -= dt; return }
            if (host.pad.triggerDown) {
                ball.x = paddle.x; ball.y = max(paddle.y + 0.25f, 1.0f); ball.z = paddle.z - 0.05f
                ball.vx = 0f; ball.vy = 0f; ball.vz = 0f; ball.inPlay = true
                ball.lastHitByPlayer = false; ball.bouncesPlayerSide = 1; ball.bouncesOpponentSide = 0
            }
            return
        }
        val v = host.pad.velocity
        if (ball.paddleHit(paddle.x, paddle.y, paddle.z, normal.x, normal.y, normal.z, v.x, v.y, v.z)) { host.play(host.sTick, ball.x, ball.y, ball.z, 1f, 1.3f); host.pad.buzz(25) }
        // the computer: moves towards the ball and returns it once it has bounced on its side
        aiX += (ball.x - aiX) * min(1f, dt * 4f)
        if (ball.lastHitByPlayer && ball.bouncesOpponentSide == 1 && ball.z < PingPongBall.TABLE_FAR + 0.25f && ball.vz < 0f) {
            if (rnd.nextFloat() < 0.13f) ball.lastHitByPlayer = true   // a miss: the ball goes past
            else {
                ball.opponentReturn((rnd.nextFloat() - 0.5f) * 1.1f, PingPongBall.TABLE_Z + 0.35f + rnd.nextFloat() * 0.85f, 0.9f + rnd.nextFloat() * 0.3f)
                host.play(host.sTick, ball.x, ball.y, ball.z, 0.8f, 1.1f)
            }
        }
        val r = ball.step(dt)
        if (r != null || ball.z > 1.5f || ball.z < PingPongBall.TABLE_FAR - 2.5f) {
            val point = r ?: if (ball.lastHitByPlayer) "Seu ponto" else "Ponto do adversário"
            if (point.startsWith("Seu")) { mine++; host.play(host.sCoin, 0f, 1.2f, -1f, 0.6f) } else theirs++
            score = mine
            ball.inPlay = false; pause = 1f
            status = "$point · você $mine × $theirs computador"
            if (mine >= 11 || theirs >= 11) { over = true; status = if (mine > theirs) "Vitória! $mine × $theirs" else "Derrota: $mine × $theirs" }
        }
    }

    private val white = rgba(1f, 1f, 1f)
    private val wall = rgba(0.86f, 0.87f, 0.9f)
    private val tableBlue = rgba(0.06f, 0.2f, 0.45f)
    private val line = rgba(0.95f, 0.95f, 0.95f)
    private val dark = rgba(0.12f, 0.12f, 0.13f)
    private val hb = Vec3(); private val he = Vec3()

    override fun draw() {
        val z = PingPongBall.TABLE_Z; val ty = PingPongBall.TABLE_Y; val tw = PingPongBall.TABLE_W; val tl = 2.74f
        // the room: wooden floor, pale walls with a dark band, light panels in the ceiling
        val rz = z; val rw = 9f; val rl = 12f; val rh = 3.6f
        host.drawBox(0f, -0.05f, rz, rw, 0.1f, rl, white, gloss = 0.6f, tex = "floor", tile = 1.4f)
        host.drawBox(0f, rh / 2, rz - rl / 2, rw, rh, 0.1f, wall, gloss = 0.1f, tex = "white", tile = 2f)
        host.drawBox(0f, rh / 2, rz + rl / 2, rw, rh, 0.1f, wall, gloss = 0.1f, tex = "white", tile = 2f)
        for (s in floatArrayOf(-1f, 1f)) {
            host.drawBox(s * rw / 2, rh / 2, rz, 0.1f, rh, rl, wall, gloss = 0.1f, tex = "white", tile = 2f)
            host.drawBox(s * (rw / 2 - 0.06f), 0.5f, rz, 0.02f, 1f, rl, rgba(0.15f, 0.22f, 0.4f), gloss = 0.3f, shadow = false)
        }
        host.drawBox(0f, 0.5f, rz - rl / 2 + 0.06f, rw, 1f, 0.02f, rgba(0.15f, 0.22f, 0.4f), gloss = 0.3f, shadow = false)
        host.drawBox(0f, rh, rz, rw, 0.1f, rl, rgba(0.92f, 0.92f, 0.93f), shadow = false)
        for (i in -1..1) for (j in 0..1) {
            host.drawBox(j * 2.6f - 1.3f, rh - 0.06f, rz + i * 3f, 1.4f, 0.02f, 0.5f, rgba(1f, 1f, 0.97f), unlit = true, shadow = false)
            host.glow(j * 2.6f - 1.3f, rh - 0.12f, rz + i * 3f, 0.6f, rgba(1f, 1f, 0.95f), 0.15f)
        }
        // barriers around the court, like in a club
        for (s in floatArrayOf(-1f, 1f)) host.drawBox(s * 2.8f, 0.35f, rz, 0.04f, 0.7f, 6f, rgba(0.1f, 0.15f, 0.32f), gloss = 0.4f)
        // the table: top with white lines, a frame under it, legs, the net with its posts
        host.drawBox(0f, ty - 0.015f, z, tw, 0.03f, tl, tableBlue, gloss = 0.45f)
        host.drawBox(0f, ty - 0.06f, z, tw - 0.1f, 0.06f, tl - 0.1f, dark, gloss = 0.3f)
        host.drawBox(0f, ty + 0.0005f, z, 0.004f, 0.002f, tl, line, unlit = true, shadow = false)
        for (s in floatArrayOf(-1f, 1f)) {
            host.drawBox(s * (tw / 2 - 0.01f), ty + 0.001f, z, 0.02f, 0.002f, tl, line, unlit = true, shadow = false)
            host.drawBox(0f, ty + 0.001f, z + s * (tl / 2 - 0.01f), tw, 0.002f, 0.02f, line, unlit = true, shadow = false)
        }
        for (lx in floatArrayOf(-0.65f, 0.65f)) for (lz in floatArrayOf(z - 1.15f, z + 1.15f)) host.drawBox(lx, (ty - 0.09f) / 2, lz, 0.05f, ty - 0.09f, 0.05f, dark, gloss = 0.6f, metal = 1f)
        for (s in floatArrayOf(-1f, 1f)) host.drawBox(s * (tw / 2 + 0.12f), ty + PingPongBall.NET_H / 2, z, 0.02f, PingPongBall.NET_H, 0.03f, dark, metal = 1f)
        host.drawBox(0f, ty + PingPongBall.NET_H / 2, z, tw + 0.24f, PingPongBall.NET_H, 0.004f, rgba(0.15f, 0.15f, 0.2f, 0.55f))
        host.drawBox(0f, ty + PingPongBall.NET_H - 0.006f, z, tw + 0.24f, 0.012f, 0.006f, white)
        if (ball.inPlay || pause > 0f) host.drawSphere(ball.x, ball.y, ball.z, PingPongBall.R, rgba(1f, 0.55f, 0.12f), gloss = 0.5f)
        // your paddle: red rubber on one face, black on the other, a wooden edge and handle down to your grip
        val q = Quat.fromTo(0f, 1f, 0f, normal.x, normal.y, normal.z)
        host.drawCylinder(paddle.x, paddle.y, paddle.z, PingPongBall.PADDLE_R + 0.003f, 0.008f, rgba(0.75f, 0.55f, 0.35f), q, gloss = 0.4f)
        host.drawCylinder(paddle.x + normal.x * 0.003f, paddle.y + normal.y * 0.003f, paddle.z + normal.z * 0.003f, PingPongBall.PADDLE_R, 0.004f, rgba(0.82f, 0.08f, 0.08f), q, gloss = 0.25f)
        host.drawCylinder(paddle.x - normal.x * 0.003f, paddle.y - normal.y * 0.003f, paddle.z - normal.z * 0.003f, PingPongBall.PADDLE_R, 0.004f, rgba(0.06f, 0.06f, 0.07f), q, gloss = 0.25f)
        hb.set(paddle).addScaled(up, -PingPongBall.PADDLE_R + 0.01f)
        he.set(paddle).addScaled(up, -PingPongBall.PADDLE_R - 0.1f)
        host.drawRounded((hb.x + he.x) / 2, (hb.y + he.y) / 2, (hb.z + he.z) / 2, 0.028f, 0.11f, 0.022f, rgba(0.62f, 0.42f, 0.24f), orientation = host.along(hb.x - he.x, hb.y - he.y, hb.z - he.z), gloss = 0.5f, tex = "wood", tile = 0.2f)
        // the opponent: a simple avatar behind the far end, its paddle following the ball
        val ax = aiX * 0.8f; val az = PingPongBall.TABLE_FAR - 0.7f
        host.drawRounded(ax, 1.25f, az, 0.42f, 0.55f, 0.24f, rgba(0.2f, 0.25f, 0.4f), gloss = 0.4f, soft = true)
        host.drawSphere(ax, 1.68f, az, 0.12f, rgba(0.85f, 0.75f, 0.65f), gloss = 0.3f)
        host.drawEllipsoid(ax, 1.7f, az + 0.08f, 0.2f, 0.07f, 0.1f, rgba(0.05f, 0.05f, 0.08f), gloss = 1f)
        val px = aiX; val py = 1.0f; val pz = PingPongBall.TABLE_FAR - 0.3f
        host.drawBeam(ax + 0.2f, 1.4f, az, px, py, pz - 0.05f, 0.035f, rgba(0.2f, 0.25f, 0.4f), unlit = false)
        host.drawCylinder(px, py, pz, PingPongBall.PADDLE_R, 0.012f, rgba(0.08f, 0.08f, 0.09f), Quat.fromAxisAngle(1f, 0f, 0f, 1.5708f), gloss = 0.3f)
        // a scoreboard beside the table: your points and theirs as blocks
        for (s in 0..1) {
            val n = if (s == 0) mine else theirs
            for (k in 0 until n) host.drawBox(-2.2f + (k % 6) * 0.07f + s * 0.5f, 0.9f + (k / 6) * 0.07f, z, 0.05f, 0.05f, 0.02f, if (s == 0) rgba(0.85f, 0.1f, 0.1f) else dark, unlit = s == 0, shadow = false)
        }
    }
}

// ================================================================================ 9. Pescaria
/**
 * A lake at sunset, from the end of a wooden dock. Swing and release the trigger to cast, strike
 * when the float goes under, hold the other button to reel.
 */
class FishingGame(host: GameHost) : QuestGame(host) {
    override val title = "Pescaria"
    override val needsController = true
    override val look = GameSky.Look(
        zenith = rgba(0.16f, 0.24f, 0.5f), horizon = rgba(1f, 0.6f, 0.36f), ground = rgba(0.08f, 0.1f, 0.12f),
        sunDir = floatArrayOf(-0.15f, 0.07f, -1f), sunColor = rgba(1f, 0.55f, 0.22f), sunSize = 0.035f, clouds = 0.45f, cloudColor = rgba(1f, 0.68f, 0.52f),
        floor = GameSky.FLOOR_WATER, floorY = WATER, floorColor = rgba(0.03f, 0.08f, 0.1f), fog = 0.006f, objectFog = 0.007f,
        light = rgba(1.05f, 0.72f, 0.5f), ambient = rgba(0.4f, 0.37f, 0.45f),
    )
    override val shadowRadius = 4f
    override val shadowZ = 0.5f
    private val line = FishingLine(System.nanoTime())
    private var swing = 0f
    private var wasTrigger = false
    private var showFish = 0f
    private var fishName = ""
    private var lastState = FishingLine.State.IDLE
    private val tip = Vec3()
    private class Ripple(val x: Float, val z: Float, var t: Float)
    private val ripples = ArrayList<Ripple>()
    private val rnd = java.util.Random()

    override fun start() { line.waterY = WATER; status = "Segure o gatilho, balance o controle e solte para lançar" }

    private fun rodTip() {
        val p = host.pad.pose
        p.orientation.rotate(Pose.FORWARD, tip)
        tip.scale(1.5f).add(p.position).add(LIFT)
        // the rod bends towards a fighting fish
        if (line.state == FishingLine.State.HOOKED) {
            val dx = line.bx - tip.x; val dz = line.bz - tip.z; val l = hypot(dx, dz).coerceAtLeast(0.1f)
            tip.x += dx / l * line.tension * 0.25f; tip.z += dz / l * line.tension * 0.25f; tip.y -= line.tension * 0.35f
        }
    }

    private fun splash(n: Int, up: Float) =
        host.particles.burst(Particles.BLOB, line.bx, WATER + 0.02f, line.bz, n, 1.3f, SPRAY, 0.6f, 0.01f, gravity = 6f, up = up)

    override fun update(dt: Float) {
        rodTip()
        val pad = host.pad
        if (pad.trigger) swing = max(swing, pad.velocity.length())
        val idle = line.state == FishingLine.State.IDLE || line.state == FishingLine.State.CAUGHT || line.state == FishingLine.State.ESCAPED
        var strike = false
        val released = wasTrigger && !pad.trigger
        wasTrigger = pad.trigger
        if (idle && released) {
            // the cast: along the rod's aim, harder with a faster swing (phones that only turn: a fixed cast)
            val d = pad.aim.direction
            val h = hypot(d.x, d.z).coerceAtLeast(0.1f)
            val power = (4f + swing * 2.2f).coerceIn(5f, 13f)
            line.cast(tip.x, tip.y, tip.z, d.x / h * power, 3f, d.z / h * power)
            host.play(host.sTick, tip.x, tip.y, tip.z, 0.6f, 0.6f)
            swing = 0f
        } else if (!idle && pad.triggerDown) strike = true
        if (!pad.trigger) swing = 0f
        val msg = line.update(dt, host.pad.pose.position.x, host.pad.pose.position.z, pad.alt, strike)
        if (line.state != lastState) {
            if (line.state == FishingLine.State.WAITING && lastState == FishingLine.State.FLYING) {
                host.play(host.sSplash, line.bx, WATER, line.bz, 0.5f, 1.3f); ripples += Ripple(line.bx, line.bz, 0f); splash(10, 1.2f)
            }
            if (line.state == FishingLine.State.BITE) {
                pad.buzz(200); host.play(host.sSplash, line.bx, WATER, line.bz, 0.8f, 0.8f); ripples += Ripple(line.bx, line.bz, 0f); splash(14, 1.5f)
            }
            lastState = line.state
        }
        if (line.state == FishingLine.State.HOOKED) {
            if (host.time % 0.3f < dt) pad.buzz((line.tension * 60).toInt() + 10)
            // the fish thrashing at the surface
            if (rnd.nextFloat() < dt * 6f) { ripples += Ripple(line.bx, line.bz, 0f); splash(5, 1f) }
        }
        if (line.state == FishingLine.State.WAITING && rnd.nextFloat() < dt * 0.5f) ripples += Ripple(line.bx, line.bz, 0f)
        if (msg != null) status = msg
        if (line.state == FishingLine.State.CAUGHT && showFish <= 0f && fishName != (line.fish?.name ?: "")) {
            val f = line.fish!!
            score += (f.weightKg * 10).toInt(); fishName = f.name; showFish = 3.5f; host.play(host.sWin, host.head.x, host.head.y, host.head.z - 0.5f, 0.6f)
            host.particles.burst(Particles.BLOB, host.head.x, host.head.y - 0.25f, host.head.z - 0.55f, 16, 1.4f, SPRAY, 0.7f, 0.008f, gravity = 7f)
        }
        if (showFish > 0f) { showFish -= dt; if (showFish <= 0f) fishName = "" }
        ripples.forEach { it.t += dt }; ripples.removeAll { it.t > 2f }
        // embers rising from the campfire, fireflies over the bank
        if (rnd.nextFloat() < dt * 12f) host.particles.emit(Particles.SPARK, -2.2f + (rnd.nextFloat() - 0.5f) * 0.3f, 0.25f, 3.6f + (rnd.nextFloat() - 0.5f) * 0.3f,
            (rnd.nextFloat() - 0.5f) * 0.3f, 0.6f + rnd.nextFloat() * 0.6f, (rnd.nextFloat() - 0.5f) * 0.3f, EMBER, 1.6f, 0.006f, -0.1f)
        if (rnd.nextFloat() < dt * 3f) host.particles.emit(Particles.SPARK, (rnd.nextFloat() - 0.5f) * 10f, 0.5f + rnd.nextFloat() * 1.5f, 4f + rnd.nextFloat() * 4f,
            (rnd.nextFloat() - 0.5f) * 0.3f, (rnd.nextFloat() - 0.5f) * 0.2f, (rnd.nextFloat() - 0.5f) * 0.3f, FIREFLY, 3f, 0.01f, 0f)
        if (line.state == FishingLine.State.HOOKED) status = "Tensão: ${(line.tension * 100).toInt()}% · distância: ${line.distance.toInt()} m · segure o outro botão para recolher"
    }

    private val white = rgba(1f, 1f, 1f)
    private val post = rgba(0.3f, 0.2f, 0.14f)
    private val dir = Vec3(); private val gEnd = Vec3(); private val reel = Vec3(); private val mid = Vec3()

    override fun draw() {
        // the dock you stand on, its posts going into the water
        host.drawBox(0f, -0.06f, 1.6f, 1.8f, 0.12f, 6f, white, gloss = 0.3f, tex = "wood", tile = 0.9f)
        for (z in floatArrayOf(-1.2f, 0.8f, 2.8f, 4.4f)) for (x in floatArrayOf(-0.95f, 0.95f)) {
            host.drawCylinder(x, -0.3f, z, 0.09f, 0.75f, post, tex = "bark", tile = 0.5f)
            if (z < 1f) host.drawCylinder(x, 0.35f, z, 0.07f, 0.7f, post, tex = "bark", tile = 0.5f)
        }
        host.drawBox(-0.95f, 0.68f, -0.2f, 0.07f, 0.06f, 2.1f, post, tex = "wood-dark", tile = 1f)
        host.drawBox(0.95f, 0.68f, -0.2f, 0.07f, 0.06f, 2.1f, post, tex = "wood-dark", tile = 1f)
        // the bank behind you: grass, a tent, the campfire glowing, trees
        host.drawBox(0f, -0.3f, 7.5f, 24f, 0.6f, 7f, white, gloss = 0.05f, tex = "grass", tile = 1.5f)
        host.drawModel("survival/tent", -3.5f, 0f, 5.2f, yaw = 0.6f, scale = 1.6f)
        host.drawModel("survival/campfire-pit", -2.2f, 0f, 3.6f, scale = 1.2f)
        host.glow(-2.2f, 0.25f, 3.6f, 0.35f, rgba(1f, 0.5f, 0.15f), 0.7f + 0.3f * sin(host.time * 13f) * sin(host.time * 7f))
        host.glow(-2.2f, 0.3f, 3.6f, 1.2f, rgba(1f, 0.45f, 0.15f), 0.15f)
        host.drawModel("survival/signpost", 1.6f, 0f, 4.6f, yaw = -0.4f, scale = 1.2f)
        for (i in 0..7) host.drawModel("survival/patch-grass-large", -5f + i * 1.4f, 0f, 4.4f + (i % 3) * 0.6f, yaw = i.toFloat(), scale = 1.2f, shadow = false)
        val rnd = java.util.Random(5)
        for (i in 0 until 12) {
            val x = -9f + i * 1.6f + rnd.nextFloat()
            host.drawModel(if (i % 3 == 0) "survival/tree-tall" else "survival/tree", x, 0f, 6.5f + rnd.nextFloat() * 3f, yaw = rnd.nextFloat() * 6f, scale = 3f + rnd.nextFloat() * 2f)
        }
        // the far shore: forested hills and mountains in the haze of the sunset
        for (i in 0 until 26) {
            val a = -1.7f + i * 0.13f
            val r = 70f + rnd.nextFloat() * 25f
            host.drawModel(if (i % 4 == 0) "survival/tree-tall" else "survival/tree", sin(a) * r, WATER, -cos(a) * r, yaw = rnd.nextFloat() * 6f, scale = 9f + rnd.nextFloat() * 5f, shadow = false)
        }
        for (i in 0 until 8) {
            val a = -1.4f + i * 0.4f + rnd.nextFloat() * 0.2f
            val r = 75f + rnd.nextFloat() * 15f
            host.drawEllipsoid(sin(a) * r, WATER, -cos(a) * r, 40f + rnd.nextFloat() * 20f, 9f + rnd.nextFloat() * 6f, 25f, rgba(0.14f, 0.2f, 0.12f), yaw = a, gloss = 0f)
        }
        for (i in 0 until 5) {
            val a = -1.1f + i * 0.5f
            val s = 80f + rnd.nextFloat() * 60f
            host.drawMesh(host.cone, sin(a) * 260f, WATER + s * 0.22f, -cos(a) * 260f, s * 2f, s * 0.45f, s * 1.5f, rgba(0.3f, 0.28f, 0.38f), gloss = 0f, shadow = false)
        }
        // ripples on the water where the float went in or the fish fights
        for (r in ripples) {
            val f = 1f - r.t / 2f
            host.drawTorus(r.x, WATER + 0.005f, r.z, 0.05f + r.t * 0.35f, rgba(0.85f, 0.9f, 0.95f, 0.5f * f), thick = 0.25f)
        }
        // the rod: cork grip, a reel, the dark blank tapering to the tip
        val p = host.pad.pose.position
        dir.set(tip).sub(p).normalize()
        gEnd.set(p).addScaled(dir, 0.25f)
        host.drawBeam(p.x - dir.x * 0.08f, p.y - dir.y * 0.08f, p.z - dir.z * 0.08f, gEnd.x, gEnd.y, gEnd.z, 0.016f, rgba(0.75f, 0.6f, 0.42f), unlit = false, gloss = 0.1f)
        reel.set(p).addScaled(dir, 0.08f); reel.y -= 0.045f
        host.drawCylinder(reel.x, reel.y, reel.z, 0.035f, 0.03f, rgba(0.25f, 0.26f, 0.3f), host.along(-dir.z, 0f, dir.x), gloss = 0.9f, metal = 1f)
        mid.set(gEnd).lerp(tip, 0.5f)
        host.drawBeam(gEnd.x, gEnd.y, gEnd.z, mid.x, mid.y, mid.z, 0.009f, rgba(0.1f, 0.1f, 0.12f), unlit = false, gloss = 0.9f)
        host.drawBeam(mid.x, mid.y, mid.z, tip.x, tip.y, tip.z, 0.005f, rgba(0.1f, 0.1f, 0.12f), unlit = false, gloss = 0.9f)
        if (line.state != FishingLine.State.IDLE) {
            val dip = line.bobberDip() * 0.05f
            val by = line.by + 0.03f - dip
            // the line sags a little in the middle
            val sx = (tip.x + line.bx) / 2; val sz = (tip.z + line.bz) / 2
            val sy = (tip.y + by) / 2 - (if (line.state == FishingLine.State.HOOKED) 0.05f else 0.3f) * (1f - line.tension)
            val lc = rgba(0.95f, 0.95f, 0.95f, 0.7f)
            host.drawBeam(tip.x, tip.y, tip.z, sx, sy, sz, 0.0012f, lc)
            host.drawBeam(sx, sy, sz, line.bx, by, line.bz, 0.0012f, lc)
            host.drawSphere(line.bx, by, line.bz, 0.03f, rgba(0.95f, 0.12f, 0.1f), gloss = 0.7f)
            host.drawSphere(line.bx, by + 0.025f, line.bz, 0.022f, WHITE, gloss = 0.7f)
            host.drawCylinder(line.bx, by + 0.06f, line.bz, 0.004f, 0.04f, rgba(0.95f, 0.12f, 0.1f))
        }
        if (showFish > 0f) {
            val big = fishName == "Pirarucu" || fishName == "Dourado"
            val wiggle = sin(host.time * 14f) * 0.3f * (showFish / 3.5f)
            host.drawModel(if (big) "survival/fish-large" else "survival/fish", host.head.x, host.head.y - 0.25f, host.head.z - 0.55f, yaw = host.time * 1.2f + wiggle, scale = 1.4f, gloss = 0.9f)
        }
    }

    companion object {
        const val WATER = -0.45f
        private val LIFT = Vec3(0f, 0.28f, 0f)
        private val SPRAY = rgba(0.75f, 0.85f, 0.9f, 0.8f)
        private val EMBER = rgba(1f, 0.55f, 0.15f)
        private val FIREFLY = rgba(0.85f, 1f, 0.35f)
    }
}

// ================================================================================ 10. Pequeno Herói
/**
 * A little world on a mossy stump in a misty forest of giant trees: walk the hero with the
 * stick, jump with the trigger, collect the coins, reach the flag.
 */
class DioramaGame(host: GameHost) : QuestGame(host) {
    override val title = "Pequeno Herói"
    override val needsController = true
    override val look = GameSky.Look(
        zenith = rgba(0.2f, 0.32f, 0.3f), horizon = rgba(0.55f, 0.62f, 0.5f), ground = rgba(0.12f, 0.16f, 0.1f),
        sunDir = floatArrayOf(0.4f, 0.7f, -0.55f), sunColor = rgba(1f, 0.85f, 0.55f), sunSize = 0f,
        floor = GameSky.FLOOR_PLAIN, floorY = -0.01f, floorColor = rgba(0.16f, 0.22f, 0.1f), fog = 0.05f, objectFog = 0.05f,
        light = rgba(1.05f, 0.92f, 0.72f), ambient = rgba(0.36f, 0.43f, 0.38f),
    )
    override val shadowRadius = 2.2f
    override val shadowZ = -1.05f
    private val world = Diorama.level1()
    private val cell = 0.08f
    private val ox get() = -world.w * cell / 2
    private val oy = 0.78f
    private val oz = -1.05f
    private val rnd = java.util.Random()
    private var lastCoins = 0

    override fun start() { status = "Analógico (ou segure B e aponte): andar · gatilho: pular. Pegue as moedas e chegue à bandeira" }

    private fun gx(cx: Float) = ox + cx * cell
    private fun gz(cz: Float) = oz - world.d * cell / 2 + cz * cell

    override fun update(dt: Float) {
        val pad = host.pad
        var mx = pad.stickX.coerceIn(-1f, 1f); var mz = pad.stickY.coerceIn(-1f, 1f)
        // controllers without a stick (the HyperConnect phone): hold the other button and point where to go
        if (abs(mx) + abs(mz) < 0.1f && pad.alt) {
            val d = pad.aim.direction
            val h = hypot(d.x, d.z)
            if (h > 0.2f) { mx = d.x / h; mz = d.z / h }
        }
        val m = world.step(dt, mx, mz, pad.triggerDown)
        if (m != null) {
            status = m
            when {
                m.startsWith("Moeda") -> host.play(host.sCoin, gx(world.x), oy + world.y * cell, gz(world.z))
                m.startsWith("Caiu") -> host.play(host.sMiss, gx(world.x), oy, gz(world.z), 0.6f)
                m.startsWith("Chegou") -> {
                    host.play(host.sWin, gx(world.x), oy + world.y * cell, gz(world.z)); over = true; status = "Você chegou! ${world.collected} moedas, ${world.falls} quedas"
                    host.particles.burst(Particles.SPARK, gx(world.x), oy + world.y * cell + 0.1f, gz(world.z), 50, 1.2f, GOLD, 1.2f, 0.004f, gravity = 0.5f, up = 0.6f)
                }
            }
        }
        if (world.collected > lastCoins) {
            lastCoins = world.collected
            host.particles.burst(Particles.SPARK, gx(world.x), oy + world.y * cell + 0.05f, gz(world.z), 20, 0.6f, GOLD, 0.6f, 0.003f, gravity = 0.3f, up = 0.3f)
        }
        // fireflies drifting through the forest
        if (rnd.nextFloat() < dt * 6f) host.particles.emit(Particles.SPARK, (rnd.nextFloat() - 0.5f) * 5f, 0.3f + rnd.nextFloat() * 2f, -1f + (rnd.nextFloat() - 0.5f) * 5f,
            (rnd.nextFloat() - 0.5f) * 0.15f, (rnd.nextFloat() - 0.5f) * 0.1f, (rnd.nextFloat() - 0.5f) * 0.15f, FIREFLY, 4f, 0.008f, 0f, 0f)
        score = world.collected * 100 + (if (world.won) 500 else 0) - world.falls * 20
    }

    private val white = rgba(1f, 1f, 1f)
    private val bark = rgba(0.62f, 0.52f, 0.45f)

    override fun draw() {
        val r = java.util.Random(3)
        // giant trunks all around, their tops lost in the mist; rocks at their feet
        for (i in 0 until 9) {
            val a = i / 9f * 6.28f + r.nextFloat() * 0.4f
            val d = 3.2f + r.nextFloat() * 4f
            val x = sin(a) * d; val z = oz - cos(a) * d
            val rad = 0.5f + r.nextFloat() * 0.6f
            if (z > 0.6f && abs(x) < 1.5f) continue
            host.drawCylinder(x, 6f, z, rad, 12f, bark, gloss = 0.05f, tex = "bark", tile = 1.5f, shadow = d < 5f)
            host.drawCylinder(x, 0.25f, z, rad * 1.35f, 0.5f, bark, gloss = 0.05f, tex = "bark", tile = 1.5f, shadow = false)
            host.drawModel("survival/rock-${"abc"[i % 3]}", x + rad * 1.4f, 0f, z + 0.3f, yaw = a, scale = 1.2f)
        }
        for (i in 0 until 16) {
            val a = r.nextFloat() * 6.28f; val d = 1.4f + r.nextFloat() * 3.5f
            host.drawModel(if (i % 3 == 0) "platformer/mushrooms" else "survival/grass-large", sin(a) * d, 0f, oz - cos(a) * d, yaw = a, scale = 0.9f + r.nextFloat(), shadow = false)
        }
        for (i in 0 until 6) {
            val a = i * 1.05f
            host.drawModel("platformer/tree-pine", sin(a) * 14f, 0f, oz - cos(a) * 14f, scale = 9f + i, shadow = false)
        }
        // shafts of light through the canopy
        for (i in 0 until 4) {
            val x = -2f + i * 1.4f; val z = oz - 1.5f + (i % 2) * 2f
            host.drawMesh(host.cone, x - 0.9f, 3.5f, z + 0.8f, 1.6f, 9f, 1.6f, rgba(1f, 0.92f, 0.7f, 0.05f), orientation = Quat.fromAxisAngle(0.45f, 0f, -0.9f, 0.35f), unlit = true, shadow = false).additive = true
        }
        // the stump the little world sits on, moss on top
        val sw = world.w * cell + 0.35f; val sd = world.d * cell + 0.35f
        val sr = max(sw, sd) * 0.6f
        host.drawCylinder(0f, (oy - 0.42f) / 2, oz, sr, oy - 0.42f, bark, gloss = 0.05f, tex = "bark", tile = 0.8f)
        host.drawCylinder(0f, oy - 0.41f, oz, sr * 0.98f, 0.03f, white, gloss = 0.05f, tex = "moss", tile = 0.5f)
        for (k in 0 until 5) {
            val a = k * 1.256f
            host.drawCylinder(sin(a) * sr * 0.9f, 0.08f, oz - cos(a) * sr * 0.9f, sr * 0.22f, 0.16f, bark, tex = "bark", tile = 0.8f)
        }
        for (zz in 0 until world.d) for (xx in 0 until world.w) {
            val h = world.heights[zz * world.w + xx]
            if (h == 0) continue
            // earth under the grass top
            host.drawBox(gx(xx + 0.5f), oy - 0.4f / 2 + (h - 1) * cell / 2, gz(zz + 0.5f), cell, 0.4f + (h - 1) * cell, cell, rgba(0.45f, 0.32f, 0.2f), tex = "rock", tile = 0.3f)
            host.drawModel("platformer/block-grass", gx(xx + 0.5f), oy + (h - 1) * cell, gz(zz + 0.5f), scale = cell * 0.93f)
        }
        for (c in world.coins) {
            val cx = gx(c[0] + 0.5f); val cy = oy + world.heights[c[1] * world.w + c[0]] * cell + 0.01f + sin(host.time * 3f + c[0]) * 0.006f; val cz = gz(c[1] + 0.5f)
            host.drawModel("platformer/coin-gold", cx, cy, cz, yaw = host.time * 3f, scale = cell * 0.8f, gloss = 1f)
            host.glow(cx, cy + cell * 0.35f, cz, cell * 0.5f, GOLD, 0.25f)
        }
        val fx = gx(world.goalX + 0.5f); val fy = oy + world.heights[world.goalZ * world.w + world.goalX] * cell; val fz = gz(world.goalZ + 0.5f)
        host.drawModel("platformer/flag", fx, fy, fz, scale = cell)
        host.glow(fx, fy + cell, fz, cell * 1.2f, rgba(1f, 0.95f, 0.6f), 0.2f + 0.1f * sin(host.time * 2f))
        host.drawModel("platformer/tree-pine", gx(1.5f), oy + cell, gz(4.5f), scale = cell)
        host.drawModel("platformer/flowers", gx(7.5f), oy + 2 * cell, gz(1.5f), scale = cell)
        host.drawModel("platformer/mushrooms", gx(12.5f), oy + 3 * cell, gz(0.5f), scale = cell)
        host.drawModel("platformer/character-oobi", gx(world.x), oy + world.y * cell, gz(world.z), yaw = world.facing, scale = cell * 0.9f, gloss = 0.4f)
        // a soft glow around the hero so you always find him
        host.glow(gx(world.x), oy + world.y * cell + cell * 0.5f, gz(world.z), cell * 0.9f, rgba(0.6f, 0.9f, 1f), 0.15f)
    }

    companion object {
        private val FIREFLY = rgba(0.85f, 1f, 0.4f)
        private val GOLD = rgba(1f, 0.85f, 0.3f)
    }
}
