package com.hyperxr.ocean

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The sea's shape: a sum of Gerstner waves (the surface points move in circles, so crests are
 * sharp and troughs wide, like real deep-water waves). Each wave follows the deep-water
 * dispersion relation (speed from wavelength), and wavelengths fall off geometrically from a long
 * swell to short chop. The GPU draws the same waves from [pack]; the CPU uses [displace] and
 * [heightAt] (sinking the room, tests).
 */
object OceanWaves {
    const val COUNT = 12
    /** Floats per wave in [pack]: dirX, dirZ, k, amplitude, steepness Q, omega, phase, 0. */
    const val STRIDE = 8
    private const val G = 9.81f

    /**
     * The wave set for a sea state: [storm] 0 = calm (long low swell), 1 = gale (high, steep,
     * longer waves, more chop). [windDeg] is where the waves travel towards.
     */
    fun pack(storm: Float, windDeg: Float = 35f, out: FloatArray = FloatArray(COUNT * STRIDE)): FloatArray {
        val s = storm.coerceIn(0f, 1f)
        val rnd = java.util.Random(7)
        val longest = lerp(55f, 120f, s)
        val spread = lerp(70f, 40f, s)
        val qTotal = lerp(0.45f, 0.8f, s)
        for (i in 0 until COUNT) {
            val wavelength = longest * 0.7f.pow(i)
            val k = (2 * PI / wavelength).toFloat()
            // slope k*A: gentle in calm water, steep in a storm; short waves a little steeper
            val slope = lerp(0.05f, 0.13f, s) * (0.75f + 0.25f * i / (COUNT - 1))
            val amp = slope / k
            val ang = Math.toRadians((windDeg + (rnd.nextFloat() * 2 - 1) * spread).toDouble())
            val o = i * STRIDE
            out[o] = cos(ang).toFloat(); out[o + 1] = sin(ang).toFloat()
            out[o + 2] = k; out[o + 3] = amp
            // the steepness is shared so crests never fold over (sum of Q*k*A <= qTotal < 1)
            out[o + 4] = qTotal / (k * amp * COUNT)
            out[o + 5] = sqrt(G * k)
            out[o + 6] = (rnd.nextFloat() * 2 * PI).toFloat()
            out[o + 7] = 0f
        }
        return out
    }

    /** Largest crest height of the set (sum of amplitudes), for scaling foam and colour. */
    fun maxHeight(w: FloatArray): Float { var a = 0f; for (i in 0 until COUNT) a += w[i * STRIDE + 3]; return a }

    /**
     * Where the surface point that rests at ([x], [z]) is at time [t] (relative to the sea level),
     * into [out] (x, y, z); returns the Jacobian's determinant (below ~0.3 the crest breaks: foam).
     */
    fun displace(w: FloatArray, x: Float, z: Float, t: Float, out: FloatArray, scale: Float = 1f): Float {
        var px = x; var py = 0f; var pz = z
        var jxx = 1f; var jzz = 1f; var jxz = 0f
        for (i in 0 until COUNT) {
            val o = i * STRIDE
            val dx = w[o]; val dz = w[o + 1]; val k = w[o + 2]; val a = w[o + 3] * scale; val q = w[o + 4]
            val th = k * (dx * x + dz * z) - w[o + 5] * t + w[o + 6]
            val c = cos(th); val sn = sin(th)
            px += q * a * dx * c; pz += q * a * dz * c; py += a * sn
            jxx -= q * a * k * dx * dx * sn; jzz -= q * a * k * dz * dz * sn; jxz -= q * a * k * dx * dz * sn
        }
        out[0] = px; out[1] = py; out[2] = pz
        return jxx * jzz - jxz * jxz
    }

    /** Height of the water above the sea level at the fixed place ([x], [z]) and time [t]. */
    fun heightAt(w: FloatArray, x: Float, z: Float, t: Float, scale: Float = 1f): Float {
        // the surface point over (x, z) started somewhere else: a few fixed-point steps find it
        val p = FloatArray(3)
        var gx = x; var gz = z
        repeat(4) {
            displace(w, gx, gz, t, p, scale)
            gx += x - p[0]; gz += z - p[2]
        }
        displace(w, gx, gz, t, p, scale)
        return p[1]
    }

    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

/**
 * Lightning and thunder: strikes at random times while the storm is on, each at a distance and
 * direction; the flash flickers in a few return strokes, and the thunder arrives distance / 343
 * seconds later (near strikes crack, far ones rumble).
 */
class StormClock(seed: Long = 11) {
    class Strike(val azimuth: Float, val distance: Float, val time: Float) {
        val thunderAt get() = time + distance / 343f
    }

    private val rnd = java.util.Random(seed)
    private var next = 4f
    var now = 0f; private set
    val strikes = ArrayList<Strike>()

    /** Advances the clock; returns a new strike when one happens this step. [rate] 1 = about one every 12 s. */
    fun update(dt: Float, enabled: Boolean, rate: Float = 1f): Strike? {
        now += dt
        strikes.removeAll { now - it.thunderAt > 12f }
        if (!enabled) { next = now + 2f + rnd.nextFloat() * 4f; return null }
        if (now < next) return null
        next = now + (5f + rnd.nextFloat() * 14f) / rate.coerceAtLeast(0.2f)
        // mostly far away; sometimes close (loud, sharp)
        val d = if (rnd.nextFloat() < 0.2f) 400f + rnd.nextFloat() * 900f else 1500f + rnd.nextFloat() * 6000f
        return Strike((rnd.nextFloat() * 2 * PI).toFloat(), d, now).also { strikes += it }
    }

    /** Sky brightness from the flashes (0..~1.5): a main stroke and two or three return strokes. */
    fun flash(): Float {
        var f = 0f
        for (s in strikes) {
            val t = now - s.time
            if (t < 0f || t > 1.2f) continue
            val near = (2500f / s.distance).coerceIn(0.25f, 1.5f)
            f += near * (pulse(t, 0f) + 0.7f * pulse(t, 0.09f) + 0.45f * pulse(t, 0.23f) + 0.3f * pulse(t, 0.41f))
        }
        return f
    }

    private fun pulse(t: Float, at: Float): Float {
        val u = t - at
        return if (u < 0f) 0f else exp(-u * 22f)
    }
}

/**
 * The bolt's path: from the cloud base down to the sea by midpoint displacement, with a few
 * branches that die out. Points are polylines in metres around the strike point (y up).
 */
object LightningPath {
    fun build(seed: Long, height: Float = 900f, roughness: Float = 0.22f, depth: Int = 7): List<FloatArray> {
        val rnd = java.util.Random(seed)
        val main = ArrayList<Float>()
        fun subdivide(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, level: Int, out: ArrayList<Float>) {
            if (level == 0) { out += bx; out += by; out += bz; return }
            val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az))
            val off = len * roughness
            val mx = (ax + bx) / 2 + (rnd.nextFloat() * 2 - 1) * off
            val my = (ay + by) / 2 + (rnd.nextFloat() * 2 - 1) * off * 0.3f
            val mz = (az + bz) / 2 + (rnd.nextFloat() * 2 - 1) * off
            subdivide(ax, ay, az, mx, my, mz, level - 1, out)
            subdivide(mx, my, mz, bx, by, bz, level - 1, out)
        }
        main += 0f; main += height; main += 0f
        subdivide(0f, height, 0f, (rnd.nextFloat() - 0.5f) * height * 0.3f, 0f, (rnd.nextFloat() - 0.5f) * height * 0.3f, depth, main)
        val paths = arrayListOf(main.toFloatArray())
        // branches from the upper two thirds, heading down and out
        val n = main.size / 3
        repeat(3 + rnd.nextInt(3)) {
            val i = 2 + rnd.nextInt(max(1, n * 2 / 3 - 2))
            val sx = main[i * 3]; val sy = main[i * 3 + 1]; val sz = main[i * 3 + 2]
            val l = height * (0.15f + rnd.nextFloat() * 0.25f)
            val b = ArrayList<Float>()
            b += sx; b += sy; b += sz
            subdivide(sx, sy, sz, sx + (rnd.nextFloat() - 0.5f) * l, max(0f, sy - l), sz + (rnd.nextFloat() - 0.5f) * l, depth - 3, b)
            paths += b.toFloatArray()
        }
        return paths
    }
}

/**
 * The room as the depth sensor sees it: points (world metres) gathered while looking around,
 * from which the floor height, the ceiling and how far the walls are come out. The floor is the
 * most populated horizontal layer well below the eyes; walls are the points between floor and
 * ceiling, by their horizontal distance from where the user stands.
 */
class RoomScan(private val capacity: Int = 30000) {
    val points = FloatArray(capacity * 3)
    var count = 0; private set
    private var write = 0

    fun reset() { count = 0; write = 0 }

    /** Adds [n] points from [src] (x, y, z each), overwriting the oldest when full. */
    fun add(src: FloatArray, n: Int) {
        for (i in 0 until n) {
            val o = write * 3
            points[o] = src[i * 3]; points[o + 1] = src[i * 3 + 1]; points[o + 2] = src[i * 3 + 2]
            write = (write + 1) % capacity
            if (count < capacity) count++
        }
    }

    /** Floor height (world y), or NaN with too few points below [eyeY] - 0.7 m. */
    fun floorY(eyeY: Float): Float {
        val bin = 0.03f
        val lo = eyeY - 2.4f; val hi = eyeY - 0.7f
        val bins = IntArray(((hi - lo) / bin).toInt() + 1)
        var total = 0
        for (i in 0 until count) {
            val y = points[i * 3 + 1]
            if (y < lo || y > hi) continue
            bins[((y - lo) / bin).toInt()]++; total++
        }
        if (total < 150) return Float.NaN
        // the densest 3-bin window: the floor is a big flat layer, furniture tops are smaller
        var best = 0; var bestAt = 0
        for (b in 1 until bins.size - 1) {
            val c = bins[b - 1] + bins[b] + bins[b + 1]
            if (c > best) { best = c; bestAt = b }
        }
        // a table top is dense too: prefer the lowest layer that is at least half as dense
        for (b in 1 until bestAt) {
            val c = bins[b - 1] + bins[b] + bins[b + 1]
            if (c >= best / 2) { bestAt = b; break }
        }
        return lo + (bestAt + 0.5f) * bin
    }

    /** Ceiling height, or NaN when the scan saw none. */
    fun ceilingY(eyeY: Float): Float {
        val ys = ArrayList<Float>()
        for (i in 0 until count) { val y = points[i * 3 + 1]; if (y > eyeY + 0.3f) ys += y }
        if (ys.size < 100) return Float.NaN
        ys.sort()
        // 80th percentile: lamps and shelves hang below the ceiling
        return ys[(ys.size * 0.8f).toInt().coerceAtMost(ys.size - 1)]
    }

    /** Median horizontal distance from ([x], [z]) to wall-height points, or NaN. */
    fun wallDistance(x: Float, z: Float, floorY: Float): Float {
        val d = ArrayList<Float>()
        for (i in 0 until count) {
            val y = points[i * 3 + 1]
            if (y < floorY + 0.4f || y > floorY + 2.2f) continue
            val dx = points[i * 3] - x; val dz = points[i * 3 + 2] - z
            d += sqrt(dx * dx + dz * dz)
        }
        if (d.size < 100) return Float.NaN
        d.sort()
        return d[d.size / 2]
    }
}

/**
 * How far along a ray from the eye the room is when nothing measured it: the floor plane, the
 * ceiling plane and a cylinder of the walls' distance around the user, whichever comes first.
 * Seen from the eye any of these looks the same (the photo is projected from there); they matter
 * when the room moves (sinks), which then shows the right parallax for a box-like room.
 */
object RoomShape {
    fun distance(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, floorY: Float, ceilY: Float, wall: Float): Float {
        var t = 30f
        if (dy < -1e-4f) t = min(t, (floorY - oy) / dy)
        if (dy > 1e-4f) t = min(t, (ceilY - oy) / dy)
        val a = dx * dx + dz * dz
        if (a > 1e-6f) t = min(t, wall / sqrt(a))   // the user stands at the cylinder's axis
        return t.coerceAtLeast(0.2f)
    }
}

/** The room going down into the sea: eased, slow at both ends. 0..1 over [duration] seconds. */
object Sinking {
    fun progress(t: Float, duration: Float): Float {
        val u = (t / duration).coerceIn(0f, 1f)
        return if (u < 0.5f) 4 * u * u * u else 1 - (-2 * u + 2).let { it * it * it } / 2
    }
}

/** The sky's and sea's colours for a time of day and weather (linear light, before tone mapping). */
class SkyState {
    val sunDir = FloatArray(3); val sunColor = FloatArray(3)
    val zenith = FloatArray(3); val horizon = FloatArray(3)
    val cloudLit = FloatArray(3); val cloudShade = FloatArray(3)
    val deep = FloatArray(3); val scatter = FloatArray(3)
    var cloud = 0f; var night = 0f; var exposure = 1f; var fog = 0f; var ambient = 1f
    /** Sun elevation in degrees (negative: below the horizon, the moon lights the scene). */
    var elevation = 0f
}

object SkyLook {
    private fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
    private fun mix3(out: FloatArray, a: FloatArray, b: FloatArray, t: Float) { for (i in 0..2) out[i] = a[i] + (b[i] - a[i]) * t }

    private val DAY_SUN = floatArrayOf(3.2f, 3.07f, 2.88f); private val DUSK_SUN = floatArrayOf(2.4f, 1.2f, 0.53f); private val MOON = floatArrayOf(0.14f, 0.16f, 0.21f)
    private val DAY_ZEN = floatArrayOf(0.05f, 0.2f, 0.6f); private val DUSK_ZEN = floatArrayOf(0.12f, 0.14f, 0.32f); private val NIGHT_ZEN = floatArrayOf(0.003f, 0.006f, 0.02f)
    private val DAY_HOR = floatArrayOf(0.5f, 0.66f, 0.88f); private val DUSK_HOR = floatArrayOf(1.0f, 0.52f, 0.30f); private val NIGHT_HOR = floatArrayOf(0.015f, 0.022f, 0.045f)
    private val DAY_CLOUD = floatArrayOf(1.1f, 1.1f, 1.1f); private val DUSK_CLOUD = floatArrayOf(1.0f, 0.62f, 0.5f); private val NIGHT_CLOUD = floatArrayOf(0.05f, 0.06f, 0.09f)
    private val GREY = floatArrayOf(0.33f, 0.35f, 0.39f)

    /**
     * [light] 0..1: night, sunrise/sunset glow, full day. [weather] 0..1: clear to a dark
     * overcast storm sky. [azimuth] is where the sun stands (radians, 0 = -Z).
     */
    fun compute(light: Float, weather: Float, azimuth: Float, out: SkyState) {
        val l = light.coerceIn(0f, 1f); val w = weather.coerceIn(0f, 1f)
        val elev = OceanWaves.lerp(-14f, 60f, l)
        out.elevation = elev
        val day = smooth(-4f, 20f, elev)
        val dusk = smooth(-6f, 3f, elev) * (1f - smooth(4f, 22f, elev))
        val night = 1f - smooth(-10f, -2f, elev)
        out.night = night * (1f - w)
        // the light comes from the sun, or from the moon (high, opposite side) once the sun is down
        val moon = night > 0.5f
        val e = Math.toRadians((if (moon) 38f else elev.coerceAtLeast(-3f)).toDouble())
        val az = if (moon) azimuth + PI.toFloat() * 0.8f else azimuth
        out.sunDir[0] = (-sin(az) * cos(e)).toFloat(); out.sunDir[1] = sin(e).toFloat(); out.sunDir[2] = (-cos(az) * cos(e)).toFloat()
        val tmp = FloatArray(3)
        mix3(tmp, DAY_SUN, DUSK_SUN, dusk / (dusk + day + 1e-3f))
        mix3(out.sunColor, tmp, MOON, night)
        mix3(tmp, DAY_ZEN, DUSK_ZEN, dusk); mix3(out.zenith, tmp, NIGHT_ZEN, night)
        mix3(tmp, DAY_HOR, DUSK_HOR, dusk); mix3(out.horizon, tmp, NIGHT_HOR, night)
        mix3(tmp, DAY_CLOUD, DUSK_CLOUD, dusk); mix3(out.cloudLit, tmp, NIGHT_CLOUD, night)
        // overcast: everything towards grey, darker the stormier
        val bright = OceanWaves.lerp(0.04f, 1f, day.coerceAtLeast(dusk * 0.6f))
        for (i in 0..2) {
            out.zenith[i] = OceanWaves.lerp(out.zenith[i], GREY[i] * 0.7f * bright, w * 0.85f)
            out.horizon[i] = OceanWaves.lerp(out.horizon[i], GREY[i] * 1.05f * bright, w * 0.85f)
            out.cloudLit[i] = OceanWaves.lerp(out.cloudLit[i], GREY[i] * 1.3f * bright, w * 0.7f)
            out.cloudShade[i] = out.cloudLit[i] * OceanWaves.lerp(0.4f, 0.22f, w)
            out.sunColor[i] *= 1f - 0.8f * w
        }
        out.cloud = OceanWaves.lerp(0.42f, 0.97f, w)
        out.ambient = OceanWaves.lerp(0.07f, 1f, maxOf(day, dusk * 0.5f)) * OceanWaves.lerp(1f, 0.55f, w)
        for (i in 0..2) out.deep[i] = floatArrayOf(0f, 0.045f, 0.075f)[i] * out.ambient
        out.scatter[0] = 0f; out.scatter[1] = 0.32f; out.scatter[2] = 0.30f
        // moonlight is dim: the eye adapts, so expose more at night
        out.exposure = OceanWaves.lerp(1.9f, 1f, maxOf(day, dusk))
        out.fog = OceanWaves.lerp(0.00012f, 0.0011f, w)
    }
}
