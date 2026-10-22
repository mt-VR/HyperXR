package com.hyperxr.physics

import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Rigid-body physics for the Hand Physics Lab: spheres, boxes and capsules with real mass and
 * inertia, Coulomb friction, restitution, multi-point box contacts (so stacks rest flat), sleeping,
 * kinematic bodies driven by the tracked hands, and force-limited grabbing (a heavy object lags
 * behind the hand, a light one follows it). Sequential-impulse solver with sub-stepping.
 * Units: metres, kilograms, seconds.
 */
enum class ShapeType { SPHERE, BOX, CAPSULE }

/** [halfExtents] for boxes; [radius] for spheres and capsules; capsule axis is local Y with [halfHeight]. */
class Shape private constructor(val type: ShapeType, val radius: Float, val halfExtents: Vec3, val halfHeight: Float) {
    companion object {
        fun sphere(r: Float) = Shape(ShapeType.SPHERE, r, Vec3(r, r, r), 0f)
        fun box(hx: Float, hy: Float, hz: Float) = Shape(ShapeType.BOX, 0f, Vec3(hx, hy, hz), 0f)
        fun capsule(r: Float, halfHeight: Float) = Shape(ShapeType.CAPSULE, r, Vec3(r, halfHeight + r, r), halfHeight)
    }

    fun volume(): Float = when (type) {
        ShapeType.SPHERE -> 4f / 3f * PI * radius * radius * radius
        ShapeType.BOX -> 8f * halfExtents.x * halfExtents.y * halfExtents.z
        ShapeType.CAPSULE -> PI * radius * radius * (2f * halfHeight) + 4f / 3f * PI * radius * radius * radius
    }

    /** Bounding radius around the centre (broad phase). */
    fun boundingRadius(): Float = when (type) {
        ShapeType.SPHERE -> radius
        ShapeType.BOX -> halfExtents.length()
        ShapeType.CAPSULE -> halfHeight + radius
    }
}

/** Density kg/m³, Coulomb friction coefficient, restitution (bounciness). */
data class Material(val name: String, val density: Float, val friction: Float, val restitution: Float) {
    companion object {
        val WOOD = Material("madeira", 650f, 0.55f, 0.25f)
        val RUBBER = Material("borracha", 1100f, 0.95f, 0.78f)
        val STEEL = Material("aço", 7850f, 0.35f, 0.15f)
        val ALUMINIUM = Material("alumínio", 2700f, 0.4f, 0.2f)
        val PLASTIC = Material("plástico", 1050f, 0.4f, 0.55f)
        val CELLULOID = Material("celuloide", 85f, 0.3f, 0.86f)  // ping-pong: hollow, effective density
        val FOAM = Material("espuma", 60f, 0.8f, 0.3f)
        val HAND = Material("mão", 1000f, 0.9f, 0.1f)
    }
}

class Body(val shape: Shape, val material: Material, val type: Type = Type.DYNAMIC, massOverride: Float = 0f) {
    enum class Type { DYNAMIC, STATIC, KINEMATIC }

    val position = Vec3()
    val orientation = Quat()
    val linearVelocity = Vec3()
    val angularVelocity = Vec3()
    /** Split-impulse pseudo velocities: correct penetration without adding momentum. */
    internal val pseudoLinear = Vec3()
    internal val pseudoAngular = Vec3()
    val mass: Float = if (type == Type.DYNAMIC) (if (massOverride > 0f) massOverride else shape.volume() * material.density) else 0f
    val invMass: Float = if (mass > 0f) 1f / mass else 0f
    /** Inverse inertia in body space (diagonal). */
    val invInertiaLocal = Vec3()
    /** Inverse inertia in world space, row-major 3x3. */
    val invInertiaWorld = FloatArray(9)
    var sleeping = false
    internal var sleepTime = 0f
    /** Bodies in the same non-zero group never collide (e.g. the bones of one hand). */
    var group = 0
    /** A body held by a hand does not collide with that hand's colliders. */
    var ignoreGroup = 0
    var userData: Any? = null
    var id = 0
    /** Peak impact speed of the last step (for sounds), cleared by the reader. */
    var lastImpact = 0f
    val rot = FloatArray(9)
    var enabled = true
    internal var islandIndex = 0

    init {
        if (type == Type.DYNAMIC) {
            val m = mass
            val h = shape.halfExtents
            val ix: Float; val iy: Float; val iz: Float
            when (shape.type) {
                ShapeType.SPHERE -> { val i = 0.4f * m * shape.radius * shape.radius; ix = i; iy = i; iz = i }
                ShapeType.BOX -> {
                    ix = m / 3f * (h.y * h.y + h.z * h.z); iy = m / 3f * (h.x * h.x + h.z * h.z); iz = m / 3f * (h.x * h.x + h.y * h.y)
                }
                ShapeType.CAPSULE -> {
                    // cylinder + hemispheres, about the centre
                    val r = shape.radius; val hh = shape.halfHeight
                    val vc = PI * r * r * 2 * hh; val vs = 4f / 3f * PI * r * r * r
                    val mc = m * vc / (vc + vs); val ms = m - mc
                    iy = mc * r * r / 2f + ms * 0.4f * r * r
                    val ic = mc * (3 * r * r + 4 * hh * hh) / 12f
                    val isph = ms * (0.4f * r * r + hh * hh + 0.75f * hh * r)
                    ix = ic + isph; iz = ix
                }
            }
            invInertiaLocal.set(1f / ix, 1f / iy, 1f / iz)
        }
        updateDerived()
    }

    val isDynamic: Boolean get() = type == Type.DYNAMIC
    /** Moves in the solver: a sleeping body acts as immovable until it is woken. */
    internal val active: Boolean get() = type == Type.DYNAMIC && !sleeping

    fun wake() { if (isDynamic) { sleeping = false; sleepTime = 0f } }

    internal fun updateDerived() {
        val q = orientation
        val x = q.x; val y = q.y; val z = q.z; val w = q.w
        rot[0] = 1 - 2 * (y * y + z * z); rot[1] = 2 * (x * y - w * z); rot[2] = 2 * (x * z + w * y)
        rot[3] = 2 * (x * y + w * z); rot[4] = 1 - 2 * (x * x + z * z); rot[5] = 2 * (y * z - w * x)
        rot[6] = 2 * (x * z - w * y); rot[7] = 2 * (y * z + w * x); rot[8] = 1 - 2 * (x * x + y * y)
        if (!isDynamic) { invInertiaWorld.fill(0f); return }
        // R * diag(I^-1) * R^T
        val d = invInertiaLocal
        for (r in 0..2) for (c in 0..2) {
            invInertiaWorld[r * 3 + c] = rot[r * 3] * d.x * rot[c * 3] + rot[r * 3 + 1] * d.y * rot[c * 3 + 1] + rot[r * 3 + 2] * d.z * rot[c * 3 + 2]
        }
    }

    /** World axis [i] of the body (column i of the rotation). */
    fun axis(i: Int, out: Vec3): Vec3 = out.set(rot[i], rot[3 + i], rot[6 + i])

    fun applyInvInertia(v: Vec3, out: Vec3): Vec3 {
        val m = invInertiaWorld
        return out.set(m[0] * v.x + m[1] * v.y + m[2] * v.z, m[3] * v.x + m[4] * v.y + m[5] * v.z, m[6] * v.x + m[7] * v.y + m[8] * v.z)
    }

    fun teleport(p: Vec3, q: Quat) {
        position.set(p); orientation.set(q).normalize()
        linearVelocity.set(0f, 0f, 0f); angularVelocity.set(0f, 0f, 0f)
        updateDerived(); wake()
    }
}

/** A contact point between two bodies; [normal] points from [a] to [b]. */
class Contact {
    var a: Body? = null
    var b: Body? = null
    val point = Vec3()
    val normal = Vec3()
    var depth = 0f
    // solver data
    internal val ra = Vec3(); internal val rb = Vec3()
    internal val t1 = Vec3(); internal val t2 = Vec3()
    internal var massN = 0f; internal var massT1 = 0f; internal var massT2 = 0f
    internal var bias = 0f
    internal var posBias = 0f
    internal var jp = 0f
    internal var jn = 0f; internal var jt1 = 0f; internal var jt2 = 0f
    internal var friction = 0f
}

/** A hand (or cursor) holding a body: force-limited PD drive towards a target pose. */
class Hold(val body: Body) {
    val targetPosition = Vec3()
    val targetOrientation = Quat()
    /** Velocity of the holder, so a moving hand carries the object without lag at constant speed. */
    val holderVelocity = Vec3()
    /** Maximum force of the grip (N): heavy objects sag and lag, as they would in a real hand. */
    var maxForce = 45f
    var maxTorque = 1.2f
    var stiffness = 900f  // N/m per kg-equivalent, critically damped below
    var angularStiffness = 400f
}

class RigidWorld {
    val gravity = Vec3(0f, -9.81f, 0f)
    val bodies = ArrayList<Body>()
    val holds = ArrayList<Hold>()
    var substeps = 16
    var iterations = 10
    var timeScale = 1f
    /** Allowed penetration before position correction kicks in (m). */
    var slop = 0.0003f
    var baumgarte = 0.5f
    var positionIterations = 8
    var sleepLinear = 0.02f
    var sleepAngular = 0.05f
    var sleepAfter = 0.5f
    private var nextId = 1

    private val contacts = ArrayList<Contact>()
    // impulse cache of the previous sub-step, for warm starting (pair ids, point, impulses)
    private var cacheN = 0
    private var cacheIds = IntArray(512)
    private var cacheData = FloatArray(512 * 6)
    var warmStart = 1f
    private var cacheUsed = BooleanArray(512)
    private val pool = ArrayList<Contact>()
    private var used = 0
    val contactCount: Int get() = contacts.size

    fun add(b: Body): Body { b.id = nextId++; bodies += b; return b }
    fun remove(b: Body) { bodies.remove(b); holds.removeAll { it.body === b } }

    fun hold(b: Body): Hold { holds.removeAll { it.body === b }; return Hold(b).also { holds += it; b.wake() } }
    fun release(h: Hold) { holds.remove(h) }

    /**
     * Sets a kinematic body's pose for this frame; its velocity is derived from the motion so
     * contacts transfer momentum (a flick of the finger sends a ball flying).
     */
    fun moveKinematic(b: Body, p: Vec3, q: Quat, dt: Float) {
        if (dt > 0f) {
            b.linearVelocity.set(p).sub(b.position).scale(1f / dt)
            val dq = q.mul(b.orientation.conjugate(tmpQ), tmpQ2)
            if (dq.w < 0) dq.set(-dq.x, -dq.y, -dq.z, -dq.w)
            val s = sqrt(max(0f, 1f - dq.w * dq.w))
            val angle = 2f * kotlin.math.atan2(s, dq.w)
            if (s > 1e-6f) b.angularVelocity.set(dq.x / s, dq.y / s, dq.z / s).scale(angle / dt) else b.angularVelocity.set(0f, 0f, 0f)
            // a teleport (tracking re-acquired) must not hit objects at 100 m/s
            if (b.linearVelocity.length() > 6f) b.linearVelocity.set(0f, 0f, 0f)
        }
        b.position.set(p); b.orientation.set(q); b.updateDerived()
    }

    fun step(frameDt: Float) {
        val dt = (frameDt * timeScale).coerceIn(0f, 1f / 20f)
        if (dt <= 0f) return
        val h = dt / substeps
        for (b in bodies) b.lastImpact = 0f
        repeat(substeps) { substep(h) }
    }

    private val tmp = Vec3(); private val tmp2 = Vec3(); private val tmp3 = Vec3()
    private val tmpQ = Quat(); private val tmpQ2 = Quat()

    private fun substep(h: Float) {
        // 1. forces: gravity and hand grips
        for (b in bodies) {
            if (!b.isDynamic || !b.enabled || b.sleeping) continue
            b.linearVelocity.addScaled(gravity, h)
        }
        for (hd in holds) applyHold(hd, h)
        // 2. collisions
        collide()
        // 3. velocity solve
        java.util.Arrays.fill(cacheUsed, 0, cacheN, false)
        for (c in contacts) { prepare(c, h); if (warmStart > 0f) warm(c) }
        repeat(iterations) { for (c in contacts) solve(c) }
        storeCache()
        repeat(positionIterations) { for (c in contacts) solvePosition(c) }
        buildIslands()
        // 4. integrate
        for (b in bodies) {
            if (!b.isDynamic || !b.enabled || b.sleeping) continue
            // mild air drag keeps tumbling objects from spinning forever
            b.angularVelocity.scale(1f - 0.02f * h)
            b.position.addScaled(b.linearVelocity, h).addScaled(b.pseudoLinear, h)
            val w = tmp.set(b.angularVelocity).add(b.pseudoAngular)
            b.pseudoLinear.set(0f, 0f, 0f); b.pseudoAngular.set(0f, 0f, 0f)
            val q = b.orientation
            val dx = 0.5f * h * (w.x * q.w + w.y * q.z - w.z * q.y)
            val dy = 0.5f * h * (w.y * q.w + w.z * q.x - w.x * q.z)
            val dz = 0.5f * h * (w.z * q.w + w.x * q.y - w.y * q.x)
            val dw = 0.5f * h * (-w.x * q.x - w.y * q.y - w.z * q.z)
            q.set(q.x + dx, q.y + dy, q.z + dz, q.w + dw).normalize()
            b.updateDerived()
            // 5. sleep timer (the decision is made per island below)
            val held = holds.any { it.body === b }
            if (!held && b.linearVelocity.lengthSq() < sleepLinear * sleepLinear && b.angularVelocity.lengthSq() < sleepAngular * sleepAngular) b.sleepTime += h
            else b.sleepTime = 0f
        }
    }

    // ---------------------------------------------------------------- islands
    // Bodies in contact form an island; an island sleeps only when all its bodies are still, and
    // wakes as a whole. Sleeping bodies one by one let a block freeze in mid-air while the one
    // under it was still settling, which later released as an explosion.
    private var parent = IntArray(64)
    private var islandStill = BooleanArray(64)
    private var islandAwakeMoving = BooleanArray(64)

    private fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }

    private fun buildIslands() {
        val n = bodies.size
        if (parent.size < n) { parent = IntArray(n * 2); islandStill = BooleanArray(n * 2); islandAwakeMoving = BooleanArray(n * 2) }
        for (i in 0 until n) { parent[i] = i; bodies[i].islandIndex = i }
        for (c in contacts) {
            val a = c.a!!; val b = c.b!!
            if (!a.isDynamic || !b.isDynamic) continue
            val ra = find(a.islandIndex); val rb = find(b.islandIndex)
            if (ra != rb) parent[ra] = rb
        }
        for (i in 0 until n) { islandStill[i] = true; islandAwakeMoving[i] = false }
        for (i in 0 until n) {
            val b = bodies[i]
            if (!b.isDynamic || !b.enabled) continue
            val r = find(i)
            if (!b.sleeping && b.sleepTime < sleepAfter) islandStill[r] = false
            if (!b.sleeping && b.sleepTime == 0f) islandAwakeMoving[r] = true
        }
        // kinematic contacts (hands) keep their island awake
        for (c in contacts) {
            val a = c.a!!; val b = c.b!!
            if (a.type == Body.Type.KINEMATIC && b.isDynamic) { islandStill[find(b.islandIndex)] = false; islandAwakeMoving[find(b.islandIndex)] = true }
            if (b.type == Body.Type.KINEMATIC && a.isDynamic) { islandStill[find(a.islandIndex)] = false; islandAwakeMoving[find(a.islandIndex)] = true }
        }
        for (i in 0 until n) {
            val b = bodies[i]
            if (!b.isDynamic || !b.enabled) continue
            val r = find(i)
            if (islandAwakeMoving[r] && b.sleeping) b.wake()
            else if (islandStill[r] && !b.sleeping) { b.sleeping = true; b.linearVelocity.set(0f, 0f, 0f); b.angularVelocity.set(0f, 0f, 0f) }
        }
    }

    private fun applyHold(hd: Hold, h: Float) {
        val b = hd.body
        if (!b.isDynamic) return
        b.wake()
        // linear: critically damped spring towards the target, limited by grip force
        val k = hd.stiffness
        val c = 2f * sqrt(k)
        tmp.set(hd.targetPosition).sub(b.position).scale(k)
        tmp2.set(hd.holderVelocity).sub(b.linearVelocity).scale(c)
        tmp.add(tmp2).scale(b.mass)  // force for unit "feel" independent of mass, then limited
        tmp.addScaled(gravity, -b.mass) // the grip also carries the weight...
        val f = tmp.length()
        if (f > hd.maxForce) tmp.scale(hd.maxForce / f) // ...up to what a hand can hold
        b.linearVelocity.addScaled(tmp, h * b.invMass)
        // angular: rotate towards the target orientation
        val dq = hd.targetOrientation.mul(b.orientation.conjugate(tmpQ), tmpQ2)
        if (dq.w < 0) dq.set(-dq.x, -dq.y, -dq.z, -dq.w)
        tmp3.set(dq.x, dq.y, dq.z).scale(2f * hd.angularStiffness) // small-angle axis*angle
        tmp3.addScaled(b.angularVelocity, -2f * sqrt(hd.angularStiffness))
        // torque = I * alpha, limited
        val inertia = 1f / max(min(b.invInertiaLocal.x, min(b.invInertiaLocal.y, b.invInertiaLocal.z)), 1e-9f)
        val torque = tmp3.length() * inertia
        if (torque > hd.maxTorque) tmp3.scale(hd.maxTorque / torque)
        b.angularVelocity.addScaled(tmp3, h)
    }

    // ---------------------------------------------------------------- broad + narrow phase
    private fun newContact(a: Body, b: Body): Contact {
        if (used == pool.size) pool += Contact()
        val c = pool[used++]
        c.a = a; c.b = b; c.jn = 0f; c.jt1 = 0f; c.jt2 = 0f
        contacts += c
        return c
    }

    private fun collide() {
        contacts.clear(); used = 0
        val n = bodies.size
        for (i in 0 until n) {
            val a = bodies[i]
            if (!a.enabled) continue
            for (j in i + 1 until n) {
                val b = bodies[j]
                if (!b.enabled) continue
                if (!a.isDynamic && !b.isDynamic) continue
                if ((!a.isDynamic || a.sleeping) && (!b.isDynamic || b.sleeping)) continue
                if (a.group != 0 && a.group == b.group) continue
                if ((a.ignoreGroup != 0 && a.ignoreGroup == b.group) || (b.ignoreGroup != 0 && b.ignoreGroup == a.group)) continue
                val r = a.shape.boundingRadius() + b.shape.boundingRadius()
                if (a.position.distanceTo(b.position) > r) continue
                Collide.pair(a, b) { pt, nrm, depth -> val c = newContact(a, b); c.point.set(pt); c.normal.set(nrm); c.depth = depth }
            }
        }
    }

    // ---------------------------------------------------------------- solver
    private val va = Vec3(); private val vb = Vec3(); private val dv = Vec3(); private val imp = Vec3()

    private fun velocityAt(b: Body, r: Vec3, out: Vec3): Vec3 = b.angularVelocity.cross(r, out).add(b.linearVelocity)

    private fun effMass(a: Body, b: Body, ra: Vec3, rb: Vec3, n: Vec3): Float {
        var k = (if (a.active) a.invMass else 0f) + (if (b.active) b.invMass else 0f)
        if (a.active) { ra.cross(n, tmp); a.applyInvInertia(tmp, tmp2); tmp2.cross(ra, tmp3); k += tmp3.dot(n) }
        if (b.active) { rb.cross(n, tmp); b.applyInvInertia(tmp, tmp2); tmp2.cross(rb, tmp3); k += tmp3.dot(n) }
        return if (k > 0f) 1f / k else 0f
    }

    private fun prepare(c: Contact, h: Float) {
        val a = c.a!!; val b = c.b!!
        c.ra.set(c.point).sub(a.position)
        c.rb.set(c.point).sub(b.position)
        val n = c.normal
        // tangent basis
        if (abs(n.x) > 0.57f) c.t1.set(n.y, -n.x, 0f) else c.t1.set(0f, n.z, -n.y)
        c.t1.normalize()
        n.cross(c.t1, c.t2)
        c.massN = effMass(a, b, c.ra, c.rb, n)
        c.massT1 = effMass(a, b, c.ra, c.rb, c.t1)
        c.massT2 = effMass(a, b, c.ra, c.rb, c.t2)
        c.friction = sqrt(a.material.friction * b.material.friction)
        velocityAt(b, c.rb, vb); velocityAt(a, c.ra, va)
        val vn = dv.set(vb).sub(va).dot(n)
        val e = max(a.material.restitution, b.material.restitution)
        // restitution only for real impacts (resting contacts would jitter)
        val bounce = if (vn < -0.4f) -e * vn else 0f
        c.bias = bounce
        c.posBias = baumgarte / h * max(0f, c.depth - slop)
        c.jp = 0f
        val impact = -vn
        if (impact > a.lastImpact) a.lastImpact = impact
        if (impact > b.lastImpact) b.lastImpact = impact
    }

    /** Starts from the impulses this contact had last sub-step: stacks hold instead of slowly sinking. */
    private fun warm(c: Contact) {
        val a = c.a!!; val b = c.b!!
        val key = a.id * 65536 + b.id
        var bestD = 0.004f * 0.004f
        var best = -1
        for (i in 0 until cacheN) {
            if (cacheIds[i] != key || cacheUsed[i]) continue
            val dx = cacheData[i * 6] - c.point.x; val dy = cacheData[i * 6 + 1] - c.point.y; val dz = cacheData[i * 6 + 2] - c.point.z
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 < bestD) { bestD = d2; best = i }
        }
        if (best < 0) return
        cacheUsed[best] = true
        c.jn = cacheData[best * 6 + 3] * warmStart
        c.jt1 = cacheData[best * 6 + 4] * warmStart
        c.jt2 = cacheData[best * 6 + 5] * warmStart
        applyImpulse(c, imp.set(c.normal).scale(c.jn).addScaled(c.t1, c.jt1).addScaled(c.t2, c.jt2))
    }

    private fun storeCache() {
        if (cacheIds.size < contacts.size) { cacheIds = IntArray(contacts.size * 2); cacheData = FloatArray(contacts.size * 12); cacheUsed = BooleanArray(contacts.size * 2) }
        cacheN = contacts.size
        for ((i, c) in contacts.withIndex()) {
            cacheIds[i] = c.a!!.id * 65536 + c.b!!.id
            cacheData[i * 6] = c.point.x; cacheData[i * 6 + 1] = c.point.y; cacheData[i * 6 + 2] = c.point.z
            cacheData[i * 6 + 3] = c.jn; cacheData[i * 6 + 4] = c.jt1; cacheData[i * 6 + 5] = c.jt2
        }
    }

    private fun applyImpulse(c: Contact, j: Vec3) {
        val a = c.a!!; val b = c.b!!
        if (a.active) {
            a.linearVelocity.addScaled(j, -a.invMass)
            c.ra.cross(j, tmp); a.applyInvInertia(tmp, tmp2); a.angularVelocity.sub(tmp2)
        }
        if (b.active) {
            b.linearVelocity.addScaled(j, b.invMass)
            c.rb.cross(j, tmp); b.applyInvInertia(tmp, tmp2); b.angularVelocity.add(tmp2)
        }
    }

    private fun pseudoAt(b: Body, r: Vec3, out: Vec3): Vec3 = b.pseudoAngular.cross(r, out).add(b.pseudoLinear)

    private fun solvePosition(c: Contact) {
        if (c.posBias <= 0f) return
        val a = c.a!!; val b = c.b!!
        pseudoAt(b, c.rb, vb); pseudoAt(a, c.ra, va)
        val vn = dv.set(vb).sub(va).dot(c.normal)
        var lambda = c.massN * (c.posBias - vn)
        val old = c.jp
        c.jp = max(old + lambda, 0f)
        lambda = c.jp - old
        imp.set(c.normal).scale(lambda)
        if (a.active) { a.pseudoLinear.addScaled(imp, -a.invMass); c.ra.cross(imp, tmp); a.applyInvInertia(tmp, tmp2); a.pseudoAngular.sub(tmp2) }
        if (b.active) { b.pseudoLinear.addScaled(imp, b.invMass); c.rb.cross(imp, tmp); b.applyInvInertia(tmp, tmp2); b.pseudoAngular.add(tmp2) }
    }

    private fun solve(c: Contact) {
        val a = c.a!!; val b = c.b!!
        val n = c.normal
        // normal
        velocityAt(b, c.rb, vb); velocityAt(a, c.ra, va); dv.set(vb).sub(va)
        var lambda = c.massN * (c.bias - dv.dot(n))
        val old = c.jn
        c.jn = max(old + lambda, 0f)
        lambda = c.jn - old
        applyImpulse(c, imp.set(n).scale(lambda))
        // friction (two directions, clamped by the normal impulse)
        val maxF = c.friction * c.jn
        velocityAt(b, c.rb, vb); velocityAt(a, c.ra, va); dv.set(vb).sub(va)
        var l1 = -c.massT1 * dv.dot(c.t1)
        val o1 = c.jt1
        c.jt1 = (o1 + l1).coerceIn(-maxF, maxF); l1 = c.jt1 - o1
        var l2 = -c.massT2 * dv.dot(c.t2)
        val o2 = c.jt2
        c.jt2 = (o2 + l2).coerceIn(-maxF, maxF); l2 = c.jt2 - o2
        applyImpulse(c, imp.set(c.t1).scale(l1).addScaled(c.t2, l2))
    }

    companion object
}

internal const val PI = 3.1415927f

/** Narrow phase: contact generation for every shape pair. */
object Collide {
    fun interface Sink { fun add(point: Vec3, normal: Vec3, depth: Float) }

    private val p = Vec3(); private val q = Vec3(); private val n = Vec3(); private val d = Vec3()
    private val s0 = Vec3(); private val s1 = Vec3(); private val e0 = Vec3(); private val e1 = Vec3()

    /** Calls [sink] for each contact with the normal pointing from [a] to [b]. */
    fun pair(a: Body, b: Body, sink: Sink) {
        val ta = a.shape.type; val tb = b.shape.type
        when {
            ta == ShapeType.SPHERE && tb == ShapeType.SPHERE -> sphereSphere(a.position, a.shape.radius, b.position, b.shape.radius, sink, false)
            ta == ShapeType.SPHERE && tb == ShapeType.BOX -> sphereBox(a.position, a.shape.radius, b, sink, flip = true)
            ta == ShapeType.BOX && tb == ShapeType.SPHERE -> sphereBox(b.position, b.shape.radius, a, sink, flip = false)
            ta == ShapeType.BOX && tb == ShapeType.BOX -> BoxBox.collide(a, b, sink)
            ta == ShapeType.CAPSULE && tb == ShapeType.CAPSULE -> capsuleCapsule(a, b, sink)
            ta == ShapeType.CAPSULE && tb == ShapeType.SPHERE -> capsuleSphere(a, b.position, b.shape.radius, sink, flip = false)
            ta == ShapeType.SPHERE && tb == ShapeType.CAPSULE -> capsuleSphere(b, a.position, a.shape.radius, sink, flip = true)
            ta == ShapeType.CAPSULE && tb == ShapeType.BOX -> capsuleBox(a, b, sink, flip = false)
            ta == ShapeType.BOX && tb == ShapeType.CAPSULE -> capsuleBox(b, a, sink, flip = true)
        }
    }

    private val outN = Vec3()
    private fun emit(sink: Sink, point: Vec3, normal: Vec3, depth: Float, flip: Boolean) {
        if (flip) { outN.set(normal).scale(-1f); sink.add(point, outN, depth) } else sink.add(point, normal, depth)
    }

    /** Normal from sphere A to sphere B. */
    fun sphereSphere(ca: Vec3, ra: Float, cb: Vec3, rb: Float, sink: Sink, flip: Boolean) {
        d.set(cb).sub(ca)
        val dist = d.length()
        val depth = ra + rb - dist
        if (depth <= 0f) return
        if (dist > 1e-6f) n.set(d).scale(1f / dist) else n.set(0f, 1f, 0f)
        p.set(ca).addScaled(n, ra - depth * 0.5f)
        emit(sink, p, n, depth, flip)
    }

    /** Emits with the normal from the BOX to the sphere unless [flip] (then sphere -> box). */
    fun sphereBox(c: Vec3, r: Float, box: Body, sink: Sink, flip: Boolean) {
        val he = box.shape.halfExtents
        // centre in box space
        d.set(c).sub(box.position)
        val lx = d.x * box.rot[0] + d.y * box.rot[3] + d.z * box.rot[6]
        val ly = d.x * box.rot[1] + d.y * box.rot[4] + d.z * box.rot[7]
        val lz = d.x * box.rot[2] + d.y * box.rot[5] + d.z * box.rot[8]
        val cx = lx.coerceIn(-he.x, he.x); val cy = ly.coerceIn(-he.y, he.y); val cz = lz.coerceIn(-he.z, he.z)
        val inside = cx == lx && cy == ly && cz == lz
        var nx: Float; var ny: Float; var nz: Float
        var depth: Float
        if (!inside) {
            nx = lx - cx; ny = ly - cy; nz = lz - cz
            val dist = sqrt(nx * nx + ny * ny + nz * nz)
            depth = r - dist
            if (depth <= 0f) return
            nx /= dist; ny /= dist; nz /= dist
        } else {
            // centre inside the box: push out through the nearest face
            val dx = he.x - abs(lx); val dy = he.y - abs(ly); val dz = he.z - abs(lz)
            nx = 0f; ny = 0f; nz = 0f
            if (dx <= dy && dx <= dz) { nx = if (lx >= 0) 1f else -1f; depth = r + dx }
            else if (dy <= dz) { ny = if (ly >= 0) 1f else -1f; depth = r + dy }
            else { nz = if (lz >= 0) 1f else -1f; depth = r + dz }
        }
        // back to world
        n.set(box.rot[0] * nx + box.rot[1] * ny + box.rot[2] * nz, box.rot[3] * nx + box.rot[4] * ny + box.rot[5] * nz, box.rot[6] * nx + box.rot[7] * ny + box.rot[8] * nz)
        p.set(c).addScaled(n, -(r - depth * 0.5f))
        emit(sink, p, n, depth, flip)
    }

    private fun segment(b: Body, a0: Vec3, a1: Vec3) {
        val hh = b.shape.halfHeight
        b.axis(1, d)
        a0.set(b.position).addScaled(d, -hh)
        a1.set(b.position).addScaled(d, hh)
    }

    /** Closest point on segment [s0,s1] to [x]. */
    private fun closestOnSegment(s0: Vec3, s1: Vec3, x: Vec3, out: Vec3): Vec3 {
        val dx = s1.x - s0.x; val dy = s1.y - s0.y; val dz = s1.z - s0.z
        val len2 = dx * dx + dy * dy + dz * dz
        var t = if (len2 > 1e-12f) ((x.x - s0.x) * dx + (x.y - s0.y) * dy + (x.z - s0.z) * dz) / len2 else 0f
        t = t.coerceIn(0f, 1f)
        return out.set(s0.x + dx * t, s0.y + dy * t, s0.z + dz * t)
    }

    private val cp = Vec3(); private val cq = Vec3()
    fun capsuleSphere(cap: Body, c: Vec3, r: Float, sink: Sink, flip: Boolean) {
        segment(cap, s0, s1)
        closestOnSegment(s0, s1, c, cp)
        sphereSphere(cp, cap.shape.radius, c, r, sink, flip)
    }

    fun capsuleCapsule(a: Body, b: Body, sink: Sink) {
        segment(a, s0, s1); segment(b, e0, e1)
        closestSegments(s0, s1, e0, e1, cp, cq)
        sphereSphere(cp, a.shape.radius, cq, b.shape.radius, sink, false)
    }

    private val cb = Vec3()
    fun capsuleBox(cap: Body, box: Body, sink: Sink, flip: Boolean) {
        // a capsule lying on a box needs two contacts (its ends) to rest without rolling sideways
        segment(cap, s0, s1)
        val r = cap.shape.radius
        // sphere-box emits box -> sphere; capsule->box wanted unless flip
        sphereBox(s0, r, box, { pt, nrm, dep -> emit(sink, pt, nrm, dep, !flip) }, flip = false)
        sphereBox(s1, r, box, { pt, nrm, dep -> emit(sink, pt, nrm, dep, !flip) }, flip = false)
        closestOnSegment(s0, s1, box.position, cb)
        if (cb.distanceTo(s0) > r * 0.5f && cb.distanceTo(s1) > r * 0.5f) sphereBox(cb, r, box, { pt, nrm, dep -> emit(sink, pt, nrm, dep, !flip) }, flip = false)
    }

    /** Closest points between segments p0-p1 and q0-q1. */
    fun closestSegments(p0: Vec3, p1: Vec3, q0: Vec3, q1: Vec3, outP: Vec3, outQ: Vec3) {
        val d1x = p1.x - p0.x; val d1y = p1.y - p0.y; val d1z = p1.z - p0.z
        val d2x = q1.x - q0.x; val d2y = q1.y - q0.y; val d2z = q1.z - q0.z
        val rx = p0.x - q0.x; val ry = p0.y - q0.y; val rz = p0.z - q0.z
        val a = d1x * d1x + d1y * d1y + d1z * d1z
        val e = d2x * d2x + d2y * d2y + d2z * d2z
        val f = d2x * rx + d2y * ry + d2z * rz
        var s: Float; var t: Float
        if (a <= 1e-12f && e <= 1e-12f) { s = 0f; t = 0f }
        else if (a <= 1e-12f) { s = 0f; t = (f / e).coerceIn(0f, 1f) }
        else {
            val c = d1x * rx + d1y * ry + d1z * rz
            if (e <= 1e-12f) { t = 0f; s = (-c / a).coerceIn(0f, 1f) }
            else {
                val b = d1x * d2x + d1y * d2y + d1z * d2z
                val denom = a * e - b * b
                s = if (denom > 1e-12f) ((b * f - c * e) / denom).coerceIn(0f, 1f) else 0f
                t = (b * s + f) / e
                if (t < 0f) { t = 0f; s = (-c / a).coerceIn(0f, 1f) } else if (t > 1f) { t = 1f; s = ((b - c) / a).coerceIn(0f, 1f) }
            }
        }
        outP.set(p0.x + d1x * s, p0.y + d1y * s, p0.z + d1z * s)
        outQ.set(q0.x + d2x * t, q0.y + d2y * t, q0.z + d2z * t)
    }
}

/**
 * Box–box contacts: separating-axis test over the 15 candidate axes, then clipping of the incident
 * face against the reference face (up to 8 points) for face contacts, or the closest points of
 * the two edges for edge–edge contacts.
 */
object BoxBox {
    private val axA = arrayOf(Vec3(), Vec3(), Vec3())
    private val axB = arrayOf(Vec3(), Vec3(), Vec3())
    private val l = Vec3(); private val t = Vec3(); private val bestAxis = Vec3(); private val nrm = Vec3()
    private val poly = Array(16) { Vec3() }
    private val poly2 = Array(16) { Vec3() }
    private val tmp = Vec3(); private val pt = Vec3()
    private val ea0 = Vec3(); private val ea1 = Vec3(); private val eb0 = Vec3(); private val eb1 = Vec3(); private val cpA = Vec3(); private val cpB = Vec3()

    private val CORNERS = floatArrayOf(1f, 1f, -1f, 1f, -1f, -1f, 1f, -1f)

    private fun he(b: Body, i: Int) = when (i) { 0 -> b.shape.halfExtents.x; 1 -> b.shape.halfExtents.y; else -> b.shape.halfExtents.z }

    private fun radius(b: Body, ax: Array<Vec3>, axis: Vec3): Float =
        he(b, 0) * abs(ax[0].dot(axis)) + he(b, 1) * abs(ax[1].dot(axis)) + he(b, 2) * abs(ax[2].dot(axis))

    fun collide(a: Body, b: Body, sink: Collide.Sink) {
        for (i in 0..2) { a.axis(i, axA[i]); b.axis(i, axB[i]) }
        t.set(b.position).sub(a.position)
        var best = Float.MAX_VALUE
        var bestType = -1 // 0..2 face A, 3..5 face B, 6.. edge (i*3+j)
        for (k in 0 until 15) {
            when {
                k < 3 -> l.set(axA[k])
                k < 6 -> l.set(axB[k - 3])
                else -> {
                    axA[(k - 6) / 3].cross(axB[(k - 6) % 3], l)
                    val len = l.length()
                    if (len < 1e-4f) continue // parallel edges: covered by face axes
                    l.scale(1f / len)
                }
            }
            val overlap = radius(a, axA, l) + radius(b, axB, l) - abs(t.dot(l))
            if (overlap <= 0f) return // separating axis found
            // prefer face axes: edge contacts only when clearly shallower
            val score = if (k >= 6) overlap * 1.05f + 0.0005f else overlap
            if (score < best) { best = score; bestType = k; bestAxis.set(l) }
        }
        if (t.dot(bestAxis) < 0f) bestAxis.scale(-1f) // normal from A to B
        val depth = radius(a, axA, bestAxis) + radius(b, axB, bestAxis) - abs(t.dot(bestAxis))
        if (bestType < 6) faceContact(a, b, bestType, depth, sink) else edgeContact(a, b, bestType - 6, depth, sink)
    }

    private fun faceContact(a: Body, b: Body, type: Int, depth: Float, sink: Collide.Sink) {
        val refIsA = type < 3
        val ref = if (refIsA) a else b
        val inc = if (refIsA) b else a
        val refAx = if (refIsA) axA else axB
        val incAx = if (refIsA) axB else axA
        val fi = type % 3
        // reference normal points from the reference box towards the incident one
        nrm.set(bestAxis); if (!refIsA) nrm.scale(-1f)
        // incident face: the one most anti-parallel to the reference normal
        var incIdx = 0; var incDot = 0f
        for (i in 0..2) { val dd = incAx[i].dot(nrm); if (abs(dd) > abs(incDot)) { incDot = dd; incIdx = i } }
        val incSign = if (incDot > 0f) -1f else 1f
        val u = (incIdx + 1) % 3; val v = (incIdx + 2) % 3
        val hu = he(inc, u); val hv = he(inc, v)
        val corners = CORNERS
        val hc = he(inc, incIdx) * incSign
        var n = 0
        for (ci in 0 until 4) {
            poly[n++].set(inc.position).addScaled(incAx[incIdx], hc).addScaled(incAx[u], hu * corners[ci * 2]).addScaled(incAx[v], hv * corners[ci * 2 + 1])
        }
        // clip against the 4 side planes of the reference face
        val ru = (fi + 1) % 3; val rv = (fi + 2) % 3
        var src = poly; var dst = poly2
        for (side in 0 until 4) {
            val axisIdx = if (side < 2) ru else rv
            val sign = if (side % 2 == 0) 1f else -1f
            val ax = refAx[axisIdx]
            // keep sign * dot(p, ax) <= halfExtent + sign * dot(centre, ax)
            n = clip(src, n, dst, ax, sign, he(ref, axisIdx) + sign * ref.position.dot(ax))
            if (n == 0) return
            val s = src; src = dst; dst = s
        }
        // keep points below the reference face
        val faceOffset = ref.position.dot(nrm) + he(ref, fi)
        var emitted = 0
        for (i in 0 until n) {
            val sep = src[i].dot(nrm) - faceOffset
            if (sep > 0.0005f) continue
            pt.set(src[i]).addScaled(nrm, -sep * 0.5f)
            sink.add(pt, bestAxis, max(-sep, 0.0001f))
            emitted++
        }
        if (emitted == 0) {
            // degenerate clip (numerical): fall back to a single contact at the incident face centre
            pt.set(inc.position).addScaled(incAx[incIdx], hc)
            sink.add(pt, bestAxis, depth)
        }
    }

    /** Sutherland–Hodgman against the plane sign*dot(p, ax) <= limit. */
    private fun clip(src: Array<Vec3>, n: Int, dst: Array<Vec3>, ax: Vec3, sign: Float, limit: Float): Int {
        var m = 0
        for (i in 0 until n) {
            val p0 = src[i]; val p1 = src[(i + 1) % n]
            val d0 = sign * p0.dot(ax) - limit
            val d1 = sign * p1.dot(ax) - limit
            if (d0 <= 0f) { if (m < dst.size) dst[m++].set(p0) }
            if ((d0 < 0f && d1 > 0f) || (d0 > 0f && d1 < 0f)) {
                val s = d0 / (d0 - d1)
                if (m < dst.size) dst[m++].set(p0.x + (p1.x - p0.x) * s, p0.y + (p1.y - p0.y) * s, p0.z + (p1.z - p0.z) * s)
            }
        }
        return m
    }

    private fun edgeContact(a: Body, b: Body, k: Int, depth: Float, sink: Collide.Sink) {
        val i = k / 3; val j = k % 3
        // supporting edge of A (towards B) and of B (towards A)
        tmp.set(a.position)
        for (m in 0..2) if (m != i) tmp.addScaled(axA[m], he(a, m) * (if (axA[m].dot(bestAxis) > 0f) 1f else -1f))
        ea0.set(tmp).addScaled(axA[i], -he(a, i)); ea1.set(tmp).addScaled(axA[i], he(a, i))
        tmp.set(b.position)
        for (m in 0..2) if (m != j) tmp.addScaled(axB[m], he(b, m) * (if (axB[m].dot(bestAxis) > 0f) -1f else 1f))
        eb0.set(tmp).addScaled(axB[j], -he(b, j)); eb1.set(tmp).addScaled(axB[j], he(b, j))
        Collide.closestSegments(ea0, ea1, eb0, eb1, cpA, cpB)
        pt.set(cpA).add(cpB).scale(0.5f)
        sink.add(pt, bestAxis, depth)
    }
}
