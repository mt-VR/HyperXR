package com.hyperxr.handtracking

import android.content.Context
import android.graphics.Bitmap
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.diagnostics.NnapiNative
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * Where the user's real hands are in the camera picture, pixel by pixel, on the NPU: MediaPipe's
 * multiclass segmenter (256x256, Apache-2.0) gives the body-skin class, which is the hands and
 * arms. The renderer then draws those camera pixels over the virtual UI, so real hands are in
 * front of panels instead of hidden behind them.
 */
class HandMaskEngine(private val context: Context) {
    @Volatile var device = ""; private set
    @Volatile var lastMs = 0f; private set
    private var interpreter: Interpreter? = null
    private var delegate: NnApiDelegate? = null
    private val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(SIZE * SIZE * CLASSES * 4).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(SIZE * SIZE)
    private val logits = FloatArray(SIZE * SIZE * CLASSES)

    fun start(): Boolean {
        if (interpreter != null) return true
        val model = context.assets.openFd(ASSET).use { fd ->
            fd.createInputStream().channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
        val accel = pickAccelerator()
        val tries = buildList { if (accel != null) add("NPU: $accel" to accel); add("NNAPI" to ""); add("CPU" to null) }
        for ((label, name) in tries) {
            try {
                val opts = Interpreter.Options()
                if (name != null) {
                    val nn = NnApiDelegate.Options().setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED)
                        .setAllowFp16(true).setUseNnapiCpu(false).setCacheDir(context.cacheDir.absolutePath).setModelToken("hxhandmask-v1")
                    if (name.isNotEmpty()) nn.setAcceleratorName(name)
                    delegate = NnApiDelegate(nn); opts.addDelegate(delegate); opts.setNumThreads(2)
                } else { opts.setNumThreads(2); opts.setUseXNNPACK(true) }
                model.rewind()
                val i = Interpreter(model, opts)
                input.rewind(); output.rewind(); i.run(input, output)
                interpreter = i; device = label
                Diagnostics.log("Hand occlusion mask on $label")
                return true
            } catch (t: Throwable) {
                Diagnostics.log("Hand occlusion: $label failed (${t.message})")
                delegate?.close(); delegate = null
            }
        }
        return false
    }

    /** Body-skin probability (0..255) for [bmp] (SIZE x SIZE) into [out] (SIZE x SIZE). */
    fun run(bmp: Bitmap, out: ByteArray): Boolean {
        val i = interpreter ?: return false
        val t0 = System.nanoTime()
        bmp.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        input.rewind()
        for (p in pixels) { input.putFloat(((p shr 16) and 0xff) / 255f); input.putFloat(((p shr 8) and 0xff) / 255f); input.putFloat((p and 0xff) / 255f) }
        input.rewind(); output.rewind()
        i.run(input, output)
        output.rewind(); output.asFloatBuffer().get(logits)
        // softmax over the classes, keep body skin
        for (k in 0 until SIZE * SIZE) {
            val b = k * CLASSES
            var m = logits[b]; for (c in 1 until CLASSES) m = maxOf(m, logits[b + c])
            var sum = 0f; for (c in 0 until CLASSES) sum += exp(logits[b + c] - m)
            out[k] = (exp(logits[b + SKIN] - m) / sum * 255f).toInt().coerceIn(0, 255).toByte()
        }
        lastMs = (System.nanoTime() - t0) / 1e6f
        return true
    }

    fun close() { interpreter?.close(); interpreter = null; delegate?.close(); delegate = null }

    private fun pickAccelerator(): String? {
        val rows = runCatching { NnapiNative.devices() }.getOrDefault(emptyList()).map { it.split('|') }.filter { it.size >= 2 }
        val accels = rows.filter { it[1] == "accelerator" && !it[0].contains("reference", true) }.map { it[0] }
        val npuWords = listOf("npu", "eden", "neuron", "mdla", "apu", "hta", "htp", "edgetpu", "tpu", "nnhal", "dsp")
        return accels.sortedBy { n -> npuWords.indexOfFirst { n.contains(it, true) }.let { if (it < 0) 99 else it } }.firstOrNull()
    }

    companion object {
        const val SIZE = 256
        const val CLASSES = 6
        /** background, hair, body skin, face skin, clothes, others */
        const val SKIN = 2
        const val ASSET = "models/selfie_multiclass_256x256.tflite"
    }
}
