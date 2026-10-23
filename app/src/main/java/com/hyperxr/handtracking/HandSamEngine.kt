package com.hyperxr.handtracking

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.diagnostics.NnapiNative
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel

/**
 * The real hands, cut out of the camera image by a promptable segmentation network instead of a
 * skin-colour guess: EfficientViT-SAM L0 (MIT Han Lab, Apache-2.0), converted here from its
 * PyTorch weights. The image encoder (512x512, fp16 weights) runs on the NPU when it takes it, else
 * the GPU, else the CPU; the light prompt decoder runs from ONNX on the CPU (ONNX Runtime), once
 * per hand, prompted with the hand's box and points on it from the hand landmarks. One encoder run
 * serves both hands (they share one crop).
 */
class HandSamEngine(private val context: Context) {
    @Volatile var device = ""; private set
    @Volatile var encoderMs = 0f; private set
    @Volatile var decoderMs = 0f; private set

    private var encoder: Interpreter? = null
    private var nnapi: NnApiDelegate? = null
    private var gpu: org.tensorflow.lite.gpu.GpuDelegate? = null
    private var env: OrtEnvironment? = null
    private var decoder: OrtSession? = null

    private val n = SamHands.ENCODER_SIZE
    private val input = ByteBuffer.allocateDirect(n * n * 3 * 4).order(ByteOrder.nativeOrder())
    private val embeddings = ByteBuffer.allocateDirect(256 * 64 * 64 * 4).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(n * n)
    private val crop: Bitmap = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(crop)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val points = FloatArray(SamHands.PROMPTS * 2)
    private val labels = FloatArray(SamHands.PROMPTS)

    fun start(): Boolean {
        if (encoder != null) return true
        val model = context.assets.openFd(ENCODER).use { fd ->
            fd.createInputStream().channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
        val accel = pickAccelerator()
        val tries = buildList { if (accel != null) add("NPU: $accel" to accel); add("GPU" to GPU); add("CPU" to null) }
        for ((label, name) in tries) {
            try {
                val opts = Interpreter.Options()
                when (name) {
                    GPU -> org.tensorflow.lite.gpu.GpuDelegate(org.tensorflow.lite.gpu.GpuDelegate.Options().setPrecisionLossAllowed(true)
                        .setInferencePreference(org.tensorflow.lite.gpu.GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)).also { gpu = it; opts.addDelegate(it) }
                    null -> { opts.setNumThreads(4); opts.setUseXNNPACK(true) }
                    else -> NnApiDelegate(NnApiDelegate.Options().setAcceleratorName(name).setAllowFp16(true).setUseNnapiCpu(false)
                        .setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED)
                        .setCacheDir(context.cacheDir.absolutePath).setModelToken("hxhandsam-l0-v1")).also { nnapi = it; opts.addDelegate(it) }
                }
                model.rewind()
                val i = Interpreter(model, opts)
                input.rewind(); embeddings.rewind(); i.run(input, embeddings)
                encoder = i; device = label
                break
            } catch (t: Throwable) {
                Diagnostics.log("Hand SAM encoder: $label failed (${t.message})")
                nnapi?.close(); nnapi = null; gpu?.close(); gpu = null
            }
        }
        if (encoder == null) return false
        return try {
            val e = OrtEnvironment.getEnvironment()
            val bytes = context.assets.open(DECODER).use { it.readBytes() }
            val so = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2); setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT) }
            env = e; decoder = e.createSession(bytes, so)
            Diagnostics.log("Hand SAM: encoder on $device, decoder on the CPU (ONNX Runtime)")
            true
        } catch (t: Throwable) { Diagnostics.log("Hand SAM decoder failed: ${t.message}"); close(); false }
    }

    /**
     * Segments [hands] (21 landmarks each, 0..1 in [frame]) in [frame]: one mask per hand, over the
     * hands' shared crop. Null when the networks are not running.
     */
    fun segment(frame: Bitmap, hands: List<FloatArray>): List<SamHands.HandMask>? {
        val enc = encoder ?: return null
        val dec = decoder ?: return null
        val e = env ?: return null
        if (hands.isEmpty()) return emptyList()
        val w = frame.width; val h = frame.height
        val c = SamHands.crop(hands, w, h)
        // the square crop (parts outside the frame stay black), scaled to 512x512
        crop.eraseColor(0xff000000.toInt())
        matrix.reset(); matrix.postTranslate(-c.x0, -c.y0); matrix.postScale(n / c.side, n / c.side)
        canvas.drawBitmap(frame, matrix, paint)
        crop.getPixels(pixels, 0, n, 0, 0, n, n)
        input.rewind()
        for (p in pixels) { input.putFloat(((p shr 16) and 0xff) / 255f); input.putFloat(((p shr 8) and 0xff) / 255f); input.putFloat((p and 0xff) / 255f) }
        input.rewind(); embeddings.rewind()
        val t0 = System.nanoTime()
        enc.run(input, embeddings)
        val t1 = System.nanoTime()
        encoderMs = (t1 - t0) / 1e6f
        embeddings.rewind()
        val embFloats = embeddings.asFloatBuffer()
        val out = ArrayList<SamHands.HandMask>(hands.size)
        OnnxTensor.createTensor(e, embFloats, longArrayOf(1, 256, 64, 64)).use { embT ->
            for (hand in hands) {
                SamHands.prompts(hand, c, w, h, points, labels)
                OnnxTensor.createTensor(e, FloatBuffer.wrap(points), longArrayOf(1, SamHands.PROMPTS.toLong(), 2)).use { pT ->
                    OnnxTensor.createTensor(e, FloatBuffer.wrap(labels), longArrayOf(1, SamHands.PROMPTS.toLong())).use { lT ->
                        dec.run(mapOf("embeddings" to embT, "points" to pT, "labels" to lT)).use { r ->
                            val logits = FloatArray(SamHands.MASK_SIZE * SamHands.MASK_SIZE)
                            (r.get(0) as OnnxTensor).floatBuffer.get(logits)
                            out += SamHands.HandMask(c, logits, SamHands.anchor(hand, w, h))
                        }
                    }
                }
            }
        }
        decoderMs = (System.nanoTime() - t1) / 1e6f
        return out
    }

    fun close() {
        encoder?.close(); encoder = null; nnapi?.close(); nnapi = null; gpu?.close(); gpu = null
        decoder?.close(); decoder = null
    }

    private fun pickAccelerator(): String? {
        val rows = runCatching { NnapiNative.devices() }.getOrDefault(emptyList()).map { it.split('|') }.filter { it.size >= 2 }
        val accels = rows.filter { it[1] == "accelerator" && !it[0].contains("reference", true) }.map { it[0] }
        val npuWords = listOf("npu", "eden", "neuron", "mdla", "apu", "hta", "htp", "edgetpu", "tpu", "nnhal", "dsp")
        return accels.sortedBy { nm -> npuWords.indexOfFirst { nm.contains(it, true) }.let { if (it < 0) 99 else it } }.firstOrNull()
    }

    companion object {
        const val ENCODER = "models/hand_sam_encoder.tflite"
        const val DECODER = "models/hand_sam_decoder.onnx"
        private const val GPU = "\u0000gpu"
    }
}
