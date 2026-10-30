package com.hyperxr.rendering

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.diagnostics.NnapiNative
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live passthrough enhancement with the NPU.
 *
 * Several times a second the GPU shrinks the camera image to 128 x 72 and reads it back; on its
 * own thread the NPU restores it with Real-ESRGAN (the compact general x4v3 network, int8: noise,
 * blur and haze removed) and [ToneGrid] turns the restored image into a 16 x 9 grid of local colour
 * transforms with local tone mapping (dark rooms and shadows lifted, highlights kept). The camera
 * shader applies the grid to every full-resolution frame, and cleans the noise and sharpens the
 * edges at the pixels of the screen. Without an NPU or GPU that takes the model, the grid comes
 * from the camera image itself and the rest is the same.
 */
class PassthroughEnhancer(private val context: Context) {
    val grid = ToneGrid()
    @Volatile var device = "-"; private set
    @Volatile var lastMs = 0f; private set
    var sharpness = 0.6f

    private var prog = 0
    private var vao = 0
    private var fbo = 0
    private var smallTex = 0
    private var gainTex = 0
    private var offsetTex = 0
    private val read = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder())
    private val src = FloatArray(W * H * 3)
    private val restored = FloatArray(W * H * 3)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "hx-enhance").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val busy = AtomicBoolean(false)
    @Volatile private var fresh = false
    private val pendingA = FloatArray(grid.gw * grid.gh * 3)
    private val pendingB = FloatArray(grid.gw * grid.gh * 3)
    private val upload = ByteBuffer.allocateDirect(grid.gw * grid.gh * 4).order(ByteOrder.nativeOrder())
    private var frame = 0L

    private var interpreter: Interpreter? = null
    private var nnapi: NnApiDelegate? = null
    private var gpu: org.tensorflow.lite.gpu.GpuDelegate? = null
    private val modelIn = ByteBuffer.allocateDirect(N * N * 3).order(ByteOrder.nativeOrder())
    private val modelOut = ByteBuffer.allocateDirect(N * 4 * N * 4 * 3).order(ByteOrder.nativeOrder())

    /** GL thread: the shrink pass and the grid textures; the model loads on the worker. */
    fun init() {
        prog = Gl.compile(SHRINK_VS, SHRINK_FS, "enhance-shrink")
        vao = IntArray(1).also { GLES30.glGenVertexArrays(1, it, 0) }[0]
        GLES30.glBindVertexArray(vao); FullscreenTriangle.bind(); GLES30.glBindVertexArray(0)
        val ids = IntArray(3)
        GLES30.glGenTextures(3, ids, 0)
        smallTex = ids[0]; gainTex = ids[1]; offsetTex = ids[2]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, smallTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, W, H, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        linear()
        for (t in intArrayOf(gainTex, offsetTex)) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, grid.gw, grid.gh, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            linear()
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0); fbo = f[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, smallTex, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        // the identity until the first grid arrives
        grid.a.copyInto(pendingA); grid.b.copyInto(pendingB); fresh = true
        worker.execute { loadModel() }
    }

    private fun linear() {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    /** NPU by name first (int8 is what NPUs run best), then NNAPI's choice, then the GPU. */
    private fun loadModel() {
        val model = try {
            context.assets.openFd(ASSET).use { fd -> fd.createInputStream().channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
        } catch (t: Throwable) { device = "GPU (sem modelo)"; return }
        val accel = npuName()
        val tries = buildList {
            if (accel != null) add("NPU: $accel" to accel)
            add("NNAPI" to "")
            add("GPU" to GPU)
        }
        for ((label, name) in tries) {
            val step = "enhance:$label"
            if (com.hyperxr.diagnostics.NativeCrashGuard.blocked(step)) continue
            try {
                val opts = Interpreter.Options()
                when (name) {
                    GPU -> org.tensorflow.lite.gpu.GpuDelegate(org.tensorflow.lite.gpu.GpuDelegate.Options().setPrecisionLossAllowed(true)
                        .setInferencePreference(org.tensorflow.lite.gpu.GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)).also { gpu = it; opts.addDelegate(it) }
                    else -> {
                        val nn = NnApiDelegate.Options().setAllowFp16(true).setUseNnapiCpu(false)
                            .setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED)
                            .setCacheDir(context.cacheDir.absolutePath).setModelToken("hxenhance-esrgan-v1")
                        if (name.isNotEmpty()) nn.setAcceleratorName(name)
                        NnApiDelegate(nn).also { nnapi = it; opts.addDelegate(it) }
                    }
                }
                model.rewind()
                val i = com.hyperxr.diagnostics.NativeCrashGuard.run(step) {
                    Interpreter(model, opts).also { modelIn.rewind(); modelOut.rewind(); it.run(modelIn, modelOut) }
                }
                // a delegate that takes the model but runs it slowly is no better than none
                val t0 = System.nanoTime()
                modelIn.rewind(); modelOut.rewind(); i.run(modelIn, modelOut)
                val ms = (System.nanoTime() - t0) / 1e6f
                if (ms > 120f) { Diagnostics.log("Enhance: $label too slow (${ms.toInt()} ms)"); i.close(); closeDelegates(); continue }
                interpreter = i; device = label
                Diagnostics.log("Passthrough enhancement: Real-ESRGAN on $label, ${ms.toInt()} ms")
                return
            } catch (t: Throwable) {
                Diagnostics.log("Enhance: $label failed (${t.message})")
                closeDelegates()
            }
        }
        device = "GPU (restauração sem NPU)"
    }

    private fun closeDelegates() { nnapi?.close(); nnapi = null; gpu?.close(); gpu = null }

    /**
     * GL thread, once per frame after the camera updated: every few frames (when the worker is
     * free) the camera image is shrunk and handed to the worker.
     */
    fun capture(camera: CameraImageSource) {
        if (prog == 0 || !camera.hasFrame) return
        if (++frame % 3L != 0L || busy.get()) return
        val prevFbo = IntArray(1); GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        val vp = IntArray(4); GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, vp, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, W, H)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(prog)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(prog, "uTexMatrix"), 1, false, camera.texMatrix, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camera.textureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uTex"), 0)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        read.rewind()
        GLES30.glReadPixels(0, 0, W, H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, read)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
        GLES30.glViewport(vp[0], vp[1], vp[2], vp[3])
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        // rows come bottom-up from GL; the grid and the model want the top row first
        for (y in 0 until H) {
            val row = (H - 1 - y) * W * 4
            for (x in 0 until W) {
                val p = row + x * 4; val o = (y * W + x) * 3
                src[o] = (read.get(p).toInt() and 0xff) / 255f
                src[o + 1] = (read.get(p + 1).toInt() and 0xff) / 255f
                src[o + 2] = (read.get(p + 2).toInt() and 0xff) / 255f
            }
        }
        busy.set(true)
        worker.execute {
            try { analyse() } catch (t: Throwable) { Diagnostics.log("Enhance: ${t.message}") } finally { busy.set(false) }
        }
    }

    private fun analyse() {
        val t0 = System.nanoTime()
        val i = interpreter
        if (i != null) {
            // the 128 x 72 image at the top of the model's 128 x 128 input, its last row repeated
            modelIn.rewind()
            for (y in 0 until N) {
                val sy = minOf(y, H - 1)
                for (x in 0 until N) {
                    val o = (sy * W + x) * 3
                    modelIn.put((src[o] * 255f + 0.5f).toInt().toByte())
                    modelIn.put((src[o + 1] * 255f + 0.5f).toInt().toByte())
                    modelIn.put((src[o + 2] * 255f + 0.5f).toInt().toByte())
                }
            }
            modelIn.rewind(); modelOut.rewind()
            i.run(modelIn, modelOut)
            // 512 x 288 of the 512 x 512 output, averaged 4 x 4 back to 128 x 72
            val outW = N * 4
            for (y in 0 until H) for (x in 0 until W) for (c in 0..2) {
                var s = 0
                for (dy in 0..3) for (dx in 0..3) s += modelOut.get(((y * 4 + dy) * outW + (x * 4 + dx)) * 3 + c).toInt() and 0xff
                restored[(y * W + x) * 3 + c] = (OUT_SCALE * (s / 16f - OUT_ZERO)).coerceIn(0f, 1f)
            }
            grid.fit(src, restored, W, H)
        } else {
            grid.fit(src, null, W, H)
        }
        lastMs = (System.nanoTime() - t0) / 1e6f
        synchronized(pendingA) { grid.a.copyInto(pendingA); grid.b.copyInto(pendingB); fresh = true }
    }

    /** GL thread: binds the grid (units 1 and 2) and sets the camera shader's uniforms. */
    fun bind(p: SceneRenderer.Program) {
        if (fresh) {
            synchronized(pendingA) {
                put(gainTex) { k -> ((pendingA[k] - A_MIN) / (A_MAX - A_MIN)).coerceIn(0f, 1f) }
                put(offsetTex) { k -> ((pendingB[k] - B_MIN) / (B_MAX - B_MIN)).coerceIn(0f, 1f) }
                fresh = false
            }
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, gainTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, offsetTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1i(p.u("uGain"), 1)
        GLES30.glUniform1i(p.u("uOffset"), 2)
        GLES30.glUniform1f(p.u("uEnhance"), 1f)
        GLES30.glUniform1f(p.u("uSharp"), sharpness)
        GLES30.glUniform4f(p.u("uRange"), A_MIN, A_MAX - A_MIN, B_MIN, B_MAX - B_MIN)
    }

    private inline fun put(tex: Int, value: (Int) -> Float) {
        upload.rewind()
        for (cell in 0 until grid.gw * grid.gh) {
            for (c in 0..2) upload.put((value(cell * 3 + c) * 255f + 0.5f).toInt().toByte())
            upload.put(0xff.toByte())
        }
        upload.rewind()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, grid.gw, grid.gh, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, upload)
    }

    /** Stops the worker and the model (any thread; the GL objects go with the GL context). */
    fun shutdown() {
        worker.shutdown()
        runCatching { worker.awaitTermination(300, java.util.concurrent.TimeUnit.MILLISECONDS) }
        interpreter?.close(); interpreter = null; closeDelegates()
    }

    fun release() {
        worker.shutdown()
        runCatching { worker.awaitTermination(300, java.util.concurrent.TimeUnit.MILLISECONDS) }
        interpreter?.close(); interpreter = null; closeDelegates()
        if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        GLES30.glDeleteTextures(3, intArrayOf(smallTex, gainTex, offsetTex), 0)
        if (prog != 0) GLES30.glDeleteProgram(prog)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        prog = 0; fbo = 0; vao = 0
    }

    companion object {
        const val ASSET = "models/real_esrgan_x4v3_w8a8.tflite"
        private const val GPU = "\u0000gpu"
        /** The shrunk camera image (16:9, the usual camera texture shape). */
        const val W = 128
        const val H = 72
        /** The model's square input; its output is 4x as large. */
        private const val N = 128
        /** Output quantization of the int8 model (from its metadata). */
        private const val OUT_SCALE = 0.004972266f
        private const val OUT_ZERO = 25f
        /** The ranges ToneGrid keeps a and b in, packed into 8-bit textures. */
        const val A_MIN = 0.5f
        const val A_MAX = 4.5f
        const val B_MIN = -0.3f
        const val B_MAX = 0.3f

        fun npuName(): String? {
            val rows = runCatching { NnapiNative.devices() }.getOrDefault(emptyList()).map { it.split('|') }.filter { it.size >= 2 }
            val accels = rows.filter { it[1] == "accelerator" && !it[0].contains("reference", true) }.map { it[0] }
            val npuWords = listOf("npu", "eden", "neuron", "mdla", "apu", "hta", "htp", "edgetpu", "tpu", "nnhal", "dsp")
            return accels.sortedBy { n -> npuWords.indexOfFirst { n.contains(it, true) }.let { if (it < 0) 99 else it } }.firstOrNull()
        }

        private const val SHRINK_VS = """#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uTexMatrix;
out vec2 vUv;
void main() {
    vec2 uv = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5);   // top-left origin, like the camera quad
    vUv = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
    gl_Position = vec4(aPos.xy, 0.0, 1.0);
}"""

        private const val SHRINK_FS = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec2 vUv;
uniform samplerExternalOES uTex;
out vec4 fragColor;
void main() { fragColor = vec4(texture(uTex, vUv).rgb, 1.0); }"""
    }
}
