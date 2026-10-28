package com.hyperxr.share

import android.app.Activity
import android.app.Presentation
import android.content.ContentValues
import android.hardware.display.DisplayManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.hyperxr.diagnostics.Diagnostics
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * H.264 video from an input surface (the 16:9 spectator view) plus optional microphone AAC, muxed
 * to MP4 and saved to Movies/HyperXR so the phone's gallery and the HyperXR gallery show it.
 */
class VideoRecorder(private val activity: Activity, private val width: Int, private val height: Int, private val withMic: Boolean) {
    var sink: ScreenShare.Sink? = null
    private val temp = File(activity.cacheDir, "rec_${System.currentTimeMillis()}.mp4")
    private lateinit var video: MediaCodec
    private var audio: MediaCodec? = null
    private var mic: AudioRecord? = null
    private lateinit var muxer: MediaMuxer
    private val lock = Object()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxing = false
    @Volatile private var running = false
    private var startedMs = 0L
    private val threads = ArrayList<Thread>()

    fun elapsedText(): String {
        val s = (System.currentTimeMillis() - startedMs) / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** Starts the encoders; returns the surface to render into. */
    fun start(): Surface {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            // ~0.2 bits per pixel per frame at 30 fps: 12 Mb/s at 1080p, 22 Mb/s at 1440p
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 30 * 0.2f).toInt().coerceIn(4_000_000, 40_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        video.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val input = video.createInputSurface()
        muxer = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (withMic) setupMic()
        video.start()
        audio?.start()
        mic?.startRecording()
        running = true
        startedMs = System.currentTimeMillis()
        threads += Thread({ guarded { drainVideo() } }, "hx-rec-video").apply { start() }
        if (audio != null) threads += Thread({ guarded { pumpAudio() } }, "hx-rec-audio").apply { start() }
        return input
    }

    /** Encoder threads must never take the app down: a failed stream just ends the recording. */
    private fun guarded(body: () -> Unit) = try { body() } catch (t: Throwable) { Diagnostics.log("Recorder thread: $t") }

    @Suppress("MissingPermission")
    private fun setupMic() {
        try {
            val rate = 48_000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192) * 2)
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mic = rec; audio = enc
        } catch (t: Throwable) { Diagnostics.log("Recorder: microphone unavailable ($t)") }
    }

    /** Adds a track; the muxer starts once every encoder has reported its format. */
    private fun addTrack(f: MediaFormat, isVideo: Boolean) = synchronized(lock) {
        if (isVideo) videoTrack = muxer.addTrack(f) else audioTrack = muxer.addTrack(f)
        if (videoTrack >= 0 && (audio == null || audioTrack >= 0)) {
            muxer.start(); muxing = true
            // frames encoded before the muxer started were dropped: ask for a key frame now
            runCatching { video.setParameters(android.os.Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        }
    }

    private fun write(track: () -> Int, codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        val buf = codec.getOutputBuffer(index)
        synchronized(lock) {
            if (muxing && buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                buf.position(info.offset); buf.limit(info.offset + info.size)
                muxer.writeSampleData(track(), buf, info)
            }
        }
        codec.releaseOutputBuffer(index, false)
    }

    private fun drainVideo() {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val i = video.dequeueOutputBuffer(info, 10_000)
            when {
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> addTrack(video.outputFormat, true)
                i >= 0 -> {
                    write({ videoTrack }, video, i, info)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun pumpAudio() {
        val enc = audio ?: return
        val rec = mic ?: return
        val info = MediaCodec.BufferInfo()
        val startNs = System.nanoTime()
        var eos = false
        while (true) {
            if (!eos) {
                val i = enc.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val buf = enc.getInputBuffer(i)!!
                    buf.clear()
                    val n = rec.read(buf, buf.capacity().coerceAtMost(8192))
                    val pts = (System.nanoTime() - startNs) / 1000
                    if (!running) { enc.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                    else enc.queueInputBuffer(i, 0, n.coerceAtLeast(0), pts, 0)
                }
            }
            val o = enc.dequeueOutputBuffer(info, 0)
            when {
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> addTrack(enc.outputFormat, false)
                o >= 0 -> {
                    write({ audioTrack }, enc, o, info)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Ends the streams, finishes the file and publishes it; [done] gets the file name or null. */
    fun stop(done: ((String?) -> Unit)?) {
        Thread({
            var name: String? = null
            try {
                running = false
                runCatching { video.signalEndOfInputStream() }
                threads.forEach { it.join(3000) }
                runCatching { mic?.stop() }; mic?.release()
                runCatching { audio?.stop() }; audio?.release()
                runCatching { video.stop() }; video.release()
                val ok = synchronized(lock) { muxing.also { if (it) muxer.stop(); muxer.release() } }
                if (ok) name = publish()
            } catch (t: Throwable) {
                Diagnostics.log("Recorder stop failed: $t")
            } finally {
                temp.delete()
                done?.invoke(name)
            }
        }, "hx-rec-stop").start()
    }

    private fun publish(): String {
        val name = "HyperMagic_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/HyperMagic")
            }
            val uri = activity.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore refused the video")
            activity.contentResolver.openOutputStream(uri)!!.use { out -> temp.inputStream().use { it.copyTo(out) } }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "HyperMagic").apply { mkdirs() }
            val f = File(dir, name)
            temp.copyTo(f, overwrite = true)
            android.media.MediaScannerConnection.scanFile(activity, arrayOf(f.absolutePath), arrayOf("video/mp4"), null)
        }
        return name
    }
}

/**
 * Live 16:9 view for any browser on the same network: http://<phone>:8090 shows the image as
 * Motion JPEG (no app needed on the PC or TV). 720p at up to 15 fps.
 */
class MjpegServer {
    @Volatile var clients = 0; private set
    @Volatile private var frame: ByteArray? = null
    private val frameLock = Object()
    private var server: ServerSocket? = null
    private val encoder = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var encoding = false
    val running get() = server != null
    val port = 8090

    fun start(): Boolean {
        if (server != null) return true
        return try {
            val s = ServerSocket(port)
            server = s
            Thread({ accept(s) }, "hx-stream").apply { isDaemon = true; start() }
            true
        } catch (t: Throwable) { Diagnostics.log("Stream server failed: $t"); false }
    }

    fun stop() { runCatching { server?.close() }; server = null }

    /** Encodes the bitmap off the GL thread; frames arriving while one is encoding are skipped. */
    fun offer(bmp: android.graphics.Bitmap) {
        if (encoding) { bmp.recycle(); return }
        encoding = true
        encoder.execute {
            val out = java.io.ByteArrayOutputStream(200_000)
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out)
            bmp.recycle()
            synchronized(frameLock) { frame = out.toByteArray(); frameLock.notifyAll() }
            encoding = false
        }
    }

    private fun accept(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (_: Throwable) { return }
            Thread({ serve(c) }, "hx-stream-client").apply { isDaemon = true; start() }
        }
    }

    private fun serve(c: Socket) {
        c.use { sock ->
            val req = sock.getInputStream().bufferedReader().readLine() ?: return
            val out = sock.getOutputStream()
            if (!req.contains("/stream")) {
                val html = "<!doctype html><html><head><meta name=viewport content='width=device-width'><title>HyperMagic</title></head>" +
                    "<body style='margin:0;background:#000;display:flex;align-items:center;justify-content:center;height:100vh'>" +
                    "<img src='/stream' style='max-width:100%;max-height:100%;aspect-ratio:16/9'></body></html>"
                val body = html.toByteArray()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(body); out.flush(); return
            }
            out.write("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=hxframe\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray())
            synchronized(this) { clients++ }
            try {
                var last: ByteArray? = null
                while (server != null) {
                    val f = synchronized(frameLock) {
                        while (frame === last && server != null) frameLock.wait(500)
                        frame
                    } ?: continue
                    last = f
                    out.write("--hxframe\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.size}\r\n\r\n".toByteArray())
                    out.write(f); out.write("\r\n".toByteArray()); out.flush()
                }
            } catch (_: Throwable) {
            } finally { synchronized(this) { clients-- } }
        }
    }

    /** The phone's Wi-Fi address, for the URL shown in the panel. */
    fun url(): String? {
        val ip = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
        }.getOrNull() ?: return null
        return "http://$ip:$port"
    }
}

/**
 * Second screens (HDMI/USB-C, Miracast, casting that offers a presentation display): each shows
 * the 16:9 view full screen. [gl] runs work on the GL thread.
 */
class ExternalDisplays(private val activity: Activity, private val gl: (() -> Unit) -> Unit) {
    var share: ScreenShare? = null
    private val shown = HashMap<Int, Presentation>()
    @Volatile var count = 0; private set
    private var enabled = false
    private val dm = activity.getSystemService(DisplayManager::class.java)
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = refresh()
        override fun onDisplayRemoved(id: Int) = refresh()
        override fun onDisplayChanged(id: Int) {}
    }

    /** Starts following presentation displays (UI thread). */
    fun start() {
        if (enabled) return
        enabled = true
        dm.registerDisplayListener(listener, null)
        refresh()
    }

    fun stop() = activity.runOnUiThread {
        if (!enabled) return@runOnUiThread
        enabled = false
        dm.unregisterDisplayListener(listener)
        shown.values.forEach { runCatching { it.dismiss() } }
        shown.clear(); count = 0
    }

    private fun refresh() {
        val displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).filter { it.displayId != Display.DEFAULT_DISPLAY }
        for (id in shown.keys.toList()) if (displays.none { it.displayId == id }) shown.remove(id)?.let { runCatching { it.dismiss() } }
        for (d in displays) if (d.displayId !in shown) show(d)
        count = shown.size
    }

    private fun show(d: Display) {
        val view = SurfaceView(activity)
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {}
            override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) {
                val s = h.surface
                gl { share?.let { sh -> sh.removeSink(s); sh.addSink(s, w, hh, recordable = false) } }
            }
            override fun surfaceDestroyed(h: SurfaceHolder) {
                val s = h.surface
                // the sink must go before the surface does: wait for the GL thread
                val latch = java.util.concurrent.CountDownLatch(1)
                gl { share?.removeSink(s); latch.countDown() }
                latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        })
        val p = Presentation(activity, d)
        p.setContentView(view)
        try { p.show(); shown[d.displayId] = p } catch (t: Throwable) { Diagnostics.log("Presentation on ${d.name} failed: $t") }
    }
}

/**
 * Android 15+ tells an app when something is recording or mirroring its window. While that
 * happens [recording] is true, and ScreenShare records its own 16:9 copy alongside.
 */
class ExternalRecordingDetector(private val activity: Activity) {
    @Volatile var recording = false; private set
    private var callback: java.util.function.Consumer<Int>? = null
    val supported get() = Build.VERSION.SDK_INT >= 35

    fun start() {
        if (Build.VERSION.SDK_INT < 35 || callback != null) return
        val cb = java.util.function.Consumer<Int> { state -> recording = state == android.view.WindowManager.SCREEN_RECORDING_STATE_VISIBLE }
        try {
            val initial = activity.windowManager.addScreenRecordingCallback(activity.mainExecutor, cb)
            recording = initial == android.view.WindowManager.SCREEN_RECORDING_STATE_VISIBLE
            callback = cb
        } catch (t: Throwable) { Diagnostics.log("Screen recording detection unavailable: $t") }
    }

    fun stop() {
        val cb = callback ?: return
        if (Build.VERSION.SDK_INT >= 35) runCatching { activity.windowManager.removeScreenRecordingCallback(cb) }
        callback = null; recording = false
    }
}
