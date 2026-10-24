package com.hyperxr.joycon

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.input.ButtonMapper
import com.hyperxr.input.ControllerButton
import com.hyperxr.input.GamepadProfiles

enum class JoyConSide { LEFT, RIGHT, PRO, COMBINED }

/**
 * Live state of one controller as Android exposes it: buttons, sticks, d-pad, and for remotes in
 * mouse mode the mouse movement turned into a stick. Motion sensors are never used.
 */
class JoyCon(val deviceId: Int, val name: String, val side: JoyConSide, val profile: GamepadProfiles.Profile) {
    @Volatile var buttons = 0
    @Volatile var stickX = 0f
    @Volatile var stickY = 0f
    /** Second stick (two-stick pads): used for scrolling. */
    @Volatile var stick2X = 0f
    @Volatile var stick2Y = 0f
    // the parts the main stick is made of
    internal var analogX = 0f; internal var analogY = 0f
    internal var dpadX = 0; internal var dpadY = 0
    internal var hatX = 0f; internal var hatY = 0f
    internal var mouseX = 0f; internal var mouseY = 0f; internal var mouseNs = 0L
    internal var lastMouseX = Float.NaN; internal var lastMouseY = Float.NaN
    val family get() = profile.family
}

/**
 * Every controller Android can see (GamepadProfiles): Joy-Con and Pro, DualShock 4 and
 * DualSense, Xbox, 8BitDo, any gamepad, and VR Box style remotes in game, keyboard or mouse mode.
 * Buttons come from KeyEvents, sticks, triggers and the d-pad hat from joystick MotionEvents, a
 * remote's mouse movement from mouse MotionEvents. Where the controller points always comes from
 * the hand holding it (the engine's ControllerPoseFusion), never from its own motion sensors.
 */
class JoyConManager(context: Context) : InputManager.InputDeviceListener {
    private val im = context.getSystemService(InputManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    // written on the main thread (devices come and go), read every frame on the render thread
    val controllers = java.util.concurrent.ConcurrentHashMap<Int, JoyCon>()
    val mappers = java.util.concurrent.ConcurrentHashMap<JoyConSide, ButtonMapper>()
    var onAction: ((JoyConSide, ButtonMapper.ActionEvent) -> Unit)? = null
    var onChange: (() -> Unit)? = null
    var settings = com.hyperxr.settings.ControllerSettings()
        set(v) { field = v; mappers.clear() }
    private val mouseStick = FloatArray(2)

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
        val profile = profileOf(dev) ?: return
        if (controllers.containsKey(deviceId)) return
        val jc = JoyCon(deviceId, dev.name, sideOf(profile), profile)
        controllers[deviceId] = jc
        Diagnostics.log("Controller connected: ${dev.name} (${profile.label})")
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

    /** True when [deviceId] is a controller (its Back key belongs to HyperXR, not to Android). */
    fun isController(deviceId: Int) = controllers.containsKey(deviceId)

    private fun mapperFor(side: JoyConSide) = mappers.getOrPut(side) { ButtonMapper.fromSettings(settings) }

    /** Returns true when the event came from a known controller and was consumed. */
    fun onKeyEvent(e: KeyEvent): Boolean {
        val jc = controllers[e.deviceId] ?: return false
        val down = e.action == KeyEvent.ACTION_DOWN
        if (jc.family != GamepadProfiles.Family.NINTENDO) {
            GamepadProfiles.dpad(e.keyCode)?.let { d ->
                // a d-pad held steers like the stick; released, that axis goes back to the middle
                if (d[0] != 0) jc.dpadX = if (down) d[0] else if (jc.dpadX == d[0]) 0 else jc.dpadX
                if (d[1] != 0) jc.dpadY = if (down) d[1] else if (jc.dpadY == d[1]) 0 else jc.dpadY
                compose(jc)
                return true
            }
        }
        val button = GamepadProfiles.mapKey(e.keyCode, jc.profile) ?: return true
        if (e.repeatCount > 0 && down) return true
        val bit = BUTTON_BITS.getValue(button)
        jc.buttons = if (down) jc.buttons or bit else jc.buttons and bit.inv()
        mapperFor(jc.side).onButton(button, down)?.let { onAction?.invoke(jc.side, it) }
        return true
    }

    fun onMotionEvent(e: MotionEvent): Boolean {
        val jc = controllers[e.deviceId] ?: return false
        if (e.source and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE) return onMouse(jc, e)
        if (e.source and InputDevice.SOURCE_JOYSTICK == 0) return false
        val dev = e.device
        val hasTriggers = dev?.getMotionRange(MotionEvent.AXIS_LTRIGGER) != null || dev?.getMotionRange(MotionEvent.AXIS_BRAKE) != null
        val rxTriggers = GamepadProfiles.triggersOnRxRy(jc.profile, hasTriggers)
        // A single right Joy-Con reports its stick on the right-stick axes (Z/RZ or RX/RY) on most
        // drivers, the left one on X/Y: use whichever pair is deflected.
        val a = dz(e.getAxisValue(MotionEvent.AXIS_X)) to dz(-e.getAxisValue(MotionEvent.AXIS_Y))
        val b = dz(e.getAxisValue(MotionEvent.AXIS_Z)) to dz(-e.getAxisValue(MotionEvent.AXIS_RZ))
        val c = if (rxTriggers) 0f to 0f else dz(e.getAxisValue(MotionEvent.AXIS_RX)) to dz(-e.getAxisValue(MotionEvent.AXIS_RY))
        val right = if (mag(b) >= mag(c)) b else c
        if (jc.side == JoyConSide.PRO || jc.side == JoyConSide.COMBINED) {
            // two sticks: the right one is the cursor, the left one scrolls; a pad whose right
            // stick is idle (or missing) steers with the left one
            val useLeft = mag(right) < 1e-4f && mag(a) > 0f
            jc.analogX = if (useLeft) a.first else right.first; jc.analogY = if (useLeft) a.second else right.second
            jc.stick2X = if (useLeft) 0f else a.first; jc.stick2Y = if (useLeft) 0f else a.second
        } else {
            val m = if (mag(a) >= mag(right)) a else right
            jc.analogX = m.first; jc.analogY = m.second
        }
        // triggers: analog axes on most drivers; on old PlayStation drivers RX/RY at rest = -1
        val zr: Float; val zl: Float
        if (rxTriggers) {
            zr = (e.getAxisValue(MotionEvent.AXIS_RY) + 1f) / 2f; zl = (e.getAxisValue(MotionEvent.AXIS_RX) + 1f) / 2f
        } else {
            zr = e.getAxisValue(MotionEvent.AXIS_RTRIGGER).coerceAtLeast(e.getAxisValue(MotionEvent.AXIS_GAS))
            zl = e.getAxisValue(MotionEvent.AXIS_LTRIGGER).coerceAtLeast(e.getAxisValue(MotionEvent.AXIS_BRAKE))
        }
        updateAnalog(jc, ControllerButton.ZR, zr > 0.5f)
        updateAnalog(jc, ControllerButton.ZL, zl > 0.5f)
        val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X); val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        if (jc.family == GamepadProfiles.Family.NINTENDO && jc.side == JoyConSide.LEFT) {
            // left Joy-Con: the hat is its face buttons (right = A, down = B)
            updateAnalog(jc, ControllerButton.A, hx > 0.5f)
            updateAnalog(jc, ControllerButton.B, hy > 0.5f)
        } else { jc.hatX = hx; jc.hatY = hy }
        compose(jc)
        return true
    }

    /** A remote in mouse mode: its joystick moves the mouse; that movement becomes the stick. */
    private fun onMouse(jc: JoyCon, e: MotionEvent): Boolean {
        var dx = e.getAxisValue(MotionEvent.AXIS_RELATIVE_X); var dy = e.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
        if (dx == 0f && dy == 0f) {
            // drivers without relative axes: the change of the absolute pointer position
            if (!jc.lastMouseX.isNaN()) { dx = e.x - jc.lastMouseX; dy = e.y - jc.lastMouseY }
            jc.lastMouseX = e.x; jc.lastMouseY = e.y
        }
        if (dx != 0f || dy != 0f) {
            GamepadProfiles.mouseToStick(dx, dy, mouseStick)
            jc.mouseX = mouseStick[0]; jc.mouseY = mouseStick[1]; jc.mouseNs = System.nanoTime()
        }
        // its buttons, when they come as mouse buttons
        updateAnalog(jc, ControllerButton.A, e.buttonState and MotionEvent.BUTTON_PRIMARY != 0)
        updateAnalog(jc, ControllerButton.B, e.buttonState and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_BACK) != 0)
        compose(jc)
        return true
    }

    /** The main stick: the analog stick when it is deflected, else the d-pad or hat, else the mouse. */
    private fun compose(jc: JoyCon) {
        var x = jc.analogX; var y = jc.analogY
        if (x * x + y * y < 1e-4f) {
            val px = if (jc.dpadX != 0) jc.dpadX.toFloat() else dz(jc.hatX)
            val py = if (jc.dpadY != 0) -jc.dpadY.toFloat() else dz(-jc.hatY)
            if (px != 0f || py != 0f) { x = px; y = py }
            else if (jc.mouseNs != 0L) { x = jc.mouseX; y = -jc.mouseY }
        }
        jc.stickX = x; jc.stickY = y
    }

    /** Once a frame: a mouse that stopped moving lets the stick go back to the middle. */
    fun tick(nowNs: Long) {
        for (jc in controllers.values) {
            if (jc.mouseNs != 0L && nowNs - jc.mouseNs > MOUSE_HOLD_NS) { jc.mouseNs = 0L; jc.mouseX = 0f; jc.mouseY = 0f; compose(jc) }
        }
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
        const val NINTENDO_VENDOR = GamepadProfiles.NINTENDO_VENDOR
        private const val MOUSE_HOLD_NS = 80_000_000L
        val BUTTON_BITS = mapOf(
            ControllerButton.R to 1, ControllerButton.L to 2, ControllerButton.ZR to 4, ControllerButton.ZL to 8,
            ControllerButton.PLUS to 16, ControllerButton.MINUS to 32, ControllerButton.HOME to 64,
            ControllerButton.A to 128, ControllerButton.B to 256, ControllerButton.X to 512, ControllerButton.Y to 1024,
            ControllerButton.SL to 2048, ControllerButton.SR to 4096, ControllerButton.STICK_R to 8192, ControllerButton.STICK_L to 8192,
            ControllerButton.CAPTURE to 16384,
        )

        /** What Android tells about a device, for [GamepadProfiles.classify]. */
        fun profileOf(dev: InputDevice): GamepadProfiles.Profile? {
            val src = dev.sources
            fun has(s: Int) = src and s == s
            val external = if (Build.VERSION.SDK_INT >= 29) dev.isExternal else !dev.name.lowercase().let { "gpio" in it || "pon" in it || "keypad" in it || "touch" in it }
            return GamepadProfiles.classify(GamepadProfiles.Device(
                dev.name, dev.vendorId, dev.productId,
                gamepad = has(InputDevice.SOURCE_GAMEPAD), joystick = has(InputDevice.SOURCE_JOYSTICK),
                keyboard = has(InputDevice.SOURCE_KEYBOARD), alphabeticKeyboard = dev.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC,
                mouse = has(InputDevice.SOURCE_MOUSE), external = external, virtual = dev.isVirtual,
            ))
        }

        private fun sideOf(p: GamepadProfiles.Profile) = when (p.side) {
            GamepadProfiles.Side.LEFT -> JoyConSide.LEFT
            GamepadProfiles.Side.RIGHT -> JoyConSide.RIGHT
            GamepadProfiles.Side.PRO -> JoyConSide.PRO
            GamepadProfiles.Side.COMBINED -> JoyConSide.COMBINED
        }

        fun classify(dev: InputDevice): JoyConSide? = profileOf(dev)?.let { sideOf(it) }

        /** "Name (kind)" of every controller connected now. */
        fun connectedJoyCons(): List<String> =
            InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.mapNotNull { d -> profileOf(d)?.let { "${d.name} (${it.label})" } }.distinct()

        /** Kept for callers that map Nintendo keys directly. */
        fun mapKey(code: Int, side: JoyConSide): ControllerButton? =
            GamepadProfiles.mapKey(code, GamepadProfiles.Profile(GamepadProfiles.Family.NINTENDO, GamepadProfiles.Side.valueOf(side.name)))
    }
}
