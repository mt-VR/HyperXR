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
import com.hyperxr.math.Vec3
import com.hyperxr.settings.HandTrackingSettings
import com.hyperxr.settings.InferenceBackend
import java.nio.ByteBuffer

/**
 * Camera frames -> hand engine (HyperXR's own tracker on the NPU, or MediaPipe on GPU/CPU) ->
 * HandVerifier (is it really a hand?) -> HyperXR depth fusion -> temporal tracking (One Euro,
 * loss/recovery, prediction) -> gestures.
 *
 * Latency choices: only the newest frame is processed (a frame arriving while inference runs is
 * dropped instead of queued), the YUV->RGB conversion writes into reused buffers at a reduced
 * resolution, and MediaPipe's internal ROI tracking skips palm detection while hands stay visible.
 */
class HandTrackingService(private val context: Context) {
    private val thread = HandlerThread("hx-hands", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)

    private var landmarker: HandLandmarker? = null
    /** HyperXR's own NPU engine (backend NNAPI), or null when MediaPipe runs. */
    @Volatile private var npu: HandEngine? = null
    /** Rejects what is not the user's hand (feet, curtains, other people), for both engines. */
    val verifier = HandVerifier()
    /** Decides left/right itself (3D chirality + continuity), whatever the engine says. */
    private val handedness = HandednessResolver()
    @Volatile var status: String = "parado"; private set
    @Volatile var backendInUse: String = "-"; private set
    /** HybridMagic: frames go to the PC's GPU while the HybridMagic PC program is connected. */
    @Volatile var hybrid = false
    /** Wraps a phone engine so the PC tracks while it is there (the phone's engine otherwise). */
    private fun withPc(e: HandEngine): HandEngine = if (hybrid) com.hyperxr.hybrid.HybridHandEngine(e) else e

    val trackers = mapOf(Handedness.LEFT to HandTracker(Handedness.LEFT), Handedness.RIGHT to HandTracker(Handedness.RIGHT))
    private val fusion = DepthFusion()
    private val fused = arrayOf(DepthFusion.Result(), DepthFusion.Result())
    private val fusedLabel = arrayOfNulls<Handedness>(2)
    private val fusedScore = FloatArray(2)
    private val fusedPinch = FloatArray(2)
    /** Reused per result (two per side: MediaPipe may label both hands the same way). */
    private val observations = mapOf(
        Handedness.LEFT to arrayOf(LandmarkObservation(Handedness.LEFT), LandmarkObservation(Handedness.LEFT)),
        Handedness.RIGHT to arrayOf(LandmarkObservation(Handedness.RIGHT), LandmarkObservation(Handedness.RIGHT)),
    )
    private val seen = java.util.EnumSet.noneOf(Handedness::class.java)

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
    private val lock = Any()
    private val published = mapOf(Handedness.LEFT to HandState(Handedness.LEFT), Handedness.RIGHT to HandState(Handedness.RIGHT))
    private var minIntervalNs = 33_000_000L
    /** Whether the rate may rise above the setting when the engine is fast (off for battery profiles). */
    private var boostRate = true
    /** Smoothed inference time and camera-exposure-to-result age, measured per frame. */
    private var inferEwmaNs = 30_000_000L
    private var ageEwmaNs = 60_000_000L
    /** User trim on top of the measured latency (the "prediction" setting; 20 ms = neutral). */
    private var predictionTrimS = 0f
    private var lastSubmitNs = 0L
    private var lastTimestampMs = 0L

    /**
     * Two frame slots pipeline the work: while the GPU runs inference on one slot, the camera
     * thread converts the next frame into the other (split across two CPU threads), and the next
     * inference starts the moment the previous result arrives. The newest frame always wins the
     * queued slot, so latency stays one frame at most.
     */
    private class Slot {
        val converter = YuvToBitmap()
        var bitmap: Bitmap? = null
        var intr: CameraIntrinsics? = null
        var pose: Pose? = null
        val depth = DepthSnapshot()
        var captureNs = 0L
        var submitNs = 0L
        var rotation180 = false
    }
    // one on the GPU, one being post-processed, one being converted / queued
    private val slots = arrayOf(Slot(), Slot(), Slot())
    private val slotLock = Any()
    private var inflight: Slot? = null
    private var queued: Slot? = null
    private var converting: Slot? = null
    private var processing: Slot? = null
    private var inflightSinceNs = 0L
    private var backendRequested = InferenceBackend.GPU
    private var restarting = false
    private var lastAnalysed: Slot? = null

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
        boostRate = inferenceHz >= 30
        predictionTrimS = (s.predictionMs - 20f) / 1000f
        trackers.values.forEach {
            it.setFilterParams(s.filterMinCutoff, s.filterBeta)
            it.pinchRatio = s.pinchClose
            it.setProcessNoise(s.kalmanProcessNoise)
            it.skeleton.reset() // bone lengths re-learned at the (possibly new) hand scale
            it.predictionSeconds = s.predictionMs / 1000f
        }
    }

    /**
     * Creates the engine on the worker thread: NNAPI = HyperXR's own tracker on the NPU, otherwise
     * MediaPipe (GPU, CPU fallback). Returns false only if the model asset is missing.
     */
    fun start(backend: InferenceBackend): Boolean {
        backendRequested = backend
        val hasModel = try { context.assets.openFd(MODEL_ASSET).close(); true } catch (t: Throwable) { false }
        if (!hasModel) { status = "Modelo $MODEL_ASSET ausente do APK"; return false }
        handler.post {
            landmarker?.close(); landmarker = null
            npu?.close(); npu = null
            verifier.reset(); handedness.reset()
            // RTMPose (OpenMMLab) first on every backend: NPU, GPU or CPU as asked, falling back down
            val rtm = RtmHandEngine(context)
            if (runCatching { rtm.start(backend) }.onFailure { Diagnostics.log("Hand tracking: RTMPose failed: ${it.message}") }.getOrDefault(false)) {
                npu = withPc(rtm)
                backendInUse = "RTMPose · ${rtm.device}" + if (hybrid) " + HybridMagic" else ""
                status = "rodando"
                synchronized(slotLock) { inflight = null; queued = null; restarting = false }
                Diagnostics.log("Hand tracking: RTMPose on ${rtm.device}")
                return@post
            }
            rtm.close()
            if (backend == InferenceBackend.NNAPI) {
                val e = NpuHandEngine(context)
                val ok = runCatching { e.start() }.getOrDefault(false)
                if (ok) {
                    npu = withPc(e)
                    backendInUse = "MediaPipe NPU · ${e.device} (RTMPose indisponível)"
                    status = "rodando"
                    synchronized(slotLock) { inflight = null; queued = null; restarting = false }
                    Diagnostics.log("Hand tracking: MediaPipe models on ${e.device}")
                    return@post
                }
                Diagnostics.log("Hand tracking: NPU engine unavailable, using MediaPipe")
            }
            val order = if (backend == InferenceBackend.CPU) listOf(Delegate.CPU) else listOf(Delegate.GPU, Delegate.CPU)
            val failures = mutableListOf<String>()
            if (backend == InferenceBackend.NNAPI) failures += "NPU indisponível"
            for (d in order) {
                try {
                    landmarker = create(d, RunningMode.LIVE_STREAM)
                    backendInUse = "MediaPipe " + (if (d == Delegate.GPU) "GPU" else "CPU") + if (failures.isEmpty()) "" else " (fallback: ${failures.joinToString()})"
                    status = "rodando"
                    break
                } catch (t: Throwable) {
                    failures += "${d.name}: ${t.message}"
                }
            }
            if (landmarker == null) status = "Falha ao iniciar o MediaPipe: ${failures.joinToString()}"
            synchronized(slotLock) { inflight = null; queued = null; restarting = false }
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
            // Presence at 0.5 (was 0.35): measured on photos, the sole of a foot scores up to 0.73 and
            // curtains under 0.1, so a low threshold tracked feet; HandVerifier checks the rest.
            // permissive here: HandVerifier decides what is a hand (shape, size, reach, presence),
            // and a hand found or kept by MediaPipe a frame earlier shows up a frame earlier
            .setMinHandDetectionConfidence(0.42f)
            .setMinHandPresenceConfidence(0.35f)
            .setMinTrackingConfidence(0.35f)
        if (mode == RunningMode.LIVE_STREAM) {
            opts.setResultListener { result, _ -> onResult(result) }
            opts.setErrorListener { e -> status = "Erro de inferência: ${e.message}"; finishInflight() }
        }
        return HandLandmarker.createFromOptions(context, opts.build())
    }

    fun stop() { handler.post { landmarker?.close(); landmarker = null; npu?.close(); npu = null; status = "parado" } }

    /**
     * Frames are taken as fast as the engine finishes them, up to 60 per second, when the setting
     * allows 30 or more: an NPU that answers in 8 ms tracks at the camera's 60 fps instead of 30.
     */
    /**
     * ADPF (Android 12+): the inference thread tells the system how long each frame took against
     * the time it has, so the CPU is clocked up to meet it instead of idling between camera frames.
     */
    private var hint: Any? = null
    private var hintTried = false
    private fun reportWork(ns: Long) {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        if (!hintTried) {
            hintTried = true
            hint = runCatching {
                val pm = context.getSystemService(android.os.PerformanceHintManager::class.java)
                pm?.createHintSession(intArrayOf(android.os.Process.myTid()), intervalNs())
            }.getOrNull()
        }
        (hint as? android.os.PerformanceHintManager.Session)?.let { runCatching { it.reportActualWorkDuration(ns.coerceAtLeast(1)) } }
    }

    private fun intervalNs(): Long =
        if (!boostRate) minIntervalNs else minOf(minIntervalNs, maxOf(16_000_000L, inferEwmaNs * 11 / 10))

    /** True when a new frame would be used now (lets callers skip acquiring camera images). */
    fun wantsFrame(): Boolean {
        val now = System.nanoTime()
        synchronized(slotLock) {
            // Watchdog: a result that never arrives must not stop hand tracking forever. A stuck
            // graph is recreated (the same backend), which costs ~1 s once instead of losing hands.
            if (inflight != null && now - inflightSinceNs > 1_500_000_000L && !restarting) {
                restarting = true
                Diagnostics.log("Hand tracking: no result for 1.5 s, restarting MediaPipe")
                start(backendRequested)
            }
            return (landmarker != null || npu != null) && !restarting && converting == null && now - lastSubmitNs >= intervalNs()
        }
    }

    /**
     * Submit a camera frame (closed here). [cameraPose] is the camera pose in tracking space at
     * capture time; [rotation180] is true when the landscape display is upside down relative to
     * the sensor (ROTATION_270).
     */
    fun submit(image: Image, intr: CameraIntrinsics, cameraPose: Pose?, depth: DepthImage?, rotation180: Boolean = false) {
        if (!wantsFrame()) { image.close(); return }
        val imageTs = image.timestampSafe  // read before the image is closed
        val slot: Slot
        synchronized(slotLock) {
            // any slot that is not on the GPU (an unsent queued frame is replaced by this newer one)
            slot = slots.first { it !== inflight && it !== processing }
            if (queued === slot) queued = null
            converting = slot
        }
        lastSubmitNs = System.nanoTime()
        try {
            slot.bitmap = slot.converter.convert(image, rotation180, pool)
        } finally { image.close() }
        slot.intr = if (rotation180) intr.copy(cx = intr.width - intr.cx, cy = intr.height - intr.cy) else intr
        slot.rotation180 = rotation180
        slot.pose = cameraPose?.copy()
        slot.depth.capture(depth, rotation180)
        // when the light hit the sensor, not when the frame reached us: the camera's timestamp
        // (REALTIME on nearly every phone) says how old the image already is
        val sensorAge = SystemClock.elapsedRealtimeNanos() - imageTs
        slot.captureNs = lastSubmitNs - if (sensorAge in 0..250_000_000L) sensorAge else 0L
        val launchNow: Boolean
        synchronized(slotLock) {
            converting = null
            launchNow = inflight == null
            if (launchNow) { inflight = slot; inflightSinceNs = System.nanoTime() } else queued = slot
        }
        if (launchNow) launch(slot)
    }

    /**
     * Inference always starts on our own worker thread: MediaPipe's result callback runs on the
     * graph's thread, and feeding the graph from inside it deadlocks the pipeline (the first
     * result arrives, then nothing more).
     */
    private fun launch(slot: Slot) { handler.post { launchNow(slot) } }

    private fun launchNow(slot: Slot) {
        val bmp = slot.bitmap ?: run { finishInflight(); return }
        slot.submitNs = System.nanoTime()
        npu?.let { engine ->
            // the NPU call is synchronous on this thread; post-processing follows right away
            val hands = try { engine.process(bmp) } catch (t: Throwable) { status = "NPU falhou: ${t.message}"; finishInflight(); return }
            val t0 = System.nanoTime()
            synchronized(slotLock) { processing = slot }
            finishInflight()
            try { processHands(hands, slot, t0) } finally { synchronized(slotLock) { if (processing === slot) processing = null } }
            return
        }
        val lm = landmarker ?: run { finishInflight(); return }
        var ts = SystemClock.uptimeMillis()
        if (ts <= lastTimestampMs) ts = lastTimestampMs + 1 // MediaPipe needs strictly increasing timestamps
        lastTimestampMs = ts
        try {
            lm.detectAsync(BitmapImageBuilder(bmp).build(), ts)
        } catch (t: Throwable) {
            status = "detectAsync falhou: ${t.message}"
            finishInflight()
        }
    }

    /** Ends the in-flight inference and immediately starts the queued frame, if any. */
    private fun finishInflight() {
        val next: Slot?
        synchronized(slotLock) {
            inflight = null
            next = queued
            queued = null
            if (next != null) { inflight = next; inflightSinceNs = System.nanoTime() }
        }
        if (next != null) launch(next)
    }

    /** Second CPU thread for the YUV->RGB conversion (the camera thread does the other half). */
    private val pool = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "hx-hands-yuv").apply { priority = Thread.MAX_PRIORITY } }

    private fun onResult(result: HandLandmarkerResult) {
        val t0 = System.nanoTime()
        val slot = synchronized(slotLock) { inflight?.also { processing = it } } ?: return
        // the GPU is free: start the queued frame first, then post-process this one on the CPU
        finishInflight()
        try { process(result, slot, t0) } finally { synchronized(slotLock) { if (processing === slot) processing = null } }
    }

    /** MediaPipe's result in the engine-neutral form (MediaPipe filters presence itself). */
    private fun process(result: HandLandmarkerResult, slot: Slot, t0: Long) {
        val hands = ArrayList<RawHand>(2)
        for (i in result.landmarks().indices) {
            val lms = result.landmarks()[i]
            if (lms.size < HandJoint.COUNT) continue
            val cat = result.handedness().getOrNull(i)?.firstOrNull()
            val image = FloatArray(63)
            for (j in 0 until HandJoint.COUNT) { val p = lms[j]; image[j * 3] = p.x(); image[j * 3 + 1] = p.y(); image[j * 3 + 2] = p.z() }
            val world = result.worldLandmarks().getOrNull(i)?.takeIf { it.size >= HandJoint.COUNT }?.let { wl ->
                FloatArray(63).also { w -> for (j in 0 until HandJoint.COUNT) { val p = wl[j]; w[j * 3] = p.x(); w[j * 3 + 1] = p.y(); w[j * 3 + 2] = p.z() } }
            }
            hands += RawHand(cat?.categoryName() ?: "Right", cat?.score() ?: 0.8f, Float.NaN, image, world)
        }
        processHands(hands, slot, t0)
    }

    private val presenceOf = FloatArray(2)
    private val nearEdge = BooleanArray(2)
    private val fusedImage = Array(2) { FloatArray(HandJoint.COUNT * 3) }
    private val sideImage = mapOf(Handedness.LEFT to FloatArray(HandJoint.COUNT * 3), Handedness.RIGHT to FloatArray(HandJoint.COUNT * 3))

    /**
     * Every analysed camera frame with its intrinsics and camera pose (tracking space), for the
     * room capture. Called on the hand tracking thread: copy what you need, the bitmap is reused.
     */
    @Volatile var frameTap: ((Bitmap, CameraIntrinsics, Pose, DepthSampler?) -> Unit)? = null

    /** The same frames for the colour-marked controller (its own callback, so it runs beside [frameTap]). */
    @Volatile var colorTap: ((Bitmap, CameraIntrinsics, Pose, DepthSampler?) -> Unit)? = null

    private fun afterFrame(slot: Slot) {
        colorTap?.let { tap -> val b = slot.bitmap; val i = slot.intr; if (b != null && i != null) runCatching { tap(b, i, slot.pose ?: NO_POSE, null) } }
        frameTap?.let { tap -> val b = slot.bitmap; val i = slot.intr; val p = slot.pose; if (b != null && i != null && p != null) runCatching { tap(b, i, p, if (slot.depth.valid) slot.depth else null) } }
    }

    // ------------------------------------------------------------------ arms (VR hands)
    /** True while the VR hands are drawn with their arms: the arm segmenter then runs (~15 Hz). */
    @Volatile var armTracking = false
    val arms by lazy { ArmSegEngine(context) }
    private val armThread = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "hx-arms") }
    private val armBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var armPreparing = false
    private var lastArmNs = 0L
    private val armResult = ArmFromMask.Result()

    private class ArmJob(val bmp: Bitmap, val intr: CameraIntrinsics, val pose: Pose, val captureNs: Long,
                         val sides: List<Triple<Handedness, FloatArray, Vec3>>)

    private fun maybeRunArms(slot: Slot, intr: CameraIntrinsics) {
        if (!armTracking || seen.isEmpty() || arms.failed) return
        val pose = slot.pose ?: return
        if (!arms.ready) {
            if (!armPreparing) { armPreparing = true; armThread.execute { arms.prepare(); armPreparing = false } }
            return
        }
        val now = System.nanoTime()
        if (now - lastArmNs < 66_000_000L || !armBusy.compareAndSet(false, true)) return
        lastArmNs = now
        val src = slot.bitmap ?: run { armBusy.set(false); return }
        val small = Bitmap.createScaledBitmap(src, ArmSegEngine.SIZE, ArmSegEngine.SIZE, true)
        val job = ArmJob(small, intr, pose.copy(), slot.captureNs,
            seen.map { Triple(it, sideImage.getValue(it).copyOf(), trackers.getValue(it).state.joints[HandJoint.WRIST].copy()) })
        armThread.execute {
            try { runArmJob(job) } catch (t: Throwable) { Diagnostics.log("Arm tracking run failed: ${t.message}") } finally { small.recycle(); armBusy.set(false) }
        }
    }

    private val armRay = Vec3(); private val armOut = Vec3(); private val armFwd = Vec3(); private val armShoulder = Vec3()

    private fun runArmJob(job: ArmJob) {
        if (!arms.run(job.bmp)) return
        val aspect = job.intr.width.toFloat() / job.intr.height.coerceAtLeast(1)
        // shoulders from where the camera (your head) is and faces, level
        job.pose.orientation.rotate(armFwd.set(0f, 0f, -1f), armFwd); armFwd.y = 0f
        if (armFwd.length() < 1e-3f) armFwd.set(0f, 0f, -1f)
        armFwd.normalize()
        for ((side, img, wrist) in job.sides) {
            val r = armResult
            if (!ArmFromMask.forearm(arms.arm, ArmSegEngine.SIZE, img, aspect, r)) continue
            // the shoulder on the side the hand is on (the label can be wrong; the place rarely is)
            val lat = (wrist.x - job.pose.position.x) * -armFwd.z + (wrist.z - job.pose.position.z) * armFwd.x
            val s = if (kotlin.math.abs(lat) > 0.06f) (if (lat > 0f) 1f else -1f) else if (side == Handedness.RIGHT) 1f else -1f
            armShoulder.set(job.pose.position).addScaled(armFwd, -0.04f)
            armShoulder.x += -armFwd.z * 0.18f * s; armShoulder.z += armFwd.x * 0.18f * s; armShoulder.y -= 0.24f
            ArmSolver.rayThrough(r.px, r.py, job.intr, job.pose, armRay)
            val prior = synchronized(lock) { published.getValue(side).let { if (it.armNs != 0L) it.armDir.copy() else null } }
            if (!ArmSolver.elbowDirection(job.pose.position, wrist, armRay, armShoulder, prior, armOut)) continue
            synchronized(lock) {
                for (st in listOf(trackers.getValue(side).state, published.getValue(side))) {
                    st.armDir.set(armOut); st.armConfidence = r.confidence; st.armNs = job.captureNs
                }
            }
        }
    }

    // ------------------------------------------------------------------ AI depth (table typing)
    /**
     * Table height (tracking space) while the table keyboard wants AI depth; NaN = off. The
     * network then runs on the newest analysed frames, one at a time, on its own thread.
     */
    @Volatile var aiDepthTableY = Float.NaN
    val aiDepth by lazy { MonoDepthEngine(context) }
    private val depthThread = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "hx-ai-depth") }
    private val depthBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var depthPreparing = false
    private var lastDepthNs = 0L

    private class DepthJob(val bmp: Bitmap, val intr: CameraIntrinsics, val pose: Pose, val captureNs: Long, val tableY: Float,
                           val sides: List<Pair<Handedness, FloatArray>>)

    private fun maybeRunAiDepth(slot: Slot, intr: CameraIntrinsics) {
        val tableY = aiDepthTableY
        if (tableY.isNaN() || seen.isEmpty()) return
        val pose = slot.pose ?: return
        if (!aiDepth.ready) {
            if (!depthPreparing) { depthPreparing = true; depthThread.execute { aiDepth.prepare(); depthPreparing = false } }
            return
        }
        val now = System.nanoTime()
        if (now - lastDepthNs < 60_000_000L || !depthBusy.compareAndSet(false, true)) return
        lastDepthNs = now
        val src = slot.bitmap ?: run { depthBusy.set(false); return }
        // scaled copy now: the slot's bitmap is reused for the next camera frame
        val small = Bitmap.createScaledBitmap(src, MonoDepthEngine.SIZE, MonoDepthEngine.SIZE, true)
        val job = DepthJob(small, intr, pose.copy(), slot.captureNs, tableY, seen.map { it to sideImage.getValue(it).copyOf() })
        depthThread.execute {
            try { runDepthJob(job) } catch (t: Throwable) { Diagnostics.log("AI depth run failed: ${t.message}") } finally { small.recycle(); depthBusy.set(false) }
        }
    }

    private val calDisp = FloatArray(6000)
    private val calInv = FloatArray(6000)

    /**
     * Fingertip heights from the network's depth: calibrated on the table around each hand
     * (MonoDepthCalibration), read on each fingertip (the nearest of a 3x3 patch, i.e. the finger
     * itself and not the table next to it).
     */
    private fun runDepthJob(job: DepthJob) {
        if (!aiDepth.run(job.bmp)) return
        val n = MonoDepthEngine.SIZE
        val disp = aiDepth.disparity
        val dir = Vec3()
        for ((side, img) in job.sides) {
            var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
            for (j in 0 until HandJoint.COUNT) {
                minX = minOf(minX, img[j * 3]); maxX = maxOf(maxX, img[j * 3]); minY = minOf(minY, img[j * 3 + 1]); maxY = maxOf(maxY, img[j * 3 + 1])
            }
            val w = maxX - minX; val h = maxY - minY
            // table samples: around the hand (2.5x its size), not on any hand (1.25x)
            var count = 0
            var gy = 2
            while (gy < n && count < calDisp.size) {
                var gx = 2
                while (gx < n && count < calDisp.size) {
                    val nx = (gx + 0.5f) / n; val ny = (gy + 0.5f) / n
                    val near = nx > minX - w * 0.75f && nx < maxX + w * 0.75f && ny > minY - h * 0.75f && ny < maxY + h * 0.75f
                    val onHand = job.sides.any { (_, im) -> inBox(im, nx, ny, 0.125f) }
                    if (near && !onHand) {
                        val z = MonoDepthCalibration.tableDepth(nx, ny, job.intr, job.pose, job.tableY, dir)
                        if (!z.isNaN()) { calDisp[count] = disp[gy * n + gx]; calInv[count] = 1f / z; count++ }
                    }
                    gx += 6
                }
                gy += 6
            }
            val fit = MonoDepthCalibration.fit(calDisp, calInv, count)
            val heights = FloatArray(5) { Float.NaN }
            if (fit != null) for (f in 0 until 5) {
                val tip = 4 + f * 4
                val nx = img[tip * 3] * 0.8f + img[(tip - 1) * 3] * 0.2f
                val ny = img[tip * 3 + 1] * 0.8f + img[(tip - 1) * 3 + 1] * 0.2f
                val cx = (nx * n).toInt().coerceIn(1, n - 2); val cy = (ny * n).toInt().coerceIn(1, n - 2)
                var d = Float.NEGATIVE_INFINITY
                for (dy in -1..1) for (dx in -1..1) d = maxOf(d, disp[(cy + dy) * n + cx + dx])
                val inv = fit.a * d + fit.b
                if (inv <= 0.2f) continue
                heights[f] = MonoDepthCalibration.heightAt(nx, ny, 1f / inv, job.intr, job.pose, job.tableY, dir)
            }
            synchronized(lock) {
                for (st in listOf(trackers.getValue(side).state, published.getValue(side))) {
                    heights.copyInto(st.nnTipHeight); st.nnNs = job.captureNs
                }
            }
        }
    }

    private fun inBox(img: FloatArray, nx: Float, ny: Float, grow: Float): Boolean {
        var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
        for (j in 0 until HandJoint.COUNT) {
            minX = minOf(minX, img[j * 3]); maxX = maxOf(maxX, img[j * 3]); minY = minOf(minY, img[j * 3 + 1]); maxY = maxOf(maxY, img[j * 3 + 1])
        }
        val gw = (maxX - minX) * grow; val gh = (maxY - minY) * grow
        return nx > minX - gw && nx < maxX + gw && ny > minY - gh && ny < maxY + gh
    }
    private val tipCam = Vec3()

    /**
     * The fingertips of this camera result, raw (as the model placed them) and measured by the
     * depth map: sampled a little behind the tip along the finger (the tip pixel itself is often
     * the table just past the finger) and lifted into the tracking space with the camera pose.
     * A depth reading far from the model's fingertip hit something else and is not used.
     */
    private fun measureTips(dst: HandState, r: DepthFusion.Result, image: FloatArray, intr: CameraIntrinsics, depth: DepthSampler?, slot: Slot) {
        dst.resultNs = slot.captureNs
        for (f in 0 until 5) {
            val tip = 4 + f * 4
            dst.rawTips[f].set(r.cameraSpace[tip])
            dst.measuredTipValid[f] = false
            if (depth == null) continue
            val u = (image[tip * 3] * 0.75f + image[(tip - 1) * 3] * 0.25f) * intr.width
            val v = (image[tip * 3 + 1] * 0.75f + image[(tip - 1) * 3 + 1] * 0.25f) * intr.height
            val d = depth.depthAt(u, v)
            if (d.isNaN() || d < 0.08f) continue
            tipCam.set((u - intr.cx) * d / intr.fx, -(v - intr.cy) * d / intr.fy, -d)
            val m = dst.measuredTips[f]
            slot.pose?.transformPoint(tipCam, m) ?: m.set(tipCam)
            dst.measuredTipValid[f] = m.distanceTo(r.cameraSpace[tip]) < 0.06f
        }
    }

    private fun processHands(hands: List<RawHand>, slot: Slot, t0: Long) {
        Diagnostics.record(Stage.HAND_INFERENCE, t0 - slot.submitNs)
        val intr = slot.intr ?: return
        val depth = if (slot.depth.valid) slot.depth else null
        lastAnalysed = slot
        var n = 0
        val debug = debugListener
        val debugLms = if (debug != null) ArrayList<FloatArray>(2) else null
        // size and proportions first: a foot or a fold of cloth never reaches the trackers
        val real = hands.filter { h -> h.world == null || verifier.shape(h.world) == null }.take(2)
        val sides = handedness.resolve(real.map { h -> HandednessResolver.Input(if (h.worldEstimated) null else h.world, h.image[0], h.image[1], h.rightScore) })
        for ((idx, h) in real.withIndex()) {
            if (n == 2) break
            val label = sides[idx]
            val obs = observations.getValue(label)[n]
            obs.score = h.labelScore
            h.image.copyInto(obs.image)
            obs.hasWorld = h.world != null
            h.world?.copyInto(obs.world)
            debugLms?.add(obs.image.copyOf())
            if (fusion.fuse(obs, intr, depth, slot.pose, fused[n])) {
                // fused joints are in world space when a camera pose is known: measure from the camera
                val w = fused[n].cameraSpace[HandJoint.WRIST]
                val c = slot.pose?.position
                if (verifier.reach(w.x - (c?.x ?: 0f), w.y - (c?.y ?: 0f), w.z - (c?.z ?: 0f)) != null) continue
                fusedLabel[n] = label; fusedScore[n] = obs.score; fusedPinch[n] = pinchRatioFromWorld(obs); presenceOf[n] = h.presence
                nearEdge[n] = HandHold.nearEdge(obs.image); obs.image.copyInto(fusedImage[n]); n++
            }
        }
        val assign = HandAssignment.assign(n, { k -> fused[k].cameraSpace[HandJoint.WRIST] }, { k -> fusedLabel[k]!! }, trackers)
        seen.clear()
        verifier.beginFrame()
        for (k in 0 until n) {
            val side = assign[k]
            // confirmation over frames: a confident first sighting, then kept through blur
            if (!verifier.gate(if (side == Handedness.LEFT) 0 else 1, presenceOf[k], t0, onlyHand = n == 1)) continue
            // a left/right flip of the same hand: no ghost left coasting on the old side
            if (verifier.swappedFrom >= 0) {
                val gone = if (verifier.swappedFrom == 0) Handedness.LEFT else Handedness.RIGHT
                trackers.getValue(gone).forceLost()
                synchronized(lock) { imageHands.getValue(gone).valid = false }
            }
            val r = fused[k]
            if (autoCalibrate) scaleEstimator.add(r.cameraSpace, r.depthSource, r.confidence)?.let { palm ->
                autoCalibrate = false
                fusion.palmLengthMeters = palm
                Diagnostics.log("Hand size measured with ARCore Depth: palm ${"%.1f".format(palm * 100)} cm")
                onPalmMeasured?.invoke(palm)
            }
            // depth is smoothed along the ray from where the camera was (world space when its
            // pose is known, otherwise camera space with the camera at the origin)
            // a hand lost in the middle of the view is kept (it is behind blur or the other hand);
            // one lost at the edge has left the view and goes quickly
            trackers.getValue(side).lostTimeoutNs = HandHold.timeoutNs(nearEdge[k])
            trackers.getValue(side).update(r.cameraSpace, fusedScore[k], r.depthSource, t0, fusedPinch[k], slot.pose?.position ?: ORIGIN)
            measureTips(trackers.getValue(side).state, r, fusedImage[k], intr, depth, slot)
            fusedImage[k].copyInto(sideImage.getValue(side))
            recordImageHand(side, fusedImage[k], slot)
            seen += side
        }
        verifier.endFrame()
        for ((side, tracker) in trackers) if (side !in seen) tracker.update(null, 0f, DepthSource.NONE, t0)
        synchronized(lock) {
            for ((side, tracker) in trackers) {
                val dst = published.getValue(side)
                copy(tracker.state, dst)
                dst.timestampNs = System.nanoTime()  // when this state became current (extrapolation base)
                motions.getValue(side).set(tracker.motion)
            }
        }
        maybeRunAiDepth(slot, intr)
        maybeRunArms(slot, intr)
        afterFrame(slot)
        Diagnostics.record(Stage.HAND_POSE, System.nanoTime() - t0)
        updateStats(t0, seen.size, t0 - slot.submitNs, t0 - slot.captureNs)
        // the trackers predict across the measured age of the image (exposure -> result), so a
        // published hand is where the real hand is now, not where it was when photographed
        inferEwmaNs = (inferEwmaNs * 7 + (t0 - slot.submitNs)) / 8
        ageEwmaNs = (ageEwmaNs * 7 + (t0 - slot.captureNs)) / 8
        // half the age: more prediction swings the hand past where it stops and back
        val predict = (ageEwmaNs / 1e9f * 0.5f + predictionTrimS).coerceIn(0f, 0.06f)
        reportWork(t0 - slot.submitNs)
        trackers.values.forEach { it.predictionSeconds = predict }
        if (debug != null) slot.bitmap?.let { runCatching { debug(it, debugLms!!) } }
    }

    private var statLatencyNs = 0L

    // ------------------------------------------------------------------ hands in the camera image
    /** Joints where the camera image shows them (sensor orientation, 0..1), for drawing over passthrough. */
    private class ImageHand { val pts = FloatArray(HandJoint.COUNT * 2); var captureNs = 0L; var vx = 0f; var vy = 0f; var valid = false }
    private val imageHands = mapOf(Handedness.LEFT to ImageHand(), Handedness.RIGHT to ImageHand())

    private fun recordImageHand(side: Handedness, image: FloatArray, slot: Slot) {
        synchronized(lock) {
            val ih = imageHands.getValue(side)
            // centre of the palm, for the hand's velocity across the image
            val flip = slot.rotation180
            val m = HandJoint.MIDDLE_MCP * 3
            val rx = (image[0] + image[m]) / 2; val ry = (image[1] + image[m + 1]) / 2
            val nx = if (flip) 1f - rx else rx; val ny = if (flip) 1f - ry else ry
            val dt = (slot.captureNs - ih.captureNs) / 1e9f
            if (ih.valid && dt in 0.005f..0.15f) {
                val ox = (ih.pts[0] + ih.pts[HandJoint.MIDDLE_MCP * 2]) / 2; val oy = (ih.pts[1] + ih.pts[HandJoint.MIDDLE_MCP * 2 + 1]) / 2
                ih.vx = ih.vx * 0.4f + (nx - ox) / dt * 0.6f
                ih.vy = ih.vy * 0.4f + (ny - oy) / dt * 0.6f
            } else { ih.vx = 0f; ih.vy = 0f }
            for (j in 0 until HandJoint.COUNT) {
                ih.pts[j * 2] = if (flip) 1f - image[j * 3] else image[j * 3]
                ih.pts[j * 2 + 1] = if (flip) 1f - image[j * 3 + 1] else image[j * 3 + 1]
            }
            ih.captureNs = slot.captureNs
            ih.valid = true
        }
    }

    /**
     * The [side] hand's joints in the camera image (sensor orientation, 0..1, x then y), carried
     * forward with the hand's image velocity to [displayedCaptureNs] (when the camera frame on
     * screen was taken), so the outline sits on the real hand in the passthrough. False when the
     * hand was not seen in the last quarter second.
     */
    fun imageHand(side: Handedness, out: FloatArray, displayedCaptureNs: Long): Boolean = synchronized(lock) {
        val ih = imageHands.getValue(side)
        if (!ih.valid || System.nanoTime() - ih.captureNs > 250_000_000L) return false
        val dt = ((displayedCaptureNs - ih.captureNs) / 1e9f).coerceIn(0f, 0.08f)
        for (j in 0 until HandJoint.COUNT) { out[j * 2] = ih.pts[j * 2] + ih.vx * dt; out[j * 2 + 1] = ih.pts[j * 2 + 1] + ih.vy * dt }
        true
    }

    private fun updateStats(now: Long, hands: Int, inferNs: Long, latencyNs: Long) {
        if (statStartNs == 0L) statStartNs = now
        statFrames++
        if (hands > 0) statWithHands++
        statInferNs += inferNs
        statLatencyNs += latencyNs
        val span = now - statStartNs
        if (span >= 1_000_000_000L) {
            val fps = statFrames * 1e9f / span
            val f = statFrames.coerceAtLeast(1)
            val engine = npu?.let { "detector %.1f ms · landmarks %.1f ms".format(it.detectorMs, it.landmarkMs) } ?: "inferência %.0f ms".format(statInferNs / 1e6f / f)
            stats = "%.0f quadros/s · mão em %d%% · %s · câmera→pose %.0f ms · %s · %dx%d".format(
                fps, statWithHands * 100 / f, engine, statLatencyNs / 1e6f / f,
                backendInUse, lastAnalysed?.bitmap?.width ?: 0, lastAnalysed?.bitmap?.height ?: 0,
            ) + if (verifier.rejections > 0) " · rejeitado: ${verifier.lastRejection}" else ""
            statFrames = 0; statWithHands = 0; statInferNs = 0; statLatencyNs = 0; statStartNs = now
        }
    }

    /** Per side: how the published hand keeps moving until the next result (see HandMotion). */
    private val motions = mapOf(Handedness.LEFT to HandMotion(), Handedness.RIGHT to HandMotion())

    /**
     * Thread-safe copy of the latest hand states, moved forward to now: the camera gives a result
     * every 16-33 ms but the display draws every 8-16 ms. Between results a moving hand keeps
     * moving as a whole with its smoothed velocity (a still hand stays exactly still, so noise is
     * never turned into shaking), and each new result blends in over ~20 ms instead of jumping.
     */
    fun snapshot(left: HandState, right: HandState, aheadS: Float = DISPLAY_AHEAD_S) {
        val now = System.nanoTime()
        synchronized(lock) {
            for ((side, out) in listOf(Handedness.LEFT to left, Handedness.RIGHT to right)) {
                val src = published.getValue(side)
                copy(src, out)
                if (src.state != TrackingState.TRACKING || src.timestampNs == 0L) continue
                // forward to now, plus the time until the frame drawn now reaches the eyes
                val since = ((now - src.timestampNs) / 1e9f).coerceIn(0f, 0.05f)
                motions.getValue(side).extrapolate(src, since, out.joints, aheadS.coerceIn(0f, 0.02f))
            }
        }
    }

    private fun copy(src: HandState, dst: HandState) {
        for (i in 0 until HandJoint.COUNT) dst.joints[i].set(src.joints[i])
        dst.state = src.state; dst.confidence = src.confidence; dst.depthSource = src.depthSource
        dst.pinchStrength = src.pinchStrength; dst.pinchRatio = src.pinchRatio; dst.gesture = src.gesture; dst.timestampNs = src.timestampNs
        for (f in 0 until 5) { dst.rawTips[f].set(src.rawTips[f]); dst.measuredTips[f].set(src.measuredTips[f]); dst.measuredTipValid[f] = src.measuredTipValid[f] }
        dst.resultNs = src.resultNs
        src.nnTipHeight.copyInto(dst.nnTipHeight); dst.nnNs = src.nnNs
        dst.armDir.set(src.armDir); dst.armConfidence = src.armConfidence; dst.armNs = src.armNs
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
        // RTMDet + RTMPose (same synthetic frame: no hand, so the detector runs every frame)
        for (b in listOf(InferenceBackend.NNAPI, InferenceBackend.GPU, InferenceBackend.CPU)) {
            val e = RtmHandEngine(context)
            try {
                if (e.start(b)) {
                    val stats = LatencyStats("RTMPose (${e.device}, detector)", iterations)
                    repeat(5) { e.process(bmp) }
                    repeat(iterations) {
                        val t0 = System.nanoTime()
                        e.process(bmp)
                        stats.record(System.nanoTime() - t0)
                    }
                    out += stats.snapshot().format()
                } else out += "RTMPose ${b.name}: indisponível"
            } catch (t: Throwable) { out += "RTMPose ${b.name}: indisponível (${t.message})" } finally { e.close() }
        }
        // MediaPipe models on the NPU (palm detector every frame)
        try {
            val e = NpuHandEngine(context)
            if (e.start()) {
                try {
                    val stats = LatencyStats("MediaPipe NPU (${e.device}, detector de palma)", iterations)
                    repeat(5) { e.process(bmp) }
                    repeat(iterations) {
                        val t0 = System.nanoTime()
                        e.process(bmp)
                        stats.record(System.nanoTime() - t0)
                    }
                    out += stats.snapshot().format()
                } finally { e.close() }
            } else out += "HyperMagic NPU: indisponível"
        } catch (t: Throwable) { out += "HyperMagic NPU: indisponível (${t.message})" }
        return out
    }

    fun shutdown() { stop(); thread.quitSafely() }

    companion object {
        const val MODEL_ASSET = "models/hand_landmarker.task"
        const val DISPLAY_AHEAD_S = 0.012f
        private val ORIGIN = com.hyperxr.math.Vec3()
        /** A frame without a camera pose: camera space is the world. */
        private val NO_POSE = Pose()
        /** Full 640x480 frames: hands at arm's length are small, and downscaling cost landmark quality. */
        const val MAX_WIDTH = 640
    }
}

/** Image timestamp, 0 when the image is already closed or has none. */
private val Image.timestampSafe: Long get() = try { timestamp } catch (_: Throwable) { 0L }

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

/** Native YUV -> RGBA conversion (app/src/main/cpp/yuv_convert.cpp). */
object YuvNative {
    val available: Boolean = try { System.loadLibrary("hyperxr_image"); true } catch (t: Throwable) { false }

    @JvmStatic external fun convert(y: java.nio.ByteBuffer, u: java.nio.ByteBuffer, v: java.nio.ByteBuffer, yRow: Int, yPix: Int,
                                    uvRow: Int, uvPix: Int, step: Int, rotate180: Boolean, bitmap: Bitmap): Boolean

    /** Affine crop + bilinear + RGB/255 floats into a direct buffer (size x size x 3). */
    @JvmStatic external fun warpToTensor(bitmap: Bitmap, m: FloatArray, size: Int, out: java.nio.ByteBuffer): Boolean

    /** Affine crop + bilinear + (pixel - mean) * invStd into a direct buffer: interleaved RGB, or BGR planes. */
    @JvmStatic external fun warpToTensorNorm(bitmap: Bitmap, m: FloatArray, outW: Int, outH: Int, mean: FloatArray, invStd: FloatArray, pad: Float,
                                             planarBgr: Boolean, out: java.nio.ByteBuffer): Boolean
}

/**
 * YUV_420_888 -> ARGB bitmap at <= [HandTrackingService.MAX_WIDTH] px wide, written into buffers
 * that are reused frame after frame (no per-frame allocation). Integer BT.601 conversion, in
 * native code straight into the bitmap when available, otherwise in Kotlin on two threads.
 */
class YuvToBitmap {
    private var pixels = IntArray(0)
    private var bitmap: Bitmap? = null
    private val done = java.util.concurrent.Semaphore(0)

    /** Converts on the calling thread and [helper] at once (top and bottom halves of the image). */
    fun convert(img: Image, rotate180: Boolean, helper: java.util.concurrent.Executor? = null): Bitmap {
        val step = maxOf(1, (img.width + HandTrackingService.MAX_WIDTH - 1) / HandTrackingService.MAX_WIDTH)
        val ow = img.width / step
        val oh = img.height / step
        if (pixels.size != ow * oh) pixels = IntArray(ow * oh)
        val b = bitmap?.takeIf { it.width == ow && it.height == oh } ?: Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888).also { bitmap = it }
        val p = img.planes
        val yb = p[0].buffer; val ub = p[1].buffer; val vb = p[2].buffer
        val yRow = p[0].rowStride; val yPix = p[0].pixelStride
        val uvRow = p[1].rowStride; val uvPix = p[1].pixelStride
        if (YuvNative.available && yb.isDirect && ub.isDirect && vb.isDirect &&
            YuvNative.convert(yb, ub, vb, yRow, yPix, uvRow, uvPix, step, rotate180, b)) return b
        val half = oh / 2
        if (helper != null) {
            // ByteBuffer absolute gets are thread-safe for reading with duplicated buffers
            val y2 = yb.duplicate(); val u2 = ub.duplicate(); val v2 = vb.duplicate()
            helper.execute {
                try { rows(half, oh, step, ow, oh, rotate180, y2, u2, v2, yRow, yPix, uvRow, uvPix) } finally { done.release() }
            }
            rows(0, half, step, ow, oh, rotate180, yb, ub, vb, yRow, yPix, uvRow, uvPix)
            done.acquireUninterruptibly()
        } else {
            rows(0, oh, step, ow, oh, rotate180, yb, ub, vb, yRow, yPix, uvRow, uvPix)
        }
        b.setPixels(pixels, 0, ow, 0, 0, ow, oh)
        return b
    }

    private fun rows(from: Int, to: Int, step: Int, ow: Int, oh: Int, rotate180: Boolean,
                     yb: java.nio.ByteBuffer, ub: java.nio.ByteBuffer, vb: java.nio.ByteBuffer, yRow: Int, yPix: Int, uvRow: Int, uvPix: Int) {
        val px = pixels
        for (oy in from until to) {
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
                px[dstRow + (if (rotate180) ow - 1 - ox else ox)] = (0xff shl 24) or (r shl 16) or (g shl 8) or bl
            }
        }
    }
}
