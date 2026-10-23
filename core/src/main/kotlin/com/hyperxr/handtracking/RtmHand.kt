package com.hyperxr.handtracking

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The math around OpenMMLab's hand models (RTMDet-nano finds hands, RTMPose-m places the 21
 * joints; Apache-2.0), independent of Android:
 *  - SimCC decoding: each joint's x and y are the best of 512 bins over the 256 px crop;
 *  - the crop for the next frame, from where the joints are now;
 *  - a presence score from the joints' confidences (calibrated on photos: a hand averages
 *    0.64-0.72, background, objects and people 0.2-0.5);
 *  - lifting the 2D joints to relative depth with the hand's bone proportions;
 *  - which hand it is from the palm's winding in the image.
 * Joint order is the same as MediaPipe's (wrist, thumb 1-4, index 5-8, ... pinky 17-20).
 */
object RtmHand {
    const val INPUT = 256
    const val BINS = 512
    const val SPLIT = 2f

    /** Square crop in frame pixels. */
    data class Crop(val cx: Float, val cy: Float, val side: Float)

    /**
     * SimCC outputs (21 x 512 each) to joints in frame pixels ([out]: x, y per joint) and the
     * per-joint confidence ([conf]).
     */
    fun decode(simX: FloatArray, simY: FloatArray, crop: Crop, out: FloatArray, conf: FloatArray) {
        for (j in 0 until HandJoint.COUNT) {
            var bx = 0; var vx = -Float.MAX_VALUE
            var by = 0; var vy = -Float.MAX_VALUE
            val o = j * BINS
            for (k in 0 until BINS) {
                val a = simX[o + k]; if (a > vx) { vx = a; bx = k }
                val b = simY[o + k]; if (b > vy) { vy = b; by = k }
            }
            val u = bx / SPLIT; val v = by / SPLIT
            out[j * 2] = crop.cx - crop.side / 2 + u / INPUT * crop.side
            out[j * 2 + 1] = crop.cy - crop.side / 2 + v / INPUT * crop.side
            conf[j] = min(vx, vy)
        }
    }

    /** 0..1: how sure the model is that the crop holds a hand. */
    fun presence(conf: FloatArray): Float = ((conf.average().toFloat() - 0.45f) / 0.2f).coerceIn(0f, 1f)

    /** The crop the model was trained with: the hand's box, square, 25% larger. */
    fun cropFromBox(x0: Float, y0: Float, x1: Float, y1: Float) = Crop((x0 + x1) / 2, (y0 + y1) / 2, max(x1 - x0, y1 - y0) * 1.25f)

    /** Next frame's crop from this frame's joints (frame pixels), slightly larger to allow for motion. */
    fun cropFromJoints(p: FloatArray): Crop {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (j in 0 until HandJoint.COUNT) { x0 = min(x0, p[j * 2]); x1 = max(x1, p[j * 2]); y0 = min(y0, p[j * 2 + 1]); y1 = max(y1, p[j * 2 + 1]) }
        // a fist's joints span little: never smaller than the palm's own size suggests
        val palm = palmScale(p)
        val c = cropFromBox(x0, y0, x1, y1)
        return c.copy(side = max(c.side * 1.1f, palm * 2.6f))
    }

    /** The rigid part of the palm (wrist, index, middle, ring, pinky knuckles), in palm lengths. */
    private val PALM = intArrayOf(HandJoint.WRIST, HandJoint.INDEX_MCP, HandJoint.MIDDLE_MCP, HandJoint.RING_MCP, HandJoint.PINKY_MCP)
    private val PALM_X = floatArrayOf(0f, -0.30f, 0f, 0.22f, 0.45f)
    private val PALM_Y = floatArrayOf(0f, -0.92f, -1f, -0.91f, -0.76f)

    /**
     * The palm's length in pixels as it would be facing the camera, however it is tilted. A tilted
     * flat palm is the palm squashed along one direction only; the direction along the tilt axis
     * keeps its full length. So the best 2D affine fit of the palm's shape to the five knuckles
     * has the true size as its larger singular value. (Wrist to middle knuckle alone shrinks as
     * the hand tilts, which made the hand's distance, and so its size, jump.)
     */
    fun palmScale(p: FloatArray): Float {
        var mx = 0f; var my = 0f; var tx = 0f; var ty = 0f
        for ((k, j) in PALM.withIndex()) { mx += p[j * 2]; my += p[j * 2 + 1]; tx += PALM_X[k]; ty += PALM_Y[k] }
        val n = PALM.size.toFloat(); mx /= n; my /= n; tx /= n; ty /= n
        // A = (P^T Q)(Q^T Q)^-1, with P the image points and Q the template, both centred
        var pqxx = 0f; var pqxy = 0f; var pqyx = 0f; var pqyy = 0f; var qxx = 0f; var qxy = 0f; var qyy = 0f
        for ((k, j) in PALM.withIndex()) {
            val px = p[j * 2] - mx; val py = p[j * 2 + 1] - my; val qx = PALM_X[k] - tx; val qy = PALM_Y[k] - ty
            pqxx += px * qx; pqxy += px * qy; pqyx += py * qx; pqyy += py * qy
            qxx += qx * qx; qxy += qx * qy; qyy += qy * qy
        }
        val det = qxx * qyy - qxy * qxy
        val a = (pqxx * qyy - pqxy * qxy) / det; val b = (pqxy * qxx - pqxx * qxy) / det
        val c = (pqyx * qyy - pqyy * qxy) / det; val d = (pqyy * qxx - pqyx * qxy) / det
        // larger singular value of [[a, b], [c, d]]
        val s1 = a * a + b * b + c * c + d * d
        val s2 = a * d - b * c
        val big = sqrt(max(0f, (s1 + sqrt(max(0f, s1 * s1 - 4 * s2 * s2))) / 2))
        return max(big, hypot(p[HandJoint.MIDDLE_MCP * 2] - p[0], p[HandJoint.MIDDLE_MCP * 2 + 1] - p[1])).coerceAtLeast(1f)
    }

    /** Bone lengths as a fraction of the palm (wrist to middle knuckle), per bone (parent, child). */
    private val BONES = arrayOf(
        intArrayOf(0, 1, 30), intArrayOf(1, 2, 38), intArrayOf(2, 3, 33), intArrayOf(3, 4, 30),
        intArrayOf(5, 6, 44), intArrayOf(6, 7, 26), intArrayOf(7, 8, 22),
        intArrayOf(9, 10, 48), intArrayOf(10, 11, 30), intArrayOf(11, 12, 23),
        intArrayOf(13, 14, 45), intArrayOf(14, 15, 29), intArrayOf(15, 16, 23),
        intArrayOf(17, 18, 36), intArrayOf(18, 19, 21), intArrayOf(19, 20, 20),
    )

    /**
     * Relative depth per joint (frame pixels, + = farther from the camera) from the 2D joints: a
     * bone that looks shorter than its real length points towards or away from the camera by the
     * difference. Seen with a headset the back of the hand faces the camera, and fingers bend
     * towards the palm, so bent bones go away from the camera. A tilted palm has its knuckles
     * farther than the wrist (the hand reaches out from the headset).
     */
    fun liftDepth(p: FloatArray, out: FloatArray) {
        val palm = palmScale(p)
        java.util.Arrays.fill(out, 0f)
        for (k in 1 until PALM.size) {
            val j = PALM[k]
            val len = hypot(PALM_X[k], PALM_Y[k]) * palm
            val l = hypot(p[j * 2] - p[0], p[j * 2 + 1] - p[1])
            out[j] = sqrt(max(0f, len * len - l * l))
        }
        for (b in BONES) {
            val a = b[0]; val c = b[1]; val len = b[2] / 100f * palm
            val l = hypot(p[c * 2] - p[a * 2], p[c * 2 + 1] - p[a * 2 + 1])
            out[c] = out[a] + sqrt(max(0f, len * len - l * l))
        }
    }

    /**
     * Probability that this is a right hand, from the image alone: the winding of wrist, index
     * knuckle and pinky knuckle flips between left and right hands (and between back and palm
     * views); with the back of the hand towards the headset camera, positive means right.
     */
    fun rightScore(p: FloatArray): Float {
        val ax = p[HandJoint.INDEX_MCP * 2] - p[0]; val ay = p[HandJoint.INDEX_MCP * 2 + 1] - p[1]
        val bx = p[HandJoint.PINKY_MCP * 2] - p[0]; val by = p[HandJoint.PINKY_MCP * 2 + 1] - p[1]
        val cross = ax * by - ay * bx
        val scale = (ax * ax + ay * ay + bx * bx + by * by).coerceAtLeast(1e-6f)
        // a palm seen edge-on has a small winding: little evidence either way
        val s = (cross / scale * 4f).coerceIn(-1f, 1f)
        return 0.5f + 0.3f * s
    }

    /**
     * Joints in the form the pipeline uses: normalized image x, y and depth in image-width units
     * ([image], 63 values), plus hand-centred metres from a 9 cm palm ([world], 63 values).
     */
    fun toPipeline(p: FloatArray, frameW: Int, frameH: Int, image: FloatArray, world: FloatArray, palmMeters: Float = 0.09f) {
        val z = FloatArray(HandJoint.COUNT)
        liftDepth(p, z)
        val m = palmMeters / palmScale(p)
        val cx = (p[0] + p[HandJoint.MIDDLE_MCP * 2]) / 2; val cy = (p[1] + p[HandJoint.MIDDLE_MCP * 2 + 1]) / 2
        for (j in 0 until HandJoint.COUNT) {
            image[j * 3] = p[j * 2] / frameW; image[j * 3 + 1] = p[j * 2 + 1] / frameH; image[j * 3 + 2] = z[j] / frameW
            world[j * 3] = (p[j * 2] - cx) * m; world[j * 3 + 1] = -(p[j * 2 + 1] - cy) * m; world[j * 3 + 2] = -z[j] * m
        }
    }
}
