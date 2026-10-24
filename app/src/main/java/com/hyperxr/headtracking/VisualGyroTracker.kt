package com.hyperxr.headtracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.Image
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import com.hyperxr.handtracking.CameraIntrinsics
import com.hyperxr.input.ControllerPoseFusion
import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import java.util.concurrent.atomic.AtomicReference

/**
 * 3DoF head tracking for phones without a gyroscope: the computational gyroscope ([VisualGyro],
 * rotation from the camera with a keyframe map) plus the accelerometer for pitch and roll.
 * Camera frames come from the passthrough camera (the one hand tracking uses); each is reduced to
 * a 160-px-wide grey image on the calling thread (a few hundred microseconds) and processed on
 * its own thread, newest frame first. The compass, when the phone has one, keeps yaw from drifting
 * over minutes; readings with an unusual field strength (a speaker, a metal desk) are ignored.
 */
class VisualGyroTracker(context: Context, private val displayRotation: Int) : HeadTracker, SensorEventListener {
    override val description: String get() = "3DoF (giroscópio computacional: câmera + mapa${if (gyro?.tracking == false) ", sem textura" else ""})"
    override val sixDof = false

    private val sm = context.getSystemService(SensorManager::class.java)
    private val thread = HandlerThread("hx-visual-gyro", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val flipped = displayRotation == Surface.ROTATION_270
    @Volatile private var gyro: VisualGyro? = null
    private val pending = AtomicReference<Pair<GrayImage, Long>?>(null)
    private val lock = Any()
    private val orientation = Quat()
    private val angular = Vec3()
    private var sampleNs = 0L
    private val yawOffset = Quat()
    private var pendingRecenter = true
    private val gravity = Vec3()
    private var haveGravity = false
    private val magnetic = Vec3()
    private var haveMagnetic = false
    private val neck = Vec3()

    /** Head frame from the phone's sensor frame (as for the rotation-vector tracker). */
    private val deviceFix = when (displayRotation) {
        Surface.ROTATION_270 -> Quat.fromAxisAngle(0f, 0f, 1f, (Math.PI / 2).toFloat())
        else -> Quat.fromAxisAngle(0f, 0f, 1f, (-Math.PI / 2).toFloat())
    }

    override fun start() {
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, 5000, handler) }
        sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { sm.registerListener(this, it, 20000, handler) }
    }

    override fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            if (!haveMagnetic) { magnetic.set(e.values[0], e.values[1], e.values[2]); haveMagnetic = true }
            else magnetic.set(magnetic.x * 0.8f + e.values[0] * 0.2f, magnetic.y * 0.8f + e.values[1] * 0.2f, magnetic.z * 0.8f + e.values[2] * 0.2f)
            return
        }
        // low-passed specific force = "up" while the head is not accelerating
        if (!haveGravity) { gravity.set(e.values[0], e.values[1], e.values[2]); haveGravity = true }
        else gravity.set(gravity.x * 0.9f + e.values[0] * 0.1f, gravity.y * 0.9f + e.values[1] * 0.1f, gravity.z * 0.9f + e.values[2] * 0.1f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** A camera frame (YUV, not closed here) with its intrinsics; call before the image is closed. */
    fun submit(image: Image, intr: CameraIntrinsics) {
        val y = image.planes[0]
        val step = maxOf(1, image.width / 160)
        val w = image.width / step; val h = image.height / step
        val g = GrayImage(w, h)
        val buf = y.buffer; val row = y.rowStride; val px = y.pixelStride
        for (oy in 0 until h) {
            val sy = oy * step
            val dst = (if (flipped) h - 1 - oy else oy) * w
            for (ox in 0 until w) {
                val v = buf.get(sy * row + ox * step * px)
                g.px[dst + (if (flipped) w - 1 - ox else ox)] = v
            }
        }
        val now = SystemClock.elapsedRealtimeNanos()
        val camTs = try { image.timestamp } catch (_: Throwable) { 0L }
        // the camera clock is REALTIME on nearly every phone; otherwise use the arrival time
        val ts = if (now - camTs in 0..250_000_000L) camTs else now
        if (gyro == null) {
            // intrinsics at the reduced size (the image is already turned upright for 180°)
            val s = w.toFloat() / intr.width
            gyro = VisualGyro(intr.fx * s, intr.fy * s, (if (flipped) intr.width - intr.cx else intr.cx) * s, (if (flipped) intr.height - intr.cy else intr.cy) * s)
                .also { it.reset(levelFromGravity()) }
        }
        if (pending.getAndSet(g to ts) == null) handler.post { drain() }
    }

    private fun drain() {
        val (img, ts) = pending.getAndSet(null) ?: return
        val g = gyro ?: return
        g.process(img, ts)
        if (haveGravity) {
            val up = headUp()
            val len = up.length()
            // only when the phone is not being accelerated (|a| close to 1 g)
            if (len in 9.3f..10.3f) g.applyGravity(up, 0.02f)
        }
        if (haveMagnetic) {
            val m = deviceFix.conjugate(Quat()).rotate(Vec3(magnetic.x, magnetic.y, magnetic.z))
            // the Earth's field is 25-65 uT; outside that something nearby is bending it
            if (m.length() in 22f..70f) g.applyHeading(m, 0.003f)
        }
        synchronized(lock) { orientation.set(g.orientation); angular.set(g.angularVelocity); sampleNs = ts }
    }

    /** Gravity reaction ("up") in the head frame: v_head = fix⁻¹ · v_device. */
    private fun headUp(): Vec3 = deviceFix.conjugate(Quat()).rotate(Vec3(gravity.x, gravity.y, gravity.z))

    /** Starting orientation: level according to the accelerometer, facing forward. */
    private fun levelFromGravity(): Quat {
        if (!haveGravity) return Quat()
        val up = headUp().normalize()
        return Quat.fromTo(up, Vec3(0f, 1f, 0f))
    }

    override fun pose(predictionNs: Long, out: Pose) {
        val q = poseQ; val w = poseW; val ts: Long
        synchronized(lock) { q.set(orientation); w.set(angular); ts = sampleNs }
        // camera rate (30-60 Hz): carried forward by the measured angular velocity to photon time
        val age = if (ts > 0) (SystemClock.elapsedRealtimeNanos() - ts + predictionNs).coerceIn(0L, 80_000_000L) else 0L
        Quat.integrate(q, w.x, w.y, w.z, age / 1e9f, q)
        if (pendingRecenter) {
            ControllerPoseFusion.yawOnly(q, yawOffset)
            yawOffset.set(-yawOffset.x, -yawOffset.y, -yawOffset.z, yawOffset.w)
            pendingRecenter = false
        }
        yawOffset.mul(q, out.orientation)
        out.orientation.normalize()
        // eyes ~8 cm in front of and ~7.5 cm above the neck pivot
        out.orientation.rotate(neck.set(0f, 0.075f, -0.08f), out.position)
        out.position.add(neckBase)
    }

    // pose() scratch (GL thread, two or three calls a frame)
    private val poseQ = Quat(); private val poseW = Vec3(); private val neckBase = Vec3(0f, 1.6f - 0.075f, 0.08f)

    override fun recenter() { pendingRecenter = true }

    val keyframes: Int get() = gyro?.keyframeCount ?: 0

    fun shutdown() { stop(); thread.quitSafely() }

    companion object {
        fun hasGyroscope(c: Context) = c.getSystemService(SensorManager::class.java).getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
    }
}
