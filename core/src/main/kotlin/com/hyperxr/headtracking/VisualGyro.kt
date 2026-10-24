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

    /** Bilinear sample; (x, y) must be at least one pixel inside the image. */
    fun sample(x: Float, y: Float): Float {
        val ix = x.toInt(); val iy = y.toInt()
        val fx = x - ix; val fy = y - iy
        val i = iy * w + ix
        val a = (px[i].toInt() and 0xff); val b = (px[i + 1].toInt() and 0xff)
        val c = (px[i + w].toInt() and 0xff); val d = (px[i + w + 1].toInt() and 0xff)
        return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy
    }
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
 *  - gravity (accelerometer, see [applyGravity]) keeps pitch and roll level, and the compass,
 *    when the phone has one, keeps yaw from drifting over minutes (see [applyHeading]).
 * Speed: features persist from frame to frame (corners are only searched for in grid cells that
 * lost theirs), the patch search is one pass per candidate with a radius that shrinks while the
 * motion guess is good, and the final position is refined to sub-pixel by Lucas-Kanade.
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
        haveNorth = false; prevCount = 0; prevFeatures = FloatArray(0)
    }

    /** Features carried into the next frame (tracked ones that survived plus new corners). */
    val featureCount: Int get() = prevCount
    /** Microseconds of the last [process] call. */
    @Volatile var lastProcessUs = 0L; private set

    /**
     * One camera frame (grayscale, at the resolution the intrinsics refer to). Returns true when
     * the rotation was measured; false when the view has too little texture (dark, a blank wall).
     */
    fun process(img: GrayImage, timeNs: Long): Boolean {
        val t0 = System.nanoTime()
        // [img] is kept (as the previous frame and maybe a keyframe): callers pass a fresh image
        val pyr = Pyramid(img, img.half())
        val p = prev
        var ok = false
        tracked.clear()
        if (p != null && prevCount >= MIN_MATCHES) {
            val m = Quat()
            // a good motion guess needs only a small search; on failure the full radius is tried
            var n = if (tracking && inliers >= 20) estimate(p, prevFeatures, prevCount, pyr, lastM, m, R1_SMALL, tracked) else 0
            if (n < MIN_MATCHES) { tracked.clear(); n = estimate(p, prevFeatures, prevCount, pyr, lastM, m, R1, tracked) }
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
        // features for the next frame: the tracked inliers, plus new corners in empty cells
        if (!ok) tracked.clear()
        prevFeatures = detect(pyr.l0, tracked)
        prevCount = prevFeatures.size / 2
        prev = pyr
        prevTimeNs = timeNs
        maybeAddKeyframe(pyr)
        lastProcessUs = (System.nanoTime() - t0) / 1000
        return ok
    }

    private val tracked = ArrayList<Float>(GRID_X * GRID_Y * 2)

    private var haveNorth = false
    private val worldNorth = Vec3()

    /**
     * Pulls yaw toward the compass: [northHead] is the magnetic field in the head frame (any
     * length). The first call stores where north is in the world; later calls turn the head about
     * the vertical by [alpha] of the difference. Callers skip it when the field strength is off
     * (a speaker or metal nearby).
     */
    fun applyHeading(northHead: Vec3, alpha: Float) {
        val n = orientation.rotate(Vec3(northHead.x, northHead.y, northHead.z))
        n.y = 0f
        if (n.length() < 1e-3f) return
        n.normalize()
        if (!haveNorth) { worldNorth.set(n.x, n.y, n.z); haveNorth = true; return }
        // signed angle about +y from the measured north to the stored one
        val ang = kotlin.math.atan2(n.z * worldNorth.x - n.x * worldNorth.z, n.x * worldNorth.x + n.z * worldNorth.z)
        if (abs(ang) > 0.6f) return  // a jump this large is a disturbance, not drift
        val corr = Quat.fromAxisAngle(0f, 1f, 0f, ang * alpha)
        orientation.set(corr.mul(orientation, Quat())).normalize()
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
    private val occupied = BooleanArray(GRID_X * GRID_Y)

    /**
     * The [kept] features (one per grid cell, the rest dropped) plus the best corner (Shi-Tomasi
     * score on a 5x5 window) of every cell without one, (x, y) pairs.
     */
    private fun detect(img: GrayImage, kept: List<Float> = emptyList()): FloatArray {
        val out = ArrayList<Float>(GRID_X * GRID_Y * 2)
        val cw = img.w / GRID_X; val ch = img.h / GRID_Y
        occupied.fill(false)
        var k = 0
        while (k + 1 < kept.size) {
            val x = kept[k]; val y = kept[k + 1]; k += 2
            if (x < MARGIN || y < MARGIN || x >= img.w - MARGIN || y >= img.h - MARGIN) continue
            val c = minOf((y / ch).toInt(), GRID_Y - 1) * GRID_X + minOf((x / cw).toInt(), GRID_X - 1)
            if (occupied[c]) continue
            occupied[c] = true; out += x; out += y
        }
        for (gy in 0 until GRID_Y) for (gx in 0 until GRID_X) {
            if (occupied[gy * GRID_X + gx]) continue
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

    private val patch = IntArray(49)

    /** Loads the 7x7 patch of [a] at (ax, ay) for [ssd]; false when off the image. */
    private fun loadPatch(a: GrayImage, ax: Int, ay: Int): Boolean {
        if (ax < 3 || ay < 3 || ax >= a.w - 3 || ay >= a.h - 3) return false
        var k = 0
        for (dy in -3..3) { val r = (ay + dy) * a.w + ax; for (dx in -3..3) patch[k++] = a.px[r + dx].toInt() and 0xff }
        return true
    }

    /**
     * Mean-removed SSD (robust to the camera's exposure changing between frames) of the loaded
     * patch against [b] at (bx, by), in one pass: Σ(d - mean d)² = Σd² - (Σd)²/49.
     */
    private fun ssd(b: GrayImage, bx: Int, by: Int): Int {
        if (bx < 3 || by < 3 || bx >= b.w - 3 || by >= b.h - 3) return Int.MAX_VALUE
        var sd = 0; var sd2 = 0; var k = 0
        val px = b.px; val w = b.w
        for (dy in -3..3) {
            val r = (by + dy) * w + bx
            for (dx in -3..3) { val d = patch[k++] - (px[r + dx].toInt() and 0xff); sd += d; sd2 += d * d }
        }
        return sd2 - sd * sd / 49
    }

    private val tpl = FloatArray(81)
    private val tgx = FloatArray(49)
    private val tgy = FloatArray(49)

    /**
     * Sub-pixel position by Lucas-Kanade (inverse compositional, translation, mean-removed): the
     * patch of [a] at (x0, y0) is looked for in [b] starting at (sx, sy). False when it does not
     * converge near the start.
     */
    private fun refine(a: GrayImage, x0: Float, y0: Float, b: GrayImage, sx: Float, sy: Float, out: FloatArray): Boolean {
        if (x0 < 5 || y0 < 5 || x0 >= a.w - 6 || y0 >= a.h - 6) return false
        for (j in 0 until 9) for (i in 0 until 9) tpl[j * 9 + i] = a.sample(x0 + i - 4, y0 + j - 4)
        var hxx = 0f; var hxy = 0f; var hyy = 0f; var tm = 0f
        for (j in 0 until 7) for (i in 0 until 7) {
            val k = (j + 1) * 9 + i + 1
            val gx = (tpl[k + 1] - tpl[k - 1]) / 2; val gy = (tpl[k + 9] - tpl[k - 9]) / 2
            tgx[j * 7 + i] = gx; tgy[j * 7 + i] = gy
            hxx += gx * gx; hxy += gx * gy; hyy += gy * gy; tm += tpl[k]
        }
        tm /= 49
        val det = hxx * hyy - hxy * hxy
        if (det < 1f) return false
        var x = sx; var y = sy
        for (it in 0 until 4) {
            if (x < 4 || y < 4 || x >= b.w - 5 || y >= b.h - 5) return false
            var im = 0f
            for (j in 0 until 7) for (i in 0 until 7) { val v = b.sample(x + i - 3, y + j - 3); res[j * 7 + i] = v; im += v }
            im /= 49
            var ex = 0f; var ey = 0f
            for (j in 0 until 7) for (i in 0 until 7) {
                val k = j * 7 + i
                val e = (res[k] - im) - (tpl[(j + 1) * 9 + i + 1] - tm)
                ex += tgx[k] * e; ey += tgy[k] * e
            }
            val dx = (hyy * ex - hxy * ey) / det; val dy = (hxx * ey - hxy * ex) / det
            x -= dx; y -= dy
            if (abs(dx) < 0.02f && abs(dy) < 0.02f) break
        }
        if (abs(x - sx) > 1.5f || abs(y - sy) > 1.5f) return false
        out[0] = x; out[1] = y
        return true
    }
    private val res = FloatArray(49)

    /** Coarse-to-fine match of a feature: returns (x, y) in [to] or null. */
    private fun track(from: Pyramid, fxp: Float, fyp: Float, to: Pyramid, gx: Float, gy: Float, radius: Int, out: FloatArray): Boolean {
        // level 1 (half size): search around the guess
        val ax1 = (fxp / 2).toInt(); val ay1 = (fyp / 2).toInt()
        if (!loadPatch(from.l1, ax1, ay1)) return false
        var bestS = Int.MAX_VALUE; var bx = 0; var by = 0
        val cx1 = (gx / 2).toInt(); val cy1 = (gy / 2).toInt()
        for (dy in -radius..radius) for (dx in -radius..radius) {
            val s = ssd(to.l1, cx1 + dx, cy1 + dy)
            if (s < bestS) { bestS = s; bx = cx1 + dx; by = cy1 + dy }
        }
        if (bestS == Int.MAX_VALUE) return false
        // level 0: integer refinement, then sub-pixel by Lucas-Kanade (a parabola if it fails)
        val ax0 = fxp.toInt(); val ay0 = fyp.toInt()
        if (!loadPatch(from.l0, ax0, ay0)) return false
        bestS = Int.MAX_VALUE
        var rx = 0; var ry = 0
        for (dy in -2..2) for (dx in -2..2) {
            val s = ssd(to.l0, bx * 2 + dx, by * 2 + dy)
            if (s < bestS) { bestS = s; rx = bx * 2 + dx; ry = by * 2 + dy }
        }
        if (bestS == Int.MAX_VALUE || bestS > MAX_SSD) return false
        val sx = rx + (fxp - ax0); val sy = ry + (fyp - ay0)
        if (refine(from.l0, fxp, fyp, to.l0, sx, sy, out)) return true
        fun sub(m: Int, c: Int, p: Int): Float { val d = (m - 2 * c + p).toFloat(); return if (d <= 0f) 0f else ((m - p) / (2 * d)).coerceIn(-0.5f, 0.5f) }
        val sl = ssd(to.l0, rx - 1, ry); val sr = ssd(to.l0, rx + 1, ry)
        val su = ssd(to.l0, rx, ry - 1); val sd = ssd(to.l0, rx, ry + 1)
        out[0] = sx + (if (sl != Int.MAX_VALUE && sr != Int.MAX_VALUE) sub(sl, bestS, sr) else 0f)
        out[1] = sy + (if (su != Int.MAX_VALUE && sd != Int.MAX_VALUE) sub(su, bestS, sd) else 0f)
        return true
    }

    private fun bearing(x: Float, y: Float, out: Vec3): Vec3 = out.set((x - cx) / fx, (y - cy) / fy, 1f).normalize()

    private fun project(b: Vec3, out: FloatArray) {
        out[0] = cx + fx * b.x / b.z; out[1] = cy + fy * b.y / b.z
    }

    /**
     * Matches [features] of [from] into [to] starting from the motion [guess] (b2 = guess b1),
     * then finds the rotation. Returns the inlier count and the rotation in [out]; the inliers'
     * positions in [to] go to [inlierPx] when given.
     */
    private fun estimate(from: Pyramid, features: FloatArray, count: Int, to: Pyramid, guess: Quat, out: Quat, radius: Int = R1, inlierPx: MutableList<Float>? = null): Int {
        val a = ArrayList<Vec3>(count); val b = ArrayList<Vec3>(count)
        val px = ArrayList<Float>(count * 2)
        val g = FloatArray(2); val m = FloatArray(2)
        for (i in 0 until count) {
            val x = features[i * 2]; val y = features[i * 2 + 1]
            val b1 = bearing(x, y, Vec3())
            val p = guess.rotate(b1)
            if (p.z < 0.2f) continue
            project(p, g)
            if (!track(from, x, y, to, g[0], g[1], radius, m)) continue
            a += b1; b += bearing(m[0], m[1], Vec3()); px += m[0]; px += m[1]
        }
        if (a.size < MIN_MATCHES) return 0
        val n = ransac(a, b, out)
        if (inlierPx != null && n >= MIN_MATCHES) {
            val cosT = kotlin.math.cos(INLIER_RAD)
            for (k in a.indices) if (out.rotate(a[k]).dot(b[k]) > cosT) { inlierPx += px[k * 2]; inlierPx += px[k * 2 + 1] }
        }
        return n
    }

    private val rv = Vec3()

    /** 2-point RANSAC on bearing pairs, then Horn's method on the inliers. */
    private fun ransac(a: List<Vec3>, b: List<Vec3>, out: Quat): Int {
        val n = a.size
        var bestInl = 0
        val best = Quat()
        val q = Quat()
        val rnd = java.util.Random(n.toLong() * 7919)
        val cosT = kotlin.math.cos(INLIER_RAD)
        val pa = arrayListOf(a[0], a[0]); val pb = arrayListOf(b[0], b[0])
        for (iter in 0 until RANSAC_ITERS) {
            val i = rnd.nextInt(n); var j = rnd.nextInt(n)
            if (j == i) j = (j + 1) % n
            pa[0] = a[i]; pa[1] = a[j]; pb[0] = b[i]; pb[1] = b[j]
            horn(pa, pb, q)
            var inl = 0
            for (k in 0 until n) if (q.rotate(a[k], rv).dot(b[k]) > cosT) inl++
            if (inl > bestInl) { bestInl = inl; best.set(q) }
            // nearly everything agrees already (a pure rotation, few outliers): no need to keep drawing
            if (iter >= 8 && bestInl >= n * 0.9f) break
        }
        if (bestInl < MIN_MATCHES) return bestInl
        val ia = ArrayList<Vec3>(bestInl); val ib = ArrayList<Vec3>(bestInl)
        for (k in 0 until n) if (best.rotate(a[k], rv).dot(b[k]) > cosT) { ia += a[k]; ib += b[k] }
        horn(ia, ib, out)
        return ia.size
    }

    /**
     * Horn's closed-form rotation (unit quaternion) minimising Σ|b - R a|²: the eigenvector of the
     * largest eigenvalue of a 4x4 symmetric matrix, by shifted power iteration.
     */
    private val hv = FloatArray(4); private val ht = FloatArray(4)

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
        val v = hv; v[0] = 1f; v[1] = 0f; v[2] = 0f; v[3] = 0f
        val t = ht
        for (it in 0 until 60) {
            for (i in 0..3) t[i] = n[i][0] * v[0] + n[i][1] * v[1] + n[i][2] * v[2] + n[i][3] * v[3]
            val l = sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2] + t[3] * t[3]).coerceAtLeast(1e-12f)
            var change = 0f
            for (i in 0..3) { val nv = t[i] / l; change += abs(nv - v[i]); v[i] = nv }
            // converged: further power steps change nothing measurable
            if (change < 1e-7f) break
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
        const val R1_SMALL = 3
        const val MAX_SSD = 49 * 30 * 30
        const val RANSAC_ITERS = 40
        const val INLIER_RAD = 0.012f   // ~0.7 degree
        const val MAX_KEYFRAMES = 60

        fun angleDeg(a: Quat, b: Quat): Float = Math.toDegrees(a.angleTo(b).toDouble()).toFloat()
    }
}
