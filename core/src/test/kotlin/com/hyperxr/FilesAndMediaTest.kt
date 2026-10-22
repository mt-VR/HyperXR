package com.hyperxr

import com.hyperxr.files.FileKind
import com.hyperxr.files.FileKinds
import com.hyperxr.files.FileOps
import com.hyperxr.files.FileSort
import com.hyperxr.media.Spherical
import com.hyperxr.media.StereoLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class FilesAndMediaTest {
    private fun tmp(): File = Files.createTempDirectory("hxfiles").toFile()

    @Test fun kinds() {
        assertEquals(FileKind.TEXT, FileKinds.kindOf("Notas.TXT"))
        assertEquals(FileKind.IMAGE, FileKinds.kindOf("a.jpeg"))
        assertEquals(FileKind.VIDEO, FileKinds.kindOf("clip.mp4"))
        assertEquals(FileKind.AUDIO, FileKinds.kindOf("x.opus"))
        assertEquals(FileKind.APK, FileKinds.kindOf("game.apk"))
        assertEquals(FileKind.OBB, FileKinds.kindOf("main.1.com.x.obb"))
        assertEquals(FileKind.FOLDER, FileKinds.kindOf("dir.txt", isDirectory = true))
        assertTrue(FileKinds.looksLikeText("olá mundo\n".toByteArray()))
        assertFalse(FileKinds.looksLikeText(byteArrayOf(0x50, 0x4b, 0, 3)))
    }

    @Test fun uniqueNamesAndRename() {
        val d = tmp()
        File(d, "foto.jpg").writeText("a")
        assertEquals("foto (2).jpg", FileOps.uniqueName(d, "foto.jpg"))
        File(d, "foto (2).jpg").writeText("b")
        assertEquals("foto (3).jpg", FileOps.uniqueName(d, "foto.jpg"))
        assertEquals("pasta", FileOps.uniqueName(d, "pasta"))
        assertFalse(FileOps.isValidName("a/b")); assertFalse(FileOps.isValidName("..")); assertTrue(FileOps.isValidName("ok.txt"))
        val r = FileOps.rename(File(d, "foto.jpg"), "praia.jpg")
        assertEquals("a", r.readText())
        try { FileOps.rename(r, "foto (2).jpg"); error("should fail") } catch (_: IOException) {}
    }

    @Test fun copyMoveAndSelfCopyGuard() {
        val d = tmp()
        val src = File(d, "src").apply { mkdirs() }
        File(src, "a.txt").writeText("1")
        File(src, "sub").mkdirs(); File(src, "sub/b.txt").writeText("22")
        val dst = File(d, "dst").apply { mkdirs() }
        val c = FileOps.copyInto(src, dst)
        assertEquals("22", File(c, "sub/b.txt").readText())
        assertEquals(3L, FileOps.totalSize(c))
        val again = FileOps.copyInto(src, dst)
        assertEquals("src (2)", again.name)
        try { FileOps.copyInto(src, File(src, "sub")); error("should fail") } catch (_: IOException) {}
        val m = FileOps.moveInto(File(src, "a.txt"), dst)
        assertFalse(File(src, "a.txt").exists()); assertEquals("1", m.readText())
    }

    @Test fun sortFoldersFirst() {
        val d = tmp()
        File(d, "b.txt").writeText("12345"); File(d, "a.txt").writeText("1"); File(d, "z").mkdirs()
        val names = FileOps.sorted(d.listFiles()!!.toList(), FileSort.NAME).map { it.name }
        assertEquals(listOf("z", "a.txt", "b.txt"), names)
        assertEquals(listOf("z", "b.txt", "a.txt"), FileOps.sorted(d.listFiles()!!.toList(), FileSort.SIZE, descending = true).map { it.name })
    }

    @Test fun photoSphereXmp() {
        val xmp = "<x:xmpmeta><rdf:Description GPano:UsePanoramaViewer=\"True\" GPano:ProjectionType=\"equirectangular\"/></x:xmpmeta>"
        val i = Spherical.detectImage(("ÿØjunk" + xmp).toByteArray(Charsets.ISO_8859_1), 4000, 3000)
        assertTrue(i.spherical); assertTrue(i.fromMetadata)
        val elem = "<GPano:ProjectionType>equirectangular</GPano:ProjectionType>".toByteArray()
        assertTrue(Spherical.detectImage(elem, 100, 100).fromMetadata)
        // no metadata: 2:1 is "probably 360", 4:3 is flat
        val p = Spherical.detectImage(ByteArray(10), 6080, 3040)
        assertTrue(p.spherical); assertFalse(p.fromMetadata)
        assertFalse(Spherical.detectImage(ByteArray(10), 4000, 3000).spherical)
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        return byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) + type.toByteArray() + payload
    }

    @Test fun sphericalVideoV2() {
        val st3d = box("st3d", byteArrayOf(0, 0, 0, 0, 1)) // top-bottom
        val sv3d = box("sv3d", box("proj", box("equi", ByteArray(20))))
        val moov = box("moov", box("trak", st3d + sv3d))
        val v = Spherical.detectVideo(listOf(ByteArray(100) + moov), 3840, 3840)
        assertTrue(v.spherical); assertTrue(v.fromMetadata)
        assertEquals(StereoLayout.TOP_BOTTOM, v.stereo); assertEquals("equirectangular", v.projection)
        // st3d alone (3D movie, not 360) stays flat
        assertFalse(Spherical.detectVideo(listOf(box("moov", st3d)), 3840, 1080).spherical)
        // the letters "sv3d" inside media data without a plausible box size are ignored
        assertFalse(Spherical.detectVideo(listOf(byteArrayOf(0x7f, 0x7f, 0x7f, 0x7f) + "sv3d".toByteArray()), 1920, 1080).spherical)
    }

    @Test fun sphericalVideoV1() {
        val xml = "<rdf:SphericalVideo><GSpherical:Spherical>true</GSpherical:Spherical><GSpherical:ProjectionType>equirectangular</GSpherical:ProjectionType><GSpherical:StereoMode>left-right</GSpherical:StereoMode></rdf:SphericalVideo>"
        val v = Spherical.detectVideo(listOf(xml.toByteArray()), 3840, 1080)
        assertTrue(v.spherical); assertEquals(StereoLayout.LEFT_RIGHT, v.stereo)
        assertTrue(Spherical.detectVideo(emptyList(), 3840, 1920).spherical)
    }
}
