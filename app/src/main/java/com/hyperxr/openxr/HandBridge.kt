package com.hyperxr.openxr

import android.content.Context
import java.io.File

/**
 * The shared file through which OpenXR games get HyperMagic's hands (XR_EXT_hand_tracking in the
 * HyperMagic runtime; layout in native/openxr-runtime/src/hx_hands.h). The runtime inside the
 * game maps it through [RuntimeConfigProvider] (content://com.hypermagic.runtime/hands).
 */
object HandBridge {
    init { System.loadLibrary("hyperxr_handbridge") }

    fun file(ctx: Context) = File(File(ctx.filesDir, "openxr").apply { mkdirs() }, "hands.bin")

    /** Creates the file with its header when missing (so a game can map it before any hands exist). */
    fun ensure(ctx: Context): File {
        val f = file(ctx)
        if (f.length() != nativeSize().toLong()) { val m = nativeOpen(f.absolutePath); if (m != 0L) nativeClose(m) }
        return f
    }

    @JvmStatic external fun nativeSize(): Int
    @JvmStatic external fun nativeOpen(path: String): Long
    @JvmStatic external fun nativeClose(map: Long)
    /** [state]: left tracked, confidence, pinch, then the same for the right; [joints]: 2 x 21 x 3, camera space. */
    @JvmStatic external fun nativeWrite(map: Long, timeNs: Long, state: FloatArray, joints: FloatArray)
    @JvmStatic external fun nativeReaderAlive(map: Long): Long
    @JvmStatic external fun nativeReaderWantsHands(map: Long): Long
}
