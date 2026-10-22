package com.hyperxr.files

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.hyperxr.environments.EnvironmentEntry
import com.hyperxr.media.Spherical
import com.hyperxr.media.SphericalInfo
import com.hyperxr.rendering.SceneRenderer
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.windows.KeyTarget
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.Executors

/** What the file manager, gallery and viewers need from the launcher. */
interface MediaHost {
    val activity: Activity
    /** Opens [p] above the dock, beside the panel that asked (GL thread). */
    fun openPanel(p: XrPanel, distance: Float = 1.3f)
    fun openKeyboardFor(target: KeyTarget)
    fun closeKeyboard()
    /** Shows a 360 photo/video around the user, or null to go back to the environment (GL thread). */
    fun showImmersive(m: SceneRenderer.Immersive?)
    val immersive: SceneRenderer.Immersive?
    /** Head yaw in radians (to turn the centre of a 360 frame to where the user looks). */
    fun headYaw(): Float
    fun onGl(task: () -> Unit)
    fun selectEnvironment(entry: EnvironmentEntry)
}

/** Background work for panels (decoding, copying, MediaStore queries). */
val mediaIo = Executors.newFixedThreadPool(2)

/**
 * One photo, video, audio or other file, either a path ([file]) or a MediaStore item ([uri]).
 * [width]/[height] come from MediaStore when known (0 otherwise).
 */
class MediaItem(
    val name: String,
    val kind: FileKind,
    val file: File? = null,
    val uri: Uri? = null,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0,
    val dateMs: Long = 0,
    val durationMs: Long = 0,
) {
    @Volatile var sphere: SphericalInfo? = null

    fun open(ctx: Context): InputStream = file?.inputStream() ?: ctx.contentResolver.openInputStream(uri!!) ?: error("Não foi possível abrir $name")

    /** Reads [n] bytes from the start (and, with [tail], also from the end) without loading the file. */
    fun chunks(ctx: Context, n: Int, tail: Boolean): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val pfd = when {
            file != null -> null
            else -> ctx.contentResolver.openFileDescriptor(uri!!, "r")
        }
        try {
            if (file != null) RandomAccessFile(file, "r").use { raf -> readEnds(raf.length(), n, tail, out) { off, buf -> raf.seek(off); raf.readFully(buf) } }
            else java.io.FileInputStream(pfd!!.fileDescriptor).channel.use { ch ->
                readEnds(ch.size(), n, tail, out) { off, buf -> ch.position(off); val bb = java.nio.ByteBuffer.wrap(buf); while (bb.hasRemaining() && ch.read(bb) > 0) Unit }
            }
        } finally { pfd?.close() }
        return out
    }

    private inline fun readEnds(len: Long, n: Int, tail: Boolean, out: MutableList<ByteArray>, read: (Long, ByteArray) -> Unit) {
        val head = ByteArray(minOf(len, n.toLong()).toInt()); read(0, head); out += head
        if (tail && len > n) { val t = ByteArray(minOf(len - n, n.toLong()).toInt()); read(len - t.size, t); out += t }
    }

    /** Detects (once) whether this is a 360 photo/video. Background thread. */
    fun detectSphere(ctx: Context): SphericalInfo {
        sphere?.let { return it }
        val info = try {
            when (kind) {
                FileKind.IMAGE -> {
                    var w = width; var h = height
                    if (w <= 0 || h <= 0) {
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        open(ctx).use { BitmapFactory.decodeStream(it, null, o) }
                        w = o.outWidth; h = o.outHeight
                    }
                    Spherical.detectImage(chunks(ctx, 256 * 1024, tail = false)[0], w, h)
                }
                FileKind.VIDEO -> {
                    var w = width; var h = height
                    if (w <= 0 || h <= 0) {
                        val r = android.media.MediaMetadataRetriever()
                        try {
                            if (file != null) r.setDataSource(file.path) else r.setDataSource(ctx, uri)
                            w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                            h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                            val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                        } finally { r.release() }
                    }
                    Spherical.detectVideo(chunks(ctx, 1024 * 1024, tail = true), w, h)
                }
                else -> SphericalInfo.FLAT
            }
        } catch (t: Throwable) { SphericalInfo.FLAT }
        sphere = info
        return info
    }

    /** Decodes for display, at most [maxSide] px on the long side, honouring EXIF rotation. */
    fun decodeBitmap(ctx: Context, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(ctx).use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0) return null
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = open(ctx).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val rot = try {
            open(ctx).use {
                when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        } catch (_: Throwable) { 0 }
        val scaled = if (maxOf(bmp.width, bmp.height) > maxSide) {
            val k = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true).also { if (it !== bmp) bmp.recycle() }
        } else bmp
        if (rot == 0) return scaled
        val m = android.graphics.Matrix().apply { postRotate(rot.toFloat()) }
        return Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, m, true).also { if (it !== scaled) scaled.recycle() }
    }

    companion object {
        fun of(f: File): MediaItem = MediaItem(f.name, FileKinds.kindOf(f.name, f.isDirectory), file = f, sizeBytes = f.length(), dateMs = f.lastModified())
    }
}
