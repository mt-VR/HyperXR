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

// ---------------------------------------------------------------------------- YouTube VR look
/** Colours of YouTube's dark theme, as in YouTube VR. */
private object Yt {
    val bg = Color.argb(246, 15, 15, 15)
    val surface = Color.rgb(39, 39, 39)
    val surfaceHover = Color.rgb(63, 63, 63)
    val text = Color.rgb(241, 241, 241)
    val text2 = Color.rgb(170, 170, 170)
    val red = Color.rgb(255, 0, 0)
    val chipOn = Color.rgb(241, 241, 241)
    val chipOnText = Color.rgb(15, 15, 15)
}

/** White Material Symbols and the YouTube logo, rendered into drawable-nodpi (yt_*.png). */
private object YtIcons {
    private val cache = HashMap<String, Bitmap?>()
    fun get(ctx: android.content.Context, name: String): Bitmap? = cache.getOrPut(name) {
        val id = ctx.resources.getIdentifier("yt_$name", "drawable", ctx.packageName)
        if (id == 0) null else BitmapFactory.decodeResource(ctx.resources, id, BitmapFactory.Options().apply { inScaled = false })
    }
}

private val circlePath = android.graphics.Path()

/** YouTube's dark panel with large rounded corners. */
private fun ytBackground(c: PanelCanvas) {
    fill.shader = null; fill.color = Yt.bg
    c.canvas.drawRoundRect(RectF(0f, 0f, c.w, c.h), 56f, 56f, fill)
    c.y = 0f
}

/** A round icon button (hover = grey disc), YouTube style. */
private fun XrPanel.iconButton(c: PanelCanvas, id: String, icon: Bitmap?, cx: Float, cy: Float, r: Float, on: Boolean = false, action: () -> Unit) {
    val hov = isHovered(id) || isPressed(id)
    if (hov || on) { fill.color = if (on) Yt.chipOn else Yt.surfaceHover; c.canvas.drawCircle(cx, cy, r, fill) }
    icon?.let {
        val s = r * 1.1f
        val p = if (on) Paint(bmpPaint).apply { colorFilter = android.graphics.PorterDuffColorFilter(Yt.chipOnText, android.graphics.PorterDuff.Mode.SRC_IN) } else bmpPaint
        c.canvas.drawBitmap(it, null, RectF(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2), p)
    }
    addRegion(XrPanel.Region(RectF(cx - r, cy - r, cx + r, cy + r), id, action))
}

/** A pill chip (category, quality): white when selected, grey otherwise. */
private fun XrPanel.chip(c: PanelCanvas, id: String, label: String, x: Float, y: Float, on: Boolean, height: Float = 64f, action: () -> Unit): Float {
    val tp = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 28f; typeface = XrTheme.medium }
    val w = tp.measureText(label) + 56f
    val r = RectF(x, y, x + w, y + height)
    fill.color = if (on) Yt.chipOn else if (isHovered(id)) Yt.surfaceHover else Yt.surface
    c.canvas.drawRoundRect(r, height / 2.5f, height / 2.5f, fill)
    tp.color = if (on) Yt.chipOnText else Yt.text
    c.canvas.drawText(label, x + 28f, y + height / 2 + 10f, tp)
    addRegion(XrPanel.Region(r, id, action))
    return w
}

/** Channel avatar in a circle (grey disc until it is loaded). */
private fun avatar(c: PanelCanvas, b: Bitmap?, cx: Float, cy: Float, r: Float) {
    fill.color = Yt.surface; c.canvas.drawCircle(cx, cy, r, fill)
    if (b == null) return
    c.canvas.save()
    circlePath.reset(); circlePath.addCircle(cx, cy, r, android.graphics.Path.Direction.CW)
    c.canvas.clipPath(circlePath)
    c.canvas.drawBitmap(b, null, RectF(cx - r, cy - r, cx + r, cy + r), bmpPaint)
    c.canvas.restore()
}

/** "canal • 1,2 mi visualizações • há 2 dias" */
private fun meta(channel: String, views: Long, ago: String, live: Boolean) = buildList {
    if (channel.isNotEmpty()) add(channel)
    if (views > 0) add(if (live) "${formatCount(views)} assistindo" else "${formatCount(views)} visualizações")
    if (ago.isNotEmpty()) add(ago)
}.joinToString(" • ")
private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

private val thumbPath = android.graphics.Path()

/** Draws [b] covering [r] (centre crop) with YouTube's rounded thumbnail corners. */
private fun drawThumb(c: PanelCanvas, b: Bitmap?, r: RectF, radius: Float = 24f) {
    fill.color = Color.rgb(39, 39, 39)
    c.canvas.drawRoundRect(r, radius, radius, fill)
    if (b == null) return
    val k = maxOf(r.width() / b.width, r.height() / b.height)
    val sw = r.width() / k; val sh = r.height() / k
    val sx = (b.width - sw) / 2; val sy = (b.height - sh) / 2
    c.canvas.save()
    thumbPath.reset(); thumbPath.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW)
    c.canvas.clipPath(thumbPath)
    c.canvas.drawBitmap(b, android.graphics.Rect(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt()), r, bmpPaint)
    c.canvas.restore()
}

/** Duration (or LIVE) badge in the thumbnail's corner. */
private fun badge(c: PanelCanvas, r: RectF, v: XrYouTube.Video) {
    val text = if (v.live) "AO VIVO" else formatDuration(v.durationS)
    if (text.isEmpty()) return
    val tp = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f; typeface = XrTheme.medium; color = Color.WHITE }
    val w = tp.measureText(text) + 20f
    val b = RectF(r.right - w - 12f, r.bottom - 46f, r.right - 12f, r.bottom - 12f)
    fill.color = if (v.live) Color.rgb(204, 0, 0) else Color.argb(204, 0, 0, 0)
    c.canvas.drawRoundRect(b, 8f, 8f, fill)
    c.canvas.drawText(text, b.left + 10f, b.bottom - 9f, tp)
}

// ============================================================================ browse
/**
 * The home screen, laid out like YouTube VR: a navigation rail on the left (Home, Explore,
 * Search and YouTube's categories), category chips across the top, and a grid of large video
 * cards: rounded thumbnail with the duration, channel avatar, title on two lines and
 * "channel • views • when" underneath.
 */
class YouTubePanel(private val host: MediaHost) : XrPanel(1.9f, 1.28f, title = ""), KeyTarget {
    init { closable = false }
    private var categories: List<Array<String>> = emptyList()
    private var tab = "home"
    @Volatile private var results: XrYouTube.Results? = null
    private val videos = ArrayList<XrYouTube.Video>()
    @Volatile private var status: String? = "Carregando…"
    @Volatile private var busy = false
    private var page = 0
    private val query = StringBuilder()
    private var editing = false
    private val ctx get() = host.activity

    init {
        ytIo.execute {
            try {
                init()
                categories = XrYouTube.categories().filter { it[0] != "Trending" }
                host.onGl { home() }
            } catch (t: Throwable) { status = "Sem conexão com o YouTube: ${t.message}" }
            dirty = true
        }
    }

    override fun paintBackground(c: PanelCanvas) = ytBackground(c)

    /** Home: YouTube's music, gaming and live picks, like the home feed of a signed-out user. */
    private fun home() {
        tab = "home"; page = 0; videos.clear(); results = null; dirty = true
        load { XrYouTube.category(categories.firstOrNull { it[0].contains("music", true) }?.get(0) ?: categories.first()[0]) }
    }

    private fun open(id: String) {
        tab = id; page = 0; videos.clear(); results = null; dirty = true
        load { XrYouTube.category(id) }
    }

    private fun search(q: String) {
        if (q.isBlank()) return
        tab = "search"; page = 0; videos.clear(); results = null; dirty = true
        load { XrYouTube.search(q) }
    }

    private fun load(block: () -> XrYouTube.Results) {
        busy = true; status = "Carregando…"; dirty = true
        ytIo.execute {
            try {
                val r = block()
                host.onGl { results = r; videos.addAll(r.videos); status = if (videos.isEmpty()) "Nenhum resultado" else null; busy = false; dirty = true }
            } catch (t: Throwable) {
                Diagnostics.log("YouTube XR: ${t.message}")
                status = "Algo deu errado: ${t.message}"; busy = false; dirty = true
            }
        }
    }

    private fun more() {
        val r = results ?: return
        if (busy || !r.hasMore()) return
        busy = true; dirty = true
        ytIo.execute {
            try {
                val n = XrYouTube.more(r)
                host.onGl { results = n; videos.addAll(n.videos); busy = false; page++; dirty = true }
            } catch (t: Throwable) { status = "Algo deu errado: ${t.message}"; busy = false; dirty = true }
        }
    }

    override fun typeText(text: String) { query.append(text); dirty = true }
    override fun sendKey(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> if (query.isNotEmpty()) query.setLength(query.length - 1)
            KeyEvent.KEYCODE_ENTER -> { editing = false; host.closeKeyboard(); search(query.toString()) }
        }
        dirty = true
    }

    private fun startSearch() { editing = true; tab = "search"; host.openKeyboardFor(this); dirty = true }

    private fun railIcon(id: String) = when {
        id == "home" -> "home"; id == "explore" -> "explore"; id == "search" -> "search"
        id.contains("music", true) -> "music_note"; id.contains("gaming", true) -> "sports_esports"
        id.contains("live", true) -> "sensors"; id.contains("movie", true) -> "movie"; id.contains("podcast", true) -> "podcasts"
        else -> "local_fire_department"
    }

    override fun draw(c: PanelCanvas) {
        val railW = 200f
        // ---- navigation rail
        val logo = YtIcons.get(ctx, "logo")
        logo?.let { c.canvas.drawBitmap(it, null, RectF(44f, 40f, 44f + 92f, 40f + 92f), bmpPaint) }
        var ry = 190f
        val railItems = listOf("home" to "Início", "search" to "Buscar") + categories.map { it[0] to it[1] }
        for ((id, label) in railItems) {
            val on = tab == id
            val r = RectF(16f, ry, railW - 16f, ry + 118f)
            if (on || isHovered("rail:$id")) { fill.color = if (on) Yt.surface else Color.argb(255, 33, 33, 33); c.canvas.drawRoundRect(r, 24f, 24f, fill) }
            YtIcons.get(ctx, railIcon(id))?.let { c.canvas.drawBitmap(it, null, RectF(r.centerX() - 26f, ry + 16f, r.centerX() + 26f, ry + 68f), bmpPaint) }
            val tp = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; typeface = if (on) XrTheme.medium else XrTheme.regular; color = Yt.text; textAlign = Paint.Align.CENTER }
            c.canvas.drawText(if (label.length > 12) label.substringBefore(' ') else label, r.centerX(), ry + 100f, tp)
            addRegion(Region(r, "rail:$id", { when (id) { "home" -> home(); "search" -> startSearch(); else -> open(id) } }))
            ry += 128f
            if (ry > c.h - 130f) break
        }
        // ---- top bar: search box and close
        val left = railW + 30f
        val sb = RectF(left + 260f, 40f, c.w - 300f, 112f)
        fill.color = Color.rgb(18, 18, 18); c.canvas.drawRoundRect(sb, 36f, 36f, fill)
        fill.style = Paint.Style.STROKE; fill.strokeWidth = 2f; fill.color = Color.rgb(48, 48, 48); c.canvas.drawRoundRect(sb, 36f, 36f, fill); fill.style = Paint.Style.FILL
        YtIcons.get(ctx, "search")?.let { c.canvas.drawBitmap(it, null, RectF(sb.left + 24f, sb.top + 16f, sb.left + 64f, sb.top + 56f), bmpPaint) }
        val shown = if (query.isEmpty()) "Pesquisar" else query.toString() + if (editing) "|" else ""
        c.label(shown, sb.left + 84f, sb.top + 16f, 30f, if (query.isEmpty()) Yt.text2 else Yt.text, maxWidth = sb.width() - 100f, maxLines = 1)
        addRegion(Region(sb, "searchbox", { startSearch() }))
        iconButton(c, "close", YtIcons.get(ctx, "close"), c.w - 80f, 76f, 38f) { visible = false; onClose?.invoke() }
        // ---- chips
        var cx = left
        val chipY = 140f
        cx += chip(c, "chip:home", "Tudo", cx, chipY, tab == "home") { home() } + 16f
        for (cat in categories) {
            if (cx > c.w - 320f) break
            cx += chip(c, "chip:${cat[0]}", cat[1], cx, chipY, tab == cat[0]) { open(cat[0]) } + 16f
        }
        // ---- grid of cards (3 x 2), YouTube VR's large cards
        val top = chipY + 96f
        val cols = 3; val rows = 2; val per = cols * rows
        val gap = 36f
        val gridW = c.w - left - 50f
        val cw = (gridW - (cols - 1) * gap) / cols
        val thumbH = cw * 9f / 16f
        val ch = thumbH + 124f
        status?.let { c.label(it, left, top, 30f, Yt.text2) }
        val pages = maxOf(1, (videos.size + per - 1) / per)
        page = page.coerceIn(0, pages - 1)
        videos.drop(page * per).take(per).forEachIndexed { i, v ->
            val x = left + (i % cols) * (cw + gap)
            val y = top + (i / cols) * (ch + 10f)
            val tr = RectF(x, y, x + cw, y + thumbH)
            drawThumb(c, Thumbs.get(v.thumbnail) { dirty = true }, tr)
            badge(c, tr, v)
            if (isHovered("v$i")) { fill.color = Color.argb(40, 255, 255, 255); c.canvas.drawRoundRect(tr, 24f, 24f, fill) }
            avatar(c, Thumbs.get(v.avatar) { dirty = true }, x + 30f, tr.bottom + 46f, 30f)
            val tx = x + 80f
            c.label(v.title, tx, tr.bottom + 16f, 28f, Yt.text, bold = true, maxWidth = cw - 84f, maxLines = 2)
            c.label(meta(v.channel, v.views, v.ago, v.live), tx, tr.bottom + 92f, 22f, Yt.text2, maxWidth = cw - 84f, maxLines = 1)
            addRegion(Region(RectF(x, y, x + cw, y + ch), "v$i", { play(v) }))
        }
        // ---- paging
        val by = c.h - 60f
        iconButton(c, "prev", YtIcons.get(ctx, "chevron_left"), left + gridW / 2 - 90f, by, 34f) { if (page > 0) { page--; dirty = true } }
        val tp = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f; color = Yt.text2; textAlign = Paint.Align.CENTER }
        c.canvas.drawText(if (busy) "…" else "${page + 1}", left + gridW / 2, by + 8f, tp)
        iconButton(c, "next", YtIcons.get(ctx, "chevron_right"), left + gridW / 2 + 90f, by, 34f) {
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
    XrPanel(2.0f, 1.42f, title = "") {
    init { closable = false }
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
        video = v; details = null; status = "Carregando…"; dirty = true
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

    override fun paintBackground(c: PanelCanvas) = ytBackground(c)

    private var qualityMenu = false

    override fun draw(c: PanelCanvas) {
        val d = details
        val ctx = host.activity
        val sideW = 560f
        val pad = 44f
        // ---- the screen
        videoArea = RectF(pad, pad, c.w - pad - sideW - 36f, pad + (c.w - 2 * pad - sideW - 36f) * 9f / 16f)
        fill.color = Color.BLACK
        c.canvas.drawRoundRect(videoArea, 28f, 28f, fill)
        if (immersiveOn) c.label("Vídeo 360 ao seu redor. Olhe em volta.", videoArea.left + 40f, videoArea.top + 40f, 34f, Yt.text)
        status?.let { c.label(it, videoArea.left + 40f, videoArea.bottom - 80f, 28f, Yt.text2) }
        val live = d?.live == true

        // ---- progress: YouTube's red bar with its red scrubber
        var y = videoArea.bottom + 30f
        seekRect = RectF(videoArea.left, y - 16f, videoArea.right, y + 16f)
        fill.color = Color.argb(90, 255, 255, 255)
        c.canvas.drawRoundRect(RectF(seekRect.left, y - 4f, seekRect.right, y + 4f), 4f, 4f, fill)
        if (live) {
            fill.color = Yt.red; c.canvas.drawRoundRect(RectF(seekRect.left, y - 4f, seekRect.right, y + 4f), 4f, 4f, fill)
        } else if (durationMs > 0) {
            val px = seekRect.left + seekRect.width() * positionMs / durationMs
            fill.color = Yt.red
            c.canvas.drawRoundRect(RectF(seekRect.left, y - 4f, px, y + 4f), 4f, 4f, fill)
            c.canvas.drawCircle(px, y, if (isHovered("seek")) 16f else 12f, fill)
            addRegion(Region(seekRect, "seek", {
                val f = ((hoverPx - seekRect.left) / seekRect.width()).coerceIn(0f, 1f)
                val to = (f * durationMs).toLong(); main.post { player?.seekTo(to) }
            }))
        }
        // ---- control row: play, -10, +10, time ... 360, 3D, quality, close
        y += 62f
        var x = videoArea.left + 40f
        iconButton(c, "play", YtIcons.get(ctx, if (playing) "pause" else "play_arrow"), x, y, 40f) { main.post { player?.let { it.playWhenReady = !it.playWhenReady } } }
        x += 96f
        iconButton(c, "back10", YtIcons.get(ctx, "replay_10"), x, y, 36f) { main.post { player?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) } } }
        x += 88f
        iconButton(c, "fwd10", YtIcons.get(ctx, "forward_10"), x, y, 36f) { main.post { player?.let { it.seekTo(it.currentPosition + 10_000) } } }
        x += 70f
        val timeText = if (live) "AO VIVO" else "${formatDuration(positionMs / 1000).ifEmpty { "0:00" }} / ${formatDuration(durationMs / 1000)}"
        c.label(timeText, x, y - 16f, 26f, if (live) Yt.red else Yt.text, bold = live)
        var rx = videoArea.right - 40f
        val q = d?.qualities?.getOrNull(qualityIndex)
        iconButton(c, "quality", YtIcons.get(ctx, "settings"), rx, y, 36f, on = qualityMenu) { qualityMenu = !qualityMenu; dirty = true }
        rx -= 88f
        if (immersiveOn) {
            iconButton(c, "stereo", YtIcons.get(ctx, "3d_rotation"), rx, y, 36f, on = layout != StereoLayout.MONO) {
                layout = StereoLayout.entries[(layout.ordinal + 1) % StereoLayout.entries.size]; host.immersive?.layout = layout
            }
            rx -= 88f
        }
        iconButton(c, "360", YtIcons.get(ctx, "panorama_photosphere"), rx, y, 36f, on = immersiveOn) { setImmersive(!immersiveOn) }
        if (q != null) {
            val tp = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = Yt.text2; textAlign = Paint.Align.RIGHT }
            c.canvas.drawText(q.label, rx - 52f, y + 8f, tp)
        }
        // quality menu: chips above the control row, highest first (like YouTube's quality list)
        if (qualityMenu && d != null) {
            var qx = videoArea.left + 30f
            val qy = videoArea.bottom - 100f
            fill.color = Color.argb(230, 28, 28, 28)
            c.canvas.drawRoundRect(RectF(videoArea.left + 16f, qy - 16f, videoArea.right - 16f, qy + 80f), 24f, 24f, fill)
            d.qualities.forEachIndexed { i, qq ->
                if (qx < videoArea.right - 180f) qx += chip(c, "q$i", qq.label, qx, qy, i == qualityIndex, 64f) { setQuality(i); qualityMenu = false } + 12f
            }
        }
        // ---- title, channel row, likes
        y += 62f
        c.label(video.title, videoArea.left, y, 36f, Yt.text, bold = true, maxWidth = videoArea.width(), maxLines = 2)
        y += 104f
        avatar(c, Thumbs.get(d?.avatar ?: video.avatar) { dirty = true }, videoArea.left + 34f, y + 34f, 34f)
        c.label(d?.channel ?: video.channel, videoArea.left + 86f, y + 2f, 28f, Yt.text, bold = true, maxWidth = videoArea.width() * 0.45f, maxLines = 1)
        val subs = d?.subscribers ?: -1
        c.label(if (subs > 0) "${formatCount(subs)} inscritos" else meta("", d?.views ?: video.views, d?.ago ?: video.ago, live),
            videoArea.left + 86f, y + 40f, 22f, Yt.text2, maxWidth = videoArea.width() * 0.45f, maxLines = 1)
        if ((d?.likes ?: 0) > 0) {
            val pill = RectF(videoArea.right - 230f, y + 2f, videoArea.right, y + 66f)
            fill.color = Yt.surface; c.canvas.drawRoundRect(pill, 32f, 32f, fill)
            YtIcons.get(ctx, "thumb_up")?.let { c.canvas.drawBitmap(it, null, RectF(pill.left + 22f, pill.top + 14f, pill.left + 58f, pill.top + 50f), bmpPaint) }
            c.label(formatCount(d!!.likes), pill.left + 74f, pill.top + 14f, 26f, Yt.text, bold = true, maxLines = 1)
        }
        if (d != null && subs > 0) c.label(meta("", d.views, d.ago, live), videoArea.left, y + 84f, 22f, Yt.text2, maxWidth = videoArea.width(), maxLines = 1)

        // ---- up next
        val sx = c.w - pad - sideW
        iconButton(c, "close", YtIcons.get(ctx, "close"), c.w - pad - 30f, pad + 30f, 34f) { visible = false; onClose?.invoke() }
        c.label("A seguir", sx, pad + 8f, 32f, Yt.text, bold = true)
        var ty = pad + 80f
        val related = d?.related.orEmpty()
        val per = 6
        val pages = maxOf(1, (related.size + per - 1) / per)
        relatedPage = relatedPage.coerceIn(0, pages - 1)
        val rowH = 152f
        related.drop(relatedPage * per).take(per).forEachIndexed { i, v ->
            val tr = RectF(sx, ty, sx + rowH * 16f / 9f, ty + rowH)
            drawThumb(c, Thumbs.get(v.thumbnail) { dirty = true }, tr, 18f)
            badge(c, tr, v)
            val tx = tr.right + 18f
            c.label(v.title, tx, ty + 2f, 24f, Yt.text, bold = true, maxWidth = c.w - pad - tx, maxLines = 2)
            c.label(v.channel, tx, ty + 72f, 20f, Yt.text2, maxWidth = c.w - pad - tx, maxLines = 1)
            c.label(meta("", v.views, v.ago, v.live), tx, ty + 102f, 20f, Yt.text2, maxWidth = c.w - pad - tx, maxLines = 1)
            if (isHovered("r$i")) { fill.color = Color.argb(30, 255, 255, 255); c.canvas.drawRoundRect(RectF(sx - 8f, ty - 8f, c.w - pad + 8f, ty + rowH + 8f), 20f, 20f, fill) }
            addRegion(Region(RectF(sx, ty, c.w - pad, ty + rowH), "r$i", {
                if (immersiveOn) setImmersive(false)
                relatedPage = 0; qualityMenu = false; load(v)
            }))
            ty += rowH + 22f
        }
        iconButton(c, "rprev", YtIcons.get(ctx, "chevron_left"), sx + 60f, c.h - 70f, 34f) { if (relatedPage > 0) { relatedPage--; dirty = true } }
        iconButton(c, "rnext", YtIcons.get(ctx, "chevron_right"), c.w - pad - 60f, c.h - 70f, 34f) { if (relatedPage < pages - 1) { relatedPage++; dirty = true } }
    }

    override fun release() {
        if (immersiveOn) host.showImmersive(null)
        immersiveOn = false
        stop()
        screen?.release(); screen = null
        super.release()
    }
}
