package com.hyperxr.xrapps

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One element of a compiled Android XML file (AndroidManifest.xml inside an APK). */
class XmlElement(val name: String, val attrs: Map<String, String>, val parent: XmlElement?) {
    val children = ArrayList<XmlElement>()
    fun attr(n: String): String? = attrs[n]
    fun all(n: String): List<XmlElement> = children.filter { it.name == n }
    fun descendants(n: String): List<XmlElement> = children.flatMap { (if (it.name == n) listOf(it) else emptyList()) + it.descendants(n) }
}

/**
 * Reader for Android's binary XML (AXML): string pool, resource map and element chunks. Attribute
 * names stripped by obfuscators are recovered from the resource map for the attributes HyperXR
 * reads. Values become strings ("true", "26", "@0x7f010000", ...).
 */
object BinaryXml {
    private const val RES_STRING_POOL = 0x0001
    private const val RES_XML = 0x0003
    private const val RES_XML_START_ELEMENT = 0x0102
    private const val RES_XML_END_ELEMENT = 0x0103
    private const val RES_XML_RESOURCE_MAP = 0x0180

    /** android:* attribute resource ids -> names (frameworks/base/core/res/res/values/public.xml). */
    private val ATTR_IDS = mapOf(
        0x01010003 to "name", 0x01010024 to "value", 0x01010025 to "resource", 0x0101028e to "required",
        0x01010281 to "glEsVersion", 0x0101020c to "minSdkVersion", 0x01010270 to "targetSdkVersion",
        0x0101021b to "versionCode", 0x0101021c to "versionName", 0x01010001 to "label", 0x01010002 to "icon",
        0x01010010 to "exported", 0x0101000f to "debuggable", 0x010104ea to "extractNativeLibs",
        0x01010591 to "isSplitRequired", 0x0101064e to "requiredSplitTypes",
    )

    /** One attribute's typed value inside the file, for in-place edits ([dataOffset] = 32-bit data). */
    class Slot(val element: String, val name: String, val rawString: String?, val dataType: Int, val data: Int, val dataOffset: Int, val elementIndex: Int)

    /** Every attribute of every element, in document order (names recovered like [parse]). */
    fun slots(bytes: ByteArray): List<Slot> {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 8 && (b.getShort(0).toInt() and 0xffff) == RES_XML) { "Não é um XML binário do Android" }
        var strings: List<String> = emptyList()
        var resIds = IntArray(0)
        val out = ArrayList<Slot>()
        var element = 0
        var pos = b.getShort(2).toInt() and 0xffff
        while (pos + 8 <= bytes.size) {
            val type = b.getShort(pos).toInt() and 0xffff
            val headerSize = b.getShort(pos + 2).toInt() and 0xffff
            val size = b.getInt(pos + 4)
            require(size >= 8 && pos + size <= bytes.size) { "Bloco corrompido em $pos" }
            when (type) {
                RES_STRING_POOL -> strings = readStringPool(b, pos)
                RES_XML_RESOURCE_MAP -> resIds = IntArray((size - headerSize) / 4) { b.getInt(pos + headerSize + it * 4) }
                RES_XML_START_ELEMENT -> {
                    val ext = pos + headerSize
                    val name = str(strings, b.getInt(ext + 4))
                    val attrStart = b.getShort(ext + 8).toInt() and 0xffff
                    val attrSize = b.getShort(ext + 10).toInt() and 0xffff
                    val count = b.getShort(ext + 12).toInt() and 0xffff
                    for (i in 0 until count) {
                        val a = ext + attrStart + i * attrSize
                        val nameIdx = b.getInt(a + 4)
                        var an = str(strings, nameIdx)
                        if (nameIdx in resIds.indices) ATTR_IDS[resIds[nameIdx]]?.let { an = it }
                        val raw = b.getInt(a + 8)
                        out += Slot(name, an, if (raw >= 0) str(strings, raw) else null, b.get(a + 15).toInt() and 0xff, b.getInt(a + 16), a + 16, element)
                    }
                    element++
                }
            }
            pos += size
        }
        return out
    }

    const val TYPE_STRING = 0x03
    const val TYPE_INT_DEC = 0x10
    const val TYPE_INT_HEX = 0x11
    const val TYPE_INT_BOOLEAN = 0x12

    fun parse(bytes: ByteArray): XmlElement {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 8 && (b.getShort(0).toInt() and 0xffff) == RES_XML) { "Não é um XML binário do Android" }
        var strings: List<String> = emptyList()
        var resIds = IntArray(0)
        val root = XmlElement("#document", emptyMap(), null)
        var current = root
        var pos = b.getShort(2).toInt() and 0xffff
        while (pos + 8 <= bytes.size) {
            val type = b.getShort(pos).toInt() and 0xffff
            val headerSize = b.getShort(pos + 2).toInt() and 0xffff
            val size = b.getInt(pos + 4)
            require(size >= 8 && pos + size <= bytes.size) { "Bloco corrompido em $pos" }
            when (type) {
                RES_STRING_POOL -> strings = readStringPool(b, pos)
                RES_XML_RESOURCE_MAP -> resIds = IntArray((size - headerSize) / 4) { b.getInt(pos + headerSize + it * 4) }
                RES_XML_START_ELEMENT -> {
                    val ext = pos + headerSize
                    val name = str(strings, b.getInt(ext + 4))
                    val attrStart = b.getShort(ext + 8).toInt() and 0xffff
                    val attrSize = b.getShort(ext + 10).toInt() and 0xffff
                    val count = b.getShort(ext + 12).toInt() and 0xffff
                    val attrs = LinkedHashMap<String, String>()
                    for (i in 0 until count) {
                        val a = ext + attrStart + i * attrSize
                        val nameIdx = b.getInt(a + 4)
                        var an = str(strings, nameIdx)
                        if (nameIdx in resIds.indices) ATTR_IDS[resIds[nameIdx]]?.let { an = it }
                        if (an.isEmpty()) continue
                        val raw = b.getInt(a + 8)
                        val dataType = b.get(a + 15).toInt() and 0xff
                        val data = b.getInt(a + 16)
                        attrs[an] = if (raw >= 0) str(strings, raw) else typed(dataType, data, strings)
                    }
                    val el = XmlElement(name, attrs, current)
                    current.children += el
                    current = el
                }
                RES_XML_END_ELEMENT -> current = current.parent ?: root
            }
            pos += size
        }
        return root.children.firstOrNull() ?: error("XML vazio")
    }

    private fun typed(type: Int, data: Int, strings: List<String>): String = when (type) {
        0x03 -> str(strings, data)
        0x10 -> data.toString()
        0x11 -> "0x" + Integer.toHexString(data)
        0x12 -> (data != 0).toString()
        0x01 -> "@0x" + Integer.toHexString(data)
        0x04 -> java.lang.Float.intBitsToFloat(data).toString()
        else -> data.toString()
    }

    private fun str(strings: List<String>, i: Int) = if (i in strings.indices) strings[i] else ""

    private fun readStringPool(b: ByteBuffer, pos: Int): List<String> {
        val headerSize = b.getShort(pos + 2).toInt() and 0xffff
        val count = b.getInt(pos + 8)
        val flags = b.getInt(pos + 16)
        val stringsStart = b.getInt(pos + 20)
        val utf8 = flags and 0x100 != 0
        val out = ArrayList<String>(count)
        for (i in 0 until count) {
            val off = pos + stringsStart + b.getInt(pos + headerSize + i * 4)
            out += if (utf8) {
                var p = off
                // utf-16 length then utf-8 length, each 1 or 2 bytes
                p += if (b.get(p).toInt() and 0x80 != 0) 2 else 1
                var len = b.get(p).toInt() and 0xff
                if (len and 0x80 != 0) { len = ((len and 0x7f) shl 8) or (b.get(p + 1).toInt() and 0xff); p += 2 } else p += 1
                String(b.array(), p, len, Charsets.UTF_8)
            } else {
                var p = off
                var len = b.getShort(p).toInt() and 0xffff
                if (len and 0x8000 != 0) { len = ((len and 0x7fff) shl 16) or (b.getShort(p + 2).toInt() and 0xffff); p += 4 } else p += 2
                val chars = CharArray(len) { k -> b.getShort(p + k * 2).toInt().toChar() }
                String(chars)
            }
        }
        return out
    }
}
