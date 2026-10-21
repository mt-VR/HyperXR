package com.hyperxr.gltf

import com.hyperxr.math.Mat4
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class GltfPrimitive(
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray?,
    val colors: FloatArray?,
    val indices: IntArray,
    val material: Int,
)

class GltfMesh(val name: String, val primitives: List<GltfPrimitive>)

class GltfMaterial(
    val name: String,
    val baseColor: FloatArray,
    val baseColorImage: Int?,
    val emissive: FloatArray,
    val metallic: Float,
    val roughness: Float,
    val alphaMode: String,
    val alphaCutoff: Float,
    val doubleSided: Boolean,
)

class GltfImage(val bytes: ByteArray, val mimeType: String?)

/** One placement of a mesh in the scene; [world] is column-major. */
class GltfInstance(val mesh: Int, val world: FloatArray, val nodeName: String)

class GltfModel(
    val meshes: List<GltfMesh>,
    val materials: List<GltfMaterial>,
    val images: List<GltfImage>,
    val instances: List<GltfInstance>,
    val boundsMin: Vec3,
    val boundsMax: Vec3,
    val warnings: List<String>,
) {
    val triangleCount: Int get() = instances.sumOf { inst -> meshes[inst.mesh].primitives.sumOf { it.indices.size / 3 } }
}

class GltfException(message: String) : Exception(message)

/**
 * glTF 2.0 importer for GLB and .gltf (embedded or external buffers/images). Supports triangle
 * primitives, POSITION/NORMAL/TEXCOORD_0/COLOR_0, indices, base-color materials and the node
 * hierarchy. Unsupported required extensions (e.g. Draco) are reported, never silently ignored.
 */
object GltfLoader {
    private val json = Json { ignoreUnknownKeys = true }
    private val SUPPORTED_REQUIRED = setOf("KHR_materials_unlit", "KHR_texture_transform")

    /** @param resolve loads an external URI relative to the asset (buffers, images). */
    fun load(bytes: ByteArray, resolve: (String) -> ByteArray = { throw GltfException("External resource '$it' not available") }): GltfModel {
        return if (bytes.size >= 12 && readU32(bytes, 0) == 0x46546C67L) loadGlb(bytes, resolve)
        else parse(bytes.toString(Charsets.UTF_8), null, resolve)
    }

    private fun loadGlb(bytes: ByteArray, resolve: (String) -> ByteArray): GltfModel {
        val version = readU32(bytes, 4)
        if (version != 2L) throw GltfException("Unsupported GLB version $version")
        val length = readU32(bytes, 8)
        if (length > bytes.size) throw GltfException("Truncated GLB ($length > ${bytes.size})")
        var off = 12
        var jsonText: String? = null
        var bin: ByteArray? = null
        while (off + 8 <= length) {
            val chunkLen = readU32(bytes, off).toInt()
            val type = readU32(bytes, off + 4)
            val start = off + 8
            if (chunkLen < 0 || start + chunkLen > length) throw GltfException("Corrupt GLB chunk")
            when (type) {
                0x4E4F534AL -> jsonText = String(bytes, start, chunkLen, Charsets.UTF_8)
                0x004E4942L -> bin = bytes.copyOfRange(start, start + chunkLen)
            }
            off = start + ((chunkLen + 3) and 3.inv())
        }
        return parse(jsonText ?: throw GltfException("GLB without JSON chunk"), bin, resolve)
    }

    private fun parse(text: String, glbBin: ByteArray?, resolve: (String) -> ByteArray): GltfModel {
        val root = json.parseToJsonElement(text).jsonObject
        val warnings = mutableListOf<String>()
        val version = root["asset"]?.jsonObject?.get("version")?.jsonPrimitive?.contentOrNull
        if (version == null || !version.startsWith("2.")) throw GltfException("Not a glTF 2.0 asset (version=$version)")
        root["extensionsRequired"]?.jsonArray?.map { it.jsonPrimitive.content }?.forEach {
            if (it !in SUPPORTED_REQUIRED) throw GltfException("Required extension '$it' is not supported")
        }

        val buffers = root.arr("buffers").mapIndexed { i, b ->
            val uri = b.jsonObject["uri"]?.jsonPrimitive?.contentOrNull
            when {
                uri == null -> glbBin ?: throw GltfException("Buffer $i has no data")
                uri.startsWith("data:") -> decodeDataUri(uri)
                else -> resolve(decodeUri(uri))
            }
        }
        val views = root.arr("bufferViews").map { it.jsonObject }
        val accessors = root.arr("accessors").map { it.jsonObject }

        fun viewBytes(viewIndex: Int): Triple<ByteBuffer, Int, Int> {
            val v = views[viewIndex]
            val buf = buffers[v.int("buffer")]
            val off = v.intOr("byteOffset", 0)
            val len = v.int("byteLength")
            if (off + len > buf.size) throw GltfException("bufferView $viewIndex out of range")
            return Triple(ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN), off, v.intOr("byteStride", 0))
        }

        fun readAccessor(index: Int): Pair<FloatArray, Int> {
            val a = accessors[index]
            val count = a.int("count")
            val comps = when (a["type"]!!.jsonPrimitive.content) {
                "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; "MAT4" -> 16
                else -> throw GltfException("Unsupported accessor type ${a["type"]}")
            }
            val out = FloatArray(count * comps)
            if (a["sparse"] != null) warnings += "Sparse accessor $index ignored (base values used)"
            val viewIndex = a["bufferView"]?.jsonPrimitive?.intOrNull ?: return out to comps
            val (bb, viewOff, stride) = viewBytes(viewIndex)
            val ctype = a.int("componentType")
            val csize = when (ctype) { 5120, 5121 -> 1; 5122, 5123 -> 2; 5125, 5126 -> 4; else -> throw GltfException("componentType $ctype") }
            val normalized = a["normalized"]?.jsonPrimitive?.booleanOrNull == true
            val elemStride = if (stride > 0) stride else csize * comps
            val base = viewOff + a.intOr("byteOffset", 0)
            if (count > 0 && base + (count - 1) * elemStride + comps * csize > bb.capacity()) throw GltfException("Accessor $index out of range")
            for (i in 0 until count) {
                val p = base + i * elemStride
                for (c in 0 until comps) {
                    val q = p + c * csize
                    out[i * comps + c] = when (ctype) {
                        5126 -> bb.getFloat(q)
                        5125 -> (bb.getInt(q).toLong() and 0xffffffffL).toFloat()
                        5123 -> (bb.getShort(q).toInt() and 0xffff).let { if (normalized) it / 65535f else it.toFloat() }
                        5122 -> bb.getShort(q).toFloat().let { if (normalized) maxOf(it / 32767f, -1f) else it }
                        5121 -> (bb.get(q).toInt() and 0xff).let { if (normalized) it / 255f else it.toFloat() }
                        else -> bb.get(q).toFloat().let { if (normalized) maxOf(it / 127f, -1f) else it }
                    }
                }
            }
            return out to comps
        }

        fun readIndices(index: Int): IntArray {
            val a = accessors[index]
            val count = a.int("count")
            val viewIndex = a["bufferView"]!!.jsonPrimitive.int
            val (bb, viewOff, _) = viewBytes(viewIndex)
            val base = viewOff + a.intOr("byteOffset", 0)
            return when (val t = a.int("componentType")) {
                5125 -> IntArray(count) { bb.getInt(base + it * 4) }
                5123 -> IntArray(count) { bb.getShort(base + it * 2).toInt() and 0xffff }
                5121 -> IntArray(count) { bb.get(base + it).toInt() and 0xff }
                else -> throw GltfException("Invalid index componentType $t")
            }
        }

        val meshes = root.arr("meshes").mapIndexed { mi, m ->
            val mo = m.jsonObject
            val prims = mo.arr("primitives").mapNotNull { p ->
                val po = p.jsonObject
                val mode = po.intOr("mode", 4)
                if (mode != 4) { warnings += "Mesh $mi: primitive mode $mode skipped (triangles only)"; return@mapNotNull null }
                val attrs = po["attributes"]!!.jsonObject
                if (po["extensions"]?.jsonObject?.containsKey("KHR_draco_mesh_compression") == true) {
                    throw GltfException("Draco-compressed meshes are not supported")
                }
                val posIndex = attrs["POSITION"]?.jsonPrimitive?.int ?: return@mapNotNull null
                val positions = readAccessor(posIndex).first
                val vcount = positions.size / 3
                val indices = po["indices"]?.jsonPrimitive?.intOrNull?.let { readIndices(it) } ?: IntArray(vcount) { it }
                if (indices.any { it < 0 || it >= vcount }) throw GltfException("Mesh $mi has out-of-range indices")
                val normals = attrs["NORMAL"]?.jsonPrimitive?.intOrNull?.let { readAccessor(it).first } ?: computeNormals(positions, indices)
                val uvs = attrs["TEXCOORD_0"]?.jsonPrimitive?.intOrNull?.let { readAccessor(it).first }
                val colors = attrs["COLOR_0"]?.jsonPrimitive?.intOrNull?.let { idx ->
                    val (c, comps) = readAccessor(idx)
                    if (comps == 4) c else FloatArray(vcount * 4) { i -> if (i % 4 == 3) 1f else c[(i / 4) * 3 + i % 4] }
                }
                GltfPrimitive(positions, normals, uvs, colors, indices, po.intOr("material", -1))
            }
            GltfMesh(mo["name"]?.jsonPrimitive?.contentOrNull ?: "mesh$mi", prims)
        }

        val textures = root.arr("textures").map { it.jsonObject["source"]?.jsonPrimitive?.intOrNull }
        val images = root.arr("images").mapIndexed { i, img ->
            val io = img.jsonObject
            val uri = io["uri"]?.jsonPrimitive?.contentOrNull
            val mime = io["mimeType"]?.jsonPrimitive?.contentOrNull
            try {
                when {
                    uri != null && uri.startsWith("data:") -> GltfImage(decodeDataUri(uri), mime)
                    uri != null -> GltfImage(resolve(decodeUri(uri)), mime ?: guessMime(uri))
                    else -> {
                        val (bb, off, _) = viewBytes(io.int("bufferView"))
                        val len = views[io.int("bufferView")].int("byteLength")
                        GltfImage(bb.array().copyOfRange(off, off + len), mime)
                    }
                }
            } catch (e: Exception) {
                warnings += "Image $i unavailable: ${e.message}"
                GltfImage(ByteArray(0), mime)
            }
        }

        val materials = root.arr("materials").mapIndexed { i, m ->
            val mo = m.jsonObject
            val pbr = mo["pbrMetallicRoughness"]?.jsonObject
            val bc = pbr?.get("baseColorFactor")?.jsonArray?.map { it.jsonPrimitive.float }?.toFloatArray() ?: floatArrayOf(1f, 1f, 1f, 1f)
            val texIndex = pbr?.get("baseColorTexture")?.jsonObject?.get("index")?.jsonPrimitive?.intOrNull
            if (pbr?.get("baseColorTexture")?.jsonObject?.intOr("texCoord", 0) ?: 0 != 0) warnings += "Material $i uses texCoord>0 (TEXCOORD_0 used)"
            GltfMaterial(
                name = mo["name"]?.jsonPrimitive?.contentOrNull ?: "material$i",
                baseColor = bc,
                baseColorImage = texIndex?.let { textures.getOrNull(it) },
                emissive = mo["emissiveFactor"]?.jsonArray?.map { it.jsonPrimitive.float }?.toFloatArray() ?: floatArrayOf(0f, 0f, 0f),
                metallic = pbr?.get("metallicFactor")?.jsonPrimitive?.float ?: 1f,
                roughness = pbr?.get("roughnessFactor")?.jsonPrimitive?.float ?: 1f,
                alphaMode = mo["alphaMode"]?.jsonPrimitive?.contentOrNull ?: "OPAQUE",
                alphaCutoff = mo["alphaCutoff"]?.jsonPrimitive?.float ?: 0.5f,
                doubleSided = mo["doubleSided"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }

        // Node hierarchy -> world transforms
        val nodes = root.arr("nodes").map { it.jsonObject }
        val instances = mutableListOf<GltfInstance>()
        val bmin = Vec3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val bmax = Vec3(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        val tmp = Vec3(); val tmpOut = Vec3()

        fun visit(index: Int, parent: FloatArray, depth: Int) {
            if (depth > 64) throw GltfException("Node hierarchy too deep or cyclic")
            val n = nodes[index]
            val local = n["matrix"]?.jsonArray?.map { it.jsonPrimitive.float }?.toFloatArray() ?: run {
                val t = n["translation"]?.jsonArray?.map { it.jsonPrimitive.float } ?: listOf(0f, 0f, 0f)
                val r = n["rotation"]?.jsonArray?.map { it.jsonPrimitive.float } ?: listOf(0f, 0f, 0f, 1f)
                val s = n["scale"]?.jsonArray?.map { it.jsonPrimitive.float } ?: listOf(1f, 1f, 1f)
                Mat4.compose(Vec3(t[0], t[1], t[2]), Quat(r[0], r[1], r[2], r[3]), s[0], s[1], s[2], FloatArray(16))
            }
            val world = Mat4.multiply(parent, local, FloatArray(16))
            n["mesh"]?.jsonPrimitive?.intOrNull?.let { mi ->
                instances += GltfInstance(mi, world, n["name"]?.jsonPrimitive?.contentOrNull ?: "node$index")
                for (p in meshes[mi].primitives) {
                    var i = 0
                    while (i < p.positions.size) {
                        Mat4.transformPoint(world, tmp.set(p.positions[i], p.positions[i + 1], p.positions[i + 2]), tmpOut)
                        bmin.set(minOf(bmin.x, tmpOut.x), minOf(bmin.y, tmpOut.y), minOf(bmin.z, tmpOut.z))
                        bmax.set(maxOf(bmax.x, tmpOut.x), maxOf(bmax.y, tmpOut.y), maxOf(bmax.z, tmpOut.z))
                        i += 3
                    }
                }
            }
            n["children"]?.jsonArray?.forEach { visit(it.jsonPrimitive.int, world, depth + 1) }
        }

        val sceneIndex = root["scene"]?.jsonPrimitive?.intOrNull ?: 0
        val scenes = root.arr("scenes")
        val rootsList = if (scenes.isNotEmpty()) scenes[sceneIndex].jsonObject.arr("nodes").map { it.jsonPrimitive.int }
        else nodes.indices.filter { i -> nodes.none { n -> n["children"]?.jsonArray?.any { it.jsonPrimitive.int == i } == true } }
        val identity = Mat4.identity(FloatArray(16))
        rootsList.forEach { visit(it, identity, 0) }
        if (instances.isEmpty()) { bmin.set(0f, 0f, 0f); bmax.set(0f, 0f, 0f) }

        return GltfModel(meshes, materials, images, instances, bmin, bmax, warnings)
    }

    fun computeNormals(pos: FloatArray, idx: IntArray): FloatArray {
        val n = FloatArray(pos.size)
        var i = 0
        while (i + 2 < idx.size) {
            val a = idx[i] * 3; val b = idx[i + 1] * 3; val c = idx[i + 2] * 3
            val ux = pos[b] - pos[a]; val uy = pos[b + 1] - pos[a + 1]; val uz = pos[b + 2] - pos[a + 2]
            val vx = pos[c] - pos[a]; val vy = pos[c + 1] - pos[a + 1]; val vz = pos[c + 2] - pos[a + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (v in intArrayOf(a, b, c)) { n[v] += nx; n[v + 1] += ny; n[v + 2] += nz }
            i += 3
        }
        var j = 0
        while (j < n.size) {
            val l = kotlin.math.sqrt(n[j] * n[j] + n[j + 1] * n[j + 1] + n[j + 2] * n[j + 2])
            if (l > 1e-12f) { n[j] /= l; n[j + 1] /= l; n[j + 2] /= l } else n[j + 1] = 1f
            j += 3
        }
        return n
    }

    private fun JsonObject.arr(key: String): JsonArray = this[key]?.jsonArray ?: JsonArray(emptyList())
    private fun JsonObject.int(key: String): Int = this[key]?.jsonPrimitive?.int ?: throw GltfException("Missing '$key'")
    private fun JsonObject.intOr(key: String, def: Int): Int = this[key]?.jsonPrimitive?.intOrNull ?: def

    private fun readU32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xff) or ((b[off + 1].toLong() and 0xff) shl 8) or
            ((b[off + 2].toLong() and 0xff) shl 16) or ((b[off + 3].toLong() and 0xff) shl 24)

    private fun decodeDataUri(uri: String): ByteArray {
        val comma = uri.indexOf(',')
        if (comma < 0 || !uri.substring(0, comma).endsWith(";base64")) throw GltfException("Unsupported data URI")
        return Base64.getDecoder().decode(uri.substring(comma + 1))
    }

    private fun decodeUri(uri: String): String {
        val decoded = java.net.URLDecoder.decode(uri.replace("+", "%2B"), "UTF-8")
        if (decoded.startsWith("/") || decoded.split('/').any { it == ".." } || decoded.contains("://")) {
            throw GltfException("Refusing external URI '$uri'")
        }
        return decoded
    }

    private fun guessMime(uri: String) = when {
        uri.endsWith(".png", true) -> "image/png"
        uri.endsWith(".jpg", true) || uri.endsWith(".jpeg", true) -> "image/jpeg"
        uri.endsWith(".webp", true) -> "image/webp"
        else -> null
    }
}
