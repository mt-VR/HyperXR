package com.hyperxr.handtracking

import com.hyperxr.math.Pose
import com.hyperxr.math.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the forearm goes in the camera picture: a segmentation network (on the NPU) marks the
 * skin and clothes pixels; from the wrist we look for the longest run of arm pixels on the side
 * away from the fingers, then refine its direction from the pixels in that cone.
 */
object ArmFromMask {
    class Result {
        /** A point along the forearm in the picture (0..1, same coordinates as the joints). */
        var px = 0f; var py = 0f
        /** Visible forearm, in palm lengths; whether it runs off the picture (the elbow is outside). */
        var visible = 0f
        var leavesImage = false
        var confidence = 0f
    }

    /**
     * [mask]: arm probability (0..1), row-major [n] x [n] over the picture. [image]: the hand's 21
     * joints as x, y, z triples (0..1). [aspect]: picture width / height (the mask is square).
     */
    fun forearm(mask: FloatArray, n: Int, image: FloatArray, aspect: Float, out: Result): Boolean {
        val wx = image[HandJoint.WRIST * 3] * aspect; val wy = image[HandJoint.WRIST * 3 + 1]
        val mx = image[HandJoint.MIDDLE_MCP * 3] * aspect; val my = image[HandJoint.MIDDLE_MCP * 3 + 1]
        val palm = sqrt((mx - wx) * (mx - wx) + (my - wy) * (my - wy))
        if (palm < 0.01f) return false
        val back = atan2(wy - my, wx - mx)
        val step = palm * 0.1f
        var bestA = 0f; var bestRun = 0f; var bestEdge = false
        var a = -80
        while (a <= 80) {
            val ang = back + Math.toRadians(a.toDouble()).toFloat()
            val c = cos(ang); val s = sin(ang)
            var r = palm * 0.35f; var run = 0f; var misses = 0; var edge = false
            while (r < palm * 3.5f) {
                val x = (wx + c * r) / aspect; val y = wy + s * r
                if (x < 0f || x >= 1f || y < 0f || y >= 1f) { edge = true; break }
                if (sample(mask, n, x, y) > 0.5f) { run = r - palm * 0.35f + step; misses = 0 } else if (++misses >= 2) break
                r += step
            }
            // a little preference for straight back from the fingers, where forearms usually are
            val score = run * (1f - abs(a) / 400f)
            if (score > bestRun) { bestRun = score; bestA = ang; bestEdge = edge }
            a += 5
        }
        if (bestRun < palm * 0.8f) return false
        // refine: the mean direction of the arm pixels in a cone around the best ray
        var sx = 0f; var sy = 0f; var sw = 0f; var sp = 0f; var cnt = 0
        val reach = minOf(bestRun + palm * 0.35f, palm * 3.5f)
        val cells = n / 64 + 1
        var gy = 0
        while (gy < n) {
            var gx = 0
            while (gx < n) {
                val nx = (gx + 0.5f) / n; val ny = (gy + 0.5f) / n
                val dx = nx * aspect - wx; val dy = ny - wy
                val d = sqrt(dx * dx + dy * dy)
                if (d > palm * 0.4f && d < reach) {
                    var da = atan2(dy, dx) - bestA
                    while (da > Math.PI) da -= (2 * Math.PI).toFloat()
                    while (da < -Math.PI) da += (2 * Math.PI).toFloat()
                    if (abs(da) < 0.35f) {
                        val p = mask[gy * n + gx]
                        if (p > 0.5f) { val w = p * d; sx += dx / d * w; sy += dy / d * w; sw += w; sp += p; cnt++ }
                    }
                }
                gx += cells
            }
            gy += cells
        }
        if (sw <= 0f || cnt < 4) return false
        val len = sqrt(sx * sx + sy * sy)
        val ux = sx / len; val uy = sy / len
        val k = palm * 1.5f
        out.px = (wx + ux * k) / aspect; out.py = wy + uy * k
        out.visible = bestRun / palm
        out.leavesImage = bestEdge
        out.confidence = ((bestRun / (palm * 2f)).coerceAtMost(1f) * (sp / cnt)).coerceIn(0f, 1f)
        return true
    }

    private fun sample(m: FloatArray, n: Int, x: Float, y: Float): Float {
        val ix = (x * n).toInt().coerceIn(0, n - 1); val iy = (y * n).toInt().coerceIn(0, n - 1)
        return m[iy * n + ix]
    }
}

/**
 * The elbow from what the camera saw: the forearm lies in the plane through the camera, the wrist
 * and the forearm's direction in the picture; in that plane the elbow is a forearm length from the
 * wrist, on the picture's side, and as near as it can be to an upper arm's length from the
 * shoulder (which picks between pointing at the camera and away from it).
 */
object ArmSolver {
    const val FOREARM = 0.26f
    const val UPPER_ARM = 0.29f

    private val a = Vec3(); private val b = Vec3(); private val nrm = Vec3(); private val q = Vec3()
    private val e = Vec3(); private val cand = Vec3(); private val ray = Vec3()

    /** The camera ray through picture point (nx, ny), world space. */
    fun rayThrough(nx: Float, ny: Float, intr: CameraIntrinsics, cam: Pose, out: Vec3): Vec3 {
        val u = nx * intr.width; val v = ny * intr.height
        cam.orientation.rotate(out.set((u - intr.cx) / intr.fx, -(v - intr.cy) / intr.fy, -1f), out)
        return out.normalize()
    }

    /**
     * Unit direction wrist -> elbow into [out]; false when the geometry is degenerate. [prior] is the
     * direction the arm had (or the IK guess), used only to break near ties.
     */
    @Synchronized
    fun elbowDirection(camPos: Vec3, wrist: Vec3, forearmRay: Vec3, shoulder: Vec3, prior: Vec3?, out: Vec3,
                       forearm: Float = FOREARM, upper: Float = UPPER_ARM): Boolean {
        a.set(wrist).sub(camPos)
        val dist = a.length()
        if (dist < 0.05f) return false
        a.scale(1f / dist)
        ray.set(forearmRay)
        a.cross(ray, nrm)
        if (nrm.length() < 1e-4f) return false
        nrm.normalize()
        nrm.cross(a, b); b.normalize()
        // which side of the wrist's view ray the forearm is on, in the plane
        q.set(camPos).addScaled(ray, dist).sub(wrist)
        val side = q.dot(b)
        if (abs(side) < 1e-5f) return false
        var best = Float.MAX_VALUE
        var deg = 0
        while (deg < 360) {
            val t = Math.toRadians(deg.toDouble()).toFloat()
            cand.set(a).scale(cos(t)).addScaled(b, sin(t))
            deg += 3
            if (cand.dot(b) * side <= 0f) continue
            e.set(wrist).addScaled(cand, forearm)
            val dx = e.x - shoulder.x; val dy = e.y - shoulder.y; val dz = e.z - shoulder.z
            val err = sqrt(dx * dx + dy * dy + dz * dz) - upper
            var cost = err * err
            if (prior != null) cost += 0.0015f * (1f - cand.dot(prior))
            // the elbow is not above the shoulder far ahead of you, nor in front of the camera
            if (e.y > shoulder.y + 0.1f) cost += 0.02f
            if (cost < best) { best = cost; out.set(cand) }
        }
        return best < Float.MAX_VALUE
    }
}
