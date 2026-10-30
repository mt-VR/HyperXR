package com.hyperxr.openxr

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.hyperxr.HyperXRApp

/**
 * Read-only viewer configuration for the HyperXR OpenXR runtime running inside other apps
 * (IPD, field of view, lens distortion), so OpenXR apps see the same calibration as HyperXR.
 * Only these numbers are exposed; and, while an OpenXR game runs, `hands` (joint positions and
 * pinch, [HandBridge]) and `camera` (small camera frames, only while the game shows passthrough).
 */
class RuntimeConfigProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        if (uri.lastPathSegment == "room") return room()
        val g = HyperXRApp.instance.settings.current.graphics
        val c = MatrixCursor(COLUMNS)
        c.addRow(arrayOf<Any>(g.ipdMm, g.fovDegrees, g.distortionK1, g.distortionK2, if (g.lensDistortion) 1 else 0, g.lensSeparationMm))
        return c
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        // hands and camera frames only for the game HyperMagic launched (and HyperMagic itself)
        val caller = callingPackage
        if (caller != ctx.packageName && caller != HandBridge.allowedGame(ctx)) throw SecurityException("$caller may not read $uri")
        val f = when (uri.lastPathSegment) {
            "hands" -> HandBridge.ensure(ctx)
            "camera" -> HandBridge.ensureCamera(ctx)
            else -> return null
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_WRITE)
    }

    /**
     * The room for the Meta scene model of OpenXR games: HyperGuardian's area (floor corners x, z)
     * moved so its middle is where the player stands (the game's tracking starts there), 2.6 m high.
     * Empty when no area was drawn: the runtime then uses a 4 x 4 m room.
     */
    private fun room(): Cursor {
        val c = MatrixCursor(arrayOf("x", "z", "height"))
        val ctx = context ?: return c
        // the shape of the user's room: only for the game HyperMagic launched
        if (callingPackage != ctx.packageName && callingPackage != HandBridge.allowedGame(ctx)) return c
        val v = ctx.getSharedPreferences("guardian", android.content.Context.MODE_PRIVATE).getString("area", "")
            ?.takeIf { it.isNotEmpty() }?.let { s -> runCatching { s.split(',').map { it.toFloat() } }.getOrNull() } ?: return c
        if (v.size < 6 || v.size % 2 != 0) return c
        // at most 32 corners (the runtime's limit), spread evenly along the outline
        val total = v.size / 2
        val n = minOf(total, 32)
        val pick = IntArray(n) { it * total / n }
        val cx = pick.sumOf { v[it * 2].toDouble() }.toFloat() / n
        val cz = pick.sumOf { v[it * 2 + 1].toDouble() }.toFloat() / n
        for (i in pick) c.addRow(arrayOf<Any>(v[i * 2] - cx, v[i * 2 + 1] - cz, ROOM_HEIGHT_M))
        return c
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.hyperxr.runtime"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "com.hypermagic.runtime"
        const val ROOM_HEIGHT_M = 2.6f
        val COLUMNS = arrayOf("ipd_mm", "fov_deg", "k1", "k2", "lens_distortion", "lens_separation_mm")
    }
}
