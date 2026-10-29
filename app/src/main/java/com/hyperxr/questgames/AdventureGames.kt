package com.hyperxr.questgames

import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Adventure games: a board game on the table, drawing in the air, a kayak, a wingsuit, a table
// race and a museum heist. Games that travel keep you still and move the world around you.

private const val DESK_Y = 0.85f

private fun desk(host: GameHost, w: Float = 1.4f, d: Float = 0.75f, z: Float = -0.55f) {
    host.drawBox(0f, DESK_Y / 2, z, w, DESK_Y, d, rgb(0.5f, 0.34f, 0.22f), tex = "wood", tile = 1f)
}

private val TAVERN = GameSky.Look(
    zenith = rgb(0.1f, 0.07f, 0.05f), horizon = rgb(0.25f, 0.16f, 0.1f), ground = rgb(0.12f, 0.08f, 0.05f),
    sunDir = floatArrayOf(0.2f, 0.9f, 0.3f), floor = GameSky.FLOOR_PLAIN, floorColor = rgb(0.2f, 0.13f, 0.08f),
    fog = 0.05f, light = rgb(1f, 0.75f, 0.5f), ambient = rgb(0.3f, 0.24f, 0.2f),
)

/** Masmorra de Mesa: a turn-based dungeon on a board. Tap a square next to the hero to walk or attack. */
class TableDungeonGame(host: GameHost) : QuestGame(host) {
    override val title = "Masmorra de Mesa"
    override val look = TAVERN
    private val n = 7
    private val cell = 0.075f
    private val touch = Toucher(host)
    private val rnd = java.util.Random()
    private val wall = Array(n) { BooleanArray(n) }
    private val potion = Array(n) { BooleanArray(n) }
    private class Foe(var x: Int, var y: Int, var hp: Int) { var hurt = 0f }
    private val foes = ArrayList<Foe>()
    private var hx = 0; private var hy = 0
    private var hp = 6
    private var floor = 1
    private var dice = 0; private var diceT = 0f

    override fun start() { build(); status = "Toque uma casa vizinha ao herói para andar; toque um goblin vizinho para atacar (dado 3+ acerta)" }

    private fun cx(c: Int) = (c - (n - 1) / 2f) * cell
    private fun cz(r: Int) = -0.55f + (r - (n - 1) / 2f) * cell

    private fun build() {
        for (x in 0 until n) for (y in 0 until n) { wall[x][y] = false; potion[x][y] = false }
        hx = 0; hy = n - 1
        repeat(6 + floor) { val x = rnd.nextInt(n); val y = rnd.nextInt(n); if (!(x <= 1 && y >= n - 2) && !(x >= n - 2 && y <= 1)) wall[x][y] = true }
        repeat(2) { val x = rnd.nextInt(n); val y = rnd.nextInt(n); if (!wall[x][y]) potion[x][y] = true }
        foes.clear()
        repeat(2 + floor) {
            var x: Int; var y: Int
            do { x = rnd.nextInt(n); y = rnd.nextInt(n - 2) } while (wall[x][y] || (x == n - 1 && y == 0) || foes.any { it.x == x && it.y == y })
            foes += Foe(x, y, 1 + floor / 3)
        }
    }

    private fun free(x: Int, y: Int) = x in 0 until n && y in 0 until n && !wall[x][y] && foes.none { it.x == x && it.y == y } && !(x == hx && y == hy)

    private fun roll(): Int { dice = 1 + rnd.nextInt(6); diceT = 1.2f; host.play(host.sTick, 0.4f, DESK_Y + 0.1f, -0.4f, 0.6f, 0.8f + dice * 0.08f); return dice }

    private fun foesTurn() {
        for (f in foes) {
            if (abs(f.x - hx) + abs(f.y - hy) == 1) {
                if (rnd.nextInt(6) >= 3) { hp--; host.play(host.sPunch, cx(hx), DESK_Y, cz(hy)) }
            } else {
                val sx = Integer.signum(hx - f.x); val sy = Integer.signum(hy - f.y)
                if (sx != 0 && free(f.x + sx, f.y)) f.x += sx else if (sy != 0 && free(f.x, f.y + sy)) f.y += sy
            }
        }
        if (hp <= 0) { over = true; status = "O herói caiu no andar $floor. $score pontos" }
    }

    override fun update(dt: Float) {
        diceT -= dt
        for (f in foes) f.hurt -= dt
        if (over) return
        for (x in 0 until n) for (y in 0 until n) {
            if (abs(x - hx) + abs(y - hy) != 1) continue
            if (!touch.tapped("c${x}_$y", cx(x), DESK_Y + 0.04f, cz(y), 0.032f)) continue
            val foe = foes.firstOrNull { it.x == x && it.y == y }
            if (foe != null) {
                if (roll() >= 3) {
                    foe.hp--; foe.hurt = 0.4f
                    if (foe.hp <= 0) { foes.remove(foe); score += 100; host.particles.burst(Particles.DEBRIS, cx(x), DESK_Y + 0.05f, cz(y), 20, 0.6f, rgb(0.3f, 0.6f, 0.2f), 0.6f, 0.006f) }
                } else host.play(host.sMiss, cx(x), DESK_Y, cz(y), 0.5f)
                foesTurn()
            } else if (free(x, y)) {
                hx = x; hy = y
                if (potion[x][y]) { potion[x][y] = false; hp = min(8, hp + 2); host.play(host.sCoin, cx(x), DESK_Y, cz(y)) }
                if (hx == n - 1 && hy == 0) {
                    score += 300; floor++; host.play(host.sWin, 0f, DESK_Y + 0.2f, -0.55f)
                    if (floor > 5) { over = true; status = "Você venceu a masmorra! $score pontos" } else { build(); status = "Desceu para o andar $floor" }
                    return
                }
                foesTurn()
            }
            break
        }
        if (!over) status = "Andar $floor · vida $hp · goblins ${foes.size}" + if (diceT > 0f) " · dado: $dice" else ""
    }

    override fun draw() {
        desk(host)
        val s = cell * n
        host.drawRounded(0f, DESK_Y + 0.01f, -0.55f, s + 0.04f, 0.02f, s + 0.04f, rgb(0.35f, 0.3f, 0.26f), gloss = 0.2f)
        for (x in 0 until n) for (y in 0 until n) {
            val near = !over && abs(x - hx) + abs(y - hy) == 1 && !wall[x][y]
            val c = when {
                wall[x][y] -> null
                x == n - 1 && y == 0 -> rgb(0.95f, 0.75f, 0.2f)
                near -> rgb(0.4f, 0.65f, 0.9f)
                (x + y) % 2 == 0 -> rgb(0.5f, 0.47f, 0.42f)
                else -> rgb(0.44f, 0.41f, 0.37f)
            }
            if (wall[x][y]) host.drawBox(cx(x), DESK_Y + 0.045f, cz(y), cell * 0.95f, 0.07f, cell * 0.95f, rgb(0.33f, 0.31f, 0.3f), tex = "stone", tile = 0.1f)
            else host.drawBox(cx(x), DESK_Y + 0.022f, cz(y), cell * 0.92f, 0.004f, cell * 0.92f, c!!)
            if (potion[x][y]) { host.drawSphere(cx(x), DESK_Y + 0.04f, cz(y), 0.012f, rgb(0.9f, 0.2f, 0.4f), gloss = 0.9f); host.glow(cx(x), DESK_Y + 0.04f, cz(y), 0.03f, rgb(1f, 0.3f, 0.5f), 0.5f) }
        }
        host.glow(cx(n - 1), DESK_Y + 0.05f, cz(0), 0.06f, rgb(1f, 0.8f, 0.3f), 0.6f + 0.2f * sin(host.time * 3f))
        // hero: a blue knight figure; goblins green
        val bob = sin(host.time * 4f) * 0.003f
        host.drawCylinder(cx(hx), DESK_Y + 0.045f + bob, cz(hy), 0.016f, 0.04f, rgb(0.25f, 0.4f, 0.85f), gloss = 0.6f)
        host.drawSphere(cx(hx), DESK_Y + 0.075f + bob, cz(hy), 0.013f, rgb(0.85f, 0.85f, 0.9f), metal = 0.7f)
        host.drawBox(cx(hx) + 0.02f, DESK_Y + 0.06f + bob, cz(hy), 0.004f, 0.045f, 0.004f, rgb(0.8f, 0.8f, 0.85f), metal = 0.9f)
        for (f in foes) {
            val col = if (f.hurt > 0f) rgb(1f, 0.3f, 0.3f) else rgb(0.35f, 0.6f, 0.25f)
            host.drawEllipsoid(cx(f.x), DESK_Y + 0.04f, cz(f.y), 0.028f, 0.04f, 0.028f, col)
            host.drawSphere(cx(f.x), DESK_Y + 0.068f, cz(f.y), 0.012f, col)
            host.drawSphere(cx(f.x) - 0.005f, DESK_Y + 0.07f, cz(f.y) + 0.01f, 0.003f, rgb(1f, 0.9f, 0.1f), unlit = true, shadow = false)
            host.drawSphere(cx(f.x) + 0.005f, DESK_Y + 0.07f, cz(f.y) + 0.01f, 0.003f, rgb(1f, 0.9f, 0.1f), unlit = true, shadow = false)
        }
        // the die and the hero's hearts
        if (diceT > 0f) {
            val q = Quat.fromAxisAngle(0.6f, 0.8f, 0f, diceT * 8f)
            host.drawRounded(0.42f, DESK_Y + 0.04f, -0.4f, 0.04f, 0.04f, 0.04f, rgb(0.95f, 0.95f, 0.92f), orientation = q)
            for (k in 0 until dice) host.drawSphere(0.42f - 0.012f + (k % 3) * 0.012f, DESK_Y + 0.07f, -0.4f - 0.006f + (k / 3) * 0.012f, 0.004f, rgb(0.1f, 0.1f, 0.1f), shadow = false)
        }
        for (k in 0 until hp) host.drawSphere(-0.42f + (k % 4) * 0.03f, DESK_Y + 0.03f, -0.35f - (k / 4) * 0.03f, 0.011f, rgb(0.95f, 0.15f, 0.25f), gloss = 0.8f)
        host.glow(0.55f, DESK_Y + 0.35f, -0.85f, 0.4f, rgb(1f, 0.6f, 0.25f), 0.5f + 0.1f * sin(host.time * 9f))
    }
}

/** Traços de Luz: draw shapes in the air by passing a fingertip through the dots, in order. */
class LightSketchGame(host: GameHost) : QuestGame(host) {
    override val title = "Traços de Luz"
    override val look = GameSky.Look(
        zenith = rgb(0.02f, 0.02f, 0.06f), horizon = rgb(0.08f, 0.05f, 0.15f), ground = rgb(0.02f, 0.02f, 0.04f),
        sunDir = floatArrayOf(0f, 1f, 0f), stars = 0.6f, floor = GameSky.FLOOR_GRID, floorColor = rgb(0.02f, 0.02f, 0.05f),
        grid = floatArrayOf(0.3f, 0.2f, 0.7f, 1f), fog = 0.03f, light = rgb(0.8f, 0.8f, 1f), ambient = rgb(0.3f, 0.3f, 0.45f),
    )
    private val names = arrayOf("círculo", "estrela", "coração", "casinha", "espiral", "raio")
    private var shape = 0
    private val dots = ArrayList<FloatArray>()
    private var next = 0
    private var timer = 0f
    private val trail = Array(240) { FloatArray(4) }
    private var trailN = 0; private var trailHead = 0
    private val p = Vec3()
    private var cy = 1.3f

    override fun start() { cy = max(1.0f, host.head.y - 0.15f); make(); status = "Passe a ponta do dedo pelos pontos, em ordem. Faça a pinça para deixar um rastro de luz" }

    private fun make() {
        dots.clear(); next = 0; timer = 0f
        val r = 0.22f
        fun add(x: Float, y: Float) { dots += floatArrayOf(x, cy + y, -0.45f) }
        when (shape) {
            0 -> for (k in 0..12) { val a = k / 12f * 2f * PI.toFloat(); add(sin(a) * r, cos(a) * r) }
            1 -> for (k in 0..10) { val a = k / 10f * 2f * PI.toFloat(); val rr = if (k % 2 == 0) r else r * 0.42f; add(sin(a) * rr, cos(a) * rr) }
            2 -> for (k in 0..16) { val t = k / 16f * 2f * PI.toFloat(); val x = 16f * sin(t) * sin(t) * sin(t); val y = 13f * cos(t) - 5f * cos(2 * t) - 2f * cos(3 * t) - cos(4 * t); add(x / 17f * r, y / 17f * r) }
            3 -> listOf(-1f to -1f, 1f to -1f, 1f to 0.3f, 0f to 1f, -1f to 0.3f, -1f to -1f, 1f to 0.3f, -1f to 0.3f).forEach { (x, y) -> add(x * r * 0.8f, y * r * 0.8f) }
            4 -> for (k in 0..18) { val a = k / 18f * 4f * PI.toFloat(); val rr = r * (0.15f + 0.85f * k / 18f); add(sin(a) * rr, cos(a) * rr) }
            else -> listOf(0.3f to 1f, -0.4f to 0.05f, 0.1f to 0.05f, -0.3f to -1f, 0.45f to 0.15f, -0.05f to 0.15f, 0.3f to 1f).forEach { (x, y) -> add(x * r, y * r) }
        }
    }

    private fun tipOf(i: Int): Boolean = when (i) {
        0, 1 -> host.hands[i].usable.also { if (it) p.set(host.hands[i].tip) }
        else -> host.pad.connected.also { if (it) p.set(host.pad.aim.origin).addScaled(host.pad.aim.direction, 0.1f) }
    }

    override fun update(dt: Float) {
        if (over) return
        timer += dt
        for (i in 0..2) {
            if (!tipOf(i)) continue
            val drawing = if (i < 2) host.hands[i].pinch else host.pad.trigger
            if (drawing) { val t = trail[trailHead]; t[0] = p.x; t[1] = p.y; t[2] = p.z; t[3] = host.time; trailHead = (trailHead + 1) % trail.size; trailN = min(trailN + 1, trail.size) }
            if (next < dots.size) {
                val d = dots[next]
                if (dist(p.x, p.y, p.z, d[0], d[1], d[2]) < 0.045f) {
                    next++; score += 10
                    host.play(host.sTick, d[0], d[1], d[2], 0.6f, 1f + next * 0.04f)
                    host.particles.burst(Particles.SPARK, d[0], d[1], d[2], 8, 0.5f, rgb(0.6f, 0.5f, 1f), 0.4f, 0.004f, gravity = 0f)
                }
            }
        }
        if (next >= dots.size) {
            val bonus = max(0, (30f - timer).toInt()) * 10
            score += 100 + bonus; host.play(host.sWin, 0f, cy, -0.45f)
            shape++
            if (shape >= names.size) { over = true; status = "Galeria completa! $score pontos" } else { make(); status = "Agora: ${names[shape]}" }
        } else if (timer > 2f) status = "${names[shape]} · ponto ${next + 1} de ${dots.size} · ${timer.toInt()} s"
    }

    override fun draw() {
        for ((k, d) in dots.withIndex()) {
            val done = k < next
            val c = if (done) rgb(0.5f, 0.4f, 1f) else if (k == next) rgb(1f, 0.85f, 0.3f) else rgb(0.4f, 0.4f, 0.5f)
            host.drawSphere(d[0], d[1], d[2], if (k == next) 0.014f + 0.004f * sin(host.time * 8f) else 0.009f, c, unlit = true, shadow = false)
            if (k == next) host.glow(d[0], d[1], d[2], 0.05f, rgb(1f, 0.8f, 0.3f), 0.7f)
            if (done && k > 0) { val a = dots[k - 1]; host.glowBeam(a[0], a[1], a[2], d[0], d[1], d[2], 0.006f, rgb(0.55f, 0.45f, 1f), 0.9f) }
        }
        for (k in 0 until trailN) {
            val t = trail[(trailHead - 1 - k + trail.size) % trail.size]
            val age = host.time - t[3]
            if (age > 6f) continue
            host.drawSphere(t[0], t[1], t[2], 0.006f, rgb(0.3f + 0.5f * sin(t[3] * 2f) * sin(t[3] * 2f), 0.6f, 1f), unlit = true, shadow = false)
        }
    }
}

/** Moves the world around a traveller at (px, pz) facing [yaw]: world point -> local game space. */
private class Traveller {
    var px = 0f; var pz = 0f; var yaw = 0f
    var lx = 0f; var lz = 0f
    fun local(wx: Float, wz: Float) {
        val dx = wx - px; val dz = wz - pz
        val c = cos(yaw); val s = sin(yaw)
        lx = dx * c + dz * s
        lz = -(dx * s - dz * c)
    }
    fun forward(d: Float) { px += sin(yaw) * d; pz -= cos(yaw) * d }
}

/** Caiaque: paddle with both hands (pull a hand backwards low on one side) and pass through the gates of buoys. */
class KayakGame(host: GameHost) : QuestGame(host) {
    override val title = "Caiaque"
    override val look = GameSky.Look(
        zenith = rgb(0.25f, 0.5f, 0.85f), horizon = rgb(0.75f, 0.85f, 0.95f), ground = rgb(0.1f, 0.25f, 0.3f),
        sunDir = floatArrayOf(0.4f, 0.6f, -0.6f), sunColor = rgb(1f, 0.95f, 0.8f), clouds = 0.4f,
        floor = GameSky.FLOOR_WATER, floorY = 0.25f, floorColor = rgb(0.04f, 0.16f, 0.2f), fog = 0.008f, objectFog = 0.006f,
        light = rgb(1f, 0.96f, 0.88f), ambient = rgb(0.4f, 0.45f, 0.55f),
    )
    private val t = Traveller()
    private var speed = 0f; private var turn = 0f
    private val gates = ArrayList<FloatArray>()   // x, z, passed
    private var nextGate = 0
    private var time = 0f
    private val rocks = Array(40) { val a = it * 2.4f; floatArrayOf(sin(a) * (30f + it * 4f), -cos(a) * (30f + it * 4f) - it * 6f, 1f + (it % 4) * 0.8f) }

    override fun start() {
        var x = 0f; var z = -12f
        for (k in 0 until 12) { gates += floatArrayOf(x, z, 0f); x += (if (k % 2 == 0) 1 else -1) * (4f + k * 0.6f); z -= 14f }
        status = "Reme: com a mão baixa, puxe para trás. Esquerda vira para a direita, direita vira para a esquerda (ou analógico)"
    }

    override fun update(dt: Float) {
        if (over) return
        time += dt
        for (i in 0..1) {
            val g = host.hands[i]
            if (!g.usable) continue
            val low = g.palm.y < host.head.y - 0.35f
            if (low && g.velocity.z > 0.4f) {
                val push = min(g.velocity.z, 3f) * dt
                speed += push * 1.6f
                turn += (if (g.palm.x < host.head.x) 1f else -1f) * push * 0.5f
                if (host.time % 0.25f < dt) host.play(host.sSplash, g.palm.x, 0.25f, g.palm.z, 0.3f)
            }
        }
        if (host.pad.connected) { speed += -host.pad.stickY * dt * 1.5f; turn += host.pad.stickX * dt * 0.8f }
        speed = speed.coerceIn(0f, 5f) * (1f - 0.35f * dt)
        turn *= (1f - 2.5f * dt)
        t.yaw += turn * dt * 2f
        t.forward(speed * dt)
        if (nextGate < gates.size) {
            val g = gates[nextGate]
            t.local(g[0], g[1])
            if (t.lz > -0.3f) {
                if (abs(t.lx) < 2f) { g[2] = 1f; score += 100 + max(0, 20 - time.toInt()) * 5; host.play(host.sCoin, 0f, 0.6f, -1f) } else { g[2] = -1f; host.play(host.sMiss, 0f, 0.6f, -1f) }
                nextGate++; time = 0f
            } else if (t.lz > 30f) { g[2] = -1f; nextGate++ }
        }
        if (nextGate >= gates.size) { over = true; status = "Fim do percurso! ${gates.count { it[2] > 0f }} de ${gates.size} portões · $score pontos" }
        else status = "Portão ${nextGate + 1} de ${gates.size} · ${(speed * 3.6f).toInt()} km/h"
    }

    override fun draw() {
        val bob = sin(host.time * 1.7f) * 0.02f
        // the kayak around you
        host.drawEllipsoid(0f, 0.28f + bob, -0.2f, 0.6f, 0.25f, 3.2f, rgb(0.95f, 0.45f, 0.1f), gloss = 0.7f)
        host.drawEllipsoid(0f, 0.36f + bob, 0.05f, 0.45f, 0.08f, 0.7f, rgb(0.1f, 0.1f, 0.12f))
        // the paddle between the hands
        val a = host.hands[0]; val b = host.hands[1]
        if (a.usable && b.usable) {
            val dx = b.palm.x - a.palm.x; val dy = b.palm.y - a.palm.y; val dz = b.palm.z - a.palm.z
            host.drawBeam(a.palm.x - dx * 0.5f, a.palm.y - dy * 0.5f, a.palm.z - dz * 0.5f, b.palm.x + dx * 0.5f, b.palm.y + dy * 0.5f, b.palm.z + dz * 0.5f, 0.015f, rgb(0.2f, 0.2f, 0.22f), unlit = false)
            host.drawEllipsoid(a.palm.x - dx * 0.55f, a.palm.y - dy * 0.55f, a.palm.z - dz * 0.55f, 0.12f, 0.04f, 0.2f, rgb(0.95f, 0.85f, 0.15f))
            host.drawEllipsoid(b.palm.x + dx * 0.55f, b.palm.y + dy * 0.55f, b.palm.z + dz * 0.55f, 0.12f, 0.04f, 0.2f, rgb(0.95f, 0.85f, 0.15f))
        }
        for ((k, g) in gates.withIndex()) {
            if (k < nextGate - 1 || k > nextGate + 3) continue
            t.local(g[0], g[1])
            val col = when { g[2] > 0f -> rgb(0.3f, 0.9f, 0.4f); k == nextGate -> rgb(1f, 0.3f, 0.2f); else -> rgb(1f, 0.6f, 0.2f) }
            for (side in -1..1 step 2) {
                t.local(g[0] + side * 2f * cos(0f), g[1])
                host.drawSphere(t.lx, 0.3f + bob, t.lz, 0.3f, col, gloss = 0.7f)
                host.drawCylinder(t.lx, 0.9f, t.lz, 0.04f, 1.2f, rgb(0.9f, 0.9f, 0.9f))
            }
            if (k == nextGate) { t.local(g[0], g[1]); host.glow(t.lx, 1.5f, t.lz, 0.6f, rgb(1f, 0.5f, 0.3f), 0.5f) }
        }
        for (r in rocks) { t.local(r[0], r[1]); if (r[0] != 0f && t.lz < 20f && t.lz > -150f) host.drawEllipsoid(t.lx, 0.2f, t.lz, r[2] * 2f, r[2], r[2] * 1.6f, rgb(0.35f, 0.33f, 0.3f), gloss = 0.1f) }
        // the shore far away
        for (k in 0 until 10) { t.local(-60f + k * 3f, -40f - k * 30f); host.drawEllipsoid(t.lx, 0f, t.lz, 30f, 12f, 30f, rgb(0.2f, 0.4f, 0.2f), gloss = 0.05f) }
        for (k in 0 until 10) { t.local(70f - k * 2f, -60f - k * 30f); host.drawEllipsoid(t.lx, 0f, t.lz, 30f, 14f, 30f, rgb(0.22f, 0.42f, 0.2f), gloss = 0.05f) }
    }
}

/** Voo Livre: a wingsuit flight. Arms open glide, arms closed dive; lower one arm to bank. Fly through the rings. */
class WingsuitGame(host: GameHost) : QuestGame(host) {
    override val title = "Voo Livre"
    override val look = GameSky.Look(
        zenith = rgb(0.2f, 0.42f, 0.8f), horizon = rgb(0.8f, 0.85f, 0.92f), ground = rgb(0.25f, 0.35f, 0.2f),
        sunDir = floatArrayOf(-0.3f, 0.5f, -0.8f), sunColor = rgb(1f, 0.95f, 0.85f), clouds = 0.6f,
        floor = GameSky.FLOOR_NONE, fog = 0.002f, objectFog = 0.002f, light = rgb(1f, 0.97f, 0.9f), ambient = rgb(0.45f, 0.48f, 0.55f),
    )
    override val shadowRadius = 0f
    private val t = Traveller()
    private var alt = 420f
    private var speed = 30f
    private var bank = 0f
    private val rings = ArrayList<FloatArray>()   // x, z, height, state
    private var next = 0
    private val peaks = Array(30) { val r = java.util.Random(it.toLong() * 31); floatArrayOf((r.nextFloat() - 0.5f) * 900f, -r.nextFloat() * 2600f, 80f + r.nextFloat() * 220f, 120f + r.nextFloat() * 160f) }
    private fun ground(x: Float, z: Float): Float {
        var h = 0f
        for (p in peaks) { val d = sqrt((x - p[0]) * (x - p[0]) + (z - p[1]) * (z - p[1])); if (d < p[3]) h = max(h, p[2] * (1f - d / p[3])) }
        return h
    }

    override fun start() {
        var x = 0f; var z = -120f; var h = 390f
        for (k in 0 until 14) { rings += floatArrayOf(x, z, h, 0f); x += sin(k * 0.9f) * 60f; z -= 150f; h -= 25f }
        status = "Abra os braços para planar, feche para mergulhar. Baixe um braço para virar para esse lado"
    }

    override fun update(dt: Float) {
        if (over) return
        val a = host.hands[0]; val b = host.hands[1]
        var spread = 1f; var tilt = 0f
        if (a.usable && b.usable) {
            val l = if (a.palm.x < b.palm.x) a else b; val r = if (l === a) b else a
            spread = ((r.palm.x - l.palm.x) / 1.2f).coerceIn(0f, 1f)
            tilt = ((r.palm.y - l.palm.y) / 0.5f).coerceIn(-1f, 1f)
        } else if (host.pad.connected) { tilt = -host.pad.stickX; spread = 1f - max(0f, host.pad.stickY) }
        bank += (tilt - bank) * min(1f, dt * 3f)
        t.yaw += bank * dt * 0.9f
        val sink = 6f + (1f - spread) * 30f
        speed += ((1f - spread) * 25f - (speed - 32f) * 0.4f) * dt
        alt -= sink * dt
        t.forward(speed * dt)
        if (next < rings.size) {
            val g = rings[next]
            t.local(g[0], g[1])
            if (t.lz > 0f) {
                val dy = alt - g[2]
                if (abs(t.lx) < 12f && abs(dy) < 12f) { g[3] = 1f; score += 200; host.play(host.sCoin, 0f, 1.6f, -2f); alt += 15f } else { g[3] = -1f; host.play(host.sMiss, 0f, 1.6f, -2f) }
                next++
            }
        }
        val gnd = ground(t.px, t.pz)
        if (alt - gnd < 2f) {
            over = true
            status = if (next >= rings.size) "Pouso perfeito! $score pontos" else "Você tocou o chão. ${rings.count { it[3] > 0f }} anéis · $score pontos"
            host.play(host.sBoom, 0f, 1f, -1f, 0.6f, 0.7f)
        } else status = "Altura ${(alt - gnd).toInt()} m · ${(speed * 3.6f).toInt()} km/h · anel ${min(next + 1, rings.size)} de ${rings.size}"
        if (next >= rings.size && !over) score += (dt * 10).toInt()
    }

    override fun draw() {
        val eye = host.head.y
        fun ly(h: Float) = h - alt + eye
        val q = Quat.fromAxisAngle(1f, 0f, 0f, PI.toFloat() / 2f)
        for ((k, g) in rings.withIndex()) {
            if (k < next || k > next + 4) continue
            t.local(g[0], g[1])
            host.drawTorus(t.lx, ly(g[2]), t.lz, 12f, if (k == next) rgb(1f, 0.75f, 0.2f) else rgb(0.4f, 0.8f, 1f), orientation = q, glow = true, thick = 0.1f)
        }
        for (p in peaks) {
            t.local(p[0], p[1])
            if (t.lz > 300f || t.lz < -2500f) continue
            host.drawEllipsoid(t.lx, ly(0f), t.lz, p[3] * 2f, p[2] * 2f, p[3] * 2f, rgb(0.32f, 0.36f, 0.3f), gloss = 0.05f)
            host.drawEllipsoid(t.lx, ly(p[2] * 0.82f), t.lz, p[3] * 0.55f, p[2] * 0.4f, p[3] * 0.55f, rgb(0.95f, 0.96f, 1f), gloss = 0.2f)
        }
        // the valley floor
        host.drawBox(0f, ly(0f) - 1f, -500f, 3000f, 2f, 3000f, rgb(0.24f, 0.36f, 0.2f), shadow = false)
        // the wings between the arms and the body
        for (i in 0..1) {
            val h = host.hands[i]
            if (!h.usable) continue
            host.drawBeam(h.palm.x, h.palm.y, h.palm.z, host.head.x, host.head.y - 0.7f, host.head.z, 0.05f, rgb(0.9f, 0.25f, 0.2f), unlit = false)
        }
    }
}

/** Corrida de Mesa: tiny cars around an oval on the table. Pinch to accelerate and move the hand sideways to steer (or the controller). */
class TableRacingGame(host: GameHost) : QuestGame(host) {
    override val title = "Corrida de Mesa"
    override val look = TAVERN
    private val ra = 0.5f; private val rb = 0.24f; private val cz = -0.55f
    private var x = ra; private var z = cz; private var h = 0f; private var v = 0f
    private var prog = 0f; private var lastAng = 0f
    private var ai = 0f
    private val laps = 3
    private var steerRef = Float.NaN
    private var time = 0f
    private var finished = false

    override fun start() {
        x = ra; z = cz; h = PI.toFloat() / 2f; v = 0f; lastAng = ang(x, z); ai = 0f
        status = "Pinça (ou gatilho) acelera; leve a mão para os lados para virar. 3 voltas contra o carro vermelho"
    }

    private fun ang(x: Float, z: Float) = atan2((z - cz) / rb, x / ra)

    override fun update(dt: Float) {
        if (over) return
        time += dt
        var gas = false; var steer = 0f
        val hand = host.hands.firstOrNull { it.usable && it.pinch }
        if (hand != null) {
            gas = true
            if (steerRef.isNaN()) steerRef = hand.palm.x
            steer = ((hand.palm.x - steerRef) / 0.12f).coerceIn(-1f, 1f)
        } else steerRef = Float.NaN
        if (host.pad.connected) { if (host.pad.trigger) gas = true; if (abs(host.pad.stickX) > 0.1f) steer = host.pad.stickX }
        v += (if (gas) 0.6f else -0.3f) * dt
        val rr = sqrt((x / ra) * (x / ra) + ((z - cz) / rb) * ((z - cz) / rb))
        val off = abs(rr - 1f) > 0.24f
        v = v.coerceIn(0f, if (off) 0.12f else 0.5f)
        h += steer * dt * 3.2f * min(1f, v / 0.2f + 0.2f)
        x += cos(h) * v * dt; z -= sin(h) * v * dt
        // walls at the table edge
        x = x.coerceIn(-0.66f, 0.66f); z = z.coerceIn(cz - 0.34f, cz + 0.34f)
        val a = ang(x, z)
        var d = a - lastAng
        if (d > PI) d -= 2f * PI.toFloat(); if (d < -PI) d += 2f * PI.toFloat()
        prog += d; lastAng = a
        ai += dt * (0.62f + 0.04f * sin(time * 0.7f))
        val myLaps = (-prog / (2f * PI.toFloat())).toInt()
        val aiLaps = (ai / (2f * PI.toFloat())).toInt()
        if (myLaps >= laps || aiLaps >= laps) {
            over = true
            if (myLaps >= laps && -prog >= ai) { score = 1000 + max(0, 60 - time.toInt()) * 20; status = "Você venceu em ${time.toInt()} s!"; host.play(host.sWin, 0f, DESK_Y + 0.2f, cz) }
            else { score = myLaps * 200; status = "O carro vermelho venceu. Tente de novo" }
        } else status = "Volta ${min(laps, myLaps + 1)} de $laps · ${if (-prog >= ai) "1º lugar" else "2º lugar"}" + if (off) " · fora da pista" else ""
    }

    override fun draw() {
        desk(host)
        val top = DESK_Y + 0.004f
        host.drawBox(0f, top, cz, 1.36f, 0.004f, 0.7f, rgb(0.25f, 0.5f, 0.22f))
        for (k in 0 until 48) {
            val a = k / 48f * 2f * PI.toFloat()
            val px = cos(a) * ra; val pz = cz + sin(a) * rb
            host.drawBox(px, top + 0.003f, pz, 0.08f, 0.002f, 0.08f, rgb(0.22f, 0.22f, 0.24f), shadow = false)
            if (k % 2 == 0) {
                host.drawBox(cos(a) * ra * 1.26f, top + 0.006f, cz + sin(a) * rb * 1.26f, 0.015f, 0.008f, 0.015f, if (k % 4 == 0) rgb(0.9f, 0.1f, 0.1f) else rgb(0.95f, 0.95f, 0.95f), shadow = false)
                host.drawBox(cos(a) * ra * 0.74f, top + 0.006f, cz + sin(a) * rb * 0.74f, 0.015f, 0.008f, 0.015f, if (k % 4 == 0) rgb(0.95f, 0.95f, 0.95f) else rgb(0.9f, 0.1f, 0.1f), shadow = false)
            }
        }
        host.drawBox(ra, top + 0.006f, cz, 0.1f, 0.002f, 0.012f, rgb(1f, 1f, 1f), shadow = false)
        car(x, z, h, rgb(0.2f, 0.45f, 0.95f))
        val ax = cos(-ai) * ra; val az = cz + sin(-ai) * rb
        car(ax, az, atan2(rb * cos(-ai), ra * sin(-ai)), rgb(0.9f, 0.15f, 0.15f))
    }

    private fun car(x: Float, z: Float, heading: Float, c: FloatArray) {
        val y = DESK_Y + 0.02f
        host.drawRounded(x, y, z, 0.06f, 0.02f, 0.032f, c, yaw = heading, gloss = 0.8f)
        host.drawRounded(x - cos(heading) * 0.005f, y + 0.015f, z + sin(heading) * 0.005f, 0.03f, 0.014f, 0.026f, rgb(0.1f, 0.12f, 0.15f), yaw = heading, gloss = 0.9f)
        for (s in -1..1 step 2) for (f in -1..1 step 2) {
            val wx = x + cos(heading) * 0.02f * f + sin(heading) * 0.017f * s
            val wz = z - sin(heading) * 0.02f * f + cos(heading) * 0.017f * s
            host.drawSphere(wx, DESK_Y + 0.012f, wz, 0.008f, rgb(0.05f, 0.05f, 0.05f), shadow = false)
        }
    }
}

/** Ladrão de Joias: take the jewels from the museum to your bag without touching the moving lasers. */
class JewelThiefGame(host: GameHost) : QuestGame(host) {
    override val title = "Ladrão de Joias"
    override val look = GameSky.Look(
        zenith = rgb(0.03f, 0.03f, 0.05f), horizon = rgb(0.06f, 0.06f, 0.1f), ground = rgb(0.03f, 0.03f, 0.04f),
        sunDir = floatArrayOf(0.1f, 1f, 0.2f), floor = GameSky.FLOOR_PLAIN, floorColor = rgb(0.12f, 0.1f, 0.1f), fog = 0.08f,
        light = rgb(0.5f, 0.55f, 0.8f), ambient = rgb(0.15f, 0.15f, 0.22f),
    )
    private val grab = Grabber(host, reach = 0.15f)
    private val gems = ArrayList<Prop>()
    private val bag = floatArrayOf(0.35f, 0.9f, -0.15f)
    private var stolen = 0
    private var alarms = 0
    private var hitCool = 0f
    private var time = 150f
    private val gemColors = arrayOf(rgb(0.2f, 0.9f, 0.5f), rgb(0.9f, 0.15f, 0.3f), rgb(0.3f, 0.5f, 1f), rgb(0.95f, 0.85f, 0.3f), rgb(0.8f, 0.4f, 1f))
    private val p = Vec3()

    override fun start() {
        for (k in 0 until 5) {
            val px = -0.8f + k * 0.4f; val pz = -1.1f - (k % 2) * 0.35f
            gems += Prop(px, 1.12f, pz, 0.04f, k).also { it.resting = true }
        }
        status = "Pegue as joias (mão fechada) e solte dentro da bolsa à sua direita. Não encoste nos lasers"
    }

    private fun laserY(k: Int) = 0.8f + 0.55f * (0.5f + 0.5f * sin(host.time * (0.6f + k * 0.25f) + k * 1.7f))
    private fun laserZ(k: Int) = -0.45f - k * 0.3f

    override fun update(dt: Float) {
        if (over) return
        time -= dt; hitCool -= dt
        grab.update(gems, onGrab = { _, _ -> host.play(host.sTick, 0f, 1f, -0.8f) })
        for (g in gems) {
            if (!g.alive) continue
            g.physics(dt, floor = 0f)
            if (g.held < 0 && dist(g.x, g.y, g.z, bag[0], bag[1], bag[2]) < 0.16f) {
                g.alive = false; stolen++; score += 300; host.play(host.sCoin, bag[0], bag[1], bag[2])
            }
        }
        // lasers: any hand, the controller or the head crossing a beam sets off the alarm
        for (k in 0 until 3) {
            val ly = laserY(k); val lz = laserZ(k)
            for (i in 0..3) {
                val ok = if (i < 3) host.handPos(i, p) else { p.set(host.head); true }
                if (!ok || abs(p.x) > 1.1f) continue
                if (abs(p.y - ly) < 0.035f && abs(p.z - lz) < 0.05f && hitCool <= 0f) {
                    alarms++; hitCool = 1.2f; host.play(host.sMiss, p.x, p.y, p.z, 1f, 0.6f); host.pad.buzz(120)
                }
            }
        }
        when {
            stolen == gems.size -> { over = true; score += time.toInt() * 10; status = "Golpe perfeito! Todas as joias em ${150 - time.toInt()} s" }
            alarms >= 3 -> { over = true; status = "Alarme! A polícia chegou. $stolen joias roubadas" }
            time <= 0f -> { over = true; status = "O guarda voltou. $stolen joias roubadas" }
            else -> status = "Joias $stolen de ${gems.size} · alarmes $alarms de 3 · ${time.toInt()} s"
        }
    }

    override fun draw() {
        // museum: back wall, pedestals with glass
        host.drawBox(0f, 1.5f, -1.9f, 3.2f, 3f, 0.1f, rgb(0.35f, 0.3f, 0.32f), tex = "stone", tile = 0.8f)
        for (g in gems) {
            host.drawBox(g.home[0], 0.53f, g.home[2], 0.22f, 1.06f, 0.22f, rgb(0.85f, 0.82f, 0.78f), gloss = 0.4f)
            host.glow(g.home[0], 1.5f, g.home[2], 0.3f, rgb(0.8f, 0.85f, 1f), 0.25f)
            if (!g.alive) continue
            val c = gemColors[g.kind % gemColors.size]
            host.drawEllipsoid(g.x, g.y, g.z, 0.07f, 0.09f, 0.07f, c, yaw = host.time + g.kind, gloss = 1f)
            host.glow(g.x, g.y, g.z, 0.08f, c, 0.4f)
        }
        for (k in 0 until 3) {
            val ly = laserY(k); val lz = laserZ(k)
            val c = if (hitCool > 0f) rgb(1f, 0.6f, 0.1f) else rgb(1f, 0.1f, 0.1f)
            host.glowBeam(-1.1f, ly, lz, 1.1f, ly, lz, 0.006f, c, 1f)
            host.drawBox(-1.15f, ly, lz, 0.05f, 0.08f, 0.05f, rgb(0.2f, 0.2f, 0.22f), metal = 0.7f)
            host.drawBox(1.15f, ly, lz, 0.05f, 0.08f, 0.05f, rgb(0.2f, 0.2f, 0.22f), metal = 0.7f)
        }
        // the bag
        host.drawEllipsoid(bag[0], bag[1] - 0.05f, bag[2], 0.26f, 0.22f, 0.22f, rgb(0.22f, 0.2f, 0.18f), gloss = 0.1f)
        host.drawTorus(bag[0], bag[1] + 0.06f, bag[2], 0.1f, rgb(0.35f, 0.3f, 0.25f))
        if (alarms > 0 && hitCool > 0f) host.glow(0f, 2.5f, -1f, 2f, rgb(1f, 0.1f, 0.1f), 0.4f * (sin(host.time * 20f) * 0.5f + 0.5f))
    }
}
