package com.hyperxr.gltf

import com.hyperxr.math.Vec3

/**
 * The pieces of a glTF model that can be picked up on their own: one per node with a mesh. Each
 * lists the drawable parts it is made of, in the order the renderer builds them (instances, then
 * their primitives), and its bounds in model space.
 */
class ModelPiece(val name: String, val parts: IntArray, val min: Vec3, val max: Vec3) {
    val center: Vec3 get() = Vec3((min.x + max.x) / 2, (min.y + max.y) / 2, (min.z + max.z) / 2)
    val radius: Float get() = 0.5f * min.distanceTo(max)

    /** Distance from [p] (model space) to the box, 0 inside. */
    fun distanceTo(p: Vec3): Float {
        val dx = maxOf(min.x - p.x, 0f, p.x - max.x)
        val dy = maxOf(min.y - p.y, 0f, p.y - max.y)
        val dz = maxOf(min.z - p.z, 0f, p.z - max.z)
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }
}

object ModelPieces {
    fun of(model: GltfModel): List<ModelPiece> {
        val out = ArrayList<ModelPiece>()
        var part = 0
        val p = Vec3(); val w = Vec3()
        for (inst in model.instances) {
            val prims = model.meshes[inst.mesh].primitives
            val ids = IntArray(prims.size) { part + it }
            part += prims.size
            val mn = Vec3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            val mx = Vec3(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            val m = inst.world
            for (prim in prims) {
                val pos = prim.positions
                var k = 0
                while (k + 2 < pos.size) {
                    p.set(pos[k], pos[k + 1], pos[k + 2]); k += 3
                    w.set(m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12], m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13], m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14])
                    mn.set(minOf(mn.x, w.x), minOf(mn.y, w.y), minOf(mn.z, w.z))
                    mx.set(maxOf(mx.x, w.x), maxOf(mx.y, w.y), maxOf(mx.z, w.z))
                }
            }
            if (mn.x > mx.x) { mn.set(0f, 0f, 0f); mx.set(0f, 0f, 0f) }
            out += ModelPiece(inst.nodeName, ids, mn, mx)
        }
        return out
    }

    /** Scale that makes the model's largest side [size] metres (1 for an empty model). */
    fun fitScale(model: GltfModel, size: Float): Float {
        val d = maxOf(model.boundsMax.x - model.boundsMin.x, model.boundsMax.y - model.boundsMin.y, model.boundsMax.z - model.boundsMin.z)
        return if (d > 1e-6f) size / d else 1f
    }

    /** Where each piece goes when the model is spread out: away from the model's centre by [amount] of its distance. */
    fun explodeOffset(piece: ModelPiece, model: GltfModel, amount: Float, out: Vec3): Vec3 {
        val c = piece.center
        val mx = (model.boundsMin.x + model.boundsMax.x) / 2; val my = (model.boundsMin.y + model.boundsMax.y) / 2; val mz = (model.boundsMin.z + model.boundsMax.z) / 2
        return out.set((c.x - mx) * amount, (c.y - my) * amount, (c.z - mz) * amount)
    }
}
