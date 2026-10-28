package com.hyperxr.emulators

import android.graphics.Paint
import android.graphics.RectF
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.hyperxr.input.PointerEvent
import com.hyperxr.input.PointerPhase
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme
import com.hyperxr.windows.SpatialWindow
import com.hyperxr.windows.VirtualScreen
import com.hyperxr.xr.GameInput
import kotlin.math.abs

/**
 * A game on a big screen in space. Pointing at it and clicking gives it the controller (Joy-Cons,
 * a Pro Controller, any Bluetooth gamepad or keyboard); HOME gives the controller back to
 * HyperXR. Without a controller, [EmulatorPad] has the buttons for the hands.
 */
class EmulatorWindow(val session: EmulatorSession, private val focus: (EmulatorWindow?) -> Unit) :
    SpatialWindow("${session.core.coreName} — ${session.rom.nameWithoutExtension}", VirtualScreen(960, (960 / session.aspect).toInt()), 1.5f),
    GameInput {

    @Volatile private var keyMask = 0
    @Volatile private var axisMask = 0
    @Volatile var padMask = 0
        set(v) { field = v; push() }
    @Volatile var captured = false
        private set

    init {
        screen.onSurface = { session.setSurface(it) }
        status = "Clique na tela para jogar com o controle"
    }

    private fun push() = session.setButtons(keyMask or axisMask or padMask)

    fun setCaptured(on: Boolean) {
        captured = on
        if (!on) { keyMask = 0; axisMask = 0; push() }
        status = if (on) "Controle no jogo (HOME devolve ao HyperMagic)" else "Clique na tela para jogar com o controle"
    }

    override fun injectTouch(action: Int, x: Float, y: Float, downTimeMs: Long, eventTimeMs: Long) {
        if (action == MotionEvent.ACTION_UP) focus(this)
    }

    override fun onKey(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BUTTON_MODE || e.keyCode == KeyEvent.KEYCODE_HOME) {
            if (e.action == KeyEvent.ACTION_UP) focus(null)
            return true
        }
        // a PC takes a real keyboard as a keyboard (gamepads still go to the joypad, which the
        // core maps to keys for each game)
        if (session.core.pc && !isGamepad(e)) {
            val k = RetroKeys.fromKeyCode(e.keyCode) ?: return false
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) RetroNative.keyEvent(true, k, e.unicodeChar, mods(e))
            else if (e.action == KeyEvent.ACTION_UP) RetroNative.keyEvent(false, k, 0, mods(e))
            return true
        }
        val bit = bitFor(e.keyCode) ?: return false
        if (e.action == KeyEvent.ACTION_DOWN) keyMask = keyMask or (1 shl bit)
        else if (e.action == KeyEvent.ACTION_UP) keyMask = keyMask and (1 shl bit).inv()
        push()
        return true
    }

    override fun onMotion(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK == 0) return false
        // whichever of stick, hat or right stick is deflected most (single Joy-Cons report differently)
        var x = e.getAxisValue(MotionEvent.AXIS_X); var y = e.getAxisValue(MotionEvent.AXIS_Y)
        val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X); val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        if (abs(hx) + abs(hy) > abs(x) + abs(y)) { x = hx; y = hy }
        val zx = e.getAxisValue(MotionEvent.AXIS_Z); val zy = e.getAxisValue(MotionEvent.AXIS_RZ)
        if (abs(zx) + abs(zy) > abs(x) + abs(y)) { x = zx; y = zy }
        var m = RetroPad.directions(x, y)
        if (maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)) > 0.5f) m = m or (1 shl RetroPad.L2)
        if (maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)) > 0.5f) m = m or (1 shl RetroPad.R2)
        axisMask = m
        push()
        return true
    }

    /** The XR keyboard: each character as a key press (Shift held for capitals and symbols). */
    override fun typeText(text: String) {
        if (!session.core.pc) return
        for (c in text) {
            val (k, shift) = RetroKeys.fromChar(c) ?: continue
            val m = if (shift) RetroKeys.MOD_SHIFT else 0
            if (shift) RetroNative.keyEvent(true, RetroKeys.LSHIFT, 0, m)
            RetroNative.keyEvent(true, k, c.code, m)
            RetroNative.keyEvent(false, k, 0, m)
            if (shift) RetroNative.keyEvent(false, RetroKeys.LSHIFT, 0, 0)
        }
    }

    override fun sendKey(keyCode: Int) {
        if (!session.core.pc) return
        val k = RetroKeys.fromKeyCode(keyCode) ?: return
        RetroNative.keyEvent(true, k, 0, 0); RetroNative.keyEvent(false, k, 0, 0)
    }

    /** Wants the XR keyboard button (PC cores). */
    val wantsKeyboard: Boolean get() = session.core.pc

    private var mouseButtons = 0
    private var lastU = Float.NaN; private var lastV = Float.NaN

    /**
     * A PC: pointing at the screen moves the mouse (hover too), pinch/click is the left button
     * (the secondary pointer action the right one); the first click also gives it the controller.
     */
    override fun onPointerEvent(event: com.hyperxr.input.PointerEvent) {
        if (!session.core.pc) return super.onPointerEvent(event)
        val u = event.u.coerceIn(0f, 1f); val v = event.v.coerceIn(0f, 1f)
        val w = session.width.coerceAtLeast(1); val h = session.height.coerceAtLeast(1)
        val dx = if (lastU.isNaN()) 0 else ((u - lastU) * w).toInt()
        val dy = if (lastV.isNaN()) 0 else ((v - lastV) * h).toInt()
        if (dx != 0 || lastU.isNaN()) lastU = u
        if (dy != 0 || lastV.isNaN()) lastV = v
        val bit = if (event.secondary) 2 else 1
        when (event.phase) {
            com.hyperxr.input.PointerPhase.DOWN -> mouseButtons = mouseButtons or bit
            com.hyperxr.input.PointerPhase.UP -> { mouseButtons = mouseButtons and bit.inv(); if (!captured) focus(this) }
            com.hyperxr.input.PointerPhase.CANCEL, com.hyperxr.input.PointerPhase.EXIT -> { mouseButtons = 0; lastU = Float.NaN; lastV = Float.NaN }
            else -> Unit
        }
        val valid = event.phase != com.hyperxr.input.PointerPhase.EXIT
        RetroNative.mouse(valid, ((u * 2 - 1) * 32767).toInt(), ((v * 2 - 1) * 32767).toInt(), mouseButtons, dx, dy)
    }

    private fun isGamepad(e: KeyEvent) = e.source and (android.view.InputDevice.SOURCE_GAMEPAD or android.view.InputDevice.SOURCE_JOYSTICK) != 0 ||
        KeyEvent.isGamepadButton(e.keyCode)

    private fun mods(e: KeyEvent) = (if (e.isShiftPressed) RetroKeys.MOD_SHIFT else 0) or (if (e.isCtrlPressed) RetroKeys.MOD_CTRL else 0) or
        (if (e.isAltPressed) RetroKeys.MOD_ALT else 0)
    override fun openUrl(url: String) {}
    override fun back() = focus(null)
    override val supportsReload: Boolean get() = true
    override fun reload() { session.reset() }

    override fun close() {
        visible = false
        if (captured) focus(null)
        session.setSurface(null)
        session.stop()
    }

    companion object {
        /** Controller and keyboard keys to libretro joypad bits (Nintendo layout: A right, B bottom). */
        fun bitFor(code: Int): Int? = when (code) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_X -> RetroPad.A
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_Z -> RetroPad.B
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_S -> RetroPad.X
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_A -> RetroPad.Y
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_Q -> RetroPad.L
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_W -> RetroPad.R
            KeyEvent.KEYCODE_BUTTON_L2 -> RetroPad.L2
            KeyEvent.KEYCODE_BUTTON_R2 -> RetroPad.R2
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER -> RetroPad.START
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_SHIFT_RIGHT -> RetroPad.SELECT
            KeyEvent.KEYCODE_DPAD_UP -> RetroPad.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> RetroPad.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> RetroPad.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> RetroPad.RIGHT
            else -> null
        }
    }
}

/**
 * Buttons for the hands: press and hold with a pinch (or the cursor); both hands can hold a
 * button each (move with one, jump with the other). Sliding onto another button switches to it.
 */
class EmulatorPad(val window: EmulatorWindow) : XrPanel(1.05f, 0.42f, title = "Controle — ${window.session.core.system}") {
    private val rects = ArrayList<Pair<RectF, Int>>()
    private val held = HashMap<Int, Int>()  // pointer id -> bit
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** Set by the launcher: toggles the cinema screen; [cinemaOn] is its current state. */
    var onCinema: (() -> Unit)? = null
    var cinemaOn = false

    private fun mask() = held.values.fold(0) { m, b -> m or (1 shl b) }

    override fun draw(c: PanelCanvas) {
        rects.clear()
        val top = c.titleHeight + 10f
        val s = (c.h - top - 20f) / 3f  // one cell of the cross
        val pressed = mask()
        fun key(bit: Int, label: String, x: Float, y: Float, w: Float, h: Float, round: Boolean = false) {
            val r = RectF(x, y, x + w, y + h)
            rects += r to bit
            paint.style = Paint.Style.FILL
            paint.color = if (pressed and (1 shl bit) != 0) XrTheme.accent else android.graphics.Color.argb(80, 255, 255, 255)
            if (round) c.canvas.drawOval(r, paint) else c.canvas.drawRoundRect(r, h * 0.25f, h * 0.25f, paint)
            if (label.isEmpty()) {
                // direction: a plain triangle pointing that way
                val k = h * 0.22f; val mx = r.centerX(); val my = r.centerY()
                val path = android.graphics.Path()
                when (bit) {
                    RetroPad.UP -> { path.moveTo(mx, my - k); path.lineTo(mx + k, my + k); path.lineTo(mx - k, my + k) }
                    RetroPad.DOWN -> { path.moveTo(mx, my + k); path.lineTo(mx + k, my - k); path.lineTo(mx - k, my - k) }
                    RetroPad.LEFT -> { path.moveTo(mx - k, my); path.lineTo(mx + k, my - k); path.lineTo(mx + k, my + k) }
                    else -> { path.moveTo(mx + k, my); path.lineTo(mx - k, my - k); path.lineTo(mx - k, my + k) }
                }
                path.close()
                paint.color = XrTheme.text
                c.canvas.drawPath(path, paint)
            } else c.label(label, x + w / 2 - label.length * 9f, y + h / 2 - 18f, 32f, XrTheme.text, true)
        }
        // cross on the left
        val cx = c.pad
        key(RetroPad.UP, "", cx + s, top, s, s)
        key(RetroPad.LEFT, "", cx, top + s, s, s)
        key(RetroPad.RIGHT, "", cx + 2 * s, top + s, s, s)
        key(RetroPad.DOWN, "", cx + s, top + 2 * s, s, s)
        // middle: shoulders, select, start
        val mx = c.w / 2 - 1.6f * s
        key(RetroPad.L, "L", mx, top, 1.5f * s, 0.7f * s)
        key(RetroPad.R, "R", mx + 1.7f * s, top, 1.5f * s, 0.7f * s)
        key(RetroPad.SELECT, "Select", mx, top + 0.95f * s, 1.5f * s, 0.7f * s)
        key(RetroPad.START, "Start", mx + 1.7f * s, top + 0.95f * s, 1.5f * s, 0.7f * s)
        // hold to go back in time / to run faster (the session reads these two bits)
        key(RetroPad.L2, "Voltar", mx, top + 1.9f * s, 1.5f * s, 0.7f * s)
        key(RetroPad.R2, "Rápido", mx + 1.7f * s, top + 1.9f * s, 1.5f * s, 0.7f * s)
        onCinema?.let { cinema -> c.button("cinema", if (cinemaOn) "Sair do cinema" else "Cinema", c.w - c.pad - 300f, 14f, 300f, 64f) { cinema() } }
        // face buttons on the right, Nintendo diamond
        val fx = c.w - c.pad - 3 * s
        key(RetroPad.X, "X", fx + s, top, s, s, round = true)
        key(RetroPad.Y, "Y", fx, top + s, s, s, round = true)
        key(RetroPad.A, "A", fx + 2 * s, top + s, s, s, round = true)
        key(RetroPad.B, "B", fx + s, top + 2 * s, s, s, round = true)
    }

    private fun hit(event: PointerEvent): Int? {
        val px = event.u * pxW; val py = event.v * pxH
        return rects.lastOrNull { it.first.contains(px, py) }?.second
    }

    override fun onPointerEvent(event: PointerEvent) {
        val id = event.pointerId
        when (event.phase) {
            PointerPhase.DOWN -> {
                val b = hit(event) ?: return super.onPointerEvent(event)
                held[id] = b
            }
            PointerPhase.MOVE -> {
                if (!held.containsKey(id)) return super.onPointerEvent(event)
                val b = hit(event) ?: return
                held[id] = b
            }
            PointerPhase.UP, PointerPhase.CANCEL, PointerPhase.EXIT -> {
                if (held.remove(id) == null) return super.onPointerEvent(event)
            }
            else -> return super.onPointerEvent(event)
        }
        window.padMask = mask()
        dirty = true
    }

    override fun release() {
        held.clear(); window.padMask = 0
        super.release()
    }
}
