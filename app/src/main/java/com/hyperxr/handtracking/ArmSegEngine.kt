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
 * Sees your arms: MediaPipe's multiclass segmenter (Apache-2.0, in the APK) on the NPU through
 * NNAPI, the GPU path of NNAPI or the CPU as fallbacks. One camera picture in (256x256), the
 * chance of "arm" (bare skin or sleeve) for every pixel out.
 */
class ArmSegEngine(private val context: Context) {
    @Volatile var status = "não carregado"; private set
    @Volatile var lastMs = 0f; private set
    @Volatile var ready = false; private set
    @Volatile var failed = false; private set

    private var interpreter: Interpreter? = null
    private var delegate: NnApiDelegate? = null
    private val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(SIZE * SIZE * CLASSES * 4).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(SIZE * SIZE)
    private val raw = FloatArray(SIZE * SIZE * CLASSES)
    /** Arm probability, row-major SIZE x SIZE (the latest run). */
    val arm = FloatArray(SIZE * SIZE)

    /** Loads the model. Blocking: call on a worker thread. */
    fun prepare(): Boolean {
        if (ready) return true
        if (failed) return false
        val model = try {
            context.assets.openFd(ASSET).use { fd -> fd.createInputStream().channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
        } catch (t: Throwable) { status = "sem modelo"; failed = true; return false }
        val accel = pickAccelerator()
        val tries = buildList {
            if (accel != null) add("NPU: $accel" to accel)
            add("NNAPI" to "")
            add("CPU" to null)
        }
        for ((label, name) in tries) {
            val step = "armseg:$label"
            if (com.hyperxr.diagnostics.NativeCrashGuard.blocked(step)) continue
            try {
                val opts = Interpreter.Options()
                if (name != null) {
                    val nn = NnApiDelegate.Options()
                        .setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED)
                        .setAllowFp16(true).setUseNnapiCpu(false)
                        .setCacheDir(context.cacheDir.absolutePath).setModelToken("hxarmseg-v1")
                    if (name.isNotEmpty()) nn.setAcceleratorName(name)
                    delegate = NnApiDelegate(nn); opts.addDelegate(delegate)
                    opts.setNumThreads(1)
                } else { opts.setNumThreads(2); opts.setUseXNNPACK(true) }
                model.rewind()
                val i = com.hyperxr.diagnostics.NativeCrashGuard.run(step) { Interpreter(model, opts).also { input.rewind(); output.rewind(); it.run(input, output) } }
                interpreter = i
                status = "pronto ($label)"
                Diagnostics.log("Arm tracking on $label")
                ready = true
                return true
            } catch (t: Throwable) {
                Diagnostics.log("Arm tracking: $label failed (${t.message})")
                delegate?.close(); delegate = null
            }
        }
        failed = true
        status = "nenhum processador aceitou o modelo"
        return false
    }

    /** Runs on [bmp] (already SIZE x SIZE); the result is in [arm]. Worker thread. */
    fun run(bmp: Bitmap): Boolean {
        val it = interpreter ?: return false
        val t0 = System.nanoTime()
        bmp.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        input.rewind()
        for (p in pixels) {
            input.putFloat((((p shr 16) and 0xff) - 127.5f) / 127.5f)
            input.putFloat((((p shr 8) and 0xff) - 127.5f) / 127.5f)
            input.putFloat(((p and 0xff) - 127.5f) / 127.5f)
        }
        input.rewind(); output.rewind()
        it.run(input, output)
        output.rewind()
        output.asFloatBuffer().get(raw)
        // the output may be scores or probabilities: a softmax over the classes gives the same answer for both
        var k = 0
        for (i in 0 until SIZE * SIZE) {
            var m = raw[k]
            for (c in 1 until CLASSES) if (raw[k + c] > m) m = raw[k + c]
            var sum = 0f
            for (c in 0 until CLASSES) sum += exp(raw[k + c] - m)
            arm[i] = (exp(raw[k + SKIN] - m) + exp(raw[k + CLOTHES] - m)) / sum
            k += CLASSES
        }
        lastMs = (System.nanoTime() - t0) / 1e6f
        return true
    }

    fun close() { interpreter?.close(); interpreter = null; delegate?.close(); delegate = null; ready = false }

    private fun pickAccelerator(): String? {
        val rows = runCatching { NnapiNative.devices() }.getOrDefault(emptyList()).map { it.split('|') }.filter { it.size >= 2 }
        val accels = rows.filter { it[1] == "accelerator" && !it[0].contains("reference", true) }.map { it[0] }
        val npuWords = listOf("npu", "eden", "neuron", "mdla", "apu", "hta", "htp", "edgetpu", "tpu", "nnhal", "dsp")
        return accels.sortedBy { n -> npuWords.indexOfFirst { n.contains(it, true) }.let { if (it < 0) 99 else it } }.firstOrNull()
    }

    companion object {
        const val SIZE = 256
        private const val CLASSES = 6
        private const val SKIN = 2
        private const val CLOTHES = 4
        const val ASSET = "models/arm_segmenter.tflite"
    }
}
