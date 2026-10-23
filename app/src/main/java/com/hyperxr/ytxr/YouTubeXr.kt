package com.hyperxr.ytxr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.KeyEvent
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.files.MediaHost
import com.hyperxr.math.Vec3
import com.hyperxr.media.StereoLayout
import com.hyperxr.rendering.FrameScene
import com.hyperxr.rendering.SceneRenderer
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme
import com.hyperxr.windows.KeyTarget
import com.hyperxr.windows.VirtualScreen
import com.hyperxr.youtube.XrYouTube
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * YouTube for XR, without the YouTube web page or app: HyperXR fetches categories, search
 * results, video details and the video/audio streams itself (NewPipe Extractor, built from
 * source) and draws everything as its own spatial panels; videos play in HyperXR's player on a
 * cinema screen in front of the user, or all around for 360 videos.
 */
private val ytIo = Executors.newFixedThreadPool(3)
private val main = Handler(Looper.getMainLooper())

private fun init() = XrYouTube.init("pt", "BR")

/** Thumbnails, decoded small and cached. */
private object Thumbs {
    private val cache = LruCache<String, Bitmap>(120)
    private val loading = HashSet<String>()
    fun get(url: String, onReady: () -> Unit): Bitmap? {
        if (url.isEmpty()) return null
        cache.get(url)?.let { return it }
        synchronized(loading) { if (!loading.add(url)) return null }
        ytIo.execute {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 15000
                val bytes = c.inputStream.use { it.readBytes() }
                val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { cache.put(url, it); onReady() }
            } catch (_: Throwable) {
            } finally { synchronized(loading) { loading.remove(url) } }
        }
        return null
    }
}

private fun formatDuration(s: Long): String = if (s <= 0) "" else if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)

private fun formatCount(n: Long): String = when {
    n < 0 -> ""
    n >= 1_000_000_000 -> "%.1f bi".format(n / 1e9)
    n >= 1_000_000 -> "%.1f mi".format(n / 1e6)
    n >= 1_000 -> "%.0f mil".format(n / 1e3)
    else -> n.toString()
}

private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

/** Draws [b] covering [r] (centre crop), with rounded corners approximated by a dark frame. */
private fun drawThumb(c: PanelCanvas, b: Bitmap?, r: RectF) {
    fill.color = Color.rgb(24, 24, 27)
    c.canvas.drawRoundRect(r, 16f, 16f, fill)
    if (b == null) return
    val k = maxOf(r.width() / b.width, r.height() / b.height)
    val sw = r.width() / k; val sh = r.height() / k
    val sx = (b.width - sw) / 2; val sy = (b.height - sh) / 2
    c.canvas.save(); c.canvas.clipRect(r)
    c.canvas.drawBitmap(b, android.graphics.Rect(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt()), r, bmpPaint)
    c.canvas.restore()
}

// ============================================================================ browse
class YouTubePanel(private val host: MediaHost) : XrPanel(1.9f, 1.18f, title = "YouTube XR"), KeyTarget {
    private var categories: List<Array<String>> = emptyList()
    private var tab = "search"
    @Volatile private var results: XrYouTube.Results? = null
    private val videos = ArrayList<XrYouTube.Video>()
    @Volatile private var status: String? = "Carregando…"
    @Volatile private var busy = false
    private var page = 0
    private val query = StringBuilder()
    private var editing = false
    private var lastQuery = ""

    init {
        ytIo.execute {
            try {
                init()
                categories = XrYouTube.categories().filter { it[0] != "Trending" }
                val first = categories.firstOrNull { it[0].contains("music", true) } ?: categories.firstOrNull()
                if (first != null) host.onGl { open(first[0]) } else status = null
            } catch (t: Throwable) { status = "Sem conexão com o YouTube: ${t.message}" }
            dirty = true
        }
    }

    private fun open(id: String) {
        tab = id; page = 0; videos.clear(); results = null; dirty = true
        load { XrYouTube.category(id) }
    }

    private fun search(q: String) {
        if (q.isBlank()) return
        tab = "search"; lastQuery = q; page = 0; videos.clear(); results = null; dirty = true
        load { XrYouTube.search(q) }
    }

    private fun load(block: () -> XrYouTube.Results) {
        busy = true; status = "Carregando…"; dirty = true
        ytIo.execute {
            try {
                val r = block()
                host.onGl { results = r; videos.addAll(r.videos); status = if (videos.isEmpty()) "Nada encontrado" else null; busy = false; dirty = true }
            } catch (t: Throwable) {
                Diagnostics.log("YouTube XR: ${t.message}")
                status = "Falha: ${t.message}"; busy = false; dirty = true
            }
        }
    }

    private fun more() {
        val r = results ?: return
        if (busy || !r.hasMore()) return
        busy = true; status = "Carregando mais…"; dirty = true
        ytIo.execute {
            try {
                val n = XrYouTube.more(r)
                host.onGl { results = n; videos.addAll(n.videos); status = null; busy = false; page++; dirty = true }
            } catch (t: Throwable) { status = "Falha: ${t.message}"; busy = false; dirty = true }
        }
    }

    // search box typing (the XR keyboard sends here)
    override fun typeText(text: String) { query.append(text); dirty = true }
    override fun sendKey(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> if (query.isNotEmpty()) query.setLength(query.length - 1)
            KeyEvent.KEYCODE_ENTER -> { editing = false; host.closeKeyboard(); search(query.toString()) }
        }
        dirty = true
    }

    override fun draw(c: PanelCanvas) {
        // tabs: search + YouTube's own categories
        var x = c.pad
        val tabW = 250f
        c.button("tab:search", "Buscar", x, c.y, tabW, 64f, accent = tab == "search") {
            editing = true; tab = "search"; host.openKeyboardFor(this); dirty = true
        }
        x += tabW + 10f
        for (cat in categories) {
            if (x + tabW > c.w - c.pad) break
            c.button("tab:${cat[0]}", cat[1], x, c.y, tabW, 64f, accent = tab == cat[0]) { open(cat[0]) }
            x += tabW + 10f
        }
        c.y += 80f
        if (tab == "search") {
            val box = RectF(c.pad, c.y, c.w - c.pad - 230f, c.y + 64f)
            fill.color = XrTheme.button
            c.canvas.drawRoundRect(box, 20f, 20f, fill)
            val shown = if (query.isEmpty() && !editing) "Toque para buscar no YouTube" else query.toString() + if (editing) "|" else ""
            c.label(shown, box.left + 20f, box.top + 14f, 30f, if (query.isEmpty()) XrTheme.textDim else XrTheme.text)
            addRegion(Region(box, "searchbox", { editing = true; host.openKeyboardFor(this); dirty = true }))
            c.button("go", "Buscar", c.w - c.pad - 210f, c.y, 210f, 64f, accent = true) { editing = false; host.closeKeyboard(); search(query.toString()) }
            c.y += 80f
        }
        status?.let { c.paragraph(it, 26f, XrTheme.textDim) }
        // grid: 4 x 2 per page
        val cols = 4; val rows = 2; val per = cols * rows
        val gap = 22f
        val cw = (c.w - 2 * c.pad - (cols - 1) * gap) / cols
        val thumbH = cw * 9f / 16f
        val ch = thumbH + 96f
        val top = c.y
        val pages = maxOf(1, (videos.size + per - 1) / per)
        page = page.coerceIn(0, pages - 1)
        videos.drop(page * per).take(per).forEachIndexed { i, v ->
            val cx = c.pad + (i % cols) * (cw + gap)
            val cy = top + (i / cols) * (ch + gap)
            val tr = RectF(cx, cy, cx + cw, cy + thumbH)
            drawThumb(c, Thumbs.get(v.thumbnail) { dirty = true }, tr)
            val badge = if (v.live) "AO VIVO" else formatDuration(v.durationS)
            if (badge.isNotEmpty()) {
                fill.color = if (v.live) Color.rgb(204, 0, 0) else Color.argb(210, 0, 0, 0)
                val bw = badge.length * 15f + 24f
                c.canvas.drawRoundRect(RectF(tr.right - bw - 10f, tr.bottom - 46f, tr.right - 10f, tr.bottom - 10f), 8f, 8f, fill)
                c.label(badge, tr.right - bw - 0f, tr.bottom - 44f, 24f, Color.WHITE)
            }
            if (isHovered("v$i")) { fill.color = Color.argb(60, 255, 255, 255); c.canvas.drawRoundRect(tr, 16f, 16f, fill) }
            c.label(v.title, cx, tr.bottom + 8f, 26f, XrTheme.text, bold = true, maxWidth = cw)
            c.label(listOf(v.channel, if (v.views > 0) "${formatCount(v.views)} visualizações" else "").filter { it.isNotEmpty() }.joinToString(" • "),
                cx, tr.bottom + 62f, 22f, XrTheme.textDim, maxWidth = cw)
            addRegion(Region(RectF(cx, cy, cx + cw, cy + ch), "v$i", { play(v) }))
        }
        val by = c.h - 96f
        c.button("prev", "Anterior", c.pad, by, 220f, 76f, enabled = page > 0) { page--; dirty = true }
        c.label("${page + 1} / $pages", c.w / 2 - 40f, by + 20f, 28f, XrTheme.textDim)
        val canMore = page < pages - 1 || (results?.hasMore() == true)
        c.button("next", "Próxima", c.w - c.pad - 220f, by, 220f, 76f, enabled = canMore) {
            if (page < pages - 1) { page++; dirty = true } else more()
        }
    }

    private fun play(v: XrYouTube.Video) { host.openPanel(YouTubePlayerPanel(host, v), 1.6f) }

    override fun release() { if (editing) host.closeKeyboard(); super.release() }
}

// ============================================================================ player
/**
 * The cinema: the video on a large screen in front of the user (or around them in 360), title,
 * channel and counts, quality from 144p up to what the video has (4K/8K, 60 fps), and the related
 * videos to go on watching. Video and audio come as separate streams and are played together.
 */
class YouTubePlayerPanel(private val host: MediaHost, private var video: XrYouTube.Video) :
    XrPanel(1.9f, 1.5f, title = "YouTube XR") {
    private var details: XrYouTube.Details? = null
    private var qualityIndex = 0
    private var player: ExoPlayer? = null
    private var screen: VirtualScreen? = null
    @Volatile private var status: String? = "Carregando…"
    @Volatile private var playing = false
    @Volatile private var positionMs = 0L
    @Volatile private var durationMs = 0L
    @Volatile private var videoW = 0
    @Volatile private var videoH = 0
    private var videoArea = RectF()
    private var seekRect = RectF()
    private var immersiveOn = false
    private var layout = StereoLayout.MONO
    private var tick = 0f
    private var relatedPage = 0

    init { load(video) }

    private fun load(v: XrYouTube.Video) {
        video = v; title = v.title; details = null; status = "Carregando…"; dirty = true
        stop()
        ytIo.execute {
            try {
                init()
                val d = XrYouTube.details(v.url)
                host.onGl {
                    details = d
                    // start at 1080p (or the best below it): 4K is one click away and needs more bandwidth
                    qualityIndex = d.qualities.indexOfFirst { it.height <= 1080 }.let { if (it < 0) 0 else it }
                    status = if (d.qualities.isEmpty() && d.hlsUrl.isEmpty()) "Sem vídeo reproduzível" else null
                    start(0L)
                    dirty = true
                }
            } catch (t: Throwable) {
                Diagnostics.log("YouTube XR details: ${t.message}")
                status = "Não foi possível abrir: ${t.message}"; dirty = true
            }
        }
    }

    private fun source(url: String): MediaSource {
        val http = DefaultHttpDataSource.Factory().setUserAgent(XrYouTube.userAgentFor(url)).setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000).setReadTimeoutMs(30000)
        return ProgressiveMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(url))
    }

    /** (Re)starts playback of the current quality at [atMs]. */
    private fun start(atMs: Long) {
        val d = details ?: return
        val surface = screen?.surface
        val src: MediaSource = when {
            d.live && d.hlsUrl.isNotEmpty() -> HlsMediaSource.Factory(DefaultHttpDataSource.Factory().setUserAgent(XrYouTube.userAgentFor(d.hlsUrl)))
                .createMediaSource(MediaItem.fromUri(d.hlsUrl))
            d.qualities.isEmpty() -> return
            else -> {
                val q = d.qualities[qualityIndex.coerceIn(0, d.qualities.size - 1)]
                if (q.audioUrl != null) MergingMediaSource(true, source(q.videoUrl), source(q.audioUrl)) else source(q.videoUrl)
            }
        }
        main.post {
            val p = player ?: ExoPlayer.Builder(host.activity).setLooper(Looper.getMainLooper()).build().also { p ->
                player = p
                p.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying; dirty = true }
                    override fun onVideoSizeChanged(size: VideoSize) { videoW = size.width; videoH = size.height; dirty = true }
                    override fun onPlayerError(error: PlaybackException) {
                        Diagnostics.log("YouTube XR playback: ${error.errorCodeName} ${error.message}")
                        status = "Erro ao reproduzir (${error.errorCodeName})"; dirty = true
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_BUFFERING) { status = "Carregando…"; dirty = true }
                        if (state == Player.STATE_READY) { status = null; dirty = true }
                    }
                })
                poll()
            }
            surface?.let { p.setVideoSurface(it) }
            p.setMediaSource(src)
            p.prepare()
            if (atMs > 0) p.seekTo(atMs)
            p.playWhenReady = true
        }
    }

    /** Reads position/duration on the player's thread a few times a second. */
    private fun poll() {
        val p = player ?: return
        positionMs = p.currentPosition
        durationMs = if (p.duration == C.TIME_UNSET) 0L else p.duration
        main.postDelayed({ poll() }, 250)
    }

    private fun stop() {
        val p = player ?: return
        player = null
        main.post { runCatching { p.clearVideoSurface(); p.release() } }
    }

    private fun setQuality(i: Int) {
        val d = details ?: return
        if (i == qualityIndex || d.qualities.isEmpty()) return
        qualityIndex = i.coerceIn(0, d.qualities.size - 1)
        val at = positionMs
        main.post { player?.stop() }
        start(at)
        dirty = true
    }

    private fun setImmersive(on: Boolean) {
        val s = screen ?: return
        immersiveOn = on
        host.showImmersive(if (on) SceneRenderer.Immersive(s.texture, true, s.texMatrix, layout, host.headYaw()) else null)
        dirty = true
    }

    override fun update(dt: Float) {
        tick += dt
        if (tick > 0.5f) { tick = 0f; if (playing) dirty = true }
    }

    override fun submit(scene: FrameScene) {
        val s = screen ?: VirtualScreen(3840, 2160).also { sc ->
            sc.onSurface = { surf -> main.post { player?.setVideoSurface(surf) } }
            screen = sc
        }
        s.ensure(); s.latch()
        super.submit(scene)
        if (!visible || immersiveOn || !s.hasFrame || videoW == 0 || videoH == 0) return
        // the picture just in front of the panel, fitted into its area
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
        p.radius = 0.014f
    }

    override fun draw(c: PanelCanvas) {
        val d = details
        val sideW = 520f
        videoArea = RectF(c.pad, c.y, c.w - c.pad - sideW - 24f, c.y + (c.w - 2 * c.pad - sideW - 24f) * 9f / 16f)
        fill.color = Color.BLACK
        c.canvas.drawRoundRect(videoArea, 18f, 18f, fill)
        if (immersiveOn) c.label("Vídeo 360 ao seu redor. Olhe em volta.", videoArea.left + 30f, videoArea.top + 40f, 34f, XrTheme.text)
        status?.let { c.label(it, videoArea.left + 30f, videoArea.bottom - 70f, 28f, XrTheme.textDim) }

        // progress
        var y = videoArea.bottom + 14f
        val live = d?.live == true
        seekRect = RectF(videoArea.left, y, videoArea.right, y + 40f)
        fill.color = XrTheme.button
        c.canvas.drawRoundRect(RectF(seekRect.left, seekRect.centerY() - 5f, seekRect.right, seekRect.centerY() + 5f), 5f, 5f, fill)
        if (durationMs > 0 && !live) {
            val px = seekRect.left + seekRect.width() * positionMs / durationMs
            fill.color = Color.rgb(230, 33, 23)
            c.canvas.drawRoundRect(RectF(seekRect.left, seekRect.centerY() - 5f, px, seekRect.centerY() + 5f), 5f, 5f, fill)
            fill.color = Color.WHITE; c.canvas.drawCircle(px, seekRect.centerY(), 13f, fill)
            addRegion(Region(seekRect, "seek", {
                val f = ((hoverPx - seekRect.left) / seekRect.width()).coerceIn(0f, 1f)
                val to = (f * durationMs).toLong(); main.post { player?.seekTo(to) }
            }))
        }
        y += 46f
        c.label(if (live) "AO VIVO" else "${formatDuration(positionMs / 1000)} / ${formatDuration(durationMs / 1000)}", videoArea.left, y, 24f, XrTheme.textDim)
        y += 40f

        // controls
        val q = d?.qualities?.getOrNull(qualityIndex)
        val btns = ArrayList<Triple<String, String, () -> Unit>>()
        btns += Triple("back10", "-10 s") { main.post { player?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) } } }
        btns += Triple("play", if (playing) "Pausar" else "Reproduzir") { main.post { player?.let { it.playWhenReady = !it.playWhenReady } } }
        btns += Triple("fwd10", "+10 s") { main.post { player?.let { it.seekTo(it.currentPosition + 10_000) } } }
        if (d != null && d.qualities.size > 1) {
            btns += Triple("qup", "Qualidade +") { setQuality(qualityIndex - 1) }
            btns += Triple("qdown", "Qualidade -") { setQuality(qualityIndex + 1) }
        }
        btns += Triple("360", if (immersiveOn) "Sair do 360" else "Ver em 360") { setImmersive(!immersiveOn) }
        if (immersiveOn) btns += Triple("stereo", when (layout) { StereoLayout.MONO -> "Mono"; StereoLayout.TOP_BOTTOM -> "3D cima/baixo"; StereoLayout.LEFT_RIGHT -> "3D lado a lado" }) {
            layout = StereoLayout.entries[(layout.ordinal + 1) % StereoLayout.entries.size]; host.immersive?.layout = layout
        }
        val gap = 10f
        val bw = (videoArea.width() - (btns.size - 1) * gap) / btns.size
        btns.forEachIndexed { i, (id, label, act) -> c.button(id, label, videoArea.left + i * (bw + gap), y, bw, 76f, accent = id == "play") { act(); dirty = true } }
        y += 92f

        // title and info
        c.label(video.title, videoArea.left, y, 34f, XrTheme.text, bold = true, maxWidth = videoArea.width())
        y += 56f
        val info = buildList {
            add(d?.channel ?: video.channel)
            if ((d?.views ?: 0) > 0) add("${formatCount(d!!.views)} visualizações")
            if ((d?.likes ?: 0) > 0) add("${formatCount(d!!.likes)} curtidas")
            q?.let { add("${it.label}${if (it.audioUrl != null) "" else " (áudio junto)"}") }
            if (d?.spherical == true) add("vídeo 360")
        }.filter { it.isNotEmpty() }.joinToString(" • ")
        c.label(info, videoArea.left, y, 24f, XrTheme.textDim, maxWidth = videoArea.width())

        // related videos on the right
        val rx = c.w - c.pad - sideW
        var ry = c.y
        c.label("A seguir", rx, ry, 28f, XrTheme.text, bold = true)
        ry += 46f
        val related = d?.related.orEmpty()
        val per = 5
        val pages = maxOf(1, (related.size + per - 1) / per)
        relatedPage = relatedPage.coerceIn(0, pages - 1)
        val rowH = 150f
        related.drop(relatedPage * per).take(per).forEachIndexed { i, v ->
            val top = ry + i * (rowH + 12f)
            val tr = RectF(rx, top, rx + rowH * 16f / 9f, top + rowH)
            drawThumb(c, Thumbs.get(v.thumbnail) { dirty = true }, tr)
            c.label(v.title, tr.right + 12f, top + 4f, 22f, XrTheme.text, bold = true, maxWidth = c.w - c.pad - tr.right - 12f)
            c.label(v.channel, tr.right + 12f, top + 96f, 20f, XrTheme.textDim, maxWidth = c.w - c.pad - tr.right - 12f)
            if (isHovered("r$i")) { fill.color = Color.argb(50, 255, 255, 255); c.canvas.drawRoundRect(RectF(rx, top, c.w - c.pad, top + rowH), 14f, 14f, fill) }
            addRegion(Region(RectF(rx, top, c.w - c.pad, top + rowH), "r$i", {
                if (immersiveOn) setImmersive(false)
                relatedPage = 0; load(v)
            }))
        }
        val by = ry + per * (rowH + 12f)
        c.button("rprev", "‹", rx, by, 120f, 70f, enabled = relatedPage > 0) { relatedPage--; dirty = true }
        c.button("rnext", "›", c.w - c.pad - 120f, by, 120f, 70f, enabled = relatedPage < pages - 1) { relatedPage++; dirty = true }
    }

    override fun release() {
        if (immersiveOn) host.showImmersive(null)
        immersiveOn = false
        stop()
        screen?.release(); screen = null
        super.release()
    }
}
