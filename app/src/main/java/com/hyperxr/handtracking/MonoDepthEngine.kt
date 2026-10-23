package com.hyperxr.handtracking

import android.content.Context
import android.graphics.Bitmap
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.diagnostics.NnapiNative
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.ZipInputStream

/**
 * AI depth: Depth Anything V2 (Small, MIT-licensed mobile export by Qualcomm AI Hub) on the
 * phone's NPU through NNAPI, CPU as the last resort. One camera picture in (518x518), a dense
 * relative depth map out: sharp at the edges of hands and fingers, per frame, where ARCore's
 * depth is coarse, smoothed over time and only on some phones.
 *
 * The model (~99 MB) is not inside the APK: it is downloaded once, the first time it is needed,
 * into the app's private storage.
 */
class MonoDepthEngine(private val context: Context) {
    @Volatile var status = "não carregado"; private set
    @Volatile var progress = 0f; private set
    @Volatile var lastMs = 0f; private set
    @Volatile var device = ""; private set
    @Volatile var ready = false; private set

    private var interpreter: Interpreter? = null
    private var delegate: NnApiDelegate? = null
    private val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(SIZE * SIZE)
    /** The latest result, row-major SIZE x SIZE, larger = nearer. */
    val disparity = FloatArray(SIZE * SIZE)

    private val modelFile get() = File(File(context.filesDir, "models"), MODEL_NAME)

    /** Downloads (once) and loads the model. Blocking: call on a worker thread. */
    fun prepare(): Boolean {
        if (ready) return true
        try {
            if (!modelFile.isFile || modelFile.length() < 50_000_000L) download()
            load()
            ready = true
            return true
        } catch (t: Throwable) {
            status = "falhou: ${t.message}"
            Diagnostics.log("AI depth: ${t.message}")
            return false
        }
    }

    private fun download() {
        status = "baixando o modelo (uma vez, ~92 MB)"
        val dir = modelFile.parentFile!!.apply { mkdirs() }
        val tmp = File(dir, "$MODEL_NAME.part")
        val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply { connectTimeout = 20000; readTimeout = 60000 }
        require(conn.responseCode == 200) { "download HTTP ${conn.responseCode}" }
        val total = conn.contentLengthLong.coerceAtLeast(1L)
        var read = 0L
        ZipInputStream(CountingStream(conn.inputStream) { n -> read += n; progress = (read.toFloat() / total).coerceAtMost(1f) }.buffered(1 shl 16)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.name.endsWith(".tflite")) continue
                tmp.outputStream().buffered(1 shl 20).use { zip.copyTo(it, 1 shl 16) }
                break
            }
        }
        require(tmp.length() > 50_000_000L) { "modelo incompleto" }
        tmp.renameTo(modelFile)
        Diagnostics.log("AI depth: model downloaded (${modelFile.length() / 1_000_000} MB)")
    }

    private fun load() {
        status = "carregando na NPU"
        val buf = RandomAccessFile(modelFile, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
        val accel = pickAccelerator()
        val tries = buildList {
            if (accel != null) add("NPU: $accel" to accel)
            add("NNAPI" to "")
            add("CPU" to null)
        }
        for ((label, name) in tries) {
            try {
                val opts = Interpreter.Options()
                if (name != null) {
                    val nn = NnApiDelegate.Options()
                        .setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED)
                        .setAllowFp16(true).setUseNnapiCpu(false)
                        .setCacheDir(context.cacheDir.absolutePath).setModelToken("hxdepth-da2-v1")
                    if (name.isNotEmpty()) nn.setAcceleratorName(name)
                    delegate = NnApiDelegate(nn); opts.addDelegate(delegate)
                    opts.setNumThreads(2)
                } else { opts.setNumThreads(4); opts.setUseXNNPACK(true) }
                buf.rewind()
                val i = Interpreter(buf, opts)
                input.rewind(); output.rewind(); i.run(input, output)  // a delegate can fail on the first run
                interpreter = i; device = label
                status = "pronto ($label)"
                Diagnostics.log("AI depth on $label")
                return
            } catch (t: Throwable) {
                Diagnostics.log("AI depth: $label failed (${t.message})")
                delegate?.close(); delegate = null
            }
        }
        error("nenhum processador aceitou o modelo")
    }

    /** Runs on [bmp] (already SIZE x SIZE); the result is in [disparity]. Worker thread. */
    fun run(bmp: Bitmap): Boolean {
        val i = interpreter ?: return false
        val t0 = System.nanoTime()
        bmp.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        input.rewind()
        for (p in pixels) {
            input.putFloat(((p shr 16) and 0xff) / 255f); input.putFloat(((p shr 8) and 0xff) / 255f); input.putFloat((p and 0xff) / 255f)
        }
        input.rewind(); output.rewind()
        i.run(input, output)
        output.rewind()
        output.asFloatBuffer().get(disparity)
        lastMs = (System.nanoTime() - t0) / 1e6f
        status = "%s · %.0f ms".format(device, lastMs)
        return true
    }

    fun close() { interpreter?.close(); interpreter = null; delegate?.close(); delegate = null; ready = false }

    private fun pickAccelerator(): String? {
        val rows = runCatching { NnapiNative.devices() }.getOrDefault(emptyList()).map { it.split('|') }.filter { it.size >= 2 }
        val accels = rows.filter { it[1] == "accelerator" && !it[0].contains("reference", true) }.map { it[0] }
        val npuWords = listOf("npu", "eden", "neuron", "mdla", "apu", "hta", "htp", "edgetpu", "tpu", "nnhal", "dsp")
        return accels.sortedBy { n -> npuWords.indexOfFirst { n.contains(it, true) }.let { if (it < 0) 99 else it } }.firstOrNull()
    }

    private class CountingStream(private val s: java.io.InputStream, private val onRead: (Int) -> Unit) : java.io.InputStream() {
        override fun read(): Int = s.read().also { if (it >= 0) onRead(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = s.read(b, off, len).also { if (it > 0) onRead(it) }
        override fun close() = s.close()
    }

    companion object {
        const val SIZE = 518
        const val MODEL_NAME = "depth_anything_v2.tflite"
        /** Qualcomm AI Hub release of Depth Anything V2 (Small), MIT licence. */
        const val MODEL_URL = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/depth_anything_v2/releases/v0.65.0/depth_anything_v2-tflite-float.zip"
    }
}
