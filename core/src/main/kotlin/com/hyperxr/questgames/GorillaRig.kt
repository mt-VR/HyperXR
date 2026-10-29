package com.hyperxr.questgames

import kotlin.math.sqrt

/**
 * Gorilla locomotion: no legs, no stick. You move by pushing the ground and the walls with your
 * hands. Each hand is a small sphere that can't go into the world: when your real hand goes into
 * the ground, the body moves instead (hand stays, you rise). A hand on a surface sticks until you
 * pull it away, so sweeping it along the floor walks you. Let go after a fast push and you fly with
 * the body's speed (capped, so camera hand-tracking shake doesn't throw you around).
 *
 * World units are metres. The player's tracked head and hands come in relative to the play space;
 * [body] is where that play space is in the world.
 */
class GorillaRig(val world: World) {
    /** Solid shapes of the level. */
    class World {
        /** Axis-aligned boxes: minX, minY, minZ, maxX, maxY, maxZ. */
        val boxes = ArrayList<FloatArray>()
        /** Vertical cylinders (tree trunks): x, z, radius, bottom, top. */
        val trunks = ArrayList<FloatArray>()
        /** Ground height (flat ground at 0 plus whatever the level adds). */
        var ground: (Float, Float) -> Float = { _, _ -> 0f }

        fun box(cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float) {
            boxes += floatArrayOf(cx - sx / 2, cy - sy / 2, cz - sz / 2, cx + sx / 2, cy + sy / 2, cz + sz / 2)
        }
        fun trunk(x: Float, z: Float, r: Float, bottom: Float, top: Float) { trunks += floatArrayOf(x, z, r, bottom, top) }

        /**
         * Pushes the sphere at (p, r) out of everything it overlaps; returns true when it touched.
         * [n] gets the push direction of the last contact (the surface normal).
         */
        fun resolve(p: FloatArray, r: Float, n: FloatArray): Boolean {
            var hit = false
            val g = ground(p[0], p[2])
            if (p[1] - r < g) { p[1] = g + r; n[0] = 0f; n[1] = 1f; n[2] = 0f; hit = true }
            for (b in boxes) {
                val cx = p[0].coerceIn(b[0], b[3]); val cy = p[1].coerceIn(b[1], b[4]); val cz = p[2].coerceIn(b[2], b[5])
                var dx = p[0] - cx; var dy = p[1] - cy; var dz = p[2] - cz
                val d2 = dx * dx + dy * dy + dz * dz
                if (d2 >= r * r) continue
                if (d2 > 1e-10f) {
                    val d = sqrt(d2); dx /= d; dy /= d; dz /= d
                    p[0] = cx + dx * r; p[1] = cy + dy * r; p[2] = cz + dz * r
                    n[0] = dx; n[1] = dy; n[2] = dz
                } else {
                    // centre inside the box: leave through the nearest face
                    val faces = floatArrayOf(p[0] - b[0], b[3] - p[0], p[1] - b[1], b[4] - p[1], p[2] - b[2], b[5] - p[2])
                    var k = 0; for (i in 1..5) if (faces[i] < faces[k]) k = i
                    n[0] = 0f; n[1] = 0f; n[2] = 0f
                    when (k) {
                        0 -> { p[0] = b[0] - r; n[0] = -1f }; 1 -> { p[0] = b[3] + r; n[0] = 1f }
                        2 -> { p[1] = b[1] - r; n[1] = -1f }; 3 -> { p[1] = b[4] + r; n[1] = 1f }
                        4 -> { p[2] = b[2] - r; n[2] = -1f }; else -> { p[2] = b[5] + r; n[2] = 1f }
                    }
                }
                hit = true
            }
            for (t in trunks) {
                if (p[1] < t[3] - r || p[1] > t[4] + r) continue
                var dx = p[0] - t[0]; var dz = p[2] - t[1]
                val d = sqrt(dx * dx + dz * dz)
                val rr = t[2] + r
                if (d >= rr) continue
                if (d > 1e-5f) { dx /= d; dz /= d } else { dx = 1f; dz = 0f }
                // over the top of a stump: stand on it
                if (p[1] > t[4] && p[1] - t[4] < rr - d) { p[1] = t[4] + r; n[0] = 0f; n[1] = 1f; n[2] = 0f }
                else { p[0] = t[0] + dx * rr; p[2] = t[1] + dz * rr; n[0] = dx; n[1] = 0f; n[2] = dz }
                hit = true
            }
            return hit
        }
    }

    // tuning (VR Box defaults: see also the GorillaCraft profile)
    var armLength = 1.4f
    var handRadius = 0.09f
    var jumpMultiplier = 1.15f
    var maxJumpSpeed = 5.5f
    var velocityLimit = 0.45f
    var gravity = 9.81f
    var groundFriction = 0.82f
    var unstick = 0.04f
    var bodyRadius = 0.25f

    /** Where the play space is in the world. */
    val body = floatArrayOf(0f, 0f, 0f)
    val velocity = floatArrayOf(0f, 0f, 0f)
    /** Hands in the world (after collision), and whether each touches something. */
    val hand = Array(2) { FloatArray(3) }
    val touching = BooleanArray(2)
    val normal = Array(2) { FloatArray(3) }
    private val stuck = BooleanArray(2)
    private val history = ArrayList<FloatArray>()
    var grounded = false; private set
    /** Set for one update when a hand starts touching (for a tap sound / haptics). */
    val tapped = BooleanArray(2)

    private val desired = FloatArray(3)
    private val head = FloatArray(3)
    private val tmp = FloatArray(3)
    private val tmpN = FloatArray(3)

    /**
     * One step. [headLocal] and the hands are relative to the play space; a hand that isn't
     * tracked is null (it lets go). Returns nothing; read [body], [hand], [touching].
     */
    fun step(dt: Float, headLocal: FloatArray, handsLocal: Array<FloatArray?>) {
        if (dt <= 0f) return
        for (k in 0..2) head[k] = headLocal[k] + body[k]
        val before = body.copyOf()
        var corrX = 0f; var corrY = 0f; var corrZ = 0f; var n = 0
        for (i in 0..1) {
            tapped[i] = false
            val local = handsLocal[i]
            if (local == null) { touching[i] = false; stuck[i] = false; continue }
            for (k in 0..2) desired[k] = local[k] + body[k]
            // arms have a length: the hand stays within reach of the head
            val dx = desired[0] - head[0]; val dy = desired[1] - head[1]; val dz = desired[2] - head[2]
            val len = sqrt(dx * dx + dy * dy + dz * dz)
            if (len > armLength) { val s = armLength / len; desired[0] = head[0] + dx * s; desired[1] = head[1] + dy * s; desired[2] = head[2] + dz * s }
            val target: FloatArray
            if (stuck[i]) {
                // a stuck hand stays where it touched until it moves away from the surface
                val away = (desired[0] - hand[i][0]) * normal[i][0] + (desired[1] - hand[i][1]) * normal[i][1] + (desired[2] - hand[i][2]) * normal[i][2]
                desired.copyInto(tmp)
                val free = !world.resolve(tmp, handRadius, tmpN)
                if (free && away > unstick) { stuck[i] = false; touching[i] = false; target = desired.copyOf() }
                else target = hand[i].copyOf()
            } else {
                desired.copyInto(tmp)
                if (world.resolve(tmp, handRadius, tmpN)) {
                    stuck[i] = true; touching[i] = true; tapped[i] = true
                    tmpN.copyInto(normal[i])
                    target = tmp.copyOf()
                } else { touching[i] = false; target = desired.copyOf() }
            }
            target.copyInto(hand[i])
            if (stuck[i]) {
                // the body moves so the real hand matches the planted one
                corrX += target[0] - desired[0]; corrY += target[1] - desired[1]; corrZ += target[2] - desired[2]; n++
            }
        }
        if (n > 0) {
            body[0] += corrX / n; body[1] += corrY / n; body[2] += corrZ / n
            for (k in 0..2) velocity[k] = 0f
            // the snap when a hand first lands on a surface is not a push
            if (!tapped[0] && !tapped[1]) history += floatArrayOf((body[0] - before[0]) / dt, (body[1] - before[1]) / dt, (body[2] - before[2]) / dt)
            while (history.size > 4) history.removeAt(0)
            grounded = false
        } else {
            if (history.isNotEmpty()) {
                // just let go: throw with the average push speed, if it was a real push
                val v = FloatArray(3)
                for (h in history) for (k in 0..2) v[k] += h[k] / history.size
                val speed = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                if (speed > velocityLimit) {
                    val s = minOf(speed * jumpMultiplier, maxJumpSpeed) / speed
                    for (k in 0..2) velocity[k] = v[k] * s
                }
                history.clear()
            }
            velocity[1] -= gravity * dt
            for (k in 0..2) body[k] += velocity[k] * dt
        }
        // the chest (under the head) can't go into the world either
        val cx = headLocal[0] + body[0]; val cy = headLocal[1] + body[1] - 0.35f; val cz = headLocal[2] + body[2]
        tmp[0] = cx; tmp[1] = cy; tmp[2] = cz
        if (world.resolve(tmp, bodyRadius, tmpN)) {
            body[0] += tmp[0] - cx; body[1] += tmp[1] - cy; body[2] += tmp[2] - cz
            val vn = velocity[0] * tmpN[0] + velocity[1] * tmpN[1] + velocity[2] * tmpN[2]
            if (vn < 0f) for (k in 0..2) velocity[k] -= vn * tmpN[k]
        }
        // feet on the ground
        val g = world.ground(headLocal[0] + body[0], headLocal[2] + body[2])
        grounded = body[1] <= g + 0.001f
        if (body[1] < g) { body[1] = g; if (velocity[1] < 0f) velocity[1] = 0f }
        if (grounded && n == 0) { velocity[0] *= groundFriction; velocity[2] *= groundFriction }
    }

    /** Puts the player at [x, y, z] standing still. */
    fun teleport(x: Float, y: Float, z: Float) {
        body[0] = x; body[1] = y; body[2] = z
        for (k in 0..2) velocity[k] = 0f
        history.clear(); stuck.fill(false); touching.fill(false)
    }
}
