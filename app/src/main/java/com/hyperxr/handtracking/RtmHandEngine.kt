package com.hyperxr.handtracking

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.diagnostics.NnapiNative
import com.hyperxr.settings.InferenceBackend
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel

/**
 * HyperXR's hand tracker on OpenMMLab's models (Apache-2.0, official ONNX exports):
 * RTMDet-nano finds hands (ONNX Runtime, CPU) and RTMPose-m places the 21 joints (converted to
 * TFLite with fp16 weights: NPU first, then GPU, then CPU). Like a headset's tracker, the
 * detector only runs while a hand is missing; a tracked hand costs one pose inference per frame,
 * cropped from where its joints were. Depth and handedness come from the 2D joints (RtmHand).
 */
class RtmHandEngine(private val context: Context) : HandEngine {
    override var device = "-"; private set
    override var detectorMs = 0f; private set
    override var landmarkMs = 0f; private set

    private var pose: Interpreter? = null
    private var nnapi: NnApiDelegate? = null
    private var gpu: org.tensorflow.lite.gpu.GpuDelegate? = null
    private var env: OrtEnvironment? = null
    private var detector: OrtSession? = null

    private val n = RtmHand.INPUT
    private val poseIn = ByteBuffer.allocateDirect(n * n * 3 * 4).order(ByteOrder.nativeOrder())
    private val simX = Array(1) { Array(HandJoint.COUNT) { FloatArray(RtmHand.BINS) } }
    private val simY = Array(1) { Array(HandJoint.COUNT) { FloatArray(RtmHand.BINS) } }
    private val flatX = FloatArray(HandJoint.COUNT * RtmHand.BINS)
    private val flatY = FloatArray(HandJoint.COUNT * RtmHand.BINS)
    private val crop = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    private val det = Bitmap.createBitmap(DET, DET, Bitmap.Config.ARGB_8888)
    private val detIn = FloatArray(3 * DET * DET)
    private val pixels = IntArray(DET * DET)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val joints = FloatArray(HandJoint.COUNT * 2)
    private val conf = FloatArray(HandJoint.COUNT)

    /** Hands followed from the previous frame: their crop for this frame. */
    private val tracks = ArrayList<RtmHand.Crop>(2)
    private var frame = 0L

    fun start(prefer: InferenceBackend = InferenceBackend.NNAPI): Boolean {
        if (pose != null) return true
        val model = context.assets.openFd(POSE).use { fd -> fd.createInputStream().channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
        val accel = pickAccelerator()
        val tries = buildList {
            when (prefer) {
                InferenceBackend.CPU -> add("CPU" to null)
                InferenceBackend.GPU -> { add("GPU" to GPU); if (accel != null) add("NPU: $accel" to accel); add("CPU" to null) }
                else -> { if (accel != null) add("NPU: $accel" to accel); add("GPU" to GPU); add("CPU" to null) }
            }
        }
        for ((label, name) in tries) {
            try {
                val opts = Interpreter.Options()
                when (name) {
                    GPU -> org.tensorflow.lite.gpu.GpuDelegate(org.tensorflow.lite.gpu.GpuDelegate.Options().setPrecisionLossAllowed(true)
                        .setInferencePreference(org.tensorflow.lite.gpu.GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)).also { gpu = it; opts.addDelegate(it) }
                    null -> { opts.setNumThreads(4); opts.setUseXNNPACK(true) }
                    else -> NnApiDelegate(NnApiDelegate.Options().setAcceleratorName(name).setAllowFp16(true).setUseNnapiCpu(false)
                        .setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED)
                        .setCacheDir(context.cacheDir.absolutePath).setModelToken("hxrtmpose-hand-v1")).also { nnapi = it; opts.addDelegate(it) }
                }
                model.rewind()
                val i = Interpreter(model, opts)
                poseIn.rewind(); i.runForMultipleInputsOutputs(arrayOf(poseIn), mapOf(0 to simX, 1 to simY))
                pose = i; device = label
                break
            } catch (t: Throwable) {
                Diagnostics.log("RTMPose: $label failed (${t.message})")
                nnapi?.close(); nnapi = null; gpu?.close(); gpu = null
            }
        }
        if (pose == null) return false
        return try {
            val e = OrtEnvironment.getEnvironment()
            val bytes = context.assets.open(DETECTOR).use { it.readBytes() }
            detector = e.createSession(bytes, OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) })
            env = e
            Diagnostics.log("Hand tracking: RTMPose-m on $device, RTMDet-nano on the CPU")
            true
        } catch (t: Throwable) { Diagnostics.log("RTMDet failed: ${t.message}"); close(); false }
    }

    override fun process(bmp: Bitmap): List<RawHand> {
        val p = pose ?: return emptyList()
        frame++
        val w = bmp.width; val h = bmp.height
        val crops = ArrayList(tracks)
        val fromTrack = BooleanArray(2) { it < crops.size }
        // look for a missing hand: every frame with none, every 3rd frame with one
        if (crops.size < 2 && (crops.isEmpty() || frame % 3 == 0L)) {
            val t0 = System.nanoTime()
            for (b in detect(bmp)) {
                if (crops.size >= 2) break
                val c = RtmHand.cropFromBox(b[0], b[1], b[2], b[3])
                if (crops.none { o -> kotlin.math.hypot(o.cx - c.cx, o.cy - c.cy) < o.side * 0.35f }) crops += c
            }
            detectorMs = (System.nanoTime() - t0) / 1e6f
        }
        val t1 = System.nanoTime()
        val out = ArrayList<RawHand>(2)
        tracks.clear()
        for ((k, c0) in crops.withIndex()) {
            var c = c0
            var presence = runPose(p, bmp, c)
            // a tracked hand that moved fast may have left its crop: one wider look before giving up
            if (presence < 0.3f && k < fromTrack.size && fromTrack[k]) {
                val wide = c.copy(side = c.side * 1.5f)
                val p2 = runPose(p, bmp, wide)
                if (p2 > presence) { presence = p2; c = wide } else runPose(p, bmp, c)
            }
            // new hands must look clearly like one; tracked ones are kept through blur
            if (presence < (if (k < fromTrack.size && fromTrack[k]) 0.25f else 0.5f)) continue
            if (out.any { o -> kotlin.math.hypot(o.image[0] * w - joints[0], o.image[1] * h - joints[1]) < c.side * 0.15f }) continue
            val image = FloatArray(HandJoint.COUNT * 3); val world = FloatArray(HandJoint.COUNT * 3)
            RtmHand.toPipeline(joints, w, h, image, world)
            val right = RtmHand.rightScore(joints)
            out += RawHand(if (right >= 0.5f) "Right" else "Left", if (right >= 0.5f) right else 1 - right, presence, image, world, right, worldEstimated = true)
            tracks += RtmHand.cropFromJoints(joints)
        }
        landmarkMs = (System.nanoTime() - t1) / 1e6f
        return out
    }

    /** Runs RTMPose on [c]; joints and confidences land in [joints] / [conf]. Returns the presence. */
    private fun runPose(p: Interpreter, bmp: Bitmap, c: RtmHand.Crop): Float {
        matrix.reset()
        matrix.postTranslate(-(c.cx - c.side / 2), -(c.cy - c.side / 2))
        matrix.postScale(n / c.side, n / c.side)
        val cv = Canvas(crop); cv.drawColor(Color.BLACK); cv.drawBitmap(bmp, matrix, paint)
        crop.getPixels(pixels, 0, n, 0, 0, n, n)
        poseIn.rewind()
        val f = poseIn.asFloatBuffer()
        for (i in 0 until n * n) {
            val px = pixels[i]
            f.put((((px shr 16) and 0xff) - 123.675f) / 58.395f); f.put((((px shr 8) and 0xff) - 116.28f) / 57.12f); f.put(((px and 0xff) - 103.53f) / 57.375f)
        }
        poseIn.rewind()
        p.runForMultipleInputsOutputs(arrayOf(poseIn), mapOf(0 to simX, 1 to simY))
        for (j in 0 until HandJoint.COUNT) {
            System.arraycopy(simX[0][j], 0, flatX, j * RtmHand.BINS, RtmHand.BINS)
            System.arraycopy(simY[0][j], 0, flatY, j * RtmHand.BINS, RtmHand.BINS)
        }
        RtmHand.decode(flatX, flatY, c, joints, conf)
        return RtmHand.presence(conf)
    }

    /** RTMDet-nano: hand boxes (x0, y0, x1, y1 in frame pixels), best first. */
    private fun detect(bmp: Bitmap): List<FloatArray> {
        val d = detector ?: return emptyList()
        val e = env ?: return emptyList()
        val s = DET.toFloat() / maxOf(bmp.width, bmp.height)
        matrix.reset(); matrix.postScale(s, s)
        val cv = Canvas(det); cv.drawColor(Color.rgb(114, 114, 114)); cv.drawBitmap(bmp, matrix, paint)
        det.getPixels(pixels, 0, DET, 0, 0, DET, DET)
        val plane = DET * DET
        for (i in 0 until plane) {
            val px = pixels[i]
            // BGR planes, as the detector was trained
            detIn[i] = ((px and 0xff) - 103.53f) / 57.375f
            detIn[plane + i] = (((px shr 8) and 0xff) - 116.28f) / 57.12f
            detIn[2 * plane + i] = (((px shr 16) and 0xff) - 123.675f) / 58.395f
        }
        OnnxTensor.createTensor(e, FloatBuffer.wrap(detIn), longArrayOf(1, 3, DET.toLong(), DET.toLong())).use { t ->
            d.run(mapOf("input" to t)).use { r ->
                @Suppress("UNCHECKED_CAST")
                val dets = (r.get(0).value as Array<Array<FloatArray>>)[0]
                return dets.filter { it[4] >= MIN_DETECTION }.sortedByDescending { it[4] }.take(2).map { floatArrayOf(it[0] / s, it[1] / s, it[2] / s, it[3] / s, it[4]) }
            }
        }
    }

    override fun close() {
        pose?.close(); pose = null; nnapi?.close(); nnapi = null; gpu?.close(); gpu = null
        detector?.close(); detector = null
    }

    private fun pickAccelerator(): String? {
        val rows = runCatching { NnapiNative.devices() }.getOrDefault(emptyList()).map { it.split('|') }.filter { it.size >= 2 }
        val accels = rows.filter { it[1] == "accelerator" && !it[0].contains("reference", true) }.map { it[0] }
        val npuWords = listOf("npu", "eden", "neuron", "mdla", "apu", "hta", "htp", "edgetpu", "tpu", "nnhal", "dsp")
        return accels.sortedBy { nm -> npuWords.indexOfFirst { nm.contains(it, true) }.let { if (it < 0) 99 else it } }.firstOrNull()
    }

    companion object {
        const val POSE = "models/rtm_hand_pose.tflite"
        const val DETECTOR = "models/rtm_hand_detector.onnx"
        private const val DET = 320
        /** Detector score for a new hand (a real hand scored 0.57 on a test photo, clutter under 0.1). */
        private const val MIN_DETECTION = 0.3f
        private const val GPU = "\u0000gpu"
    }
}

/** What HandTrackingService needs from an engine that runs on its own thread. */
interface HandEngine {
    val device: String
    val detectorMs: Float
    val landmarkMs: Float
    fun process(bmp: Bitmap): List<RawHand>
    fun close()
}
