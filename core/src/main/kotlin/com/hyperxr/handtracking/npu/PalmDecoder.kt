package com.hyperxr.handtracking.npu

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * HyperXR's own hand pipeline (the NPU engine), model-independent parts: decoding the palm
 * detector (192x192 SSD, 2016 anchors, box + 7 palm keypoints per anchor), weighted
 * non-maximum suppression, and the rotated hand regions the landmark model looks at.
 * Coordinates are image pixels unless noted.
 */
class PalmDetection(val score: Float, val cx: Float, val cy: Float, val w: Float, val h: Float, val keypoints: FloatArray /* 7 x (x, y) */)

/** A rotated square region of the camera image: what the 224x224 landmark model sees. */
data class HandRoi(val cx: Float, val cy: Float, val size: Float, val rotation: Float) {
    /** Maps a point of the model's 224x224 input (u, v) to image pixels. */
    fun toImage(u: Float, v: Float, out: FloatArray, offset: Int = 0) {
        val k = size / INPUT; val c = cos(rotation); val s = sin(rotation)
        val du = u - INPUT / 2; val dv = v - INPUT / 2
        out[offset] = cx + k * (c * du - s * dv)
        out[offset + 1] = cy + k * (s * du + c * dv)
    }

    /**
     * The same mapping as [toImage] as 6 affine coefficients (x = a0 u + a1 v + a2,
     * y = a3 u + a4 v + a5), for the native crop that fills the model input.
     */
    fun affine(out: FloatArray) {
        val k = size / INPUT; val c = cos(rotation); val s = sin(rotation); val h = INPUT / 2
        out[0] = k * c; out[1] = -k * s; out[2] = cx - h * k * c + h * k * s
        out[3] = k * s; out[4] = k * c; out[5] = cy - h * k * s - h * k * c
    }

    companion object {
        const val INPUT = 224f

        /** Rotation that makes the wrist→finger direction point up in the model input. */
        fun rotation(x0: Float, y0: Float, x1: Float, y1: Float): Float = normalize((PI / 2 - atan2(-(y1 - y0), x1 - x0)).toFloat())

        private fun normalize(a: Float): Float {
            var r = a
            while (r > PI) r -= (2 * PI).toFloat()
            while (r < -PI) r += (2 * PI).toFloat()
            return r
        }

        /** From a palm: rotated by wrist→middle knuckle, shifted toward the fingers, 2.6x the palm box. */
        fun fromPalm(d: PalmDetection): HandRoi {
            val rot = rotation(d.keypoints[0], d.keypoints[1], d.keypoints[4], d.keypoints[5])
            val side = max(d.w, d.h)
            val sy = -0.5f * side
            return HandRoi(d.cx - sy * sin(rot), d.cy + sy * cos(rot), side * 2.6f, rot)
        }

        private val ROI_JOINTS = intArrayOf(0, 1, 2, 3, 5, 6, 9, 10, 13, 14, 17, 18)

        /**
         * From the previous frame's landmarks (pixels, x/y per joint at [stride]): the next frame
         * needs no palm detection while the hand stays in view.
         */
        fun fromLandmarks(p: FloatArray, stride: Int = 3): HandRoi {
            val rot = rotation(p[0], p[1], p[9 * stride], p[9 * stride + 1])
            val c = cos(-rot); val s = sin(-rot)
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (j in ROI_JOINTS) {
                val x = p[j * stride]; val y = p[j * stride + 1]
                val rx = x * c - y * s; val ry = x * s + y * c
                minX = min(minX, rx); maxX = max(maxX, rx); minY = min(minY, ry); maxY = max(maxY, ry)
            }
            val mx = (minX + maxX) / 2; val my = (minY + maxY) / 2
            val w = maxX - minX; val h = maxY - minY
            val c2 = cos(rot); val s2 = sin(rot)
            val sy = -0.1f * h
            return HandRoi(mx * c2 - my * s2 - sy * s2, mx * s2 + my * c2 + sy * c2, max(w, h) * 2.0f, rot)
        }
    }
}

object PalmDecoder {
    const val INPUT = 192
    const val ANCHORS = 2016

    /** SSD anchor centres (x, y in 0..1): stride 8 -> 24x24x2, strides 16 merged -> 12x12x6. */
    val anchors: FloatArray = FloatArray(ANCHORS * 2).also { a ->
        var i = 0
        for ((fm, perCell) in listOf(24 to 2, 12 to 6)) {
            for (y in 0 until fm) for (x in 0 until fm) repeat(perCell) {
                a[i++] = (x + 0.5f) / fm; a[i++] = (y + 0.5f) / fm
            }
        }
    }

    /**
     * Decodes the detector output ([regressors] 2016x18, [logits] 2016) for an image of
     * [width]x[height] letterboxed into the square input. Returns palms after weighted NMS.
     */
    fun decode(regressors: FloatArray, logits: FloatArray, width: Int, height: Int, minScore: Float = 0.5f): List<PalmDetection> {
        val side = max(width, height).toFloat()
        val ox = (side - width) / 2; val oy = (side - height) / 2
        val raw = ArrayList<PalmDetection>()
        val minLogit = ln(minScore / (1 - minScore))
        for (i in 0 until ANCHORS) {
            val l = logits[i].coerceIn(-100f, 100f)
            if (l < minLogit) continue
            val score = 1f / (1f + exp(-l))
            val r = i * 18
            val ax = anchors[i * 2]; val ay = anchors[i * 2 + 1]
            val kp = FloatArray(14)
            for (k in 0 until 7) {
                kp[k * 2] = (regressors[r + 4 + k * 2] / INPUT + ax) * side - ox
                kp[k * 2 + 1] = (regressors[r + 5 + k * 2] / INPUT + ay) * side - oy
            }
            raw += PalmDetection(score, (regressors[r] / INPUT + ax) * side - ox, (regressors[r + 1] / INPUT + ay) * side - oy,
                regressors[r + 2] / INPUT * side, regressors[r + 3] / INPUT * side, kp)
        }
        return weightedNms(raw, 0.3f)
    }

    private fun ln(x: Float) = kotlin.math.ln(x.toDouble()).toFloat()

    private fun iou(a: PalmDetection, b: PalmDetection): Float {
        val iw = max(0f, min(a.cx + a.w / 2, b.cx + b.w / 2) - max(a.cx - a.w / 2, b.cx - b.w / 2))
        val ih = max(0f, min(a.cy + a.h / 2, b.cy + b.h / 2) - max(a.cy - a.h / 2, b.cy - b.h / 2))
        val inter = iw * ih
        return inter / (a.w * a.h + b.w * b.h - inter + 1e-9f)
    }

    /** Overlapping detections of one palm are averaged by score (steadier than keeping the best). */
    fun weightedNms(list: List<PalmDetection>, threshold: Float): List<PalmDetection> {
        val rest = list.sortedByDescending { it.score }.toMutableList()
        val out = ArrayList<PalmDetection>()
        while (rest.isNotEmpty()) {
            val top = rest[0]
            val group = rest.filter { iou(top, it) > threshold }
            rest.removeAll(group.toSet())
            val ws = group.sumOf { it.score.toDouble() }.toFloat()
            fun avg(f: (PalmDetection) -> Float) = group.sumOf { (it.score * f(it)).toDouble() }.toFloat() / ws
            val kp = FloatArray(14) { k -> avg { it.keypoints[k] } }
            out += PalmDetection(top.score, avg { it.cx }, avg { it.cy }, avg { it.w }, avg { it.h }, kp)
        }
        return out
    }
}
