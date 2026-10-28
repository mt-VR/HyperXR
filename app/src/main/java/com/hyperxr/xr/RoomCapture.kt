package com.hyperxr.xr

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import com.hyperxr.HyperXRApp
import com.hyperxr.environments.PanoramaStitcher
import com.hyperxr.files.MediaHost
import com.hyperxr.handtracking.HandTrackingService
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.acos
import kotlin.math.abs

/**
 * "Capture your room in 360": while you turn around, camera frames and the camera's orientation
 * are stitched into an equirectangular panorama (PanoramaStitcher), shown live, and saved as a
 * personal environment - your own room as a VR background, with nothing leaving the phone.
 */
class RoomCapturePanel(private val host: MediaHost, private val hands: HandTrackingService?) : XrPanel(1.4f, 1.05f, title = "Capturar sua sala em 360°") {
    private var stitcher = PanoramaStitcher(2048)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "hx-room-capture") }
    private val busy = AtomicBoolean(false)
    @Volatile private var capturing = false
    @Volatile private var coverage = 0f
    @Volatile private var preview: Bitmap? = null
    @Volatile private var status = ""
    private var lastAddNs = 0L
    private var lastPreviewNs = 0L
    private var lastQ: FloatArray? = null
    private var lastQNs = 0L
    private val previewPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun start() {
        val h = hands ?: run { status = "Precisa da câmera (modo realidade mista com o hand tracking ligado)"; dirty = true; return }
        capturing = true; status = "Gire devagar no lugar. Olhe também para cima e para baixo."; dirty = true
        h.frameTap = { bmp, intr, pose, _ -> onFrame(bmp, intr.fx, intr.fy, intr.cx, intr.cy, intr.width, floatArrayOf(pose.orientation.x, pose.orientation.y, pose.orientation.z, pose.orientation.w)) }
    }

    private fun stop() { capturing = false; hands?.frameTap = null; dirty = true }

    /** Hand tracking thread: picks a frame now and then and stitches it on the worker. */
    private fun onFrame(bmp: Bitmap, fx: Float, fy: Float, cx: Float, cy: Float, intrWidth: Int, q: FloatArray) {
        val now = System.nanoTime()
        // turning fast blurs the picture: skip frames while the camera spins above ~60 deg/s
        val prev = lastQ; val prevNs = lastQNs
        lastQ = q; lastQNs = now
        if (prev != null && now > prevNs) {
            val dot = abs(prev[0] * q[0] + prev[1] * q[1] + prev[2] * q[2] + prev[3] * q[3]).coerceAtMost(1f)
            val speed = 2 * acos(dot) / ((now - prevNs) / 1e9f)
            if (speed > 1.05f) return
        }
        if (now - lastAddNs < 150_000_000L || !busy.compareAndSet(false, true)) return
        lastAddNs = now
        val w = 480; val s = w.toFloat() / bmp.width
        val hgt = (bmp.height * s).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bmp, w, hgt, true)
        val k = bmp.width.toFloat() / intrWidth * s   // intrinsics are in the camera image's pixels
        worker.execute {
            try {
                val px = IntArray(w * hgt); small.getPixels(px, 0, w, 0, 0, w, hgt)
                stitcher.add(px, w, hgt, fx * k, fy * k, cx * k, cy * k, q)
                if (System.nanoTime() - lastPreviewNs > 700_000_000L) { lastPreviewNs = System.nanoTime(); makePreview(); coverage = stitcher.coverage() }
                dirty = true
            } finally { small.recycle(); busy.set(false) }
        }
    }

    private fun makePreview() {
        val pw = 512; val ph = 256
        val src = stitcher.color; val sw = stitcher.width; val sh = stitcher.height
        val out = IntArray(pw * ph) { i ->
            val p = src[(i / pw) * sh / ph * sw + (i % pw) * sw / pw]
            if (p == 0) 0xff2a2c31.toInt() else p
        }
        preview = Bitmap.createBitmap(out, pw, ph, Bitmap.Config.ARGB_8888)
    }

    private fun save() {
        stop()
        status = "Montando o panorama..."; dirty = true
        worker.execute {
            val entry = try {
                val px = stitcher.result()
                val bmp = Bitmap.createBitmap(px, stitcher.width, stitcher.height, Bitmap.Config.ARGB_8888)
                val jpg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
                bmp.recycle()
                val name = "Minha sala " + SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date())
                HyperXRApp.instance.environments.importPanorama(name, ByteArrayInputStream(jpg), source = "Capturado com a câmera do HyperMagic")
            } catch (t: Throwable) { status = "Falha: ${t.message}"; dirty = true; null }
            if (entry != null) host.onGl { host.selectEnvironment(entry); status = "Salvo em Ambientes e aplicado: ${entry.name}"; dirty = true }
        }
    }

    private fun restart() {
        stop()
        worker.execute { stitcher = PanoramaStitcher(2048); coverage = 0f; preview = null; status = ""; dirty = true }
    }

    override fun draw(c: PanelCanvas) {
        c.paragraph("Fique no mesmo lugar e gire o corpo devagar, olhando em volta, para cima e para baixo. " +
            "A câmera e a orientação da cabeça montam uma foto 360° da sua sala, que vira um ambiente. Nada sai do celular.", 26f)
        val top = c.y + 8f
        val r = RectF(c.pad, top, c.w - c.pad, top + (c.w - 2 * c.pad) / 2f)
        val img = preview
        if (img != null) c.canvas.drawBitmap(img, null, r, previewPaint)
        else c.canvas.drawRoundRect(r, 18f, 18f, Paint().apply { color = 0xff2a2c31.toInt() })
        c.y = r.bottom + 16f
        c.label("Visto: %.0f%% da esfera".format(coverage * 100), c.pad, c.y, 30f, XrTheme.text, bold = true)
        c.y += 52f
        if (status.isNotEmpty()) { c.paragraph(status, 26f); c.y += 4f }
        val by = c.y + 6f
        if (capturing) c.button("pause", "Pausar", c.pad, by, 260f, 80f) { stop() }
        else c.button("start", if (stitcher.frames == 0) "Começar" else "Continuar", c.pad, by, 260f, 80f, accent = true) { start() }
        c.button("save", "Salvar como ambiente", c.pad + 280f, by, 420f, 80f, accent = coverage >= 0.25f) {
            if (coverage < 0.1f) { status = "Gire mais um pouco antes de salvar"; dirty = true } else save()
        }
        c.button("again", "Recomeçar", c.pad + 720f, by, 260f, 80f) { restart() }
    }

    override fun release() { stop(); worker.shutdown(); super.release() }
}
