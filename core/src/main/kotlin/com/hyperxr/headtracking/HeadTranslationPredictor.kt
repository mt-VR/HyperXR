package com.hyperxr.headtracking

import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3

/**
 * Head position at photon time from a 6DoF pose that is a camera frame old (ARCore: 30-60 Hz,
 * tens of ms behind). Two parts:
 *  - neck model: most head translation while looking around is the eyes swinging around the neck
 *    as the head turns. That part is computed from the (gyro-predicted) orientation, which is
 *    current, so turning the head moves the view with no lag from the camera pose;
 *  - the rest (walking, leaning) is the neck pivot's own velocity, smoothed by a filter that
 *    opens up with speed, and used only while the head really moves: a still head's tracking
 *    noise times 50 ms of prediction was visible as shaking. The pivot itself is smoothed with a
 *    filter that is strong at rest and opens up with speed, which removes the tracker's own
 *    millimetre noise from a still view without adding lag to real movement.
 */
class HeadTranslationPredictor(
    /** Eyes relative to the neck pivot, head frame (x right, y up, -z forward). */
    val eyeFromNeck: Vec3 = Vec3(0f, 0.075f, -0.08f),
) {
    val velocity = Vec3()
    var gain = 0f; private set
    var stillSpeed = 0.03f
    var movingSpeed = 0.18f
    var cutoffHz = 2f
    var cutoffPerMps = 12f
    private val prevPivot = Vec3()
    private val smoothPivot = Vec3()
    var restCutoffHz = 1.5f
    var restCutoffPerMps = 40f
    private var prevTs = 0L
    private val tmp = Vec3(); private val tmp2 = Vec3()

    fun reset() { velocity.set(0f, 0f, 0f); gain = 0f; prevTs = 0L }

    private val pivotScratch = Vec3()

    /** A new tracked pose: eye position [pos] and orientation [q] at [timestampNs]. */
    fun onPose(pos: Vec3, q: Quat, timestampNs: Long) {
        val pivot = pivotScratch.set(pos).sub(q.rotate(eyeFromNeck, tmp))
        if (prevTs != 0L && timestampNs > prevTs) {
            val dt = (timestampNs - prevTs) / 1e9f
            if (dt in 0.005f..0.2f) {
                val sp = velocity.length()
                val cutoff = cutoffHz + cutoffPerMps * sp.coerceAtMost(2f)
                val tau = 1f / (2f * Math.PI.toFloat() * cutoff)
                val a = 1f / (1f + tau / dt)
                velocity.set(
                    velocity.x + ((pivot.x - prevPivot.x) / dt - velocity.x) * a,
                    velocity.y + ((pivot.y - prevPivot.y) / dt - velocity.y) * a,
                    velocity.z + ((pivot.z - prevPivot.z) / dt - velocity.z) * a,
                )
                val v = velocity.length()
                if (v > 3f) velocity.scale(3f / v)
            } else velocity.set(0f, 0f, 0f)
        }
        val s = velocity.length()
        if (prevTs == 0L) smoothPivot.set(pivot)
        else {
            val dt = ((timestampNs - prevTs) / 1e9f).coerceIn(0.001f, 0.2f)
            val tau = 1f / (2f * Math.PI.toFloat() * (restCutoffHz + restCutoffPerMps * s))
            smoothPivot.lerp(pivot, 1f / (1f + tau / dt))
        }
        val t = ((s - stillSpeed) / (movingSpeed - stillSpeed)).coerceIn(0f, 1f)
        gain = t * t * (3 - 2 * t)
        prevPivot.set(pivot); prevTs = timestampNs
    }

    /**
     * Eye position at photon time: [pos]/[qAtPose] from the tracked pose, [qPredicted] the
     * orientation at photon time, [aheadSeconds] from the pose to photon time.
     */
    fun predict(pos: Vec3, qAtPose: Quat, qPredicted: Quat, aheadSeconds: Float, out: Vec3): Vec3 {
        // smoothed pivot (the raw one when [pos] is not the last pose given to onPose)
        out.set(pos).sub(qAtPose.rotate(eyeFromNeck, tmp))
        if (out.distanceTo(prevPivot) < 1e-5f) out.set(smoothPivot)
        out.add(qPredicted.rotate(eyeFromNeck, tmp2))
        return out.addScaled(velocity, gain * aheadSeconds.coerceIn(0f, 0.08f))
    }
}
