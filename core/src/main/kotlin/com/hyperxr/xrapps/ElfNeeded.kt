package com.hyperxr.xrapps

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The libraries a native library asks the Android linker for (DT_NEEDED), read from its ELF
 * dynamic section without loading the whole file: a game whose library needs one that neither the
 * APK nor Android has closes the moment it opens.
 */
object ElfNeeded {
    private const val PT_LOAD = 1
    private const val PT_DYNAMIC = 2
    private const val DT_NEEDED = 1L
    private const val DT_STRTAB = 5L
    private const val DT_STRSZ = 10L

    /** Libraries every Android app can link against (the NDK's stable system libraries). */
    val SYSTEM = setOf(
        "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libEGL.so", "libGLESv1_CM.so", "libGLESv2.so",
        "libGLESv3.so", "libvulkan.so", "libz.so", "libjnigraphics.so", "libOpenSLES.so", "libOpenMAXAL.so", "libmediandk.so",
        "libcamera2ndk.so", "libaaudio.so", "libnativewindow.so", "libneuralnetworks.so", "libsync.so", "libamidi.so",
        "libbinder_ndk.so", "libstdc++.so", "libicu.so", "libnativehelper.so",
    )

    /** Libraries that only Meta's headsets have (in their system image). */
    val QUEST_ONLY = setOf("libvrapi.so", "libvrintegrationloader.so", "libossdk.so", "libsxrapi.so")

    /**
     * DT_NEEDED names of the ELF file that [open] streams (opened up to three times); empty when
     * it is not an ELF file or its layout is unusual.
     */
    fun read(open: () -> InputStream): List<String> = runCatching { parse(open) }.getOrDefault(emptyList())

    private fun parse(open: () -> InputStream): List<String> {
        val head = open().use { it.readUpTo(65536) }
        if (head.size < 52 || head[0] != 0x7F.toByte() || head[1] != 'E'.code.toByte() || head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()) return emptyList()
        val is64 = head[4].toInt() == 2
        val bb = ByteBuffer.wrap(head).order(if (head[5].toInt() == 2) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        val phoff = if (is64) bb.getLong(0x20) else bb.getInt(0x1C).toLong() and 0xFFFFFFFFL
        val phentsize = bb.getShort(if (is64) 0x36 else 0x2A).toInt() and 0xFFFF
        val phnum = bb.getShort(if (is64) 0x38 else 0x2C).toInt() and 0xFFFF
        if (phoff <= 0 || phoff + phentsize.toLong() * phnum > head.size) return emptyList()
        class Load(val offset: Long, val vaddr: Long, val size: Long)
        val loads = ArrayList<Load>()
        var dynOff = -1L; var dynSize = 0L
        for (i in 0 until phnum) {
            val p = (phoff + i.toLong() * phentsize).toInt()
            val type = bb.getInt(p)
            val off: Long; val vaddr: Long; val fsz: Long
            if (is64) { off = bb.getLong(p + 8); vaddr = bb.getLong(p + 16); fsz = bb.getLong(p + 32) }
            else { off = bb.getInt(p + 4).toLong() and 0xFFFFFFFFL; vaddr = bb.getInt(p + 8).toLong() and 0xFFFFFFFFL; fsz = bb.getInt(p + 16).toLong() and 0xFFFFFFFFL }
            if (type == PT_LOAD) loads += Load(off, vaddr, fsz)
            if (type == PT_DYNAMIC) { dynOff = off; dynSize = fsz }
        }
        if (dynOff < 0 || dynSize <= 0 || dynSize > (1 shl 20)) return emptyList()
        val dyn = ByteBuffer.wrap(readAt(open, dynOff, dynSize.toInt())).order(bb.order())
        val entry = if (is64) 16 else 8
        val needed = ArrayList<Long>()
        var strtab = -1L; var strsz = 0L
        var i = 0
        while (i + entry <= dyn.capacity()) {
            val tag = if (is64) dyn.getLong(i) else dyn.getInt(i).toLong()
            val v = if (is64) dyn.getLong(i + 8) else dyn.getInt(i + 4).toLong() and 0xFFFFFFFFL
            if (tag == 0L) break
            when (tag) { DT_NEEDED -> needed += v; DT_STRTAB -> strtab = v; DT_STRSZ -> strsz = v }
            i += entry
        }
        if (needed.isEmpty() || strtab < 0 || strsz <= 0 || strsz > (4 shl 20)) return emptyList()
        val seg = loads.firstOrNull { strtab >= it.vaddr && strtab < it.vaddr + it.size } ?: return emptyList()
        val strings = readAt(open, seg.offset + (strtab - seg.vaddr), strsz.toInt())
        return needed.mapNotNull { o ->
            if (o < 0 || o >= strings.size) return@mapNotNull null
            var e = o.toInt()
            while (e < strings.size && strings[e] != 0.toByte()) e++
            String(strings, o.toInt(), e - o.toInt(), Charsets.UTF_8)
        }
    }

    private fun readAt(open: () -> InputStream, offset: Long, size: Int): ByteArray = open().use { s ->
        var left = offset
        while (left > 0) {
            val k = s.skip(left)
            if (k <= 0) { if (s.read() < 0) return ByteArray(0); left-- } else left -= k
        }
        s.readUpTo(size)
    }

    private fun InputStream.readUpTo(n: Int): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) { val k = read(out, got, n - got); if (k < 0) break; got += k }
        return if (got == n) out else out.copyOf(got)
    }

    /**
     * What [libs] (APK paths of one ABI to the name list each needs) ask for that is in neither
     * the APK nor Android, sorted.
     */
    fun missing(needed: Map<String, List<String>>): List<String> {
        val bundled = needed.keys.map { it.substringAfterLast('/') }.toSet()
        return needed.values.flatten().filter { it !in bundled && it !in SYSTEM }.distinct().sorted()
    }
}
