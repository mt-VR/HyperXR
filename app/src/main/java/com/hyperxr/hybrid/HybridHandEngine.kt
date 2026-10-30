package com.hyperxr.hybrid

import android.graphics.Bitmap
import com.hyperxr.handtracking.HandEngine
import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.RawHand
import com.hyperxr.handtracking.RtmHand
import java.io.ByteArrayOutputStream

/**
 * HybridMagic hand tracking: while the HybridMagic PC program is connected, each camera frame is
 * tracked by the PC's GPU (detector on every frame, mirrored second look, re-cropped second pass:
 * hybridmagic/engine.py) and the phone only sends the image and reads the joints. Whenever the PC
 * is missing or late, the frame is tracked by [local], the phone's own engine, so the hands never
 * stop. The joints become the pipeline's hands exactly as RtmHandEngine makes them.
 */
class HybridHandEngine(private val local: HandEngine) : HandEngine {
    override val device: String
        get() = if (usingPc) "PC: ${HybridLink.pcName.ifBlank { "conectado" }} (HybridMagic)" else local.device
    override val detectorMs: Float get() = if (usingPc) 0f else local.detectorMs
    override val landmarkMs: Float get() = if (usingPc) pcMs else local.landmarkMs

    @Volatile private var usingPc = false
    @Volatile private var pcMs = 0f
    private var seq = 0
    private var misses = 0
    private var pauseUntil = 0L
    private val jpeg = Buffer()

    override fun process(bmp: Bitmap): List<RawHand> {
        val now = System.nanoTime()
        if (HybridLink.connected && now >= pauseUntil) {
            jpeg.reset()
            bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, jpeg)
            val r = HybridLink.request(++seq, bmp.width, bmp.height, jpeg.bytes(), jpeg.size(), TIMEOUT_MS)
            if (r != null) {
                misses = 0; usingPc = true
                pcMs = r.pcMs
                return r.hands.map { toRaw(it, bmp.width, bmp.height) }
            }
            // a slow Wi-Fi moment: this frame on the phone; several in a row and the PC rests a while
            if (++misses >= 4) { pauseUntil = now + 2_000_000_000L; misses = 0 }
        }
        usingPc = false
        return local.process(bmp)
    }

    private fun toRaw(h: HybridLink.Hand, w: Int, hgt: Int): RawHand {
        val image = FloatArray(HandJoint.COUNT * 3); val world = FloatArray(HandJoint.COUNT * 3)
        RtmHand.toPipeline(h.joints, w, hgt, image, world)
        val right = RtmHand.rightScore(h.joints)
        return RawHand(if (right >= 0.5f) "Right" else "Left", if (right >= 0.5f) right else 1 - right, h.presence, image, world, right, worldEstimated = true)
    }

    override fun close() = local.close()

    /** A JPEG buffer reused frame after frame (its array is handed over without a copy). */
    private class Buffer : ByteArrayOutputStream(64 * 1024) { fun bytes(): ByteArray = buf }

    companion object {
        private const val QUALITY = 82
        /** Longer than this and the phone's own tracker is faster: the frame is tracked here. */
        private const val TIMEOUT_MS = 70L
    }
}
