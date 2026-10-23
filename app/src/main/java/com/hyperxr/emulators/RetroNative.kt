package com.hyperxr.emulators

import android.view.Surface

/** JNI side of the libretro frontend (app/src/main/cpp/retro_frontend.cpp). One core at a time. */
object RetroNative {
    val available: Boolean = runCatching { System.loadLibrary("hyperxr_retro") }.isSuccess

    external fun open(library: String, systemDir: String, saveDir: String): Boolean
    /** "name|version|extensions|need_fullpath" */
    external fun systemInfo(): String?
    external fun loadGame(path: String): Boolean
    /** [base width, base height, aspect, fps, sample rate] */
    external fun avInfo(): DoubleArray
    external fun setSurface(surface: Surface?)
    external fun setButtons(mask: Int)
    /** Runs one frame; returns the interleaved stereo samples written to [audio]. */
    external fun runFrame(audio: ShortArray): Int
    external fun reset()
    external fun saveRam(path: String): Boolean
    external fun loadRam(path: String): Boolean
    external fun saveState(path: String): Boolean
    external fun loadState(path: String): Boolean
    /** Keeps up to [seconds] of history for rewind within [budgetBytes]; returns the seconds kept. */
    external fun enableRewind(seconds: Float, fps: Float, budgetBytes: Int): Float
    /** One step back in time (false when the history is used up). */
    external fun rewindStep(): Boolean
    external fun stateSize(): Int
    external fun close()
}
