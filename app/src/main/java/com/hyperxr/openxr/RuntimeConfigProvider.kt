package com.hyperxr.openxr

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.hyperxr.HyperXRApp

/**
 * Read-only viewer configuration for the HyperXR OpenXR runtime running inside other apps
 * (IPD, field of view, lens distortion), so OpenXR apps see the same calibration as HyperXR.
 * Only these numbers are exposed.
 */
class RuntimeConfigProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val g = HyperXRApp.instance.settings.current.graphics
        val c = MatrixCursor(COLUMNS)
        c.addRow(arrayOf<Any>(g.ipdMm, g.fovDegrees, g.distortionK1, g.distortionK2, if (g.lensDistortion) 1 else 0))
        return c
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.hyperxr.runtime"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "com.hyperxr.runtime"
        val COLUMNS = arrayOf("ipd_mm", "fov_deg", "k1", "k2", "lens_distortion")
    }
}
