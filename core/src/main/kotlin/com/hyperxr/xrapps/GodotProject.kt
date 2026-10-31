package com.hyperxr.xrapps

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Godot's exported project settings (`project.binary`, "ECFG"): a count, then per setting a
 * length-prefixed key and an encoded Variant. Used to read which renderer a Godot 4 game starts
 * with and to switch it to Compatibility (OpenGL ES), the one the HyperMagic runtime can serve.
 */
object GodotProject {
    const val PATH = "assets/project.binary"
    const val METHOD = "rendering/renderer/rendering_method"
    const val METHOD_MOBILE = "rendering/renderer/rendering_method.mobile"
    const val COMPATIBILITY = "gl_compatibility"
    private const val VARIANT_STRING = 4

    /** Settings in file order, each with its raw Variant bytes; null when it is not an ECFG file. */
    fun parse(bytes: ByteArray): List<Pair<String, ByteArray>>? {
        if (bytes.size < 8 || String(bytes, 0, 4, Charsets.US_ASCII) != "ECFG") return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(4)
        val count = bb.int
        if (count < 0 || count > 100_000) return null
        val out = ArrayList<Pair<String, ByteArray>>(count)
        repeat(count) {
            if (bb.remaining() < 4) return null
            val kl = bb.int
            if (kl < 0 || kl > bb.remaining()) return null
            val key = ByteArray(kl).also { bb.get(it) }.toString(Charsets.UTF_8)
            if (bb.remaining() < 4) return null
            val vl = bb.int
            if (vl < 0 || vl > bb.remaining()) return null
            out += key to ByteArray(vl).also { bb.get(it) }
        }
        return out
    }

    /** The value when it is a String Variant. */
    fun string(v: ByteArray): String? {
        if (v.size < 8) return null
        val bb = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.int and 0xFFFF != VARIANT_STRING) return null
        val n = bb.int
        if (n < 0 || n > bb.remaining()) return null
        return String(v, 8, n, Charsets.UTF_8)
    }

    fun encodeString(s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        val pad = (4 - b.size % 4) % 4
        return ByteBuffer.allocate(8 + b.size + pad).order(ByteOrder.LITTLE_ENDIAN).putInt(VARIANT_STRING).putInt(b.size).put(b).array()
    }

    /**
     * The renderer the game uses on Android: Godot reads the ".mobile" override first; its default
     * there is "mobile" (Vulkan).
     */
    fun renderer(settings: List<Pair<String, ByteArray>>): String {
        val m = settings.associate { it.first to it.second }
        return m[METHOD_MOBILE]?.let(::string) ?: "mobile"
    }

    fun serialize(settings: List<Pair<String, ByteArray>>): ByteArray {
        val o = ByteArrayOutputStream()
        fun i32(x: Int) = o.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(x).array())
        o.write("ECFG".toByteArray(Charsets.US_ASCII))
        i32(settings.size)
        for ((k, v) in settings) {
            val kb = k.toByteArray(Charsets.UTF_8)
            i32(kb.size); o.write(kb); i32(v.size); o.write(v)
        }
        return o.toByteArray()
    }

    /** The same settings with the Compatibility renderer, on every platform. */
    fun withCompatibility(settings: List<Pair<String, ByteArray>>): List<Pair<String, ByteArray>> {
        val v = encodeString(COMPATIBILITY)
        val out = settings.filter { it.first != METHOD && it.first != METHOD_MOBILE }.toMutableList()
        out += METHOD to v
        out += METHOD_MOBILE to v
        return out
    }
}
