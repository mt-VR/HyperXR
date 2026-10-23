package com.hyperxr.input

import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.HandState
import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * Keyboard lying on a real table: the layout in the table's plane, in metres. Local coordinates:
 * x to the right, z towards the user (rows nearer the user have larger z), origin at the centre.
 * Keys are bigger than a real keyboard's (3.2 cm pitch) because phone hand tracking is a
 * centimetre or so off; that is what keeps typing accurate.
 */
class TableKeyLayout(val pitch: Float = 0.032f, val rowPitch: Float = 0.034f, val gap: Float = 0.003f) {
    enum class Kind { CHAR, SHIFT, BACKSPACE, SPACE, ENTER, SYMBOLS, CLOSE }

    class Key(val id: String, val label: String, val kind: Kind, val text: String, val x0: Float, val z0: Float, val x1: Float, val z1: Float) {
        val cx get() = (x0 + x1) / 2
        val cz get() = (z0 + z1) / 2
        fun contains(x: Float, z: Float) = x in x0..x1 && z in z0..z1
    }

    var symbols = false
        set(v) { field = v; build() }

    var keys: List<Key> = emptyList(); private set
    var width = 0f; private set
    var depth = 0f; private set

    init { build() }

    private fun build() {
        val rows = if (symbols) listOf("1234567890", "@#$%&*-+()", "!?/:;'\",.", "") else listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm")
        width = 10 * pitch
        depth = 5 * rowPitch
        val out = ArrayList<Key>()
        val left = -width / 2
        val top = -depth / 2
        rows.forEachIndexed { r, row ->
            val z0 = top + r * rowPitch
            // centred rows like a phone keyboard; the last letter row leaves room for shift/backspace
            val start = left + (10 - row.length) * pitch / 2
            row.forEachIndexed { i, ch ->
                val x0 = start + i * pitch
                out += Key("c$r$i", ch.toString(), Kind.CHAR, ch.toString(), x0 + gap, z0 + gap, x0 + pitch - gap, z0 + rowPitch - gap)
            }
        }
        val z3 = top + 3 * rowPitch
        out += Key("shift", "Shift", Kind.SHIFT, "", left + gap, z3 + gap, left + 1.5f * pitch - gap, z3 + rowPitch - gap)
        out += Key("bksp", "Apagar", Kind.BACKSPACE, "", left + 8.5f * pitch + gap, z3 + gap, left + width - gap, z3 + rowPitch - gap)
        val z4 = top + 4 * rowPitch
        out += Key("sym", if (symbols) "abc" else "?123", Kind.SYMBOLS, "", left + gap, z4 + gap, left + 1.5f * pitch - gap, z4 + rowPitch - gap)
        out += Key("space", "Espaço", Kind.SPACE, " ", left + 1.5f * pitch + gap, z4 + gap, left + 7.5f * pitch - gap, z4 + rowPitch - gap)
        out += Key("enter", "Enter", Kind.ENTER, "", left + 7.5f * pitch + gap, z4 + gap, left + 9f * pitch - gap, z4 + rowPitch - gap)
        out += Key("close", "X", Kind.CLOSE, "", left + 9f * pitch + gap, z4 + gap, left + width - gap, z4 + rowPitch - gap)
        keys = out
    }

    /**
     * Like [keyAt], but a fingertip that was over [previous] stays on it until it is [margin]
     * past its edge: tracking noise at the border between two keys no longer flips between them.
     */
    fun keyAtSticky(x: Float, z: Float, previous: Key?, margin: Float = 0.006f): Key? {
        if (previous != null && previous in keys && x in previous.x0 - margin..previous.x1 + margin && z in previous.z0 - margin..previous.z1 + margin) return previous
        return keyAt(x, z)
    }

    /** The key under (x, z), or the nearest key centre within half a pitch of its edge. */
    fun keyAt(x: Float, z: Float): Key? {
        keys.firstOrNull { it.contains(x, z) }?.let { return it }
        // between keys (the gaps): the nearest one, if close enough
        var best: Key? = null; var bestD = Float.MAX_VALUE
        for (k in keys) {
            val dx = maxOf(k.x0 - x, 0f, x - k.x1); val dz = maxOf(k.z0 - z, 0f, z - k.z1)
            val d = dx * dx + dz * dz
            if (d < bestD) { bestD = d; best = k }
        }
        return if (bestD < (gap * 2) * (gap * 2)) best else null
    }
}

/**
 * Where the keyboard lies: a point on the table (its centre) and the yaw it faces the user with.
 * The table is level (tracking space has gravity along -Y).
 */
class TablePlacement {
    val center = Vec3()
    var yaw = 0f
    var valid = false

    /** World point -> keyboard local (x right, y up from the table, z towards the user). */
    fun toLocal(p: Vec3, out: Vec3): Vec3 {
        val dx = p.x - center.x; val dz = p.z - center.z
        val c = cos(yaw); val s = sin(yaw)
        // keyboard right = (cos yaw, 0, -sin yaw), towards the user = (sin yaw, 0, cos yaw)
        return out.set(dx * c - dz * s, p.y - center.y, dx * s + dz * c)
    }

    fun toWorld(x: Float, y: Float, z: Float, out: Vec3): Vec3 {
        val c = cos(yaw); val s = sin(yaw)
        return out.set(center.x + x * c + z * s, center.y + y, center.z - x * s + z * c)
    }

    /** Pose of the flat keyboard panel: lying on the table, its top edge away from the user. */
    fun panelPose(out: Pose, lift: Float = 0.002f): Pose {
        out.position.set(center.x, center.y + lift, center.z)
        Quat.fromAxisAngle(0f, 1f, 0f, yaw).mul(Quat.fromAxisAngle(1f, 0f, 0f, (-Math.PI / 2).toFloat()), out.orientation)
        return out
    }

    /** In front of the head (yaw), [distance] m ahead horizontally, on a table at height [tableY]. */
    fun placeInFront(head: Pose, tableY: Float, distance: Float = 0.38f) {
        val f = head.orientation.rotate(Vec3(0f, 0f, -1f))
        yaw = kotlin.math.atan2(-f.x, -f.z)
        center.set(head.position.x - sin(yaw) * distance, tableY, head.position.z - cos(yaw) * distance)
        valid = true
    }
}

/**
 * Finds the table under the hands from depth points (tracking space, metres): the most populated
 * horizontal level between [minBelowHead] and [maxBelowHead] under the eyes. Tables are level, so
 * a height histogram is all it takes (robust to the floor, walls and objects on the table).
 */
object TableDetector {
    class Table(val height: Float, val support: Int, val centroid: Vec3)

    fun detect(points: FloatArray, count: Int, headY: Float, minBelowHead: Float = 0.2f, maxBelowHead: Float = 1.1f, minSupport: Int = 40): Table? {
        val bin = 0.01f
        val lo = headY - maxBelowHead; val hi = headY - minBelowHead
        val nb = ((hi - lo) / bin).toInt() + 1
        if (nb <= 2) return null
        val hist = IntArray(nb)
        for (i in 0 until count) {
            val y = points[i * 3 + 1]
            if (y < lo || y > hi) continue
            hist[((y - lo) / bin).toInt().coerceIn(0, nb - 1)]++
        }
        // best 3-bin window (a plane with ~1 cm noise spreads over neighbouring bins)
        var best = -1; var bestN = 0
        for (b in 1 until nb - 1) {
            val n = hist[b - 1] + hist[b] + hist[b + 1]
            if (n > bestN) { bestN = n; best = b }
        }
        if (best < 0 || bestN < minSupport) return null
        val yc = lo + (best + 0.5f) * bin
        var sy = 0.0; var sx = 0.0; var sz = 0.0; var n = 0
        for (i in 0 until count) {
            val y = points[i * 3 + 1]
            if (kotlin.math.abs(y - yc) > 0.015f) continue
            sx += points[i * 3]; sy += y; sz += points[i * 3 + 2]; n++
        }
        if (n < minSupport) return null
        return Table((sy / n).toFloat(), n, Vec3((sx / n).toFloat(), (sy / n).toFloat(), (sz / n).toFloat()))
    }
}

/**
 * Typing on the table: HyperXR's own contact detector. A key is typed only when a fingertip really
 * taps the table, not when it merely comes close. Each new camera result is checked for the two
 * physical signs of a tap, both required:
 *  1. contact: the fingertip is at the table. With the ARCore Depth API this is measured, the
 *     depth map read on the fingertip itself (HandState.measuredTips); without it, the hand
 *     model's fingertip is used with wider margins;
 *  2. impact: the fingertip was coming down and stopped abruptly. A finger hitting a hard surface
 *     decelerates within one camera frame; a finger lowered slowly or hovering never does.
 * Plus: the finger must have come down from higher up (and lift again before its next key), the
 * same key is never accepted twice within [sameKeyNs], and when two fingers of one hand land in the
 * same result only the lower one counts. Only the fingers in [enabled] type (0 thumb .. 4 pinky);
 * the thumb only ever types the space bar.
 *
 * The hand landmarks themselves come from the hand tracking engine (on the NPU with the NPU
 * engine). The motion is read on the raw per-result fingertips: smoothing would blur the stop.
 */
class TableTyping(val layout: TableKeyLayout) {
    /** Fingertip height above the table that counts as contact (measured / model-only). */
    var contactMeasuredM = 0.014f
    var contactModelM = 0.013f
    /** Height to lift above before the same finger can type again. */
    var releaseM = 0.022f
    /** It must have come down at least this much since it was last lifted. */
    var minDrop = 0.012f
    /** Downward speed before the stop, and how much of it must be gone after (the impact). */
    var descentSpeed = 0.10f
    var stopFraction = 0.4f
    /** A model-only tap is not confirmed when the finger is already rising faster than this. */
    var confirmRiseSpeed = 0.25f
    var sameKeyNs = 140_000_000L
    /** How far above a key a fingertip highlights it. */
    var hoverH = 0.05f
    /** The fingertip joint is at the centre of the fingertip; the pad is this far below it. */
    var tipRadius = 0.007f
    /** The depth map sees the top of the finger: about this far above the pad. */
    var fingerThickness = 0.012f
    /**
     * AI depth (HandState.nnTipHeight): when fresh, a tap found by the motion is only typed once
     * the network confirms the fingertip is on the table, whose top is then below [nnContactM].
     */
    var useAiDepth = true
    var nnContactM = 0.022f
    /** How long a tap may wait for the network's verdict before it is dropped. */
    var nnWaitNs = 350_000_000L
    /** Fingers that type (0 thumb, 1 index, 2 middle, 3 ring, 4 pinky). */
    var enabled: Set<Int> = setOf(1, 2)

    private class Finger {
        val t = LongArray(4); val h = FloatArray(4); var n = 0
        var down = false; var peak = Float.NaN; var key: TableKeyLayout.Key? = null; var over: TableKeyLayout.Key? = null
        /** Model-only taps wait for the next result to confirm the finger stays on the table. */
        var pending = false
        /** A tap waiting for the AI depth verdict, found on the result of [impactNs]. */
        var waitNn = false; var impactNs = 0L
        fun push(ts: Long, height: Float) {
            for (i in 3 downTo 1) { t[i] = t[i - 1]; h[i] = h[i - 1] }
            t[0] = ts; h[0] = height; if (n < 4) n++
        }
        fun clear() { n = 0; down = false; peak = Float.NaN; key = null; over = null; pending = false; waitNn = false }
    }
    private val state = Array(2) { Array(5) { Finger() } }
    private val lastResult = LongArray(2)
    private var lastKey: TableKeyLayout.Key? = null
    private var lastKeyNs = Long.MIN_VALUE / 2
    private val local = Vec3()

    /** Keys a fingertip hovers over (for the highlight), and keys currently held down. */
    val hovered = HashSet<String>()
    val held = HashSet<String>()

    fun reset() { for (h in state) for (f in h) f.clear(); lastResult.fill(0L); hovered.clear(); held.clear() }

    private fun tip(f: Int) = 4 + f * 4

    /** Height of finger [f]'s pad above the table in this camera result, and whether it was measured. */
    private fun resultHeight(hand: HandState, f: Int, place: TablePlacement): Pair<Float, Boolean> =
        if (hand.measuredTipValid[f]) {
            place.toLocal(hand.measuredTips[f], local); (local.y - fingerThickness) to true
        } else {
            place.toLocal(hand.rawTips[f], local); (local.y - tipRadius) to false
        }

    /** One display frame; returns the keys typed (normally none or one). */
    fun update(hands: Array<HandState?>, place: TablePlacement, now: Long): List<TableKeyLayout.Key> {
        hovered.clear(); held.clear()
        if (!place.valid) return emptyList()
        val out = ArrayList<TableKeyLayout.Key>(1)
        for (hi in 0..1) {
            val hand = hands[hi]
            val st = state[hi]
            if (hand == null || !hand.isUsable) { st.forEach { it.clear() }; lastResult[hi] = 0L; continue }
            // where each fingertip is over the keyboard: the smoothed hand drawn on screen
            for (f in 0 until 5) {
                val fs = st[f]
                if (f !in enabled) { fs.clear(); continue }
                place.toLocal(hand.joints[tip(f)], local)
                val key = layout.keyAtSticky(local.x, local.z, fs.over)
                fs.over = if (key != null && (f != 0 || key.kind == TableKeyLayout.Kind.SPACE)) key else null
                if (fs.down) fs.key?.let { held += it.id }
                else if (fs.over != null && local.y - tipRadius < hoverH) hovered += fs.over!!.id
            }
            var cand = -1; var candH = Float.MAX_VALUE
            // taps waiting for the AI depth verdict: the network's frame is the tap's or newer
            for (f in 0 until 5) {
                val fs = st[f]
                if (!fs.waitNn) continue
                if (hand.nnNs >= fs.impactNs - 20_000_000L) {
                    fs.waitNn = false
                    val nh = hand.nnTipHeight[f]
                    if (!nh.isNaN() && nh < nnContactM && fs.over != null && nh < candH) { cand = f; candH = nh }
                } else if (hand.resultNs - fs.impactNs > nnWaitNs) fs.waitNn = false
            }
            if (cand >= 0) { press(st, cand, hand, now, out); cand = -1; candH = Float.MAX_VALUE }
            // contact and impact are judged once per camera result
            if (hand.resultNs == 0L || hand.resultNs == lastResult[hi]) continue
            lastResult[hi] = hand.resultNs
            val nnFresh = useAiDepth && hand.nnNs > 0L && hand.resultNs - hand.nnNs < 400_000_000L
            for (f in 0 until 5) {
                if (f !in enabled) continue
                val fs = st[f]
                val (h, measured) = resultHeight(hand, f, place)
                fs.push(hand.resultNs, h)
                if (fs.down) {
                    if (h > releaseM) { fs.down = false; fs.key = null; fs.peak = h }
                    continue
                }
                fs.peak = if (fs.peak.isNaN()) h else maxOf(fs.peak, h)
                if (fs.over == null || fs.n < 3) { fs.pending = false; continue }
                if (fs.waitNn) continue
                // with AI depth the motion only has to look roughly like a touch: the network decides
                val contact = h < if (measured) contactMeasuredM else if (nnFresh) contactModelM * 2 else contactModelM
                if (fs.pending) {
                    // confirmation of a model-only tap: still on the table and not moving away
                    fs.pending = false
                    val v = (fs.h[0] - fs.h[1]) / ((fs.t[0] - fs.t[1]) / 1e9f).coerceAtLeast(1e-3f)
                    if (contact && v < confirmRiseSpeed && h < candH) { cand = f; candH = h }
                    continue
                }
                val dropped = fs.peak - h >= minDrop
                val dt1 = ((fs.t[1] - fs.t[2]) / 1e9f).coerceAtLeast(1e-3f)
                val dt0 = ((fs.t[0] - fs.t[1]) / 1e9f).coerceAtLeast(1e-3f)
                val vBefore = (fs.h[1] - fs.h[2]) / dt1   // negative = coming down
                val vNow = (fs.h[0] - fs.h[1]) / dt0
                // coming down fast one result earlier, (nearly) stopped now
                val impact = vBefore < -descentSpeed && vNow > vBefore * stopFraction
                if (contact && dropped && impact) {
                    when {
                        // AI depth: the network's next verdict on this finger decides
                        nnFresh -> { fs.waitNn = true; fs.impactNs = hand.resultNs }
                        // measured by the Depth API: typed now
                        measured -> if (h < candH) { cand = f; candH = h }
                        // estimated by the hand model (noisier): one more result must confirm
                        else -> fs.pending = true
                    }
                }
            }
            if (cand >= 0) press(st, cand, hand, now, out)
        }
        return out
    }

    private fun press(st: Array<Finger>, cand: Int, hand: HandState, now: Long, out: MutableList<TableKeyLayout.Key>) {
        val fs = st[cand]
        val key = fs.over ?: return
        fs.down = true; fs.key = key; fs.peak = Float.NaN; fs.waitNn = false
        if (!(key === lastKey && now - lastKeyNs < sameKeyNs)) { out += key; lastKey = key; lastKeyNs = now }
        held += key.id
        // the other fingers of this hand that are on the table too do not type from this touch
        for (f in 0 until 5) if (f != cand && f in enabled) {
            val o = st[f]
            if (!o.down && o.n > 0 && o.h[0] < contactModelM) { o.down = true; o.key = null; o.peak = Float.NaN; o.waitNn = false }
        }
    }
}
