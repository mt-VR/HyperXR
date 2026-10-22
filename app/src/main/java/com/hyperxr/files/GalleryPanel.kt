package com.hyperxr.files

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import com.hyperxr.HyperXRApp
import com.hyperxr.media.Spherical
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme
import java.io.File

/**
 * Photos and videos of the phone (MediaStore) plus HyperXR captures. 360 photos/videos are found
 * from their metadata (only 2:1 and 1:1 frames are read, so big libraries stay fast) and open
 * around the user; 360 photos can be saved as environments from the viewer.
 */
class GalleryPanel(private val host: MediaHost) : XrPanel(1.6f, 1.1f, title = "Galeria") {
    private enum class Tab(val label: String) { ALL("Tudo"), PHOTOS("Fotos"), VIDEOS("Vídeos"), SPHERE("360"), CAPTURES("Capturas") }

    private val app = HyperXRApp.instance
    @Volatile private var items: List<MediaItem> = emptyList()
    @Volatile private var loading = true
    @Volatile private var detecting = 0
    @Volatile private var note: String? = null
    private var tab = Tab.ALL
    private var page = 0
    private val thumbs = LruCache<String, Bitmap>(60)
    private val pendingThumbs = HashSet<String>()

    init { load() }

    private fun hasPermission(): Boolean {
        if (StorageAccess.granted(app)) return true
        if (Build.VERSION.SDK_INT >= 34 && app.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED) return true
        return if (Build.VERSION.SDK_INT >= 33)
            app.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        else app.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermission() = host.activity.runOnUiThread {
        val perms = if (Build.VERSION.SDK_INT >= 34) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        else if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        host.activity.requestPermissions(perms, 42)
    }

    private fun load() {
        loading = true
        mediaIo.execute {
            val list = ArrayList<MediaItem>()
            if (hasPermission()) {
                try { list += query(images = true); list += query(images = false) } catch (t: Throwable) { note = "Erro ao ler a galeria: ${t.message}" }
            }
            File(app.filesDir, "captures").listFiles().orEmpty().forEach { list += MediaItem.of(it) }
            list.sortByDescending { it.dateMs }
            items = list
            loading = false; dirty = true
            detect360(list)
        }
    }

    private fun query(images: Boolean): List<MediaItem> {
        val uri = if (images) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val cols = mutableListOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
        if (!images) cols += MediaStore.Video.VideoColumns.DURATION
        val out = ArrayList<MediaItem>()
        app.contentResolver.query(uri, cols.toTypedArray(), null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { cur ->
            val iId = cur.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iW = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val iH = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val iS = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iD = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val iDur = if (images) -1 else cur.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION)
            while (cur.moveToNext()) {
                out += MediaItem(
                    name = cur.getString(iName) ?: "?", kind = if (images) FileKind.IMAGE else FileKind.VIDEO,
                    uri = ContentUris.withAppendedId(uri, cur.getLong(iId)), width = cur.getInt(iW), height = cur.getInt(iH),
                    sizeBytes = cur.getLong(iS), dateMs = cur.getLong(iD) * 1000, durationMs = if (iDur >= 0) cur.getLong(iDur) else 0,
                )
            }
        }
        return out
    }

    /** Only frames shaped like 360 media are opened and read (2:1 mono, 1:1 top-bottom stereo). */
    private fun detect360(list: List<MediaItem>) {
        val candidates = list.filter { m ->
            val w = m.width; val h = m.height
            w <= 0 || h <= 0 || Spherical.isEquirectAspect(w, h) || (m.kind == FileKind.VIDEO && w == h && w >= 1920)
        }
        val checked = candidates.toHashSet()
        detecting = candidates.size
        for (m in candidates) {
            m.detectSphere(app)
            detecting--
            if (detecting % 8 == 0) dirty = true
        }
        // everything else is flat
        for (m in list) if (m.sphere == null && m !in checked) m.sphere = com.hyperxr.media.SphericalInfo.FLAT
        detecting = 0; dirty = true
    }

    private fun filtered(): List<MediaItem> = items.filter {
        when (tab) {
            Tab.ALL -> true
            Tab.PHOTOS -> it.kind == FileKind.IMAGE
            Tab.VIDEOS -> it.kind == FileKind.VIDEO
            Tab.SPHERE -> it.sphere?.spherical == true
            Tab.CAPTURES -> it.file != null
        }
    }

    private fun thumb(m: MediaItem): Bitmap? {
        val key = (m.uri ?: m.file).toString()
        thumbs.get(key)?.let { return it }
        if (key in pendingThumbs) return null
        pendingThumbs += key
        mediaIo.execute {
            val b = try {
                if (m.uri != null && Build.VERSION.SDK_INT >= 29) app.contentResolver.loadThumbnail(m.uri, Size(320, 320), null)
                else if (m.uri != null && m.kind == FileKind.VIDEO) {
                    @Suppress("DEPRECATION")
                    MediaStore.Video.Thumbnails.getThumbnail(app.contentResolver, ContentUris.parseId(m.uri), MediaStore.Video.Thumbnails.MINI_KIND, null)
                } else m.decodeBitmap(app, 320)
            } catch (_: Throwable) { null }
            host.onGl {
                pendingThumbs -= key
                if (b != null) thumbs.put(key, b)
                dirty = true
            }
        }
        return null
    }

    override fun draw(c: PanelCanvas) {
        val gap = 10f
        // tabs
        var x = c.pad
        for (t in Tab.entries) {
            val w = if (t == Tab.SPHERE) 120f else 170f
            c.button("tab${t.name}", t.label, x, c.y, w, 66f, accent = tab == t) { tab = t; page = 0; dirty = true }
            x += w + gap
        }
        c.button("reload", "Atualizar", c.w - c.pad - 180f, c.y, 180f, 66f) { thumbs.evictAll(); load(); dirty = true }
        val gridTop = c.y + 84f
        val list = filtered()
        if (!hasPermission()) {
            c.label("A galeria precisa de permissão para ver fotos e vídeos.", c.pad, gridTop + 10f, 30f, XrTheme.text)
            c.button("perm", "Permitir (na tela do celular)", c.pad, gridTop + 70f, 460f, 76f, accent = true) { requestPermission(); note = "Depois de permitir, toque em Atualizar" }
            note?.let { c.label(it, c.pad, gridTop + 170f, 26f, XrTheme.textDim) }
            return
        }
        when {
            loading -> c.label("Carregando galeria...", c.pad, gridTop + 10f, 30f, XrTheme.textDim)
            list.isEmpty() -> c.label(if (tab == Tab.SPHERE && detecting > 0) "Procurando fotos e vídeos 360..." else "Nada aqui ainda", c.pad, gridTop + 10f, 30f, XrTheme.textDim)
        }
        val cols = 5; val rows = 3
        val cellW = (c.w - 2 * c.pad - (cols - 1) * gap) / cols
        val cellH = (c.h - 120f - gridTop - (rows - 1) * gap) / rows
        val perPage = cols * rows
        val pages = maxOf(1, (list.size + perPage - 1) / perPage)
        page = page.coerceIn(0, pages - 1)
        list.drop(page * perPage).take(perPage).forEachIndexed { i, m ->
            val cx = c.pad + (i % cols) * (cellW + gap)
            val cy = gridTop + (i / cols) * (cellH + gap)
            val r = RectF(cx, cy, cx + cellW, cy + cellH)
            val idx = list.indexOf(m)
            c.button("m:${m.uri ?: m.file}", "", cx, cy, cellW, cellH) { openViewer(host, list, idx)?.let { host.openPanel(it) } }
            val b = thumb(m)
            if (b != null) {
                // centre-crop into the cell, rounded like the cell
                c.canvas.save()
                val path = android.graphics.Path().apply { addRoundRect(RectF(r.left + 4f, r.top + 4f, r.right - 4f, r.bottom - 4f), 18f, 18f, android.graphics.Path.Direction.CW) }
                c.canvas.clipPath(path)
                val s = maxOf(r.width() / b.width, r.height() / b.height)
                val w = b.width * s; val h = b.height * s
                c.canvas.drawBitmap(b, null, RectF(r.centerX() - w / 2, r.centerY() - h / 2, r.centerX() + w / 2, r.centerY() + h / 2), bmpPaint)
                c.canvas.restore()
            } else c.label(m.name, r.left + 14f, r.top + 14f, 20f, XrTheme.textDim, maxWidth = r.width() - 28f)
            var bx = r.left + 12f
            if (m.sphere?.spherical == true) bx += c.chip("360", bx, r.bottom - 52f, XrTheme.accent) + 8f
            if (m.kind == FileKind.VIDEO) c.chip(if (m.durationMs > 0) formatTime(m.durationMs) else "Vídeo", bx, r.bottom - 52f, Color.argb(200, 20, 20, 22))
        }
        // footer
        val by = c.h - 100f
        val info = buildString {
            append("${list.size} itens")
            if (detecting > 0) append(" • verificando 360: $detecting")
            note?.let { append(" • $it") }
        }
        c.label(info, c.pad, by + 24f, 24f, XrTheme.textDim)
        val px = c.w - c.pad - 2 * 160f - gap
        c.button("pgprev", "Anterior", px, by, 160f, 76f, enabled = page > 0) { page--; dirty = true }
        c.button("pgnext", "Próxima", px + 160f + gap, by, 160f, 76f, enabled = page < pages - 1) { page++; dirty = true }
        if (pages > 1) c.label("${page + 1}/$pages", px - 100f, by + 24f, 24f, XrTheme.textDim)
    }

    override fun release() {
        thumbs.evictAll()
        super.release()
    }

    private companion object { val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
}
