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

private val RED_GLASS = floatArrayOf(0.95f, 0.15f, 0.12f, 1f)
private val DARK = floatArrayOf(0.08f, 0.08f, 0.09f, 1f)

/** The blaster in the controller's hand, and where its barrel points. */
private fun drawBlaster(host: GameHost) {
    val p = host.pad.pose
    host.drawModel("blaster/blaster-a", p.position.x, p.position.y, p.position.z, scale = 0.9f, orientation = p.orientation)
}

// ================================================================================ 6. Tempo Parado
/** Time moves only when you move. Red figures walk in and fire slow bullets: shoot them, dodge the bullets. */
class SlowTimeGame(host: GameHost) : QuestGame(host) {
    override val title = "Tempo Parado"
    override val needsController = true
    override val sky = floatArrayOf(0.92f, 0.93f, 0.95f, 1f)
    private class Enemy(var x: Float, var z: Float, val seed: Int) { var cooldown = 2f + seed % 3; var alive = true; var shatter = 0f }
    private class Shard(var x: Float, var y: Float, var z: Float, val vx: Float, var vy: Float, val vz: Float, var t: Float)
    private val enemies = ArrayList<Enemy>()
    private val bullets = ArrayList<Bolt>()
    private val shards = ArrayList<Shard>()
    private var wave = 0
    private var scale = 0.05f
    private var tracer = 0f
    private val tracerEnd = Vec3()
    private val rnd = java.util.Random()

    override fun start() { nextWave(); status = "O tempo só anda quando você se mexe. Mire com o controle e aperte o gatilho" }

    private fun nextWave() {
        wave++
        repeat(2 + wave) { i ->
            val a = (rnd.nextFloat() - 0.5f) * 2.4f
            val r = 6f + rnd.nextFloat() * 4f
            enemies += Enemy(sin(a) * r, -cos(a) * r, i)
        }
    }

    override fun update(dt: Float) {
        // the time scale: from how fast the head and the controller are moving
        val target = SlowTime.scale(host.headSpeed, host.pad.velocity.length())
        scale += (target - scale) * min(1f, dt * 10f)
        val g = dt * scale
        if (host.pad.triggerDown) shoot()
        for (e in enemies) {
            if (!e.alive) { e.shatter += g; continue }
            val dx = host.head.x - e.x; val dz = host.head.z - e.z
            val d = hypot(dx, dz)
            if (d > 3f) { e.x += dx / d * 1.1f * g; e.z += dz / d * 1.1f * g }
            e.cooldown -= g
            if (e.cooldown <= 0f && d < 9f) {
                e.cooldown = 2.5f + rnd.nextFloat() * 2f
                // aimed at the head where it is now: it travels slowly, you can step aside
                val sx = e.x + dx / d * 0.35f; val sy = 1.35f; val sz = e.z + dz / d * 0.35f
                val tx = host.head.x - sx; val ty = host.head.y - sy; val tz = host.head.z - sz
                val l = sqrt(tx * tx + ty * ty + tz * tz)
                bullets += Bolt(sx, sy, sz, tx / l * 5f, ty / l * 5f, tz / l * 5f, false)
            }
        }
        for (b in bullets) {
            b.step(g)
            if (!b.fromPlayer && (b.x - host.head.x) * (b.x - host.head.x) + (b.y - host.head.y) * (b.y - host.head.y) + (b.z - host.head.z) * (b.z - host.head.z) < 0.17f * 0.17f) {
                over = true; status = "Atingido! Onda $wave, $score derrubados"; host.play(host.sMiss, host.head.x, host.head.y, host.head.z)
            }
        }
        bullets.removeAll { it.life <= 0f }
        shards.forEach { it.t += g; it.vy -= 9f * g; it.x += it.vx * g; it.y += it.vy * g; it.z += it.vz * g }
        shards.removeAll { it.t > 2.5f || it.y < 0f }
        enemies.removeAll { !it.alive && it.shatter > 0.1f }
        if (tracer > 0f) tracer -= dt
        if (!over) {
            status = "Onda $wave · tempo ${(scale * 100).toInt()}%"
            if (enemies.none { it.alive }) { score += 0; nextWave(); host.play(host.sWin, 0f, 1.5f, -1f, 0.5f) }
        }
    }

    private fun shoot() {
        val r = host.pad.aim
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
        tracer = 0.08f
        tracerEnd.set(r.origin).addScaled(r.direction, best)
        if (shotBullet != null) { bullets.remove(shotBullet); return }
        hit?.let { e ->
            e.alive = false; score++
            host.play(host.sBoom, e.x, 1.2f, e.z, 0.7f, 1.5f)
            repeat(14) { shards += Shard(e.x, 0.4f + rnd.nextFloat() * 1.3f, e.z, (rnd.nextFloat() - 0.5f) * 3f, rnd.nextFloat() * 2f, (rnd.nextFloat() - 0.5f) * 3f, 0f) }
        }
    }

    override fun draw() {
        host.drawBox(0f, -0.01f, -3f, 30f, 0.02f, 30f, floatArrayOf(0.97f, 0.97f, 0.98f, 1f), gloss = 0.1f)
        val m = host.model("chars/character-male-a")
        val h = m?.let { it.boundsMax[1] - it.boundsMin[1] }?.takeIf { it > 0.01f } ?: 1f
        for (e in enemies) if (e.alive) host.drawModel("chars/character-male-a", e.x, 0f, e.z, yaw = atan2(host.head.x - e.x, host.head.z - e.z), scale = 1.75f / h, tint = RED_GLASS)
        for (b in bullets) host.drawSphere(b.x, b.y, b.z, 0.035f, DARK, gloss = 0.9f)
        for (s in shards) host.drawBox(s.x, s.y, s.z, 0.08f, 0.08f, 0.08f, RED_GLASS, yaw = s.t * 5f)
        drawBlaster(host)
        if (tracer > 0f) { val o = host.pad.aim.origin; host.drawBeam(o.x, o.y, o.z, tracerEnd.x, tracerEnd.y, tracerEnd.z, 0.004f, floatArrayOf(0.2f, 0.2f, 0.2f, 0.7f)) }
    }
}

// ================================================================================ 7. Caçador de Drones
/** Drones circle and fire: shoot them with the trigger, hold the other button for a shield. */
class DroneGame(host: GameHost) : QuestGame(host) {
    override val title = "Caçador de Drones"
    override val needsController = true
    override val sky = floatArrayOf(0.03f, 0.04f, 0.1f, 1f)
    private val drones = ArrayList<Drone>()
    private val bolts = ArrayList<Bolt>()
    private class Blast(val x: Float, val y: Float, val z: Float, var t: Float)
    private val blasts = ArrayList<Blast>()
    private var wave = 0
    private var lives = 3
    private val models = listOf("space/craft_speederA", "space/craft_speederB", "space/craft_racer")
    private var hurt = 0f

    override fun start() { nextWave(); status = "Gatilho: atirar. Outro botão: escudo" }

    private fun nextWave() { wave++; repeat(2 + wave) { drones += Drone(System.nanoTime() + it) } }

    override fun update(dt: Float) {
        val pad = host.pad
        if (pad.triggerDown && !pad.alt) {
            val r = pad.aim
            bolts += Bolt(r.origin.x, r.origin.y, r.origin.z, r.direction.x * 30f, r.direction.y * 30f, r.direction.z * 30f, true)
            host.play(host.sShoot, r.origin.x, r.origin.y, r.origin.z, 0.8f, 1.4f); pad.buzz(20)
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
                if (hit != null) { drones.remove(hit); blasts += Blast(hit.x, hit.y, hit.z, 0f); score += 10; host.play(host.sBoom, hit.x, hit.y, hit.z); it.remove(); continue }
            } else {
                if (pad.alt && (b.x - shieldC.x) * (b.x - shieldC.x) + (b.y - shieldC.y) * (b.y - shieldC.y) + (b.z - shieldC.z) * (b.z - shieldC.z) < 0.3f * 0.3f) {
                    host.play(host.sTick, b.x, b.y, b.z); pad.buzz(15); it.remove(); continue
                }
                if ((b.x - host.head.x) * (b.x - host.head.x) + (b.y - host.head.y + 0.25f) * (b.y - host.head.y + 0.25f) + (b.z - host.head.z) * (b.z - host.head.z) < 0.3f * 0.3f) {
                    lives--; hurt = 0.5f; host.play(host.sMiss, host.head.x, host.head.y, host.head.z); pad.buzz(120); it.remove(); continue
                }
            }
            if (b.life <= 0f) it.remove()
        }
        blasts.forEach { it.t += dt }; blasts.removeAll { it.t > 0.5f }
        if (hurt > 0f) hurt -= dt
        if (drones.isEmpty()) nextWave()
        status = "Onda $wave · vidas $lives"
        if (lives <= 0) { over = true; status = "Fim de jogo na onda $wave" }
    }

    override fun draw() {
        // a round platform floating in space, with stars
        host.drawCylinder(0f, -0.05f, 0f, 2.2f, 0.1f, floatArrayOf(0.2f, 0.22f, 0.3f, 1f))
        val rnd = java.util.Random(9)
        repeat(120) {
            val a = rnd.nextFloat() * 6.28f; val e = rnd.nextFloat() * 1.4f - 0.2f
            host.drawSphere(cos(a) * cos(e) * 60f, sin(e) * 60f + 1.6f, sin(a) * cos(e) * 60f, 0.12f, floatArrayOf(1f, 1f, 1f, 1f), unlit = true)
        }
        for ((i, d) in drones.withIndex()) host.drawModel(models[i % models.size], d.x, d.y - 0.15f, d.z, yaw = atan2(host.head.x - d.x, host.head.z - d.z), scale = 0.5f)
        for (b in bolts) {
            val c = if (b.fromPlayer) floatArrayOf(0.3f, 0.9f, 1f, 1f) else floatArrayOf(1f, 0.25f, 0.2f, 1f)
            val s = sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz).coerceAtLeast(1e-3f)
            val len = if (b.fromPlayer) 0.5f else 0.2f
            host.drawBeam(b.x, b.y, b.z, b.x - b.vx / s * len, b.y - b.vy / s * len, b.z - b.vz / s * len, 0.012f, c)
        }
        for (b in blasts) host.drawSphere(b.x, b.y, b.z, 0.2f + b.t * 1.5f, floatArrayOf(1f, 0.6f, 0.2f, 1f - b.t * 2f), unlit = true)
        drawBlaster(host)
        if (host.pad.alt) {
            val o = host.pad.aim.origin; val d = host.pad.aim.direction
            val q = Quat.fromTo(0f, 1f, 0f, d.x, d.y, d.z)
            host.drawCylinder(o.x + d.x * 0.15f, o.y + d.y * 0.15f, o.z + d.z * 0.15f, 0.3f, 0.01f, floatArrayOf(0.3f, 0.8f, 1f, 0.45f), q, unlit = true)
        }
        if (hurt > 0f) host.drawSphere(host.head.x, host.head.y, host.head.z, 0.3f, floatArrayOf(1f, 0.1f, 0.1f, hurt), unlit = true)
    }
}

// ================================================================================ 8. Tênis de Mesa
/** Table tennis against the computer: the controller is your paddle. Trigger serves. First to 11. */
class PingPongGame(host: GameHost) : QuestGame(host) {
    override val title = "Tênis de Mesa"
    override val needsController = true
    private val ball = PingPongBall()
    private var mine = 0; private var theirs = 0
    private var aiX = 0f
    private val rnd = java.util.Random()
    private var pause = 0f
    private val paddle = Vec3(); private val normal = Vec3()

    override fun start() { status = "Gatilho: sacar. Rebata com o controle como raquete" }

    private fun paddlePose() {
        val p = host.pad.pose
        p.orientation.rotate(Pose.FORWARD, normal)
        paddle.set(p.position).addScaled(normal, 0.12f)
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

    override fun draw() {
        val z = PingPongBall.TABLE_Z
        host.drawBox(0f, PingPongBall.TABLE_Y - 0.02f, z, PingPongBall.TABLE_W, 0.04f, 2.74f, floatArrayOf(0.1f, 0.3f, 0.55f, 1f), gloss = 0.3f)
        host.drawBox(0f, PingPongBall.TABLE_Y + 0.001f, z, 0.02f, 0.002f, 2.74f, floatArrayOf(1f, 1f, 1f, 1f), unlit = true)
        host.drawBox(0f, PingPongBall.TABLE_Y + PingPongBall.NET_H / 2, z, PingPongBall.TABLE_W + 0.3f, PingPongBall.NET_H, 0.01f, floatArrayOf(0.9f, 0.9f, 0.9f, 0.6f))
        for (lx in floatArrayOf(-0.65f, 0.65f)) for (lz in floatArrayOf(z - 1.2f, z + 1.2f)) host.drawBox(lx, PingPongBall.TABLE_Y / 2, lz, 0.05f, PingPongBall.TABLE_Y, 0.05f, DARK)
        if (ball.inPlay || pause > 0f) host.drawSphere(ball.x, ball.y, ball.z, PingPongBall.R, floatArrayOf(1f, 0.6f, 0.15f, 1f), gloss = 0.5f)
        // your paddle and the computer's
        val q = Quat.fromTo(0f, 1f, 0f, normal.x, normal.y, normal.z)
        host.drawCylinder(paddle.x, paddle.y, paddle.z, PingPongBall.PADDLE_R, 0.012f, floatArrayOf(0.8f, 0.1f, 0.1f, 1f), q)
        host.drawCylinder(aiX, 1.0f, PingPongBall.TABLE_FAR - 0.3f, PingPongBall.PADDLE_R, 0.012f, floatArrayOf(0.15f, 0.15f, 0.15f, 1f), Quat.fromAxisAngle(1f, 0f, 0f, 1.5708f))
    }
}

// ================================================================================ 9. Pescaria
/** A lake at sunset. Swing and release the trigger to cast, strike when the float goes under, hold the other button to reel. */
class FishingGame(host: GameHost) : QuestGame(host) {
    override val title = "Pescaria"
    override val needsController = true
    override val sky = floatArrayOf(0.98f, 0.74f, 0.55f, 1f)
    private val line = FishingLine(System.nanoTime())
    private var swing = 0f
    private var wasTrigger = false
    private var showFish = 0f
    private var fishName = ""
    private var lastState = FishingLine.State.IDLE
    private val tip = Vec3()

    override fun start() { line.waterY = 0f; status = "Segure o gatilho, balance o controle e solte para lançar" }

    private fun rodTip() {
        val p = host.pad.pose
        p.orientation.rotate(Pose.FORWARD, tip)
        tip.scale(1.4f).add(p.position).add(Vec3(0f, 0.25f, 0f))
    }

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
            if (line.state == FishingLine.State.WAITING && lastState == FishingLine.State.FLYING) host.play(host.sSplash, line.bx, 0f, line.bz, 0.5f, 1.3f)
            if (line.state == FishingLine.State.BITE) { pad.buzz(200); host.play(host.sSplash, line.bx, 0f, line.bz, 0.8f, 0.8f) }
            lastState = line.state
        }
        if (line.state == FishingLine.State.HOOKED && host.time % 0.3f < dt) pad.buzz((line.tension * 60).toInt() + 10)
        if (msg != null) status = msg
        if (line.state == FishingLine.State.CAUGHT && showFish <= 0f && fishName != (line.fish?.name ?: "")) {
            val f = line.fish!!
            score += (f.weightKg * 10).toInt(); fishName = f.name; showFish = 3f; host.play(host.sWin, host.head.x, host.head.y, host.head.z - 0.5f, 0.6f)
        }
        if (showFish > 0f) { showFish -= dt; if (showFish <= 0f) fishName = "" }
        if (line.state == FishingLine.State.HOOKED) status = "Tensão: ${(line.tension * 100).toInt()}% · distância: ${line.distance.toInt()} m · segure o outro botão para recolher"
    }

    override fun draw() {
        // the shore under you, the lake ahead, trees around
        host.drawBox(0f, -0.06f, 1.5f, 12f, 0.1f, 6f, floatArrayOf(0.35f, 0.55f, 0.25f, 1f))
        host.drawBox(0f, -0.02f, -20f, 60f, 0.02f, 38f, floatArrayOf(0.12f, 0.35f, 0.5f, 1f), gloss = 0.9f)
        val rnd = java.util.Random(5)
        for (i in 0 until 16) {
            val a = -1.5f + i * 0.2f
            val r = 26f + rnd.nextFloat() * 6f
            host.drawModel(if (i % 3 == 0) "survival/tree-tall" else "survival/tree", sin(a) * r, 0f, -cos(a) * r, yaw = rnd.nextFloat() * 6f, scale = 6f + rnd.nextFloat() * 3f)
        }
        for (i in 0..5) host.drawModel("survival/rock-${"abc"[i % 3]}", -3f + i * 1.3f, 0f, -0.9f - (i % 2) * 0.3f, yaw = i.toFloat(), scale = 0.8f)
        host.drawModel("survival/tent", -3.5f, 0f, 2.5f, yaw = 0.6f, scale = 1.6f)
        host.drawModel("survival/campfire-pit", -2.2f, 0f, 1.6f, scale = 1.2f)
        // the rod and the line
        val p = host.pad.pose.position
        host.drawBeam(p.x, p.y, p.z, tip.x, tip.y, tip.z, 0.008f, floatArrayOf(0.35f, 0.22f, 0.12f, 1f), unlit = false)
        if (line.state != FishingLine.State.IDLE) {
            val dip = line.bobberDip() * 0.05f
            host.drawBeam(tip.x, tip.y, tip.z, line.bx, line.by + 0.03f - dip, line.bz, 0.0015f, floatArrayOf(0.95f, 0.95f, 0.95f, 0.8f))
            host.drawSphere(line.bx, line.by + 0.03f - dip, line.bz, 0.03f, floatArrayOf(0.95f, 0.15f, 0.12f, 1f))
            host.drawSphere(line.bx, line.by + 0.06f - dip, line.bz, 0.02f, floatArrayOf(1f, 1f, 1f, 1f))
        }
        if (showFish > 0f) host.drawModel(if (fishName == "Pirarucu" || fishName == "Dourado") "survival/fish-large" else "survival/fish",
            host.head.x, host.head.y - 0.25f, host.head.z - 0.55f, yaw = host.time * 1.5f, scale = 1.3f)
    }
}

// ================================================================================ 10. Pequeno Herói
/** A tabletop world: walk the little hero with the stick, jump with the trigger, collect the coins, reach the flag. */
class DioramaGame(host: GameHost) : QuestGame(host) {
    override val title = "Pequeno Herói"
    override val needsController = true
    private val world = Diorama.level1()
    private val cell = 0.08f
    private val ox get() = -world.w * cell / 2
    private val oy = 0.78f
    private val oz = -1.05f

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
                m.startsWith("Chegou") -> { host.play(host.sWin, gx(world.x), oy + world.y * cell, gz(world.z)); over = true; status = "Você chegou! ${world.collected} moedas, ${world.falls} quedas" }
            }
        }
        score = world.collected * 100 + (if (world.won) 500 else 0) - world.falls * 20
    }

    override fun draw() {
        // the table
        host.drawBox(0f, oy - 0.42f, oz, world.w * cell + 0.3f, 0.04f, world.d * cell + 0.3f, floatArrayOf(0.55f, 0.38f, 0.22f, 1f))
        for (zz in 0 until world.d) for (xx in 0 until world.w) {
            val h = world.heights[zz * world.w + xx]
            if (h == 0) continue
            // earth under the grass top
            host.drawBox(gx(xx + 0.5f), oy - 0.4f / 2 + (h - 1) * cell / 2 - 0.0f, gz(zz + 0.5f), cell, 0.4f + (h - 1) * cell, cell, floatArrayOf(0.45f, 0.32f, 0.2f, 1f))
            host.drawModel("platformer/block-grass", gx(xx + 0.5f), oy + (h - 1) * cell, gz(zz + 0.5f), scale = cell * 0.93f)
        }
        for (c in world.coins) host.drawModel("platformer/coin-gold", gx(c[0] + 0.5f), oy + world.heights[c[1] * world.w + c[0]] * cell + 0.01f, gz(c[1] + 0.5f), yaw = host.time * 3f, scale = cell * 0.8f)
        host.drawModel("platformer/flag", gx(world.goalX + 0.5f), oy + world.heights[world.goalZ * world.w + world.goalX] * cell, gz(world.goalZ + 0.5f), scale = cell)
        host.drawModel("platformer/tree-pine", gx(1.5f), oy + cell, gz(4.5f), scale = cell)
        host.drawModel("platformer/flowers", gx(7.5f), oy + 2 * cell, gz(1.5f), scale = cell)
        host.drawModel("platformer/mushrooms", gx(12.5f), oy + 3 * cell, gz(0.5f), scale = cell)
        host.drawModel("platformer/character-oobi", gx(world.x), oy + world.y * cell, gz(world.z), yaw = world.facing, scale = cell * 0.9f)
    }
}
