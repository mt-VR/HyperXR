package com.hyperxr

import com.hyperxr.gltf.GltfLoader
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every model the games load must parse, with its colormap found next to it. */
class GameAssetsTest {
    @Test fun allGameModelsLoad() {
        val root = listOf(File("../app/src/main/assets/games"), File("app/src/main/assets/games")).first { it.isDirectory }
        val files = root.walkTopDown().filter { it.extension == "glb" }.toList()
        assertTrue(files.size >= 70)
        for (f in files) {
            val m = GltfLoader.load(f.readBytes()) { uri -> File(f.parentFile, uri).readBytes() }
            assertTrue("${f.name}: no geometry", m.triangleCount > 0)
            assertTrue("${f.name}: ${m.warnings}", m.warnings.none { "required" in it.lowercase() })
            // every kit with a texture has its licence next to it
            assertTrue(File(f.parentFile, "License.txt").exists())
        }
    }
}
