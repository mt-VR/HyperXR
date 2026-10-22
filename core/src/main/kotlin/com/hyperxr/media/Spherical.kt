package com.hyperxr.media

import kotlin.math.abs

/** How the two eyes are packed in a stereo 360 frame. */
enum class StereoLayout { MONO, TOP_BOTTOM, LEFT_RIGHT }

/**
 * Result of 360 detection. [fromMetadata] = the file says so (Photo Sphere XMP, Spherical Video
 * V1/V2); otherwise the 2:1 equirectangular shape alone made it "probably 360".
 */
data class SphericalInfo(
    val spherical: Boolean,
    val fromMetadata: Boolean,
    val stereo: StereoLayout = StereoLayout.MONO,
    /** "equirectangular", "cubemap" (YouTube EAC/cubemap: detected but not rendered) or "". */
    val projection: String = "",
) {
    val renderable: Boolean get() = spherical && projection != "cubemap"

    companion object { val FLAT = SphericalInfo(false, false) }
}

/**
 * Detects 360 photos and videos from the bytes Android cameras and editors write:
 * - photos: XMP `GPano:ProjectionType = equirectangular` (Google Photo Sphere, Insta360, Ricoh...)
 * - videos: Spherical Video V2 boxes (`sv3d`, `st3d`, `proj/equi|cbmp`) or V1 XML (`GSpherical`)
 * - fallback: exact 2:1 frame (equirectangular mono) or 1:1 with top-bottom metadata.
 */
object Spherical {
    /** Equirectangular frames are exactly twice as wide as tall; allow 1% for odd encoder sizes. */
    fun isEquirectAspect(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && abs(width.toFloat() / height - 2f) <= 0.02f

    /** [head] = the first bytes of a JPEG/PNG/WebP (XMP lives near the start). */
    fun detectImage(head: ByteArray, width: Int, height: Int): SphericalInfo {
        val proj = xmpValue(head, "ProjectionType")
        if (proj != null) {
            val eq = proj.equals("equirectangular", ignoreCase = true)
            if (eq) return SphericalInfo(true, true, StereoLayout.MONO, "equirectangular")
        }
        return if (isEquirectAspect(width, height)) SphericalInfo(true, false, StereoLayout.MONO, "equirectangular") else SphericalInfo.FLAT
    }

    /**
     * [chunks] = byte ranges of an MP4/MOV likely to hold the `moov` box (its start and its end:
     * `moov` sits at either end depending on the muxer).
     */
    fun detectVideo(chunks: List<ByteArray>, width: Int, height: Int): SphericalInfo {
        var spherical = false; var meta = false
        var stereo = StereoLayout.MONO; var projection = ""
        for (b in chunks) {
            if (findBox(b, "sv3d") >= 0) { spherical = true; meta = true }
            val st = findBox(b, "st3d")
            if (st >= 0 && st + 8 < b.size) {
                // FullBox: 4 bytes version/flags after the type, then stereo_mode
                stereo = when (b[st + 8].toInt()) { 1 -> StereoLayout.TOP_BOTTOM; 2 -> StereoLayout.LEFT_RIGHT; else -> stereo }
                meta = true
            }
            if (spherical && findBox(b, "equi") >= 0) projection = "equirectangular"
            if (spherical && findBox(b, "cbmp") >= 0) projection = "cubemap"
            // Spherical Video V1 (XML in a uuid box)
            val v1 = xmpValue(b, "Spherical")
            if (v1 != null && v1.equals("true", ignoreCase = true)) {
                spherical = true; meta = true
                val sm = xmpValue(b, "StereoMode")?.lowercase()
                if (sm == "top-bottom") stereo = StereoLayout.TOP_BOTTOM
                if (sm == "left-right") stereo = StereoLayout.LEFT_RIGHT
                val pt = xmpValue(b, "ProjectionType")
                if (pt != null) projection = if (pt.equals("equirectangular", true)) "equirectangular" else pt.lowercase()
            }
        }
        if (spherical) return SphericalInfo(true, meta, stereo, projection.ifEmpty { "equirectangular" })
        // stereo metadata without sv3d is a 3D (not 360) video: leave it flat
        if (isEquirectAspect(width, height)) return SphericalInfo(true, false, StereoLayout.MONO, "equirectangular")
        return SphericalInfo.FLAT
    }

    /**
     * Value of an XMP property written as an attribute (`GPano:ProjectionType="x"`) or as an
     * element (`<GPano:ProjectionType>x</...>`); the namespace prefix is ignored.
     */
    fun xmpValue(bytes: ByteArray, property: String): String? {
        val key = (":$property").toByteArray()
        var from = 0
        while (true) {
            val i = indexOf(bytes, key, from)
            if (i < 0) return null
            var j = i + key.size
            // attribute form
            while (j < bytes.size && (bytes[j] == ' '.code.toByte())) j++
            if (j < bytes.size && bytes[j] == '='.code.toByte()) {
                j++
                while (j < bytes.size && bytes[j] == ' '.code.toByte()) j++
                if (j < bytes.size && (bytes[j] == '"'.code.toByte() || bytes[j] == '\''.code.toByte())) {
                    val q = bytes[j]; val start = j + 1
                    var end = start
                    while (end < bytes.size && bytes[end] != q && end - start < 200) end++
                    return String(bytes, start, end - start, Charsets.UTF_8).trim()
                }
            } else if (j < bytes.size && bytes[j] == '>'.code.toByte()) {
                val start = j + 1
                var end = start
                while (end < bytes.size && bytes[end] != '<'.code.toByte() && end - start < 200) end++
                return String(bytes, start, end - start, Charsets.UTF_8).trim()
            }
            from = i + 1
        }
    }

    /**
     * Offset of the type field of an ISO-BMFF box [type] whose 32-bit size (the 4 bytes before it)
     * is plausible for a metadata box, so the same four letters inside compressed media are skipped.
     */
    fun findBox(b: ByteArray, type: String): Int {
        val t = type.toByteArray()
        var from = 4
        while (true) {
            val i = indexOf(b, t, from)
            if (i < 0) return -1
            val size = ((b[i - 4].toInt() and 0xff) shl 24) or ((b[i - 3].toInt() and 0xff) shl 16) or
                ((b[i - 2].toInt() and 0xff) shl 8) or (b[i - 1].toInt() and 0xff)
            if (size in 8..1_000_000) return i
            from = i + 1
        }
    }

    fun indexOf(hay: ByteArray, needle: ByteArray, from: Int = 0): Int {
        if (needle.isEmpty()) return from
        val first = needle[0]
        var i = from.coerceAtLeast(0)
        val last = hay.size - needle.size
        while (i <= last) {
            if (hay[i] == first) {
                var k = 1
                while (k < needle.size && hay[i + k] == needle[k]) k++
                if (k == needle.size) return i
            }
            i++
        }
        return -1
    }
}
