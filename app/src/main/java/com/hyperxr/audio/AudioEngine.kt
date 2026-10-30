package com.hyperxr.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.hyperxr.math.Pose
import com.hyperxr.math.Vec3
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Low-latency sound effects. Procedural sounds are synthesised to 16-bit PCM WAV files in the cache
 * and played through SoundPool (decoded once, mixed by the platform's fast path when available).
 * Spatialisation: Resonance Audio (HRTF, third-order ambisonics, through AAudio: SpatialAudio) for
 * synthesised sounds, so a sound is placed in front, behind, above or below; where it cannot start,
 * and for loaded files, equal-power stereo panning with inverse-distance attenuation.
 */
class AudioEngine(private val context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(24)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val dir = File(context.cacheDir, "synth").apply { mkdirs() }
    private var nextSynth = 0
    var masterVolume = 1f
    var spatial = true
    private val local = Vec3()
    /** SoundPool id -> Resonance sound, for the sounds that also live in the spatial engine. */
    private val nativeSounds = HashMap<Int, Int>()
    private val hrtf = SpatialAudio.start()

    fun load(file: File): Int = pool.load(file.absolutePath, 1)

    fun synth(wave: String, freq: Float, freqEnd: Float, duration: Float, volume: Float, attack: Float, decay: Float): Int {
        val rate = 44100
        val n = (duration.coerceIn(0.01f, 5f) * rate).toInt()
        val pcm = ShortArray(n)
        var phase = 0.0
        var noise = 12345
        for (i in 0 until n) {
            val t = i.toFloat() / rate
            val f = freq + (freqEnd - freq) * (t / duration)
            phase += 2 * PI * f / rate
            val s = when (wave) {
                "square" -> if (sin(phase) >= 0) 1.0 else -1.0
                "saw" -> 2.0 * ((phase / (2 * PI)) % 1.0) - 1.0
                "noise" -> { noise = noise * 1103515245 + 12345; ((noise ushr 16) and 0x7fff) / 16384.0 - 1.0 }
                else -> sin(phase)
            }
            val env = minOf(1f, t / attack.coerceAtLeast(0.001f)) * exp(-(maxOf(0f, t - attack)) / decay.coerceAtLeast(0.005f)).toFloat()
            pcm[i] = (s * env * volume.coerceIn(0f, 1f) * 32000).toInt().toShort()
        }
        val file = File(dir, "s${nextSynth++}.wav")
        writeWav(file, pcm, rate)
        val id = pool.load(file.absolutePath, 1)
        if (hrtf) {
            val f = FloatArray(n) { pcm[it] / 32768f }
            SpatialAudio.nativeAddSound(f).takeIf { it >= 0 }?.let { nativeSounds[id] = it }
        }
        return id
    }

    /** Plays [soundId]; when [position] is given it is spatialised against [head]. Returns a stream id. */
    fun play(soundId: Int, volume: Float, pitch: Float, loop: Boolean, position: Vec3?, head: Pose?): Int {
        val native = nativeSounds[soundId]
        if (native != null) {
            SpatialAudio.nativeMaster(masterVolume)
            if (head != null) listener(head)
            val sp = spatial && position != null
            val h = SpatialAudio.nativePlay(native, volume, pitch.coerceIn(0.5f, 2f), loop, sp, position?.x ?: 0f, position?.y ?: 0f, position?.z ?: 0f)
            if (h > 0) return NATIVE_BASE + h
        }
        var l = volume * masterVolume
        var r = l
        if (spatial && position != null && head != null) {
            head.inverse().transformPoint(position, local)
            val dist = local.length().coerceAtLeast(0.3f)
            val atten = (1f / dist).coerceAtMost(1f)
            val pan = (local.x / dist).coerceIn(-1f, 1f) // -1 left, 1 right
            val angle = (pan + 1f) * (PI / 4).toFloat()
            l *= kotlin.math.cos(angle) * 1.414f * atten
            r *= kotlin.math.sin(angle) * 1.414f * atten
        }
        return pool.play(soundId, l.coerceIn(0f, 1f), r.coerceIn(0f, 1f), 1, if (loop) -1 else 0, pitch.coerceIn(0.5f, 2f))
    }

    fun stop(streamId: Int) { if (streamId >= NATIVE_BASE) SpatialAudio.nativeStopVoice(streamId - NATIVE_BASE) else pool.stop(streamId) }

    /** Moves a playing sound (a source that walks about). */
    fun move(streamId: Int, position: Vec3) { if (streamId >= NATIVE_BASE) SpatialAudio.nativeMove(streamId - NATIVE_BASE, position.x, position.y, position.z) }

    /** Where the ears are: once a frame from the engine, so HRTF follows the head. */
    fun listener(head: Pose) {
        if (!hrtf) return
        val p = head.position; val q = head.orientation
        SpatialAudio.nativeListener(p.x, p.y, p.z, q.x, q.y, q.z, q.w)
    }

    fun release() { pool.release(); SpatialAudio.stop(); dir.deleteRecursively() }

    private companion object { const val NATIVE_BASE = 1 shl 24 }

    private fun writeWav(file: File, pcm: ShortArray, rate: Int) {
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            fun le32(v: Int) { f.write(v and 0xff); f.write((v shr 8) and 0xff); f.write((v shr 16) and 0xff); f.write((v shr 24) and 0xff) }
            fun le16(v: Int) { f.write(v and 0xff); f.write((v shr 8) and 0xff) }
            f.writeBytes("RIFF"); le32(36 + pcm.size * 2); f.writeBytes("WAVE")
            f.writeBytes("fmt "); le32(16); le16(1); le16(1); le32(rate); le32(rate * 2); le16(2); le16(16)
            f.writeBytes("data"); le32(pcm.size * 2)
            val bytes = ByteArray(pcm.size * 2)
            for (i in pcm.indices) { bytes[i * 2] = (pcm[i].toInt() and 0xff).toByte(); bytes[i * 2 + 1] = ((pcm[i].toInt() shr 8) and 0xff).toByte() }
            f.write(bytes)
        }
    }
}
