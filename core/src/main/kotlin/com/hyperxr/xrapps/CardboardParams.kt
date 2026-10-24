package com.hyperxr.xrapps

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64

/**
 * Viewer description in Google's Cardboard format (DeviceParams in cardboard_device.proto), so
 * Cardboard and Daydream (GVR SDK) apps render with the HyperXR viewer calibration instead of a
 * generic Cardboard v1.
 *  - GVR / Cardboard Java SDK apps read `/sdcard/Cardboard/current_device_params`: an 8-byte
 *    big-endian header (sentinel 0x35587A2B, length) followed by the message.
 *  - Apps on the new open-source Cardboard SDK take the same message from a QR URL
 *    (`google.com/cardboard/cfg?p=<base64url>`).
 */
object CardboardParams {
    const val SENTINEL = 0x35587A2B
    const val FILE_PATH = "Cardboard/current_device_params"

    /** Matches DeviceParams.ButtonType: a VR Box button taps the screen through the case. */
    const val BUTTON_INDIRECT_TOUCH = 3

    data class Viewer(
        val vendor: String = "HyperXR",
        val model: String = "VR Box",
        val screenToLensM: Float = 0.042f,
        val interLensM: Float = 0.060f,
        /** Same angle for outer, inner, top and bottom: symmetric lenses. */
        val halfFovDeg: Float = 47.5f,
        val trayToLensM: Float = 0.035f,
        val k1: Float = 0.22f,
        val k2: Float = 0.24f,
        val button: Int = BUTTON_INDIRECT_TOUCH,
    )

    fun fromSettings(lensSeparationMm: Float, fovDegrees: Float, k1: Float, k2: Float, vendor: String = "HyperXR", model: String = "VR Box",
                     screenToLensMm: Float = 42f, trayToLensMm: Float = 35f, button: Int = BUTTON_INDIRECT_TOUCH) = Viewer(
        vendor = vendor, model = model, screenToLensM = screenToLensMm / 1000f, trayToLensM = trayToLensMm / 1000f, button = button,
        interLensM = lensSeparationMm / 1000f,
        halfFovDeg = (fovDegrees / 2f).coerceIn(20f, 60f),
        k1 = k1, k2 = k2,
    )

    /** Protocol buffer encoding of DeviceParams. */
    fun encode(v: Viewer): ByteArray {
        val out = ByteArrayOutputStream()
        fun varint(x: Int) { var n = x; while (n and 0x7f.inv() != 0) { out.write((n and 0x7f) or 0x80); n = n ushr 7 }; out.write(n) }
        fun tag(field: Int, wire: Int) = varint((field shl 3) or wire)
        fun fixed(f: Float) { val b = java.lang.Float.floatToIntBits(f); for (i in 0 until 4) out.write((b ushr (8 * i)) and 0xff) }
        fun string(field: Int, s: String) { val b = s.toByteArray(); tag(field, 2); varint(b.size); out.write(b) }
        fun float(field: Int, f: Float) { tag(field, 5); fixed(f) }
        fun floats(field: Int, fs: FloatArray) { tag(field, 2); varint(fs.size * 4); fs.forEach { fixed(it) } }
        string(1, v.vendor)
        string(2, v.model)
        float(3, v.screenToLensM)
        float(4, v.interLensM)
        floats(5, floatArrayOf(v.halfFovDeg, v.halfFovDeg, v.halfFovDeg, v.halfFovDeg))
        float(6, v.trayToLensM)
        floats(7, floatArrayOf(v.k1, v.k2))
        tag(12, 0); varint(v.button)
        return out.toByteArray()
    }

    fun fileBytes(v: Viewer): ByteArray {
        val msg = encode(v)
        return ByteBuffer.allocate(8 + msg.size).putInt(SENTINEL).putInt(msg.size).put(msg).array()
    }

    fun qrUrl(v: Viewer): String = "https://google.com/cardboard/cfg?p=" + Base64.getUrlEncoder().withoutPadding().encodeToString(encode(v))
}
