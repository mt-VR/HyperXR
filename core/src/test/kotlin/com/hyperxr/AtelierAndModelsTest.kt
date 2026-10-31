package com.hyperxr

import com.hyperxr.gltf.GltfLoader
import com.hyperxr.gltf.ModelPieces
import com.hyperxr.input.GrabDrag
import com.hyperxr.input.HandGrip
import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import com.hyperxr.questgames.Sketch3D
import com.hyperxr.questgames.SketchBrush
import com.hyperxr.questgames.SketchStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AtelierAndModelsTest {
    @Test fun handPoseIsAProperRotation() {
        val p = HandGrip.handPose(Vec3(0f, 0f, 0f), Vec3(0.03f, 0f, -0.09f), Vec3(-0.03f, 0f, -0.08f), Vec3(0f, 0f, -0.09f), Pose())
        val fwd = p.orientation.rotate(Vec3(0f, 0f, -1f))
        assertEquals(-1f, fwd.z, 1e-3f)                  // fingers point along -z
        assertEquals(1f, p.orientation.dot(p.orientation), 1e-4f)
        assertEquals(-0.045f, p.position.z, 1e-5f)
    }

    @Test fun grabbedObjectFollowsTheHandRigidly() {
        val hand = Pose(Vec3(0f, 1f, 0f), Quat())
        val obj = Pose(Vec3(0.1f, 1f, -0.2f), Quat.fromAxisAngle(0f, 1f, 0f, 0.3f))
        val g = GrabDrag(); g.begin(hand, obj)
        // the hand moves up and turns 90 degrees about y
        val moved = Pose(Vec3(0f, 1.5f, 0f), Quat.fromAxisAngle(0f, 1f, 0f, (Math.PI / 2).toFloat()))
        val out = g.update(moved, Pose())
        // the object's offset (0.1, 0, -0.2) turned 90 degrees about y becomes (-0.2, 0, -0.1)
        assertEquals(-0.2f, out.position.x, 1e-4f)
        assertEquals(1.5f, out.position.y, 1e-4f)
        assertEquals(-0.1f, out.position.z, 1e-4f)
        // and it turned with the hand
        val expect = Quat.fromAxisAngle(0f, 1f, 0f, (Math.PI / 2).toFloat() + 0.3f)
        assertTrue(kotlin.math.abs(out.orientation.dot(expect)) > 0.9999f)
        // not moving the hand leaves it where it was
        val same = g.update(hand, Pose())
        assertEquals(obj.position.x, same.position.x, 1e-5f); assertEquals(obj.position.z, same.position.z, 1e-5f)
    }

    private fun twoNodeGlb(): ByteArray {
        val bin = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f).forEach { bin.putFloat(it) }
        shortArrayOf(0, 1, 2, 0).forEach { bin.putShort(it) }
        val json = """{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0,1]}],
         "nodes":[{"mesh":0,"name":"base"},{"mesh":0,"name":"top","translation":[0,2,0]}],
         "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}],
         "buffers":[{"byteLength":44}],
         "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":6}],
         "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},{"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR"}]}"""
        var jb = json.toByteArray(); while (jb.size % 4 != 0) jb += ' '.code.toByte()
        val out = ByteArrayOutputStream()
        fun u32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        u32(0x46546C67); u32(2); u32(12 + 8 + jb.size + 8 + 44)
        u32(jb.size); u32(0x4E4F534A); out.write(jb)
        u32(44); u32(0x004E4942); out.write(bin.array())
        return out.toByteArray()
    }

    @Test fun piecesAreTheNodes() {
        val m = GltfLoader.load(twoNodeGlb())
        val pieces = ModelPieces.of(m)
        assertEquals(listOf("base", "top"), pieces.map { it.name })
        assertEquals(listOf(0, 1), pieces.flatMap { it.parts.toList() })
        assertEquals(2f, pieces[1].min.y, 1e-5f)
        assertEquals(3f, pieces[1].max.y, 1e-5f)
        assertEquals(0f, pieces[1].distanceTo(Vec3(0.2f, 2.2f, 0f)), 1e-5f)
        assertEquals(1f, pieces[0].distanceTo(Vec3(0f, 2f, 0f)), 1e-5f)
        // the model is 3 m tall: half a metre needs a sixth
        assertEquals(0.5f / 3f, ModelPieces.fitScale(m, 0.5f), 1e-5f)
        val off = ModelPieces.explodeOffset(pieces[1], m, 1f, Vec3())
        assertEquals(1f, off.y, 1e-5f)
    }

    @Test fun sketchUndoEraseAndSave() {
        val s = Sketch3D()
        s.add(SketchStroke(floatArrayOf(0f, 0f, 0f), 0, 0.004f, SketchBrush.TUBO))   // a single point: ignored
        assertEquals(0, s.strokes.size)
        s.add(SketchStroke(floatArrayOf(0f, 1f, 0f, 0.1f, 1f, 0f), Sketch3D.COLORS[2], 0.004f, SketchBrush.NEON))
        s.add(SketchStroke(floatArrayOf(0f, 2f, 0f, 0.1f, 2f, 0f), Sketch3D.COLORS[3], 0.008f, SketchBrush.TUBO))
        assertEquals(1, s.hit(0.1f, 2.01f, 0f, 0.02f))
        assertEquals(-1, s.hit(5f, 5f, 5f, 0.02f))
        val back = Sketch3D().apply { load(s.toJson()) }
        assertEquals(s.strokes, back.strokes)
        s.erase(1); assertEquals(1, s.strokes.size)
        s.undo(); assertEquals(0, s.strokes.size)
        s.redo(); assertEquals(1, s.strokes.size)
        s.clear(); assertEquals(0, s.strokes.size)
        assertEquals(1f, Sketch3D.rgba(Sketch3D.COLORS[2])[0], 0.12f)
    }
}
