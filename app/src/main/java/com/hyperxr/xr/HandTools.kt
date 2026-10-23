package com.hyperxr.xr

import com.hyperxr.handtracking.HandMeasure
import com.hyperxr.handtracking.HandState
import com.hyperxr.handtracking.Handedness
import com.hyperxr.handtracking.PalmMenuDetector
import com.hyperxr.input.InteractionSurface
import com.hyperxr.math.Mat4
import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme

/**
 * Tools that live on the hands:
 *  - palm menu: turn a palm toward the face and a small menu appears above that hand (voice,
 *    recentre, capture, library, windows, measuring tape); the other hand clicks it;
 *  - measuring tape: with it on, pinch with both hands and the distance between the pinches is
 *    shown on a line between them; releasing keeps the last measure for a few seconds.
 */
class HandTools(private val ctx: XrContext, private val actions: Actions) {
    interface Actions {
        fun voice()
        fun recenter()
        fun capture()
        fun library()
        fun windows()
    }

    private val detectors = arrayOf(PalmMenuDetector(), PalmMenuDetector())
    private var menu: PalmMenu? = null
    private var menuHand = -1
    var measuring = false; private set
    private var label: MeasureLabel? = null
    private var holdSeconds = 0f
    private val a = Vec3(); private val b = Vec3(); private val dir = Vec3(); private val mid = Vec3()
    private val up = Vec3(0f, 1f, 0f); private val q = Quat()

    /** Surfaces to add to the frame's pointer targets (GL thread, before pointers update). */
    fun surfaces(): List<InteractionSurface> = listOfNotNull(menu?.takeIf { it.visible })

    fun update(hands: Array<HandState>, head: Pose, dt: Float) {
        // palm menu: keep the hand that opened it while it stays up
        var open = -1
        for (i in hands.indices) {
            val h = hands[i]
            val on = h.isUsable && detectors[i].update(h.joints, h.handedness == Handedness.RIGHT, head.position, h.pinchStrength, dt)
            if (!h.isUsable) detectors[i].reset()
            if (on && (open < 0 || i == menuHand)) open = i
        }
        if (open < 0) closeMenu()
        else {
            val m = menu ?: PalmMenu().also { menu = it }
            menuHand = open
            place(m, hands[open], head)
        }
        updateMeasure(hands, head, dt)
    }

    private fun place(m: PalmMenu, h: HandState, head: Pose) {
        // just above the fingertips, a little toward the eyes, facing them
        val tip = h.joints[com.hyperxr.handtracking.HandJoint.MIDDLE_TIP]
        val n = PalmMenuDetector.palmNormal(h.joints, h.handedness == Handedness.RIGHT, Vec3())
        m.pose.position.set(tip.x + n.x * 0.03f, tip.y + 0.07f + n.y * 0.03f, tip.z + n.z * 0.03f)
        // smooth: the menu follows the hand without its tremor
        m.target.set(m.pose.position)
        if (m.placed) m.pose.position.set(m.last).lerp(m.target, 0.35f) else m.placed = true
        m.last.set(m.pose.position)
        m.faceUser(head.position)
    }

    private fun closeMenu() {
        menu?.release(); menu = null; menuHand = -1
    }

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
        menu?.let { if (it.visible) it.submit(ctx.scene) }
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

    fun release() { closeMenu(); label?.release(); label = null }

    private inner class PalmMenu : XrPanel(0.30f, 0.22f, ppm = 1100f) {
        val target = Vec3(); val last = Vec3(); var placed = false
        init { closable = false; priority = 3 }

        override fun draw(c: PanelCanvas) {
            val items = listOf<Pair<String, () -> Unit>>(
                "Voz" to { actions.voice() },
                "Recentralizar" to { actions.recenter() },
                "Captura" to { actions.capture() },
                "Biblioteca" to { actions.library() },
                "Janelas" to { actions.windows() },
                (if (measuring) "Parar fita" else "Fita métrica") to { toggleMeasuring(); dirty = true },
            )
            val cols = 2
            val gap = 12f
            val bw = (c.w - 2 * 20f - gap) / cols
            val bh = (c.h - 2 * 20f - 2 * gap) / 3
            for ((k, it) in items.withIndex()) {
                val x = 20f + (k % cols) * (bw + gap)
                val y = 20f + (k / cols) * (bh + gap)
                c.button("palm:$k", it.first, x, y, bw, bh, accent = it.first == "Parar fita") { it.second() }
            }
        }
    }

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
