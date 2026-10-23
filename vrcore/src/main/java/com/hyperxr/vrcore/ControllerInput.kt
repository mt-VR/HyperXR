package com.hyperxr.vrcore

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.hyperxr.daydream.ControllerFrame
import com.hyperxr.daydream.DaydreamController
import com.hyperxr.daydream.Evdev
import com.hyperxr.daydream.EvdevPad
import com.hyperxr.daydream.Quat
import rikka.shizuku.Shizuku
import java.io.FileInputStream
import kotlin.math.PI

/**
 * The emulated Daydream controller: a Joy-Con / gamepad read from /dev/input through Shizuku
 * (with its own gyro when the kernel exposes one), otherwise pointing where the head looks.
 * Shared by the controller service and the settings screen; runs while anyone uses it.
 */
object ControllerInput {
    private const val TAG = "HxVrCore"
    const val PREFS = "vrcore"
    val AXIS_PRESETS = listOf("1,2,3", "-2,1,3", "2,-1,3", "1,3,-2", "-1,2,-3")

    enum class Source { NONE, GAZE, GAMEPAD, GAMEPAD_IMU }

    @Volatile var source = Source.NONE; private set
    @Volatile var deviceName = ""; private set
    @Volatile var lastFrame: ControllerFrame? = null; private set
    @Volatile var shizukuState = ""; private set

    private lateinit var ctx: Context
    private val model = DaydreamController()
    private val users = HashSet<(ControllerFrame, Boolean) -> Unit>()
    private var tick: Thread? = null
    private var head = floatArrayOf(0f, 0f, 0f, 1f)
    private var headSeen = false
    private var firstFrame = true
    private var pad: EvdevPad? = null
    private val readers = ArrayList<Pair<Thread, ParcelFileDescriptor>>()
    private var opener: IInputOpener? = null

    /** Head: phone rotation vector, converted to the GVR frame (+Y up, -Z ahead) for a landscape phone in a viewer. */
    private val headListener = object : SensorEventListener {
        private val wq = Quat.axisAngle(1f, 0f, 0f, (-PI / 2).toFloat())   // east-north-up -> x-up-south
        private val dq = Quat.axisAngle(0f, 0f, 1f, (-PI / 2).toFloat())   // landscape screen -> head
        private val tmp = FloatArray(4)
        override fun onSensorChanged(e: SensorEvent) {
            SensorManager.getQuaternionFromVector(tmp, e.values)            // w, x, y, z
            val q = Quat.mul(Quat.mul(wq, floatArrayOf(tmp[1], tmp[2], tmp[3], tmp[0])), dq)
            synchronized(this@ControllerInput) { head = q; headSeen = true }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    fun axisMap(c: Context): IntArray = (c.getSharedPreferences(PREFS, 0).getString("axes", AXIS_PRESETS[0]) ?: AXIS_PRESETS[0])
        .split(",").mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 3 }?.toIntArray() ?: intArrayOf(1, 2, 3)

    fun setAxisMap(c: Context, spec: String) { c.getSharedPreferences(PREFS, 0).edit().putString("axes", spec).apply(); model.axisMap = axisMap(c) }

    /** [listener] gets every frame and whether it recentered; returns the stop handle. */
    @Synchronized fun use(context: Context, listener: (ControllerFrame, Boolean) -> Unit): () -> Unit {
        ctx = context.applicationContext
        users += listener
        if (tick == null) start()
        return { synchronized(this) { users -= listener; if (users.isEmpty()) stop() } }
    }

    fun recenter() = synchronized(this) { model.recenter() }

    private fun start() {
        model.axisMap = axisMap(ctx)
        firstFrame = true
        val sm = ctx.getSystemService(SensorManager::class.java)
        (sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR))
            ?.let { sm.registerListener(headListener, it, SensorManager.SENSOR_DELAY_FASTEST) }
        source = Source.GAZE
        connectGamepad()
        tick = Thread({
            while (!Thread.currentThread().isInterrupted) {
                emit()
                try { Thread.sleep(8) } catch (e: InterruptedException) { break }
            }
        }, "hx-daydream").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    private fun stop() {
        tick?.interrupt(); tick = null
        ctx.getSystemService(SensorManager::class.java).unregisterListener(headListener)
        closeReaders()
        source = Source.NONE
    }

    private fun emit() {
        val frame: ControllerFrame
        val recentered: Boolean
        val ls: List<(ControllerFrame, Boolean) -> Unit>
        synchronized(this) {
            val p = pad
            val now = SystemClock.elapsedRealtimeNanos()
            val input = if (p != null) synchronized(p) {
                val s = p.stick()
                DaydreamController.Input(now, p.daydreamButtons(), s[0], s[1], p.gyro(), p.accel(), if (headSeen) head else null)
            } else DaydreamController.Input(now, 0, 0f, 0f, head = if (headSeen) head else null)
            frame = model.update(input)
            // the first pose becomes "straight ahead"
            if (firstFrame && (headSeen || input.gyro != null)) { model.recenter(); firstFrame = false; frame.orientation = model.orientation() }
            recentered = model.recentered
            ls = users.toList()
        }
        lastFrame = frame
        for (l in ls) l(frame, recentered)
    }

    // ---------------------------------------------------------------- gamepad through Shizuku

    fun shizukuReady(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) { false }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val o = binder?.takeIf { it.pingBinder() }?.let { IInputOpener.Stub.asInterface(it) } ?: return
            opener = o
            Thread({ openDevices(o) }, "hx-daydream-open").start()
        }
        override fun onServiceDisconnected(name: ComponentName?) { opener = null }
    }

    private fun connectGamepad() {
        shizukuState = when {
            !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> "Shizuku não está rodando"
            !shizukuReady() -> "Shizuku sem permissão para o HyperXR Daydream"
            else -> "Shizuku pronto"
        }
        if (!shizukuReady()) return
        opener?.let { o -> Thread({ openDevices(o) }, "hx-daydream-open").start(); return }
        try {
            val version = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
            Shizuku.bindUserService(Shizuku.UserServiceArgs(ComponentName(ctx.packageName, InputOpener::class.java.name))
                .daemon(false).processNameSuffix("input").version(((version / 1000) % Int.MAX_VALUE).toInt().coerceAtLeast(1)), conn)
        } catch (t: Throwable) { Log.w(TAG, "Shizuku user service: ${t.message}") }
    }

    /** Finds the Joy-Con / gamepad (and its IMU) and starts a reader for each. */
    private fun openDevices(o: IInputOpener) {
        val devices = try { Evdev.parseGeteventP(o.listDevices()) } catch (t: Throwable) { emptyList() }
        val (padDev, motionDev) = Evdev.pick(devices)
        if (padDev == null) { Log.i(TAG, "no gamepad among ${devices.size} input devices"); return }
        val p = EvdevPad(padDev, motionDev)
        closeReaders()
        val padFd = try { o.open(padDev.path) } catch (t: Throwable) { null } ?: return
        startReader(padFd, p, motion = false)
        val motionFd = motionDev?.let { try { o.open(it.path) } catch (t: Throwable) { null } }
        if (motionFd != null) startReader(motionFd, p, motion = true)
        synchronized(this) { pad = p }
        deviceName = padDev.name
        source = if (motionFd != null && p.hasImu) Source.GAMEPAD_IMU else Source.GAMEPAD
        Log.i(TAG, "controller: $padDev, motion: $motionDev")
    }

    private fun startReader(fd: ParcelFileDescriptor, p: EvdevPad, motion: Boolean) {
        val t = Thread({
            val is64 = Process.is64Bit()
            val buf = ByteArray(Evdev.eventSize(is64) * 64)
            try {
                FileInputStream(fd.fileDescriptor).use { inp ->
                    while (!Thread.currentThread().isInterrupted) {
                        val n = inp.read(buf)
                        if (n <= 0) break
                        val events = Evdev.parse(buf, n, is64)
                        synchronized(p) { for (e in events) if (motion) p.onMotionEvent(e) else p.onPadEvent(e) }
                    }
                }
            } catch (t: Throwable) { Log.i(TAG, "input reader ended: ${t.message}") }
            // the controller went away (Bluetooth off, out of range)
            synchronized(this) { if (pad === p) { pad = null; source = Source.GAZE; deviceName = "" } }
        }, if (motion) "hx-daydream-imu" else "hx-daydream-pad")
        t.start()
        synchronized(readers) { readers += t to fd }
    }

    private fun closeReaders() {
        synchronized(readers) {
            for ((t, fd) in readers) { t.interrupt(); runCatching { fd.close() } }
            readers.clear()
        }
        synchronized(this) { pad = null }
    }

    /** Looks for the gamepad again (after pairing a Joy-Con or granting Shizuku). */
    fun rescan() { if (tick != null) connectGamepad() }
}
