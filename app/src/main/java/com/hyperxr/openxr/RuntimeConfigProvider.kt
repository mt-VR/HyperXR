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
 * Only these numbers are exposed; and `hands`, the shared file with the tracked hands while an
 * OpenXR game runs ([HandBridge]: joint positions and pinch, nothing from the camera image).
 */
class RuntimeConfigProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val g = HyperXRApp.instance.settings.current.graphics
        val c = MatrixCursor(COLUMNS)
        c.addRow(arrayOf<Any>(g.ipdMm, g.fovDegrees, g.distortionK1, g.distortionK2, if (g.lensDistortion) 1 else 0, g.lensSeparationMm))
        return c
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (uri.lastPathSegment != "hands") return null
        val f = HandBridge.ensure(context ?: return null)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_WRITE)
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.hyperxr.runtime"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "com.hypermagic.runtime"
        val COLUMNS = arrayOf("ipd_mm", "fov_deg", "k1", "k2", "lens_distortion", "lens_separation_mm")
    }
}
