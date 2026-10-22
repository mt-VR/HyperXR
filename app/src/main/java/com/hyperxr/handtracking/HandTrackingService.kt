package com.hyperxr.handtracking

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.diagnostics.LatencyStats
import com.hyperxr.diagnostics.Stage
import com.hyperxr.math.Pose
import com.hyperxr.settings.HandTrackingSettings
import com.hyperxr.settings.InferenceBackend
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera frames -> MediaPipe Hand Landmarker (GPU delegate, CPU fallback) -> HyperXR depth fusion
 * -> temporal tracking (One Euro, loss/recovery, prediction) -> gestures.
 *
 * Latency choices: only the newest frame is processed (a frame arriving while inference runs is
 * dropped instead of queued), the YUV->RGB conversion writes into reused buffers at a reduced
 * resolution, and MediaPipe's internal ROI tracking skips palm detection while hands stay visible.
 */
class HandTrackingService(private val context: Context) {
    private val thread = HandlerThread("hx-hands").apply { start() }
    private val handler = Handler(thread.looper)
    private val busy = AtomicBoolean(false)

    private var landmarker: HandLandmarker? = null
    @Volatile var status: String = "parado"; private set
    @Volatile var backendInUse: String = "-"; private set

    val trackers = mapOf(Handedness.LEFT to HandTracker(Handedness.LEFT), Handedness.RIGHT to HandTracker(Handedness.RIGHT))
    private val fusion = DepthFusion()
    private val fused = arrayOf(DepthFusion.Result(), DepthFusion.Result())
    private val fusedLabel = arrayOfNulls<Handedness>(2)
    private val fusedScore = FloatArray(2)
    private val fusedPinch = FloatArray(2)
    private var busySinceNs = 0L

    /** Live statistics (shown in the hand test screen and in Diagnostics). */
    @Volatile var stats: String = "-"; private set
    private var statFrames = 0
    private var statWithHands = 0
    private var statInferNs = 0L
    private var statStartNs = 0L

    /**
     * Test screen hook, called on the inference thread with the exact frame that was analysed and
     * the normalized landmarks found (x, y, z per joint). Must copy what it needs before returning.
     */
    @Volatile var debugListener: ((Bitmap, List<FloatArray>) -> Unit)? = null
    private var pendingBitmap: Bitmap? = null
    private val lock = Any()
    private val published = mapOf(Handedness.LEFT to HandState(Handedness.LEFT), Handedness.RIGHT to HandState(Handedness.RIGHT))
    private var minIntervalNs = 33_000_000L
    private var lastSubmitNs = 0L
    private var lastTimestampMs = 0L

    // Context of the frame currently inside MediaPipe (only one at a time).
    private var pendingIntr: CameraIntrinsics? = null
    private var pendingPose: Pose? = null
    private val pendingDepth = DepthSnapshot()
    private var pendingSubmitNs = 0L
    private val converter = YuvToBitmap()

    private val scaleEstimator = HandScaleEstimator()
    private var autoCalibrate = false
    /** Called once when auto calibration measured the palm (metres), to persist it. */
    var onPalmMeasured: ((Float) -> Unit)? = null
    val calibrationProgress: Float get() = scaleEstimator.progress

    fun configure(s: HandTrackingSettings, inferenceHz: Int) {
        fusion.palmLengthMeters = HandScale.palmLength(s.calibratedPalmMm, s.handLengthCm)
        autoCalibrate = s.autoCalibrateHandSize && s.calibratedPalmMm == 0f
        if (autoCalibrate) scaleEstimator.reset()
        minIntervalNs = 1_000_000_000L / inferenceHz.coerceIn(5, 60)
        trackers.values.forEach {
            it.setFilterParams(s.filterMinCutoff, s.filterBeta)
            it.pinchRatio = s.pinchClose
            it.predictionSeconds = s.predictionMs / 1000f
        }
    }

    /** Creates the landmarker on the worker thread. Returns false only if the model asset is missing. */
    fun start(backend: InferenceBackend): Boolean {
        val hasModel = try { context.assets.openFd(MODEL_ASSET).close(); true } catch (t: Throwable) { false }
        if (!hasModel) { status = "Modelo $MODEL_ASSET ausente do APK"; return false }
        handler.post {
            landmarker?.close()
            landmarker = null
            val order = if (backend == InferenceBackend.CPU) listOf(Delegate.CPU) else listOf(Delegate.GPU, Delegate.CPU)
            val failures = mutableListOf<String>()
            for (d in order) {
                try {
                    landmarker = create(d, RunningMode.LIVE_STREAM)
                    backendInUse = (if (d == Delegate.GPU) "GPU" else "CPU") + if (failures.isEmpty()) "" else " (fallback: ${failures.joinToString()})"
                    status = "rodando"
                    break
                } catch (t: Throwable) {
                    failures += "${d.name}: ${t.message}"
                }
            }
            if (landmarker == null) status = "Falha ao iniciar o MediaPipe: ${failures.joinToString()}"
            Diagnostics.log("Hand tracking: $status, backend $backendInUse")
        }
        return true
    }

    private fun create(delegate: Delegate, mode: RunningMode): HandLandmarker {
        val base = BaseOptions.builder().setModelAssetPath(MODEL_ASSET).setDelegate(delegate).build()
        val opts = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(mode)
            .setNumHands(2)
            // Lower presence/tracking thresholds keep MediaPipe on its cheap ROI tracking through
            // blur and partial occlusion instead of dropping the hand and re-running the detector.
            .setMinHandDetectionConfidence(0.45f)
            .setMinHandPresenceConfidence(0.35f)
            .setMinTrackingConfidence(0.35f)
        if (mode == RunningMode.LIVE_STREAM) {
            opts.setResultListener { result, _ -> onResult(result) }
            opts.setErrorListener { e -> status = "Erro de inferência: ${e.message}"; busy.set(false) }
        }
        return HandLandmarker.createFromOptions(context, opts.build())
    }

    fun stop() { handler.post { landmarker?.close(); landmarker = null; status = "parado" } }

    /**
     * Submit a camera frame (closed here). [cameraPose] is the camera pose in tracking space at
     * capture time; [rotation180] is true when the landscape display is upside down relative to
     * the sensor (ROTATION_270).
     */
    /** True when a new frame would be analysed now (lets callers skip acquiring camera images). */
    fun wantsFrame(): Boolean {
        val now = System.nanoTime()
        // Watchdog: a result that never arrives must not stop hand tracking forever.
        if (busy.get() && now - busySinceNs > 600_000_000L) busy.set(false)
        return landmarker != null && !busy.get() && now - lastSubmitNs >= minIntervalNs
    }

    fun submit(image: Image, intr: CameraIntrinsics, cameraPose: Pose?, depth: DepthImage?, rotation180: Boolean = false) {
        val lm = landmarker
        val now = System.nanoTime()
        if (lm == null || !wantsFrame() || !busy.compareAndSet(false, true)) { image.close(); return }
        busySinceNs = now
        lastSubmitNs = now
        val bitmap = try { converter.convert(image, rotation180) } finally { image.close() }
        pendingBitmap = bitmap
        pendingIntr = if (rotation180) intr.copy(cx = intr.width - intr.cx, cy = intr.height - intr.cy) else intr
        pendingPose = cameraPose?.copy()
        pendingDepth.capture(depth, rotation180)
        pendingSubmitNs = System.nanoTime()
        var ts = SystemClock.uptimeMillis()
        if (ts <= lastTimestampMs) ts = lastTimestampMs + 1 // MediaPipe needs strictly increasing timestamps
        lastTimestampMs = ts
        try {
            lm.detectAsync(BitmapImageBuilder(bitmap).build(), ts)
        } catch (t: Throwable) {
            busy.set(false)
            status = "detectAsync falhou: ${t.message}"
        }
    }

    private fun onResult(result: HandLandmarkerResult) {
        val t0 = System.nanoTime()
        Diagnostics.record(Stage.HAND_INFERENCE, t0 - pendingSubmitNs)
        val intr = pendingIntr ?: run { busy.set(false); return }
        val depth = if (pendingDepth.valid) pendingDepth else null
        var n = 0
        val debug = debugListener
        val debugLms = if (debug != null) ArrayList<FloatArray>(2) else null
        for (i in result.landmarks().indices) {
            if (n == 2) break
            val lms = result.landmarks()[i]
            if (lms.size < HandJoint.COUNT) continue
            val cat = result.handedness().getOrNull(i)?.firstOrNull()
            // MediaPipe labels assume a mirrored (selfie) image; the back camera is not mirrored.
            val label = if (cat?.categoryName() == "Left") Handedness.RIGHT else Handedness.LEFT
            val obs = LandmarkObservation(label)
            obs.score = cat?.score() ?: 0.8f
            for (j in 0 until HandJoint.COUNT) {
                val p = lms[j]
                obs.image[j * 3] = p.x(); obs.image[j * 3 + 1] = p.y(); obs.image[j * 3 + 2] = p.z()
            }
            result.worldLandmarks().getOrNull(i)?.takeIf { it.size >= HandJoint.COUNT }?.let { wl ->
                for (j in 0 until HandJoint.COUNT) { val p = wl[j]; obs.world[j * 3] = p.x(); obs.world[j * 3 + 1] = p.y(); obs.world[j * 3 + 2] = p.z() }
                obs.hasWorld = true
            }
            debugLms?.add(obs.image.copyOf())
            if (fusion.fuse(obs, intr, depth, pendingPose, fused[n])) {
                fusedLabel[n] = label; fusedScore[n] = obs.score; fusedPinch[n] = pinchRatioFromWorld(obs); n++
            }
        }
        val assign = HandAssignment.assign(n, { k -> fused[k].cameraSpace[HandJoint.WRIST] }, { k -> fusedLabel[k]!! }, trackers)
        val seen = HashSet<Handedness>()
        for (k in 0 until n) {
            val side = assign[k]
            val r = fused[k]
            if (autoCalibrate) scaleEstimator.add(r.cameraSpace, r.depthSource, r.confidence)?.let { palm ->
                autoCalibrate = false
                fusion.palmLengthMeters = palm
                Diagnostics.log("Hand size measured with ARCore Depth: palm ${"%.1f".format(palm * 100)} cm")
                onPalmMeasured?.invoke(palm)
            }
            trackers.getValue(side).update(r.cameraSpace, fusedScore[k], r.depthSource, t0, fusedPinch[k])
            seen += side
        }
        for ((side, tracker) in trackers) if (side !in seen) tracker.update(null, 0f, DepthSource.NONE, t0)
        synchronized(lock) { for ((side, tracker) in trackers) copy(tracker.state, published.getValue(side)) }
        Diagnostics.record(Stage.HAND_POSE, System.nanoTime() - t0)
        updateStats(t0, n)
        if (debug != null) pendingBitmap?.let { runCatching { debug(it, debugLms!!) } }
        busy.set(false)
    }

    private fun updateStats(now: Long, hands: Int) {
        if (statStartNs == 0L) statStartNs = now
        statFrames++
        if (hands > 0) statWithHands++
        statInferNs += now - pendingSubmitNs
        val span = now - statStartNs
        if (span >= 1_000_000_000L) {
            val fps = statFrames * 1e9f / span
            stats = "%.0f quadros/s analisados · mão em %d%% · inferência %.0f ms · %s · %dx%d".format(
                fps, statWithHands * 100 / statFrames.coerceAtLeast(1), statInferNs / 1e6f / statFrames.coerceAtLeast(1),
                backendInUse, pendingBitmap?.width ?: 0, pendingBitmap?.height ?: 0,
            )
            statFrames = 0; statWithHands = 0; statInferNs = 0; statStartNs = now
        }
    }

    /** Thread-safe copy of the latest hand states. */
    fun snapshot(left: HandState, right: HandState) {
        synchronized(lock) {
            copy(published.getValue(Handedness.LEFT), left)
            copy(published.getValue(Handedness.RIGHT), right)
        }
    }

    private fun copy(src: HandState, dst: HandState) {
        for (i in 0 until HandJoint.COUNT) dst.joints[i].set(src.joints[i])
        dst.state = src.state; dst.confidence = src.confidence; dst.depthSource = src.depthSource
        dst.pinchStrength = src.pinchStrength; dst.pinchRatio = src.pinchRatio; dst.gesture = src.gesture; dst.timestampNs = src.timestampNs
    }

    /**
     * Times MediaPipe on GPU and CPU (IMAGE mode, [iterations] runs after warm-up). The input is a
     * synthetic frame without hands, so this measures palm detection, the stage that runs when no
     * hand is being tracked.
     */
    fun benchmark(iterations: Int = 40): List<String> {
        val out = mutableListOf<String>()
        val bmp = Bitmap.createBitmap(480, 360, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF808080.toInt()) }
        val img = BitmapImageBuilder(bmp).build()
        for (d in listOf(Delegate.GPU, Delegate.CPU)) {
            try {
                val lm = create(d, RunningMode.IMAGE)
                try {
                    val stats = LatencyStats("MediaPipe ${d.name} (detector de palma)", iterations)
                    repeat(5) { lm.detect(img) }
                    repeat(iterations) {
                        val t0 = System.nanoTime()
                        lm.detect(img)
                        stats.record(System.nanoTime() - t0)
                    }
                    out += stats.snapshot().format()
                } finally { lm.close() }
            } catch (t: Throwable) {
                out += "${d.name}: indisponível (${t.message})"
            }
        }
        return out
    }

    fun shutdown() { stop(); thread.quitSafely() }

    companion object {
        const val MODEL_ASSET = "models/hand_landmarker.task"
        /** Full 640x480 frames: hands at arm's length are small, and downscaling cost landmark quality. */
        const val MAX_WIDTH = 640
    }
}

/** A CPU depth image (ARCore 16-bit millimetres) handed to the hand pipeline by the caller. */
class DepthImage(val buffer: ByteBuffer, val width: Int, val height: Int, val rowStride: Int, val pixelStride: Int, val sourceWidth: Int, val sourceHeight: Int)

/** Copy of the depth image taken at submit time, because inference finishes asynchronously. */
class DepthSnapshot : DepthSampler {
    private var data = ShortArray(0)
    private var w = 0; private var h = 0
    private var srcW = 1; private var srcH = 1
    private var flip = false
    var valid = false; private set

    fun capture(d: DepthImage?, rotation180: Boolean) {
        if (d == null) { valid = false; return }
        if (data.size < d.width * d.height) data = ShortArray(d.width * d.height)
        w = d.width; h = d.height; srcW = d.sourceWidth; srcH = d.sourceHeight; flip = rotation180
        for (y in 0 until h) for (x in 0 until w) data[y * w + x] = d.buffer.getShort(y * d.rowStride + x * d.pixelStride)
        valid = true
    }

    override fun depthAt(px: Float, py: Float): Float {
        var x = (px / srcW * w).toInt(); var y = (py / srcH * h).toInt()
        if (flip) { x = w - 1 - x; y = h - 1 - y }
        if (x !in 0 until w || y !in 0 until h) return Float.NaN
        val mm = data[y * w + x].toInt() and 0xffff
        return if (mm == 0) Float.NaN else mm / 1000f
    }
}

/**
 * YUV_420_888 -> ARGB bitmap at <= [HandTrackingService.MAX_WIDTH] px wide, written into buffers
 * that are reused frame after frame (no per-frame allocation). Integer BT.601 conversion.
 */
class YuvToBitmap {
    private var pixels = IntArray(0)
    private var bitmap: Bitmap? = null

    fun convert(img: Image, rotate180: Boolean): Bitmap {
        val step = maxOf(1, (img.width + HandTrackingService.MAX_WIDTH - 1) / HandTrackingService.MAX_WIDTH)
        val ow = img.width / step
        val oh = img.height / step
        if (pixels.size != ow * oh) pixels = IntArray(ow * oh)
        val b = bitmap?.takeIf { it.width == ow && it.height == oh } ?: Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888).also { bitmap = it }
        val p = img.planes
        val yb = p[0].buffer; val ub = p[1].buffer; val vb = p[2].buffer
        val yRow = p[0].rowStride; val yPix = p[0].pixelStride
        val uvRow = p[1].rowStride; val uvPix = p[1].pixelStride
        for (oy in 0 until oh) {
            val sy = oy * step
            val yBase = sy * yRow
            val uvBase = (sy shr 1) * uvRow
            val dstRow = (if (rotate180) oh - 1 - oy else oy) * ow
            for (ox in 0 until ow) {
                val sx = ox * step
                val yy = (yb.get(yBase + sx * yPix).toInt() and 0xff) - 16
                val ui = uvBase + (sx shr 1) * uvPix
                val u = (ub.get(ui).toInt() and 0xff) - 128
                val v = (vb.get(ui).toInt() and 0xff) - 128
                val c = 1192 * (if (yy < 0) 0 else yy)
                var r = (c + 1634 * v) shr 10; if (r < 0) r = 0 else if (r > 255) r = 255
                var g = (c - 833 * v - 400 * u) shr 10; if (g < 0) g = 0 else if (g > 255) g = 255
                var bl = (c + 2066 * u) shr 10; if (bl < 0) bl = 0 else if (bl > 255) bl = 255
                pixels[dstRow + (if (rotate180) ow - 1 - ox else ox)] = (0xff shl 24) or (r shl 16) or (g shl 8) or bl
            }
        }
        b.setPixels(pixels, 0, ow, 0, 0, ow, oh)
        return b
    }
}
