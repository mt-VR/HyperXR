package com.hyperxr.color

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.handtracking.CameraIntrinsics
import com.hyperxr.handtracking.DepthSampler
import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Where the calibrated controllers are kept: one file per hand (files/color_controller_left.json,
 * files/color_controller_right.json), so two controllers (two Joy-Cons) can be tracked at once.
 */
object ColorControllerStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun file(ctx: Context, side: Int) = File(ctx.filesDir, if (side == 0) "color_controller_left.json" else "color_controller_right.json")

    fun load(ctx: Context, side: Int): ColorControllerModel {
        migrate(ctx)
        return runCatching { json.decodeFromString(ColorControllerModel.serializer(), file(ctx, side).readText()) }.getOrDefault(ColorControllerModel(side = side)).copy(side = side)
    }

    fun loadAll(ctx: Context): Array<ColorControllerModel> = arrayOf(load(ctx, 0), load(ctx, 1))
    fun save(ctx: Context, m: ColorControllerModel) { file(ctx, m.side).writeText(json.encodeToString(ColorControllerModel.serializer(), m)) }
    fun delete(ctx: Context, side: Int) { file(ctx, side).delete() }
    /** Changes whenever either file changes. */
    fun stamp(ctx: Context): Long = file(ctx, 0).lastModified() * 31 + file(ctx, 1).lastModified()

    /** The first version kept a single controller in color_controller.json: it becomes its hand's file. */
    private fun migrate(ctx: Context) {
        val old = File(ctx.filesDir, "color_controller.json")
        if (!old.exists()) return
        runCatching { json.decodeFromString(ColorControllerModel.serializer(), old.readText()) }.getOrNull()?.let { m ->
            if (!file(ctx, m.side).exists()) save(ctx, m)
        }
        old.delete()
    }
}

/**
 * Tracks the colour-marked controllers (up to two, one per hand) in the camera frames hand
 * tracking already analyses. Every frame is shrunk to ~320 px, a fragment shader on the GPU sorts
 * each pixel into one of the markers' colours of both controllers at once (HSV, the same test as
 * [ColorMatch.classify]), the largest blob of each colour becomes a spot, and [ColorPoseSolver]
 * turns each controller's spots into its pose. Without a usable GL context the same sorting runs
 * on the CPU.
 *
 * A spot marked as a button, hidden (under a finger) while the others are seen, is a click.
 */
class ColorTracker(private val ctx: Context) {
    class Snapshot {
        val pose = Pose()
        /** World direction it points. */
        val aim = Vec3(0f, 0f, -1f)
        var pressed = false
        var dof = 0
        var timeNs = 0L
    }

    /**
     * For the calibrator's test view: spots found for all markers (left's first, then right's;
     * image 0..1, NaN when not), and per hand the aim drawn in the image.
     */
    class Debug(
        val spots: FloatArray, val owner: IntArray,
        val center: Array<FloatArray?>, val tip: Array<FloatArray?>,
        val dof: IntArray, val error: FloatArray, val pressed: BooleanArray, val gpu: Boolean,
    )

    /** Everything about one hand's controller. */
    private class Track(val side: Int) {
        var model = ColorControllerModel(side = side)
        var first = 0                       // its first marker in the combined list
        val camRot = Quat(); val camPos = Vec3()
        var haveCam = false
        var hiddenFrames = 0
        var pressed = false
        val smoothRot = Quat(); val smoothPos = Vec3()
        var smoothed = false
        val snap = Snapshot()
        val obs = ArrayList<ColorPoseSolver.Obs>()
        fun reset() { haveCam = false; smoothed = false; hiddenFrames = 0; pressed = false }
    }

    private val tracks = arrayOf(Track(0), Track(1))
    /** The markers of both controllers, left's first: what the shader sorts pixels into. */
    @Volatile private var combined = ColorControllerModel()
    @Volatile var debugListener: ((Debug) -> Unit)? = null
    /** True when the shader runs on the GPU; false when the CPU does the sorting. */
    @Volatile var onGpu = false; private set

    /** The controller of one hand (0 left, 1 right). */
    fun model(side: Int): ColorControllerModel = tracks[side].model
    val usable: Boolean get() = combined.usable

    private val thread = HandlerThread("hx-color").apply { start() }
    private val handler = Handler(thread.looper)
    private val busy = AtomicBoolean(false)
    private var small: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect(); private val dstRect = Rect()
    private var frameIntr: CameraIntrinsics? = null
    private val framePose = Pose()
    private var modelStamp = Long.MIN_VALUE
    private var lastCheckNs = 0L
    private val lock = Any()

    init { use(ColorControllerStore.loadAll(ctx)) }

    /** Hand tracking's frame callback (its thread): copies a small picture and hands it to our thread. */
    val tap: (Bitmap, CameraIntrinsics, Pose, DepthSampler?) -> Unit = { bmp, intr, pose, _ -> offer(bmp, intr, pose) }

    /** Use these controllers right away (the calibrator); the tracker otherwise rereads the files when they change. */
    fun use(models: Array<ColorControllerModel>) {
        handler.post {
            var first = 0
            val all = ArrayList<ColorMarker>()
            for (t in tracks) {
                val m = models.getOrNull(t.side) ?: ColorControllerModel(side = t.side)
                // the shader takes 8 colours: the right controller gets what the left leaves
                val room = ColorTracker.MAX - all.size
                t.model = if (m.markers.size > room) m.copy(markers = m.markers.take(room)) else m
                t.first = first; first += t.model.markers.size
                all += t.model.markers
                t.reset()
                synchronized(lock) { t.snap.timeNs = 0L }
            }
            combined = ColorControllerModel(all)
        }
    }

    fun offer(bmp: Bitmap, intr: CameraIntrinsics, pose: Pose?) {
        val now = System.nanoTime()
        if (now - lastCheckNs > 2_000_000_000L) {
            lastCheckNs = now
            val stamp = ColorControllerStore.stamp(ctx)
            if (stamp != modelStamp) {
                val firstCheck = modelStamp == Long.MIN_VALUE
                modelStamp = stamp
                if (!firstCheck) use(ColorControllerStore.loadAll(ctx))
            }
        }
        if (!combined.usable || !busy.compareAndSet(false, true)) return
        val sw = minOf(320, bmp.width); val sh = (bmp.height.toLong() * sw / bmp.width).toInt().coerceAtLeast(1)
        val s = small?.takeIf { it.width == sw && it.height == sh } ?: Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).also { small = it }
        srcRect.set(0, 0, bmp.width, bmp.height); dstRect.set(0, 0, sw, sh)
        Canvas(s).drawBitmap(bmp, srcRect, dstRect, paint)
        frameIntr = intr
        if (pose != null) framePose.set(pose) else { framePose.position.set(0f, 0f, 0f); framePose.orientation.identity() }
        handler.post { try { process(s) } catch (t: Throwable) { Diagnostics.log("Color controller: ${t.message}") } finally { busy.set(false) } }
    }

    /** The latest pose of one hand's controller; false before the first one. */
    fun read(side: Int, out: Snapshot): Boolean = synchronized(lock) {
        val snap = tracks[side].snap
        if (snap.timeNs == 0L) return false
        out.pose.set(snap.pose); out.aim.set(snap.aim); out.pressed = snap.pressed; out.dof = snap.dof; out.timeNs = snap.timeNs
        true
    }

    fun shutdown() { handler.post { releaseGl() }; thread.quitSafely() }

    // ------------------------------------------------------------------ per frame (our thread)
    private var classes = ByteArray(0)
    private var blobs = Array(0) { BlobFinder.Blob() }
    private val result = ColorPoseSolver.Result()
    private val worldRot = Quat(); private val worldPos = Vec3(); private val tmp = Vec3()

    private fun process(bmp: Bitmap) {
        val all = combined
        val intr = frameIntr ?: return
        val w = bmp.width; val h = bmp.height
        if (classes.size != w * h) classes = ByteArray(w * h)
        if (blobs.size != all.markers.size) blobs = Array(all.markers.size) { BlobFinder.Blob() }
        if (!classifyGpu(bmp, all)) classifyCpu(bmp, all)
        BlobFinder.find(classes, w, h, all.markers.size, blobs, minArea = 5)
        val pxPerSmall = intr.width.toFloat() / w
        val listener = debugListener
        val center = arrayOfNulls<FloatArray>(2); val tip = arrayOfNulls<FloatArray>(2)
        val dof = IntArray(2); val error = FloatArray(2); val pressedOut = BooleanArray(2)
        for (t in tracks) {
            val m = t.model
            if (!m.usable) continue
            t.obs.clear()
            var buttonSeen = true; var others = 0
            for (i in m.markers.indices) {
                val b = blobs[t.first + i]; val mk = m.markers[i]
                if (!b.found) { if (mk.button) buttonSeen = false; continue }
                if (!mk.button) others++
                val u = (b.x * intr.width - intr.cx) / intr.fx
                val v = (b.y * intr.height - intr.cy) / intr.fy
                val r = sqrt(b.area / PI.toFloat()) * pxPerSmall / intr.fx
                t.obs += ColorPoseSolver.Obs(i, u, v, r)
            }
            // a click: the button spot gone for two frames while the rest is in view
            if (m.markers.any { it.button }) {
                if (!buttonSeen && others >= 1) t.hiddenFrames++ else t.hiddenFrames = 0
                t.pressed = t.hiddenFrames >= 2
            } else t.pressed = false
            pressedOut[t.side] = t.pressed
            if (t.obs.isEmpty()) continue
            val ok = ColorPoseSolver.solve(m, t.obs, if (t.haveCam) t.camRot else null, if (t.haveCam) t.camPos else null, result)
            error[t.side] = result.error
            if (!ok || result.error > 0.05f) continue
            t.camRot.set(result.rotation); t.camPos.set(result.position); t.haveCam = true
            dof[t.side] = result.dof
            // world pose: the camera's pose times the controller's pose in camera space
            framePose.orientation.mul(t.camRot, worldRot).normalize()
            framePose.transformPoint(t.camPos, worldPos)
            if (!t.smoothed) { t.smoothRot.set(worldRot); t.smoothPos.set(worldPos); t.smoothed = true }
            else {
                // follow quickly when it moves, hold still when it doesn't (less shake at rest)
                val move = tmp.set(worldPos).sub(t.smoothPos).length()
                val a = (0.35f + move * 25f).coerceIn(0.35f, 1f)
                t.smoothPos.lerp(worldPos, a)
                t.smoothRot.slerp(worldRot, if (result.dof >= 6) a else 0.25f, t.smoothRot).normalize()
            }
            synchronized(lock) {
                t.snap.pose.position.set(t.smoothPos); t.snap.pose.orientation.set(t.smoothRot)
                t.smoothRot.rotate(m.aim(tmp), t.snap.aim).normalize()
                t.snap.pressed = t.pressed; t.snap.dof = result.dof; t.snap.timeNs = System.nanoTime()
            }
            if (listener != null) {
                // centre and a point 12 cm along the aim, back in the picture
                center[t.side] = project(t.camPos, intr)
                val tipCam = t.camRot.rotate(m.aim(Vec3()), Vec3()).also { it.scale(0.12f).add(t.camPos) }
                tip[t.side] = project(tipCam, intr)
            }
        }
        if (listener != null) {
            val spots = FloatArray(all.markers.size * 2) { Float.NaN }
            for ((i, b) in blobs.withIndex()) if (b.found) { spots[i * 2] = b.x; spots[i * 2 + 1] = b.y }
            val owner = IntArray(all.markers.size) { i -> if (i < tracks[1].first) 0 else 1 }
            listener(Debug(spots, owner, center, tip, dof, error, pressedOut, onGpu))
        }
    }

    private fun project(p: Vec3, intr: CameraIntrinsics): FloatArray? {
        if (p.z > -0.02f) return null
        val d = -p.z
        return floatArrayOf((p.x / d * intr.fx + intr.cx) / intr.width, (-p.y / d * intr.fy + intr.cy) / intr.height)
    }

    private val hsv = FloatArray(3)
    private var pixels = IntArray(0)

    private fun classifyCpu(bmp: Bitmap, m: ColorControllerModel) {
        val n = bmp.width * bmp.height
        if (pixels.size != n) pixels = IntArray(n)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        for (i in 0 until n) {
            val p = pixels[i]
            ColorMatch.hsv(((p shr 16) and 255) / 255f, ((p shr 8) and 255) / 255f, (p and 255) / 255f, hsv)
            classes[i] = ColorMatch.classify(hsv, m.markers).toByte()
        }
    }

    // ------------------------------------------------------------------ GPU
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var glFailed = false
    private var program = 0
    private var tex = 0; private var fbo = 0; private var outTex = 0
    private var texW = 0; private var texH = 0
    private var readBuf: ByteBuffer? = null
    private val uCol = FloatArray(MAX * 4); private val uLim = FloatArray(MAX * 4)

    private fun classifyGpu(bmp: Bitmap, m: ColorControllerModel): Boolean {
        if (glFailed || m.markers.size > MAX) return false
        if (context == EGL14.EGL_NO_CONTEXT && !initGl()) { glFailed = true; releaseGl(); Diagnostics.log("Color controller: GPU unavailable, sorting colours on the CPU"); return false }
        val w = bmp.width; val h = bmp.height
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        if (w != texW || h != texH) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outTex)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, outTex, 0)
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) { glFailed = true; return false }
            texW = w; texH = h
            readBuf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        } else GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
        for ((i, mk) in m.markers.withIndex()) {
            uCol[i * 4] = mk.h; uCol[i * 4 + 1] = mk.s; uCol[i * 4 + 2] = mk.v; uCol[i * 4 + 3] = mk.hueTol
            uLim[i * 4] = mk.satMin; uLim[i * 4 + 1] = mk.valMin; uLim[i * 4 + 2] = if (mk.s < 0.22f) 1f else 0f; uLim[i * 4 + 3] = 0f
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "tex"), 0)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "col"), MAX, uCol, 0)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "lim"), MAX, uLim, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "count"), m.markers.size.toFloat())
        val pos = GLES20.glGetAttribLocation(program, "pos")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, TRIANGLE)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
        val buf = readBuf ?: return false
        buf.position(0)
        // rows come out bottom first; the quad samples the bitmap's top row at the bottom, so they line up
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) { glFailed = true; return false }
        for (i in 0 until w * h) classes[i] = buf.get(i * 4)
        onGpu = true
        return true
    }

    private fun initGl(): Boolean {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return false
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return false
        val attrs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE)
        val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attrs, 0, cfgs, 0, 1, n, 0) || n[0] == 0) return false
        context = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) return false
        surface = EGL14.eglCreatePbufferSurface(display, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        if (surface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, surface, surface, context)) return false
        program = link(VS, FS) ?: return false
        val ids = IntArray(2)
        GLES20.glGenTextures(2, ids, 0); tex = ids[0]; outTex = ids[1]
        for (t in ids) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        val f = IntArray(1); GLES20.glGenFramebuffers(1, f, 0); fbo = f[0]
        texW = 0; texH = 0
        return true
    }

    private fun link(vs: String, fs: String): Int? {
        fun shader(type: Int, src: String): Int? {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) { Diagnostics.log("Color shader: ${GLES20.glGetShaderInfoLog(s)}"); return null }
            return s
        }
        val v = shader(GLES20.GL_VERTEX_SHADER, vs) ?: return null
        val f = shader(GLES20.GL_FRAGMENT_SHADER, fs) ?: return null
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f); GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        return if (ok[0] == 0) null else p
    }

    private fun releaseGl() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY; context = EGL14.EGL_NO_CONTEXT; surface = EGL14.EGL_NO_SURFACE
    }

    companion object {
        const val MAX = 8
        private val TRIANGLE = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)); position(0) }

        // one triangle that covers the whole target
        private const val VS = """
attribute vec2 pos;
varying vec2 uv;
void main() {
    gl_Position = vec4(pos, 0.0, 1.0);
    uv = pos * 0.5 + 0.5;
}"""

        /** Same rules as ColorMatch.classify: hue within tolerance and enough saturation and light; greys by brightness. */
        private const val FS = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D tex;
uniform vec4 col[8];
uniform vec4 lim[8];
uniform float count;
varying vec2 uv;
vec3 hsv(vec3 c) {
    float mx = max(c.r, max(c.g, c.b));
    float mn = min(c.r, min(c.g, c.b));
    float d = mx - mn;
    float h = 0.0;
    if (d > 1e-5) {
        if (mx == c.r) h = mod((c.g - c.b) / d, 6.0);
        else if (mx == c.g) h = (c.b - c.r) / d + 2.0;
        else h = (c.r - c.g) / d + 4.0;
    }
    h *= 60.0;
    if (h < 0.0) h += 360.0;
    return vec3(h, mx > 1e-5 ? d / mx : 0.0, mx);
}
void main() {
    vec3 p = hsv(texture2D(tex, uv).rgb);
    float best = 0.0;
    float bestD = 1e9;
    for (int i = 0; i < 8; i++) {
        if (float(i) >= count) break;
        vec4 c = col[i];
        vec4 l = lim[i];
        float dist;
        bool ok;
        if (l.z > 0.5) {
            dist = abs(p.z - c.z) * 100.0;
            ok = p.y < 0.28 && abs(p.z - c.z) < 0.16;
        } else {
            float hd = abs(p.x - c.x);
            hd = mod(hd, 360.0);
            if (hd > 180.0) hd = 360.0 - hd;
            dist = hd;
            ok = hd <= c.w && p.y >= l.x && p.z >= l.y;
        }
        if (ok && dist < bestD) { bestD = dist; best = float(i + 1); }
    }
    gl_FragColor = vec4(best / 255.0, 0.0, 0.0, 1.0);
}"""
    }
}
