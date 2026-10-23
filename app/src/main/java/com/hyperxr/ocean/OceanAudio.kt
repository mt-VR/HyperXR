package com.hyperxr.ocean

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process

/** Streams [OceanSynth] to the speakers on its own audio-priority thread. */
class OceanAudio {
    val synth = OceanSynth(RATE)
    private var track: AudioTrack? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(maxOf(min, BLOCK * 2 * 4 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (t: Throwable) { com.hyperxr.diagnostics.Diagnostics.log("Ocean audio unavailable: ${t.message}"); return }
        track = t
        running = true
        t.play()
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val buf = FloatArray(BLOCK * 2)
            while (running) { synth.render(buf, BLOCK); t.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING) }
        }, "hx-ocean-audio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500); thread = null
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }

    private companion object {
        const val RATE = 48000
        const val BLOCK = 512
    }
}
