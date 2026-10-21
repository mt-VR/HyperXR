package com.hyperxr.handtracking

import android.content.Context
import android.media.Image
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.diagnostics.LatencyStats
import com.hyperxr.diagnostics.Stage
import com.hyperxr.math.Pose
import com.hyperxr.settings.HandTrackingSettings
import com.hyperxr.settings.InferenceBackend
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Model files used by the hand pipeline. HyperXR does not bundle hand models; the user imports
 * models whose licence they accept. Expected contracts (documented in docs/HAND_TRACKING.md):
 *  - detector: input [1,H,W,3] (float32 0..1 or uint8), outputs of TFLite_Detection_PostProcess:
 *    boxes [1,N,4] (ymin,xmin,ymax,xmax normalised), classes [1,N], scores [1,N], count [1].
 *  - landmarks: input [1,S,S,3]; output 0 = [1,63] (x,y in input pixels, z relative), then
 *    presence [1,1] and handedness [1,1] (> 0.5 = right).
 */
class HandModels(context: Context) {
    val dir = File(context.filesDir, "models").apply { mkdirs() }
    val detector = File(dir, "hand_detector.tflite")
    val landmarks = File(dir, "hand_landmark.tflite")
    val available: Boolean get() = detector.isFile && landmarks.isFile

    fun import(context: Context, uri: Uri, target: File) {
        context.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
    }
}

/** One TFLite interpreter on a chosen backend, reporting what actually got used. */
class InferenceModel(file: File, requested: InferenceBackend, threads: Int = 2) : AutoCloseable {
    val interpreter: Interpreter
    val backend: String
    private var gpu: GpuDelegate? = null
    private var nnapi: NnApiDelegate? = null

    init {
        val buffer = file.readBytes().let { ByteBuffer.allocateDirect(it.size).order(ByteOrder.nativeOrder()).put(it).also { b -> b.rewind() } }
        val order = when (requested) {
            InferenceBackend.NNAPI -> listOf(InferenceBackend.NNAPI, InferenceBackend.GPU, InferenceBackend.CPU)
            InferenceBackend.GPU -> listOf(InferenceBackend.GPU, InferenceBackend.CPU)
            InferenceBackend.CPU -> listOf(InferenceBackend.CPU)
            InferenceBackend.AUTO -> listOf(InferenceBackend.GPU, InferenceBackend.CPU)
        }
        var created: Interpreter? = null
        var used = "CPU"
        val failures = mutableListOf<String>()
        for (b in order) {
            try {
                val opts = Interpreter.Options().setNumThreads(threads)
                when (b) {
                    InferenceBackend.GPU -> { gpu = GpuDelegate(); opts.addDelegate(gpu) }
                    InferenceBackend.NNAPI -> {
                        nnapi = NnApiDelegate(NnApiDelegate.Options().setAllowFp16(true).setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED))
                        opts.addDelegate(nnapi)
                    }
                    else -> opts.setUseXNNPACK(true)
                }
                created = Interpreter(buffer, opts)
                used = if (b == InferenceBackend.CPU) "CPU (XNNPACK, $threads threads)" else b.name
                break
            } catch (t: Throwable) {
                failures += "${b.name}: ${t.message}"
                gpu?.close(); gpu = null
                nnapi?.close(); nnapi = null
            }
        }
        interpreter = created ?: throw IllegalStateException("No backend could run the model: $failures")
        backend = if (failures.isEmpty()) used else "$used (fallback; ${failures.joinToString()})"
    }

    override fun close() { interpreter.close(); gpu?.close(); nnapi?.close() }
}

/**
 * Camera frames -> landmarks -> 3D fusion -> temporal tracking. Runs on its own thread, always on
 * the newest frame (older frames are dropped), at most `handInferenceHz` times per second; while a
 * hand stays tracked only the landmark model runs on a ROI predicted from the last landmarks.
 */
class HandTrackingService(private val context: Context) {
    val models = HandModels(context)
    private val thread = HandlerThread("hx-hands").apply { start() }
    private val handler = Handler(thread.looper)
    private val busy = AtomicBoolean(false)

    private var detector: InferenceModel? = null
    private var landmark: InferenceModel? = null
    @Volatile var status: String = "stopped"; private set
    @Volatile var backendInUse: String = "-"; private set

    val trackers = mapOf(Handedness.LEFT to HandTracker(Handedness.LEFT), Handedness.RIGHT to HandTracker(Handedness.RIGHT))
    private val fusion = DepthFusion()
    private val fusionResult = DepthFusion.Result()
    private val rois = HashMap<Handedness, FloatArray>() // normalized cx, cy, size
    private var lastRunNs = 0L
    private var frameCounter = 0
    private var settings = HandTrackingSettings()

    /** Snapshot for the render thread (copied under lock). */
    private val lock = Any()
    private val published = mapOf(Handedness.LEFT to HandState(Handedness.LEFT), Handedness.RIGHT to HandState(Handedness.RIGHT))

    fun configure(s: HandTrackingSettings, inferenceHz: Int) {
        settings = s
        minIntervalNs = 1_000_000_000L / inferenceHz.coerceIn(5, 60)
        trackers.values.forEach {
            it.setFilterParams(s.filterMinCutoff, s.filterBeta)
            it.pinchThreshold = s.pinchThreshold
            it.predictionSeconds = s.predictionMs / 1000f
        }
    }

    private var minIntervalNs = 33_000_000L

    fun start(backend: InferenceBackend): Boolean {
        if (!models.available) { status = "No hand models installed (Settings > Hand tracking > Import models)"; return false }
        handler.post {
            try {
                detector?.close(); landmark?.close()
                detector = InferenceModel(models.detector, backend)
                landmark = InferenceModel(models.landmarks, backend)
                backendInUse = "detector: ${detector!!.backend}; landmarks: ${landmark!!.backend}"
                status = "running"
            } catch (t: Throwable) {
                status = "Model load failed: ${t.message}"
                Diagnostics.log(status)
            }
        }
        return true
    }

    fun stop() {
        handler.post { detector?.close(); landmark?.close(); detector = null; landmark = null; status = "stopped" }
    }

    /**
     * Submit a camera frame. [cameraPose] is the camera pose in tracking space at capture time
     * (null when only 3DoF is known: joints are then relative to the head-locked camera).
     */
    fun submit(image: Image, intr: CameraIntrinsics, cameraPose: Pose?, depth: DepthSampler?, releaseDepth: (() -> Unit)?) {
        val now = System.nanoTime()
        if (detector == null || landmark == null || now - lastRunNs < minIntervalNs || !busy.compareAndSet(false, true)) {
            image.close(); releaseDepth?.invoke(); return
        }
        lastRunNs = now
        // Convert on the caller thread while the Image is valid, then infer asynchronously.
        val frame = YuvFrame.from(image)
        image.close()
        val pose = cameraPose?.copy()
        handler.post {
            try { process(frame, intr, pose, depth, now) } catch (t: Throwable) { status = "Inference error: ${t.message}" }
            finally { releaseDepth?.invoke(); busy.set(false) }
        }
    }

    private fun process(frame: YuvFrame, intr: CameraIntrinsics, cameraPose: Pose?, depth: DepthSampler?, ts: Long) {
        frameCounter++
        val det = detector ?: return
        val lm = landmark ?: return
        val t0 = System.nanoTime()
        // Detector only when a hand is missing, and at most every 3rd frame.
        if (rois.size < 2 && frameCounter % 3 == 0) {
            for (box in runDetector(det, frame)) {
                val overlaps = rois.values.any { kotlin.math.abs(it[0] - box[0]) < box[2] * 0.5f && kotlin.math.abs(it[1] - box[1]) < box[2] * 0.5f }
                if (!overlaps) rois[if (rois.containsKey(Handedness.RIGHT)) Handedness.LEFT else Handedness.RIGHT] = box
            }
        }
        val seen = HashSet<Handedness>()
        for ((hand, roi) in rois.entries.toList()) {
            val obs = runLandmarks(lm, frame, roi) ?: run { rois.remove(hand); null } ?: continue
            Diagnostics.record(Stage.HAND_INFERENCE, System.nanoTime() - t0)
            val tp = System.nanoTime()
            val side = obs.handedness
            obs.timestampNs = ts
            if (fusion.fuse(obs, intr, depth, cameraPose, fusionResult)) {
                trackers.getValue(side).update(fusionResult.cameraSpace, obs.score, fusionResult.depthSource, ts)
                seen += side
            }
            // next ROI from the landmarks bounding box
            rois.remove(hand)
            rois[side] = roiFromLandmarks(obs.image)
            Diagnostics.record(Stage.HAND_POSE, System.nanoTime() - tp)
        }
        for ((side, tracker) in trackers) if (side !in seen) tracker.update(null, 0f, DepthSource.NONE, ts)
        synchronized(lock) {
            for ((side, tracker) in trackers) copy(tracker.state, published.getValue(side))
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
        dst.pinchStrength = src.pinchStrength; dst.gesture = src.gesture; dst.timestampNs = src.timestampNs
    }

    private fun roiFromLandmarks(img: FloatArray): FloatArray {
        var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
        for (i in 0 until HandJoint.COUNT) {
            minX = minOf(minX, img[i * 3]); maxX = maxOf(maxX, img[i * 3])
            minY = minOf(minY, img[i * 3 + 1]); maxY = maxOf(maxY, img[i * 3 + 1])
        }
        val size = maxOf(maxX - minX, maxY - minY) * 1.7f
        return floatArrayOf((minX + maxX) / 2, (minY + maxY) / 2, size.coerceIn(0.05f, 1.2f))
    }

    // ------------------------------------------------------------------ models
    private fun inputBuffer(model: InferenceModel, frame: YuvFrame, cx: Float, cy: Float, size: Float, aspectCorrect: Boolean): Pair<ByteBuffer, Int> {
        val t = model.interpreter.getInputTensor(0)
        val shape = t.shape() // [1,H,W,3]
        val h = shape[1]; val w = shape[2]
        val isFloat = t.dataType() == org.tensorflow.lite.DataType.FLOAT32
        val buf = ByteBuffer.allocateDirect(w * h * 3 * if (isFloat) 4 else 1).order(ByteOrder.nativeOrder())
        frame.sampleRgb(buf, w, h, cx, cy, size, if (aspectCorrect) frame.width.toFloat() / frame.height else 1f, isFloat)
        buf.rewind()
        return buf to w
    }

    private fun runDetector(model: InferenceModel, frame: YuvFrame): List<FloatArray> {
        val (input, _) = inputBuffer(model, frame, 0.5f, 0.5f, 1f, aspectCorrect = false)
        val outCount = model.interpreter.outputTensorCount
        if (outCount < 4) { status = "Detector output format not supported (expected detection post-process)"; return emptyList() }
        val n = model.interpreter.getOutputTensor(0).shape()[1]
        val boxes = Array(1) { Array(n) { FloatArray(4) } }
        val classes = Array(1) { FloatArray(n) }
        val scores = Array(1) { FloatArray(n) }
        val count = FloatArray(1)
        model.interpreter.runForMultipleInputsOutputs(arrayOf(input), mapOf(0 to boxes, 1 to classes, 2 to scores, 3 to count))
        val out = mutableListOf<FloatArray>()
        for (i in 0 until minOf(n, count[0].toInt())) {
            if (scores[0][i] < 0.5f) continue
            val b = boxes[0][i]
            val cx = (b[1] + b[3]) / 2; val cy = (b[0] + b[2]) / 2
            out += floatArrayOf(cx, cy, maxOf(b[3] - b[1], b[2] - b[0]) * 2.0f)
            if (out.size == 2) break
        }
        return out
    }

    private fun runLandmarks(model: InferenceModel, frame: YuvFrame, roi: FloatArray): LandmarkObservation? {
        val (input, size) = inputBuffer(model, frame, roi[0], roi[1], roi[2], aspectCorrect = true)
        val lmOut = Array(1) { FloatArray(63) }
        val presence = Array(1) { FloatArray(1) }
        val handed = Array(1) { FloatArray(1) }
        val outputs = HashMap<Int, Any>()
        outputs[0] = lmOut
        if (model.interpreter.outputTensorCount > 1) outputs[1] = presence
        if (model.interpreter.outputTensorCount > 2) outputs[2] = handed
        model.interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)
        val score = if (outputs.containsKey(1)) presence[0][0] else 1f
        if (score < 0.5f) return null
        val obs = LandmarkObservation(if (handed[0][0] > 0.5f) Handedness.RIGHT else Handedness.LEFT)
        obs.score = score
        // crop pixels -> full-image normalized coordinates
        val aspect = frame.width.toFloat() / frame.height
        val roiW = roi[2] / aspect
        val roiH = roi[2]
        for (i in 0 until HandJoint.COUNT) {
            obs.image[i * 3] = roi[0] + (lmOut[0][i * 3] / size - 0.5f) * roiW
            obs.image[i * 3 + 1] = roi[1] + (lmOut[0][i * 3 + 1] / size - 0.5f) * roiH
            obs.image[i * 3 + 2] = lmOut[0][i * 3 + 2] / size * roiW
        }
        return obs
    }

    /** Runs each available backend [iterations] times on a synthetic frame and reports the timing. */
    fun benchmark(iterations: Int = 50): List<String> {
        if (!models.available) return listOf("No hand models installed: nothing to benchmark")
        val results = mutableListOf<String>()
        val frame = YuvFrame.synthetic(640, 480)
        for (b in listOf(InferenceBackend.CPU, InferenceBackend.GPU, InferenceBackend.NNAPI)) {
            try {
                InferenceModel(models.landmarks, b).use { m ->
                    val stats = LatencyStats("landmarks ${m.backend}", iterations)
                    repeat(5) { runLandmarks(m, frame, floatArrayOf(0.5f, 0.5f, 0.6f)) } // warm-up
                    repeat(iterations) {
                        val t0 = System.nanoTime()
                        runLandmarks(m, frame, floatArrayOf(0.5f, 0.5f, 0.6f))
                        stats.record(System.nanoTime() - t0)
                    }
                    results += stats.snapshot().format()
                }
            } catch (t: Throwable) {
                results += "${b.name}: unavailable (${t.message})"
            }
        }
        return results
    }

    fun shutdown() { stop(); thread.quitSafely() }
}

/** Compact copy of a YUV_420_888 image with bilinear-free nearest sampling into model inputs. */
class YuvFrame private constructor(val width: Int, val height: Int, private val y: ByteArray, private val u: ByteArray, private val v: ByteArray,
                                   private val yRow: Int, private val uvRow: Int, private val uvPix: Int) {
    /** Samples a square ROI (normalized centre/size, size relative to height) into an RGB tensor. */
    fun sampleRgb(out: ByteBuffer, w: Int, h: Int, cx: Float, cy: Float, size: Float, aspect: Float, asFloat: Boolean) {
        val roiW = size / aspect * width
        val roiH = size * height
        val x0 = cx * width - roiW / 2
        val y0 = cy * height - roiH / 2
        for (j in 0 until h) {
            val sy = (y0 + (j + 0.5f) / h * roiH).toInt()
            for (i in 0 until w) {
                val sx = (x0 + (i + 0.5f) / w * roiW).toInt()
                var r = 0; var g = 0; var b = 0
                if (sx in 0 until width && sy in 0 until height) {
                    val yy = (y[sy * yRow + sx].toInt() and 0xff) - 16
                    val ui = (sy / 2) * uvRow + (sx / 2) * uvPix
                    val uu = (u[ui].toInt() and 0xff) - 128
                    val vv = (v[ui].toInt() and 0xff) - 128
                    val c = 1192 * maxOf(yy, 0)
                    r = ((c + 1634 * vv) shr 10).coerceIn(0, 255)
                    g = ((c - 833 * vv - 400 * uu) shr 10).coerceIn(0, 255)
                    b = ((c + 2066 * uu) shr 10).coerceIn(0, 255)
                }
                if (asFloat) { out.putFloat(r / 255f); out.putFloat(g / 255f); out.putFloat(b / 255f) }
                else { out.put(r.toByte()); out.put(g.toByte()); out.put(b.toByte()) }
            }
        }
    }

    companion object {
        fun from(img: Image): YuvFrame {
            val p = img.planes
            fun bytes(b: ByteBuffer): ByteArray { val a = ByteArray(b.remaining()); b.get(a); return a }
            return YuvFrame(img.width, img.height, bytes(p[0].buffer), bytes(p[1].buffer), bytes(p[2].buffer), p[0].rowStride, p[1].rowStride, p[1].pixelStride)
        }

        fun synthetic(w: Int, h: Int): YuvFrame =
            YuvFrame(w, h, ByteArray(w * h) { 128.toByte() }, ByteArray(w * h / 2) { 128.toByte() }, ByteArray(w * h / 2) { 128.toByte() }, w, w / 2, 1)
    }
}
