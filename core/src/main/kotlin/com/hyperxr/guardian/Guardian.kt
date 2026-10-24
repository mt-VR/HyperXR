package com.hyperxr.guardian

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The play area drawn by hand (Manual mode): a closed outline on the floor, in tracking-space
 * metres (x, z). The trace is thinned as it is drawn, loops made by crossing the line are cut
 * off, and the outline is simplified so a shaky hand still gives clean walls.
 */
class GuardianArea {
    /** Outline vertices: x0, z0, x1, z1, ... (closed: the last joins the first). */
    var points = FloatArray(0); private set
    val size get() = points.size / 2
    val valid get() = size >= 3 && area() >= MIN_AREA

    private val trace = ArrayList<Float>()
    val tracing get() = trace.size / 2

    fun beginTrace() { trace.clear() }

    /** Adds a traced floor point; returns true when it was far enough from the last one to count. */
    fun addTracePoint(x: Float, z: Float): Boolean {
        val n = trace.size
        if (n >= 2 && hypot(x - trace[n - 2], z - trace[n - 1]) < STEP) return false
        trace += x; trace += z
        return true
    }

    fun tracePoints(): FloatArray = trace.toFloatArray()

    /** True when the trace has come back near where it started (the loop can be closed). */
    fun traceCanClose(): Boolean {
        if (trace.size < 16) return false
        val n = trace.size
        return hypot(trace[n - 2] - trace[0], trace[n - 1] - trace[1]) < CLOSE_RADIUS && traceLength() > 2.5f
    }

    private fun traceLength(): Float {
        var l = 0f
        for (i in 2 until trace.size step 2) l += hypot(trace[i] - trace[i - 2], trace[i + 1] - trace[i - 1])
        return l
    }

    /** Turns the trace into the outline; false (and the old outline kept) when it is too small. */
    fun finishTrace(): Boolean {
        val loop = removeLoops(trace.toFloatArray())
        val simple = simplify(loop, 0.05f)
        val old = points
        points = simple
        if (!valid) { points = old; return false }
        return true
    }

    fun set(p: FloatArray) { points = p.copyOf() }
    fun clear() { points = FloatArray(0) }

    /** Area in m² (shoelace). */
    fun area(): Float {
        var a = 0f
        val n = size
        for (i in 0 until n) {
            val j = (i + 1) % n
            a += points[i * 2] * points[j * 2 + 1] - points[j * 2] * points[i * 2 + 1]
        }
        return abs(a) / 2
    }

    fun contains(x: Float, z: Float): Boolean {
        var inside = false
        val n = size
        var j = n - 1
        for (i in 0 until n) {
            val xi = points[i * 2]; val zi = points[i * 2 + 1]; val xj = points[j * 2]; val zj = points[j * 2 + 1]
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Distance to the outline: positive inside the area, negative outside. NaN without an area. */
    fun signedDistance(x: Float, z: Float, nearest: FloatArray? = null): Float {
        if (size < 3) return Float.NaN
        var best = Float.MAX_VALUE
        val n = size
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ax = points[i * 2]; val az = points[i * 2 + 1]; val bx = points[j * 2]; val bz = points[j * 2 + 1]
            val dx = bx - ax; val dz = bz - az
            val len2 = dx * dx + dz * dz
            val t = if (len2 < 1e-9f) 0f else (((x - ax) * dx + (z - az) * dz) / len2).coerceIn(0f, 1f)
            val px = ax + dx * t; val pz = az + dz * t
            val d = hypot(x - px, z - pz)
            if (d < best) { best = d; nearest?.let { it[0] = px; it[1] = pz } }
        }
        return if (contains(x, z)) best else -best
    }

    companion object {
        const val STEP = 0.08f
        const val CLOSE_RADIUS = 0.5f
        /** Smaller than this is not a play area (a scribble). */
        const val MIN_AREA = 1.0f

        /**
         * Where the line crosses itself, the part between the two crossings is a loop drawn by
         * mistake (or the overshoot at the end): it is cut out, keeping the largest closed shape.
         */
        fun removeLoops(p: FloatArray): FloatArray {
            val pts = ArrayList<Float>()
            for (v in p) pts += v
            var changed = true
            var guard = 0
            while (changed && guard++ < 200) {
                changed = false
                val n = pts.size / 2
                loop@ for (i in 0 until n - 1) for (j in i + 2 until n - 1) {
                    if (i == 0 && j == n - 2) continue
                    val hit = intersect(pts[i * 2], pts[i * 2 + 1], pts[i * 2 + 2], pts[i * 2 + 3], pts[j * 2], pts[j * 2 + 1], pts[j * 2 + 2], pts[j * 2 + 3]) ?: continue
                    // the loop is i+1..j; the overshoot is what lies outside it (0..i and j+1..end)
                    val inner = j - i
                    val outer = n - inner
                    val keep = ArrayList<Float>()
                    if (inner >= outer) {
                        keep += hit[0]; keep += hit[1]
                        for (k in i + 1..j) { keep += pts[k * 2]; keep += pts[k * 2 + 1] }
                    } else {
                        for (k in 0..i) { keep += pts[k * 2]; keep += pts[k * 2 + 1] }
                        keep += hit[0]; keep += hit[1]
                        for (k in j + 1 until n) { keep += pts[k * 2]; keep += pts[k * 2 + 1] }
                    }
                    pts.clear(); pts += keep
                    changed = true
                    break@loop
                }
            }
            return pts.toFloatArray()
        }

        private fun intersect(ax: Float, az: Float, bx: Float, bz: Float, cx: Float, cz: Float, dx: Float, dz: Float): FloatArray? {
            val r1 = bx - ax; val r2 = bz - az; val s1 = dx - cx; val s2 = dz - cz
            val den = r1 * s2 - r2 * s1
            if (abs(den) < 1e-9f) return null
            val t = ((cx - ax) * s2 - (cz - az) * s1) / den
            val u = ((cx - ax) * r2 - (cz - az) * r1) / den
            if (t <= 0f || t >= 1f || u <= 0f || u >= 1f) return null
            return floatArrayOf(ax + r1 * t, az + r2 * t)
        }

        /** Douglas-Peucker on a closed outline (tolerance [eps] metres). */
        fun simplify(p: FloatArray, eps: Float): FloatArray {
            val n = p.size / 2
            if (n < 4) return p.copyOf()
            val keep = BooleanArray(n)
            // split at the point farthest from the first, then simplify both halves
            var far = 0; var fd = -1f
            for (i in 1 until n) { val d = hypot(p[i * 2] - p[0], p[i * 2 + 1] - p[1]); if (d > fd) { fd = d; far = i } }
            keep[0] = true; keep[far] = true
            fun dp(a: Int, b: Int) {
                if (b <= a + 1) return
                val ax = p[a * 2]; val az = p[a * 2 + 1]; val bx = p[(b % n) * 2]; val bz = p[(b % n) * 2 + 1]
                var best = -1f; var at = -1
                for (k in a + 1 until b) {
                    val d = segDist(p[(k % n) * 2], p[(k % n) * 2 + 1], ax, az, bx, bz)
                    if (d > best) { best = d; at = k }
                }
                if (best > eps) { keep[at % n] = true; dp(a, at); dp(at, b) }
            }
            dp(0, far); dp(far, n)
            val out = ArrayList<Float>()
            for (i in 0 until n) if (keep[i]) { out += p[i * 2]; out += p[i * 2 + 1] }
            return out.toFloatArray()
        }

        private fun segDist(x: Float, z: Float, ax: Float, az: Float, bx: Float, bz: Float): Float {
            val dx = bx - ax; val dz = bz - az
            val len2 = dx * dx + dz * dz
            val t = if (len2 < 1e-9f) 0f else (((x - ax) * dx + (z - az) * dz) / len2).coerceIn(0f, 1f)
            return hypot(x - (ax + dx * t), z - (az + dz * t))
        }
    }
}

/**
 * What the depth sensor has seen around you, from above (Auto mode): a grid of 5 cm cells, each
 * holding how sure we are something stands there and how tall it is.
 *
 * Protections against false walls:
 *  - your own body: points within 45 cm of your head (horizontally) and near your tracked hands
 *    are ignored, so your arms and legs are never obstacles;
 *  - the floor and what hangs from the ceiling: only points from 12 cm above the floor to 30 cm
 *    above your head count;
 *  - noise and things that moved (a pet, a door): a cell needs several hits from separate scans
 *    before it counts, and every depth ray that passes through a cell to something behind it
 *    proves that cell empty and wears its evidence down;
 *  - beds, sofas, tables: anything lower than 1.1 m is "low furniture", not a wall. It warns only
 *    much closer (you walk into it with your legs, you do not lean over it), never pulls you out
 *    to the camera on its own, and is ignored entirely under and right around you, so lying
 *    next to or standing beside a bed does not keep the warning on.
 */
class ObstacleMap(val cell: Float = 0.05f) {
    enum class Kind { WALL, LOW }

    class Cell {
        var evidence = 0f
        var top = -1e9f
        var scans = 0
        var lastScan = -1
    }

    val cells = HashMap<Long, Cell>()
    private var scan = 0

    private fun key(ix: Int, iz: Int) = (ix.toLong() shl 32) or (iz.toLong() and 0xffffffffL)
    private fun ix(x: Float) = floor(x / cell).toInt()
    fun cellX(k: Long) = ((k shr 32).toInt() + 0.5f) * cell
    fun cellZ(k: Long) = (k.toInt() + 0.5f) * cell

    fun reset() { cells.clear(); scan = 0 }

    /**
     * Adds one depth scan: [n] points (x, y, z) seen from the camera at ([camX], [camZ]).
     * [hands] are joint positions (x, y, z each) to ignore around; [headX]/[headZ] is where you
     * stand; [floorY] the floor and [headY] the eyes.
     */
    fun integrate(pts: FloatArray, n: Int, camX: Float, camZ: Float, headX: Float, headY: Float, headZ: Float, floorY: Float, hands: FloatArray? = null, handCount: Int = 0) {
        scan++
        for (i in 0 until n) {
            val x = pts[i * 3]; val y = pts[i * 3 + 1]; val z = pts[i * 3 + 2]
            if (y < floorY + FLOOR_MARGIN || y > headY + 0.3f) {
                // a floor hit still proves the way to it is free
                if (y < floorY + FLOOR_MARGIN) carve(camX, headY, camZ, x, y, z, floorY)
                continue
            }
            if (hypot(x - headX, z - headZ) < BODY_RADIUS) continue
            var nearHand = false
            if (hands != null) for (h in 0 until handCount) {
                val dx = x - hands[h * 3]; val dy = y - hands[h * 3 + 1]; val dz = z - hands[h * 3 + 2]
                if (dx * dx + dy * dy + dz * dz < HAND_RADIUS * HAND_RADIUS) { nearHand = true; break }
            }
            if (nearHand) continue
            carve(camX, headY, camZ, x, y, z, floorY)
            val c = cells.getOrPut(key(ix(x), ix(z))) { Cell() }
            c.evidence = min(c.evidence + 1f, MAX_EVIDENCE)
            c.top = max(c.top, y - floorY)
            if (c.lastScan != scan) { c.lastScan = scan; c.scans++ }
        }
    }

    /**
     * Cells a depth ray passed through (stopping 15 cm short of what it hit) are seen empty, but
     * only where the ray went lower than the obstacle's top: a ray passing over a bed to the wall
     * behind it says nothing about the bed.
     */
    private fun carve(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, floorY: Float) {
        val dx = x1 - x0; val dz = z1 - z0
        val len = hypot(dx, dz)
        if (len < 0.2f) return
        val steps = ((len - 0.15f) / cell).toInt()
        for (s in 0 until steps) {
            val t = s * cell / len
            val k = key(ix(x0 + dx * t), ix(z0 + dz * t))
            val c = cells[k] ?: continue
            val rayHeight = y0 + (y1 - y0) * t - floorY
            if (rayHeight > c.top) continue
            c.evidence -= FREE_DECAY
            if (c.evidence <= 0f) cells.remove(k)
        }
    }

    fun confirmed(c: Cell) = c.evidence >= MIN_EVIDENCE && c.scans >= MIN_SCANS
    fun kind(c: Cell) = if (c.top >= WALL_HEIGHT) Kind.WALL else Kind.LOW

    /**
     * Distances from ([x], [z]) to the nearest confirmed wall and low obstacle (NaN when none
     * within [radius]). Low furniture right under and around you (inside [LOW_IGNORE]) is not
     * counted: you are on or beside it already.
     */
    fun nearest(x: Float, z: Float, radius: Float = 2.5f, out: FloatArray = FloatArray(6)): FloatArray {
        var wall = Float.NaN; var low = Float.NaN
        val r = (radius / cell).toInt() + 1
        val cx = ix(x); val cz = ix(z)
        // scan the cells around rather than all cells: the map can be large
        for (dx in -r..r) for (dz in -r..r) {
            val c = cells[key(cx + dx, cz + dz)] ?: continue
            if (!confirmed(c)) continue
            val px = (cx + dx + 0.5f) * cell; val pz = (cz + dz + 0.5f) * cell
            val d = hypot(px - x, pz - z)
            if (d > radius) continue
            if (kind(c) == Kind.WALL) {
                if (wall.isNaN() || d < wall) { wall = d; out[2] = px; out[3] = pz }
            } else if (d > LOW_IGNORE) {
                if (low.isNaN() || d < low) { low = d; out[4] = px; out[5] = pz }
            }
        }
        out[0] = wall; out[1] = low
        return out
    }

    companion object {
        const val FLOOR_MARGIN = 0.12f
        const val BODY_RADIUS = 0.45f
        const val HAND_RADIUS = 0.14f
        const val WALL_HEIGHT = 1.1f
        const val LOW_IGNORE = 0.5f
        const val MIN_EVIDENCE = 4f
        const val MIN_SCANS = 2
        const val MAX_EVIDENCE = 30f
        const val FREE_DECAY = 0.6f
    }
}

/**
 * How strongly to warn, from distances: the grid fades in from [warn] metres, the camera from
 * [pass] metres. Walls use the full distances; low furniture half of them and never brings the
 * camera on its own beyond [lowPass]. Smoothed with a fast rise (safety) and slow fall (no
 * flicker).
 */
class GuardianWarning(var warn: Float = 0.6f, var pass: Float = 0.25f) {
    var grid = 0f; private set
    var camera = 0f; private set
    private val lowPass = 0.12f

    private fun ramp(d: Float, from: Float, to: Float): Float {
        if (d.isNaN()) return 0f
        val t = ((from - d) / (from - to)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    /** [wallM]/[lowM] distances to obstacles (NaN = none); [outsideM] how far outside the area (>0 outside). */
    fun update(dt: Float, wallM: Float, lowM: Float, outsideM: Float) {
        val area = if (outsideM.isNaN()) 0f else ramp(-outsideM, warn, 0f)
        val g = max(max(ramp(wallM, warn, 0f), ramp(lowM, warn * 0.5f, 0f)), area)
        // outside the area: the camera fully, at once
        val outside = if (!outsideM.isNaN() && outsideM > 0f) 1f else 0f
        val c = max(max(ramp(wallM, pass, 0.05f), ramp(lowM, lowPass, 0.02f) * 0.6f), max(outside, if (outsideM.isNaN()) 0f else ramp(-outsideM, pass, 0f)))
        grid += (g - grid) * ((if (g > grid) 12f else 2f) * dt).coerceAtMost(1f)
        camera += (c - camera) * ((if (c > camera) 14f else 2.5f) * dt).coerceAtMost(1f)
    }
}

/** Where a pointing ray from the hand meets the floor (x, z), or null when it does not go down. */
object FloorRay {
    fun hit(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, floorY: Float, maxDist: Float = 6f): FloatArray? {
        if (dy > -0.05f) return null
        val t = (floorY - oy) / dy
        if (t <= 0f || t > maxDist) return null
        val l = sqrt(dx * dx + dy * dy + dz * dz)
        if (t * l > maxDist) return null
        return floatArrayOf(ox + dx * t, oz + dz * t)
    }
}
