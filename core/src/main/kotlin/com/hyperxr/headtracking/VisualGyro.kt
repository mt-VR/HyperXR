package com.hyperxr.headtracking

import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/** 8-bit grayscale image (row-major). */
class GrayImage(val w: Int, val h: Int, val px: ByteArray = ByteArray(w * h)) {
    operator fun get(x: Int, y: Int): Int = px[y * w + x].toInt() and 0xff

    /** Half size, 2x2 box filter. */
    fun half(out: GrayImage? = null): GrayImage {
        val o = out?.takeIf { it.w == w / 2 && it.h == h / 2 } ?: GrayImage(w / 2, h / 2)
        for (y in 0 until o.h) for (x in 0 until o.w) {
            val s = this[2 * x, 2 * y] + this[2 * x + 1, 2 * y] + this[2 * x, 2 * y + 1] + this[2 * x + 1, 2 * y + 1]
            o.px[y * o.w + x] = (s shr 2).toByte()
        }
        return o
    }

    fun copy() = GrayImage(w, h, px.copyOf())
}

/**
 * The computational gyroscope: head rotation from the camera alone, for phones without a
 * gyroscope (ARCore needs one, so those phones also have no 6DoF). A small rotational SLAM:
 *  - frame to frame: corners on a grid are followed by patch matching on a 2-level pyramid,
 *    starting from where the last angular velocity says they went; their bearing vectors give
 *    the rotation (2-point RANSAC, then Horn's closed form on the inliers), so hands and people
 *    moving in front of the camera are outliers, not rotation;
 *  - map: views are kept as keyframes with their orientation; when the head looks again where a
 *    keyframe was taken, matching against it gives an absolute orientation and the accumulated
 *    drift is pulled back (loop closure, as when looking around and coming back);
 *  - gravity (accelerometer, see [applyGravity]) keeps pitch and roll level.
 * Pure rotation is assumed: head translation is small compared with the distance of the room.
 *
 * Frames: the camera (x right, y down, z forward) and the head (x right, y up, -z forward) differ
 * by a half turn about x; with the image turned 180° (ROTATION_270) by a half turn about y.
 */
class VisualGyro(
    private val fx: Float, private val fy: Float, private val cx: Float, private val cy: Float,
    private val flipped180: Boolean = false,
) {
    /** Head orientation in the world (y up). */
    val orientation = Quat()
    /** Head-frame angular velocity (rad/s), for prediction. */
    val angularVelocity = Vec3()
    @Volatile var inliers = 0; private set
    @Volatile var keyframeCount = 0; private set
    @Volatile var lastCorrectionDeg = 0f; private set
    @Volatile var tracking = false; private set

    private val camToHead = if (flipped180) Quat.fromAxisAngle(0f, 1f, 0f, Math.PI.toFloat()) else Quat.fromAxisAngle(1f, 0f, 0f, Math.PI.toFloat())

    private var prev: Pyramid? = null
    private var prevFeatures = FloatArray(0)
    private var prevCount = 0
    private var prevTimeNs = 0L
    /** Last frame-to-frame bearing rotation (b2 = M b1), the motion guess for the next frame. */
    private val lastM = Quat()

    private class Pyramid(val l0: GrayImage, val l1: GrayImage)
    private class Keyframe(val pyr: Pyramid, val features: FloatArray, val count: Int, val head: Quat)
    private val keyframes = ArrayList<Keyframe>()

    fun reset(start: Quat = Quat()) {
        orientation.set(start); prev = null; keyframes.clear(); keyframeCount = 0; lastM.identity(); angularVelocity.set(0f, 0f, 0f)
    }

    /**
     * One camera frame (grayscale, at the resolution the intrinsics refer to). Returns true when
     * the rotation was measured; false when the view has too little texture (dark, a blank wall).
     */
    fun process(img: GrayImage, timeNs: Long): Boolean {
        val pyr = Pyramid(img.copy(), img.half())
        val p = prev
        var ok = false
        if (p != null && prevCount >= MIN_MATCHES) {
            val m = Quat()
            val n = estimate(p, prevFeatures, prevCount, pyr, lastM, m)
            inliers = n
            if (n >= MIN_MATCHES) {
                ok = true
                // b2 = M b1 for fixed points  =>  the camera turned by M⁻¹ (body frame)
                val dCam = m.conjugate(Quat())
                val dHead = toHead(dCam)
                orientation.mul(dHead, orientation).normalize()
                val dt = ((timeNs - prevTimeNs) / 1e9f).coerceIn(1e-3f, 0.2f)
                val ang = 2f * acos(abs(dHead.w).coerceAtMost(1f))
                val s = sqrt(1f - dHead.w * dHead.w).coerceAtLeast(1e-6f)
                val sign = if (dHead.w < 0f) -1f else 1f
                angularVelocity.set(dHead.x / s * ang / dt * sign, dHead.y / s * ang / dt * sign, dHead.z / s * ang / dt * sign)
                lastM.set(m)
            } else {
                lastM.identity(); angularVelocity.set(0f, 0f, 0f)
            }
        }
        tracking = ok
        relocalize(pyr)
        // new features for the next frame
        prevFeatures = detect(pyr.l0)
        prevCount = prevFeatures.size / 2
        prev = pyr
        prevTimeNs = timeNs
        maybeAddKeyframe(pyr)
        return ok
    }

    /**
     * Pulls pitch and roll toward the measured "up" (accelerometer, in the head frame, any
     * length). [alpha] is the fraction corrected per call; only when the phone is not accelerating.
     */
    fun applyGravity(upHead: Vec3, alpha: Float) {
        val len = upHead.length()
        if (len < 1e-3f) return
        val measured = Vec3(upHead.x / len, upHead.y / len, upHead.z / len)
        // world up seen from the head according to the current orientation
        val predicted = orientation.conjugate(Quat()).rotate(Vec3(0f, 1f, 0f))
        // q' = q · C with C⁻¹ taking the predicted up onto the measured one
        val corr = Quat.fromTo(measured, predicted)
        val small = Quat().slerp(corr, alpha, Quat())
        orientation.mul(small, orientation).normalize()
    }

    private fun toHead(dCam: Quat): Quat {
        val inv = camToHead.conjugate(Quat())
        return camToHead.mul(dCam, Quat()).mul(inv, Quat())
    }

    // ------------------------------------------------------------------ features
    /** Best corner (Shi-Tomasi score on a 5x5 window) per grid cell, (x, y) pairs. */
    private fun detect(img: GrayImage): FloatArray {
        val out = ArrayList<Float>(GRID_X * GRID_Y * 2)
        val cw = img.w / GRID_X; val ch = img.h / GRID_Y
        for (gy in 0 until GRID_Y) for (gx in 0 until GRID_X) {
            var best = 0f; var bx = -1; var by = -1
            val x0 = maxOf(gx * cw, MARGIN); val x1 = minOf((gx + 1) * cw, img.w - MARGIN)
            val y0 = maxOf(gy * ch, MARGIN); val y1 = minOf((gy + 1) * ch, img.h - MARGIN)
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val s = cornerScore(img, x, y)
                    if (s > best) { best = s; bx = x; by = y }
                    x += 2
                }
                y += 2
            }
            if (best > MIN_CORNER && bx >= 0) { out += bx.toFloat(); out += by.toFloat() }
        }
        return out.toFloatArray()
    }

    private fun cornerScore(img: GrayImage, x: Int, y: Int): Float {
        var sxx = 0f; var syy = 0f; var sxy = 0f
        for (dy in -2..2) for (dx in -2..2) {
            val gx = (img[x + dx + 1, y + dy] - img[x + dx - 1, y + dy]).toFloat()
            val gy = (img[x + dx, y + dy + 1] - img[x + dx, y + dy - 1]).toFloat()
            sxx += gx * gx; syy += gy * gy; sxy += gx * gy
        }
        // smaller eigenvalue of the structure tensor: high only where both directions have edges
        val tr = sxx + syy; val det = sxx * syy - sxy * sxy
        return (tr / 2 - sqrt(maxOf(0f, tr * tr / 4 - det))) / 25f
    }

    /** SSD of 7x7 patches, a at (ax, ay) and b at (bx, by); large when off the image. */
    private fun ssd(a: GrayImage, ax: Int, ay: Int, b: GrayImage, bx: Int, by: Int): Int {
        if (bx < 3 || by < 3 || bx >= b.w - 3 || by >= b.h - 3 || ax < 3 || ay < 3 || ax >= a.w - 3 || ay >= a.h - 3) return Int.MAX_VALUE
        var s = 0
        // mean-removed: robust to the camera's exposure changing between frames
        var ma = 0; var mb = 0
        for (dy in -3..3) for (dx in -3..3) { ma += a[ax + dx, ay + dy]; mb += b[bx + dx, by + dy] }
        val off = (ma - mb) / 49
        for (dy in -3..3) for (dx in -3..3) { val d = a[ax + dx, ay + dy] - b[bx + dx, by + dy] - off; s += d * d }
        return s
    }

    /** Coarse-to-fine match of a feature: returns (x, y) in [to] or null. */
    private fun track(from: Pyramid, fxp: Float, fyp: Float, to: Pyramid, gx: Float, gy: Float, out: FloatArray): Boolean {
        // level 1 (half size): wide search around the guess
        val ax1 = (fxp / 2).toInt(); val ay1 = (fyp / 2).toInt()
        var bestS = Int.MAX_VALUE; var bx = 0; var by = 0
        val cx1 = (gx / 2).toInt(); val cy1 = (gy / 2).toInt()
        for (dy in -R1..R1) for (dx in -R1..R1) {
            val s = ssd(from.l1, ax1, ay1, to.l1, cx1 + dx, cy1 + dy)
            if (s < bestS) { bestS = s; bx = cx1 + dx; by = cy1 + dy }
        }
        if (bestS == Int.MAX_VALUE) return false
        // level 0: refine, then sub-pixel by a parabola through the neighbours
        val ax0 = fxp.toInt(); val ay0 = fyp.toInt()
        bestS = Int.MAX_VALUE
        var rx = 0; var ry = 0
        for (dy in -2..2) for (dx in -2..2) {
            val s = ssd(from.l0, ax0, ay0, to.l0, bx * 2 + dx, by * 2 + dy)
            if (s < bestS) { bestS = s; rx = bx * 2 + dx; ry = by * 2 + dy }
        }
        if (bestS == Int.MAX_VALUE || bestS > MAX_SSD) return false
        fun sub(m: Int, c: Int, p: Int): Float { val d = (m - 2 * c + p).toFloat(); return if (d <= 0f) 0f else ((m - p) / (2 * d)).coerceIn(-0.5f, 0.5f) }
        val sl = ssd(from.l0, ax0, ay0, to.l0, rx - 1, ry); val sr = ssd(from.l0, ax0, ay0, to.l0, rx + 1, ry)
        val su = ssd(from.l0, ax0, ay0, to.l0, rx, ry - 1); val sd = ssd(from.l0, ax0, ay0, to.l0, rx, ry + 1)
        out[0] = rx + (if (sl != Int.MAX_VALUE && sr != Int.MAX_VALUE) sub(sl, bestS, sr) else 0f) + (fxp - ax0)
        out[1] = ry + (if (su != Int.MAX_VALUE && sd != Int.MAX_VALUE) sub(su, bestS, sd) else 0f) + (fyp - ay0)
        return true
    }

    private fun bearing(x: Float, y: Float, out: Vec3): Vec3 = out.set((x - cx) / fx, (y - cy) / fy, 1f).normalize()

    private fun project(b: Vec3, out: FloatArray) {
        out[0] = cx + fx * b.x / b.z; out[1] = cy + fy * b.y / b.z
    }

    /**
     * Matches [features] of [from] into [to] starting from the motion [guess] (b2 = guess b1),
     * then finds the rotation. Returns the inlier count and the rotation in [out].
     */
    private fun estimate(from: Pyramid, features: FloatArray, count: Int, to: Pyramid, guess: Quat, out: Quat): Int {
        val a = ArrayList<Vec3>(count); val b = ArrayList<Vec3>(count)
        val g = FloatArray(2); val m = FloatArray(2)
        for (i in 0 until count) {
            val x = features[i * 2]; val y = features[i * 2 + 1]
            val b1 = bearing(x, y, Vec3())
            val p = guess.rotate(b1)
            if (p.z < 0.2f) continue
            project(p, g)
            if (!track(from, x, y, to, g[0], g[1], m)) continue
            a += b1; b += bearing(m[0], m[1], Vec3())
        }
        if (a.size < MIN_MATCHES) return 0
        return ransac(a, b, out)
    }

    /** 2-point RANSAC on bearing pairs, then Horn's method on the inliers. */
    private fun ransac(a: List<Vec3>, b: List<Vec3>, out: Quat): Int {
        val n = a.size
        var bestInl = 0
        val best = Quat()
        val q = Quat()
        val rnd = java.util.Random(n.toLong() * 7919)
        val cosT = kotlin.math.cos(INLIER_RAD)
        repeat(RANSAC_ITERS) {
            val i = rnd.nextInt(n); var j = rnd.nextInt(n)
            if (j == i) j = (j + 1) % n
            horn(listOf(a[i], a[j]), listOf(b[i], b[j]), q)
            var inl = 0
            for (k in 0 until n) if (q.rotate(a[k]).dot(b[k]) > cosT) inl++
            if (inl > bestInl) { bestInl = inl; best.set(q) }
        }
        if (bestInl < MIN_MATCHES) return bestInl
        val ia = ArrayList<Vec3>(); val ib = ArrayList<Vec3>()
        for (k in 0 until n) if (best.rotate(a[k]).dot(b[k]) > cosT) { ia += a[k]; ib += b[k] }
        horn(ia, ib, out)
        return ia.size
    }

    /**
     * Horn's closed-form rotation (unit quaternion) minimising Σ|b - R a|²: the eigenvector of the
     * largest eigenvalue of a 4x4 symmetric matrix, by shifted power iteration.
     */
    fun horn(a: List<Vec3>, b: List<Vec3>, out: Quat) {
        var sxx = 0f; var sxy = 0f; var sxz = 0f; var syx = 0f; var syy = 0f; var syz = 0f; var szx = 0f; var szy = 0f; var szz = 0f
        for (k in a.indices) {
            val p = a[k]; val q = b[k]
            sxx += p.x * q.x; sxy += p.x * q.y; sxz += p.x * q.z
            syx += p.y * q.x; syy += p.y * q.y; syz += p.y * q.z
            szx += p.z * q.x; szy += p.z * q.y; szz += p.z * q.z
        }
        val n = arrayOf(
            floatArrayOf(sxx + syy + szz, syz - szy, szx - sxz, sxy - syx),
            floatArrayOf(syz - szy, sxx - syy - szz, sxy + syx, szx + sxz),
            floatArrayOf(szx - sxz, sxy + syx, -sxx + syy - szz, syz + szy),
            floatArrayOf(sxy - syx, szx + sxz, syz + szy, -sxx - syy + szz),
        )
        // shift so every eigenvalue is positive; the largest stays the largest
        val shift = a.size.toFloat() + 1f
        for (i in 0..3) n[i][i] += shift
        var v = floatArrayOf(1f, 0f, 0f, 0f)
        val t = FloatArray(4)
        repeat(60) {
            for (i in 0..3) t[i] = n[i][0] * v[0] + n[i][1] * v[1] + n[i][2] * v[2] + n[i][3] * v[3]
            val l = sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2] + t[3] * t[3]).coerceAtLeast(1e-12f)
            v = floatArrayOf(t[0] / l, t[1] / l, t[2] / l, t[3] / l)
        }
        out.set(v[1], v[2], v[3], v[0]).normalize()
    }

    // ------------------------------------------------------------------ map (loop closure)
    private fun maybeAddKeyframe(pyr: Pyramid) {
        if (!tracking && keyframes.isNotEmpty()) return
        if (prevCount < 20) return
        // a new keyframe when no stored one looks within 20 degrees of here
        if (keyframes.any { angleDeg(it.head, orientation) < 20f }) return
        if (keyframes.size >= MAX_KEYFRAMES) keyframes.removeAt(0)
        keyframes += Keyframe(pyr, prevFeatures.copyOf(), prevCount, orientation.copy())
        keyframeCount = keyframes.size
    }

    private var frameNo = 0

    /** Every 3rd frame: match the nearest keyframe and pull the orientation toward what it says. */
    private fun relocalize(pyr: Pyramid) {
        if (++frameNo % 3 != 0 || keyframes.isEmpty()) return
        val k = keyframes.minByOrNull { angleDeg(it.head, orientation) } ?: return
        if (angleDeg(k.head, orientation) > 25f) return
        // expected bearing motion keyframe -> now: M = camDelta⁻¹, from the two head orientations
        val dHead = k.head.conjugate(Quat()).mul(orientation, Quat())
        val inv = camToHead.conjugate(Quat())
        val dCam = inv.mul(dHead, Quat()).mul(camToHead, Quat())
        val guess = dCam.conjugate(Quat())
        val m = Quat()
        val n = estimate(k.pyr, k.features, k.count, pyr, guess, m)
        if (n < RELOC_MIN) return
        val measured = k.head.mul(toHead(m.conjugate(Quat())), Quat()).normalize()
        lastCorrectionDeg = angleDeg(measured, orientation)
        // the map is trusted over the frame-to-frame chain: most of the drift goes at once
        orientation.slerp(measured, 0.5f, orientation).normalize()
    }

    companion object {
        const val GRID_X = 8
        const val GRID_Y = 6
        const val MARGIN = 6
        const val MIN_CORNER = 40f
        const val MIN_MATCHES = 8
        const val RELOC_MIN = 12
        const val R1 = 7
        const val MAX_SSD = 49 * 30 * 30
        const val RANSAC_ITERS = 40
        const val INLIER_RAD = 0.012f   // ~0.7 degree
        const val MAX_KEYFRAMES = 60

        fun angleDeg(a: Quat, b: Quat): Float = Math.toDegrees(a.angleTo(b).toDouble()).toFloat()
    }
}
