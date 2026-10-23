package com.hyperxr.xr

import android.graphics.Paint
import android.graphics.RectF
import com.hyperxr.handtracking.Gesture
import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.HandState
import com.hyperxr.math.Mat4
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import com.hyperxr.rendering.FrameScene
import com.hyperxr.rendering.GpuMesh
import com.hyperxr.rendering.StrokeSampler
import com.hyperxr.rendering.TubeGeometry
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme

/**
 * Drawing in the air: while it is on, pinching with either hand (away from any panel) draws a
 * line of light where the fingertips meet; letting go ends the stroke. Strokes stay where they
 * were drawn, in the room. Each finished stroke is one mesh; the stroke being drawn is rebuilt as
 * it grows.
 */
class AirDraw {
    class Stroke(val color: FloatArray, val radius: Float, var mesh: GpuMesh?)

    var active = false
    var color = COLORS[1]
    var radius = 0.004f
    val strokes = ArrayList<Stroke>()
    private val samplers = arrayOf(StrokeSampler(), StrokeSampler())
    private val drawing = BooleanArray(2)
    private val live = arrayOfNulls<Stroke>(2)
    private var frame = 0L
    private val tip = Vec3()
    private val m = FloatArray(16)

    /** GL thread, once per frame: [busy] = that hand is pointing at a panel (no drawing). */
    fun update(hands: Array<HandState>, busy: BooleanArray) {
        frame++
        for (i in 0..1) {
            val h = hands[i]
            val pinch = active && h.isUsable && h.gesture == Gesture.PINCH
            if (pinch && !drawing[i] && busy[i]) continue          // a pinch on the UI is a click
            if (pinch) {
                tip.set(h.joints[HandJoint.THUMB_TIP]).add(h.joints[HandJoint.INDEX_TIP]).scale(0.5f)
                if (!drawing[i]) { drawing[i] = true; samplers[i].reset(); live[i] = Stroke(color.copyOf(), radius, null).also { strokes += it } }
                if (samplers[i].add(tip.x, tip.y, tip.z) && frame % 2 == 0L) rebuild(i)
            } else if (drawing[i]) {
                drawing[i] = false
                rebuild(i)
                live[i]?.let { if (it.mesh == null) strokes.remove(it) }   // a single tap draws nothing
                live[i] = null
            }
        }
    }

    private fun rebuild(i: Int) {
        val s = live[i] ?: return
        val g = TubeGeometry.build(samplers[i].toArray(), s.radius) ?: return
        s.mesh?.release()
        s.mesh = GpuMesh(g.vertices, g.indices)
    }

    fun submit(scene: FrameScene) {
        Mat4.compose(Vec3(), Quat(), 1f, 1f, 1f, m)
        for (s in strokes) {
            val mesh = s.mesh ?: continue
            val d = scene.addDraw()
            d.mesh = mesh
            d.unlit = true
            d.castShadow = false
            System.arraycopy(s.color, 0, d.color, 0, 4)
            System.arraycopy(m, 0, d.matrix, 0, 16)
        }
    }

    fun undo() { strokes.removeLastOrNull()?.mesh?.release() }
    fun clear() { strokes.forEach { it.mesh?.release() }; strokes.clear() }
    fun release() = clear()

    companion object {
        val COLORS = listOf(
            floatArrayOf(1f, 1f, 1f, 1f), floatArrayOf(0.35f, 0.85f, 1f, 1f), floatArrayOf(1f, 0.35f, 0.85f, 1f),
            floatArrayOf(1f, 0.85f, 0.25f, 1f), floatArrayOf(0.4f, 1f, 0.5f, 1f), floatArrayOf(1f, 0.5f, 0.2f, 1f),
        )
        val SIZES = listOf("Fino" to 0.0022f, "Médio" to 0.004f, "Grosso" to 0.008f)
    }
}

/** The drawing palette: colours, thickness, undo, clear, done. */
class AirDrawPanel(private val draw: AirDraw, private val onDone: () -> Unit) : XrPanel(0.9f, 0.42f, title = "Desenho no ar") {
    private val swatch = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(c: PanelCanvas) {
        c.paragraph("Faça a pinça longe dos painéis e desenhe. Solte para terminar o traço.", 24f)
        var x = c.pad
        val top = c.y + 6f
        for ((k, col) in AirDraw.COLORS.withIndex()) {
            val sel = draw.color === col
            c.button("c$k", "", x, top, 96f, 96f, accent = sel) { draw.color = col; dirty = true }
            swatch.color = android.graphics.Color.rgb((col[0] * 255).toInt(), (col[1] * 255).toInt(), (col[2] * 255).toInt())
            c.canvas.drawRoundRect(RectF(x + 18f, top + 18f, x + 78f, top + 78f), 30f, 30f, swatch)
            x += 108f
        }
        c.y = top + 112f
        x = c.pad
        for ((label, r) in AirDraw.SIZES) {
            c.button("s$label", label, x, c.y, 180f, 72f, accent = draw.radius == r) { draw.radius = r; dirty = true }
            x += 192f
        }
        c.y += 88f
        c.button("undo", "Desfazer", c.pad, c.y, 220f, 72f) { draw.undo() }
        c.button("clear", "Apagar tudo", c.pad + 232f, c.y, 240f, 72f) { draw.clear() }
        c.button("done", "Concluir", c.pad + 484f, c.y, 220f, 72f, accent = true) { draw.active = false; onDone() }
        c.label("${draw.strokes.size} traços", c.pad + 724f, c.y + 20f, 26f, XrTheme.textDim)
    }

    override fun update(dt: Float) { dirty = true }

    /** Closing the palette (its X too) ends drawing; the strokes stay in the room. */
    override fun release() { draw.active = false; super.release() }
}
