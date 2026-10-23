package com.hyperxr.handtracking

/**
 * Someone walking up while you are inside a virtual environment: from the segmenter's per-pixel
 * classes (background, hair, body skin, face skin, clothes, others) it keeps only the people whose
 * face or hair is in view - the region grown from those pixels through hair, skin and clothes.
 * The wearer never sees their own face, so their own torso and arms, looking down, stay hidden.
 * Needs the person in [confirmRuns] runs in a row before showing them, and keeps them for
 * [holdNs] after they are lost (someone turning around, a missed frame).
 */
class PersonBreakthrough(val size: Int) {
    var minSeedFraction = 0.002f
    var confirmRuns = 2
    var holdNs = 700_000_000L

    var present = false; private set
    private var streak = 0
    private var lastSeenNs = 0L
    private var last: ByteArray? = null
    private val region = ByteArray(size * size)
    private val queue = IntArray(size * size)

    /** [labels]: argmax class per pixel (size x size). Returns the mask (0..255) to show, or null. */
    fun update(labels: ByteArray, nowNs: Long): ByteArray? {
        val n = size
        java.util.Arrays.fill(region, 0)
        var head = 0; var tail = 0; var seeds = 0
        for (i in 0 until n * n) {
            val c = labels[i].toInt()
            if (c == FACE || c == HAIR) { region[i] = 1; queue[tail++] = i; seeds++ }
        }
        val found = seeds >= minSeedFraction * n * n
        if (found) {
            while (head < tail) {
                val i = queue[head++]
                val x = i % n; val y = i / n
                fun visit(j: Int) { if (region[j].toInt() == 0 && isPerson(labels[j].toInt())) { region[j] = 1; queue[tail++] = j } }
                if (x > 0) visit(i - 1); if (x < n - 1) visit(i + 1)
                if (y > 0) visit(i - n); if (y < n - 1) visit(i + n)
            }
            streak++
            lastSeenNs = nowNs
            if (streak >= confirmRuns) { present = true; last = soften(region) }
        } else {
            streak = 0
            if (present && nowNs - lastSeenNs > holdNs) { present = false; last = null }
        }
        return if (present) last else null
    }

    /** 0/1 region to 0..255 with a 3x3 box blur, for soft edges in the shader. */
    private fun soften(r: ByteArray): ByteArray {
        val n = size
        val out = ByteArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            var s = 0; var c = 0
            for (dy in -1..1) { val yy = y + dy; if (yy < 0 || yy >= n) continue
                for (dx in -1..1) { val xx = x + dx; if (xx < 0 || xx >= n) continue; s += r[yy * n + xx]; c++ } }
            out[y * n + x] = (s * 255 / c).toByte()
        }
        return out
    }

    private fun isPerson(c: Int) = c == HAIR || c == FACE || c == SKIN || c == CLOTHES || c == OTHERS

    companion object {
        const val BACKGROUND = 0; const val HAIR = 1; const val SKIN = 2; const val FACE = 3; const val CLOTHES = 4; const val OTHERS = 5
    }
}
