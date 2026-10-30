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

    private const val PREFS = "openxr-bridge"

    /** The game HyperMagic launched last: the only app that may open the hands and camera files. */
    fun allowGame(ctx: Context, pkg: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("game", pkg).apply()

    fun allowedGame(ctx: Context): String? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("game", null)

    fun file(ctx: Context) = File(File(ctx.filesDir, "openxr").apply { mkdirs() }, "hands.bin")

    /** Creates the file with its header when missing (so a game can map it before any hands exist). */
    fun ensure(ctx: Context): File {
        val f = file(ctx)
        if (f.length() != nativeSize().toLong()) { val m = nativeOpen(f.absolutePath); if (m != 0L) nativeClose(m) }
        return f
    }

    fun cameraFile(ctx: Context) = File(File(ctx.filesDir, "openxr").apply { mkdirs() }, "camera.bin")

    /** The passthrough frames file, created with its header when missing. */
    fun ensureCamera(ctx: Context): File {
        val f = cameraFile(ctx)
        if (f.length() != nativeCameraSize().toLong()) { val m = nativeCameraOpen(f.absolutePath); if (m != 0L) nativeCameraClose(m) }
        return f
    }

    @JvmStatic external fun nativeCameraSize(): Int
    @JvmStatic external fun nativeCameraOpen(path: String): Long
    @JvmStatic external fun nativeCameraClose(map: Long)
    /** One RGBA frame ([pixels] direct, rows top to bottom) with its pinhole intrinsics. */
    @JvmStatic external fun nativeCameraWrite(map: Long, timeNs: Long, pixels: java.nio.ByteBuffer, w: Int, h: Int, fx: Float, fy: Float, cx: Float, cy: Float): Boolean
    /** When the runtime in a game last showed passthrough (CLOCK_MONOTONIC, 0 = never). */
    @JvmStatic external fun nativeCameraWanted(map: Long): Long

    @JvmStatic external fun nativeSize(): Int
    @JvmStatic external fun nativeOpen(path: String): Long
    @JvmStatic external fun nativeClose(map: Long)
    /** [state]: left tracked, confidence, pinch, then the same for the right; [joints]: 2 x 21 x 3, camera space. */
    @JvmStatic external fun nativeWrite(map: Long, timeNs: Long, state: FloatArray, joints: FloatArray)
    @JvmStatic external fun nativeReaderAlive(map: Long): Long
    @JvmStatic external fun nativeReaderWantsHands(map: Long): Long
}
