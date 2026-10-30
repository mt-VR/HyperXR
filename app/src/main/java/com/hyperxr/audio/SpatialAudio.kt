package com.hyperxr.audio

import com.hyperxr.diagnostics.Diagnostics

/** JNI to spatial_audio.cpp: Resonance Audio (HRTF) over AAudio. [available] is false where it could not start. */
object SpatialAudio {
    val loaded: Boolean = runCatching { System.loadLibrary("hyperxr_audio"); true }
        .onFailure { Diagnostics.log("Spatial audio library: ${it.message}") }.getOrDefault(false)

    @Volatile var available = false; private set

    fun start(): Boolean {
        if (!loaded) return false
        available = runCatching { nativeStart() }.getOrDefault(false)
        if (!available) Diagnostics.log("Spatial audio: AAudio did not start, using SoundPool")
        return available
    }

    fun stop() { if (available) { nativeStop(); available = false } }

    @JvmStatic external fun nativeStart(): Boolean
    @JvmStatic external fun nativeStop()
    @JvmStatic external fun nativeAddSound(pcm: FloatArray): Int
    @JvmStatic external fun nativePlay(sound: Int, volume: Float, pitch: Float, loop: Boolean, spatial: Boolean, x: Float, y: Float, z: Float): Int
    @JvmStatic external fun nativeStopVoice(handle: Int)
    @JvmStatic external fun nativeMove(handle: Int, x: Float, y: Float, z: Float)
    @JvmStatic external fun nativeListener(px: Float, py: Float, pz: Float, qx: Float, qy: Float, qz: Float, qw: Float)
    @JvmStatic external fun nativeMaster(v: Float)
}
