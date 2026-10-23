package com.hyperxr.handtracking

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Geometry around the promptable segmenter (EfficientViT-SAM): the square crop that holds the
 * hands, the prompts each hand gets (its box and points on the hand from the landmarks), and the
 * full-frame mask made from the segmenter's per-hand masks - moved and scaled with each hand
 * between two runs of the network, so the mask keeps up with the camera at the network's quality.
 *
 * Frame coordinates are pixels of the analysed camera image; landmarks are 0..1 in it.
 */
object SamHands {
    /** Input side of the encoder and the coordinate space of the prompts. */
    const val ENCODER_SIZE = 512
    const val PROMPT_SPACE = 1024f
    /** The decoder's mask covers the crop with this many cells per side. */
    const val MASK_SIZE = 256
    const val PROMPTS = 8

    /** Joints used as points on the hand: palm (centre added separately), finger middles, thumb. */
    private val POINT_JOINTS = intArrayOf(HandJoint.INDEX_PIP, HandJoint.MIDDLE_PIP, HandJoint.RING_PIP, HandJoint.PINKY_PIP, HandJoint.THUMB_IP)

    /** Square crop (frame px) holding every hand, with room around them. */
    class Crop(val x0: Float, val y0: Float, val side: Float)

    fun crop(hands: List<FloatArray>, frameW: Int, frameH: Int): Crop {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (h in hands) for (j in 0 until HandJoint.COUNT) {
            val x = h[j * 2] * frameW; val y = h[j * 2 + 1] * frameH
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
        }
        val side = (max(maxX - minX, maxY - minY) * 1.35f).coerceAtLeast(64f)
        return Crop((minX + maxX) / 2 - side / 2, (minY + maxY) / 2 - side / 2, side)
    }

    /**
     * The 8 prompts of one hand in the encoder's 1024 space: box corners (labels 2 and 3), then
     * the palm's centre and five points inside the fingers and thumb (label 1).
     */
    fun prompts(hand: FloatArray, crop: Crop, frameW: Int, frameH: Int, points: FloatArray, labels: FloatArray) {
        fun px(j: Int) = hand[j * 2] * frameW
        fun py(j: Int) = hand[j * 2 + 1] * frameH
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (j in 0 until HandJoint.COUNT) { minX = min(minX, px(j)); maxX = max(maxX, px(j)); minY = min(minY, py(j)); maxY = max(maxY, py(j)) }
        // landmarks sit inside the fingers: the box grows by a finger's width, and reaches the wrist's edge
        val palm = hypot(px(HandJoint.MIDDLE_MCP) - px(0), py(HandJoint.MIDDLE_MCP) - py(0))
        val m = 0.22f * palm
        fun put(i: Int, x: Float, y: Float, label: Float) {
            points[i * 2] = (x - crop.x0) / crop.side * PROMPT_SPACE
            points[i * 2 + 1] = (y - crop.y0) / crop.side * PROMPT_SPACE
            labels[i] = label
        }
        put(0, minX - m, minY - m, 2f); put(1, maxX + m, maxY + m, 3f)
        val palmJ = intArrayOf(0, HandJoint.INDEX_MCP, HandJoint.MIDDLE_MCP, HandJoint.RING_MCP, HandJoint.PINKY_MCP)
        put(2, palmJ.map { px(it) }.average().toFloat(), palmJ.map { py(it) }.average().toFloat(), 1f)
        for ((k, j) in POINT_JOINTS.withIndex()) put(3 + k, px(j), py(j), 1f)
    }

    /** Where a hand was and how big (palm length, px), to follow it between two network runs. */
    class Anchor(val x: Float, val y: Float, val size: Float)

    fun anchor(hand: FloatArray, frameW: Int, frameH: Int): Anchor {
        val wx = hand[0] * frameW; val wy = hand[1] * frameH
        val mx = hand[HandJoint.MIDDLE_MCP * 2] * frameW; val my = hand[HandJoint.MIDDLE_MCP * 2 + 1] * frameH
        return Anchor((wx + mx) / 2, (wy + my) / 2, hypot(mx - wx, my - wy).coerceAtLeast(1f))
    }

    /** One hand's mask from the network: logits over [crop], and where the hand was then. */
    class HandMask(val crop: Crop, val logits: FloatArray, val anchor: Anchor)

    /**
     * The full-frame mask ([outSize] x [outSize], the frame squeezed to a square, 0..255) from the
     * hands' masks, each moved and scaled from where its hand was ([HandMask.anchor]) to where it
     * is now ([now], same order; null = not seen now, drawn where it was).
     */
    fun compose(masks: List<HandMask>, now: List<Anchor?>, frameW: Int, frameH: Int, outSize: Int, out: ByteArray) {
        java.util.Arrays.fill(out, 0)
        for ((k, hm) in masks.withIndex()) {
            val a0 = hm.anchor; val a1 = now.getOrNull(k) ?: a0
            val s = a0.size / a1.size                      // frame px now -> frame px then
            val c = hm.crop
            val cell = c.side / MASK_SIZE
            // only the part of the output the moved crop can reach
            val reach = c.side / s
            val cxNow = a1.x + (c.x0 + c.side / 2 - a0.x) / s; val cyNow = a1.y + (c.y0 + c.side / 2 - a0.y) / s
            val ox0 = (((cxNow - reach / 2) / frameW) * outSize).toInt().coerceIn(0, outSize - 1)
            val ox1 = (((cxNow + reach / 2) / frameW) * outSize).toInt().coerceIn(0, outSize - 1)
            val oy0 = (((cyNow - reach / 2) / frameH) * outSize).toInt().coerceIn(0, outSize - 1)
            val oy1 = (((cyNow + reach / 2) / frameH) * outSize).toInt().coerceIn(0, outSize - 1)
            for (oy in oy0..oy1) {
                val fy = (oy + 0.5f) / outSize * frameH
                val yThen = a0.y + (fy - a1.y) * s
                val v = (yThen - c.y0) / cell - 0.5f
                if (v < 0f || v > MASK_SIZE - 1) continue
                for (ox in ox0..ox1) {
                    val fx = (ox + 0.5f) / outSize * frameW
                    val xThen = a0.x + (fx - a1.x) * s
                    val u = (xThen - c.x0) / cell - 0.5f
                    if (u < 0f || u > MASK_SIZE - 1) continue
                    val p = (sigmoid(bilinear(hm.logits, u, v)) * 255f).toInt()
                    val i = oy * outSize + ox
                    if (p > (out[i].toInt() and 0xff)) out[i] = p.toByte()
                }
            }
        }
    }

    private fun bilinear(m: FloatArray, u: Float, v: Float): Float {
        val x0 = u.toInt().coerceAtMost(MASK_SIZE - 2); val y0 = v.toInt().coerceAtMost(MASK_SIZE - 2)
        val fx = u - x0; val fy = v - y0
        val i = y0 * MASK_SIZE + x0
        return (m[i] * (1 - fx) + m[i + 1] * fx) * (1 - fy) + (m[i + MASK_SIZE] * (1 - fx) + m[i + MASK_SIZE + 1] * fx) * fy
    }

    private fun sigmoid(x: Float) = 1f / (1f + exp(-x))
}
