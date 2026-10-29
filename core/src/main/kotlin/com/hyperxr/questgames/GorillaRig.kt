package com.hyperxr.questgames

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Gorilla locomotion, ported from Another Axiom's GorillaLocomotion (the movement of Gorilla Tag,
 * open source under the MIT License, Copyright (c) 2021 Another-Axiom:
 * https://github.com/Another-Axiom/GorillaLocomotion, Player.cs). The algorithm is theirs, line
 * for line; what changes is the engine around it: Unity's Physics.SphereCast and Raycast are
 * done here against the level's shapes ([World.sphereCast]), and the Rigidbody (gravity, the
 * head and body colliders resting on things) is [physics].
 *
 * You move only with your hands. Each hand is a small sphere ([handRadius], Unity's
 * minimumRaycastDistance) that can't go through the world; a hand that touches something stays
 * where it touched while it keeps touching, and the body moves instead. Let go after a push
 * faster than [velocityLimit] and you fly with the average body speed of the last frames times
 * [jumpMultiplier], at most [maxJumpSpeed].
 *
 * World units are metres. The player's tracked head and hands come in relative to the play space;
 * [body] is where that play space is in the world (Unity's transform.position of the player).
 */
class GorillaRig(val world: World) {
    /** Unity's RaycastHit, what this needs of it. */
    class Hit { var distance = 0f; val point = FloatArray(3); val normal = FloatArray(3); var slip = -1f }

    /** Solid shapes of the level. */
    class World {
        /** Axis-aligned boxes: minX, minY, minZ, maxX, maxY, maxZ. */
        val boxes = ArrayList<FloatArray>()
        /** Vertical cylinders (tree trunks): x, z, radius, bottom, top. */
        val trunks = ArrayList<FloatArray>()
        /** Capsules (bars, pipes, rails): ax, ay, az, bx, by, bz, radius. */
        val capsules = ArrayList<FloatArray>()
        /**
         * How much a hand slides on each shape (GorillaLocomotion's Surface.slipPercentage), in the
         * order the shapes were added; a negative value (the default) is "no Surface component".
         */
        private val boxSlip = ArrayList<Float>(); private val trunkSlip = ArrayList<Float>(); private val capsuleSlip = ArrayList<Float>()
        /** Ground height (flat ground at 0 plus whatever the level adds). */
        var ground: (Float, Float) -> Float = { _, _ -> 0f }
        var groundSlip = -1f

        fun box(cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float, slip: Float = -1f) {
            boxes += floatArrayOf(cx - sx / 2, cy - sy / 2, cz - sz / 2, cx + sx / 2, cy + sy / 2, cz + sz / 2)
            boxSlip += slip
        }
        fun trunk(x: Float, z: Float, r: Float, bottom: Float, top: Float, slip: Float = -1f) { trunks += floatArrayOf(x, z, r, bottom, top); trunkSlip += slip }
        fun capsule(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, r: Float, slip: Float = -1f) {
            capsules += floatArrayOf(ax, ay, az, bx, by, bz, r); capsuleSlip += slip
        }

        /**
         * Pushes the sphere at (p, r) out of everything it overlaps; returns true when it touched.
         * [n] gets the push direction of the last contact (the surface normal).
         */
        fun resolve(p: FloatArray, r: Float, n: FloatArray): Boolean {
            var hit = false
            val g = ground(p[0], p[2])
            if (p[1] - r < g) { p[1] = g + r; n[0] = 0f; n[1] = 1f; n[2] = 0f; hit = true }
            for (s in 0 until shapeCount()) {
                val d = sdf(s, p[0], p[1], p[2])
                if (d >= r) continue
                gradient(s, p[0], p[1], p[2], n)
                val push = r - d
                p[0] += n[0] * push; p[1] += n[1] * push; p[2] += n[2] * push
                hit = true
            }
            return hit
        }

        // ---------------------------------------------------------------- shapes as distance fields
        private fun shapeCount() = boxes.size + trunks.size + capsules.size

        /** Signed distance from (x, y, z) to shape [s] (boxes, then trunks, then capsules). */
        internal fun sdf(s: Int, x: Float, y: Float, z: Float): Float {
            var k = s
            if (k < boxes.size) {
                val b = boxes[k]
                val qx = max(b[0] - x, x - b[3]); val qy = max(b[1] - y, y - b[4]); val qz = max(b[2] - z, z - b[5])
                val ox = max(qx, 0f); val oy = max(qy, 0f); val oz = max(qz, 0f)
                return sqrt(ox * ox + oy * oy + oz * oz) + minOf(max(qx, max(qy, qz)), 0f)
            }
            k -= boxes.size
            if (k < trunks.size) {
                val t = trunks[k]
                val dx = x - t[0]; val dz = z - t[1]
                val a = sqrt(dx * dx + dz * dz) - t[2]
                val half = (t[4] - t[3]) / 2
                val b = abs(y - (t[3] + t[4]) / 2) - half
                val oa = max(a, 0f); val ob = max(b, 0f)
                return minOf(max(a, b), 0f) + sqrt(oa * oa + ob * ob)
            }
            k -= trunks.size
            val c = capsules[k]
            val abx = c[3] - c[0]; val aby = c[4] - c[1]; val abz = c[5] - c[2]
            val apx = x - c[0]; val apy = y - c[1]; val apz = z - c[2]
            val l2 = abx * abx + aby * aby + abz * abz
            val h = if (l2 < 1e-9f) 0f else ((apx * abx + apy * aby + apz * abz) / l2).coerceIn(0f, 1f)
            val ex = apx - abx * h; val ey = apy - aby * h; val ez = apz - abz * h
            return sqrt(ex * ex + ey * ey + ez * ez) - c[6]
        }

        private fun slipOf(s: Int): Float {
            var k = s
            if (k < boxes.size) return boxSlip[k]
            k -= boxes.size
            if (k < trunks.size) return trunkSlip[k]
            return capsuleSlip[k - trunks.size]
        }

        /** Shape [s]'s bounds, grown by [r]: does the segment's bounding box reach it? */
        private fun near(s: Int, minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float, r: Float): Boolean {
            var k = s
            if (k < boxes.size) {
                val b = boxes[k]
                return maxX >= b[0] - r && minX <= b[3] + r && maxY >= b[1] - r && minY <= b[4] + r && maxZ >= b[2] - r && minZ <= b[5] + r
            }
            k -= boxes.size
            if (k < trunks.size) {
                val t = trunks[k]; val rr = t[2] + r
                return maxX >= t[0] - rr && minX <= t[0] + rr && maxY >= t[3] - r && minY <= t[4] + r && maxZ >= t[1] - rr && minZ <= t[1] + rr
            }
            val c = capsules[k - trunks.size]; val rr = c[6] + r
            return maxX >= minOf(c[0], c[3]) - rr && minX <= max(c[0], c[3]) + rr && maxY >= minOf(c[1], c[4]) - rr && minY <= max(c[1], c[4]) + rr &&
                maxZ >= minOf(c[2], c[5]) - rr && minZ <= max(c[2], c[5]) + rr
        }

        private fun gradient(s: Int, x: Float, y: Float, z: Float, out: FloatArray) {
            val e = 1e-4f
            out[0] = sdf(s, x + e, y, z) - sdf(s, x - e, y, z)
            out[1] = sdf(s, x, y + e, z) - sdf(s, x, y - e, z)
            out[2] = sdf(s, x, y, z + e) - sdf(s, x, y, z - e)
            val l = sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2])
            if (l < 1e-9f) { out[0] = 0f; out[1] = 1f; out[2] = 0f } else { out[0] /= l; out[1] /= l; out[2] /= l }
        }

        private fun groundNormal(x: Float, z: Float, out: FloatArray) {
            val e = 0.05f
            val gx = (ground(x + e, z) - ground(x - e, z)) / (2 * e); val gz = (ground(x, z + e) - ground(x, z - e)) / (2 * e)
            val l = sqrt(gx * gx + 1f + gz * gz)
            out[0] = -gx / l; out[1] = 1f / l; out[2] = -gz / l
        }

        private val active = ArrayList<Int>()

        /**
         * Unity's Physics.SphereCast: a sphere of [radius] from ([ox], [oy], [oz]) along
         * ([dx], [dy], [dz]) (any length; only its direction counts) up to [maxDistance]. Like
         * Unity, shapes the sphere already overlaps at the start are not hit. [hit] gets the
         * distance travelled, the contact point and the surface normal. radius 0 is a Raycast.
         */
        fun sphereCast(ox: Float, oy: Float, oz: Float, radius: Float, dx: Float, dy: Float, dz: Float, maxDistance: Float, hit: Hit): Boolean {
            val len = sqrt(dx * dx + dy * dy + dz * dz)
            if (len < 1e-9f || maxDistance <= 0f) return false
            val ux = dx / len; val uy = dy / len; val uz = dz / len
            val ex = ox + ux * maxDistance; val ey = oy + uy * maxDistance; val ez = oz + uz * maxDistance
            active.clear()
            val n = shapeCount()
            for (s in 0 until n) {
                if (!near(s, minOf(ox, ex), minOf(oy, ey), minOf(oz, ez), max(ox, ex), max(oy, ey), max(oz, ez), radius)) continue
                if (sdf(s, ox, oy, oz) <= radius) continue
                active += s
            }
            useGround = oy - ground(ox, oz) > radius
            // sphere tracing: step by the distance to the nearest shape, never past a surface
            var t = 0f
            var found = -1f
            for (iter in 0 until 96) {
                val d = gap(ox + ux * t, oy + uy * t, oz + uz * t, radius)
                if (d == Float.MAX_VALUE) return false
                if (d < 2e-5f) { found = t; break }
                t += d
                if (t > maxDistance) break
            }
            if (found < 0f) {
                // sliding along a surface the steps get tiny: sample the rest of the way and close
                // in on the first point that would be inside something
                var free = minOf(t, maxDistance)
                for (j in 1..24) {
                    val tt = free + (maxDistance - free) * j / 24f
                    if (gap(ox + ux * tt, oy + uy * tt, oz + uz * tt, radius) < 0f) {
                        var lo = free + (maxDistance - free) * (j - 1) / 24f; var hi = tt
                        for (k in 0 until 24) { val mid = (lo + hi) / 2; if (gap(ox + ux * mid, oy + uy * mid, oz + uz * mid, radius) < 0f) hi = mid else lo = mid }
                        found = lo; break
                    }
                }
                if (found < 0f) return false
            }
            if (found > maxDistance) return false
            val px = ox + ux * found; val py = oy + uy * found; val pz = oz + uz * found
            gap(px, py, pz, radius)
            hit.distance = found
            if (nearest == -1) { groundNormal(px, pz, hit.normal); hit.slip = groundSlip } else { gradient(nearest, px, py, pz, hit.normal); hit.slip = slipOf(nearest) }
            hit.point[0] = px - hit.normal[0] * radius; hit.point[1] = py - hit.normal[1] * radius; hit.point[2] = pz - hit.normal[2] * radius
            return true
        }

        private var useGround = false
        private var nearest = -2

        /** Distance from a sphere at (x, y, z) to the nearest shape in the cast (MAX_VALUE: none); [nearest] gets which. */
        private fun gap(x: Float, y: Float, z: Float, radius: Float): Float {
            var best = Float.MAX_VALUE; nearest = -2
            if (useGround) { val g = y - ground(x, z) - radius; if (g < best) { best = g; nearest = -1 } }
            for (s in active) { val d = sdf(s, x, y, z) - radius; if (d < best) { best = d; nearest = s } }
            return best
        }

        fun raycast(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxDistance: Float, hit: Hit) = sphereCast(ox, oy, oz, 0f, dx, dy, dz, maxDistance, hit)
    }

    // GorillaLocomotion's tuning (the values of its sample player), and the colliders
    var velocityHistorySize = 3; set(v) { field = v.coerceAtLeast(1); resetVelocities() }
    /** maxArmLength: a hand is never further from the head than this. */
    var armLength = 1.5f
    var unStickDistance = 1f
    var velocityLimit = 0.4f
    var maxJumpSpeed = 6.5f
    var jumpMultiplier = 1.1f
    /** minimumRaycastDistance: the hand sphere's radius. */
    var handRadius = 0.05f
    var defaultSlideFactor = 0.03f
    var defaultPrecision = 0.995f
    var gravity = 9.8f
    /** The head collider. */
    var headRadius = 0.15f
    /** The body collider: a sphere [bodyDrop] below the head; what you stand on when your hands don't hold you. */
    var bodyRadius = 0.28f
    var bodyDrop = 0.35f
    /** How much the body keeps of its sideways speed per 1/72 s while it rests on the ground (friction). */
    var groundFriction = 0.82f

    /** Where the play space is in the world (the player's transform). */
    val body = floatArrayOf(0f, 0f, 0f)
    /** The Rigidbody's velocity. */
    val velocity = floatArrayOf(0f, 0f, 0f)
    /** The hand followers: where each hand is drawn (where it touched, while it touches). */
    val hand = Array(2) { FloatArray(3) }
    val touching = BooleanArray(2)
    /** The surface normal under each touching hand. */
    val normal = Array(2) { FloatArray(3) }
    var grounded = false; private set
    /** Set for one update when a hand starts touching (for a tap sound / haptics). */
    val tapped = BooleanArray(2)
    var disableMovement = false

    private val lastHand = Array(2) { FloatArray(3) }
    private val lastHead = FloatArray(3)
    private val lastPosition = FloatArray(3)
    private var velocityHistory = Array(3) { FloatArray(3) }
    private var velocityIndex = 0
    private val denormalizedVelocityAverage = FloatArray(3)
    private var initialized = false
    private val active = BooleanArray(2)
    private val ghost = BooleanArray(2)

    private val headLocal = FloatArray(3)
    private val handsLocal = Array(2) { FloatArray(3) }
    private val hit = Hit(); private val inner = Hit()

    private fun resetVelocities() {
        velocityHistory = Array(velocityHistorySize) { FloatArray(3) }
        velocityIndex = 0; denormalizedVelocityAverage.fill(0f)
    }

    // ---------------------------------------------------------------- the player's transforms
    private fun head(out: FloatArray) { for (k in 0..2) out[k] = headLocal[k] + body[k] }

    /** CurrentLeftHandPosition / CurrentRightHandPosition: the hand, within [armLength] of the head. */
    private fun currentHand(i: Int, out: FloatArray) {
        val hx = headLocal[0] + body[0]; val hy = headLocal[1] + body[1]; val hz = headLocal[2] + body[2]
        val px = handsLocal[i][0] + body[0]; val py = handsLocal[i][1] + body[1]; val pz = handsLocal[i][2] + body[2]
        val dx = px - hx; val dy = py - hy; val dz = pz - hz
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        if (d < armLength) { out[0] = px; out[1] = py; out[2] = pz }
        else { val s = armLength / d; out[0] = hx + dx * s; out[1] = hy + dy * s; out[2] = hz + dz * s }
    }

    /**
     * One frame: Unity's physics step for the Rigidbody, then Player.Update. [headLocal] and the
     * hands are relative to the play space; a hand that isn't there (null) neither touches nor
     * moves you, and is drawn at the head.
     */
    fun step(dt: Float, head: FloatArray, hands: Array<FloatArray?>) {
        if (dt <= 0f) return
        head.copyInto(headLocal)
        for (i in 0..1) {
            val h = hands[i]
            val was = active[i]
            active[i] = h != null
            if (h != null) h.copyInto(handsLocal[i]) else headLocal.copyInto(handsLocal[i])
            // a hand that (re)appears starts where it is, not swept from where it was last seen; one
            // that appears inside something passes through until it is out (no launch)
            if (h != null && !was && initialized) {
                currentHand(i, lastHand[i]); touching[i] = false
                ghost[i] = world.resolve(lastHand[i].copyOf(), handRadius, pn)
            }
            if (ghost[i] && h != null) {
                currentHand(i, cur)
                if (!world.resolve(cur, handRadius, pn)) ghost[i] = false
            }
        }
        if (!initialized) {
            initialized = true
            for (i in 0..1) currentHand(i, lastHand[i])
            head(lastHead)
            body.copyInto(lastPosition)
            resetVelocities()
        }
        physics(dt)
        update(dt)
    }

    private val cur = FloatArray(3); private val fin = FloatArray(3)
    private val first = Array(2) { FloatArray(3) }
    private val move = FloatArray(3); private val headNow = FloatArray(3); private val v = FloatArray(3)

    /** Player.Update. */
    private val slick = BooleanArray(2)

    private fun update(dt: Float) {
        val colliding = BooleanArray(2)
        slick.fill(false)
        for (i in 0..1) first[i].fill(0f)
        // each hand: did it hit something on its way from where it was? (with a little gravity, so
        // a hand resting on the ground stays on it)
        for (i in 0..1) {
            tapped[i] = false
            if (!active[i] || ghost[i]) continue
            currentHand(i, cur)
            for (k in 0..2) v[k] = cur[k] - lastHand[i][k]
            v[1] -= 2f * gravity * dt * dt
            if (iterativeCollisionSphereCast(lastHand[i], handRadius, v, defaultPrecision, fin, true)) {
                // HyperMagic's addition: a surface that slips fully ([NO_GRIP]) stops the hand but
                // gives it nothing to push or pull on
                if (hit.slip >= NO_GRIP) { slick[i] = true; continue }
                // this lets you stick to the position you touch: as long as you keep touching the
                // surface this is the zero point for that hand
                if (touching[i]) for (k in 0..2) first[i][k] = lastHand[i][k] - cur[k]
                else for (k in 0..2) first[i][k] = fin[k] - cur[k]
                velocity.fill(0f)
                colliding[i] = true
                hit.normal.copyInto(normal[i])
            }
        }
        // average or add
        val both = (colliding[0] || touching[0]) && (colliding[1] || touching[1])
        for (k in 0..2) move[k] = if (both) (first[0][k] + first[1][k]) / 2 else first[0][k] + first[1][k]
        // check valid head movement
        head(headNow)
        for (k in 0..2) v[k] = headNow[k] + move[k] - lastHead[k]
        if (iterativeCollisionSphereCast(lastHead, headRadius, v, defaultPrecision, fin, false)) {
            for (k in 0..2) move[k] = fin[k] - lastHead[k]
            // last check to make sure the head won't phase through geometry
            for (k in 0..2) v[k] = headNow[k] - lastHead[k] + move[k]
            val l = len(v)
            if (world.raycast(lastHead[0], lastHead[1], lastHead[2], v[0], v[1], v[2], l + headRadius * defaultPrecision * 0.999f, inner)) {
                for (k in 0..2) move[k] = lastHead[k] - headNow[k]
            }
        }
        if (move[0] != 0f || move[1] != 0f || move[2] != 0f) for (k in 0..2) body[k] += move[k]
        head(lastHead)
        // final hand positions
        for (i in 0..1) {
            if (!active[i] || ghost[i]) { currentHand(i, lastHand[i]); continue }
            currentHand(i, cur)
            for (k in 0..2) v[k] = cur[k] - lastHand[i][k]
            if (iterativeCollisionSphereCast(lastHand[i], handRadius, v, defaultPrecision, fin, !both)) {
                fin.copyInto(lastHand[i])
                if (hit.slip < NO_GRIP) colliding[i] = true
            } else cur.copyInto(lastHand[i])
        }
        storeVelocities(dt)
        if ((colliding[0] || colliding[1]) && !disableMovement) {
            val m = len(denormalizedVelocityAverage)
            if (m > velocityLimit) {
                if (m * jumpMultiplier > maxJumpSpeed) for (k in 0..2) velocity[k] = denormalizedVelocityAverage[k] / m * maxJumpSpeed
                else for (k in 0..2) velocity[k] = jumpMultiplier * denormalizedVelocityAverage[k]
            }
        }
        // a hand stuck far behind (more than unStickDistance) with nothing between it and the head lets go
        for (i in 0..1) {
            if (!colliding[i] || !active[i]) continue
            currentHand(i, cur)
            for (k in 0..2) v[k] = cur[k] - lastHand[i][k]
            if (len(v) <= unStickDistance) continue
            head(headNow)
            for (k in 0..2) v[k] = cur[k] - headNow[k]
            if (!world.sphereCast(headNow[0], headNow[1], headNow[2], handRadius * defaultPrecision, v[0], v[1], v[2], len(v) - handRadius, inner)) {
                cur.copyInto(lastHand[i]); colliding[i] = false
            }
        }
        for (i in 0..1) {
            lastHand[i].copyInto(hand[i])
            tapped[i] = colliding[i] && !touching[i]
            touching[i] = colliding[i]
        }
    }

    private val ep = FloatArray(3); private val mp = FloatArray(3); private val sp = FloatArray(3); private val firstPos = FloatArray(3)

    /** Player.IterativeCollisionSphereCast: where a sphere moving by [movement] ends, with a little slide along what it hits. */
    private fun iterativeCollisionSphereCast(start: FloatArray, radius: Float, movement: FloatArray, precision: Float, end: FloatArray, singleHand: Boolean): Boolean {
        if (collisionsSphereCast(start, radius * precision, movement, precision, end)) {
            // a bit of a slide: so two hands don't stick 100%, and a hand pushing along a surface
            // while braced with the head slides a little
            end.copyInto(firstPos)
            val slip = if (hit.slip >= 0f) hit.slip else if (!singleHand) defaultSlideFactor else 0.001f
            // ProjectOnPlane(start + movement - first, normal) * slip
            for (k in 0..2) mp[k] = start[k] + movement[k] - firstPos[k]
            val dn = mp[0] * hit.normal[0] + mp[1] * hit.normal[1] + mp[2] * hit.normal[2]
            for (k in 0..2) mp[k] = (mp[k] - dn * hit.normal[k]) * slip
            if (collisionsSphereCast(end.copyOf(), radius, mp, precision * precision, end)) return true
            for (k in 0..2) { sp[k] = mp[k] + firstPos[k]; ep[k] = start[k] + movement[k] - sp[k] }
            if (collisionsSphereCast(sp.copyOf(), radius, ep, precision * precision * precision, end)) return true
            firstPos.copyInto(end)
            return true
        }
        // a sanity check with a smaller sphere, for when the first one already touched a surface
        val l = len(movement)
        if (l > 1e-9f) {
            val s = (l + radius * precision * 0.34f) / l
            for (k in 0..2) ep[k] = movement[k] * s
            if (collisionsSphereCast(start, radius * precision * 0.66f, ep, precision * 0.66f, end)) { start.copyInto(end); return true }
        }
        end.fill(0f)
        return false
    }

    private val fp = FloatArray(3); private val dv = FloatArray(3)

    /** Player.CollisionsSphereCast: a sphere cast that keeps the sphere a full radius off the surface it reaches. */
    private fun collisionsSphereCast(start: FloatArray, radius: Float, movement: FloatArray, precision: Float, final: FloatArray): Boolean {
        val l = len(movement)
        if (world.sphereCast(start[0], start[1], start[2], radius * precision, movement[0], movement[1], movement[2], l + radius * (1 - precision), hit)) {
            // a radius away from the surface along its normal
            for (k in 0..2) fp[k] = hit.point[k] + hit.normal[k] * radius
            for (k in 0..2) dv[k] = fp[k] - start[k]
            val dl = len(dv)
            if (world.sphereCast(start[0], start[1], start[2], radius * precision * precision, dv[0], dv[1], dv[2], dl + radius * (1 - precision * precision), inner)) {
                val s = if (dl > 1e-9f) max(0f, hit.distance - radius * (1f - precision * precision)) / dl else 0f
                for (k in 0..2) final[k] = start[k] + dv[k] * s
                copyHit(inner, hit)
            } else if (world.raycast(start[0], start[1], start[2], dv[0], dv[1], dv[2], dl + radius * precision * precision * 0.999f, inner)) {
                start.copyInto(final)
                copyHit(inner, hit)
                return true
            } else fp.copyInto(final)
            return true
        }
        // anti-clipping through geometry
        if (world.raycast(start[0], start[1], start[2], movement[0], movement[1], movement[2], l + radius * precision * 0.999f, hit)) {
            start.copyInto(final)
            return true
        }
        final.fill(0f)
        return false
    }

    private fun copyHit(from: Hit, to: Hit) { to.distance = from.distance; from.point.copyInto(to.point); from.normal.copyInto(to.normal); to.slip = from.slip }

    /** Player.StoreVelocities: the body's speed over the last frames. */
    private fun storeVelocities(dt: Float) {
        velocityIndex = (velocityIndex + 1) % velocityHistory.size
        val oldest = velocityHistory[velocityIndex]
        for (k in 0..2) {
            val current = (body[k] - lastPosition[k]) / dt
            denormalizedVelocityAverage[k] += (current - oldest[k]) / velocityHistory.size
            oldest[k] = current
        }
        body.copyInto(lastPosition)
    }

    // ---------------------------------------------------------------- the Rigidbody
    private val pc = FloatArray(3); private val pn = FloatArray(3); private val step = FloatArray(3)

    /**
     * What Unity's physics does to the player between updates: gravity, then the move, stopped and
     * slid along whatever the head or body collider runs into; resting on the ground, friction.
     */
    private fun physics(dt: Float) {
        velocity[1] -= gravity * dt
        for (k in 0..2) step[k] = velocity[k] * dt
        grounded = false
        for (iter in 0 until 3) {
            val l = len(step)
            if (l < 1e-7f) break
            var travel = l; var hitN: FloatArray? = null
            for (c in 0..1) {
                colliderCentre(c, pc)
                val r = if (c == 0) headRadius else bodyRadius
                if (world.sphereCast(pc[0], pc[1], pc[2], r, step[0], step[1], step[2], l, inner) && inner.distance < travel) {
                    travel = max(0f, inner.distance - 0.002f); hitN = inner.normal.copyOf()
                }
            }
            for (k in 0..2) body[k] += step[k] / l * travel
            val n = hitN ?: break
            // what is left of the move, and the speed, slide along the surface
            for (k in 0..2) step[k] = step[k] / l * (l - travel)
            val sn = step[0] * n[0] + step[1] * n[1] + step[2] * n[2]
            if (sn < 0f) for (k in 0..2) step[k] -= sn * n[k]
            val vn = velocity[0] * n[0] + velocity[1] * n[1] + velocity[2] * n[2]
            if (vn < 0f) for (k in 0..2) velocity[k] -= vn * n[k]
            if (n[1] > 0.6f) grounded = true
        }
        // the colliders never stay inside the world (the real head can walk into a wall)
        for (c in 0..1) {
            colliderCentre(c, pc)
            val before = pc.copyOf()
            if (world.resolve(pc, if (c == 0) headRadius else bodyRadius, pn)) {
                for (k in 0..2) body[k] += pc[k] - before[k]
                val vn = velocity[0] * pn[0] + velocity[1] * pn[1] + velocity[2] * pn[2]
                if (vn < 0f) for (k in 0..2) velocity[k] -= vn * pn[k]
                if (pn[1] > 0.6f) grounded = true
            }
        }
        // resting on something: is it right under the body?
        colliderCentre(1, pc)
        if (!grounded && velocity[1] <= 0f && world.sphereCast(pc[0], pc[1], pc[2], bodyRadius, 0f, -1f, 0f, 0.01f, inner) && inner.normal[1] > 0.6f) grounded = true
        if (grounded) {
            val keep = Math.pow(groundFriction.toDouble(), (dt * 72f).toDouble()).toFloat()
            velocity[0] *= keep; velocity[2] *= keep
        }
    }

    private fun colliderCentre(c: Int, out: FloatArray) {
        out[0] = headLocal[0] + body[0]; out[1] = headLocal[1] + body[1] - (if (c == 0) 0f else bodyDrop); out[2] = headLocal[2] + body[2]
    }

    private fun len(a: FloatArray) = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])

    companion object {
        /** A surface slip of this or more: the hand is stopped by it but can't hold or push on it. */
        const val NO_GRIP = 1f
    }

    /** Puts the player at [x, y, z] standing still. */
    fun teleport(x: Float, y: Float, z: Float) {
        body[0] = x; body[1] = y; body[2] = z
        velocity.fill(0f)
        touching.fill(false); ghost.fill(false)
        initialized = false
    }
}
