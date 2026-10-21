package com.hyperxr.joycon

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
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
import com.hyperxr.input.ImuOrientationFilter
import com.hyperxr.math.Quat

enum class JoyConSide { LEFT, RIGHT, PRO, COMBINED }

/** Live state of one Nintendo controller as Android exposes it. */
class JoyCon(val deviceId: Int, val name: String, val side: JoyConSide) {
    @Volatile var buttons = 0
    @Volatile var stickX = 0f
    @Volatile var stickY = 0f
    val imu = ImuOrientationFilter()
    @Volatile var hasImu = false
    @Volatile var lastImuNs = 0L
    val orientation = Quat()
    var listener: SensorEventListener? = null
    private val gyro = FloatArray(3)
    private val lock = Any()

    fun onSensor(e: SensorEvent) {
        synchronized(lock) {
            when (e.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    gyro[0] = e.values[0]; gyro[1] = e.values[1]; gyro[2] = e.values[2]
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    val dt = if (lastImuNs == 0L) 0.005f else ((e.timestamp - lastImuNs) / 1e9f).coerceIn(0.0005f, 0.05f)
                    lastImuNs = e.timestamp
                    imu.update(gyro[0], gyro[1], gyro[2], e.values[0], e.values[1], e.values[2], dt)
                    orientation.set(imu.orientation)
                    hasImu = true
                }
            }
        }
    }

    fun orientationSnapshot(out: Quat) { synchronized(lock) { out.set(orientation) } }
}

/**
 * Detects Joy-Cons / Pro Controllers through Android's input stack (Bluetooth HID handled by the
 * OS driver). Buttons come from KeyEvents; orientation from the controller IMU when the kernel
 * driver exposes it to Android (InputDevice sensors, Android 12+). When no IMU is exposed the
 * controller still works for buttons and the pointer falls back to the hand/gaze direction.
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
        if (Build.VERSION.SDK_INT >= 31) {
            val sm = dev.sensorManager
            val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (gyro != null && acc != null) {
                val l = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) = jc.onSensor(e)
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                sm.registerListener(l, gyro, 5000)
                sm.registerListener(l, acc, 5000)
                jc.listener = l
            }
        }
        Diagnostics.log("Controller connected: ${dev.name} ($side)${if (jc.listener != null) " with IMU" else " (no IMU exposed)"}")
        onChange?.invoke()
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        val jc = controllers.remove(deviceId) ?: return
        if (Build.VERSION.SDK_INT >= 31) jc.listener?.let { l -> InputDevice.getDevice(deviceId)?.sensorManager?.unregisterListener(l) }
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
        jc.stickX = e.getAxisValue(MotionEvent.AXIS_X).let { if (kotlin.math.abs(it) < 0.12f) 0f else it }
        jc.stickY = -e.getAxisValue(MotionEvent.AXIS_Y).let { if (kotlin.math.abs(it) < 0.12f) 0f else it }
        // ZL/ZR may arrive as analog triggers on some drivers
        val zr = e.getAxisValue(MotionEvent.AXIS_RTRIGGER).coerceAtLeast(e.getAxisValue(MotionEvent.AXIS_GAS))
        val zl = e.getAxisValue(MotionEvent.AXIS_LTRIGGER).coerceAtLeast(e.getAxisValue(MotionEvent.AXIS_BRAKE))
        updateAnalog(jc, ControllerButton.ZR, zr > 0.5f)
        updateAnalog(jc, ControllerButton.ZL, zl > 0.5f)
        return true
    }

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
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> null
            else -> null
        }
    }
}
