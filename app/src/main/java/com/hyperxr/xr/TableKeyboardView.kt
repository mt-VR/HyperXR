package com.hyperxr.xr

import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.hyperxr.handtracking.DepthSource
import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.HandState
import com.hyperxr.input.TableDetector
import com.hyperxr.input.TableKeyLayout
import com.hyperxr.input.TablePlacement
import com.hyperxr.input.TableTyping
import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import com.hyperxr.mixedreality.ArCoreSession
import com.hyperxr.rendering.FrameScene
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme
import com.hyperxr.windows.KeyTarget

/**
 * Keyboard lying on a real table, typed on with the fingertips.
 *
 * Finding the table: with the ARCore Depth API the depth points in front of the user are searched
 * for a level surface below the eyes (TableDetector) and the keyboard is laid on it in front of the
 * user; it keeps following the table's height afterwards. Without depth (or when nothing is
 * found), touching the table with the index finger and holding still for a moment calibrates it
 * by hand: the keyboard is centred where the finger touched. The height can also be nudged.
 *
 * Typing: TableTyping turns fingertips coming down onto the table into key presses (one per
 * touch, no duplicates); keys glow when a fingertip is over them and flash when typed. Text goes
 * to the same [KeyTarget] as the floating keyboard (web fields, Android apps, the address bar).
 */
class TableKeyboardView(
    private val target: KeyTarget,
    private val click: () -> Unit,
    private val onClose: () -> Unit,
    private val onFloating: () -> Unit,
) {
    enum class State { SEARCHING, PLACED }

    val layout = TableKeyLayout()
    val placement = TablePlacement()
    private val typing = TableTyping(layout)
    var state = State.SEARCHING; private set
    private var shift = false
    private var typed = StringBuilder()
    private var status = ""
    private var startNs = 0L

    // depth search
    private val points = FloatArray(3 * 2400)
    private val nearPoints = FloatArray(3 * 2400)
    private var lastDetectNs = 0L
    private var candidate = Float.NaN
    var usingDepth = false; private set

    // manual calibration: a fingertip held still on the table
    private val stillTips = Array(2) { ArrayDeque<Pair<Long, Vec3>>() }

    private val flashes = HashMap<String, Long>()
    private val surface = KeySurface()
    val controls = Controls()

    // ------------------------------------------------------------------ frame
    fun update(hands: Array<HandState>, head: Pose, ar: ArCoreSession?, now: Long) {
        if (startNs == 0L) startNs = now
        usingDepth = ar != null && ar.depthEnabled
        when (state) {
            State.SEARCHING -> {
                status = if (usingDepth) "Olhe para a mesa para encontrá-la, ou toque nela com o indicador e segure parado"
                    else "Toque a mesa com a ponta do indicador e segure parado por 1 segundo"
                if (usingDepth && now - lastDetectNs > 250_000_000L) {
                    lastDetectNs = now
                    detectTable(ar!!, head)?.let { h ->
                        // two readings in a row that agree: a real surface, not a passing object
                        if (!candidate.isNaN() && kotlin.math.abs(candidate - h) < 0.02f) {
                            placement.placeInFront(head, (candidate + h) / 2)
                            placed("Mesa encontrada pela Depth API")
                        } else candidate = h
                    }
                    if (now - startNs > 6_000_000_000L) status = "Nenhuma mesa encontrada pela câmera: toque a mesa com o indicador e segure parado"
                }
                calibrateByFinger(hands, head, now)
            }
            State.PLACED -> {
                // the keyboard follows the table if its height reading changes (small moves only)
                if (usingDepth && now - lastDetectNs > 500_000_000L) {
                    lastDetectNs = now
                    detectTable(ar!!, head)?.let { h ->
                        val dy = h - placement.center.y
                        if (kotlin.math.abs(dy) < 0.04f) placement.center.y += dy * 0.3f
                    }
                }
                val joints = arrayOf(hands[0].takeIf { it.isUsable }?.joints, hands[1].takeIf { it.isUsable }?.joints)
                val uncertain = booleanArrayOf(hands[0].depthSource == DepthSource.HAND_SIZE_PRIOR, hands[1].depthSource == DepthSource.HAND_SIZE_PRIOR)
                for (key in typing.update(joints, uncertain, placement, now)) press(key, now)
                if (surface.signature() != surface.drawn) surface.dirty = true
            }
        }
        flashes.entries.removeAll { now - it.value > FLASH_NS }
        controls.place(head)
        controls.dirty = controls.dirty || controls.shownStatus != status
    }

    /** The table height under the user from the Depth API (points ahead, within reach), or null. */
    private fun detectTable(ar: ArCoreSession, head: Pose): Float? {
        val n = ar.depthPoints(points)
        if (n == 0) return null
        val f = head.orientation.rotate(Vec3(0f, 0f, -1f))
        val fl = kotlin.math.sqrt(f.x * f.x + f.z * f.z).coerceAtLeast(1e-3f)
        val fx = f.x / fl; val fz = f.z / fl
        var m = 0
        for (i in 0 until n) {
            val dx = points[i * 3] - head.position.x; val dz = points[i * 3 + 2] - head.position.z
            val ahead = dx * fx + dz * fz
            if (ahead < 0.1f || ahead > 0.9f || kotlin.math.abs(dx * fz - dz * fx) > 0.6f) continue
            nearPoints[m * 3] = points[i * 3]; nearPoints[m * 3 + 1] = points[i * 3 + 1]; nearPoints[m * 3 + 2] = points[i * 3 + 2]; m++
        }
        return TableDetector.detect(nearPoints, m, head.position.y, minBelowHead = 0.2f, maxBelowHead = 1.05f, minSupport = 30)?.height
    }

    private fun calibrateByFinger(hands: Array<HandState>, head: Pose, now: Long) {
        for (i in 0..1) {
            val q = stillTips[i]
            val h = hands[i]
            if (!h.isUsable) { q.clear(); continue }
            val tip = h.joints[HandJoint.INDEX_TIP]
            q.addLast(now to Vec3().set(tip))
            while (q.isNotEmpty() && now - q.first().first > HOLD_NS) q.removeFirst()
            if (q.size < 8 || now - q.first().first < HOLD_NS * 9 / 10) continue
            if (head.position.y - tip.y < 0.25f) continue  // not low enough to be on a table
            val c = Vec3(); q.forEach { c.add(it.second) }; c.scale(1f / q.size)
            if (q.any { it.second.distanceTo(c) > 0.012f }) continue
            // the fingertip pad touches the table; the joint is at the centre of the fingertip
            placement.center.set(c.x, c.y - typing.tipRadius, c.z)
            val fwd = head.orientation.rotate(Vec3(0f, 0f, -1f))
            placement.yaw = kotlin.math.atan2(-fwd.x, -fwd.z)
            placement.valid = true
            stillTips.forEach { it.clear() }
            placed("Mesa calibrada pelo dedo")
            return
        }
    }

    private fun placed(how: String) {
        state = State.PLACED; typing.reset(); status = "$how. Digite tocando as teclas com as pontas dos dedos"
        surface.dirty = true; controls.dirty = true
        click()
    }

    /** Searches again (depth) or waits for a finger calibration. */
    fun recalibrate() {
        state = State.SEARCHING; placement.valid = false; candidate = Float.NaN; startNs = 0L; typing.reset()
        stillTips.forEach { it.clear() }; controls.dirty = true
    }

    fun nudge(dy: Float) { if (placement.valid) { placement.center.y += dy; controls.dirty = true } }

    /** Moves the keyboard to where the user faces now, on the same table height. */
    fun bringInFront(head: Pose) { if (placement.valid) { placement.placeInFront(head, placement.center.y); typing.reset() } }

    private fun press(key: TableKeyLayout.Key, now: Long) {
        flashes[key.id] = now
        click()
        when (key.kind) {
            TableKeyLayout.Kind.CHAR -> {
                val t = if (shift) key.text.uppercase() else key.text
                target.typeText(t); remember(t)
                if (shift) shift = false
            }
            TableKeyLayout.Kind.SPACE -> { target.typeText(" "); remember(" ") }
            TableKeyLayout.Kind.BACKSPACE -> { target.sendKey(android.view.KeyEvent.KEYCODE_DEL); if (typed.isNotEmpty()) typed.setLength(typed.length - 1) }
            TableKeyLayout.Kind.ENTER -> { target.sendKey(android.view.KeyEvent.KEYCODE_ENTER); typed.setLength(0) }
            TableKeyLayout.Kind.SHIFT -> shift = !shift
            TableKeyLayout.Kind.SYMBOLS -> layout.symbols = !layout.symbols
            TableKeyLayout.Kind.CLOSE -> onClose()
        }
        surface.dirty = true; controls.dirty = true
    }

    private fun remember(t: String) {
        typed.append(t)
        if (typed.length > 60) typed.delete(0, typed.length - 60)
    }

    fun submit(scene: FrameScene) {
        controls.submit(scene)
        if (state != State.PLACED) return
        placement.panelPose(surface.pose)
        surface.submit(scene)
    }

    fun release() { surface.release(); controls.release() }

    // ------------------------------------------------------------------ keys texture
    private inner class KeySurface : XrPanel(layout.width + 0.016f, layout.depth + 0.016f, title = "", ppm = 2200f) {
        init { closable = false }
        var drawn = ""
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = XrTheme.medium }

        fun signature(): String = buildString {
            append(layout.symbols); append(shift)
            typing.hovered.sorted().forEach { append(it) }; append('|')
            typing.held.sorted().forEach { append(it) }; append('|')
            flashes.keys.sorted().forEach { append(it) }
        }

        override fun draw(c: PanelCanvas) {
            drawn = signature()
            val ppm = pixelsPerMeter
            val ox = c.w / 2; val oz = c.h / 2
            for (k in layout.keys) {
                val r = RectF((k.x0 * ppm + ox), (k.z0 * ppm + oz), (k.x1 * ppm + ox), (k.z1 * ppm + oz))
                paint.color = when {
                    flashes.containsKey(k.id) || typing.held.contains(k.id) -> PRESSED
                    typing.hovered.contains(k.id) -> HOVER
                    k.kind == TableKeyLayout.Kind.SHIFT && shift -> XrTheme.accent
                    k.kind == TableKeyLayout.Kind.CHAR -> XrTheme.button
                    else -> XrTheme.buttonPressed
                }
                val rr = r.height() * 0.22f
                c.canvas.drawRoundRect(r, rr, rr, paint)
                val label = if (k.kind == TableKeyLayout.Kind.CHAR && shift) k.label.uppercase() else k.label
                text.color = XrTheme.text
                text.textSize = if (label.length > 1) r.height() * 0.32f else r.height() * 0.5f
                c.canvas.drawText(label, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text)
            }
        }
    }

    // ------------------------------------------------------------------ controls
    inner class Controls : XrPanel(0.46f, 0.26f, title = "Teclado na mesa") {
        var shownStatus = ""
        init { priority = 2 }

        fun place(head: Pose) {
            if (state == State.PLACED) {
                // standing just behind the keyboard's far edge, facing the user
                placement.toWorld(0f, 0.15f, -layout.depth / 2 - 0.04f, pose.position)
                Quat.fromAxisAngle(0f, 1f, 0f, placement.yaw, pose.orientation)
                pose.orientation.mul(Quat.fromAxisAngle(1f, 0f, 0f, -0.35f), pose.orientation)
            } else placeInFront(head, 0.65f, -0.18f)
        }

        override fun draw(c: PanelCanvas) {
            shownStatus = status
            c.paragraph(status, 24f, XrTheme.textDim)
            if (state == State.PLACED) {
                val preview = typed.toString().takeLast(34)
                c.paragraph(if (preview.isEmpty()) "(o texto digitado aparece aqui)" else preview, 28f, XrTheme.text)
            }
            val bw = (c.w - 2 * c.pad - 3 * 10f) / 4
            val y = c.h - 96f
            var x = c.pad
            fun b(id: String, label: String, enabled: Boolean = true, act: () -> Unit) { c.button(id, label, x, y, bw, 80f, enabled = enabled) { act() }; x += bw + 10f }
            b("recal", "Recalibrar") { recalibrate() }
            b("up", "Mais alto", state == State.PLACED) { nudge(0.005f) }
            b("down", "Mais baixo", state == State.PLACED) { nudge(-0.005f) }
            b("float", "Flutuante") { onFloating() }
        }
    }

    companion object {
        private const val HOLD_NS = 1_000_000_000L
        private const val FLASH_NS = 160_000_000L
        private val HOVER = Color.rgb(122, 92, 190)
        private val PRESSED = Color.rgb(196, 160, 255)
    }
}
