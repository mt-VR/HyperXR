package com.hyperxr.lab

import android.graphics.Color
import com.hyperxr.handtracking.Gesture
import com.hyperxr.handtracking.HandFrame
import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.HandState
import com.hyperxr.math.Mat4
import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Ray
import com.hyperxr.math.Vec3
import com.hyperxr.physics.Body
import com.hyperxr.physics.Hold
import com.hyperxr.physics.Material
import com.hyperxr.physics.RigidWorld
import com.hyperxr.physics.Shape
import com.hyperxr.physics.ShapeType
import com.hyperxr.rendering.Gl
import com.hyperxr.rendering.TextRenderer
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme
import com.hyperxr.xr.XrContext

/**
 * Hand Physics Lab: a workbench in front of the user, in the same space as the launcher UI (in
 * mixed reality it stands in the real room). Everything on it is a rigid body with real size,
 * mass, friction and bounciness; the tracked hands are physical colliders (push, flick, stack)
 * and pinch or grab picks objects up with a grip of limited strength (heavy things lag and can
 * slip). Joy-Con: aim with the cursor and hold A to carry an object.
 */
class HandPhysicsLab(private val ctx: XrContext, private val onClose: () -> Unit) {
    enum class Kind(val label: String) {
        BLOCK("Bloco de madeira"), DOMINO("Dominó"), CUBE("Cubo de brinquedo"), RUBBER_BALL("Bola de borracha"),
        STEEL_BALL("Esfera de aço"), PING_PONG("Bola de pingue-pongue"), CAN("Lata de refrigerante"),
    }

    class Prop(val body: Body, val kind: Kind, val texture: Int, val color: FloatArray, val gloss: Float, val metal: Float) {
        var lastSoundNs = 0L
    }

    val world = RigidWorld()
    private val props = ArrayList<Prop>()
    /** Bench frame: origin at the centre of the top surface, -Z away from the user. */
    val bench = Pose()
    private var floorY = 0f
    val panel = LabPanel()

    // bench size (m)
    private val topHalf = Vec3(0.55f, 0.02f, 0.3f)
    private var legHeight = 0.9f

    // textures and sounds (GL thread)
    private var texBench = 0; private var texBlock = 0; private var texDomino = 0; private var texMat = 0
    private val texCans = IntArray(2); private val texCubes = IntArray(3)
    private var sndWood = 0; private var sndMetal = 0; private var sndRubber = 0; private var sndTick = 0; private var sndCan = 0
    private var labelTex = 0; private var labelAspect = 4f; private var labelFor: Body? = null

    // hands
    private val handGroups = intArrayOf(1001, 1002)
    private val handBones = Array(2) { ArrayList<Body>() }
    private val handPalm = arrayOfNulls<Body>(2)
    private val handLengths = Array(2) { FloatArray(HandJoint.BONES.size) }
    private val holds = arrayOfNulls<Hold>(2)
    private val holdOffset = Array(2) { Pose() }
    private val wasGripping = BooleanArray(2)
    private val holdUsesPinch = BooleanArray(2)
    private val lastHandPos = Array(2) { Vec3() }
    private val handVel = Array(2) { Vec3() }
    /** True while hand [i] is busy with the lab (holding or reaching an object): the UI ignores its pinch. */
    val handBusy = BooleanArray(2)

    // Joy-Con carrying
    private var cursorHold: Hold? = null
    private var cursorDistance = 0.5f
    private val cursorRotOffset = Quat()

    private var gravityMode = 0
    private var stepMs = 0f
    private var slowMotion = false

    init {
        panel.lab = this
    }

    // ---------------------------------------------------------------- setup
    /** Puts the bench ~45 cm in front of the user, top at a comfortable standing height. */
    fun place(head: Pose, yaw: Float) {
        bench.position.set(head.position.x - kotlin.math.sin(yaw) * 0.5f, head.position.y - 0.62f, head.position.z - kotlin.math.cos(yaw) * 0.5f)
        Quat.fromAxisAngle(0f, 1f, 0f, yaw, bench.orientation)
        floorY = head.position.y - 1.6f
        legHeight = (bench.position.y - topHalf.y * 2 - floorY).coerceIn(0.4f, 1.2f)
        // the panel stands on the back edge of the bench, facing the user
        panel.pose.set(local(0f, 0.11f, -topHalf.z + 0.02f))
        panel.pose.orientation.set(bench.orientation)
        // tilt the panel back a little, like a lectern
        panel.pose.orientation.mul(Quat.fromAxisAngle(1f, 0f, 0f, -0.25f), panel.pose.orientation)
        rebuild()
    }

    private val tmpPose = Pose()
    private fun local(x: Float, y: Float, z: Float): Pose {
        tmpPose.orientation.set(bench.orientation)
        bench.orientation.rotate(tmpV.set(x, y, z), tmpPose.position).add(bench.position)
        return tmpPose
    }
    private val tmpV = Vec3()

    fun initGl() {
        if (texBench != 0) return
        texBench = Gl.uploadBitmap(LabTextures.wood(1024, 512, Color.rgb(196, 150, 104), Color.rgb(128, 86, 52), 11, ringFreq = 9f), mipmap = true)
        texBlock = Gl.uploadBitmap(LabTextures.wood(256, 128, Color.rgb(236, 214, 170), Color.rgb(200, 168, 118), 23, ringFreq = 5f), mipmap = true)
        texDomino = Gl.uploadBitmap(LabTextures.domino(), mipmap = true)
        texMat = Gl.uploadBitmap(LabTextures.cuttingMat(), mipmap = true)
        texCans[0] = Gl.uploadBitmap(LabTextures.canLabel(Color.rgb(196, 24, 40), Color.WHITE, "HYPER"), mipmap = true)
        texCans[1] = Gl.uploadBitmap(LabTextures.canLabel(Color.rgb(30, 90, 190), Color.rgb(250, 200, 40), "LAB"), mipmap = true)
        val cubeColors = intArrayOf(Color.rgb(226, 60, 64), Color.rgb(46, 150, 230), Color.rgb(250, 190, 40))
        val letters = arrayOf("H", "X", "R")
        for (i in 0..2) texCubes[i] = Gl.uploadBitmap(LabTextures.toyCube(cubeColors[i], letters[i]), mipmap = true)
        val a = ctx.audio
        sndWood = a.synth("sine", 420f, 210f, 0.09f, 0.9f, 0.001f, 0.025f)
        sndMetal = a.synth("sine", 2400f, 2350f, 0.45f, 0.6f, 0.001f, 0.12f)
        sndRubber = a.synth("sine", 160f, 90f, 0.12f, 1f, 0.002f, 0.04f)
        sndTick = a.synth("sine", 1900f, 1500f, 0.05f, 0.7f, 0.001f, 0.012f)
        sndCan = a.synth("square", 900f, 700f, 0.08f, 0.35f, 0.001f, 0.02f)
    }

    fun release() {
        // every id is cleared after deleting it: a second release must not delete texture names
        // that the GPU has since handed to launcher icons (they would show the wrong image)
        for (t in intArrayOf(texBench, texBlock, texDomino, texMat, labelTex) + texCans + texCubes) Gl.deleteTexture(t)
        texBench = 0; texBlock = 0; texDomino = 0; texMat = 0; labelTex = 0
        texCans.fill(0); texCubes.fill(0)
        labelFor = null
        panel.release()
    }

    /** Rebuilds the world: bench, floor and the starting arrangement of objects. */
    fun rebuild() {
        world.bodies.clear(); world.holds.clear(); props.clear()
        holds.fill(null); cursorHold = null
        handBones.forEach { it.clear() }; handPalm.fill(null)
        applyGravity()
        // static: bench top, legs, floor
        staticBox(0f, -topHalf.y, 0f, topHalf.x, topHalf.y, topHalf.z)
        val lh = legHeight / 2
        for (sx in floatArrayOf(-1f, 1f)) for (sz in floatArrayOf(-1f, 1f)) {
            staticBox(sx * (topHalf.x - 0.05f), -topHalf.y * 2 - lh, sz * (topHalf.z - 0.05f), 0.025f, lh, 0.025f)
        }
        val floor = Body(Shape.box(4f, 0.05f, 4f), Material.WOOD, Body.Type.STATIC)
        floor.teleport(Vec3(bench.position.x, floorY - 0.05f, bench.position.z), Quat())
        world.add(floor)

        // a tower of crossed wooden blocks (7.5 x 1.5 x 2.5 cm), 6 levels
        val hx = 0.0375f; val hy = 0.0075f; val hz = 0.0125f
        for (level in 0 until 6) {
            val rotated = level % 2 == 1
            for (k in -1..1) {
                val off = k * (2 * hz + 0.0005f)
                val y = hy + level * (2 * hy + 0.0002f)
                addBox(Kind.BLOCK, Material.WOOD, hx, hy, hz, if (rotated) -0.36f + off else -0.36f, y, if (rotated) -0.06f else -0.06f + off,
                    yaw = if (rotated) (Math.PI / 2).toFloat() else 0f, tex = texBlock, gloss = 0.15f)
            }
        }
        // a row of dominoes
        for (i in 0 until 10) addBox(Kind.DOMINO, Material.PLASTIC, 0.012f, 0.024f, 0.004f, -0.12f + i * 0.032f, 0.024f, -0.17f, tex = texDomino, gloss = 0.6f)
        // three toy cubes in a small pyramid
        addBox(Kind.CUBE, Material.WOOD, 0.025f, 0.025f, 0.025f, -0.08f, 0.025f, 0.07f, tex = texCubes[0], gloss = 0.3f)
        addBox(Kind.CUBE, Material.WOOD, 0.025f, 0.025f, 0.025f, -0.024f, 0.025f, 0.07f, tex = texCubes[1], gloss = 0.3f)
        addBox(Kind.CUBE, Material.WOOD, 0.025f, 0.025f, 0.025f, -0.052f, 0.0755f, 0.07f, tex = texCubes[2], gloss = 0.3f)
        // balls
        addSphere(Kind.RUBBER_BALL, Material.RUBBER, 0.034f, 0.12f, 0.06f, floatArrayOf(0.85f, 0.18f, 0.2f, 1f), gloss = 0.3f)
        addSphere(Kind.STEEL_BALL, Material.STEEL, 0.02f, 0.2f, 0.09f, floatArrayOf(0.78f, 0.79f, 0.82f, 1f), gloss = 0.95f, metal = 1f)
        addSphere(Kind.PING_PONG, Material.CELLULOID, 0.02f, 0.26f, 0.05f, floatArrayOf(0.98f, 0.96f, 0.92f, 1f), gloss = 0.45f)
        // two soda cans (6.6 cm wide, 12 cm tall, 350 g full)
        addCan(0.38f, 0.02f, texCans[0])
        addCan(0.46f, -0.06f, texCans[1])
        for (b in world.bodies) b.sleeping = b.isDynamic // start at rest: nothing jitters before it is touched
    }

    private fun applyGravity() {
        when (gravityMode) {
            0 -> world.gravity.set(0f, -9.81f, 0f)
            1 -> world.gravity.set(0f, -1.62f, 0f)
            else -> world.gravity.set(0f, 0f, 0f)
        }
        world.bodies.forEach { it.wake() }
    }

    private fun staticBox(x: Float, y: Float, z: Float, hx: Float, hy: Float, hz: Float) {
        val b = Body(Shape.box(hx, hy, hz), Material.WOOD, Body.Type.STATIC)
        val p = local(x, y, z)
        b.teleport(p.position, p.orientation)
        world.add(b)
    }

    private fun addBox(kind: Kind, m: Material, hx: Float, hy: Float, hz: Float, x: Float, y: Float, z: Float, yaw: Float = 0f, tex: Int, gloss: Float) {
        val b = Body(Shape.box(hx, hy, hz), m)
        val p = local(x, y, z)
        p.orientation.mul(Quat.fromAxisAngle(0f, 1f, 0f, yaw), p.orientation)
        b.teleport(p.position, p.orientation)
        world.add(b)
        props += Prop(b, kind, tex, floatArrayOf(1f, 1f, 1f, 1f), gloss, 0f).also { b.userData = it }
    }

    private fun addSphere(kind: Kind, m: Material, r: Float, x: Float, z: Float, color: FloatArray, gloss: Float, metal: Float = 0f) {
        val massOverride = if (kind == Kind.PING_PONG) 0.0027f else 0f
        val b = Body(Shape.sphere(r), m, massOverride = massOverride)
        val p = local(x, r, z)
        b.teleport(p.position, p.orientation)
        world.add(b)
        props += Prop(b, kind, 0, color, gloss, metal).also { b.userData = it }
    }

    private fun addCan(x: Float, z: Float, tex: Int) {
        val r = 0.033f; val hh = 0.06f - r
        val b = Body(Shape.capsule(r, hh), Material.ALUMINIUM, massOverride = 0.35f)
        val p = local(x, hh + r, z)
        b.teleport(p.position, p.orientation)
        world.add(b)
        props += Prop(b, Kind.CAN, tex, floatArrayOf(1f, 1f, 1f, 1f), 0.75f, 0.55f).also { b.userData = it }
    }

    // ---------------------------------------------------------------- frame
    private val frame = Pose()
    private val target = Pose()
    private val pinch = Vec3()

    /**
     * @param cursor Joy-Con cursor ray (null without controllers); [cursorDown] = A held while
     *   the cursor is not on a UI element.
     */
    fun update(dt: Float, hands: Array<HandState>, head: Pose, cursor: Ray?, cursorDown: Boolean) {
        val now = System.nanoTime()
        for (i in 0..1) updateHand(i, hands[i], dt)
        updateCursor(cursor, cursorDown)
        world.timeScale = if (slowMotion) 0.25f else 1f
        val t0 = System.nanoTime()
        world.step(dt)
        // frame budget: fewer sub-steps when the physics gets expensive on this phone
        val ms = (System.nanoTime() - t0) / 1e6f
        stepMs += (ms - stepMs) * 0.1f
        if (stepMs > 6f && world.substeps > 10) world.substeps = 10
        else if (stepMs < 3f && world.substeps < 16) world.substeps = 16
        com.hyperxr.diagnostics.Diagnostics.record(com.hyperxr.diagnostics.Stage.SIMULATION, System.nanoTime() - t0)
        // impact sounds, loudness from the impact speed, pitch from the object's size
        for (p in props) {
            val v = p.body.lastImpact
            if (v < 0.22f || now - p.lastSoundNs < 70_000_000L) continue
            p.lastSoundNs = now
            val snd = when (p.kind) {
                Kind.STEEL_BALL -> sndMetal; Kind.RUBBER_BALL -> sndRubber; Kind.PING_PONG -> sndTick; Kind.CAN -> sndCan; else -> sndWood
            }
            val pitch = when (p.kind) { Kind.DOMINO -> 1.5f; Kind.CUBE -> 1.1f; else -> 1f } * (0.92f + 0.16f * Math.random().toFloat())
            ctx.audio.play(snd, (v / 2.5f).coerceIn(0.05f, 1f), pitch, false, p.body.position, head)
        }
        panel.update(dt)
    }

    private fun updateHand(i: Int, h: HandState, dt: Float) {
        handBusy[i] = false
        val bones = handBones[i]
        if (!h.isUsable) {
            bones.forEach { it.enabled = false }
            handPalm[i]?.enabled = false
            releaseHand(i)
            return
        }
        // kinematic colliders on every bone (capsules) and the palm (sphere)
        if (bones.isEmpty() || needsRebuild(i, h)) buildHandColliders(i, h)
        HandJoint.BONES.forEachIndexed { k, b ->
            val body = bones[k]
            val a = h.joints[b[0]]; val c = h.joints[b[1]]
            tmpV.set(c).sub(a)
            val len = tmpV.length()
            if (len < 1e-4f) return@forEachIndexed
            tmpV.scale(1f / len)
            Quat.fromTo(Y, tmpV, tmpQ)
            pinch.set(a).add(c).scale(0.5f)
            body.enabled = true
            world.moveKinematic(body, pinch, tmpQ, dt)
        }
        handPalm[i]?.let { palm ->
            HandFrame.palmCenter(h, pinch)
            palm.enabled = true
            world.moveKinematic(palm, pinch, tmpQ.identity(), dt)
        }

        // grip: pinch (thumb + index) or whole-hand grab
        val gripping = h.gesture == Gesture.PINCH || h.gesture == Gesture.GRAB || h.gesture == Gesture.FIST
        val usePinch = h.gesture == Gesture.PINCH
        // a hold keeps the frame it started with (pinch point or palm), so the object does not jump
        if (!HandFrame.compute(h, if (holds[i] != null) holdUsesPinch[i] else usePinch, frame)) return
        if (dt > 0f) handVel[i].set(frame.position).sub(lastHandPos[i]).scale(1f / dt)
        lastHandPos[i].set(frame.position)
        if (handVel[i].length() > 4f) handVel[i].set(0f, 0f, 0f) // tracking jump

        // near an object: the pinch belongs to the lab, not to the UI
        val nearest = nearestProp(frame.position, 0.06f)
        if (nearest != null) handBusy[i] = true

        val held = holds[i]
        if (gripping && !wasGripping[i] && held == null) {
            val grab = nearestProp(frame.position, if (usePinch) 0.025f else 0.045f)
            if (grab != null) {
                val hd = world.hold(grab.body)
                hd.maxForce = if (usePinch) 25f else 60f // a pinch holds less than a full grip
                grab.body.ignoreGroup = handGroups[i]
                // remember where the object sits in the hand
                frame.inverse().compose(Pose(grab.body.position.copy(), grab.body.orientation.copy()), holdOffset[i])
                holds[i] = hd
                holdUsesPinch[i] = usePinch
                showLabel(grab)
            }
        } else if (!gripping && held != null) {
            releaseHand(i)
        }
        holds[i]?.let { hd ->
            frame.compose(holdOffset[i], target)
            hd.targetPosition.set(target.position)
            hd.targetOrientation.set(target.orientation)
            hd.holderVelocity.set(handVel[i])
            handBusy[i] = true
        }
        wasGripping[i] = gripping
    }

    private fun releaseHand(i: Int) {
        val hd = holds[i] ?: return
        world.release(hd)
        hd.body.ignoreGroup = 0
        holds[i] = null
        wasGripping[i] = false
    }

    private fun needsRebuild(i: Int, h: HandState): Boolean {
        HandJoint.BONES.forEachIndexed { k, b ->
            val len = h.joints[b[0]].distanceTo(h.joints[b[1]])
            if (kotlin.math.abs(len - handLengths[i][k]) > 0.15f * handLengths[i][k] + 0.003f) return true
        }
        return false
    }

    private fun buildHandColliders(i: Int, h: HandState) {
        handBones[i].forEach { world.remove(it) }
        handBones[i].clear()
        handPalm[i]?.let { world.remove(it) }
        HandJoint.BONES.forEachIndexed { k, b ->
            val len = h.joints[b[0]].distanceTo(h.joints[b[1]]).coerceAtLeast(0.005f)
            handLengths[i][k] = len
            val r = HandJoint.radiusFor(b[1]) * 0.9f
            val body = Body(Shape.capsule(r, (len / 2f - r * 0.5f).coerceAtLeast(0.001f)), Material.HAND, Body.Type.KINEMATIC)
            body.group = handGroups[i]
            world.add(body)
            handBones[i] += body
        }
        val palm = Body(Shape.sphere(0.028f), Material.HAND, Body.Type.KINEMATIC)
        palm.group = handGroups[i]
        world.add(palm)
        handPalm[i] = palm
    }

    private fun nearestProp(p: Vec3, maxDist: Float): Prop? {
        var best: Prop? = null
        var bestD = maxDist
        for (prop in props) {
            val d = surfaceDistance(prop.body, p)
            if (d < bestD) { bestD = d; best = prop }
        }
        return best
    }

    /** Distance from [p] to the surface of [b] (negative inside). */
    private fun surfaceDistance(b: Body, p: Vec3): Float {
        val s = b.shape
        return when (s.type) {
            ShapeType.SPHERE -> p.distanceTo(b.position) - s.radius
            ShapeType.CAPSULE -> {
                b.axis(1, tmpV)
                val t = (Vec3.sub(p, b.position, tmpV2).dot(tmpV)).coerceIn(-s.halfHeight, s.halfHeight)
                tmpV2.set(b.position).addScaled(tmpV, t)
                p.distanceTo(tmpV2) - s.radius
            }
            ShapeType.BOX -> {
                Vec3.sub(p, b.position, tmpV2)
                var outside = 0f; var inside = -1e9f
                for (k in 0..2) {
                    b.axis(k, tmpV)
                    val e = when (k) { 0 -> s.halfExtents.x; 1 -> s.halfExtents.y; else -> s.halfExtents.z }
                    val d = kotlin.math.abs(tmpV2.dot(tmpV)) - e
                    if (d > 0) outside += d * d
                    inside = maxOf(inside, d)
                }
                if (outside > 0f) kotlin.math.sqrt(outside) else inside
            }
        }
    }

    private val tmpV2 = Vec3()
    private val tmpQ = Quat()

    private fun updateCursor(cursor: Ray?, down: Boolean) {
        val hd = cursorHold
        if (cursor == null) { if (hd != null) { world.release(hd); cursorHold = null }; return }
        if (down && hd == null) {
            // pick the object the cursor ray hits (bounding spheres, nearest first)
            var best: Prop? = null; var bestT = 3f
            for (p in props) {
                val r = p.body.shape.boundingRadius()
                Vec3.sub(p.body.position, cursor.origin, tmpV)
                val t = tmpV.dot(cursor.direction)
                if (t <= 0f) continue
                val d2 = tmpV.lengthSq() - t * t
                if (d2 < r * r && t < bestT) { bestT = t; best = p }
            }
            if (best != null) {
                cursorHold = world.hold(best.body).also { it.maxForce = 40f }
                cursorDistance = bestT
                cursorRotOffset.set(best.body.orientation)
                showLabel(best)
            }
        } else if (!down && hd != null) {
            world.release(hd); cursorHold = null
        }
        cursorHold?.let { h ->
            h.targetPosition.set(cursor.origin).addScaled(cursor.direction, cursorDistance)
            h.targetOrientation.set(cursorRotOffset)
            h.holderVelocity.set(0f, 0f, 0f)
        }
    }

    /** Whether the Joy-Con cursor ray would pick an object (so A goes to the lab, not the UI). */
    fun cursorTargetsObject(cursor: Ray): Boolean {
        if (cursorHold != null) return true
        for (p in props) {
            val r = p.body.shape.boundingRadius()
            Vec3.sub(p.body.position, cursor.origin, tmpV)
            val t = tmpV.dot(cursor.direction)
            if (t > 0f && tmpV.lengthSq() - t * t < r * r) return true
        }
        return false
    }

    private fun showLabel(p: Prop) {
        if (labelFor === p.body) return
        labelFor = p.body
        val grams = p.body.mass * 1000f
        val mass = if (grams >= 1000f) "%.2f kg".format(grams / 1000f) else "%.0f g".format(grams)
        val bmp = TextRenderer.render("${p.kind.label} · $mass · ${p.body.material.name}", 34f, Color.WHITE, isBold = true, maxWidth = 900)
        labelAspect = bmp.width.toFloat() / bmp.height
        labelTex = Gl.uploadBitmap(bmp, labelTex); bmp.recycle()
    }

    // ---------------------------------------------------------------- controls (panel)
    fun reset() { rebuild() }
    fun cycleGravity() { gravityMode = (gravityMode + 1) % 3; applyGravity() }
    val gravityLabel: String get() = when (gravityMode) { 0 -> "Terra"; 1 -> "Lua"; else -> "Zero-g" }
    fun toggleSlowMotion() { slowMotion = !slowMotion }
    val isSlowMotion: Boolean get() = slowMotion
    fun dropBall() {
        val kinds = arrayOf(Kind.RUBBER_BALL, Kind.STEEL_BALL, Kind.PING_PONG)
        val k = kinds[(props.size) % 3]
        val x = -0.1f + (Math.random().toFloat() * 0.3f)
        when (k) {
            Kind.RUBBER_BALL -> addSphere(k, Material.RUBBER, 0.034f, x, 0f, floatArrayOf(0.2f, 0.55f, 0.95f, 1f), 0.3f)
            Kind.STEEL_BALL -> addSphere(k, Material.STEEL, 0.02f, x, 0f, floatArrayOf(0.78f, 0.79f, 0.82f, 1f), 0.95f, 1f)
            else -> addSphere(k, Material.CELLULOID, 0.02f, x, 0f, floatArrayOf(1f, 0.55f, 0.1f, 1f), 0.45f)
        }
        // drop it from 35 cm above the bench
        val b = props.last().body
        b.teleport(local(x, 0.35f, 0f).position, Quat())
    }
    fun close() = onClose()
    val objectCount: Int get() = props.size
    val awakeCount: Int get() = props.count { !it.body.sleeping }

    // ---------------------------------------------------------------- drawing
    private val m = FloatArray(16)
    private val tmpScale = Vec3()

    fun submit() {
        val sc = ctx.scene
        val r = ctx.renderer
        // shadows for the bench region, sun from above-front-left
        sc.shadowCenter.set(bench.position)
        sc.shadowRadius = 0.75f
        sc.lightDir[0] = -0.35f; sc.lightDir[1] = -1f; sc.lightDir[2] = -0.25f
        // bench top (wood) with a cutting mat
        box(local(0f, -topHalf.y, 0f), topHalf.x, topHalf.y, topHalf.z, texBench, floatArrayOf(1f, 1f, 1f, 1f), 0.25f, 0f)
        box(local(0.05f, 0.0006f, 0.02f), 0.3f, 0.0006f, 0.16f, texMat, floatArrayOf(1f, 1f, 1f, 1f), 0.1f, 0f)
        // legs and stretchers: powder-coated steel
        val lh = legHeight / 2
        val steel = floatArrayOf(0.18f, 0.19f, 0.21f, 1f)
        for (sx in floatArrayOf(-1f, 1f)) for (sz in floatArrayOf(-1f, 1f)) {
            box(local(sx * (topHalf.x - 0.05f), -topHalf.y * 2 - lh, sz * (topHalf.z - 0.05f)), 0.025f, lh, 0.025f, 0, steel, 0.55f, 0.7f)
        }
        for (sz in floatArrayOf(-1f, 1f)) box(local(0f, -topHalf.y * 2 - legHeight * 0.75f, sz * (topHalf.z - 0.05f)), topHalf.x - 0.05f, 0.015f, 0.015f, 0, steel, 0.55f, 0.7f)
        // objects
        for (p in props) {
            val b = p.body
            val d = sc.addDraw()
            d.texture = p.texture
            p.color.copyInto(d.color)
            d.gloss = p.gloss; d.metal = p.metal
            d.castShadow = true
            when (b.shape.type) {
                ShapeType.BOX -> {
                    d.mesh = r.cube
                    val h = b.shape.halfExtents
                    Mat4.compose(b.position, b.orientation, h.x * 2, h.y * 2, h.z * 2, d.matrix)
                }
                ShapeType.SPHERE -> {
                    d.mesh = r.sphere
                    val s = b.shape.radius * 2
                    Mat4.compose(b.position, b.orientation, s, s, s, d.matrix)
                }
                ShapeType.CAPSULE -> {
                    // a can: cylinder body (the capsule's rounded ends are hidden inside flat lids)
                    d.mesh = r.cylinder
                    val rr = b.shape.radius
                    val hh = b.shape.halfHeight + rr
                    Mat4.compose(b.position, b.orientation, rr * 2, hh * 2, rr * 2, d.matrix)
                }
            }
        }
        // what the hand is holding: name, mass and material floating above it
        val held = holds.firstOrNull { it != null } ?: cursorHold
        if (held != null && labelTex != 0) {
            val p = sc.addPanel()
            p.texture = labelTex
            p.pose.position.set(held.body.position).add(tmpV.set(0f, held.body.shape.boundingRadius() + 0.05f, 0f))
            faceUser(p.pose, ctx.input.head.position)
            p.height = 0.022f; p.width = p.height * labelAspect
            p.radius = 0.011f
            p.overlay = true
        }
        panel.submit(sc)
    }

    private fun box(p: Pose, hx: Float, hy: Float, hz: Float, tex: Int, color: FloatArray, gloss: Float, metal: Float) {
        val d = ctx.scene.addDraw()
        d.mesh = ctx.renderer.cube
        d.texture = tex
        color.copyInto(d.color)
        d.gloss = gloss; d.metal = metal
        d.castShadow = true
        Mat4.compose(p.position, p.orientation, hx * 2, hy * 2, hz * 2, d.matrix)
    }

    private fun faceUser(p: Pose, eye: Vec3) {
        val dx = p.position.x - eye.x; val dz = p.position.z - eye.z
        Quat.fromAxisAngle(0f, 1f, 0f, kotlin.math.atan2(-dx, -dz), p.orientation)
    }

    companion object { private val Y = Vec3(0f, 1f, 0f) }
}

/** Controls on the back edge of the bench. */
class LabPanel : XrPanel(0.62f, 0.2f, title = "Hand Physics Lab") {
    lateinit var lab: HandPhysicsLab
    private var lastAwake = -1

    init { closable = false }

    override fun update(dt: Float) {
        val a = lab.awakeCount
        if (a != lastAwake) { lastAwake = a; dirty = true }
    }

    override fun draw(c: PanelCanvas) {
        val n = 5
        val bw = (c.w - 2 * c.pad - (n - 1) * 10f) / n
        var x = c.pad
        fun b(id: String, label: String, accent: Boolean = false, act: () -> Unit) { c.button(id, label, x, c.y, bw, 64f, accent = accent) { act(); dirty = true }; x += bw + 10f }
        b("reset", "Reiniciar") { lab.reset() }
        b("g", "Gravidade: ${lab.gravityLabel}") { lab.cycleGravity() }
        b("slow", "Câmera lenta", accent = lab.isSlowMotion) { lab.toggleSlowMotion() }
        b("ball", "Soltar bola") { lab.dropBall() }
        b("close", "Fechar", accent = true) { lab.close() }
        c.label("Pinçe para pegar · feche a mão para agarrar firme · empurre com os dedos · ${lab.objectCount} objetos, ${lab.awakeCount} em movimento",
            c.pad, c.y + 74f, 22f, XrTheme.textDim)
    }
}
