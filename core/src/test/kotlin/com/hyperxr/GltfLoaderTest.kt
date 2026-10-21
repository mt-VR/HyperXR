package com.hyperxr

import com.hyperxr.gltf.GltfException
import com.hyperxr.gltf.GltfLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class GltfLoaderTest {
    /** Builds a GLB with one triangle (ushort indices, no normals) inside a translated, scaled node. */
    private fun triangleGlb(extraJson: String = ""): ByteArray {
        val bin = ByteBuffer.allocate(36 + 8).order(ByteOrder.LITTLE_ENDIAN)
        floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f).forEach { bin.putFloat(it) }
        shortArrayOf(0, 1, 2).forEach { bin.putShort(it) }
        bin.putShort(0) // pad to 4
        val json = """
        {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
         "nodes":[{"mesh":0,"translation":[10,0,0],"scale":[2,2,2],"children":[1]},{"mesh":0,"translation":[0,5,0]}],
         "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1,"material":0}]}],
         "materials":[{"pbrMetallicRoughness":{"baseColorFactor":[1,0,0,1]}}],
         "buffers":[{"byteLength":44}],
         "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":6}],
         "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},
                      {"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR"}]$extraJson}
        """.trimIndent()
        var jsonBytes = json.toByteArray()
        while (jsonBytes.size % 4 != 0) jsonBytes += ' '.code.toByte()
        val out = ByteArrayOutputStream()
        val total = 12 + 8 + jsonBytes.size + 8 + bin.capacity()
        fun u32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        u32(0x46546C67); u32(2); u32(total)
        u32(jsonBytes.size); u32(0x4E4F534A); out.write(jsonBytes)
        u32(bin.capacity()); u32(0x004E4942); out.write(bin.array())
        return out.toByteArray()
    }

    @Test fun loadsGlbWithHierarchy() {
        val m = GltfLoader.load(triangleGlb())
        assertEquals(1, m.meshes.size)
        assertEquals(2, m.instances.size)
        val p = m.meshes[0].primitives[0]
        assertEquals(3, p.indices.size)
        // normals were generated (+Z for CCW triangle in XY plane)
        assertEquals(1f, p.normals[2], 1e-5f)
        assertEquals(1f, m.materials[0].baseColor[0], 0f)
        // child is translated by parent's scale: world y = 2*5 = 10, plus scaled vertex at y=2
        assertEquals(10f, m.boundsMin.x, 1e-5f)
        assertEquals(12f, m.boundsMax.y, 1e-5f)
        assertEquals(2, m.triangleCount)
    }

    @Test fun rejectsDraco() {
        val ex = assertThrows(GltfException::class.java) {
            GltfLoader.load(triangleGlb(",\"extensionsRequired\":[\"KHR_draco_mesh_compression\"]"))
        }
        assertTrue(ex.message!!.contains("draco", ignoreCase = true))
    }

    @Test fun loadsGltfWithDataUriAndExternalBuffer() {
        val buf = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN)
        floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f).forEach { buf.putFloat(it) }
        val b64 = Base64.getEncoder().encodeToString(buf.array())
        fun gltf(uri: String) = """{"asset":{"version":"2.0"},"nodes":[{"mesh":0}],
          "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
          "buffers":[{"byteLength":36,"uri":"$uri"}],"bufferViews":[{"buffer":0,"byteLength":36}],
          "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"}]}""".toByteArray()
        val embedded = GltfLoader.load(gltf("data:application/octet-stream;base64,$b64"))
        assertEquals(3, embedded.meshes[0].primitives[0].indices.size)
        val external = GltfLoader.load(gltf("scene%20data.bin")) { name ->
            assertEquals("scene data.bin", name); buf.array()
        }
        assertEquals(1f, external.boundsMax.z, 0f)
        assertThrows(GltfException::class.java) { GltfLoader.load(gltf("../../etc/passwd")) { ByteArray(0) } }
    }

    @Test fun rejectsTruncatedAndWrongVersion() {
        val glb = triangleGlb()
        assertThrows(GltfException::class.java) { GltfLoader.load(glb.copyOf(40)) }
        assertThrows(GltfException::class.java) { GltfLoader.load("""{"asset":{"version":"1.0"}}""".toByteArray()) }
    }
}
