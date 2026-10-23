package com.hyperxr.handtracking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.diagnostics.NnapiNative
import com.hyperxr.handtracking.npu.HandRoi
import com.hyperxr.handtracking.npu.PalmDecoder
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/** One hand found by an engine, in the form the rest of the pipeline uses. */
class RawHand(
    /** Model label as MediaPipe names it ("Left"/"Right" for a mirrored image). */
    val modelLabel: String,
    val labelScore: Float,
    /** Hand-presence score of the landmark model; NaN when the engine does not report it. */
    val presence: Float,
    /** Normalized image landmarks (x, y in 0..1, z relative) per joint. */
    val image: FloatArray,
    /** Metric landmarks (metres, hand-centred) per joint, or null. */
    val world: FloatArray?,
    /** Model probability of a right hand in the (non-mirrored) image, NaN when not exposed. */
    val rightScore: Float = Float.NaN,
)

/**
 * HyperXR's own hand tracker for the phone's NPU. It runs the open MediaPipe hand models (palm
 * detector and landmark model, Apache-2.0, already bundled) with LiteRT and hands the whole graph
 * to the NPU through NNAPI, but the pipeline around them is HyperXR's:
 *  - the dedicated accelerator is picked by name from what NNAPI reports (Samsung Eden, MediaTek
 *    APU/MDLA, Qualcomm HTA/HTP/DSP, Google EdgeTPU...), with fp16, sustained-speed scheduling and
 *    the compiled model cached on disk, so the second start skips compilation;
 *  - palm detection runs only when a hand is missing (every frame with none, every 3rd frame while
 *    one is tracked): a tracked hand costs one landmark inference per frame, cropped from where
 *    the previous frame put it;
 *  - each crop is a rotated square aligned to the hand, so the model always sees an upright hand;
 *  - hands with a low presence score are dropped at once instead of being tracked into the
 *    background; HandVerifier then judges size, shape and reach.
 * Falls back to NNAPI's own device choice, then to the CPU (XNNPACK), and says which one runs.
 */
class NpuHandEngine(private val context: Context) {
    @Volatile var device = "-"; private set
    @Volatile var detectorMs = 0f; private set
    @Volatile var landmarkMs = 0f; private set

    private var detector: Interpreter? = null
    private var landmarks: Interpreter? = null
    private val delegates = ArrayList<AutoCloseable>()

    private val palmIn = ByteBuffer.allocateDirect(192 * 192 * 3 * 4).order(ByteOrder.nativeOrder())
    private val lmIn = ByteBuffer.allocateDirect(224 * 224 * 3 * 4).order(ByteOrder.nativeOrder())
    private val regressors = Array(1) { Array(PalmDecoder.ANCHORS) { FloatArray(18) } }
    private val logits = Array(1) { Array(PalmDecoder.ANCHORS) { FloatArray(1) } }
    private val regFlat = FloatArray(PalmDecoder.ANCHORS * 18)
    private val logFlat = FloatArray(PalmDecoder.ANCHORS)
    private val outLm = Array(1) { FloatArray(63) }
    private val outPresence = Array(1) { FloatArray(1) }
    private val outHanded = Array(1) { FloatArray(1) }
    private val outWorld = Array(1) { FloatArray(63) }
    private val palmBmp = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
    private val cropBmp = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(224 * 224)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()

    /** A hand followed from frame to frame: where it was and how fast its crop was moving (px/frame). */
    private class Track(val roi: HandRoi, val vx: Float, val vy: Float) {
        /** Where the crop should be this frame: fast hands stay inside it instead of being lost. */
        fun predicted() = roi.copy(cx = roi.cx + vx, cy = roi.cy + vy)
    }
    private val tracked = ArrayList<Track>(2)
    @Volatile var recovered = 0; private set
    private var frame = 0L

    /** Loads both models and builds the interpreters (worker thread; ~1 s the first time). */
    fun start(): Boolean {
        close()
        val models = HashMap<String, ByteBuffer>()
        context.assets.open(HandTrackingService.MODEL_ASSET).use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.name.endsWith(".tflite")) {
                        val bytes = zip.readBytes()
                        models[e.name] = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.rewind() }
                    }
                }
            }
        }
        val det = models["hand_detector.tflite"] ?: return false
        val lm = models["hand_landmarks_detector.tflite"] ?: return false
        val accel = pickAccelerator()
        val tries = buildList {
            if (accel != null) add("NPU: $accel" to accel)
            add("NNAPI (dispositivo escolhido pelo Android)" to "")
            add("CPU (XNNPACK)" to null)
        }
        for ((label, name) in tries) {
            try {
                val d = build(det, name, "palm")
                val l = build(lm, name, "landmark")
                // a delegate can accept the graph and still fail on the first run: run once now
                warmUp(d, l)
                // and an accelerator can run it with too little precision: compare with the CPU
                if (name != null) agreesWithCpu(l, lm)?.let { problem -> throw IllegalStateException(problem) }
                detector = d; landmarks = l; device = label
                Diagnostics.log("NPU hand tracker on $label")
                return true
            } catch (t: Throwable) {
                Diagnostics.log("NPU hand tracker: $label failed (${t.message})")
                close()
            }
        }
        return false
    }

    /** The NNAPI device that is a real accelerator, preferring known NPU names over DSP/GPU-like ones. */
    private fun pickAccelerator(): String? {
        val rows = runCatching { NnapiNative.devices() }.getOrDefault(emptyList()).map { it.split('|') }.filter { it.size >= 2 }
        val accels = rows.filter { it[1] == "accelerator" && !it[0].contains("reference", true) }.map { it[0] }
        val npuWords = listOf("npu", "eden", "neuron", "mdla", "apu", "hta", "htp", "edgetpu", "tpu", "nnhal", "dsp")
        return accels.sortedBy { n -> npuWords.indexOfFirst { n.contains(it, true) }.let { if (it < 0) 99 else it } }.firstOrNull()
    }

    /** [accelerator]: device name, "" = NNAPI decides, null = CPU. */
    private fun build(model: ByteBuffer, accelerator: String?, token: String): Interpreter {
        val opts = Interpreter.Options()
        if (accelerator != null) {
            val nn = NnApiDelegate.Options()
                .setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED)
                .setAllowFp16(true)
                .setUseNnapiCpu(false)
                .setCacheDir(context.cacheDir.absolutePath)
                .setModelToken("hxhands-$token-v1")
            if (accelerator.isNotEmpty()) nn.setAcceleratorName(accelerator)
            val d = NnApiDelegate(nn)
            delegates += d
            opts.addDelegate(d)
            opts.setNumThreads(2)  // only for ops the NPU driver refuses
        } else {
            opts.setNumThreads(4)
            opts.setUseXNNPACK(true)
        }
        model.rewind()
        return Interpreter(model, opts)
    }

    private fun warmUp(d: Interpreter, l: Interpreter) {
        palmIn.rewind(); d.runForMultipleInputsOutputs(arrayOf(palmIn), mapOf(0 to regressors, 1 to logits))
        lmIn.rewind(); l.runForMultipleInputsOutputs(arrayOf(lmIn), mapOf(0 to outLm, 1 to outPresence, 2 to outHanded, 3 to outWorld))
    }

    /**
     * Runs the landmark model on the accelerator and on the CPU with the same synthetic picture
     * (skin-toned blob with finger-like bars) and compares. Null when they agree.
     */
    private fun agreesWithCpu(accel: Interpreter, model: ByteBuffer): String? {
        val cpu = build(model, null, "check")
        try {
            val c = Canvas(cropBmp)
            c.drawColor(Color.rgb(60, 70, 90))
            val skin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(205, 160, 130) }
            c.drawOval(62f, 110f, 162f, 210f, skin)
            for (i in 0 until 4) c.drawRoundRect(70f + i * 24f, 30f + (i % 2) * 10f, 88f + i * 24f, 130f, 9f, 9f, skin)
            c.drawRoundRect(150f, 120f, 200f, 140f, 9f, 9f, skin)
            toFloats(cropBmp, 224, lmIn)
            val a = Array(1) { FloatArray(63) }; val ap = Array(1) { FloatArray(1) }
            lmIn.rewind(); accel.runForMultipleInputsOutputs(arrayOf(lmIn), mapOf(0 to a, 1 to ap, 2 to outHanded, 3 to outWorld))
            val b = Array(1) { FloatArray(63) }; val bp = Array(1) { FloatArray(1) }
            lmIn.rewind(); cpu.runForMultipleInputsOutputs(arrayOf(lmIn), mapOf(0 to b, 1 to bp, 2 to outHanded, 3 to outWorld))
            var diff = 0f
            for (i in 0 until 63) if (i % 3 != 2) diff += kotlin.math.abs(a[0][i] - b[0][i])
            diff /= 42f
            Diagnostics.log("NPU check: landmarks differ %.2f px, presence %.3f vs %.3f".format(diff, ap[0][0], bp[0][0]))
            if (diff.isNaN() || diff > 4f) return "resultado da NPU difere da CPU (%.1f px)".format(diff)
            if (kotlin.math.abs(ap[0][0] - bp[0][0]) > 0.15f) return "presença na NPU difere da CPU"
            return null
        } finally { cpu.close() }
    }

    fun close() {
        detector?.close(); landmarks?.close(); detector = null; landmarks = null
        delegates.forEach { runCatching { it.close() } }; delegates.clear()
        tracked.clear()
    }

    /** Finds up to two hands in [bmp] (the inference thread). */
    fun process(bmp: Bitmap): List<RawHand> {
        val det = detector ?: return emptyList()
        val lm = landmarks ?: return emptyList()
        frame++
        // crops for this frame: each tracked hand where its motion says it is now
        val rois = ArrayList<HandRoi>(2)
        val prev = ArrayList<Track?>(2)
        for (t in tracked) { rois += t.predicted(); prev += t }
        // a missing hand is looked for on every frame while the palm detector is quick (a few ms
        // on the NPU), so a hand entering the view shows up on the next camera frame; on a slow
        // detector every other frame
        if (rois.size < 2 && (rois.isEmpty() || detectorMs < 8f || frame % 2 == 0L)) {
            val t0 = System.nanoTime()
            fillPalmInput(bmp)
            det.runForMultipleInputsOutputs(arrayOf(palmIn), mapOf(0 to regressors, 1 to logits))
            detectorMs = (System.nanoTime() - t0) / 1e6f
            for (i in 0 until PalmDecoder.ANCHORS) { System.arraycopy(regressors[0][i], 0, regFlat, i * 18, 18); logFlat[i] = logits[0][i][0] }
            for (p in PalmDecoder.decode(regFlat, logFlat, bmp.width, bmp.height, 0.42f)) {
                if (rois.size >= 2) break
                val r = HandRoi.fromPalm(p)
                // a palm inside a hand already tracked is that hand
                if (rois.none { o -> dist(o.cx, o.cy, r.cx, r.cy) < o.size * 0.35f }) { rois += r; prev += null }
            }
        }
        tracked.clear()
        val out = ArrayList<RawHand>(2)
        val pts = FloatArray(63)
        for ((i, roi0) in rois.withIndex()) {
            var roi = roi0
            var presence = landmarksAt(lm, bmp, roi)
            // a tracked hand that slipped out of its crop (fast motion, motion blur): one more try
            // with a crop 40% larger before giving it up to the palm detector
            if (presence < 0.3f && prev[i] != null) {
                val wide = roi.copy(size = roi.size * 1.4f)
                val p2 = landmarksAt(lm, bmp, wide)
                if (p2 >= 0.5f) { roi = wide; presence = p2; recovered++ }
            }
            if (presence < 0.3f) continue  // not a hand (any more): stop tracking here
            val k = roi.size / HandRoi.INPUT
            val image = FloatArray(63)
            for (j in 0 until 21) {
                roi.toImage(outLm[0][j * 3], outLm[0][j * 3 + 1], pts, j * 3)
                image[j * 3] = pts[j * 3] / bmp.width
                image[j * 3 + 1] = pts[j * 3 + 1] / bmp.height
                image[j * 3 + 2] = outLm[0][j * 3 + 2] * k / bmp.width
            }
            // metric landmarks come in the crop's frame: rotate them back into the image's
            val c = kotlin.math.cos(roi.rotation); val s = kotlin.math.sin(roi.rotation)
            val world = FloatArray(63)
            for (j in 0 until 21) {
                val x = outWorld[0][j * 3]; val y = outWorld[0][j * 3 + 1]
                world[j * 3] = x * c - y * s; world[j * 3 + 1] = x * s + y * c; world[j * 3 + 2] = outWorld[0][j * 3 + 2]
            }
            // two crops that converged on the same hand: keep the better one
            val dup = out.indexOfFirst { o -> dist(o.image[0] * bmp.width, o.image[1] * bmp.height, pts[0], pts[1]) < roi.size * 0.15f }
            if (dup >= 0) { if (out[dup].presence >= presence) continue else { out.removeAt(dup); tracked.removeAt(dup) } }
            val handed = outHanded[0][0]
            out += RawHand(if (handed > 0.5f) "Right" else "Left", if (handed > 0.5f) handed else 1 - handed, presence, image, world, handed)
            // next crop from these landmarks, with the crop's velocity smoothed over frames
            val next = HandRoi.fromLandmarks(pts)
            val p = prev[i]
            val vx = if (p != null) (p.vx * 0.5f + (next.cx - p.roi.cx) * 0.5f) else 0f
            val vy = if (p != null) (p.vy * 0.5f + (next.cy - p.roi.cy) * 0.5f) else 0f
            val cap = next.size * 0.3f  // never jump the crop more than 30% of its size
            tracked += Track(next, vx.coerceIn(-cap, cap), vy.coerceIn(-cap, cap))
        }
        return out
    }

    /** Runs the landmark model on [roi]; returns the hand-presence score (outputs in the out* arrays). */
    private fun landmarksAt(lm: Interpreter, bmp: Bitmap, roi: HandRoi): Float {
        val t0 = System.nanoTime()
        fillLandmarkInput(bmp, roi)
        lm.runForMultipleInputsOutputs(arrayOf(lmIn), mapOf(0 to outLm, 1 to outPresence, 2 to outHanded, 3 to outWorld))
        landmarkMs = (System.nanoTime() - t0) / 1e6f
        return outPresence[0][0]
    }

    private fun dist(x0: Float, y0: Float, x1: Float, y1: Float) = kotlin.math.hypot(x1 - x0, y1 - y0)

    private val affine = FloatArray(6)

    /** Letterboxes the frame into the 192x192 detector input (black bars, aspect kept). */
    private fun fillPalmInput(bmp: Bitmap) {
        val scale = 192f / maxOf(bmp.width, bmp.height)
        val ox = (192 - bmp.width * scale) / 2; val oy = (192 - bmp.height * scale) / 2
        if (YuvNative.available) {
            // output (u, v) -> source ((u - ox) / scale, (v - oy) / scale), in native code
            affine[0] = 1 / scale; affine[1] = 0f; affine[2] = -ox / scale
            affine[3] = 0f; affine[4] = 1 / scale; affine[5] = -oy / scale
            palmIn.rewind()
            if (YuvNative.warpToTensor(bmp, affine, 192, palmIn)) { palmIn.rewind(); return }
        }
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(ox, oy)
        val c = Canvas(palmBmp)
        c.drawColor(Color.BLACK)
        c.drawBitmap(bmp, matrix, paint)
        toFloats(palmBmp, 192, palmIn)
    }

    /**
     * The rotated square around the hand, upright, into the 224x224 landmark input: one native
     * pass (crop, rotate, bilinear, normalize) instead of Canvas + getPixels + a Kotlin loop.
     */
    private fun fillLandmarkInput(bmp: Bitmap, roi: HandRoi) {
        if (YuvNative.available) {
            roi.affine(affine)  // the exact mapping the landmarks are mapped back with
            lmIn.rewind()
            if (YuvNative.warpToTensor(bmp, affine, 224, lmIn)) { lmIn.rewind(); return }
        }
        matrix.reset()
        matrix.postTranslate(-roi.cx, -roi.cy)
        matrix.postRotate(-Math.toDegrees(roi.rotation.toDouble()).toFloat())
        matrix.postScale(HandRoi.INPUT / roi.size, HandRoi.INPUT / roi.size)
        matrix.postTranslate(HandRoi.INPUT / 2, HandRoi.INPUT / 2)
        val c = Canvas(cropBmp)
        c.drawColor(Color.BLACK)
        c.drawBitmap(bmp, matrix, paint)
        toFloats(cropBmp, 224, lmIn)
    }

    private fun toFloats(b: Bitmap, n: Int, out: ByteBuffer) {
        b.getPixels(pixels, 0, n, 0, 0, n, n)
        out.rewind()
        val f = out.asFloatBuffer()
        for (i in 0 until n * n) {
            val p = pixels[i]
            f.put(((p shr 16) and 0xff) / 255f); f.put(((p shr 8) and 0xff) / 255f); f.put((p and 0xff) / 255f)
        }
        out.rewind()
    }
}
