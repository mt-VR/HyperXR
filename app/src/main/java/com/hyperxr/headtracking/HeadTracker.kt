package com.hyperxr.headtracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.diagnostics.Stage
import com.hyperxr.input.ControllerPoseFusion
import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3

interface HeadTracker {
    /** "6DoF (ARCore)", "3DoF (rotation vector)", ... shown to the user as-is. */
    val description: String
    val sixDof: Boolean
    fun start()
    fun stop()
    /** Head pose in tracking space, predicted [predictionNs] ahead. Called on the GL thread. */
    fun pose(predictionNs: Long, out: Pose)
    fun recenter()
}

/**
 * 3DoF head tracking from TYPE_GAME_ROTATION_VECTOR (gyro + accelerometer fusion, no magnetometer
 * jumps), with gyroscope-based prediction. The phone sits in landscape inside the viewer with the
 * screen facing the eyes; [displayRotation] selects the axis mapping. A neck model adds the small
 * translation the eyes undergo when the head rotates; it is labelled 3DoF because it is not tracked
 * translation.
 */
class RotationVectorTracker(context: Context, private val displayRotation: Int, private val gyroHistory: GyroHistory? = null) : HeadTracker, SensorEventListener {
    override val description = if (gyroHistory != null) "3DoF (rotation vector + gyro a toda a taxa, previsão até o fóton)" else "3DoF (rotation vector + gyro prediction, neck model)"
    override val sixDof = false

    private val sm = context.getSystemService(SensorManager::class.java)
    private val thread = HandlerThread("hx-head-sensors").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()
    private val deviceRot = Quat()
    private val gyro = Vec3()
    private var sampleTimeNs = 0L
    private val yawOffset = Quat()
    private val tmp = Quat()
    private val tmp2 = Quat()
    private val neck = Vec3()

    // Android world (X east, Y north, Z up) -> HyperXR world (Y up, -Z forward)
    private val worldFix = Quat.fromAxisAngle(1f, 0f, 0f, (-Math.PI / 2).toFloat())
    // head frame -> device frame for a landscape phone
    private val deviceFix = when (displayRotation) {
        Surface.ROTATION_270 -> Quat.fromAxisAngle(0f, 0f, 1f, (Math.PI / 2).toFloat())
        else -> Quat.fromAxisAngle(0f, 0f, 1f, (-Math.PI / 2).toFloat())
    }

    var neckModel = true
    /** Leaning and short steps from the linear accelerometer (PhoneXR's SensorSixDof, by Samrat Beketov). */
    var lean = true
    private val leanTracker = com.hyperxr.headtracking.LeanTracker()
    private var accelNs = 0L
    private val accelDev = Vec3(); private val accelWorld = Vec3()
    private var pendingRecenter = true

    override fun start() {
        // fastest rates, no batching: every sample as soon as the sensor hub has it
        sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let { sm.registerListener(this, it, 0, 0, handler) }
            ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(this, it, 0, 0, handler) }
        if (gyroHistory == null) sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, 0, 0, handler) }
        if (lean) sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, 0, handler) }
    }

    override fun stop() { sm.unregisterListener(this) }

    override fun onSensorChanged(e: SensorEvent) {
        val t0 = System.nanoTime()
        synchronized(lock) {
            when (e.sensor.type) {
                Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                    val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
                    val w = if (e.values.size > 3) e.values[3] else kotlin.math.sqrt(maxOf(0f, 1f - x * x - y * y - z * z))
                    deviceRot.set(x, y, z, w)
                    sampleTimeNs = e.timestamp
                }
                Sensor.TYPE_GYROSCOPE -> gyro.set(e.values[0], e.values[1], e.values[2])
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    val dt = if (accelNs == 0L) 0f else (e.timestamp - accelNs) / 1e9f
                    accelNs = e.timestamp
                    // device -> Android world (the fused rotation) -> HyperXR world (Y up), with the recentred yaw
                    deviceRot.rotate(accelDev.set(e.values[0], e.values[1], e.values[2]), accelWorld)
                    worldFix.rotate(accelWorld, accelDev)
                    yawOffset.rotate(accelDev, accelWorld)
                    leanTracker.update(accelWorld.x, accelWorld.y, accelWorld.z, dt)
                }
            }
        }
        Diagnostics.record(Stage.SENSOR_FUSION, System.nanoTime() - t0)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // scratch for pose(): it runs on the GL thread two or three times a frame, without allocating
    private val pq = Quat(); private val pw = Vec3(); private val pd = Quat(); private val neckBase = Vec3(0f, 1.6f - 0.075f, 0.08f)

    override fun pose(predictionNs: Long, out: Pose) {
        val q = pq
        val w = pw
        val ts: Long
        synchronized(lock) { q.set(deviceRot); w.set(gyro); ts = sampleTimeNs }
        // the measured gyro rotation since the fused sample, then prediction to photon time
        val target = SystemClock.elapsedRealtimeNanos() + predictionNs
        val d = pd
        if (gyroHistory != null && ts > 0 && gyroHistory.delta(ts, target, d)) q.mul(d, tmp)
        else {
            val ageNs = if (ts > 0) SystemClock.elapsedRealtimeNanos() - ts else 0L
            val dt = ((ageNs + predictionNs).coerceIn(0L, 60_000_000L)) / 1e9f
            Quat.integrate(q, w.x, w.y, w.z, dt, tmp)
        }
        // world_hx = worldFix * q_world_device * deviceFix
        worldFix.mul(tmp, tmp2)
        tmp2.mul(deviceFix, tmp)
        if (pendingRecenter) {
            ControllerPoseFusion.yawOnly(tmp, yawOffset)
            yawOffset.set(-yawOffset.x, -yawOffset.y, -yawOffset.z, yawOffset.w)
            pendingRecenter = false
        }
        yawOffset.mul(tmp, out.orientation)
        out.orientation.normalize()
        if (neckModel) {
            // eyes are ~8 cm in front of and ~7.5 cm above the neck pivot
            out.orientation.rotate(neck.set(0f, 0.075f, -0.08f), out.position)
            out.position.add(neckBase)
            if (lean) synchronized(lock) { val o = leanTracker.offset; out.position.x += o[0]; out.position.y += o[1]; out.position.z += o[2] }
        } else {
            out.position.set(0f, 1.6f, 0f)
        }
    }

    override fun recenter() { pendingRecenter = true; synchronized(lock) { leanTracker.reset() } }

    fun shutdown() { stop(); thread.quitSafely() }
}
