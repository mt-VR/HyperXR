package com.hyperxr.xrapps

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Adler32

/**
 * Removes "is this a Daydream phone?" checks from an app's own code (DEX bytecode), so a
 * Daydream app does not refuse to start on a phone that is not Daydream-ready.
 *
 * Only the app's call sites are changed, never the Google VR SDK itself: in the app's methods,
 *     invoke-static/virtual {..}, Lcom/google/vr/ndk/base/DaydreamApi;->isDaydreamReadyPlatform(..)Z
 *     move-result vX
 * becomes "vX = true" (const + nops, same length, so branch offsets and try ranges stay valid).
 * The SDK keeps its own answer (not a Daydream phone) and therefore keeps running in Cardboard
 * mode, which needs no Google VR Services. The DEX checksum and signature are recomputed.
 */
object DexPatcher {
    /** Checks answered "yes" at the app's call sites (class descriptor, method name). */
    val CHECKS = listOf(
        "Lcom/google/vr/ndk/base/DaydreamApi;" to "isDaydreamReadyPlatform",
        "Lcom/google/vr/ndk/base/DaydreamUtils;" to "isDaydreamPhone",
        "Lcom/google/vr/ndk/base/DaydreamUtilsWrapper;" to "isDaydreamPhone",
    )
    /** Code of these packages is left alone (the SDK must keep seeing the truth). */
    private val SDK_PREFIXES = listOf("Lcom/google/vr/", "Lcom/google/android/vr/")

    class Result(val dex: ByteArray, val sites: Int, val methods: List<String>)

    fun patch(input: ByteArray, checks: List<Pair<String, String>> = CHECKS): Result {
        val dex = input.copyOf()
        val b = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
        if (dex.size < 0x70 || String(dex, 0, 3) != "dex") return Result(input, 0, emptyList())
        val stringIdsOff = b.getInt(0x3C); val stringIdsSize = b.getInt(0x38)
        val typeIdsOff = b.getInt(0x44); val typeIdsSize = b.getInt(0x40)
        val methodIdsOff = b.getInt(0x5C); val methodIdsSize = b.getInt(0x58)
        val classDefsOff = b.getInt(0x64); val classDefsSize = b.getInt(0x60)

        fun string(i: Int): String {
            var p = b.getInt(stringIdsOff + i * 4)
            // uleb128 length (utf-16 units), then MUTF-8 bytes up to 0
            while (dex[p].toInt() and 0x80 != 0) p++
            p++
            var e = p
            while (dex[e].toInt() != 0) e++
            return String(dex, p, e - p, Charsets.UTF_8)
        }
        fun type(i: Int) = string(b.getInt(typeIdsOff + i * 4))

        // method ids to replace
        val targets = HashSet<Int>()
        for (m in 0 until methodIdsSize) {
            val o = methodIdsOff + m * 8
            val cls = type(b.getShort(o).toInt() and 0xffff)
            val name = string(b.getInt(o + 4))
            if (checks.any { it.first == cls && it.second == name }) targets += m
        }
        if (targets.isEmpty() || stringIdsSize == 0 || typeIdsSize == 0) return Result(input, 0, emptyList())

        var sites = 0
        val where = ArrayList<String>()
        for (c in 0 until classDefsSize) {
            val o = classDefsOff + c * 32
            val clsName = type(b.getInt(o))
            if (SDK_PREFIXES.any { clsName.startsWith(it) }) continue
            val dataOff = b.getInt(o + 24)
            if (dataOff == 0) continue
            val r = Uleb(dex, dataOff)
            val sf = r.next(); val inf = r.next(); val dm = r.next(); val vm = r.next()
            repeat(sf + inf) { r.next(); r.next() }
            var methodIdx = 0
            for (k in 0 until dm + vm) {
                if (k == dm) methodIdx = 0
                methodIdx += r.next()
                r.next() // access flags
                val codeOff = r.next()
                if (codeOff == 0) continue
                val n = patchCode(dex, b, codeOff, targets)
                if (n > 0) {
                    sites += n
                    val mo = methodIdsOff + methodIdx * 8
                    where += "${clsName.removePrefix("L").removeSuffix(";").replace('/', '.')}.${string(b.getInt(mo + 4))}"
                }
            }
        }
        if (sites == 0) return Result(input, 0, emptyList())
        // signature (SHA-1 of everything after it), then checksum (Adler-32 of everything after it)
        val sha = MessageDigest.getInstance("SHA-1").digest(dex.copyOfRange(32, dex.size))
        System.arraycopy(sha, 0, dex, 12, 20)
        val adler = Adler32().apply { update(dex, 12, dex.size - 12) }
        b.putInt(8, adler.value.toInt())
        return Result(dex, sites, where)
    }

    /** Rewrites matching call + move-result pairs in one code_item; returns how many. */
    private fun patchCode(dex: ByteArray, b: ByteBuffer, codeOff: Int, targets: Set<Int>): Int {
        val insnsSize = b.getInt(codeOff + 12)
        val base = codeOff + 16
        fun unit(i: Int) = b.getShort(base + i * 2).toInt() and 0xffff
        var i = 0; var n = 0
        while (i < insnsSize) {
            val u = unit(i)
            val op = u and 0xff
            val len = length(u, i, ::unit)
            if (len <= 0) return n  // unknown layout: stop touching this method
            if ((op == 0x71 || op == 0x6e || op == 0x77 || op == 0x74) && i + len < insnsSize) {
                val method = unit(i + 1)
                val next = unit(i + len)
                if (method in targets && (next and 0xff) == 0x0a) {
                    val reg = next ushr 8
                    // const vReg, #1 then nops over the rest (call 3 units + move-result 1 unit)
                    val total = len + 1
                    var w = 0
                    if (reg < 16) { put(b, base, i, 0x12 or (1 shl 12) or (reg shl 8)); w = 1 }
                    else { put(b, base, i, 0x13 or (reg shl 8)); put(b, base, i + 1, 1); w = 2 }
                    while (w < total) { put(b, base, i + w, 0); w++ }
                    n++
                    i += total
                    continue
                }
            }
            i += len
        }
        return n
    }

    private fun put(b: ByteBuffer, base: Int, unit: Int, v: Int) { b.putShort(base + unit * 2, v.toShort()) }

    /** Length in 16-bit units of the instruction (or payload) starting with [u] at [i]. */
    private fun length(u: Int, i: Int, unit: (Int) -> Int): Int {
        val op = u and 0xff
        if (op == 0x00) {
            return when (u) {
                0x0100 -> unit(i + 1) * 2 + 4                                   // packed-switch payload
                0x0200 -> unit(i + 1) * 4 + 2                                   // sparse-switch payload
                0x0300 -> {                                                     // fill-array-data payload
                    val width = unit(i + 1); val size = unit(i + 2) or (unit(i + 3) shl 16)
                    ((size.toLong() * width + 1) / 2 + 4).toInt()
                }
                else -> 1
            }
        }
        return when (op) {
            in 0x01..0x01, in 0x04..0x04, in 0x07..0x07 -> 1
            0x02, 0x05, 0x08 -> 2
            0x03, 0x06, 0x09 -> 3
            in 0x0a..0x12 -> 1
            0x13, 0x15, 0x16, 0x19, 0x1a, 0x1c, 0x1f, 0x20, 0x22, 0x23 -> 2
            0x14, 0x17, 0x1b, 0x24, 0x25, 0x26 -> 3
            0x18 -> 5
            0x1d, 0x1e, 0x21, 0x27, 0x28 -> 1
            0x29 -> 2
            0x2a, 0x2b, 0x2c -> 3
            in 0x2d..0x3d -> 2
            in 0x3e..0x43 -> 1
            in 0x44..0x6d -> 2
            in 0x6e..0x72 -> 3
            0x73 -> 1
            in 0x74..0x78 -> 3
            0x79, 0x7a -> 1
            in 0x7b..0x8f -> 1
            in 0x90..0xaf -> 2
            in 0xb0..0xcf -> 1
            in 0xd0..0xe2 -> 2
            in 0xe3..0xf9 -> 1
            0xfa, 0xfb -> 4
            0xfc, 0xfd -> 3
            0xfe, 0xff -> 2
            else -> -1
        }
    }

    private class Uleb(private val d: ByteArray, var p: Int) {
        fun next(): Int {
            var r = 0; var s = 0
            while (true) {
                val v = d[p++].toInt() and 0xff
                r = r or ((v and 0x7f) shl s)
                if (v and 0x80 == 0) return r
                s += 7
            }
        }
    }
}
