package com.hyperxr.openxr

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.io.File

/**
 * Answers the OpenXR loader's "which runtime is active?" query, so OpenXR apps find the HyperXR
 * runtime without any other app installed.
 *
 * The Khronos Android loader asks the installable broker (`org.khronos.openxr.runtime_broker`,
 * the Khronos "OpenXR Runtime Broker" app) first and the system broker
 * (`org.khronos.openxr.system_runtime_broker`) second. Phones ship neither, so HyperXR provides
 * the system one: if the user installs the Khronos broker, that one still wins and its choice is
 * respected. Contract (BrokerContract in OpenXR-SDK-Source/src/loader/android_utilities.cpp):
 *  - content://<authority>/openxr/<major>/abi/<abi>/runtimes/active/0
 *      -> _id, package_name, native_lib_dir, so_filename, has_functions
 *  - content://<authority>/openxr/<major>/abi/<abi>/runtimes/<package>/functions/0
 *      -> _id, function_name, symbol_name (empty: the library exports the standard symbols)
 */
class SystemBrokerProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val seg = uri.pathSegments
        if (seg.size < 6 || seg[0] != "openxr" || seg[2] != "abi" || seg[4] != "runtimes") return null
        val ctx = context ?: return null
        val libDir = ctx.applicationInfo.nativeLibraryDir
        return when {
            seg[5] == "active" -> MatrixCursor(arrayOf("_id", "package_name", "native_lib_dir", "so_filename", "has_functions")).also { c ->
                // only OpenXR 1.x, and only for the ABI our library was installed for (a 32-bit
                // game cannot load a 64-bit runtime)
                if (seg[1] == "1" && seg[3] == installedAbi(libDir) && File(libDir, SO_NAME).isFile) c.addRow(arrayOf<Any>(0, ctx.packageName, libDir, SO_NAME, 0))
            }
            seg.size >= 7 && seg[6] == "functions" -> MatrixCursor(arrayOf("_id", "function_name", "symbol_name"))
            else -> null
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val SO_NAME = "libhyperxr_openxr.so"

        /** The package manager installs native libraries to .../lib/<arm64|arm|x86_64|x86>. */
        fun installedAbi(libDir: String): String = when (libDir.substringAfterLast('/')) {
            "arm64" -> "arm64-v8a"; "arm" -> "armeabi-v7a"; "x86_64" -> "x86_64"; "x86" -> "x86"
            else -> android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        }
    }
}
