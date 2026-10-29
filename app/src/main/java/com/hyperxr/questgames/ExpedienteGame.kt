package com.hyperxr.questgames

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private fun rgba(r: Float, g: Float, b: Float, a: Float = 1f) = floatArrayOf(r, g, b, a)

/**
 * Expediente: a job in the year 2087, at a counter in front of you, for a robot boss on a screen.
 * Close a hand (or pinch, or the trigger) on something to pick it up, open to let go, throw by
 * letting go while moving. Tasks: coffee, the bin, a sandwich in the right order, fruit for the
 * boss, the red button. Inspired by Job Simulator; the office, the boss and the jokes are new.
 */
class ExpedienteGame(host: GameHost) : QuestGame(host) {
    override val title = "Expediente"
    override val look = GameSky.Look(
        zenith = rgba(0.86f, 0.88f, 0.92f), horizon = rgba(0.95f, 0.95f, 0.97f), ground = rgba(0.8f, 0.78f, 0.74f),
        sunDir = floatArrayOf(0.3f, 0.85f, 0.4f), floor = GameSky.FLOOR_PLAIN, floorColor = rgba(0.62f, 0.55f, 0.48f),
        fog = 0.01f, objectFog = 0.01f, light = rgba(1f, 0.97f, 0.92f), ambient = rgba(0.55f, 0.56f, 0.6f),
    )
    override val shadowRadius = 2.2f
    override val shadowZ = -0.6f

    private enum class Kind { MUG, PAPER, BUN_BOTTOM, PATTY, CHEESE, BUN_TOP, APPLE, BANANA }
    private class Thing(val kind: Kind, val x0: Float, val y0: Float, val z0: Float) {
        var x = x0; var y = y0; var z = z0
        var vx = 0f; var vy = 0f; var vz = 0f
        var heldBy = -1
        var full = false
        var gone = false
        val r get() = when (kind) { Kind.PAPER -> 0.04f; Kind.APPLE -> 0.045f; Kind.MUG -> 0.05f; Kind.BANANA -> 0.06f; else -> 0.06f }
        fun reset() { x = x0; y = y0; z = z0; vx = 0f; vy = 0f; vz = 0f; heldBy = -1; full = false; gone = false }
    }

    private val tableY = 0.86f
    private val tableZ = -0.62f
    private val things = ArrayList<Thing>()
    // stations (game space)
    private val machine = floatArrayOf(-0.55f, tableY, -0.78f)
    private val button = floatArrayOf(-0.42f, tableY + 0.3f, -0.68f)
    private val bin = floatArrayOf(0.9f, 0f, -0.35f)
    private val plate = floatArrayOf(0.15f, tableY, -0.62f)
    private val basket = floatArrayOf(0f, 1.55f, -1.6f)
    private val red = floatArrayOf(0.55f, tableY + 0.03f, -0.72f)
    private var brewing = 0f
    private var task = 0
    private var done = 0
    private var bossLine = ""
    private var bossTalk = 0f
    private var celebrate = 0f
    private var redPresses = 0
    private val grabbing = intArrayOf(-1, -1, -1)
    private val handPos = Array(3) { FloatArray(3) }
    private val handVel = Array(3) { FloatArray(3) }
    private val handClosed = BooleanArray(3)
    private val handHad = BooleanArray(3)
    private val order = listOf(0, 1, 2, 3, 4)
    private var tasks = order.shuffled()
    private var time = 0f

    private val tasksText = arrayOf(
        "Faça um café: ponha a xícara embaixo da máquina e aperte o botão verde",
        "Jogue a bolinha de papel na lixeira à direita",
        "Monte o lanche no prato: pão, carne, queijo e pão por cima",
        "Jogue uma fruta na cesta do chefe, lá em cima",
        "Aperte o botão vermelho. Ou não. A escolha é sua",
    )
    private val bossDone = arrayOf(
        "Café! O combustível dos humanos. Excelente.", "Lixo no lixo. Você nasceu para isso.",
        "Um lanche perfeito. Vou fingir que sei o gosto.", "Uma fruta! Robôs não comem, mas obrigado.",
        "Você apertou. Claro que apertou.",
    )

    override fun start() {
        things.clear()
        things += Thing(Kind.MUG, -0.25f, tableY + 0.05f, -0.5f)
        things += Thing(Kind.PAPER, 0.45f, tableY + 0.04f, -0.48f)
        things += Thing(Kind.BUN_BOTTOM, -0.1f, tableY + 0.03f, -0.42f)
        things += Thing(Kind.PATTY, 0.0f, tableY + 0.03f, -0.4f)
        things += Thing(Kind.CHEESE, 0.1f, tableY + 0.02f, -0.4f)
        things += Thing(Kind.BUN_TOP, 0.22f, tableY + 0.03f, -0.42f)
        things += Thing(Kind.APPLE, 0.36f, tableY + 0.05f, -0.6f)
        things += Thing(Kind.BANANA, -0.3f, tableY + 0.04f, -0.72f)
        tasks = order.shuffled(); task = 0; done = 0; time = 0f; redPresses = 0
        say("Bem-vindo ao seu primeiro dia, humano! ${tasksText[tasks[0]]}")
        status = tasksText[tasks[0]]
    }

    private fun say(line: String) { bossLine = line; bossTalk = 3f }

    override fun update(dt: Float) {
        time += dt
        bossTalk -= dt; celebrate -= dt
        // hands (and the controller) as grabbers
        for (i in 0..2) {
            handHad[i] = handClosed[i]
            if (i < 2) {
                val g = host.hands[i]
                if (!g.usable) { release(i); handClosed[i] = false; continue }
                handPos[i][0] = g.palm.x; handPos[i][1] = g.palm.y; handPos[i][2] = g.palm.z
                handVel[i][0] = g.velocity.x; handVel[i][1] = g.velocity.y; handVel[i][2] = g.velocity.z
                handClosed[i] = g.fist || g.pinch
            } else {
                val p = host.pad
                if (!p.connected) { release(2); handClosed[2] = false; continue }
                handPos[2][0] = p.pose.position.x; handPos[2][1] = p.pose.position.y; handPos[2][2] = p.pose.position.z
                handVel[2][0] = p.velocity.x; handVel[2][1] = p.velocity.y; handVel[2][2] = p.velocity.z
                handClosed[2] = p.trigger
            }
            if (handClosed[i] && !handHad[i]) grab(i)
            if (!handClosed[i] && handHad[i]) release(i)
            // buttons are pressed with an open hand or a fingertip
            val tip = if (i < 2) host.hands[i].tip else host.pad.pose.position
            if (near(tip.x, tip.y, tip.z, button, 0.06f) && brewing <= 0f) pressBrew()
            if (near(tip.x, tip.y, tip.z, red, 0.06f) && celebrate < 0.5f) pressRed()
        }
        // physics for what is not held
        for (t in things) {
            if (t.gone) continue
            val h = t.heldBy
            if (h >= 0) { t.x = handPos[h][0]; t.y = handPos[h][1] - 0.02f; t.z = handPos[h][2]; continue }
            t.vy -= 9.81f * dt
            t.x += t.vx * dt; t.y += t.vy * dt; t.z += t.vz * dt
            // the counter top and the floor
            val onTable = abs(t.x) < 0.8f && t.z < tableZ + 0.32f && t.z > tableZ - 0.32f
            val floor = if (onTable && t.y > tableY - 0.05f) tableY else 0f
            // stacking on the plate: rest on the top of the stack
            val stackTop = if (near(t.x, plate[1], t.z, plate, 0.13f) && t.kind in BURGER) stackHeight(t) else floor
            val rest = max(floor, stackTop) + t.r * 0.5f
            if (t.y < rest) { t.y = rest; t.vy = 0f; t.vx *= 0.6f; t.vz *= 0.6f; if (abs(t.vx) < 0.02f) t.vx = 0f; if (abs(t.vz) < 0.02f) t.vz = 0f }
            // fell far: back to its place
            if (t.y < -1f || abs(t.x) > 6f || abs(t.z) > 6f) t.reset()
        }
        if (brewing > 0f) {
            brewing -= dt
            if (brewing <= 0f) {
                val mug = things.first { it.kind == Kind.MUG }
                if (near(mug.x, mug.y, mug.z, machine, 0.18f)) { mug.full = true; host.play(host.sCoin, machine[0], machine[1], machine[2]) }
                else say("A máquina serviu café no balcão. Onde estava a xícara?")
            }
        }
        checkTask()
        score = done * 100 + max(0, 300 - time.toInt())
        if (!over && task < tasks.size) status = if (bossTalk > 0f) "Chefe: $bossLine" else tasksText[tasks[task]]
    }

    private fun near(x: Float, y: Float, z: Float, p: FloatArray, r: Float): Boolean {
        val dx = x - p[0]; val dy = y - p[1]; val dz = z - p[2]
        return dx * dx + dy * dy + dz * dz < r * r
    }

    private fun stackHeight(self: Thing): Float {
        var top = tableY
        for (o in things) if (o !== self && o.heldBy < 0 && o.kind in BURGER && near(o.x, plate[1], o.z, plate, 0.13f) && o.y < self.y) top = max(top, o.y + o.r * 0.5f)
        return top
    }

    private fun grab(i: Int) {
        if (grabbing[i] >= 0) return
        var best = -1; var bd = 0.13f * 0.13f
        for ((k, t) in things.withIndex()) {
            if (t.gone || t.heldBy >= 0) continue
            val dx = t.x - handPos[i][0]; val dy = t.y - handPos[i][1]; val dz = t.z - handPos[i][2]
            val d = dx * dx + dy * dy + dz * dz
            if (d < bd) { bd = d; best = k }
        }
        if (best >= 0) { grabbing[i] = best; things[best].heldBy = i; host.play(host.sTick, handPos[i][0], handPos[i][1], handPos[i][2], 0.4f); if (i == 2) host.pad.buzz(20) }
    }

    private fun release(i: Int) {
        val k = grabbing[i]; if (k < 0) return
        grabbing[i] = -1
        val t = things[k]
        t.heldBy = -1
        // thrown with the hand's speed
        t.vx = handVel[i][0]; t.vy = handVel[i][1]; t.vz = handVel[i][2]
    }

    private fun pressBrew() {
        brewing = 2f
        host.play(host.sShoot, button[0], button[1], button[2], 0.5f, 0.6f)
    }

    private fun pressRed() {
        celebrate = 1.5f; redPresses++
        host.play(host.sBoom, red[0], red[1] + 0.5f, red[2], 0.5f, 1.4f)
        host.particles.burst(Particles.SPARK, red[0], red[1] + 0.1f, red[2], 50, 2.5f, rgba(1f, 0.3f, 0.5f), 1.2f, 0.01f, gravity = 3f, up = 2f)
        if (redPresses == 3) say("Você apertou três vezes. Vou registrar isso no seu histórico.")
    }

    private fun checkTask() {
        if (task >= tasks.size) return
        val ok = when (tasks[task]) {
            0 -> things.first { it.kind == Kind.MUG }.full
            1 -> things.first { it.kind == Kind.PAPER }.let { it.heldBy < 0 && abs(it.x - bin[0]) < 0.2f && abs(it.z - bin[2]) < 0.2f && it.y < 0.45f }
            2 -> {
                val s = listOf(Kind.BUN_BOTTOM, Kind.PATTY, Kind.CHEESE, Kind.BUN_TOP).map { k -> things.first { it.kind == k } }
                s.all { it.heldBy < 0 && near(it.x, plate[1], it.z, plate, 0.13f) } && s.zipWithNext().all { (a, b) -> b.y > a.y + 0.004f }
            }
            3 -> things.any { (it.kind == Kind.APPLE || it.kind == Kind.BANANA) && near(it.x, it.y, it.z, basket, 0.22f) }.also { hit ->
                if (hit) things.filter { it.kind == Kind.APPLE || it.kind == Kind.BANANA }.forEach { if (near(it.x, it.y, it.z, basket, 0.22f)) it.gone = true }
            }
            else -> redPresses > 0
        }
        if (!ok) return
        say(bossDone[tasks[task]])
        done++; task++
        host.play(host.sWin, 0f, 1.7f, -1.4f, 0.6f)
        host.particles.burst(Particles.SPARK, 0f, 1.8f, -1.4f, 40, 2f, rgba(0.4f, 0.9f, 1f), 1f, 0.01f, gravity = 2f, up = 1.5f)
        if (task >= tasks.size) {
            over = true
            status = "Expediente encerrado em ${time.toInt()} s! O chefe robô te deu uma estrela dourada."
            return
        }
        // the next task, and the things it needs back on the counter
        if (tasks[task] == 3) things.filter { it.kind == Kind.APPLE || it.kind == Kind.BANANA }.forEach { if (it.gone) it.reset() }
        status = tasksText[tasks[task]]
    }

    // ---------------------------------------------------------------- drawing
    private val wood = rgba(0.62f, 0.45f, 0.3f)
    private val wall = rgba(0.86f, 0.85f, 0.82f)
    private val steel = rgba(0.7f, 0.72f, 0.75f)

    override fun draw() {
        // room: back wall, side walls, counter
        host.drawBox(0f, 1.5f, -2.2f, 6f, 3f, 0.1f, wall, gloss = 0.05f, tex = "white", tile = 2f)
        host.drawBox(-3f, 1.5f, 0f, 0.1f, 3f, 4.4f, wall, gloss = 0.05f, tex = "white", tile = 2f)
        host.drawBox(3f, 1.5f, 0f, 0.1f, 3f, 4.4f, wall, gloss = 0.05f, tex = "white", tile = 2f)
        host.drawBox(0f, tableY / 2, tableZ, 1.6f, tableY, 0.64f, wood, gloss = 0.15f, tex = "wood", tile = 1.2f)
        host.drawBox(0f, tableY - 0.015f, tableZ, 1.64f, 0.03f, 0.68f, rgba(0.92f, 0.92f, 0.9f), gloss = 0.5f)
        // coffee machine with its green button
        host.drawRounded(machine[0], machine[1] + 0.2f, machine[2] - 0.05f, 0.24f, 0.4f, 0.22f, rgba(0.2f, 0.2f, 0.22f), gloss = 0.6f)
        host.drawBox(machine[0], machine[1] + 0.33f, machine[2] + 0.06f, 0.06f, 0.04f, 0.06f, steel, metal = 0.8f)
        host.drawSphere(button[0], button[1], button[2], 0.025f, if (brewing > 0f) rgba(1f, 0.9f, 0.3f) else rgba(0.2f, 0.85f, 0.35f), unlit = brewing > 0f)
        if (brewing > 0f) host.drawBeam(machine[0], machine[1] + 0.3f, machine[2] + 0.06f, machine[0], machine[1] + 0.06f, machine[2] + 0.06f, 0.006f, rgba(0.35f, 0.2f, 0.1f))
        // the red button on a pedestal
        host.drawCylinder(red[0], tableY + 0.015f, red[2], 0.06f, 0.03f, rgba(0.25f, 0.25f, 0.27f))
        host.drawSphere(red[0], red[1] + 0.01f, red[2], 0.04f, rgba(0.9f, 0.1f, 0.1f), gloss = 0.7f)
        // bin and plate and the boss's basket
        host.drawCylinder(bin[0], 0.2f, bin[2], 0.16f, 0.4f, rgba(0.3f, 0.55f, 0.35f), gloss = 0.3f)
        host.drawCylinder(plate[0], tableY + 0.005f, plate[2], 0.12f, 0.01f, rgba(0.97f, 0.97f, 0.97f), gloss = 0.6f)
        host.drawTorus(basket[0], basket[1], basket[2], 0.22f, rgba(0.75f, 0.55f, 0.3f), thick = 0.4f)
        // the boss: a screen floating on an arm, with a face
        val bob = sin(host.time * 1.5f) * 0.03f
        host.drawRounded(0f, 1.95f + bob, -1.85f, 0.9f, 0.55f, 0.08f, rgba(0.12f, 0.13f, 0.16f), gloss = 0.7f)
        host.drawBox(0f, 1.95f + bob, -1.8f, 0.8f, 0.45f, 0.01f, rgba(0.1f, 0.5f, 0.85f), unlit = true)
        val blink = if ((host.time % 4f) < 0.15f) 0.2f else 1f
        for (s in listOf(-1f, 1f)) host.drawEllipsoid(0.17f * s, 2.0f + bob, -1.79f, 0.09f, 0.12f * blink, 0.01f, rgba(1f, 1f, 1f), unlit = true)
        val talk = if (bossTalk > 0f) abs(sin(host.time * 14f)) * 0.06f + 0.02f else 0.015f
        host.drawBox(0f, 1.85f + bob, -1.79f, 0.22f, talk, 0.01f, rgba(1f, 1f, 1f), unlit = true)
        if (celebrate > 0f) host.glow(0f, 1.95f, -1.8f, 0.8f, rgba(1f, 0.3f, 0.6f), celebrate * 0.4f)
        // the things
        for (t in things) {
            if (t.gone) continue
            when (t.kind) {
                Kind.MUG -> {
                    host.drawCylinder(t.x, t.y, t.z, 0.045f, 0.09f, rgba(0.95f, 0.95f, 0.95f), gloss = 0.7f)
                    if (t.full) host.drawCylinder(t.x, t.y + 0.04f, t.z, 0.04f, 0.005f, rgba(0.3f, 0.17f, 0.08f), gloss = 0.8f)
                    host.drawTorus(t.x + 0.055f, t.y, t.z, 0.022f, rgba(0.95f, 0.95f, 0.95f), thick = 0.5f)
                }
                Kind.PAPER -> host.drawSphere(t.x, t.y, t.z, 0.04f, rgba(0.95f, 0.95f, 0.92f), gloss = 0.05f)
                Kind.BUN_BOTTOM -> host.drawEllipsoid(t.x, t.y, t.z, 0.12f, 0.035f, 0.12f, rgba(0.85f, 0.6f, 0.3f), gloss = 0.3f)
                Kind.PATTY -> host.drawEllipsoid(t.x, t.y, t.z, 0.115f, 0.03f, 0.115f, rgba(0.35f, 0.2f, 0.12f), gloss = 0.3f)
                Kind.CHEESE -> host.drawBox(t.x, t.y, t.z, 0.11f, 0.008f, 0.11f, rgba(1f, 0.8f, 0.2f), gloss = 0.4f)
                Kind.BUN_TOP -> host.drawEllipsoid(t.x, t.y, t.z, 0.12f, 0.06f, 0.12f, rgba(0.88f, 0.62f, 0.3f), gloss = 0.4f)
                Kind.APPLE -> host.drawModel("food/apple", t.x, t.y - 0.04f, t.z, scale = 1.1f)
                Kind.BANANA -> host.drawModel("food/banana", t.x, t.y - 0.03f, t.z, scale = 1.2f)
            }
        }
        // what you are holding glows a little; hands near something show it can be taken
        for (i in 0..2) if (grabbing[i] >= 0) { val t = things[grabbing[i]]; host.glow(t.x, t.y, t.z, 0.12f, rgba(0.6f, 0.85f, 1f), 0.25f) }
    }

    companion object {
        private val BURGER = setOf(Kind.BUN_BOTTOM, Kind.PATTY, Kind.CHEESE, Kind.BUN_TOP)
    }
}
