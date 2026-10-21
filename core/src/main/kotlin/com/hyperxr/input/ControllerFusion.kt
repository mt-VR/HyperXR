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
 * Combines a tracked hand position with a controller's relative orientation into a pointing ray.
 *
 * Coordinate frames:
 *  - world: tracking space of the head tracker (-Z forward, +Y up).
 *  - controller IMU frame: arbitrary yaw reference chosen when the filter initialised.
 * A calibration quaternion maps the IMU frame into world space. Calibration happens on recenter:
 * the user points the controller straight ahead and the yaw difference to the head is stored.
 */
class ControllerPoseFusion {
    private val calibration = Quat()
    var calibrated = false
        private set

    /** Fallback grip offset from the head when the hand is not tracked (right hand default). */
    val fallbackOffset = Vec3(0.18f, -0.32f, -0.30f)

    val worldOrientation = Quat()
    val gripPosition = Vec3()
    var positionFromHand = false
        private set

    private val tmpV = Vec3()
    private val tmpQ = Quat()

    /** Aligns the controller's forward direction with the head's yaw (pitch/roll come from gravity). */
    fun recenter(imuOrientation: Quat, headPose: Pose) {
        val headYaw = yawOnly(headPose.orientation, Quat())
        val ctrlYaw = yawOnly(imuOrientation, Quat())
        headYaw.mul(ctrlYaw.conjugate(), calibration)
        calibrated = true
    }

    fun update(imuOrientation: Quat, handWrist: Vec3?, headPose: Pose, mirrorForLeft: Boolean = false) {
        calibration.mul(imuOrientation, worldOrientation).normalize()
        if (handWrist != null) {
            gripPosition.set(handWrist)
            positionFromHand = true
        } else {
            val yaw = yawOnly(headPose.orientation, tmpQ)
            tmpV.set(if (mirrorForLeft) -fallbackOffset.x else fallbackOffset.x, fallbackOffset.y, fallbackOffset.z)
            yaw.rotate(tmpV, gripPosition).add(headPose.position)
            positionFromHand = false
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
