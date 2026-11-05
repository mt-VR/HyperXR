package com.hyperxr.daydream

import com.hyperxr.xrapps.DexPatcher
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Adler32

/**
 * Rewrites call sites in DEX bytecode by rule, without adding anything to the file: a call whose
 * answer is fixed becomes "vX = constant" (const + nops, the same length, so branch offsets and
 * try ranges stay valid) and a call to drop becomes nops. Rules name methods of the Android
 * framework or of published SDKs, whose names survive obfuscation, so they work on apps whose own
 * code is obfuscated. The DEX signature and checksum are recomputed.
 */
object DexRules {
    /**
     * [cls] may also be reached through a subclass defined in the same DEX (an Activity calling
     * its own inherited setVrModeEnabled is referenced as the app's class). [shorty], when given,
     * must match the method's short signature (e.g. "VZL").
     */
    sealed class Rule(val cls: String, val name: String, val label: String, val shorty: String? = null)

    /**
     * `invoke … cls->name` followed by `move-result vX` becomes `vX = value`. With [stringArg],
     * only calls whose argument number [argIndex] (0 = the object itself) was loaded just before
     * by a const-string with one of those strings.
     */
    class Answer(cls: String, name: String, label: String, val value: Int, val stringArg: Set<String>? = null, val argIndex: Int = 1) : Rule(cls, name, label)

    /** A call that returns nothing, removed. */
    class Drop(cls: String, name: String, label: String, shorty: String? = null) : Rule(cls, name, label, shorty)

    class Result(val dex: ByteArray, val hits: Map<String, Int>, val examples: Map<String, List<String>>) {
        val total get() = hits.values.sum()
    }

    /** How far back (16-bit units) the const-string feeding a call may be. */
    private const val ARG_WINDOW = 24

    fun patch(input: ByteArray, rules: List<Rule>): Result {
        if (input.size < 0x70 || String(input, 0, 3) != "dex") return Result(input, emptyMap(), emptyMap())
        val dex = input.copyOf()
        val b = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
        val stringIdsSize = b.getInt(0x38); val stringIdsOff = b.getInt(0x3C)
        val typeIdsOff = b.getInt(0x44)
        val protoIdsOff = b.getInt(0x4C)
        val methodIdsSize = b.getInt(0x58); val methodIdsOff = b.getInt(0x5C)
        val classDefsSize = b.getInt(0x60); val classDefsOff = b.getInt(0x64)

        fun string(i: Int): String {
            var p = b.getInt(stringIdsOff + i * 4)
            while (dex[p].toInt() and 0x80 != 0) p++
            p++
            var e = p
            while (dex[e].toInt() != 0) e++
            return String(dex, p, e - p, Charsets.UTF_8)
        }
        fun type(i: Int) = string(b.getInt(typeIdsOff + i * 4))

        // the strings rules look for, by index
        val wanted = rules.filterIsInstance<Answer>().flatMap { it.stringArg.orEmpty() }.toSet()
        val stringIndex = HashMap<String, Int>()
        if (wanted.isNotEmpty()) for (i in 0 until stringIdsSize) { val s = string(i); if (s in wanted) stringIndex[s] = i }

        // superclass of every class defined here, to follow app classes up to the framework one
        val superOf = HashMap<String, String>()
        for (c in 0 until classDefsSize) {
            val o = classDefsOff + c * 32
            val sup = b.getInt(o + 8)
            if (sup != -1) superOf[type(b.getInt(o))] = type(sup)
        }
        fun reaches(cls: String, target: String): Boolean {
            var c: String? = cls; var guard = 0
            while (c != null && guard++ < 64) { if (c == target) return true; c = superOf[c] }
            return false
        }
        val ruleNames = rules.map { it.name }.toSet()
        val targets = HashMap<Int, Rule>()
        for (m in 0 until methodIdsSize) {
            val o = methodIdsOff + m * 8
            val name = string(b.getInt(o + 4))
            if (name !in ruleNames) continue
            val cls = type(b.getShort(o).toInt() and 0xffff)
            val shorty = string(b.getInt(protoIdsOff + (b.getShort(o + 2).toInt() and 0xffff) * 12))
            rules.firstOrNull { it.name == name && (it.shorty == null || it.shorty == shorty) && reaches(cls, it.cls) }?.let { targets[m] = it }
        }
        if (targets.isEmpty()) return Result(input, emptyMap(), emptyMap())
        val argStrings = targets.values.filterIsInstance<Answer>().associateWith { r -> r.stringArg?.mapNotNull { stringIndex[it] }?.toSet() }

        val hits = LinkedHashMap<String, Int>()
        val examples = LinkedHashMap<String, MutableList<String>>()
        for (c in 0 until classDefsSize) {
            val o = classDefsOff + c * 32
            val dataOff = b.getInt(o + 24)
            if (dataOff == 0) continue
            val r = DexPatcher.Uleb(dex, dataOff)
            val sf = r.next(); val inf = r.next(); val dm = r.next(); val vm = r.next()
            repeat(sf + inf) { r.next(); r.next() }
            var methodIdx = 0
            for (k in 0 until dm + vm) {
                if (k == dm) methodIdx = 0
                methodIdx += r.next()
                r.next()
                val codeOff = r.next()
                if (codeOff == 0) continue
                val found = patchCode(b, codeOff, targets, argStrings)
                for (label in found) {
                    hits[label] = (hits[label] ?: 0) + 1
                    val list = examples.getOrPut(label) { ArrayList() }
                    if (list.size < 3) {
                        val mo = methodIdsOff + methodIdx * 8
                        list += "${type(b.getInt(o)).removePrefix("L").removeSuffix(";").replace('/', '.')}.${string(b.getInt(mo + 4))}"
                    }
                }
            }
        }
        if (hits.isEmpty()) return Result(input, emptyMap(), emptyMap())
        val sha = MessageDigest.getInstance("SHA-1").digest(dex.copyOfRange(32, dex.size))
        System.arraycopy(sha, 0, dex, 12, 20)
        val adler = Adler32().apply { update(dex, 12, dex.size - 12) }
        b.putInt(8, adler.value.toInt())
        return Result(dex, hits, examples)
    }

    /** Patches one code_item; returns the label of each site changed. */
    private fun patchCode(b: ByteBuffer, codeOff: Int, targets: Map<Int, Rule>, argStrings: Map<Answer, Set<Int>?>): List<String> {
        val insnsSize = b.getInt(codeOff + 12)
        val base = codeOff + 16
        fun unit(i: Int) = b.getShort(base + i * 2).toInt() and 0xffff
        fun put(i: Int, v: Int) = b.putShort(base + i * 2, v.toShort())
        // last const-string per register: string index and where it was
        val constStr = HashMap<Int, Long>()
        val out = ArrayList<String>()
        var i = 0
        while (i < insnsSize) {
            val u = unit(i)
            val op = u and 0xff
            val len = DexPatcher.length(u, i, ::unit)
            if (len <= 0 || i + len > insnsSize) return out
            when (op) {
                0x1a -> constStr[u ushr 8] = (unit(i + 1).toLong() shl 32) or i.toLong()
                0x1b -> constStr[u ushr 8] = ((unit(i + 1) or (unit(i + 2) shl 16)).toLong() shl 32) or i.toLong()
            }
            val invoke = op in 0x6e..0x72 || op in 0x74..0x78
            val rule = if (invoke) targets[unit(i + 1)] else null
            if (rule != null) {
                when (rule) {
                    is Drop -> {
                        for (w in 0 until len) put(i + w, 0)
                        out += rule.label; i += len; continue
                    }
                    is Answer -> {
                        val next = if (i + len < insnsSize) unit(i + len) else -1
                        val feeds = argStrings[rule]?.let { want ->
                            val reg = argRegister(op, u, unit(i + 2), rule.argIndex)
                            val c = reg?.let { constStr[it] }
                            c != null && (c ushr 32).toInt() in want && i - (c and 0xffffffffL).toInt() <= ARG_WINDOW
                        } ?: true
                        if (feeds && next >= 0 && (next and 0xff) == 0x0a) {
                            val reg = next ushr 8
                            val total = len + 1
                            var w: Int
                            if (reg < 16 && rule.value in -8..7) { put(i, 0x12 or ((rule.value and 0xf) shl 12) or (reg shl 8)); w = 1 }
                            else { put(i, 0x13 or (reg shl 8)); put(i + 1, rule.value); w = 2 }
                            while (w < total) { put(i + w, 0); w++ }
                            out += rule.label; i += total; continue
                        }
                    }
                }
            }
            i += len
        }
        return out
    }

    /** Register of argument [index] of an invoke (35c: up to five listed, 3rc: a range). */
    private fun argRegister(op: Int, u: Int, regs: Int, index: Int): Int? {
        if (op in 0x74..0x78) return if (index < (u ushr 8)) regs + index else null
        val count = u ushr 12
        if (index >= count) return null
        return when (index) {
            0 -> regs and 0xf; 1 -> (regs ushr 4) and 0xf; 2 -> (regs ushr 8) and 0xf; 3 -> (regs ushr 12) and 0xf
            else -> (u ushr 8) and 0xf
        }
    }
}
