package com.hyperxr.input

import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Ray
import com.hyperxr.math.Vec3
import com.hyperxr.settings.ControllerAction
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Orientation from a 6-axis IMU (gyro + accelerometer) using a complementary filter: the gyro
 * is integrated for responsiveness, and pitch/roll are slowly pulled towards gravity. Yaw has no
 * absolute reference (no magnetometer on Joy-Cons), so it drifts and is corrected by recentering.
 * This is orientation only: an IMU alone is never treated as absolute 6DoF tracking.
 */
class ImuOrientationFilter(var gravityGain: Float = 0.02f) {
    val orientation = Quat()
    private var initialized = false
    private val g = Vec3()
    private val up = Vec3(0f, 1f, 0f)
    private val corr = Quat()
    private val tmp = Quat()

    fun reset() { orientation.identity(); initialized = false }

    /** Gyro in rad/s and accelerometer in m/s², both already converted to the HyperXR controller frame. */
    fun update(gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float, dt: Float) {
        val aLen = sqrt(ax * ax + ay * ay + az * az)
        if (!initialized) {
            if (aLen > 1f) {
                // Initial tilt from gravity; yaw starts at zero.
                Quat.fromTo(g.set(ax / aLen, ay / aLen, az / aLen), up, orientation)
                initialized = true
            }
            return
        }
        Quat.integrate(orientation, gx, gy, gz, dt, tmp)
        orientation.set(tmp)
        // Only trust the accelerometer when it measures ~1g (not during strong swings).
        if (aLen in 8.3f..11.3f) {
            orientation.rotate(g.set(ax / aLen, ay / aLen, az / aLen), g)
            Quat.fromTo(g, up, corr)
            Quat().identity().slerp(corr, gravityGain, tmp)
            tmp.mul(orientation, corr)
            orientation.set(corr).normalize()
        }
    }
}

/**
 * Low-latency smoothing for pointing rays: One Euro filters on origin and direction (adaptive:
 * steady when still, responsive when moving) and a short hold when the source disappears, so a
 * dropped hand-tracking frame does not make the pointer jump.
 */
class RayStabilizer(minCutoff: Float = 1.0f, beta: Float = 0.6f, var holdNs: Long = 350_000_000L) {
    private val f = Array(6) { com.hyperxr.handtracking.OneEuroFilter(minCutoff, beta) }
    private val last = Ray()
    private var lastNs = 0L
    private var has = false

    fun reset() { f.forEach { it.reset() }; has = false }

    /** Returns the smoothed ray, the held ray while [input] is briefly missing, or null. */
    fun update(input: Ray?, nowNs: Long, out: Ray): Ray? {
        if (input == null) {
            if (has && nowNs - lastNs < holdNs) { out.origin.set(last.origin); out.direction.set(last.direction); return out }
            reset()
            return null
        }
        val dt = if (has) ((nowNs - lastNs) / 1e9f).coerceIn(1e-4f, 0.1f) else 0f
        out.origin.set(f[0].filter(input.origin.x, dt), f[1].filter(input.origin.y, dt), f[2].filter(input.origin.z, dt))
        out.direction.set(f[3].filter(input.direction.x, dt), f[4].filter(input.direction.y, dt), f[5].filter(input.direction.z, dt)).normalize()
        last.origin.set(out.origin); last.direction.set(out.direction)
        lastNs = nowNs; has = true
        return out
    }
}

/**
 * Pose of a controller held in a tracked hand.
 *
 * Default (hand 6DoF): the pointer comes entirely from hand tracking: origin at the hand, direction
 * along the shoulder->hand ray, both stabilised. This is absolute (no drift) and works with any
 * Joy-Con, including drivers that expose no IMU.
 *
 * With [useImu] and an IMU available, the IMU orientation drives the ray (lower latency for wrist
 * rotation) and its drift is pulled continuously towards the hand ray, so it never wanders off.
 * An IMU alone is never treated as absolute 6DoF.
 */
class ControllerPoseFusion {
    var useImu = false
    /** How fast IMU yaw/pitch drift converges to the hand ray (fraction per second). */
    var imuCorrectionRate = 0.6f

    private val calibration = Quat()
    var calibrated = false
        private set

    /** Fallback grip offset from the head when nothing tracks the hand (right hand default). */
    val fallbackOffset = Vec3(0.18f, -0.32f, -0.30f)

    val worldOrientation = Quat()
    val gripPosition = Vec3()
    var positionFromHand = false
        private set
    var orientationSource = "head"
        private set

    private val stabilizer = RayStabilizer()
    private val rawRay = Ray()
    private val handRay = Ray()
    private val tmpV = Vec3()
    private val tmpQ = Quat()
    private val tmpQ2 = Quat()
    private val fwd = Vec3()

    /** Aligns the IMU's forward direction with the head's yaw (pitch/roll come from gravity). */
    fun recenter(imuOrientation: Quat, headPose: Pose) {
        val headYaw = yawOnly(headPose.orientation, Quat())
        val ctrlYaw = yawOnly(imuOrientation, Quat())
        headYaw.mul(ctrlYaw.conjugate(), calibration)
        calibrated = true
    }

    fun update(imu: Quat?, hand: com.hyperxr.handtracking.HandState?, headPose: Pose, rightSide: Boolean, nowNs: Long, dtSeconds: Float) {
        val raw = if (hand != null) HandRay.compute(hand, headPose, rightSide, rawRay) else null
        val ray = stabilizer.update(raw, nowNs, handRay)
        val imuActive = useImu && imu != null
        if (imuActive && !calibrated) recenter(imu!!, headPose)

        if (ray != null) {
            gripPosition.set(ray.origin).addScaled(ray.direction, ray.origin.distanceTo(hand!!.joints[com.hyperxr.handtracking.HandJoint.INDEX_MCP]).coerceAtMost(0.8f))
            positionFromHand = true
            if (imuActive) {
                calibration.mul(imu!!, worldOrientation).normalize()
                // drift correction: rotate the calibration a little towards the hand ray every frame
                worldOrientation.rotate(Pose.FORWARD, fwd)
                Quat.fromTo(fwd, ray.direction, tmpQ)
                tmpQ2.identity().slerp(tmpQ, (imuCorrectionRate * dtSeconds).coerceIn(0f, 1f), tmpQ)
                tmpQ.mul(calibration, tmpQ2); calibration.set(tmpQ2).normalize()
                calibration.mul(imu, worldOrientation).normalize()
                orientationSource = "imu+hand"
            } else {
                lookRotation(ray.direction, worldOrientation)
                orientationSource = "hand"
            }
            return
        }
        positionFromHand = false
        val yaw = yawOnly(headPose.orientation, tmpQ)
        tmpV.set(if (rightSide) fallbackOffset.x else -fallbackOffset.x, fallbackOffset.y, fallbackOffset.z)
        yaw.rotate(tmpV, gripPosition).add(headPose.position)
        if (imuActive) {
            calibration.mul(imu!!, worldOrientation).normalize()
            orientationSource = "imu"
        } else {
            worldOrientation.set(headPose.orientation)
            orientationSource = "head"
        }
    }

    fun ray(out: Ray = Ray()): Ray {
        out.origin.set(gripPosition)
        worldOrientation.rotate(Pose.FORWARD, out.direction).normalize()
        return out
    }

    companion object {
        /** Extracts the rotation about +Y (heading) of an orientation. */
        fun yawOnly(q: Quat, out: Quat): Quat {
            val f = q.rotate(Vec3(0f, 0f, -1f))
            val yaw = atan2(-f.x, -f.z)
            return Quat.fromAxisAngle(0f, 1f, 0f, yaw, out)
        }

        /** Orientation whose -Z axis points along [dir] with +Y as close to world up as possible. */
        fun lookRotation(dir: Vec3, out: Quat): Quat {
            val yaw = atan2(-dir.x, -dir.z)
            val pitch = kotlin.math.asin(dir.y.coerceIn(-1f, 1f))
            Quat.fromAxisAngle(0f, 1f, 0f, yaw, out)
            return out.mul(Quat.fromAxisAngle(1f, 0f, 0f, pitch), out)
        }
    }
}

/** Physical buttons HyperXR understands, independent of the device that produced them. */
enum class ControllerButton { R, L, ZR, ZL, PLUS, MINUS, HOME, CAPTURE, A, B, X, Y, STICK_R, STICK_L, SL, SR }

/**
 * Edge-triggered mapping from physical buttons to actions. A held button produces exactly one
 * press and one release, no matter how many frames or duplicate key events arrive.
 */
class ButtonMapper(var mapping: Map<ControllerButton, ControllerAction>) {
    private val down = HashSet<ControllerButton>()
    private val actionsHeld = HashMap<ControllerAction, Int>()

    data class ActionEvent(val action: ControllerAction, val pressed: Boolean, val button: ControllerButton)

    fun onButton(button: ControllerButton, isDown: Boolean): ActionEvent? {
        val changed = if (isDown) down.add(button) else down.remove(button)
        if (!changed) return null
        val action = mapping[button] ?: return null
        if (action == ControllerAction.NONE) return null
        val held = actionsHeld.getOrDefault(action, 0)
        return if (isDown) {
            actionsHeld[action] = held + 1
            if (held == 0) ActionEvent(action, true, button) else null
        } else {
            val n = (held - 1).coerceAtLeast(0)
            actionsHeld[action] = n
            if (n == 0) ActionEvent(action, false, button) else null
        }
    }

    fun isActionHeld(a: ControllerAction) = actionsHeld.getOrDefault(a, 0) > 0
    fun isDown(b: ControllerButton) = b in down

    /** Called on disconnect so a button held during a Bluetooth drop does not stay stuck. */
    fun releaseAll(): List<ActionEvent> {
        val events = down.toList().mapNotNull { onButton(it, false) }
        down.clear(); actionsHeld.clear()
        return events
    }

    companion object {
        fun fromSettings(s: com.hyperxr.settings.ControllerSettings) = ButtonMapper(
            mapOf(
                ControllerButton.R to s.buttonR, ControllerButton.L to s.buttonL,
                ControllerButton.ZR to s.buttonZR, ControllerButton.ZL to s.buttonZL,
                ControllerButton.PLUS to s.buttonPlus, ControllerButton.MINUS to ControllerAction.MENU,
                ControllerButton.HOME to s.buttonHome, ControllerButton.A to ControllerAction.SELECT,
                ControllerButton.B to ControllerAction.BACK,
            ),
        )
    }
}
