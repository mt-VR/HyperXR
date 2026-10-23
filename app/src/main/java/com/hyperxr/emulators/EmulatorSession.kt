package com.hyperxr.emulators

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.view.Surface
import com.hyperxr.diagnostics.Diagnostics
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/** Where emulator files live: imported ROMs, the games' own saves, save states, BIOS. */
object EmulatorFiles {
    fun root(c: Context) = File(c.filesDir, "emulators").apply { mkdirs() }
    fun roms(c: Context) = File(root(c), "roms").apply { mkdirs() }
    fun saves(c: Context) = File(root(c), "saves").apply { mkdirs() }
    fun states(c: Context) = File(root(c), "states").apply { mkdirs() }
    fun system(c: Context) = File(root(c), "system").apply { mkdirs() }
}

/**
 * One game running on an embedded core, on its own thread. The core's audio paces the loop
 * (blocking writes to a small AudioTrack keep emulation at the console's speed with little
 * latency); without audio the loop sleeps to the core's frame rate. The game's own save memory
 * is written every 10 s and when the session ends.
 */
class EmulatorSession(private val context: Context, val core: EmulatorCore, val rom: File) {
    @Volatile var status: String? = "Carregando…"; private set
    @Volatile var aspect = 4f / 3f; private set
    @Volatile var paused = false
    @Volatile var muted = false
    @Volatile private var running = false
    @Volatile private var surface: Surface? = null
    @Volatile private var surfaceChanged = false
    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    private var thread: Thread? = null

    private val base = rom.nameWithoutExtension
    private val ramFile get() = File(EmulatorFiles.saves(context), "$base.srm")
    private fun stateFile(slot: Int) = File(EmulatorFiles.states(context), "$base.$slot.state")

    fun setSurface(s: Surface?) { surface = s; surfaceChanged = true }
    fun setButtons(mask: Int) { if (running) RetroNative.setButtons(mask) }

    fun reset() = commands.add { RetroNative.reset() }
    fun saveState(slot: Int = 0, done: (Boolean) -> Unit = {}) = commands.add { done(RetroNative.saveState(stateFile(slot).path)) }
    fun loadState(slot: Int = 0, done: (Boolean) -> Unit = {}) = commands.add { done(stateFile(slot).isFile && RetroNative.loadState(stateFile(slot).path)) }
    fun hasState(slot: Int = 0) = stateFile(slot).isFile

    /** Starts the core and the game; [onReady] (on the emulator thread) once the picture size is known. */
    fun start(onReady: (Float) -> Unit, onError: (String) -> Unit) {
        if (!RetroNative.available) { onError("Biblioteca do emulador ausente neste aparelho"); return }
        // libretro has global callbacks: the previous game ends (and saves) before this one starts
        synchronized(EmulatorSession) {
            current?.let { old -> old.stop(); old.thread?.join(3000) }
            current = this
        }
        running = true
        thread = Thread({ loop(onReady, onError) }, "hx-retro-${core.id}").apply { start() }
    }

    private fun loop(onReady: (Float) -> Unit, onError: (String) -> Unit) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        if (!RetroNative.open(core.library, EmulatorFiles.system(context).path, EmulatorFiles.saves(context).path)) {
            running = false; status = "Não foi possível abrir o núcleo ${core.coreName}"; onError(status!!); return
        }
        if (!RetroNative.loadGame(rom.path)) {
            RetroNative.close()
            running = false; status = "O ${core.coreName} não aceitou ${rom.name}"; onError(status!!); return
        }
        if (ramFile.isFile) RetroNative.loadRam(ramFile.path)
        val av = RetroNative.avInfo()
        aspect = av[2].toFloat().takeIf { it > 0.3f && it < 4f } ?: (4f / 3f)
        val fps = av[3].takeIf { it in 20.0..120.0 } ?: 60.0
        val rate = av[4].toInt().takeIf { it in 8000..192000 } ?: 44100
        Diagnostics.log("Emulator ${core.coreName}: ${rom.name}, ${av[0].toInt()}x${av[1].toInt()} @ ${"%.2f".format(fps)} fps, $rate Hz")
        status = null
        onReady(aspect)

        val track = runCatching { audioTrack(rate, fps) }.getOrNull()
        track?.play()
        // one frame of audio at most (plus slack for cores that batch two)
        val audio = ShortArray(((rate / fps) * 2 * 2 + 64).toInt())
        val periodNs = (1e9 / fps).toLong()
        var next = System.nanoTime()
        var lastSave = System.nanoTime()
        try {
            while (running) {
                while (true) { val c = commands.poll() ?: break; runCatching { c() } }
                if (surfaceChanged) { surfaceChanged = false; RetroNative.setSurface(surface) }
                if (paused) { Thread.sleep(30); next = System.nanoTime(); continue }
                val n = RetroNative.runFrame(audio)
                if (track != null && n > 0 && !muted) {
                    track.write(audio, 0, n, AudioTrack.WRITE_BLOCKING)
                    next = System.nanoTime()
                } else {
                    next += periodNs
                    val wait = next - System.nanoTime()
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt()) else if (wait < -periodNs * 4) next = System.nanoTime()
                }
                if (System.nanoTime() - lastSave > 10_000_000_000L) { lastSave = System.nanoTime(); RetroNative.saveRam(ramFile.path) }
            }
        } catch (t: Throwable) {
            Diagnostics.log("Emulator ${core.coreName} stopped: ${t.message}")
        } finally {
            RetroNative.saveRam(ramFile.path)
            RetroNative.setSurface(null)
            RetroNative.close()
            track?.run { runCatching { stop() }; release() }
        }
    }

    private fun audioTrack(rate: Int, fps: Double): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        // ~3 frames of audio: enough to ride over a slow frame, little added latency
        val bytes = maxOf(min, (rate / fps * 4 * 3).toInt())
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    /** Ends the game (its save memory is written first); returns at once. */
    fun stop() { running = false }

    companion object {
        @Volatile private var current: EmulatorSession? = null
    }
}
