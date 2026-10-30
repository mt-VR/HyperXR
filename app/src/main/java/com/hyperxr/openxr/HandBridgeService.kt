package com.hyperxr.openxr

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Display
import android.view.Surface
import com.hyperxr.HyperXRApp
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.handtracking.CameraIntrinsics
import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.HandState
import com.hyperxr.handtracking.HandTrackingService
import com.hyperxr.handtracking.Handedness
import com.hyperxr.handtracking.TrackingState

/**
 * Hands for OpenXR games (Meta / Pico hand tracking): while a game runs, HyperMagic is in the
 * background, so its hand tracking moves into this foreground service. It is started just before
 * a game is launched (an app may only use the camera from a foreground service it started while
 * visible), opens the back camera only while the game's runtime says it has a hand tracker, and
 * publishes the hands ~90 times a second in [HandBridge]'s shared file. When the game shows
 * passthrough (XR_FB_passthrough / ALPHA_BLEND), it also publishes small colour frames (~30 fps).
 *
 * When the game tracks the face (Meta's face tracking from audio), it measures the voice on the
 * microphone (only the loudness, nothing is recorded or kept) while the game asks for it.
 *
 * It also plays the game's vibrations on the controller of that hand (Joy-Con L / R or a gamepad):
 * the runtime inside the game may not vibrate anything itself.
 *
 * It stops by itself when no OpenXR runtime has been alive for a while (the game closed), and when
 * HyperMagic comes back to the front (it needs the camera itself).
 */
class HandBridgeService : Service() {
    private val worker = HandlerThread("hx-handbridge").apply { start() }
    private val handler = Handler(worker.looper)
    private val camThread = HandlerThread("hx-handbridge-cam").apply { start() }
    private val camHandler = Handler(camThread.looper)
    private var hands: HandTrackingService? = null
    private var map = 0L
    private var cameraMap = 0L
    @Volatile private var handsWanted = false
    @Volatile private var passthroughWanted = false
    private val frameConverter = com.hyperxr.handtracking.YuvToBitmap()
    private var frameBuffer: java.nio.ByteBuffer? = null
    private var lastFrameNs = 0L
    private val hapticOut = FloatArray(3)
    private var micAllowed = false
    @Volatile private var voiceThread: Thread? = null
    @Volatile private var voiceRun = false
    private var hapticSeq = -1
    private var device: CameraDevice? = null
    private var reader: ImageReader? = null
    private var opening = false
    private var lastOpenTryNs = 0L
    private val startedNs = System.nanoTime()
    private val left = HandState(Handedness.LEFT)
    private val right = HandState(Handedness.RIGHT)
    private val state = FloatArray(6)
    private val joints = FloatArray(2 * HandJoint.COUNT * 3)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        map = HandBridge.nativeOpen(HandBridge.file(this).absolutePath)
        cameraMap = HandBridge.nativeCameraOpen(HandBridge.cameraFile(this).absolutePath)
        val s = HyperXRApp.instance.settings.current
        hands = HandTrackingService(this).also {
            it.configure(s.handTracking, s.performance.handInferenceHz)
            it.start(s.handTracking.backend)
        }
        handler.post(tick)
        Diagnostics.log("Hand bridge for OpenXR games started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Mãos nos jogos OpenXR", NotificationManager.IMPORTANCE_LOW))
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("HyperMagic: mãos no jogo")
            .setContentText("A câmera rastreia suas mãos enquanto o jogo pede")
            .setOngoing(true)
            .build()
        // the microphone joins only when its permission was given (a type without it is refused)
        val mic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        micAllowed = mic
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION_ID, n,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0))
        else startForeground(NOTIFICATION_ID, n)
    }

    /** Publishes the hands and turns the camera on and off with the game's demand. */
    private val tick = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val alive = HandBridge.nativeReaderAlive(map)
            val wants = HandBridge.nativeReaderWantsHands(map)
            // the game is gone: no runtime for a while (after the first one appeared, or never)
            val lastSign = maxOf(alive, startedNs)
            if (now - lastSign > IDLE_STOP_NS) { stopSelf(); return }
            handsWanted = wants != 0L && now - wants < CAMERA_IDLE_NS && HyperXRApp.instance.settings.current.handTracking.openxrHands
            playHaptics()
            val voice = HandBridge.nativeReaderWantsVoice(map)
            val voiceWanted = micAllowed && voice != 0L && now - voice < PASSTHROUGH_IDLE_NS
            if (voiceWanted && voiceThread == null) startVoice() else if (!voiceWanted && voiceThread != null) stopVoice()
            val pt = HandBridge.nativeCameraWanted(cameraMap)
            passthroughWanted = pt != 0L && now - pt < PASSTHROUGH_IDLE_NS
            val wanted = handsWanted || passthroughWanted
            if (wanted && device == null && !opening && now - lastOpenTryNs > 2_000_000_000L) openCamera()
            if (!wanted && device != null) closeCamera()
            if (device != null && handsWanted) publish(now)
            // quick ticks while a game runs (vibrations must not wait), slow ones otherwise
            handler.postDelayed(this, if (device != null) PUBLISH_MS else if (now - alive < 3_000_000_000L) 15L else 250L)
        }
    }

    private fun publish(now: Long) {
        val h = hands ?: return
        h.snapshot(left, right)
        for ((k, hs) in listOf(left, right).withIndex()) {
            val tracked = hs.state == TrackingState.TRACKING
            state[k * 3] = if (tracked) 1f else 0f
            state[k * 3 + 1] = hs.confidence
            state[k * 3 + 2] = hs.pinchStrength
            for (j in 0 until HandJoint.COUNT) {
                val o = (k * HandJoint.COUNT + j) * 3
                joints[o] = hs.joints[j].x; joints[o + 1] = hs.joints[j].y; joints[o + 2] = hs.joints[j].z
            }
        }
        HandBridge.nativeWrite(map, now, state, joints)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        lastOpenTryNs = System.nanoTime()
        val cm = getSystemService(CameraManager::class.java)
        val id = runCatching {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK }
        }.getOrNull() ?: return
        val ch = cm.getCameraCharacteristics(id)
        val cfg = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val size = cfg.getOutputSizes(ImageFormat.YUV_420_888).minByOrNull { kotlin.math.abs(it.width - 640) + kotlin.math.abs(it.height - 480) } ?: return
        val sensor = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val fx = if (sensor != null && focal != null) size.width * focal / sensor.width else size.width / (2f * kotlin.math.tan(0.55f))
        val intr = CameraIntrinsics(fx, fx, size.width / 2f, size.height / 2f, size.width, size.height)
        // the game's landscape side decides whether the image is upside down
        val rotation = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_90
        val rot180 = rotation == Surface.ROTATION_270
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (passthroughWanted) runCatching { publishFrame(img, intr, rot180) }
            if (handsWanted) hands?.submit(img, intr, null, null, rotation180 = rot180) ?: img.close() else img.close()
        }, camHandler)
        reader = r
        opening = true
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    opening = false
                    device = d
                    @Suppress("DEPRECATION")
                    d.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            runCatching { s.setRepeatingRequest(d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(r.surface) }.build(), null, camHandler) }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { Diagnostics.log("Hand bridge: camera session failed") }
                    }, camHandler)
                }
                override fun onDisconnected(d: CameraDevice) { opening = false; d.close(); if (device === d) device = null }
                override fun onError(d: CameraDevice, e: Int) {
                    opening = false; d.close(); if (device === d) device = null
                    Diagnostics.log("Hand bridge: camera error $e (in use by another app?)")
                }
            }, camHandler)
        } catch (t: Throwable) {
            opening = false
            Diagnostics.log("Hand bridge: camera not opened: ${t.message}")
        }
    }

    /** A passthrough frame for the game (at most ~30 per second), RGBA at <= 640 px wide. */
    private fun publishFrame(img: android.media.Image, intr: CameraIntrinsics, rot180: Boolean) {
        val now = System.nanoTime()
        if (now - lastFrameNs < FRAME_INTERVAL_NS || cameraMap == 0L) return
        lastFrameNs = now
        // when the light hit the sensor (the camera's timestamp is REALTIME on nearly every phone)
        val age = android.os.SystemClock.elapsedRealtimeNanos() - img.timestamp
        val captured = now - if (age in 0..250_000_000L) age else 0L
        val bmp = frameConverter.convert(img, rot180)
        val bytes = bmp.width * bmp.height * 4
        val buf = frameBuffer?.takeIf { it.capacity() >= bytes } ?: java.nio.ByteBuffer.allocateDirect(bytes).also { frameBuffer = it }
        buf.clear()
        bmp.copyPixelsToBuffer(buf)  // ARGB_8888 is R, G, B, A in memory: what GL_RGBA expects
        val s = bmp.width.toFloat() / intr.width
        val cx = (if (rot180) intr.width - intr.cx else intr.cx) * s
        val cy = (if (rot180) intr.height - intr.cy else intr.cy) * s
        HandBridge.nativeCameraWrite(cameraMap, captured, buf, bmp.width, bmp.height, intr.fx * s, intr.fy * s, cx, cy)
    }

    /** Measures the voice loudness every 20 ms for the game's face tracker. */
    @SuppressLint("MissingPermission")
    private fun startVoice() {
        voiceRun = true
        voiceThread = Thread({
            val rate = 16000
            val min = android.media.AudioRecord.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
            val rec = runCatching {
                android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 3200))
            }.getOrNull()
            if (rec == null || rec.state != android.media.AudioRecord.STATE_INITIALIZED) { rec?.release(); voiceThread = null; return@Thread }
            val buf = ShortArray(320)
            var smooth = 0f
            runCatching {
                rec.startRecording()
                while (voiceRun) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                    val rms = kotlin.math.sqrt(sum / n) / 32768.0
                    val db = 20 * kotlin.math.log10(rms + 1e-9)
                    val level = ((db + 50) / 40).toFloat().coerceIn(0f, 1f)  // -50 dBFS silent .. -10 dBFS loud
                    smooth += (level - smooth) * (if (level > smooth) 0.6f else 0.25f)  // opens fast, closes softly
                    HandBridge.nativeVoice(map, System.nanoTime(), smooth)
                }
            }
            runCatching { rec.stop() }
            rec.release()
        }, "hx-handbridge-voice").apply { start() }
    }

    private fun stopVoice() {
        voiceRun = false
        voiceThread?.join(500)
        voiceThread = null
    }

    /** Plays the game's newest vibration request on the controller of that hand. */
    private fun playHaptics() {
        val seq = HandBridge.nativeHaptic(map, hapticOut)
        if (hapticSeq == -1) { hapticSeq = seq; return }  // requests from before this service started are old
        if (seq == hapticSeq) return
        hapticSeq = seq
        val hand = hapticOut[0].toInt()
        val amplitude = hapticOut[1].coerceIn(0f, 1f)
        val ms = hapticOut[2].toLong().coerceIn(0L, 2000L)
        runCatching { vibrateController(hand, amplitude, ms) }
    }

    /** Joy-Con (L)/(R) by name; with one gamepad it plays both hands; nothing when none can vibrate. */
    @Suppress("DEPRECATION")
    private fun vibrateController(hand: Int, amplitude: Float, ms: Long) {
        val pads = ArrayList<android.view.InputDevice>()
        for (id in android.view.InputDevice.getDeviceIds()) {
            val d = android.view.InputDevice.getDevice(id) ?: continue
            val src = d.sources
            if ((src and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD ||
                (src and android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK) pads += d
        }
        val byName = pads.firstOrNull { (if (hand == 0) "(L)" else "(R)") in it.name }
        val pad = byName ?: (if (pads.size >= 2) pads.getOrNull(hand) else pads.firstOrNull()) ?: return
        val vib: android.os.Vibrator = if (Build.VERSION.SDK_INT >= 31) pad.vibratorManager.defaultVibrator else pad.vibrator
        if (!vib.hasVibrator()) return
        if (amplitude <= 0f || ms <= 0L) { vib.cancel(); return }
        val a = if (vib.hasAmplitudeControl()) (amplitude * 255).toInt().coerceIn(1, 255) else android.os.VibrationEffect.DEFAULT_AMPLITUDE
        vib.vibrate(android.os.VibrationEffect.createOneShot(ms, a))
    }

    private fun closeCamera() {
        runCatching { device?.close() }
        device = null
        runCatching { reader?.close() }
        reader = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopVoice()
        closeCamera()
        hands?.shutdown(); hands = null
        // nothing tracked any more: the game sees no hands rather than frozen ones
        if (map != 0L) { HandBridge.nativeWrite(map, System.nanoTime(), FloatArray(6), joints); HandBridge.nativeClose(map); map = 0L }
        if (cameraMap != 0L) { HandBridge.nativeCameraClose(cameraMap); cameraMap = 0L }
        worker.quitSafely(); camThread.quitSafely()
        Diagnostics.log("Hand bridge for OpenXR games stopped")
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "openxr-hands"
        private const val NOTIFICATION_ID = 4711
        private const val ACTION_STOP = "com.hyperxr.openxr.HANDS_STOP"
        private const val PUBLISH_MS = 11L
        /** Camera off this long after the game last asked for hands. */
        private const val CAMERA_IDLE_NS = 5_000_000_000L
        /** Passthrough frames stop this long after the game last showed the camera. */
        private const val PASSTHROUGH_IDLE_NS = 3_000_000_000L
        private const val FRAME_INTERVAL_NS = 30_000_000L
        /** Service gone when no OpenXR runtime has been alive this long. */
        private const val IDLE_STOP_NS = 90_000_000_000L

        /** Called while HyperMagic is still in front, just before an OpenXR game is started. */
        fun start(ctx: Context) {
            // a camera foreground service needs the camera permission (HyperMagic asks for it on first run)
            if (ctx.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            HandBridge.ensure(ctx)
            HandBridge.ensureCamera(ctx)
            runCatching {
                val i = Intent(ctx, HandBridgeService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }.onFailure { Diagnostics.log("Hand bridge not started: ${it.message}") }
        }

        /** HyperMagic is back in front and wants its camera. */
        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, HandBridgeService::class.java)) }
        }
    }
}
