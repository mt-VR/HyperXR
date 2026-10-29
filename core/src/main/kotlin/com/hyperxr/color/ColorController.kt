package com.hyperxr.color

import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A controller tracked by its coloured spots (tape, stickers, LEDs): the user marks them once in
 * the calibrator, then every camera frame finds them by colour (on the GPU) and solves where the
 * controller is and how it is turned.
 *
 * Model space is the camera's at calibration time, centred on the spots: x right, y up, z towards
 * the camera, metres. The spots face the camera then, so "forward" (away from you) is -z.
 */
@Serializable
data class ColorMarker(
    /** Colour: hue 0..360, saturation and value 0..1, and how far a pixel may be from it. */
    val h: Float, val s: Float, val v: Float,
    val hueTol: Float = 14f, val satMin: Float = 0.3f, val valMin: Float = 0.2f,
    /** Where the spot is on the controller (model space, metres) and its radius. */
    val x: Float, val y: Float, val z: Float = 0f, val radius: Float = 0.01f,
    /** Covering this spot (with a finger) is a click. */
    val button: Boolean = false,
)

@Serializable
data class ColorControllerModel(
    val markers: List<ColorMarker> = emptyList(),
    /** True: it points away from you (out of the back of the controller); false: towards [aimX], [aimY], [aimZ]. */
    val aimForward: Boolean = true,
    val aimX: Float = 0f, val aimY: Float = 0f, val aimZ: Float = -1f,
    /** Which hand's controller it becomes: 0 left, 1 right. */
    val side: Int = 1,
) {
    val usable: Boolean get() = markers.isNotEmpty()

    /** Unit aim direction in model space. */
    fun aim(out: Vec3): Vec3 = if (aimForward) out.set(0f, 0f, -1f) else out.set(aimX, aimY, aimZ).normalize()
}

/** RGB -> HSV (hue 0..360) and the colour test both the GPU shader and the CPU fallback use. */
object ColorMatch {
    fun hsv(r: Float, g: Float, b: Float, out: FloatArray) {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        var h = when {
            d < 1e-6f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        if (h < 0f) h += 360f
        out[0] = h; out[1] = if (mx < 1e-6f) 0f else d / mx; out[2] = mx
    }

    fun hueDistance(a: Float, b: Float): Float { val d = abs(a - b) % 360f; return if (d > 180f) 360f - d else d }

    /** Whether a pixel of this hsv is the marker's colour. Greys and whites go by brightness only. */
    fun matches(hsv: FloatArray, m: ColorMarker): Boolean {
        if (m.s < 0.22f) return hsv[1] < 0.28f && abs(hsv[2] - m.v) < 0.16f
        return hueDistance(hsv[0], m.h) <= m.hueTol && hsv[1] >= m.satMin && hsv[2] >= m.valMin
    }

    /** Which marker a pixel is (1-based; 0 = none): the closest of those it matches. The GPU shader does the same. */
    fun classify(hsv: FloatArray, markers: List<ColorMarker>): Int {
        var best = 0; var bestD = Float.MAX_VALUE
        for ((i, m) in markers.withIndex()) {
            if (!matches(hsv, m)) continue
            val d = if (m.s < 0.22f) abs(hsv[2] - m.v) * 100f else hueDistance(hsv[0], m.h)
            if (d < bestD) { bestD = d; best = i + 1 }
        }
        return best
    }

    /**
     * The marker's colour from a patch of pixels (ARGB) around a tap: circular mean hue, mean
     * saturation and value, and tolerances from how much they vary.
     */
    fun sample(argb: IntArray): ColorMarker {
        var sx = 0.0; var sy = 0.0; var ss = 0.0; var sv = 0.0
        val hsvs = Array(argb.size) { FloatArray(3) }
        for ((i, p) in argb.withIndex()) {
            hsv(((p shr 16) and 255) / 255f, ((p shr 8) and 255) / 255f, (p and 255) / 255f, hsvs[i])
            val a = Math.toRadians(hsvs[i][0].toDouble())
            sx += cos(a) * hsvs[i][1]; sy += sin(a) * hsvs[i][1]; ss += hsvs[i][1]; sv += hsvs[i][2]
        }
        val n = argb.size.coerceAtLeast(1)
        var h = Math.toDegrees(atan2(sy, sx)).toFloat(); if (h < 0f) h += 360f
        val s = (ss / n).toFloat(); val v = (sv / n).toFloat()
        var spread = 0f
        for (c in hsvs) spread += hueDistance(c[0], h) * hueDistance(c[0], h)
        val hueStd = sqrt(spread / n)
        return ColorMarker(h, s, v, hueTol = (2.5f * hueStd + 9f).coerceIn(9f, 28f),
            satMin = (s * 0.5f).coerceAtLeast(0.22f), valMin = (v * 0.4f).coerceAtLeast(0.12f), x = 0f, y = 0f)
    }
}

/** The largest blob of each class in a class map (0 = nothing, k = marker k-1). */
object BlobFinder {
    class Blob { var x = 0f; var y = 0f; var area = 0; var found = false }

    fun find(classes: ByteArray, w: Int, h: Int, count: Int, out: Array<Blob>, minArea: Int = 6) {
        for (b in out) { b.found = false; b.area = 0 }
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        for (start in 0 until w * h) {
            val k = classes[start].toInt()
            if (k <= 0 || k > count || seen[start]) continue
            var sp = 0; stack[sp++] = start; seen[start] = true
            var area = 0; var sx = 0L; var sy = 0L
            while (sp > 0) {
                val p = stack[--sp]
                val px = p % w; val py = p / w
                area++; sx += px; sy += py
                if (px > 0) { val q = p - 1; if (!seen[q] && classes[q].toInt() == k) { seen[q] = true; stack[sp++] = q } }
                if (px < w - 1) { val q = p + 1; if (!seen[q] && classes[q].toInt() == k) { seen[q] = true; stack[sp++] = q } }
                if (py > 0) { val q = p - w; if (!seen[q] && classes[q].toInt() == k) { seen[q] = true; stack[sp++] = q } }
                if (py < h - 1) { val q = p + w; if (!seen[q] && classes[q].toInt() == k) { seen[q] = true; stack[sp++] = q } }
            }
            val b = out[k - 1]
            if (area >= minArea && area > b.area) { b.area = area; b.x = (sx.toFloat() / area + 0.5f) / w; b.y = (sy.toFloat() / area + 0.5f) / h; b.found = true }
        }
    }
}

/**
 * Controller pose from the spots seen: points in normalised camera coordinates (u = (px - cx) / fx,
 * v = (py - cy) / fy, image y down) and apparent radii. Camera space: x right, y up, -z forward.
 * Three or more spots give the full pose (position and rotation); two give position and the
 * line between them; one gives the position from its apparent size.
 */
object ColorPoseSolver {
    class Obs(val marker: Int, val u: Float, val v: Float, val r: Float)
    class Result { val rotation = Quat(); val position = Vec3(); var error = 0f; var dof = 0 }

    private fun project(rot: Quat, t: Vec3, m: ColorMarker, tmp: Vec3, out: FloatArray): Boolean {
        rot.rotate(tmp.set(m.x, m.y, m.z), tmp).add(t)
        if (tmp.z > -0.02f) return false
        val d = -tmp.z
        out[0] = tmp.x / d; out[1] = -tmp.y / d; out[2] = m.radius / d
        return true
    }

    private fun cost(rot: Quat, t: Vec3, model: ColorControllerModel, obs: List<Obs>, tmp: Vec3, p: FloatArray): Float {
        var e = 0f
        for (o in obs) {
            if (!project(rot, t, model.markers[o.marker], tmp, p)) return Float.MAX_VALUE
            val du = p[0] - o.u; val dv = p[1] - o.v; val dr = (p[2] - o.r) * 0.5f
            e += du * du + dv * dv + dr * dr
        }
        return e
    }

    /** [prior]: last pose (camera space), to start from and to break the mirror ambiguity of flat spots. */
    fun solve(model: ColorControllerModel, obs: List<Obs>, priorRot: Quat?, priorPos: Vec3?, out: Result): Boolean {
        if (obs.isEmpty()) return false
        val ms = model.markers
        // start: as if facing the camera, from a 2D similarity between the spots and their images
        val init = Quat(); val t = Vec3()
        if (obs.size >= 2) {
            var mx = 0f; var my = 0f; var ux = 0f; var uy = 0f
            for (o in obs) { mx += ms[o.marker].x; my += ms[o.marker].y; ux += o.u; uy -= o.v }
            mx /= obs.size; my /= obs.size; ux /= obs.size; uy /= obs.size
            var sxx = 0f; var sxy = 0f; var mm = 0f
            for (o in obs) {
                val ax = ms[o.marker].x - mx; val ay = ms[o.marker].y - my
                val bx = o.u - ux; val by = -o.v - uy
                sxx += ax * bx + ay * by; sxy += ax * by - ay * bx; mm += ax * ax + ay * ay
            }
            val ang = atan2(sxy, sxx)
            val scale = sqrt(sxx * sxx + sxy * sxy) / mm.coerceAtLeast(1e-9f)
            val depth = (1f / scale.coerceAtLeast(1e-4f)).coerceIn(0.08f, 4f)
            Quat.fromAxisAngle(0f, 0f, 1f, ang, init)
            // where the model's centre lands: the image centroid minus the rotated model centroid offset
            val rcx = cos(ang) * mx - sin(ang) * my; val rcy = sin(ang) * mx + cos(ang) * my
            t.set(ux * depth - rcx, uy * depth - rcy, -depth)
        } else {
            val o = obs[0]; val m = ms[o.marker]
            val depth = (m.radius / o.r.coerceAtLeast(1e-4f)).coerceIn(0.08f, 4f)
            init.set(priorRot ?: Quat())
            t.set(o.u * depth, -o.v * depth, -depth)
            init.rotate(Vec3(m.x, m.y, m.z)).let { t.sub(it) }
        }
        val starts = ArrayList<Pair<Quat, Vec3>>()
        starts += init to t
        if (priorRot != null && priorPos != null) starts += priorRot.copy() to priorPos.copy()
        // a flat set of spots also fits mirrored: try the start tilted both ways
        if (obs.size >= 3) for (ax in listOf(Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f))) for (sgn in listOf(-1f, 1f)) {
            val q = Quat.fromAxisAngle(ax.x, ax.y, ax.z, 0.6f * sgn).mul(init, Quat())
            starts += q to t.copy()
        }
        var bestE = Float.MAX_VALUE
        val tmp = Vec3(); val p = FloatArray(3)
        for ((q0, t0) in starts) {
            val q = q0.copy(); val tt = t0.copy()
            val e = refine(model, obs, q, tt, tmp, p)
            // a small preference for the last pose keeps the mirrored solution from winning by noise
            val bias = if (priorRot != null) 2e-5f * (1f - abs(q.dot(priorRot))) else 0f
            if (e + bias < bestE) { bestE = e + bias; out.rotation.set(q); out.position.set(tt) }
        }
        out.error = sqrt(bestE / obs.size)
        out.dof = when { obs.size >= 3 -> 6; obs.size == 2 -> 5; else -> 3 }
        return bestE < Float.MAX_VALUE
    }

    /** Levenberg–Marquardt on rotation (small-angle steps) and translation. Returns the cost. */
    private fun refine(model: ColorControllerModel, obs: List<Obs>, q: Quat, t: Vec3, tmp: Vec3, p: FloatArray): Float {
        var lambda = 1e-3f
        var e = cost(q, t, model, obs, tmp, p)
        val dq = Quat(); val qn = Quat(); val tn = Vec3()
        val nRes = obs.size * 3
        val res = FloatArray(nRes); val jac = Array(nRes) { FloatArray(6) }; val resN = FloatArray(nRes)
        // rotation about the line through two spots can't be seen: only steer what the spots show
        val params = if (obs.size >= 3) 6 else if (obs.size == 2) 5 else 3
        for (iter in 0 until 30) {
            residuals(q, t, model, obs, tmp, p, res)
            val h = 1e-4f
            for (k in 0 until 6) {
                stepped(q, t, k, h, dq, qn, tn)
                residuals(qn, tn, model, obs, tmp, p, resN)
                for (i in 0 until nRes) jac[i][k] = (resN[i] - res[i]) / h
            }
            // normal equations on the parameters in use (translation first when few spots)
            val idx = when (params) { 6 -> intArrayOf(0, 1, 2, 3, 4, 5); 5 -> intArrayOf(0, 1, 3, 4, 5); else -> intArrayOf(3, 4, 5) }
            val n = idx.size
            val a = Array(n) { DoubleArray(n + 1) }
            for (r in 0 until n) {
                for (c in 0 until n) { var s = 0.0; for (i in 0 until nRes) s += jac[i][idx[r]] * jac[i][idx[c]]; a[r][c] = s }
                a[r][r] += lambda * (a[r][r] + 1e-6)
                var g = 0.0; for (i in 0 until nRes) g -= jac[i][idx[r]] * res[i]; a[r][n] = g
            }
            val step = gauss(a) ?: break
            val delta = FloatArray(6); for (r in 0 until n) delta[idx[r]] = step[r].toFloat()
            apply(q, t, delta, qn, tn)
            val en = cost(qn, tn, model, obs, tmp, p)
            if (en < e) {
                q.set(qn); t.set(tn); lambda = max(lambda * 0.3f, 1e-6f)
                val done = e - en < 1e-12f; e = en
                if (done) break
            } else { lambda *= 10f; if (lambda > 1e4f) break }
        }
        return e
    }

    private fun residuals(q: Quat, t: Vec3, model: ColorControllerModel, obs: List<Obs>, tmp: Vec3, p: FloatArray, out: FloatArray) {
        for ((i, o) in obs.withIndex()) {
            if (!project(q, t, model.markers[o.marker], tmp, p)) { out[i * 3] = 10f; out[i * 3 + 1] = 10f; out[i * 3 + 2] = 10f; continue }
            out[i * 3] = p[0] - o.u; out[i * 3 + 1] = p[1] - o.v; out[i * 3 + 2] = (p[2] - o.r) * 0.5f
        }
    }

    private fun stepped(q: Quat, t: Vec3, k: Int, h: Float, dq: Quat, qn: Quat, tn: Vec3) {
        val d = FloatArray(6); d[k] = h
        apply(q, t, d, qn, tn)
    }

    private fun apply(q: Quat, t: Vec3, d: FloatArray, qn: Quat, tn: Vec3) {
        val ang = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
        if (ang > 1e-9f) Quat.fromAxisAngle(d[0] / ang, d[1] / ang, d[2] / ang, ang).mul(q, qn) else qn.set(q)
        qn.normalize()
        tn.set(t.x + d[3], t.y + d[4], t.z + d[5])
    }

    private fun gauss(a: Array<DoubleArray>): DoubleArray? {
        val n = a.size
        for (c in 0 until n) {
            var piv = c
            for (r in c + 1 until n) if (abs(a[r][c]) > abs(a[piv][c])) piv = r
            if (abs(a[piv][c]) < 1e-18) return null
            val tmp = a[c]; a[c] = a[piv]; a[piv] = tmp
            for (r in 0 until n) if (r != c) {
                val f = a[r][c] / a[c][c]
                for (k in c..n) a[r][k] -= f * a[c][k]
            }
        }
        return DoubleArray(n) { a[it][n] / a[it][it] }
    }
}

/**
 * Building the model in the calibrator: the spots tapped on one picture of the controller facing
 * the camera, with the real distance between the first two.
 */
object ColorCalibration {
    class Tap(val marker: ColorMarker, val u: Float, val v: Float, val r: Float)

    /** [taps] in normalised camera coordinates; [aim] the tapped pointer point (or null: forward). */
    fun build(taps: List<Tap>, firstTwoMeters: Float, aim: FloatArray?, side: Int): ColorControllerModel? {
        if (taps.isEmpty()) return null
        val cu = taps.map { it.u }.average().toFloat(); val cv = taps.map { it.v }.average().toFloat()
        val depth = if (taps.size >= 2) {
            val du = taps[1].u - taps[0].u; val dv = taps[1].v - taps[0].v
            firstTwoMeters / sqrt(du * du + dv * dv).coerceAtLeast(1e-4f)
        } else (0.02f / taps[0].r.coerceAtLeast(1e-4f))   // one spot: assume it is 2 cm across
        val markers = taps.map { t -> t.marker.copy(x = (t.u - cu) * depth, y = -(t.v - cv) * depth, z = 0f, radius = t.r * depth) }
        var ax = 0f; var ay = 0f; var az = -1f; var forward = true
        if (aim != null) {
            ax = (aim[0] - cu) * depth; ay = -(aim[1] - cv) * depth; az = 0f
            val l = sqrt(ax * ax + ay * ay)
            if (l > 1e-4f) { ax /= l; ay /= l; forward = false } else { ax = 0f; ay = 0f; az = -1f }
        }
        return ColorControllerModel(markers, forward, ax, ay, az, side)
    }
}
