package com.hyperxr.socialvr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * VRM avatars come in a T-pose (arms straight out). The game draws them as still meshes, so the
 * arms are lowered once, when the file is loaded: every vertex skinned to an upper arm (or below
 * it) turns about that shoulder by the weight it has there, around the body's front axis. The GLB
 * is returned with its vertex data edited in place; anything it cannot read comes back unchanged.
 */
object VrmPose {
    private val json = Json { ignoreUnknownKeys = true }

    /** Lowers both arms by [angle] radians (about 70 degrees: a relaxed A-pose). */
    fun relaxArms(glb: ByteArray, angle: Float = 1.22f): ByteArray = runCatching { pose(glb, angle) }.getOrNull() ?: glb

    private fun u32(b: ByteArray, o: Int) = ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun pose(glb: ByteArray, angle: Float): ByteArray? {
        if (glb.size < 20 || u32(glb, 0) != 0x46546C67) return null
        val jsonLen = u32(glb, 12)
        val root = json.parseToJsonElement(String(glb, 20, jsonLen, Charsets.UTF_8)).jsonObject
        val binHeader = 20 + jsonLen
        if (glb.size < binHeader + 8 || u32(glb, binHeader + 4) != 0x004E4942) return null
        val binStart = binHeader + 8
        val out = glb.copyOf()
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

        val nodes = root["nodes"]?.jsonArray?.map { it.jsonObject } ?: return null
        val bones = humanBones(root) ?: return null
        val leftArm = bones["leftUpperArm"] ?: return null
        val rightArm = bones["rightUpperArm"] ?: return null
        val parent = IntArray(nodes.size) { -1 }
        nodes.forEachIndexed { i, n -> n["children"]?.jsonArray?.forEach { parent[it.jsonPrimitive.int] = i } }
        val world = Array(nodes.size) { FloatArray(16) }
        val done = BooleanArray(nodes.size)
        fun worldOf(i: Int): FloatArray {
            if (done[i]) return world[i]
            val local = localMatrix(nodes[i])
            if (parent[i] >= 0) mul(worldOf(parent[i]), local, world[i]) else local.copyInto(world[i])
            done[i] = true
            return world[i]
        }
        fun under(i: Int, top: Int): Boolean { var k = i; while (k >= 0) { if (k == top) return true; k = parent[k] }; return false }
        val pivots = arrayOf(worldOf(leftArm).let { floatArrayOf(it[12], it[13], it[14]) }, worldOf(rightArm).let { floatArrayOf(it[12], it[13], it[14]) })
        val tops = intArrayOf(leftArm, rightArm)

        val accessors = root["accessors"]?.jsonArray?.map { it.jsonObject } ?: return null
        val views = root["bufferViews"]?.jsonArray?.map { it.jsonObject } ?: return null
        val skins = root["skins"]?.jsonArray?.map { it.jsonObject } ?: return null
        val meshes = root["meshes"]?.jsonArray?.map { it.jsonObject } ?: return null
        val edited = HashSet<Int>()
        for (n in nodes) {
            val mesh = n["mesh"]?.jsonPrimitive?.intOrNull ?: continue
            val skin = n["skin"]?.jsonPrimitive?.intOrNull ?: continue
            val joints = skins[skin]["joints"]!!.jsonArray.map { it.jsonPrimitive.int }
            // for each skin joint: 0 = left arm chain, 1 = right arm chain, -1 = elsewhere
            val side = IntArray(joints.size) { j -> when { under(joints[j], tops[0]) -> 0; under(joints[j], tops[1]) -> 1; else -> -1 } }
            for (prim in meshes[mesh]["primitives"]!!.jsonArray) {
                val attrs = prim.jsonObject["attributes"]!!.jsonObject
                val pos = attrs["POSITION"]?.jsonPrimitive?.int ?: continue
                val jo = attrs["JOINTS_0"]?.jsonPrimitive?.int ?: continue
                val we = attrs["WEIGHTS_0"]?.jsonPrimitive?.int ?: continue
                if (!edited.add(pos)) continue
                val nor = attrs["NORMAL"]?.jsonPrimitive?.int
                val count = accessors[pos]["count"]!!.jsonPrimitive.int
                val pA = Acc(accessors[pos], views, binStart); val jA = Acc(accessors[jo], views, binStart); val wA = Acc(accessors[we], views, binStart)
                val nA = nor?.let { Acc(accessors[it], views, binStart) }
                val v = FloatArray(3); val r = FloatArray(3)
                for (k in 0 until count) {
                    // weight of each arm at this vertex
                    var wl = 0f; var wr = 0f
                    for (c in 0..3) {
                        val w = wA.read(bb, k, c)
                        val s = side.getOrElse(jA.read(bb, k, c).toInt()) { -1 }
                        if (s == 0) wl += w else if (s == 1) wr += w
                    }
                    val w = maxOf(wl, wr); if (w <= 0f) continue
                    val arm = if (wl >= wr) 0 else 1
                    val p = pivots[arm]
                    // the arm on the +x side turns down by -angle about z, the other by +angle
                    val a = (if (p[0] >= 0f) -angle else angle) * w
                    val c = cos(a); val s = sin(a)
                    for (q in 0..2) v[q] = pA.read(bb, k, q)
                    val x = v[0] - p[0]; val y = v[1] - p[1]
                    r[0] = p[0] + x * c - y * s; r[1] = p[1] + x * s + y * c; r[2] = v[2]
                    for (q in 0..2) pA.write(bb, k, q, r[q])
                    if (nA != null) {
                        val nx = nA.read(bb, k, 0); val ny = nA.read(bb, k, 1)
                        nA.write(bb, k, 0, nx * c - ny * s); nA.write(bb, k, 1, nx * s + ny * c)
                    }
                }
            }
        }
        return if (edited.isEmpty()) null else out
    }

    /** Human bone name -> node, from VRM 0.x (extensions.VRM) or VRM 1.0 (VRMC_vrm). */
    private fun humanBones(root: JsonObject): Map<String, Int>? {
        val ext = root["extensions"]?.jsonObject ?: return null
        ext["VRM"]?.jsonObject?.get("humanoid")?.jsonObject?.get("humanBones")?.let { list ->
            return (list as JsonArray).associate { b -> b.jsonObject["bone"]!!.jsonPrimitive.content to b.jsonObject["node"]!!.jsonPrimitive.int }
        }
        ext["VRMC_vrm"]?.jsonObject?.get("humanoid")?.jsonObject?.get("humanBones")?.jsonObject?.let { map ->
            return map.mapValues { it.value.jsonObject["node"]!!.jsonPrimitive.int }
        }
        return null
    }

    /** One accessor's elements in the BIN chunk (floats, or unsigned bytes / shorts, normalized or not). */
    private class Acc(a: JsonObject, views: List<JsonObject>, binStart: Int) {
        val type = a["componentType"]!!.jsonPrimitive.int
        val normalized = a["normalized"]?.jsonPrimitive?.content == "true"
        val size = when (type) { 5126 -> 4; 5123 -> 2; 5121 -> 1; else -> error("type $type") }
        val comps = when (a["type"]!!.jsonPrimitive.content) { "VEC3" -> 3; "VEC4" -> 4; "VEC2" -> 2; else -> 1 }
        val view = views[a["bufferView"]!!.jsonPrimitive.int]
        val stride = view["byteStride"]?.jsonPrimitive?.int ?: (size * comps)
        val base = binStart + (view["byteOffset"]?.jsonPrimitive?.int ?: 0) + (a["byteOffset"]?.jsonPrimitive?.int ?: 0)
        fun at(k: Int, c: Int) = base + k * stride + c * size
        fun read(b: ByteBuffer, k: Int, c: Int): Float = when (type) {
            5126 -> b.getFloat(at(k, c))
            5123 -> (b.getShort(at(k, c)).toInt() and 0xFFFF).let { if (normalized) it / 65535f else it.toFloat() }
            else -> (b.get(at(k, c)).toInt() and 0xFF).let { if (normalized) it / 255f else it.toFloat() }
        }
        fun write(b: ByteBuffer, k: Int, c: Int, v: Float) { if (type == 5126) b.putFloat(at(k, c), v) }
    }

    /** Column-major 4x4 of a node (its matrix, or translation * rotation * scale). */
    private fun localMatrix(n: JsonObject): FloatArray {
        n["matrix"]?.jsonArray?.let { m -> return FloatArray(16) { m[it].jsonPrimitive.float } }
        val t = n["translation"]?.jsonArray?.map { it.jsonPrimitive.float } ?: listOf(0f, 0f, 0f)
        val q = n["rotation"]?.jsonArray?.map { it.jsonPrimitive.float } ?: listOf(0f, 0f, 0f, 1f)
        val s = n["scale"]?.jsonArray?.map { it.jsonPrimitive.float } ?: listOf(1f, 1f, 1f)
        val (x, y, z, w) = q
        val len = sqrt(x * x + y * y + z * z + w * w).takeIf { it > 0f } ?: 1f
        val qx = x / len; val qy = y / len; val qz = z / len; val qw = w / len
        return floatArrayOf(
            (1 - 2 * (qy * qy + qz * qz)) * s[0], 2 * (qx * qy + qz * qw) * s[0], 2 * (qx * qz - qy * qw) * s[0], 0f,
            2 * (qx * qy - qz * qw) * s[1], (1 - 2 * (qx * qx + qz * qz)) * s[1], 2 * (qy * qz + qx * qw) * s[1], 0f,
            2 * (qx * qz + qy * qw) * s[2], 2 * (qy * qz - qx * qw) * s[2], (1 - 2 * (qx * qx + qy * qy)) * s[2], 0f,
            t[0], t[1], t[2], 1f,
        )
    }

    private fun mul(a: FloatArray, b: FloatArray, out: FloatArray) {
        val r = FloatArray(16)
        for (c in 0..3) for (row in 0..3) { var v = 0f; for (k in 0..3) v += a[k * 4 + row] * b[c * 4 + k]; r[c * 4 + row] = v }
        r.copyInto(out)
    }
}
