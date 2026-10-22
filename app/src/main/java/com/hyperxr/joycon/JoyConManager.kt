package com.hyperxr.joycon

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.input.ButtonMapper
import com.hyperxr.input.ControllerButton

enum class JoyConSide { LEFT, RIGHT, PRO, COMBINED }

/** Live state of one Nintendo controller as Android exposes it (buttons + analog stick, no motion sensors). */
class JoyCon(val deviceId: Int, val name: String, val side: JoyConSide) {
    @Volatile var buttons = 0
    @Volatile var stickX = 0f
    @Volatile var stickY = 0f
    /** Second stick (Pro Controller / paired Joy-Cons): used for scrolling. */
    @Volatile var stick2X = 0f
    @Volatile var stick2Y = 0f
}

/**
 * Detects Joy-Cons / Pro Controllers through Android's input stack (Bluetooth HID handled by the
 * OS driver). Buttons come from KeyEvents and the stick from joystick MotionEvents. Motion sensors
 * are deliberately not used: navigation is the stick (cursor), A (click) and B (back/close).
 */
class JoyConManager(context: Context) : InputManager.InputDeviceListener {
    private val im = context.getSystemService(InputManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    val controllers = HashMap<Int, JoyCon>()
    val mappers = HashMap<JoyConSide, ButtonMapper>()
    var onAction: ((JoyConSide, ButtonMapper.ActionEvent) -> Unit)? = null
    var onChange: (() -> Unit)? = null
    var settings = com.hyperxr.settings.ControllerSettings()
        set(v) { field = v; mappers.clear() }

    fun start() {
        im.registerInputDeviceListener(this, main)
        InputDevice.getDeviceIds().forEach { onInputDeviceAdded(it) }
    }

    fun stop() {
        im.unregisterInputDeviceListener(this)
        controllers.keys.toList().forEach { onInputDeviceRemoved(it) }
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        val dev = InputDevice.getDevice(deviceId) ?: return
        val side = classify(dev) ?: return
        if (controllers.containsKey(deviceId)) return
        val jc = JoyCon(deviceId, dev.name, side)
        controllers[deviceId] = jc
        Diagnostics.log("Controller connected: ${dev.name} ($side)")
        onChange?.invoke()
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        val jc = controllers.remove(deviceId) ?: return
        // A button held while Bluetooth dropped must not stay pressed.
        mapperFor(jc.side).releaseAll().forEach { onAction?.invoke(jc.side, it) }
        Diagnostics.log("Controller disconnected: ${jc.name}")
        onChange?.invoke()
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        // Reconnection often arrives as remove+add or as a change with a new descriptor.
        if (InputDevice.getDevice(deviceId) == null) onInputDeviceRemoved(deviceId)
        else if (!controllers.containsKey(deviceId)) onInputDeviceAdded(deviceId)
    }

    private fun mapperFor(side: JoyConSide) = mappers.getOrPut(side) { ButtonMapper.fromSettings(settings) }

    /** Returns true when the event came from a known controller and was consumed. */
    fun onKeyEvent(e: KeyEvent): Boolean {
        val jc = controllers[e.deviceId] ?: return false
        val button = mapKey(e.keyCode, jc.side) ?: return true
        val down = e.action == KeyEvent.ACTION_DOWN
        val bit = BUTTON_BITS.getValue(button)
        jc.buttons = if (down) jc.buttons or bit else jc.buttons and bit.inv()
        mapperFor(jc.side).onButton(button, down)?.let { onAction?.invoke(jc.side, it) }
        return true
    }

    fun onMotionEvent(e: MotionEvent): Boolean {
        val jc = controllers[e.deviceId] ?: return false
        if (e.source and InputDevice.SOURCE_JOYSTICK == 0) return false
        // A single right Joy-Con reports its stick on the right-stick axes (Z/RZ or RX/RY) on most
        // drivers, the left one on X/Y: use whichever pair is deflected.
        val a = dz(e.getAxisValue(MotionEvent.AXIS_X)) to dz(-e.getAxisValue(MotionEvent.AXIS_Y))
        val b = dz(e.getAxisValue(MotionEvent.AXIS_Z)) to dz(-e.getAxisValue(MotionEvent.AXIS_RZ))
        val c = dz(e.getAxisValue(MotionEvent.AXIS_RX)) to dz(-e.getAxisValue(MotionEvent.AXIS_RY))
        val right = if (mag(b) >= mag(c)) b else c
        if (jc.side == JoyConSide.PRO || jc.side == JoyConSide.COMBINED) {
            jc.stickX = right.first; jc.stickY = right.second   // right stick: cursor
            jc.stick2X = a.first; jc.stick2Y = a.second         // left stick: scroll
        } else {
            val main = if (mag(a) >= mag(right)) a else right
            jc.stickX = main.first; jc.stickY = main.second
        }
        // ZL/ZR may arrive as analog triggers on some drivers
        val zr = e.getAxisValue(MotionEvent.AXIS_RTRIGGER).coerceAtLeast(e.getAxisValue(MotionEvent.AXIS_GAS))
        val zl = e.getAxisValue(MotionEvent.AXIS_LTRIGGER).coerceAtLeast(e.getAxisValue(MotionEvent.AXIS_BRAKE))
        updateAnalog(jc, ControllerButton.ZR, zr > 0.5f)
        updateAnalog(jc, ControllerButton.ZL, zl > 0.5f)
        // D-pad reported as a hat: right = A position, down = B position (Nintendo layout)
        val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X); val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        if (jc.side == JoyConSide.LEFT) {
            updateAnalog(jc, ControllerButton.A, hx > 0.5f)
            updateAnalog(jc, ControllerButton.B, hy > 0.5f)
        }
        return true
    }

    private fun dz(v: Float) = if (kotlin.math.abs(v) < 0.1f) 0f else v
    private fun mag(p: Pair<Float, Float>) = p.first * p.first + p.second * p.second

    private fun updateAnalog(jc: JoyCon, b: ControllerButton, down: Boolean) {
        val bit = BUTTON_BITS.getValue(b)
        val was = jc.buttons and bit != 0
        if (was == down) return
        jc.buttons = if (down) jc.buttons or bit else jc.buttons and bit.inv()
        mapperFor(jc.side).onButton(b, down)?.let { onAction?.invoke(jc.side, it) }
    }

    /** Right-hand controller first (pointer), else any. */
    fun primary(): JoyCon? = controllers.values.firstOrNull { it.side == JoyConSide.RIGHT }
        ?: controllers.values.firstOrNull { it.side == JoyConSide.PRO || it.side == JoyConSide.COMBINED } ?: controllers.values.firstOrNull()

    fun bySide(side: JoyConSide): JoyCon? = controllers.values.firstOrNull { it.side == side }

    companion object {
        const val NINTENDO_VENDOR = 0x057E
        val BUTTON_BITS = mapOf(
            ControllerButton.R to 1, ControllerButton.L to 2, ControllerButton.ZR to 4, ControllerButton.ZL to 8,
            ControllerButton.PLUS to 16, ControllerButton.MINUS to 32, ControllerButton.HOME to 64,
            ControllerButton.A to 128, ControllerButton.B to 256, ControllerButton.X to 512, ControllerButton.Y to 1024,
            ControllerButton.SL to 2048, ControllerButton.SR to 4096, ControllerButton.STICK_R to 8192, ControllerButton.STICK_L to 8192,
            ControllerButton.CAPTURE to 16384,
        )

        fun classify(dev: InputDevice): JoyConSide? {
            val gamepad = dev.sources and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK) != 0
            val name = dev.name.lowercase()
            val nintendo = dev.vendorId == NINTENDO_VENDOR || "joy-con" in name || "joycon" in name || "pro controller" in name
            if (!gamepad || !nintendo) return null
            return when {
                dev.productId == 0x2006 || "(l)" in name || "left" in name -> JoyConSide.LEFT
                dev.productId == 0x2007 || "(r)" in name || "right" in name -> JoyConSide.RIGHT
                dev.productId == 0x2009 || "pro" in name -> JoyConSide.PRO
                "combined" in name -> JoyConSide.COMBINED
                else -> JoyConSide.RIGHT
            }
        }

        fun connectedJoyCons(): List<String> =
            InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.filter { classify(it) != null }.map { it.name }

        /** Android keycodes produced by the hid-nintendo / vendor drivers for single and paired Joy-Cons. */
        fun mapKey(code: Int, side: JoyConSide): ControllerButton? = when (code) {
            KeyEvent.KEYCODE_BUTTON_R1 -> ControllerButton.R
            KeyEvent.KEYCODE_BUTTON_L1 -> ControllerButton.L
            KeyEvent.KEYCODE_BUTTON_R2 -> ControllerButton.ZR
            KeyEvent.KEYCODE_BUTTON_L2 -> ControllerButton.ZL
            KeyEvent.KEYCODE_BUTTON_START -> ControllerButton.PLUS
            KeyEvent.KEYCODE_BUTTON_SELECT -> ControllerButton.MINUS
            KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_HOME -> ControllerButton.HOME
            KeyEvent.KEYCODE_BUTTON_A -> ControllerButton.A
            KeyEvent.KEYCODE_BUTTON_B -> ControllerButton.B
            KeyEvent.KEYCODE_BUTTON_X -> ControllerButton.X
            KeyEvent.KEYCODE_BUTTON_Y -> ControllerButton.Y
            KeyEvent.KEYCODE_BUTTON_THUMBR -> ControllerButton.STICK_R
            KeyEvent.KEYCODE_BUTTON_THUMBL -> ControllerButton.STICK_L
            KeyEvent.KEYCODE_BUTTON_C, KeyEvent.KEYCODE_BUTTON_Z -> if (side == JoyConSide.LEFT) ControllerButton.SL else ControllerButton.SR
            // left Joy-Con arrows, by position in the Nintendo layout (right = A, down = B, up = X, left = Y)
            KeyEvent.KEYCODE_DPAD_RIGHT -> ControllerButton.A
            KeyEvent.KEYCODE_DPAD_DOWN -> ControllerButton.B
            KeyEvent.KEYCODE_DPAD_UP -> ControllerButton.X
            KeyEvent.KEYCODE_DPAD_LEFT -> ControllerButton.Y
            else -> null
        }
    }
}
