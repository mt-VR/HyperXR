package com.hyperxr.xr

import com.hyperxr.handtracking.HandMeasure
import com.hyperxr.handtracking.HandState
import com.hyperxr.input.InteractionSurface
import com.hyperxr.math.Mat4
import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme

/**
 * The measuring tape (turned on from the radial menu: palm-up pinch, then "Fita métrica"): pinch
 * with both hands and the distance between the pinches is shown on a line between them;
 * releasing keeps the last measure for a few seconds.
 */
class HandTools(private val ctx: XrContext, private val actions: Actions) {
    interface Actions {
        fun voice()
        fun recenter()
        fun capture()
        fun library()
        fun windows()
    }

    var measuring = false; private set
    private var label: MeasureLabel? = null
    private var holdSeconds = 0f
    private val a = Vec3(); private val b = Vec3(); private val dir = Vec3(); private val mid = Vec3()
    private val up = Vec3(0f, 1f, 0f); private val q = Quat()

    /** Surfaces to add to the frame's pointer targets (none: the tape is not clickable). */
    fun surfaces(): List<InteractionSurface> = emptyList()

    fun update(hands: Array<HandState>, head: Pose, dt: Float) = updateMeasure(hands, head, dt)

    private fun updateMeasure(hands: Array<HandState>, head: Pose, dt: Float) {
        if (!measuring) return
        val l = hands[0]; val r = hands[1]
        val both = l.isUsable && r.isUsable && l.pinchStrength > 0.7f && r.pinchStrength > 0.7f
        val lbl = label ?: MeasureLabel().also { label = it }
        if (both) {
            HandMeasure.pinchPoint(l.joints, a); HandMeasure.pinchPoint(r.joints, b)
            lbl.text = HandMeasure.format(a.distanceTo(b))
            lbl.lineA.set(a); lbl.lineB.set(b)
            holdSeconds = 4f
        } else holdSeconds -= dt
        lbl.visible = holdSeconds > 0f
        if (lbl.visible) {
            mid.set(lbl.lineA).add(lbl.lineB).scale(0.5f)
            lbl.pose.position.set(mid.x, mid.y + 0.06f, mid.z)
            lbl.faceUser(head.position)
        }
    }

    fun toggleMeasuring() {
        measuring = !measuring
        if (!measuring) { label?.release(); label = null }
    }

    /** Draws the menu, the measure line and its label (GL thread). */
    fun submit() {
        val lbl = label ?: return
        if (!lbl.visible) return
        lbl.submit(ctx.scene)
        dir.set(lbl.lineB).sub(lbl.lineA)
        val len = dir.length()
        if (len < 1e-3f) return
        dir.scale(1f / len)
        mid.set(lbl.lineA).add(lbl.lineB).scale(0.5f)
        Quat.fromTo(up, dir, q)
        val d = ctx.scene.addDraw()
        d.mesh = ctx.renderer.cylinder
        d.late = true
        d.color[0] = 1f; d.color[1] = 0.85f; d.color[2] = 0.2f; d.color[3] = 1f
        d.castShadow = false
        Mat4.compose(mid, q, 0.003f, len, 0.003f, d.matrix)
    }

    fun release() { label?.release(); label = null }

    private class MeasureLabel : XrPanel(0.16f, 0.06f, ppm = 1400f) {
        val lineA = Vec3(); val lineB = Vec3()
        var text = ""
            set(v) { if (v != field) { field = v; dirty = true } }
        init { closable = false }

        override fun draw(c: PanelCanvas) {
            c.label(text, 24f, c.h / 2 - 26f, 44f, XrTheme.text, bold = true)
        }
    }
}
