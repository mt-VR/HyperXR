package com.hyperxr.questgames

import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.hyperxr.input.GrabDrag
import com.hyperxr.math.Pose
import com.hyperxr.math.Vec3
import com.hyperxr.rendering.GpuMesh
import com.hyperxr.rendering.StrokeSampler
import com.hyperxr.rendering.TubeGeometry
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme
import java.io.File
import java.util.IdentityHashMap

/**
 * Ateliê 3D: drawing in the space around you. A pinch (away from the menu) draws a stroke where
 * the thumb and index meet, in full 6DoF; the colour, thickness and brush come from the menu or
 * from the palette that floats over the back of the left hand (touch a colour with the right
 * index). A closed hand grabs the whole drawing and moves and turns it with the hand. With a
 * controller, the trigger draws at the tip of where it points.
 */
class AtelierGame(host: GameHost) : QuestGame(host) {
    override val title = "Ateliê 3D"
    override val shadowRadius = 0f
    override val menu = AtelierMenu(this)

    val sketch = com.hyperxr.questgames.Sketch3D()
    private val meshes = IdentityHashMap<SketchStroke, Array<GpuMesh?>>()
    var color = Sketch3D.COLORS[6]
    var size = 1
    var brush = SketchBrush.NEON
    var erasing = false
    var mirror = false

    /** The drawing's place in the game space: strokes are stored relative to it. */
    private val canvas = Pose()
    private val canvasInv = Pose()
    private val grab = GrabDrag()
    private var grabHand = -1

    // three inputs (left hand, right hand, controller), each with a mirrored twin
    private val samplers = Array(6) { StrokeSampler(step = 0.004f, smoothing = 0.35f) }
    private val live = arrayOfNulls<SketchStroke>(6)
    private val liveMesh = arrayOfNulls<Array<GpuMesh?>>(6)
    private val drawing = BooleanArray(3)
    private var frame = 0
    private val p = Vec3(); private val local = Vec3()
    private val hand = Pose(); private val tmpPose = Pose()
    private val rgba = FloatArray(4); private val halo = FloatArray(4)
    private val save = File(host.ctx.activity.filesDir, "atelie/desenho.json")

    override fun start() {
        status = "Faça a pinça para desenhar. Mão fechada move o desenho. Toque uma cor da paleta da mão esquerda com o indicador direito"
    }

    override fun update(dt: Float) {
        frame++
        canvas.inverse(canvasInv)
        // move the drawing with a closed hand
        if (grabHand >= 0) {
            if (!host.hands[grabHand].fist || !host.handPose(grabHand, hand)) { grab.end(); grabHand = -1 }
            else grab.update(hand, canvas)
        } else for (i in 0..1) {
            if (host.hands[i].fist && !host.uiBusy[i] && !drawing[i] && host.handPose(i, hand)) { grab.begin(hand, canvas); grabHand = i; break }
        }
        // the palette over the back of the left hand: the right index picks
        if (paletteVisible() && host.hands[1].usable) {
            for (k in Sketch3D.COLORS.indices) {
                palettePos(k, p)
                if (p.distanceTo(host.hands[1].tip) < 0.018f && color != Sketch3D.COLORS[k]) { color = Sketch3D.COLORS[k]; erasing = false; host.play(host.sTick, p.x, p.y, p.z, 0.4f); menu.dirty = true }
            }
        }
        for (i in 0..2) input(i)
    }

    private fun paletteVisible() = host.hands[0].usable && !drawing[0] && grabHand != 0 && host.handPose(0, tmpPose)

    /** Colour [k] of the palette: two rows over the back of the left hand. */
    private fun palettePos(k: Int, out: Vec3): Vec3 {
        val row = k / 6; val col = k % 6
        local.set(-0.05f + col * 0.02f, 0.045f + row * 0.022f, -0.02f)
        return tmpPose.transformPoint(local, out)
    }

    private fun input(i: Int) {
        val pressed: Boolean
        if (i < 2) {
            val g = host.hands[i]
            pressed = g.usable && g.pinch && (drawing[i] || !host.uiBusy[i]) && grabHand != i && host.pinchPoint(i, p)
        } else {
            val pad = host.pad
            pressed = pad.connected && pad.trigger
            if (pressed) p.set(pad.aim.origin).addScaled(pad.aim.direction, 0.12f)
        }
        if (erasing) {
            if (pressed) {
                canvasInv.transformPoint(p, local)
                val hit = sketch.hit(local.x, local.y, local.z, 0.025f)
                if (hit >= 0) { sketch.strokes[hit].let { s -> meshes.remove(s)?.forEach { it?.release() } }; sketch.erase(hit); host.play(host.sMiss, p.x, p.y, p.z, 0.4f) }
            }
            drawing[i] = false
            return
        }
        if (pressed) {
            canvasInv.transformPoint(p, local)
            if (!drawing[i]) {
                drawing[i] = true
                for (m in 0..1) { samplers[i * 2 + m].reset(); live[i * 2 + m] = null }
            }
            grow(i * 2, local.x, local.y, local.z)
            if (mirror) grow(i * 2 + 1, -local.x, local.y, local.z)
        } else if (drawing[i]) {
            drawing[i] = false
            for (m in 0..1) finish(i * 2 + m)
        }
    }

    private fun radius() = Sketch3D.SIZES[size]

    private fun grow(k: Int, x: Float, y: Float, z: Float) {
        if (!samplers[k].add(x, y, z)) return
        if (frame % 2 != 0 && samplers[k].points.size > 6) return
        val pts = samplers[k].toArray()
        if (pts.size < 6) return
        val s = SketchStroke(pts, color, radius(), brush)
        liveMesh[k]?.forEach { it?.release() }
        liveMesh[k] = build(s)
        live[k] = s
    }

    private fun finish(k: Int) {
        // the last points (skipped by the every-other-frame rebuild) go into the final mesh
        live[k]?.let { prev ->
            val pts = samplers[k].toArray()
            if (pts.size > prev.points.size) { liveMesh[k]?.forEach { it?.release() }; val s2 = prev.copy(points = pts); live[k] = s2; liveMesh[k] = build(s2) }
        }
        val s = live[k]
        val m = liveMesh[k]
        live[k] = null; liveMesh[k] = null
        if (s == null || m == null) { m?.forEach { it?.release() }; return }
        sketch.add(s)
        meshes[s] = m
        menu.dirty = true
    }

    private fun build(s: SketchStroke): Array<GpuMesh?> {
        val sides = if (s.brush == SketchBrush.FITA) 2 else 6
        val body = TubeGeometry.build(s.points, if (s.brush == SketchBrush.FITA) s.radius * 2.5f else s.radius, sides)?.let { GpuMesh(it.vertices, it.indices) }
        val glow = if (s.brush == SketchBrush.NEON) TubeGeometry.build(s.points, s.radius * 2.6f, 6)?.let { GpuMesh(it.vertices, it.indices) } else null
        return arrayOf(body, glow)
    }

    fun undo() { sketch.undo()?.let { s -> meshes.remove(s)?.forEach { it?.release() } }; menu.dirty = true }
    fun redo() { sketch.redo(); menu.dirty = true }
    fun clearAll() { meshes.values.forEach { a -> a.forEach { it?.release() } }; meshes.clear(); sketch.clear(); menu.dirty = true }

    fun saveDrawing() {
        runCatching { save.parentFile?.mkdirs(); save.writeText(sketch.toJson()) }
            .onSuccess { status = "Desenho salvo (${sketch.strokes.size} traços)" }.onFailure { status = "Não deu para salvar: ${it.message}" }
    }

    fun loadDrawing() {
        if (!save.isFile) { status = "Nenhum desenho salvo ainda"; return }
        runCatching { val text = save.readText(); clearAll(); sketch.load(text) }
            .onSuccess { status = "Desenho aberto (${sketch.strokes.size} traços)" }.onFailure { status = "Não deu para abrir: ${it.message}" }
        menu.dirty = true
    }

    fun recenter() { canvas.position.set(0f, 0f, 0f); canvas.orientation.identity() }

    override fun draw() {
        for (s in sketch.strokes) {
            val m = meshes.getOrPut(s) { build(s) }
            drawStroke(s, m)
        }
        for (k in 0 until 6) { val s = live[k] ?: continue; liveMesh[k]?.let { drawStroke(s, it) } }
        // the brush at each pinch, and the palette
        for (i in 0..1) if (host.hands[i].usable && grabHand != i && host.pinchPoint(i, p)) {
            if (erasing) host.drawSphere(p.x, p.y, p.z, 0.025f, ERASER, unlit = true, shadow = false)
            else host.drawSphere(p.x, p.y, p.z, radius() + 0.002f, Sketch3D.rgba(color, rgba), unlit = true, shadow = false)
        }
        if (paletteVisible()) for (k in Sketch3D.COLORS.indices) {
            palettePos(k, p)
            val on = Sketch3D.COLORS[k] == color && !erasing
            host.drawSphere(p.x, p.y, p.z, if (on) 0.0095f else 0.007f, Sketch3D.rgba(Sketch3D.COLORS[k], rgba), unlit = true, shadow = false)
        }
    }

    private fun drawStroke(s: SketchStroke, m: Array<GpuMesh?>) {
        Sketch3D.rgba(s.color, rgba)
        val pos = canvas.position
        m[0]?.let { body ->
            val d = host.drawMesh(body, pos.x, pos.y, pos.z, 1f, 1f, 1f, rgba, orientation = canvas.orientation,
                unlit = s.brush == SketchBrush.NEON, gloss = 0.45f, shadow = false)
            d.doubleSided = s.brush == SketchBrush.FITA
        }
        m[1]?.let { glow ->
            halo[0] = rgba[0] * 0.55f; halo[1] = rgba[1] * 0.55f; halo[2] = rgba[2] * 0.55f; halo[3] = 1f
            val d = host.drawMesh(glow, pos.x, pos.y, pos.z, 1f, 1f, 1f, halo, orientation = canvas.orientation, unlit = true, shadow = false)
            d.additive = true
        }
    }

    override fun release() {
        meshes.values.forEach { a -> a.forEach { it?.release() } }; meshes.clear()
        liveMesh.forEach { a -> a?.forEach { it?.release() } }
    }

    companion object { private val ERASER = floatArrayOf(1f, 0.35f, 0.35f, 0.5f) }
}

/** The Ateliê's menu: colours, thickness, brush, eraser, mirror, undo / redo, save / open. */
class AtelierMenu(private val game: AtelierGame) : XrPanel(0.9f, 1.15f, title = "Ateliê 3D") {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    init { closable = false }

    override fun draw(c: PanelCanvas) {
        c.y = c.pad
        c.label("Cores", c.pad, c.y, 30f, XrTheme.text, bold = true); c.y += 50f
        val cell = (c.w - 2 * c.pad) / 6f
        for ((k, col) in Sketch3D.COLORS.withIndex()) {
            val x = c.pad + (k % 6) * cell; val top = c.y + (k / 6) * cell * 0.8f
            val r = RectF(x + 8f, top + 8f, x + cell - 8f, top + cell * 0.8f - 8f)
            fill.style = Paint.Style.FILL; fill.color = col
            c.canvas.drawRoundRect(r, 24f, 24f, fill)
            if (col == game.color && !game.erasing) {
                fill.style = Paint.Style.STROKE; fill.strokeWidth = 7f; fill.color = XrTheme.accent
                c.canvas.drawRoundRect(r, 24f, 24f, fill)
            } else if (Color.luminance(col) > 0.9f) {
                fill.style = Paint.Style.STROKE; fill.strokeWidth = 2f; fill.color = XrTheme.border
                c.canvas.drawRoundRect(r, 24f, 24f, fill)
            }
            addRegion(Region(r, "c$k", { game.color = col; game.erasing = false; dirty = true }, null))
        }
        c.y += 2 * cell * 0.8f + 24f
        c.label("Espessura", c.pad, c.y, 30f, XrTheme.text, bold = true); c.y += 50f
        val names = arrayOf("Fina", "Média", "Grossa", "Enorme")
        var x = c.pad; val bw = (c.w - 2 * c.pad - 36f) / 4f
        for (k in names.indices) { c.button("s$k", names[k], x, c.y, bw, 72f, accent = game.size == k) { game.size = k; dirty = true }; x += bw + 12f }
        c.y += 96f
        c.label("Pincel", c.pad, c.y, 30f, XrTheme.text, bold = true); c.y += 50f
        x = c.pad; val bw3 = (c.w - 2 * c.pad - 36f) / 4f
        for (b in SketchBrush.entries) { c.button("b${b.name}", b.label, x, c.y, bw3, 72f, accent = !game.erasing && game.brush == b) { game.brush = b; game.erasing = false; dirty = true }; x += bw3 + 12f }
        c.button("erase", "Borracha", x, c.y, bw3, 72f, accent = game.erasing) { game.erasing = !game.erasing; dirty = true }
        c.y += 96f
        val half = (c.w - 2 * c.pad - 12f) / 2f
        c.button("undo", "Desfazer", c.pad, c.y, half, 76f, enabled = game.sketch.strokes.isNotEmpty()) { game.undo() }
        c.button("redo", "Refazer", c.pad + half + 12f, c.y, half, 76f) { game.redo() }
        c.y += 88f
        c.button("mirror", if (game.mirror) "Espelho: ligado" else "Espelho: desligado", c.pad, c.y, half, 76f, accent = game.mirror) { game.mirror = !game.mirror; dirty = true }
        c.button("center", "Trazer de volta", c.pad + half + 12f, c.y, half, 76f) { game.recenter() }
        c.y += 88f
        c.button("save", "Salvar", c.pad, c.y, half, 76f) { game.saveDrawing() }
        c.button("open", "Abrir salvo", c.pad + half + 12f, c.y, half, 76f) { game.loadDrawing() }
        c.y += 88f
        c.button("clear", "Apagar tudo", c.pad, c.y, c.w - 2 * c.pad, 76f, enabled = game.sketch.strokes.isNotEmpty()) { game.clearAll() }
        c.y += 96f
        c.label("${game.sketch.strokes.size} traços", c.pad, c.y, 26f, XrTheme.textDim)
    }
}
