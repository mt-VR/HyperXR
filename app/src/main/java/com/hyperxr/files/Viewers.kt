package com.hyperxr.files

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.hyperxr.HyperXRApp
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.input.PointerEvent
import com.hyperxr.input.PointerPhase
import com.hyperxr.math.Vec3
import com.hyperxr.media.StereoLayout
import com.hyperxr.rendering.FrameScene
import com.hyperxr.rendering.Gl
import com.hyperxr.rendering.SceneRenderer
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme
import com.hyperxr.windows.VirtualScreen
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

private fun layoutName(l: StereoLayout) = when (l) {
    StereoLayout.MONO -> "Mono"; StereoLayout.TOP_BOTTOM -> "3D cima/baixo"; StereoLayout.LEFT_RIGHT -> "3D lado a lado"
}

private fun nextLayout(l: StereoLayout) = StereoLayout.entries[(l.ordinal + 1) % StereoLayout.entries.size]

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Area of the panel used for content: under the title bar, above one row of controls. */
private fun contentRect(c: PanelCanvas, bottomBar: Float = 110f) = RectF(c.pad, c.y, c.w - c.pad, c.h - bottomBar)

// ======================================================================= image viewer

/**
 * Photo viewer: fit, zoom (pinch-drag the photo to pan), rotate, previous/next, and for 360
 * photos a view around the user plus "save as environment".
 */
class ImageViewerPanel(private val host: MediaHost, private val items: List<MediaItem>, private var index: Int) :
    XrPanel(1.5f, 1.05f, title = items[index].name) {
    private var bmp: Bitmap? = null
    @Volatile private var loading = true
    @Volatile private var error: String? = null
    @Volatile private var status: String? = null
    private var zoom = 1f
    private var panX = 0f; private var panY = 0f
    private var rotation = 0
    private var panning = false
    private var lastX = 0f; private var lastY = 0f
    private var imageRect = RectF()
    private var immersiveTex = 0
    private var layout = StereoLayout.MONO
    private val item get() = items[index]

    init { load() }

    private fun load() {
        loading = true; error = null; status = null
        title = item.name
        zoom = 1f; panX = 0f; panY = 0f; rotation = 0
        val it = item
        mediaIo.execute {
            val b = try { it.decodeBitmap(host.activity, 2048) } catch (t: Throwable) { error = t.message; null }
            it.detectSphere(host.activity)
            host.onGl {
                if (it !== item) { b?.recycle(); return@onGl }
                bmp?.recycle(); bmp = b
                if (b == null && error == null) error = "Formato de imagem não suportado"
                loading = false; dirty = true
            }
        }
    }

    private fun go(delta: Int) {
        if (items.size < 2) return
        exitImmersive()
        index = (index + delta + items.size) % items.size
        load(); dirty = true
    }

    override fun draw(c: PanelCanvas) {
        val area = contentRect(c)
        imageRect = area
        addRegion(XrPanel.Region(area, "img", null))
        val b = bmp
        val sphere = item.sphere
        when {
            immersiveTex != 0 -> c.label("Foto 360 ao seu redor. Olhe em volta.\n${layoutName(layout)}", area.left + 20f, area.top + 30f, 34f, XrTheme.text)
            loading -> c.label("Carregando...", area.left + 20f, area.top + 30f, 32f, XrTheme.textDim)
            b == null -> c.label(error ?: "Não foi possível abrir", area.left + 20f, area.top + 30f, 32f, XrTheme.danger)
            else -> {
                c.canvas.save()
                c.canvas.clipRect(area)
                val rotated = rotation % 180 != 0
                val bw = if (rotated) b.height else b.width
                val bh = if (rotated) b.width else b.height
                val s = minOf(area.width() / bw, area.height() / bh) * zoom
                c.canvas.translate(area.centerX() + panX, area.centerY() + panY)
                c.canvas.rotate(rotation.toFloat())
                c.canvas.drawBitmap(b, null, RectF(-b.width * s / 2, -b.height * s / 2, b.width * s / 2, b.height * s / 2), bmpPaint)
                c.canvas.restore()
            }
        }
        // info line
        val info = buildString {
            if (b != null) append("${b.width}x${b.height}  ")
            if (item.sizeBytes > 0) append(FileKinds.formatSize(item.sizeBytes) + "  ")
            if (sphere?.spherical == true) append(if (sphere.fromMetadata) "Foto 360 (metadados)" else "Provável 360 (proporção 2:1)")
            status?.let { append("  $it") }
            if (items.size > 1) append("   ${index + 1}/${items.size}")
        }
        c.label(info, c.pad, c.h - 150f, 24f, XrTheme.textDim)
        // controls
        val y = c.h - 100f
        val gap = 10f
        val buttons = ArrayList<Triple<String, String, () -> Unit>>()
        if (items.size > 1) { buttons += Triple("prev", "Anterior") { go(-1) }; buttons += Triple("next", "Próxima") { go(1) } }
        if (immersiveTex == 0) {
            buttons += Triple("zout", "Zoom -") { zoom = (zoom / 1.5f).coerceAtLeast(1f); if (zoom == 1f) { panX = 0f; panY = 0f } }
            buttons += Triple("zin", "Zoom +") { zoom = (zoom * 1.5f).coerceAtMost(8f) }
            buttons += Triple("rot", "Girar") { rotation = (rotation + 90) % 360 }
            if (sphere?.spherical != true && b != null && !item.name.contains("sbs", ignoreCase = true)) buttons += Triple("make3d", "Foto 3D") { make3d() }
        }
        if (sphere?.spherical == true) {
            if (immersiveTex == 0) buttons += Triple("360", "Ver em 360") { enterImmersive() }
            else {
                buttons += Triple("exit360", "Sair do 360") { exitImmersive() }
                buttons += Triple("stereo", layoutName(layout)) { layout = nextLayout(layout); host.immersive?.layout = layout }
            }
            buttons += Triple("env", "Salvar como ambiente") { saveAsEnvironment() }
        }
        val bw = (c.w - 2 * c.pad - (buttons.size - 1) * gap) / buttons.size.coerceAtLeast(1)
        buttons.forEachIndexed { i, (id, label, act) ->
            c.button(id, label, c.pad + i * (bw + gap), y, bw, 80f, accent = id == "360" || id == "exit360") { act(); dirty = true }
        }
    }

    /**
     * A side-by-side 3D copy of this photo, saved next to it in the gallery (Pictures/HyperMagic):
     * Depth Anything V2 on the NPU says how near each point is and StereoFromDepth (PhoneXR's
     * SpatialPhoto, by Samrat Beketov) draws the two eyes. The depth model is downloaded once.
     */
    private fun make3d() {
        val it = item
        status = "Foto 3D: preparando a IA de profundidade..."
        mediaIo.execute {
            val ctx = host.activity
            val engine = com.hyperxr.handtracking.MonoDepthEngine(ctx)
            try {
                if (!engine.prepare()) throw IllegalStateException(engine.status)
                status = "Foto 3D: calculando a profundidade..."; host.onGl { dirty = true }
                val photo = it.decodeBitmap(ctx, 1600) ?: throw IllegalStateException("a foto não abriu")
                val n = com.hyperxr.handtracking.MonoDepthEngine.SIZE
                val small = Bitmap.createScaledBitmap(photo, n, n, true)
                if (!engine.run(small)) throw IllegalStateException("a IA não respondeu")
                small.recycle()
                val near = com.hyperxr.media.StereoFromDepth.normalize(engine.disparity)
                val w = photo.width; val h = photo.height
                val src = IntArray(w * h).also { px -> photo.getPixels(px, 0, w, 0, 0, w, h) }
                photo.recycle()
                val (l, r) = com.hyperxr.media.StereoFromDepth.eyes(src, w, h, near, n, n)
                val out = Bitmap.createBitmap(w * 2, h, Bitmap.Config.ARGB_8888)
                out.setPixels(l, 0, w, 0, 0, w, h); out.setPixels(r, 0, w, w, 0, w, h)
                val name = it.name.substringBeforeLast('.').ifEmpty { "foto" } + "_sbs_3d.jpg"
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (android.os.Build.VERSION.SDK_INT >= 29) put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/HyperMagic")
                }
                val uri = ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("a galeria não deixou salvar")
                ctx.contentResolver.openOutputStream(uri)?.use { s -> out.compress(Bitmap.CompressFormat.JPEG, 94, s) } ?: throw IllegalStateException("o arquivo não foi gravado")
                out.recycle()
                status = "Foto 3D salva: $name"
            } catch (t: Throwable) {
                status = "Foto 3D falhou: ${t.message}"
                Diagnostics.log("Spatial photo: $t")
            } finally {
                engine.close()
                host.onGl { dirty = true }
            }
        }
    }

    private fun enterImmersive() {
        status = "Preparando 360..."
        val it = item
        mediaIo.execute {
            val big = try { it.decodeBitmap(host.activity, 4096) } catch (t: Throwable) { null }
            host.onGl {
                if (big == null) { status = "Falha ao carregar em resolução 360"; dirty = true; return@onGl }
                if (immersiveTex != 0) Gl.deleteTexture(immersiveTex)
                immersiveTex = Gl.uploadBitmap(big, mipmap = true)
                big.recycle()
                host.showImmersive(SceneRenderer.Immersive(immersiveTex, false, null, layout, host.headYaw()))
                status = null; dirty = true
            }
        }
    }

    private fun exitImmersive() {
        if (immersiveTex == 0) return
        host.showImmersive(null)
        Gl.deleteTexture(immersiveTex); immersiveTex = 0
        dirty = true
    }

    private fun saveAsEnvironment() {
        status = "Salvando ambiente..."
        val it = item
        mediaIo.execute {
            val entry = try {
                it.open(host.activity).use { input -> HyperXRApp.instance.environments.importPanorama(it.name.substringBeforeLast('.'), input) }
            } catch (t: Throwable) { status = "Falha: ${t.message}"; dirty = true; null }
            if (entry != null) host.onGl {
                exitImmersive()
                host.selectEnvironment(entry)
                status = "Salvo em Ambientes e aplicado"
                dirty = true
            }
        }
    }

    override fun onPointerEvent(event: PointerEvent) {
        val px = event.u * pxW; val py = event.v * pxH
        if (zoom > 1f && immersiveTex == 0) when (event.phase) {
            PointerPhase.DOWN -> if (imageRect.contains(px, py)) { panning = true; lastX = px; lastY = py; return }
            PointerPhase.MOVE -> if (panning) { panX += px - lastX; panY += py - lastY; lastX = px; lastY = py; dirty = true; return }
            PointerPhase.UP, PointerPhase.CANCEL -> if (panning) { panning = false; return }
            else -> Unit
        }
        super.onPointerEvent(event)
    }

    override fun release() {
        exitImmersive()
        bmp?.recycle(); bmp = null
        super.release()
    }

    private companion object { val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
}

// ======================================================================= text viewer

/** Reads text files (first 1 MB): scroll by dragging the text or with the buttons, font size. */
class TextViewerPanel(private val host: MediaHost, private val file: File) : XrPanel(1.4f, 1.05f, title = file.name) {
    @Volatile private var text: String? = null
    @Volatile private var truncated = false
    private var layout: StaticLayout? = null
    private var layoutFor = -1f
    private var size = 28f
    private var scrollY = 0f
    private var area = RectF()
    private var dragging = false
    private var lastY = 0f
    private val mono = FileKinds.extension(file.name) !in setOf("txt", "md", "markdown", "srt", "vtt", "nfo")
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = XrTheme.text; typeface = if (mono) Typeface.MONOSPACE else XrTheme.regular }

    init {
        mediaIo.execute {
            text = try {
                val max = 1 shl 20
                file.inputStream().use { input ->
                    val buf = ByteArray(minOf(file.length(), max.toLong()).toInt())
                    var n = 0
                    while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                    truncated = file.length() > max
                    String(buf, 0, n, Charsets.UTF_8).replace("\t", "    ")
                }
            } catch (t: Throwable) { "Não foi possível ler: ${t.message}" }
            dirty = true
        }
    }

    override fun draw(c: PanelCanvas) {
        area = contentRect(c)
        addRegion(XrPanel.Region(area, "text", null))
        val t = text
        if (t == null) { c.label("Carregando...", area.left, area.top + 20f, 30f, XrTheme.textDim); return }
        if (layout == null || layoutFor != size) {
            paint.textSize = size
            layout = StaticLayout.Builder.obtain(t, 0, t.length, paint, area.width().toInt() - 24)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
            layoutFor = size
        }
        val l = layout!!
        val maxScroll = (l.height - area.height() + 20f).coerceAtLeast(0f)
        scrollY = scrollY.coerceIn(0f, maxScroll)
        c.canvas.save()
        c.canvas.clipRect(area)
        c.canvas.translate(area.left + 12f, area.top + 10f - scrollY)
        l.draw(c.canvas)
        c.canvas.restore()
        // scroll bar
        if (maxScroll > 0f) {
            val frac = area.height() / l.height
            val barH = (area.height() * frac).coerceAtLeast(40f)
            val top = area.top + (area.height() - barH) * (scrollY / maxScroll)
            bar.color = Color.argb(120, 255, 255, 255)
            c.canvas.drawRoundRect(RectF(area.right - 8f, top, area.right - 2f, top + barH), 3f, 3f, bar)
        }
        val info = "${l.lineCount} linhas • ${FileKinds.formatSize(file.length())}" + if (truncated) " • mostrando o primeiro 1 MB" else ""
        c.label(info, c.pad, c.h - 150f, 24f, XrTheme.textDim)
        val page = area.height() * 0.85f
        val btns = listOf<Pair<String, () -> Unit>>(
            "Início" to { scrollY = 0f }, "Subir" to { scrollY -= page }, "Descer" to { scrollY += page },
            "Fim" to { scrollY = maxScroll }, "A-" to { size = (size - 3f).coerceAtLeast(18f) }, "A+" to { size = (size + 3f).coerceAtMost(48f) })
        val gap = 10f
        val bw = (c.w - 2 * c.pad - (btns.size - 1) * gap) / btns.size
        btns.forEachIndexed { i, (label, act) -> c.button("t$i", label, c.pad + i * (bw + gap), c.h - 100f, bw, 80f) { act(); dirty = true } }
    }

    override fun onPointerEvent(event: PointerEvent) {
        val px = event.u * pxW; val py = event.v * pxH
        when (event.phase) {
            PointerPhase.DOWN -> if (area.contains(px, py)) { dragging = true; lastY = py; return }
            PointerPhase.MOVE -> if (dragging) { scrollY -= py - lastY; lastY = py; dirty = true; return }
            PointerPhase.UP, PointerPhase.CANCEL -> if (dragging) { dragging = false; return }
            else -> Unit
        }
        super.onPointerEvent(event)
    }

    private companion object { val bar = Paint(Paint.ANTI_ALIAS_FLAG) }
}

// ======================================================================= PDF viewer

/** PDF pages rendered with Android's PdfRenderer. */
class PdfViewerPanel(private val host: MediaHost, private val file: File) : XrPanel(1.2f, 1.25f, title = file.name) {
    private val worker = Executors.newSingleThreadExecutor()
    private var renderer: android.graphics.pdf.PdfRenderer? = null
    private var fd: ParcelFileDescriptor? = null
    @Volatile private var page = 0
    @Volatile private var pages = 0
    @Volatile private var bmp: Bitmap? = null
    @Volatile private var error: String? = null

    init {
        worker.execute {
            try {
                fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = android.graphics.pdf.PdfRenderer(fd!!).also { pages = it.pageCount }
                renderPage()
            } catch (t: Throwable) { error = "PDF não suportado: ${t.message}"; dirty = true }
        }
    }

    /** Worker thread (PdfRenderer is single-threaded). */
    private fun renderPage() {
        val r = renderer ?: return
        r.openPage(page).use { p ->
            val targetW = (pxW - 72) * 3 / 2
            val b = Bitmap.createBitmap(targetW, (targetW.toFloat() * p.height / p.width).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            b.eraseColor(Color.WHITE)
            p.render(b, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val old = bmp; bmp = b; old?.recycle()
        }
        dirty = true
    }

    override fun draw(c: PanelCanvas) {
        val area = contentRect(c)
        val b = bmp
        when {
            error != null -> c.label(error!!, area.left, area.top + 20f, 30f, XrTheme.danger)
            b == null -> c.label("Carregando...", area.left, area.top + 20f, 30f, XrTheme.textDim)
            else -> {
                val s = minOf(area.width() / b.width, area.height() / b.height)
                val w = b.width * s; val h = b.height * s
                c.image(b, area.centerX() - w / 2, area.top, w, h)
            }
        }
        val gap = 10f
        val bw = (c.w - 2 * c.pad - 2 * gap) / 3
        val y = c.h - 100f
        c.button("prev", "Anterior", c.pad, y, bw, 80f, enabled = page > 0) { page--; worker.execute { renderPage() }; dirty = true }
        c.label(if (pages > 0) "Página ${page + 1} de $pages" else "", c.pad + bw + gap + 30f, y + 22f, 30f, XrTheme.text)
        c.button("next", "Próxima", c.pad + 2 * (bw + gap), y, bw, 80f, enabled = page < pages - 1) { page++; worker.execute { renderPage() }; dirty = true }
    }

    override fun release() {
        worker.execute {
            try { renderer?.close(); fd?.close() } catch (_: Throwable) {}
            bmp?.recycle(); bmp = null
        }
        worker.shutdown()
        super.release()
    }
}

// ======================================================================= video / audio player

/**
 * Plays videos (flat in the panel, or 360 around the user) and audio with MediaPlayer. The
 * decoder renders into a SurfaceTexture that is drawn as an external texture, so frames never
 * pass through the CPU.
 */
class MediaPlayerPanel(private val host: MediaHost, private val items: List<MediaItem>, private var index: Int) :
    XrPanel(1.6f, 1.12f, title = items[index].name) {
    private var player: MediaPlayer? = null
    private var screen: VirtualScreen? = null
    @Volatile private var prepared = false
    @Volatile private var error: String? = null
    @Volatile private var videoW = 0
    @Volatile private var videoH = 0
    private var videoArea = RectF()
    private var seekRect = RectF()
    private var immersiveOn = false
    private var layout = StereoLayout.MONO
    private var loop = false
    private var tick = 0f
    private var autoImmersiveDone = false
    private val item get() = items[index]
    private val isVideo get() = item.kind == FileKind.VIDEO

    init { start() }

    private fun start() {
        title = item.name
        prepared = false; error = null; videoW = 0; videoH = 0; autoImmersiveDone = false
        val it = item
        val mp = MediaPlayer()
        player = mp
        try {
            if (it.file != null) mp.setDataSource(it.file.path) else mp.setDataSource(host.activity, it.uri!!)
            mp.setOnPreparedListener { p -> if (player === p) { prepared = true; p.start(); dirty = true } }
            mp.setOnErrorListener { _, what, extra -> error = "Não foi possível reproduzir ($what/$extra)"; dirty = true; true }
            mp.setOnVideoSizeChangedListener { _, w, h -> videoW = w; videoH = h; dirty = true }
            mp.setOnCompletionListener { dirty = true }
            mp.isLooping = loop
            screen?.surface?.let { s -> mp.setSurface(s) }
            mp.prepareAsync()
        } catch (t: Throwable) { error = "Falha ao abrir: ${t.message}" }
        if (it.kind == FileKind.VIDEO) mediaIo.execute { it.detectSphere(host.activity); dirty = true }
    }

    private fun stopPlayer() {
        player?.let { p -> try { p.setSurface(null); p.release() } catch (_: Throwable) {} }
        player = null
    }

    private fun go(delta: Int) {
        if (items.size < 2) return
        stopPlayer()
        if (immersiveOn) setImmersive(false)
        index = (index + delta + items.size) % items.size
        start(); dirty = true
    }

    private fun setImmersive(on: Boolean) {
        val s = screen ?: return
        immersiveOn = on
        host.showImmersive(if (on) SceneRenderer.Immersive(s.texture, true, s.texMatrix, layout, host.headYaw()) else null)
        dirty = true
    }

    override fun update(dt: Float) {
        tick += dt
        if (tick > 0.5f) { tick = 0f; if (player?.isPlaying == true) dirty = true }
        // 360 videos tagged by the camera open around the user straight away
        val sp = item.sphere
        if (!autoImmersiveDone && sp != null && screen?.texture != 0 && screen != null) {
            autoImmersiveDone = true
            if (sp.renderable && sp.fromMetadata) { layout = sp.stereo; setImmersive(true) }
        }
    }

    override fun submit(scene: FrameScene) {
        if (isVideo) {
            val s = screen ?: VirtualScreen(1920, 1080).also { sc ->
                sc.onSurface = { surf -> player?.setSurface(surf) }
                screen = sc
            }
            s.ensure()
            s.latch()
        }
        super.submit(scene)
        if (!visible || !isVideo || immersiveOn) return
        val s = screen ?: return
        if (!s.hasFrame || videoW == 0 || videoH == 0) return
        // fit the video into its area and draw it just in front of the panel
        val ppm = pixelsPerMeter
        val k = minOf(videoArea.width() / videoW, videoArea.height() / videoH)
        val wM = videoW * k / ppm; val hM = videoH * k / ppm
        val cx = (videoArea.centerX() - pxW / 2f) / ppm
        val cy = (pxH / 2f - videoArea.centerY()) / ppm
        val p = scene.addPanel()
        p.texture = s.texture; p.oes = true; p.texMatrix = s.texMatrix
        p.pose.set(pose)
        p.pose.position.add(pose.orientation.rotate(Vec3(cx, cy, 0.004f)))
        p.width = wM; p.height = hM
        p.radius = 0.012f
    }

    override fun draw(c: PanelCanvas) {
        val mp = player
        videoArea = RectF(c.pad, c.y, c.w - c.pad, c.h - 230f)
        val sp = item.sphere
        if (!isVideo) {
            c.label(item.name, videoArea.left + 20f, videoArea.top + 60f, 44f, XrTheme.text, bold = true)
            c.label("Áudio" + if (item.sizeBytes > 0) " • ${FileKinds.formatSize(item.sizeBytes)}" else "", videoArea.left + 20f, videoArea.top + 130f, 28f, XrTheme.textDim)
        } else if (immersiveOn) {
            c.label("Vídeo 360 ao seu redor. Olhe em volta.\n${layoutName(layout)}", videoArea.left + 20f, videoArea.top + 40f, 34f, XrTheme.text)
        } else {
            fill.color = Color.BLACK
            c.canvas.drawRoundRect(videoArea, 18f, 18f, fill)
        }
        error?.let { c.label(it, videoArea.left + 20f, videoArea.bottom - 60f, 28f, XrTheme.danger) }
        if (!prepared && error == null) c.label("Carregando...", videoArea.left + 20f, videoArea.bottom - 60f, 28f, XrTheme.textDim)
        // progress
        val dur = if (prepared) (mp?.duration ?: 0) else 0
        val pos = if (prepared) (mp?.currentPosition ?: 0) else 0
        val barTop = c.h - 210f
        seekRect = RectF(c.pad, barTop, c.w - c.pad, barTop + 44f)
        fill.color = XrTheme.button
        c.canvas.drawRoundRect(RectF(seekRect.left, seekRect.centerY() - 6f, seekRect.right, seekRect.centerY() + 6f), 6f, 6f, fill)
        if (dur > 0) {
            val x = seekRect.left + seekRect.width() * pos / dur
            fill.color = XrTheme.accent
            c.canvas.drawRoundRect(RectF(seekRect.left, seekRect.centerY() - 6f, x, seekRect.centerY() + 6f), 6f, 6f, fill)
            fill.color = Color.WHITE
            c.canvas.drawCircle(x, seekRect.centerY(), 14f, fill)
            addRegion(XrPanel.Region(seekRect, "seek", {
                val f = ((hoverPx - seekRect.left) / seekRect.width()).coerceIn(0f, 1f)
                player?.seekTo((f * dur).toInt()); dirty = true
            }))
        }
        c.label("${formatTime(pos.toLong())} / ${formatTime(dur.toLong())}", c.pad, barTop + 50f, 26f, XrTheme.textDim)
        if (sp?.spherical == true) c.label(if (sp.fromMetadata) "Vídeo 360 (metadados)" else "Provável 360 (proporção 2:1)", c.w / 2, barTop + 50f, 26f, XrTheme.textDim)
        // controls
        val btns = ArrayList<Triple<String, String, () -> Unit>>()
        if (items.size > 1) btns += Triple("prev", "Anterior") { go(-1) }
        btns += Triple("back10", "-10 s") { mp?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) } }
        btns += Triple("play", if (mp?.isPlaying == true) "Pausar" else "Reproduzir") { mp?.let { if (it.isPlaying) it.pause() else if (prepared) it.start() } }
        btns += Triple("fwd10", "+10 s") { mp?.let { it.seekTo((it.currentPosition + 10_000).coerceAtMost(it.duration)) } }
        btns += Triple("loop", if (loop) "Repetir: sim" else "Repetir: não") { loop = !loop; mp?.isLooping = loop }
        if (isVideo && sp?.renderable == true) {
            btns += Triple("360", if (immersiveOn) "Sair do 360" else "Ver em 360") { setImmersive(!immersiveOn) }
            if (immersiveOn) btns += Triple("stereo", layoutName(layout)) { layout = nextLayout(layout); host.immersive?.layout = layout }
        } else if (isVideo && sp?.spherical == true) {
            c.label("Projeção ${sp.projection} ainda não suportada", c.w / 2, barTop + 80f, 22f, XrTheme.textDim)
        }
        if (items.size > 1) btns += Triple("next", "Próximo") { go(1) }
        val gap = 10f
        val bw = (c.w - 2 * c.pad - (btns.size - 1) * gap) / btns.size
        btns.forEachIndexed { i, (id, label, act) -> c.button(id, label, c.pad + i * (bw + gap), c.h - 100f, bw, 80f, accent = id == "play") { act(); dirty = true } }
    }

    override fun release() {
        if (immersiveOn) host.showImmersive(null)
        immersiveOn = false
        stopPlayer()
        screen?.release(); screen = null
        super.release()
    }

    private companion object { val fill = Paint(Paint.ANTI_ALIAS_FLAG) }
}

/** Shared by files and gallery: open the right viewer for [items][index]. */
fun openViewer(host: MediaHost, items: List<MediaItem>, index: Int): XrPanel? {
    val it = items[index]
    return when (it.kind) {
        FileKind.IMAGE -> {
            val list = items.filter { m -> m.kind == FileKind.IMAGE }
            ImageViewerPanel(host, list, list.indexOf(it).coerceAtLeast(0))
        }
        FileKind.VIDEO, FileKind.AUDIO -> {
            val list = items.filter { m -> m.kind == it.kind }
            MediaPlayerPanel(host, list, list.indexOf(it).coerceAtLeast(0))
        }
        FileKind.TEXT -> it.file?.let { f -> TextViewerPanel(host, f) }
        FileKind.PDF -> it.file?.let { f -> PdfViewerPanel(host, f) }
        else -> null
    }
}

internal fun dateText(ms: Long): String = if (ms <= 0) "" else SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(ms))

internal fun logFiles(msg: String) = Diagnostics.log("Arquivos: $msg")
