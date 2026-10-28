package com.hyperxr.vrcore

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.hyperxr.xrapps.CardboardParams

/**
 * The VR settings provider the GVR SDK asks for the viewer's lens parameters
 * (content://com.google.vr.vrcore.settings/device_params): answers with HyperXR's VR Box
 * calibration, so Cardboard and Daydream apps need no file in /sdcard (which Android 11+ hides).
 * Everything else (user prefs, phone params, SDK configuration) is left to the SDK's defaults.
 */
class SettingsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (uri.path?.trim('/') != "device_params") return null
        val v = viewer() ?: return null
        return MatrixCursor(arrayOf("value")).apply { addRow(arrayOf<Any>(CardboardParams.encode(v))) }
    }

    /** Reads HyperXR's viewer settings (its runtime config provider); null when HyperXR is not there. */
    private fun viewer(): CardboardParams.Viewer? = try {
        val ctx = context ?: return null
        ctx.contentResolver.query(Uri.parse("content://com.hypermagic.runtime/viewer"), null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val sepCol = c.getColumnIndex("lens_separation_mm")
            val sep = if (sepCol >= 0) c.getFloat(sepCol) else c.getFloat(0)
            CardboardParams.fromSettings(sep, c.getFloat(1), c.getFloat(2), c.getFloat(3))
        }
    } catch (t: Throwable) { null }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.google.vr.settings"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    // the SDK writes a newly scanned viewer here; HyperXR's settings stay the source of truth
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}
