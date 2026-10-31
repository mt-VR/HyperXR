package com.hyperxr.questgames

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The drawing of the Ateliê: strokes of points in drawing space (metres), each with its colour,
 * thickness and brush. The drawing as a whole can be moved and turned (the game keeps its pose);
 * the strokes stay in their own space. Saved as JSON.
 */
@Serializable
data class SketchStroke(val points: FloatArray, val color: Int, val radius: Float, val brush: SketchBrush) {
    override fun equals(other: Any?) = other is SketchStroke && points.contentEquals(other.points) && color == other.color && radius == other.radius && brush == other.brush
    override fun hashCode() = points.contentHashCode() * 31 + color
}

@Serializable
enum class SketchBrush(val label: String) { TUBO("Tubo"), NEON("Neon"), FITA("Fita") }

@Serializable
data class SketchFile(val strokes: List<SketchStroke> = emptyList())

class Sketch3D {
    val strokes = ArrayList<SketchStroke>()
    private val undone = ArrayList<SketchStroke>()

    fun add(s: SketchStroke) { if (s.points.size >= 6) { strokes += s; undone.clear() } }
    fun undo(): SketchStroke? = strokes.removeLastOrNull()?.also { undone += it }
    fun redo(): SketchStroke? = undone.removeLastOrNull()?.also { strokes += it }
    fun clear() { undone.addAll(strokes.asReversed()); strokes.clear() }

    /** The stroke closest to (x, y, z) within [reach] metres of one of its points, or -1. */
    fun hit(x: Float, y: Float, z: Float, reach: Float): Int {
        var best = -1; var bestD = Float.MAX_VALUE
        for ((i, s) in strokes.withIndex()) {
            val p = s.points
            var k = 0
            val r = reach + s.radius
            while (k + 2 < p.size) {
                val dx = p[k] - x; val dy = p[k + 1] - y; val dz = p[k + 2] - z
                val d = dx * dx + dy * dy + dz * dz
                if (d < r * r && d < bestD) { bestD = d; best = i }
                k += 3
            }
        }
        return best
    }

    fun erase(i: Int): SketchStroke? = if (i in strokes.indices) strokes.removeAt(i).also { undone += it } else null

    fun toJson(): String = json.encodeToString(SketchFile.serializer(), SketchFile(strokes.toList()))
    fun load(text: String) { strokes.clear(); undone.clear(); strokes += json.decodeFromString(SketchFile.serializer(), text).strokes }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        /** The palette (ARGB). */
        val COLORS = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFF202020.toInt(), 0xFFE53935.toInt(), 0xFFFF8F00.toInt(), 0xFFFFEB3B.toInt(), 0xFF7CB342.toInt(),
            0xFF00ACC1.toInt(), 0xFF1E88E5.toInt(), 0xFF5E35B1.toInt(), 0xFFD81B60.toInt(), 0xFF8D6E63.toInt(), 0xFF26C6DA.toInt(),
        )
        val SIZES = floatArrayOf(0.002f, 0.004f, 0.008f, 0.015f)

        fun rgba(c: Int, out: FloatArray = FloatArray(4)): FloatArray {
            out[0] = ((c shr 16) and 255) / 255f; out[1] = ((c shr 8) and 255) / 255f; out[2] = (c and 255) / 255f; out[3] = 1f
            return out
        }
    }
}
