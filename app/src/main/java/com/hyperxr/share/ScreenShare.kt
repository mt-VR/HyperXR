package com.hyperxr.share

import android.app.Activity
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.view.Surface
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.math.Pose
import com.hyperxr.rendering.FullscreenTriangle
import com.hyperxr.rendering.Gl
import com.hyperxr.rendering.RenderTarget
import com.hyperxr.rendering.Shaders
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Screen sharing in 16:9: a separate mono "spectator" view of the XR scene, rendered from the
 * head (optionally steadied) without the side-by-side split or the lens distortion, at 720p,
 * 1080p or 1440p. It feeds:
 *  - HyperXR's own video recording (MP4, H.264, optional microphone) and screenshots;
 *  - external displays (HDMI/USB-C, Miracast or casting that offers a second display);
 *  - a live stream any browser on the same Wi-Fi can open;
 *  - a 16:9 copy of what another app records (Android 15+ tells apps when they are being
 *    recorded): the phone screen stays side by side for the headset, and HyperXR records its own
 *    16:9 video alongside, saved when the other recording stops.
 * Everything here runs on the GL thread except where noted.
 */
class ScreenShare(private val activity: Activity) {
    private val prefs = activity.getSharedPreferences("share", Context.MODE_PRIVATE)

    var quality: Int  // output height: 720, 1080 or 1440
        get() = prefs.getInt("quality", 1080)
        set(v) { prefs.edit().putInt("quality", v).apply() }
    var microphone: Boolean
        get() = prefs.getBoolean("mic", true)
        set(v) { prefs.edit().putBoolean("mic", v).apply() }
    var steady: Boolean
        get() = prefs.getBoolean("steady", true)
        set(v) { prefs.edit().putBoolean("steady", v).apply() }
    /** While another app records the screen, also record HyperXR's own 16:9 video. */
    var externalRecording16x9: Boolean
        get() = prefs.getBoolean("ext169", true)
        set(v) { prefs.edit().putBoolean("ext169", v).apply() }

    val width get() = quality * 16 / 9
    val height get() = quality

    /** Steadied spectator pose, updated each frame by [follow]. */
    val pose = Pose()
    private var poseInit = false

    private var target: RenderTarget? = null
    private var copyProg = 0
    private var vao = 0
    private val sinks = ArrayList<Sink>()
    private val pending = ConcurrentLinkedQueue<() -> Unit>()
    private var lastSinkFrameNs = 0L

    @Volatile var recorder: VideoRecorder? = null; private set
    val stream = MjpegServer()
    private var streamTarget: RenderTarget? = null
    private var lastStreamNs = 0L
    val displays = ExternalDisplays(activity) { op -> pending += op }
    val externalRecordingDetector = ExternalRecordingDetector(activity)
    /** Last problem to show in the panel (empty when fine). */
    @Volatile var error = ""; private set

    init { displays.share = this }

    /** True while [recorder] was started because another app is recording the screen. */
    @Volatile var pairedRecording = false; private set
    private var pairedFailed = false
    /** Name of the last video saved by a paired recording, for the panel. */
    @Volatile var lastPairedSave = ""; private set

    /** Whether the spectator view must be rendered this frame. */
    fun wanted(captureRequested: Boolean): Boolean {
        while (true) { val op = pending.poll() ?: break; op() }
        followExternalRecording()
        if (captureRequested) return true
        val now = System.nanoTime()
        // recordings, external displays and the stream run at up to 30 fps
        val busy = sinks.isNotEmpty() || stream.clients > 0
        return busy && now - lastSinkFrameNs >= 32_000_000L
    }

    /**
     * Moves the spectator pose toward the head. Steadied: rotation eases in (no shake, like a
     * camera operator); off, or over the camera image, it is exactly the head.
     */
    fun follow(head: Pose, dt: Float, overCamera: Boolean) {
        if (!poseInit || !steady || overCamera) { pose.set(head); poseInit = true; return }
        pose.position.set(head.position)
        val k = (dt * 6f).coerceIn(0f, 1f)
        pose.orientation.slerp(head.orientation, k, pose.orientation)
    }

    /** The offscreen 16:9 target the engine renders the spectator view into. */
    fun target(): RenderTarget {
        val t = target
        if (t != null && t.width == width && t.height == height) return t
        t?.release()
        return RenderTarget(width, height).also { target = it }
    }

    /** After the engine rendered into [target]: send the image to every output. */
    fun deliver() {
        val t = target ?: return
        ensureProgram()
        val now = System.nanoTime()
        if (sinks.isNotEmpty() && now - lastSinkFrameNs >= 32_000_000L) {
            lastSinkFrameNs = now
            val dpy = EGL14.eglGetCurrentDisplay()
            val draw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
            val read = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
            val ctx = EGL14.eglGetCurrentContext()
            val dead = ArrayList<Sink>()
            for (s in sinks) {
                if (!EGL14.eglMakeCurrent(dpy, s.surface, s.surface, ctx)) { dead += s; continue }
                blit(t.texture, s.width, s.height, 0)
                if (s.recording) EGLExt.eglPresentationTimeANDROID(dpy, s.surface, now)
                EGL14.eglSwapBuffers(dpy, s.surface)
            }
            EGL14.eglMakeCurrent(dpy, draw, read, ctx)
            dead.forEach { removeSink(it) }
        }
        if (stream.clients > 0 && now - lastStreamNs >= 66_000_000L) {
            lastStreamNs = now
            // the stream is 720p whatever the recording quality: JPEG encoding is on the CPU
            val st = streamTarget ?: RenderTarget(1280, 720, depth = false).also { streamTarget = it }
            blit(t.texture, st.width, st.height, st.fbo)
            stream.offer(read(st))
        }
    }

    /** The current 16:9 frame as a bitmap (screenshots). */
    fun snapshot(): android.graphics.Bitmap? = target?.let { read(it) }

    private fun read(t: RenderTarget): android.graphics.Bitmap {
        val buf = java.nio.ByteBuffer.allocateDirect(t.width * t.height * 4).order(java.nio.ByteOrder.nativeOrder())
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, t.fbo)
        GLES30.glReadPixels(0, 0, t.width, t.height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        val bmp = android.graphics.Bitmap.createBitmap(t.width, t.height, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        val flip = android.graphics.Matrix().apply { preScale(1f, -1f) }
        return android.graphics.Bitmap.createBitmap(bmp, 0, 0, t.width, t.height, flip, false).also { bmp.recycle() }
    }

    /** Copies [tex] into a w×h output, letterboxed or pillarboxed to keep 16:9. */
    private fun blit(tex: Int, w: Int, h: Int, fbo: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val vw: Int; val vh: Int
        if (w * 9 > h * 16) { vh = h; vw = h * 16 / 9 } else { vw = w; vh = w * 9 / 16 }
        GLES30.glViewport((w - vw) / 2, (h - vh) / 2, vw, vh)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(copyProg)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(copyProg, "uTex"), 0)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun ensureProgram() {
        if (copyProg != 0) return
        copyProg = Gl.compile(Shaders.FULLSCREEN_VS, Shaders.COPY_FS, "share-copy")
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0]
        GLES30.glBindVertexArray(vao); FullscreenTriangle.bind(); GLES30.glBindVertexArray(0)
    }

    // ---------------------------------------------------------------- outputs (EGL window surfaces)
    class Sink(val surface: EGLSurface, val window: Surface, val width: Int, val height: Int, val recording: Boolean)

    /** Adds [window] as an output; [recordable] picks an encoder-friendly config when possible. */
    fun addSink(window: Surface, w: Int, h: Int, recordable: Boolean): Sink? {
        val dpy = EGL14.eglGetCurrentDisplay()
        val attrs = intArrayOf(EGL14.EGL_NONE)
        for (config in configs(dpy, recordable)) {
            val s = EGL14.eglCreateWindowSurface(dpy, config, window, attrs, 0) ?: continue
            if (s == EGL14.EGL_NO_SURFACE) continue
            // the surface must work with the renderer's context
            val draw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW); val read = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
            val ctx = EGL14.eglGetCurrentContext()
            val ok = EGL14.eglMakeCurrent(dpy, s, s, ctx)
            EGL14.eglMakeCurrent(dpy, draw, read, ctx)
            if (ok) return Sink(s, window, w, h, recordable).also { sinks += it }
            EGL14.eglDestroySurface(dpy, s)
        }
        Diagnostics.log("Screen share: no EGL config works for this output")
        return null
    }

    fun removeSink(s: Sink) {
        if (sinks.remove(s)) EGL14.eglDestroySurface(EGL14.eglGetCurrentDisplay(), s.surface)
    }

    fun removeSink(window: Surface) { sinks.firstOrNull { it.window === window }?.let { removeSink(it) } }

    private fun configs(dpy: android.opengl.EGLDisplay, recordable: Boolean): List<EGLConfig> {
        val out = ArrayList<EGLConfig>()
        val ctx = EGL14.eglGetCurrentContext()
        val id = IntArray(1)
        EGL14.eglQueryContext(dpy, ctx, EGL14.EGL_CONFIG_ID, id, 0)
        fun choose(attrs: IntArray) {
            val cfgs = arrayOfNulls<EGLConfig>(4); val n = IntArray(1)
            if (EGL14.eglChooseConfig(dpy, attrs, 0, cfgs, 0, cfgs.size, n, 0)) cfgs.take(n[0]).filterNotNull().forEach { out += it }
        }
        if (recordable) choose(intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE))
        choose(intArrayOf(EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE))
        return out
    }

    // ---------------------------------------------------------------- recording
    /**
     * Starts and stops the paired 16:9 recording with another app's screen recording. Without
     * the microphone: the other recorder keeps it (Android gives it to one app at a time).
     */
    private fun followExternalRecording() {
        val ext = externalRecording16x9 && externalRecordingDetector.recording
        if (!ext) pairedFailed = false
        if (ext && recorder == null && !pairedFailed) {
            // one try per external recording: a failure would otherwise be retried every frame
            if (startRecording(mic = false)) { pairedRecording = true; Diagnostics.log("Screen share: paired 16:9 recording started") }
            else pairedFailed = true
        } else if (!ext && pairedRecording) {
            pairedRecording = false
            stopRecording { name -> lastPairedSave = name ?: "" }
        }
    }

    fun startRecording(mic: Boolean = microphone): Boolean {
        if (recorder != null) return false
        error = ""
        val r = VideoRecorder(activity, width, height, mic && micAllowed())
        val input = try { r.start() } catch (t: Throwable) { Diagnostics.log("Recorder failed: $t"); error = "Não foi possível gravar: ${t.message}"; return false }
        val sink = addSink(input, width, height, recordable = true)
        if (sink == null) { r.stop(null); error = "Não foi possível gravar neste celular"; return false }
        r.sink = sink
        recorder = r
        return true
    }

    /** Stops and saves the video; [done] gets the saved name (UI thread work happens inside). */
    fun stopRecording(done: (String?) -> Unit = {}) {
        val r = recorder ?: return
        recorder = null
        pairedRecording = false
        r.sink?.let { removeSink(it) }
        r.stop(done)
    }

    private fun micAllowed() = activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** "Gravando 0:12 · Transmitindo para 1 · Tela externa", or empty when nothing is shared. */
    val summary: String get() = buildList {
            recorder?.let { add((if (pairedRecording) "Gravando em 16:9 junto com a gravação de tela " else "Gravando ") + it.elapsedText()) }
            if (stream.clients > 0) add("Transmitindo para ${stream.clients}")
            if (displays.count > 0) add("Tela externa")
        }.joinToString(" · ")

    fun release() {
        stopRecording()
        sinks.toList().forEach { removeSink(it) }
        target?.release(); target = null
        streamTarget?.release(); streamTarget = null
        stream.stop()
        displays.stop()
        externalRecordingDetector.stop()
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
