package com.hyperxr.questgames

import com.hyperxr.math.Vec3
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// Puzzle games played on a table in front of you: touch with a fingertip, grab with a closed hand
// or a pinch (the controller's trigger works too).

private val ROOM = GameSky.Look(
    zenith = rgb(0.75f, 0.74f, 0.72f), horizon = rgb(0.85f, 0.83f, 0.8f), ground = rgb(0.5f, 0.42f, 0.35f),
    sunDir = floatArrayOf(0.3f, 0.85f, 0.4f), floor = GameSky.FLOOR_PLAIN, floorColor = rgb(0.45f, 0.36f, 0.28f),
    fog = 0.02f, light = rgb(1f, 0.95f, 0.88f), ambient = rgb(0.5f, 0.48f, 0.47f),
)
private const val TABLE_Y = 0.85f

/** Fingertips (and the controller) touching a small spot this frame, edge-triggered. */
internal class Toucher(val host: GameHost) {
    private val inside = HashMap<String, Boolean>()
    private val p = Vec3()
    fun tapped(id: String, x: Float, y: Float, z: Float, r: Float = 0.035f): Boolean {
        var now = false
        for (i in 0..2) {
            val ok = when (i) { 0, 1 -> host.hands[i].usable.also { if (it) p.set(host.hands[i].tip) }; else -> host.pad.connected.also { if (it) p.set(host.pad.pose.position) } }
            if (ok && dist(p.x, p.y, p.z, x, y, z) < r) now = true
        }
        val was = inside[id] ?: false
        inside[id] = now
        return now && !was
    }
}

private fun table(host: GameHost, w: Float = 1.4f, d: Float = 0.7f, z: Float = -0.55f) {
    host.drawBox(0f, TABLE_Y / 2, z, w, TABLE_Y, d, rgb(0.55f, 0.38f, 0.24f), tex = "wood", tile = 1f)
}

/** Bomba!: cut the right wire, press the right buttons in order, before the timer runs out. The rules are on the card. */
class BombGame(host: GameHost) : QuestGame(host) {
    override val title = "Bomba!"
    override val look = ROOM
    private val touch = Toucher(host)
    private val rnd = java.util.Random()
    private var time = 120f
    private var level = 1
    private val wires = IntArray(5)   // colour index
    private val cut = BooleanArray(5)
    private var wireTarget = 0
    private val buttons = IntArray(4)
    private var seq = IntArray(0); private var seqPos = 0
    private var wireDone = false; private var btnDone = false
    private var strikes = 0
    private var rule = ""
    private val names = arrayOf("vermelho", "azul", "amarelo", "branco", "preto")
    private val colors = arrayOf(rgb(0.9f, 0.15f, 0.15f), rgb(0.2f, 0.4f, 0.95f), rgb(0.95f, 0.85f, 0.2f), rgb(0.95f, 0.95f, 0.95f), rgb(0.1f, 0.1f, 0.1f))

    override fun start() { arm(); status = "Leia o cartão à esquerda e desarme os dois módulos. Toque com a ponta do dedo" }

    private fun arm() {
        for (i in 0 until 5) { wires[i] = rnd.nextInt(5); cut[i] = false }
        val reds = wires.count { it == 0 }
        wireTarget = when {
            reds == 0 -> 1
            wires[4] == 3 -> 4
            reds > 1 -> wires.indexOfLast { it == 0 }
            else -> 0
        }
        rule = "FIOS: sem vermelho, corte o 2º. Último fio branco, corte o último. Mais de um vermelho, corte o último vermelho. Senão, corte o 1º.\n" +
            "BOTÕES: aperte em ordem crescente dos números, mas se houver um 7, ele vem primeiro."
        val nums = (1..9).shuffled(rnd).take(4)
        for (i in 0 until 4) buttons[i] = nums[i]
        val sorted = nums.sorted().toMutableList()
        if (7 in sorted) { sorted.remove(7); sorted.add(0, 7) }
        seq = sorted.map { n -> buttons.indexOf(n) }.toIntArray(); seqPos = 0
        wireDone = false; btnDone = false; time = 120f - level * 10f
    }

    private fun strike() { strikes++; time -= 15f; host.play(host.sMiss, 0f, 1f, -0.5f); if (strikes >= 3) boom() }
    private fun boom() { over = true; status = "BUM! A bomba explodiu no nível $level"; host.play(host.sBoom, 0f, 1f, -0.5f, 1f, 0.6f); host.particles.burst(Particles.SPARK, 0f, 1f, -0.55f, 80, 4f, rgb(1f, 0.6f, 0.2f), 1.5f, 0.02f) }

    override fun update(dt: Float) {
        time -= dt
        if (time <= 0f && !over) { boom(); return }
        // wires: a fingertip across the middle of a wire cuts it
        for (i in 0 until 5) if (!cut[i] && !wireDone && touch.tapped("w$i", 0.05f + i * 0.06f, TABLE_Y + 0.18f, -0.5f, 0.03f)) {
            cut[i] = true
            if (i == wireTarget) { wireDone = true; host.play(host.sCoin, 0.2f, 1f, -0.5f) } else strike()
        }
        for (i in 0 until 4) if (!btnDone && touch.tapped("b$i", -0.3f + i * 0.08f, TABLE_Y + 0.12f, -0.42f, 0.035f)) {
            if (seq[seqPos] == i) { seqPos++; host.play(host.sTick, 0f, 1f, -0.5f); if (seqPos == 4) { btnDone = true; host.play(host.sCoin, 0f, 1f, -0.5f) } }
            else { strike(); seqPos = 0 }
        }
        if (wireDone && btnDone && !over) {
            score += 500 + time.toInt() * 5
            level++
            if (level > 5) { over = true; status = "Você desarmou 5 bombas! $score pontos" } else { arm(); status = "Desarmada! Próxima bomba: nível $level" }
        } else if (!over) status = "${time.toInt()} s · erros $strikes de 3 · nível $level"
    }

    override fun draw() {
        table(host)
        host.drawRounded(0f, TABLE_Y + 0.1f, -0.5f, 0.75f, 0.2f, 0.3f, rgb(0.25f, 0.27f, 0.25f), gloss = 0.4f)
        // timer display
        host.drawBox(0f, TABLE_Y + 0.21f, -0.6f, 0.2f, 0.06f, 0.01f, rgb(0.05f, 0.05f, 0.05f))
        val blink = (time % 1f) > 0.5f
        host.glow(0f, TABLE_Y + 0.21f, -0.59f, 0.06f + (if (blink) 0.02f else 0f), rgb(1f, 0.1f, 0.1f), 0.8f)
        for (i in 0 until 5) {
            val x = 0.05f + i * 0.06f
            if (cut[i]) {
                host.drawBox(x, TABLE_Y + 0.18f, -0.56f, 0.012f, 0.012f, 0.06f, colors[wires[i]])
                host.drawBox(x, TABLE_Y + 0.18f, -0.44f, 0.012f, 0.012f, 0.06f, colors[wires[i]])
            } else host.drawBox(x, TABLE_Y + 0.18f, -0.5f, 0.012f, 0.012f, 0.18f, colors[wires[i]], gloss = 0.5f)
        }
        for (i in 0 until 4) {
            val pressed = seqPos > 0 && seq.take(seqPos).contains(i) || btnDone
            host.drawCylinder(-0.3f + i * 0.08f, TABLE_Y + 0.2f, -0.42f, 0.028f, 0.02f, if (pressed) rgb(0.3f, 0.9f, 0.4f) else rgb(0.85f, 0.85f, 0.85f), gloss = 0.6f)
            // number as dots on the button (1..9)
            for (k in 0 until buttons[i]) host.drawSphere(-0.3f + i * 0.08f - 0.016f + (k % 3) * 0.016f, TABLE_Y + 0.212f, -0.435f + (k / 3) * 0.013f, 0.004f, rgb(0.1f, 0.1f, 0.1f), shadow = false)
        }
        // the rule card, readable as a panel hint (the HUD shows the text)
        host.drawBox(-0.5f, TABLE_Y + 0.005f, -0.45f, 0.25f, 0.005f, 0.32f, rgb(0.97f, 0.95f, 0.88f))
        if (!over && host.time.toInt() % 8 < 4) status = rule.substringBefore('\n')
        else if (!over) status = rule.substringAfter('\n')
    }
}

/** Cofre do Espião: a desk with a safe. Find the code in the room, open the safe, before the gas fills the room. */
class SpyDeskGame(host: GameHost) : QuestGame(host) {
    override val title = "Cofre do Espião"
    override val look = ROOM
    private val touch = Toucher(host)
    private val grab = Grabber(host, reach = 0.15f)
    private val book = Prop(-0.4f, TABLE_Y + 0.03f, -0.5f, 0.08f)
    private val lamp = Prop(0.45f, TABLE_Y + 0.1f, -0.65f, 0.06f)
    private val code = IntArray(3) { (1..9).random() }
    private val entered = ArrayList<Int>()
    private var found = false
    private var open = false
    private var gas = 0f
    private var drawerOpen = false

    override fun start() { status = "Bilhete do chefe: 'o código está onde a luz não chega'. Mexa nas coisas da mesa" }

    override fun update(dt: Float) {
        gas += dt / 180f
        grab.update(listOf(book, lamp))
        book.physics(dt, floor = TABLE_Y); lamp.physics(dt, floor = TABLE_Y)
        // the code is written under the book (move it away from its spot)
        if (!found && dist(book.x, 0f, book.z, book.home[0], 0f, book.home[2]) > 0.18f) { found = true; host.play(host.sCoin, book.home[0], TABLE_Y, book.home[2]); status = "Embaixo do livro: ${code.joinToString(" ")}" }
        if (touch.tapped("drawer", 0f, TABLE_Y - 0.12f, -0.22f, 0.06f)) { drawerOpen = !drawerOpen; host.play(host.sTick, 0f, 0.7f, -0.25f); if (drawerOpen) status = "A gaveta tem um cartão: 'nunca toque no botão vermelho'" }
        // keypad on the safe: 9 keys
        if (!open) for (k in 1..9) {
            val kx = 0.16f + ((k - 1) % 3) * 0.04f; val ky = TABLE_Y + 0.36f - ((k - 1) / 3) * 0.04f
            if (touch.tapped("k$k", kx, ky, -0.62f, 0.02f)) {
                entered += k; host.play(host.sTick, kx, ky, -0.62f, 0.5f, 1f + k * 0.05f)
                if (entered.size == 3) {
                    if (entered.toIntArray().contentEquals(code)) { open = true; score = 1000 + ((1f - gas) * 1000).toInt(); host.play(host.sWin, 0.2f, 1.1f, -0.6f); over = true; status = "Cofre aberto! Missão cumprida" }
                    else { host.play(host.sMiss, 0.2f, 1.1f, -0.6f); gas += 0.1f }
                    entered.clear()
                }
            }
        }
        if (touch.tapped("red", -0.15f, TABLE_Y + 0.02f, -0.35f, 0.03f)) { gas += 0.3f; host.play(host.sBoom, 0f, 1f, -0.4f, 0.5f, 0.6f); status = "O gás aumentou! Avisaram para não apertar…" }
        if (gas >= 1f && !over) { over = true; status = "O gás tomou a sala. O código era ${code.joinToString("")}" }
        else if (!over && host.time.toInt() % 10 == 0 && !found) status = "Gás ${(gas * 100).toInt()}% · procure o código"
    }

    override fun draw() {
        table(host)
        host.drawBox(0f, TABLE_Y - 0.12f, -0.36f + (if (drawerOpen) 0.15f else 0f), 0.4f, 0.1f, 0.28f, rgb(0.45f, 0.3f, 0.2f), tex = "wood", tile = 0.5f)
        // the safe
        host.drawBox(0.2f, TABLE_Y + 0.22f, -0.72f, 0.3f, 0.44f, 0.22f, rgb(0.3f, 0.32f, 0.35f), metal = 0.8f, gloss = 0.5f)
        if (open) host.drawBox(0.2f, TABLE_Y + 0.22f, -0.6f, 0.26f, 0.38f, 0.02f, rgb(1f, 0.85f, 0.3f), unlit = true)
        for (k in 1..9) host.drawBox(0.16f + ((k - 1) % 3) * 0.04f, TABLE_Y + 0.36f - ((k - 1) / 3) * 0.04f, -0.605f, 0.03f, 0.03f, 0.01f, if (entered.contains(k)) rgb(0.3f, 0.9f, 0.4f) else rgb(0.8f, 0.8f, 0.82f))
        host.drawBox(book.x, book.y, book.z, 0.18f, 0.05f, 0.24f, rgb(0.5f, 0.1f, 0.15f), yaw = book.yaw)
        host.drawBox(book.home[0], TABLE_Y + 0.002f, book.home[2], 0.06f, 0.002f, 0.03f, rgb(0.1f, 0.1f, 0.1f))
        host.drawCylinder(lamp.x, lamp.y, lamp.z, 0.05f, 0.18f, rgb(0.2f, 0.5f, 0.3f), gloss = 0.5f)
        host.glow(lamp.x, lamp.y + 0.12f, lamp.z, 0.25f, rgb(1f, 0.9f, 0.6f), 0.5f)
        host.drawCylinder(-0.15f, TABLE_Y + 0.01f, -0.35f, 0.03f, 0.02f, rgb(0.9f, 0.1f, 0.1f), gloss = 0.7f)
        if (gas > 0.2f) host.glow(host.head.x, host.head.y - 0.4f, host.head.z, 1.5f, rgb(0.4f, 0.9f, 0.3f), gas * 0.5f)
    }
}

/** Peças no Lugar: a little house in pieces; grab each one and bring it near its ghost to snap it in. */
class PiecesGame(host: GameHost) : QuestGame(host) {
    override val title = "Peças no Lugar"
    override val look = ROOM
    private class Piece(val gx: Float, val gy: Float, val gz: Float, val sx: Float, val sy: Float, val sz: Float, val color: FloatArray) { var placed = false }
    private val pieces = ArrayList<Piece>()
    private val props = ArrayList<Prop>()
    private val grab = Grabber(host, reach = 0.15f)
    private val cx = 0f; private val cz = -0.6f
    private var t = 0f

    override fun start() {
        val wall = rgb(0.95f, 0.88f, 0.75f); val roof = rgb(0.75f, 0.25f, 0.2f); val wood = rgb(0.5f, 0.32f, 0.2f)
        pieces += Piece(0f, 0.02f, 0f, 0.3f, 0.02f, 0.22f, rgb(0.4f, 0.65f, 0.3f))
        pieces += Piece(-0.13f, 0.1f, 0f, 0.02f, 0.14f, 0.2f, wall); pieces += Piece(0.13f, 0.1f, 0f, 0.02f, 0.14f, 0.2f, wall)
        pieces += Piece(0f, 0.1f, -0.1f, 0.26f, 0.14f, 0.02f, wall); pieces += Piece(0f, 0.1f, 0.1f, 0.26f, 0.14f, 0.02f, wall)
        pieces += Piece(0f, 0.2f, 0f, 0.3f, 0.04f, 0.24f, roof); pieces += Piece(0f, 0.25f, 0f, 0.2f, 0.04f, 0.18f, roof)
        pieces += Piece(0.08f, 0.28f, -0.04f, 0.04f, 0.08f, 0.04f, wood); pieces += Piece(0f, 0.08f, 0.11f, 0.05f, 0.09f, 0.01f, wood)
        val rnd = java.util.Random(5)
        for ((k, p) in pieces.withIndex()) props += Prop(-0.55f + (k % 5) * 0.25f + rnd.nextFloat() * 0.05f, TABLE_Y + 0.05f, -0.3f - (k / 5) * 0.12f, 0.05f).also { it.tag = k }
        status = "Monte a casinha: pegue cada peça e leve até a sombra dela"
    }

    override fun update(dt: Float) {
        t += dt
        grab.update(props.filter { !pieces[it.tag].placed })
        for (pr in props) {
            val pc = pieces[pr.tag]
            if (pc.placed) continue
            pr.physics(dt, floor = TABLE_Y)
            if (dist(pr.x, pr.y, pr.z, cx + pc.gx, TABLE_Y + pc.gy, cz + pc.gz) < 0.06f) {
                pc.placed = true; grab.drop(pr); score += 100
                host.play(host.sCoin, pr.x, pr.y, pr.z, 0.6f, 1.2f)
                host.particles.burst(Particles.SPARK, pr.x, pr.y, pr.z, 12, 1f, rgb(1f, 0.9f, 0.5f), 0.4f, 0.005f, gravity = 0f)
            }
        }
        val left = pieces.count { !it.placed }
        if (left == 0 && !over) { over = true; score += max(0, 300 - t.toInt()) * 3; status = "Casinha pronta em ${t.toInt()} s!" }
        else if (!over) status = "Faltam $left peças · ${t.toInt()} s"
    }

    override fun draw() {
        table(host, 1.6f, 0.8f)
        for ((k, pc) in pieces.withIndex()) {
            if (pc.placed) host.drawBox(cx + pc.gx, TABLE_Y + pc.gy, cz + pc.gz, pc.sx, pc.sy, pc.sz, pc.color, gloss = 0.3f)
            else {
                host.drawBox(cx + pc.gx, TABLE_Y + pc.gy, cz + pc.gz, pc.sx, pc.sy, pc.sz, rgb(0.6f, 0.8f, 1f, 0.15f + 0.1f * sin(t * 3f + k)), unlit = true, shadow = false)
                val pr = props[k]
                host.drawBox(pr.x, pr.y, pr.z, pc.sx, pc.sy, pc.sz, pc.color, gloss = 0.3f)
            }
        }
    }
}

/** Cubos: fill the glowing box exactly with the block pieces (turn them by dropping them in; they snap to the grid). */
class CubesGame(host: GameHost) : QuestGame(host) {
    override val title = "Cubos"
    override val look = ROOM
    private val cell = 0.06f
    private val origin = floatArrayOf(-0.09f, TABLE_Y + 0.03f, -0.66f)
    private val box = intArrayOf(3, 2, 2)
    private class Shape(val cells: List<IntArray>, val color: FloatArray)
    private val shapes = listOf(
        Shape(listOf(intArrayOf(0, 0, 0), intArrayOf(1, 0, 0), intArrayOf(2, 0, 0), intArrayOf(0, 1, 0)), rgb(0.95f, 0.4f, 0.3f)),
        Shape(listOf(intArrayOf(0, 0, 0), intArrayOf(1, 0, 0), intArrayOf(1, 1, 0), intArrayOf(2, 1, 0)), rgb(0.3f, 0.7f, 1f)),
        Shape(listOf(intArrayOf(0, 0, 0), intArrayOf(0, 0, 1), intArrayOf(1, 0, 1), intArrayOf(1, 1, 1)), rgb(0.4f, 0.9f, 0.4f)),
    )
    private val props = shapes.mapIndexed { i, _ -> Prop(-0.45f + i * 0.3f, TABLE_Y + 0.05f, -0.35f, 0.07f).also { it.tag = i } }
    private val placedAt = arrayOfNulls<IntArray>(3)
    private val grab = Grabber(host, reach = 0.15f)
    private var t = 0f

    override fun start() { status = "Encha a caixa brilhante com as 3 peças, sem sobrar espaço. Solte a peça dentro da caixa" }

    override fun update(dt: Float) {
        t += dt
        grab.update(props, onGrab = { p, _ -> placedAt[p.tag] = null })
        for (p in props) {
            if (p.held >= 0 || placedAt[p.tag] != null) continue
            // dropped inside the box: snap to the nearest cell if it fits
            val gx = ((p.x - origin[0]) / cell).roundToInt(); val gy = ((p.y - origin[1]) / cell).roundToInt(); val gz = ((p.z - origin[2]) / cell).roundToInt()
            if (abs(p.x - (origin[0] + cell)) < 0.15f && abs(p.z - (origin[2] + cell / 2)) < 0.12f && p.y < origin[1] + 0.2f) {
                val at = intArrayOf(gx.coerceIn(0, 2), 0, gz.coerceIn(0, 1))
                if (fits(p.tag, at)) { placedAt[p.tag] = at; host.play(host.sTick, p.x, p.y, p.z); continue }
                // try stacking on top
                at[1] = 1
                if (fits(p.tag, at)) { placedAt[p.tag] = at; host.play(host.sTick, p.x, p.y, p.z); continue }
            }
            p.physics(dt, floor = TABLE_Y)
        }
        val full = filled().size == box[0] * box[1] * box[2]
        if (full && !over) { over = true; score = 1000 + max(0, 200 - t.toInt()) * 5; host.play(host.sWin, 0f, 1.1f, -0.6f); status = "Encaixou tudo em ${t.toInt()} s!" }
        else if (!over) status = "${filled().size} de 12 espaços · ${t.toInt()} s"
    }

    private fun cellsOf(i: Int, at: IntArray) = shapes[i].cells.map { intArrayOf(it[0] + at[0], it[1] + at[1], it[2] + at[2]) }

    private fun filled(except: Int = -1): Set<Int> {
        val s = HashSet<Int>()
        for (i in 0 until 3) { if (i == except) continue; val at = placedAt[i] ?: continue; for (c in cellsOf(i, at)) s += c[0] * 100 + c[1] * 10 + c[2] }
        return s
    }

    private fun fits(i: Int, at: IntArray): Boolean {
        val used = filled(i)
        return cellsOf(i, at).all { c -> c[0] in 0 until box[0] && c[1] in 0 until box[1] && c[2] in 0 until box[2] && (c[0] * 100 + c[1] * 10 + c[2]) !in used }
    }

    override fun draw() {
        table(host)
        // the target box, outlined
        host.drawBox(origin[0] + cell, origin[1] + cell / 2 - 0.03f + cell / 2, origin[2] + cell / 2, box[0] * cell, box[1] * cell, box[2] * cell, rgb(0.5f, 0.8f, 1f, 0.18f), unlit = true, shadow = false)
        for ((i, s) in shapes.withIndex()) {
            val at = placedAt[i]
            val p = props[i]
            for (c in s.cells) {
                val x: Float; val y: Float; val z: Float
                if (at != null) { x = origin[0] + (c[0] + at[0]) * cell; y = origin[1] + (c[1] + at[1]) * cell; z = origin[2] + (c[2] + at[2]) * cell }
                else { x = p.x + c[0] * cell; y = p.y + c[1] * cell; z = p.z + c[2] * cell }
                host.drawRounded(x, y, z, cell * 0.96f, cell * 0.96f, cell * 0.96f, s.color, gloss = 0.5f)
            }
        }
    }
}

/** Blocos que Caem: falling blocks on a board in front of you. Swipe a hand left or right, pinch to turn, push down to drop. */
class FallingBlocksGame(host: GameHost) : QuestGame(host) {
    override val title = "Blocos que Caem"
    override val look = GameSky.Look(zenith = rgb(0.02f, 0.02f, 0.06f), horizon = rgb(0.1f, 0.05f, 0.2f), ground = rgb(0.02f, 0.01f, 0.04f),
        stars = 1f, nebula = floatArrayOf(0.5f, 0.1f, 0.6f, 0.6f), fog = 0.01f, light = rgb(0.9f, 0.85f, 1f), ambient = rgb(0.4f, 0.35f, 0.5f))
    override val shadowRadius = 0f
    private val w = 10; private val h = 18
    private val grid = IntArray(w * h)
    private val pieces = arrayOf(
        intArrayOf(0, 0, 1, 0, 2, 0, 3, 0), intArrayOf(0, 0, 1, 0, 0, 1, 1, 1), intArrayOf(0, 0, 1, 0, 2, 0, 1, 1),
        intArrayOf(0, 0, 1, 0, 2, 0, 2, 1), intArrayOf(0, 0, 1, 0, 2, 0, 0, 1), intArrayOf(0, 0, 1, 0, 1, 1, 2, 1), intArrayOf(1, 0, 2, 0, 0, 1, 1, 1),
    )
    private val colors = arrayOf(rgb(0.3f, 0.9f, 1f), rgb(1f, 0.9f, 0.2f), rgb(0.7f, 0.3f, 1f), rgb(1f, 0.6f, 0.2f), rgb(0.3f, 0.4f, 1f), rgb(0.3f, 1f, 0.4f), rgb(1f, 0.3f, 0.35f))
    private var cur = IntArray(8); private var kind = 0; private var px = 4; private var py = 0
    private var fall = 0f; private var lines = 0
    private val lastX = FloatArray(2); private val wasPinch = BooleanArray(2); private var moveCool = 0f

    override fun start() { spawn(); status = "Mão para a esquerda/direita move; pinça gira; empurre a mão para baixo para derrubar" }

    private fun spawn() { kind = (0 until 7).random(); cur = pieces[kind].copyOf(); px = 3; py = 0; if (!fits(cur, px, py)) { over = true; status = "Fim: $lines linhas, $score pontos" } }
    private fun fits(p: IntArray, x: Int, y: Int): Boolean {
        for (k in 0 until 4) { val cx = p[k * 2] + x; val cy = p[k * 2 + 1] + y; if (cx !in 0 until w || cy >= h || (cy >= 0 && grid[cy * w + cx] != 0)) return false }
        return true
    }

    override fun update(dt: Float) {
        moveCool -= dt
        for (i in 0..1) {
            val g = host.hands[i]
            if (!g.usable) continue
            val dx = g.palm.x - lastX[i]
            if (moveCool <= 0f && abs(dx) > 0.06f) { val step = if (dx > 0) 1 else -1; if (fits(cur, px + step, py)) px += step; lastX[i] = g.palm.x; moveCool = 0.08f }
            if (abs(dx) > 0.2f) lastX[i] = g.palm.x
            if (g.pinch && !wasPinch[i]) rotate()
            wasPinch[i] = g.pinch
            if (g.velocity.y < -1.8f) { while (fits(cur, px, py + 1)) py++; fall = 1f }
        }
        if (host.pad.connected) {
            if (moveCool <= 0f && abs(host.pad.stickX) > 0.5f) { val s = if (host.pad.stickX > 0) 1 else -1; if (fits(cur, px + s, py)) px += s; moveCool = 0.15f }
            if (host.pad.triggerDown) rotate()
            if (host.pad.stickY < -0.7f) fall += dt * 10f
        }
        fall += dt * (1.2f + lines * 0.05f)
        if (fall >= 1f) {
            fall = 0f
            if (fits(cur, px, py + 1)) py++
            else {
                for (k in 0 until 4) { val cy = cur[k * 2 + 1] + py; if (cy >= 0) grid[cy * w + cur[k * 2] + px] = kind + 1 }
                var cleared = 0
                var y = h - 1
                while (y >= 0) {
                    if ((0 until w).all { grid[y * w + it] != 0 }) {
                        for (yy in y downTo 1) for (x in 0 until w) grid[yy * w + x] = grid[(yy - 1) * w + x]
                        for (x in 0 until w) grid[x] = 0
                        cleared++
                    } else y--
                }
                if (cleared > 0) { lines += cleared; score += intArrayOf(0, 100, 300, 500, 800)[cleared]; host.play(host.sCoin, 0f, 1.5f, -1.5f, 0.6f, 1f + cleared * 0.2f) }
                else host.play(host.sTick, 0f, 1f, -1.5f, 0.4f)
                spawn()
            }
        }
        if (!over) status = "Linhas $lines · $score pontos"
    }

    private fun rotate() {
        val r = IntArray(8)
        for (k in 0 until 4) { r[k * 2] = -cur[k * 2 + 1] + 1; r[k * 2 + 1] = cur[k * 2] }
        if (fits(r, px, py)) cur = r else if (fits(r, px - 1, py)) { cur = r; px-- } else if (fits(r, px + 1, py)) { cur = r; px++ }
    }

    override fun draw() {
        val s = 0.09f; val ox = -w * s / 2; val oy = 2.4f; val z = -1.8f
        host.drawBox(0f, oy - h * s / 2, z - 0.05f, w * s + 0.05f, h * s + 0.05f, 0.02f, rgb(0.05f, 0.05f, 0.1f, 0.7f))
        for (y in 0 until h) for (x in 0 until w) { val c = grid[y * w + x]; if (c != 0) host.drawRounded(ox + x * s + s / 2, oy - y * s - s / 2, z, s * 0.94f, s * 0.94f, s * 0.6f, colors[c - 1], gloss = 0.7f) }
        for (k in 0 until 4) host.drawRounded(ox + (cur[k * 2] + px) * s + s / 2, oy - (cur[k * 2 + 1] + py) * s - s / 2, z, s * 0.94f, s * 0.94f, s * 0.6f, colors[kind], gloss = 0.9f)
        host.glow(0f, oy - h * s / 2, z - 0.1f, 1.2f, colors[kind], 0.08f)
    }
}

/** Cidadezinha: a town on the table. Tap a lot to build (road, house, shop, park); people move in if they have what they need. */
class TinyTownGame(host: GameHost) : QuestGame(host) {
    override val title = "Cidadezinha"
    override val look = GameSky.Look(zenith = rgb(0.4f, 0.65f, 0.95f), horizon = rgb(0.9f, 0.9f, 0.85f), ground = rgb(0.5f, 0.45f, 0.38f),
        sunDir = floatArrayOf(0.4f, 0.8f, 0.3f), floor = GameSky.FLOOR_PLAIN, floorColor = rgb(0.5f, 0.42f, 0.33f), fog = 0.01f)
    private val n = 8
    private val tile = 0.08f
    private val ox = -n * 0.08f / 2; private val oz = -0.85f
    private val map = IntArray(n * n)  // 0 grass 1 road 2 house 3 shop 4 park
    private var tool = 1
    private val touch = Toucher(host)
    private var money = 100f; private var people = 0; private var t = 0f
    private val toolNames = arrayOf("", "Rua", "Casa", "Loja", "Parque")
    private val cost = intArrayOf(0, 5, 20, 30, 15)

    override fun start() { status = "Toque nos botões para escolher (Rua, Casa, Loja, Parque) e toque num lote para construir. Casas precisam de rua ao lado" }

    override fun update(dt: Float) {
        t += dt
        for (k in 1..4) if (touch.tapped("tool$k", -0.36f + (k - 1) * 0.24f, TABLE_Y + 0.03f, -0.32f, 0.05f)) { tool = k; host.play(host.sTick, 0f, 1f, -0.3f) }
        for (y in 0 until n) for (x in 0 until n) {
            val cx = ox + x * tile + tile / 2; val cz = oz + y * tile + tile / 2
            if (touch.tapped("c$x,$y", cx, TABLE_Y + 0.03f, cz, 0.04f)) {
                val i = y * n + x
                if (map[i] == 0 && money >= cost[tool]) { map[i] = tool; money -= cost[tool]; host.play(host.sPunch, cx, TABLE_Y, cz, 0.4f, 1.5f) }
                else if (map[i] != 0) { map[i] = 0; host.play(host.sTick, cx, TABLE_Y, cz, 0.4f, 0.7f) }
            }
        }
        // the town lives: houses with a road fill up, shops pay with customers, parks make people happy
        var pop = 0; var income = 0f; var happy = 0
        for (y in 0 until n) for (x in 0 until n) {
            val v = map[y * n + x]
            val road = neighbors(x, y).any { it == 1 }
            if (v == 2 && road) pop += 4 + (if (neighbors(x, y).any { it == 4 }) 2 else 0)
            if (v == 3 && road) income += 1f
            if (v == 4) happy++
        }
        people = pop
        money += dt * (min(income, pop / 4f) * 0.8f + pop * 0.05f)
        score = people * 10 + happy * 20
        if (people >= 120 && !over) { over = true; status = "Sua cidade chegou a $people moradores em ${t.toInt()} s!" }
        else if (!over) status = "Ferramenta: ${toolNames[tool]} (${cost[tool]}) · dinheiro ${money.toInt()} · moradores $people de 120"
    }

    private fun neighbors(x: Int, y: Int) = listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1).filter { (a, b) -> a in 0 until n && b in 0 until n }.map { (a, b) -> map[b * n + a] }

    override fun draw() {
        table(host, 1.1f, 0.95f, -0.6f)
        for (y in 0 until n) for (x in 0 until n) {
            val cx = ox + x * tile + tile / 2; val cz = oz + y * tile + tile / 2
            when (map[y * n + x]) {
                0 -> host.drawBox(cx, TABLE_Y + 0.005f, cz, tile * 0.96f, 0.01f, tile * 0.96f, rgb(0.4f, 0.7f, 0.35f))
                1 -> host.drawBox(cx, TABLE_Y + 0.005f, cz, tile, 0.01f, tile, rgb(0.3f, 0.3f, 0.32f))
                2 -> { host.drawBox(cx, TABLE_Y + 0.03f, cz, tile * 0.7f, 0.05f, tile * 0.7f, rgb(0.95f, 0.9f, 0.8f)); host.drawBox(cx, TABLE_Y + 0.065f, cz, tile * 0.75f, 0.02f, tile * 0.75f, rgb(0.8f, 0.3f, 0.25f)) }
                3 -> host.drawBox(cx, TABLE_Y + 0.04f, cz, tile * 0.8f, 0.07f, tile * 0.8f, rgb(0.3f, 0.6f, 0.95f), gloss = 0.6f)
                4 -> { host.drawBox(cx, TABLE_Y + 0.006f, cz, tile, 0.012f, tile, rgb(0.3f, 0.8f, 0.3f)); host.drawSphere(cx, TABLE_Y + 0.04f, cz, 0.02f, rgb(0.2f, 0.55f, 0.2f)) }
            }
        }
        for (k in 1..4) {
            val c = when (k) { 1 -> rgb(0.3f, 0.3f, 0.32f); 2 -> rgb(0.8f, 0.3f, 0.25f); 3 -> rgb(0.3f, 0.6f, 0.95f); else -> rgb(0.3f, 0.8f, 0.3f) }
            host.drawRounded(-0.36f + (k - 1) * 0.24f, TABLE_Y + 0.02f, -0.32f, 0.12f, if (tool == k) 0.04f else 0.02f, 0.07f, c, gloss = 0.6f)
        }
    }
}
