package com.hyperxr.handtracking

import com.hyperxr.math.Vec3

/**
 * Real hand size. The depth of a hand seen by a single camera is only as good as the hand size it
 * assumes, so HyperXR measures it: with ARCore Depth every confident open-hand observation yields
 * a metric palm length; the median of many samples becomes the user's palm length. Without depth
 * the user's measured hand length (ruler) is converted with the anthropometric palm/hand ratio.
 */
object HandScale {
    /** Palm length (wrist crease to third metacarpal head) is ~0.56 of hand length in adults. */
    const val PALM_TO_HAND_RATIO = 0.56f

    fun palmFromHandLength(handLengthCm: Float): Float = handLengthCm / 100f * PALM_TO_HAND_RATIO

    /** Palm length in metres from settings: measured value when present, else from hand length. */
    fun palmLength(calibratedPalmMm: Float, handLengthCm: Float): Float =
        if (calibratedPalmMm in 50f..140f) calibratedPalmMm / 1000f else palmFromHandLength(handLengthCm)
}

class HandScaleEstimator(private val needed: Int = 90) {
    private val samples = FloatArray(needed)
    private var count = 0
    var result: Float? = null
        private set

    val progress: Float get() = count.toFloat() / needed

    fun reset() { count = 0; result = null }

    /**
     * Feed metric camera-space joints computed with real depth. Only open, frontal hands are used:
     * the palm length must agree with the palm width (foreshortening makes them disagree).
     */
    fun add(joints: Array<Vec3>, depthSource: DepthSource, confidence: Float): Float? {
        if (result != null || depthSource != DepthSource.DEPTH_MAP || confidence < 0.6f) return result
        val length = joints[HandJoint.WRIST].distanceTo(joints[HandJoint.MIDDLE_MCP])
        val width = joints[HandJoint.INDEX_MCP].distanceTo(joints[HandJoint.PINKY_MCP])
        if (length !in 0.06f..0.14f || width < 0.04f) return null
        val shape = width / length // adults ~0.7..0.95 when the palm faces the camera
        if (shape !in 0.6f..1.05f) return null
        samples[count++] = length
        if (count == needed) {
            samples.sort()
            // trimmed mean of the middle half: robust to depth-edge outliers
            var sum = 0f
            for (i in needed / 4 until needed * 3 / 4) sum += samples[i]
            result = sum / (needed * 3 / 4 - needed / 4)
        }
        return result
    }
}
