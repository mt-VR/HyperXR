package com.hyperxr.input

import com.hyperxr.handtracking.HandJoint
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
 * Typing on the table with the fingertips. Each finger has its own state: it presses when its tip
 * comes down to the table (after being lifted) and must lift again before it can press another
 * key. Duplicate keys are prevented three ways: a finger types once per touch, the same key is not
 * accepted twice within [sameKeyNs] whichever finger touched it, and when two fingers of one hand
 * land in the same frame only the lower one counts (the neighbour that brushed the table is not a
 * key press).
 */
class TableTyping(val layout: TableKeyLayout) {
    /** Height of the fingertip centre above the table (metres) that counts as touching, and lifted. */
    var pressH = 0.012f
    var releaseH = 0.024f
    /** With depth from the hand-size prior the heights are wider by this factor. */
    var uncertainScale = 1.6f
    /** The finger must have come down at least this much (from its height after lifting). */
    var minDrop = 0.012f
    var sameKeyNs = 140_000_000L
    /** How far above a key a fingertip highlights it. */
    var hoverH = 0.05f
    /** Fingertip radius: the joint is at the centre of the fingertip, the pad touches the table. */
    var tipRadius = 0.007f

    /** The fingers that type: thumb (space), index, middle, ring of each hand. */
    val fingers = intArrayOf(HandJoint.THUMB_TIP, HandJoint.INDEX_TIP, HandJoint.MIDDLE_TIP, HandJoint.RING_TIP)

    private class Finger { var down = false; var peak = Float.NaN; var key: TableKeyLayout.Key? = null; var over: TableKeyLayout.Key? = null }
    private val state = Array(2) { Array(4) { Finger() } }
    private var lastKey: TableKeyLayout.Key? = null
    private var lastKeyNs = Long.MIN_VALUE / 2
    private val local = Vec3()

    /** Keys a fingertip hovers over (for the highlight), and keys currently held down. */
    val hovered = HashSet<String>()
    val held = HashSet<String>()

    fun reset() {
        for (h in state) for (f in h) { f.down = false; f.peak = Float.NaN; f.key = null; f.over = null }
        hovered.clear(); held.clear()
    }

    /**
     * One frame. [hands] holds each hand's joints (null = not tracked). Returns the keys pressed
     * this frame (normally zero or one).
     */
    fun update(hands: Array<Array<Vec3>?>, uncertain: BooleanArray, place: TablePlacement, now: Long): List<TableKeyLayout.Key> {
        hovered.clear(); held.clear()
        if (!place.valid) return emptyList()
        val out = ArrayList<TableKeyLayout.Key>(1)
        for (hi in 0..1) {
            val joints = hands[hi]
            val st = state[hi]
            if (joints == null) { st.forEach { it.down = false; it.peak = Float.NaN; it.key = null }; continue }
            val k = if (uncertain[hi]) uncertainScale else 1f
            var cand = -1; var candH = Float.MAX_VALUE; var candKey: TableKeyLayout.Key? = null
            for ((fi, joint) in fingers.withIndex()) {
                val f = st[fi]
                place.toLocal(joints[joint], local)
                val h = local.y - tipRadius
                val key = layout.keyAtSticky(local.x, local.z, f.over)
                f.over = key
                // the thumb only types the space bar (it rests near it while the fingers type)
                val usable = key != null && (joint != HandJoint.THUMB_TIP || key.kind == TableKeyLayout.Kind.SPACE)
                if (f.down) {
                    f.key?.let { held += it.id }
                    if (h > releaseH * k) { f.down = false; f.peak = h; f.key = null }
                    continue
                }
                f.peak = if (f.peak.isNaN()) h else maxOf(f.peak, h)
                if (usable && h < hoverH) hovered += key!!.id
                if (usable && h < pressH * k && f.peak - h >= minDrop && h < candH) { cand = fi; candH = h; candKey = key }
            }
            if (cand >= 0) {
                val f = st[cand]
                f.down = true; f.key = candKey; f.peak = Float.NaN
                val key = candKey!!
                if (!(key === lastKey && now - lastKeyNs < sameKeyNs)) {
                    out += key; lastKey = key; lastKeyNs = now
                }
                held += key.id
                // the other fingers of this hand that are also on the table now cannot fire from this touch
                for ((fi, other) in st.withIndex()) if (fi != cand) {
                    place.toLocal(joints[fingers[fi]], local)
                    if (local.y - tipRadius < pressH * k) { other.down = true; other.key = null; other.peak = Float.NaN }
                }
            }
        }
        return out
    }
}
