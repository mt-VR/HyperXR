package com.hyperxr.appworld

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.hyperxr.HyperXRApp
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.files.FileKind
import com.hyperxr.files.FileKinds
import com.hyperxr.files.MediaHost
import com.hyperxr.files.MediaItem
import com.hyperxr.files.openViewer
import com.hyperxr.input.InteractionSurface
import com.hyperxr.input.PointerEvent
import com.hyperxr.input.PointerPhase
import com.hyperxr.launcher.AppEntry
import com.hyperxr.launcher.AppKind
import com.hyperxr.math.Mat4
import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Ray
import com.hyperxr.math.Vec3
import com.hyperxr.rendering.GpuMesh
import com.hyperxr.rendering.Gl
import com.hyperxr.rendering.TextRenderer
import com.hyperxr.xr.LauncherActions
import com.hyperxr.xr.XrContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** A file being carried from one object to another (a book taken from the shelf). */
class FilePayload(val file: File) {
    val mime: String = when (FileKinds.kindOf(file.name)) {
        FileKind.IMAGE -> "image/" + FileKinds.extension(file.name).replace("jpg", "jpeg")
        FileKind.VIDEO -> "video/" + FileKinds.extension(file.name)
        FileKind.AUDIO -> "audio/" + FileKinds.extension(file.name)
        FileKind.PDF -> "application/pdf"
        FileKind.TEXT -> "text/plain"
        else -> "application/octet-stream"
    }
}

/**
 * App-to-World: Android apps as objects in the space around the user instead of only flat windows.
 *  - built-in adapters that work with unmodified apps: the playing media session becomes a speaker,
 *    notifications become a stack of letters with the apps' own actions, files become a shelf of
 *    books, any app becomes a portal that opens it and accepts files dropped on it (share);
 *  - apps that declare a spatial manifest (SpatialManifestParser) get their own objects whose
 *    gestures fire that app's intents and whose look follows the state its provider reports.
 * Objects are drawn with HyperXR's own primitives; placements persist across sessions.
 */
class AppWorld(private val ctx: XrContext, private val a: LauncherActions, private val media: MediaHost) {
    private val app = HyperXRApp.instance
    val objects = ArrayList<WorldObject>()
    /** Long-pinch moves objects instead of running their long-press action. */
    var organize = false
    private val file = File(app.filesDir, "appworld.json")
    private val io = Executors.newSingleThreadExecutor()
    private var anchorYaw = 0f
    private val anchor = Vec3()
    private var anchored = false
    private val labels = LinkedHashMap<String, Pair<Int, Float>>()

    // carried things
    var moving: WorldObject? = null; private set
    private var movingDistance = 1f
    var carried: FilePayload? = null; private set
    private var carrySource: WorldObject? = null
    private val lastRay = Ray()
    private var lastRayValid = false

    // ------------------------------------------------------------------ setup / persistence
    fun anchorTo(head: Pose, yaw: Float) {
        anchorYaw = yaw; anchor.set(head.position); anchored = true
        if (objects.isEmpty()) load()
        objects.forEach { it.placeFromSlot() }
    }

    private fun load() {
        val saved = try { if (file.isFile) JSONObject(file.readText()) else null } catch (_: Throwable) { null }
        val arr = saved?.optJSONArray("objects")
        if (arr == null) {
            // first run: music on the left, letters on the right, the shelf further right
            add(MusicObject(this).also { it.slot.set(-48f, 1.35f, -0.25f) })
            add(NotificationObject(this).also { it.slot.set(46f, 1.35f, -0.2f) })
            add(ShelfObject(this).also { it.slot.set(78f, 1.6f, -0.55f) })
            save(); return
        }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val obj = create(o.getString("key")) ?: continue
            obj.slot.set(o.getDouble("yaw").toFloat(), o.getDouble("dist").toFloat(), o.getDouble("dy").toFloat())
            add(obj)
        }
    }

    fun save() {
        val arr = JSONArray()
        for (o in objects) arr.put(JSONObject().put("key", o.key).put("yaw", o.slot.x.toDouble()).put("dist", o.slot.y.toDouble()).put("dy", o.slot.z.toDouble()))
        val text = JSONObject().put("objects", arr).toString()
        io.execute { try { file.writeText(text) } catch (t: Throwable) { Diagnostics.log("App-to-World save failed: ${t.message}") } }
    }

    /** Rebuilds an object from its key ("music", "notifications", "shelf", "portal:<app id>", "app:<pkg>:<id>"). */
    private fun create(key: String): WorldObject? = when {
        key == "music" -> MusicObject(this)
        key == "notifications" -> NotificationObject(this)
        key == "shelf" -> ShelfObject(this)
        key == HyperBot.KEY -> HyperBot(this)
        key.startsWith("portal:") -> a.allApps().firstOrNull { it.id == key.removePrefix("portal:") }?.let { PortalObject(this, it) }
        key.startsWith("app:") -> {
            val pkg = key.removePrefix("app:").substringBefore(':'); val id = key.substringAfterLast(':')
            SpatialRegistry.discover(app).firstOrNull { it.packageName == pkg }?.objects?.firstOrNull { it.id == id }?.let { ManifestObject(this, pkg, it) }
        }
        else -> null
    }

    fun has(key: String) = objects.any { it.key == key }

    fun add(o: WorldObject) { if (!has(o.key)) { objects += o; if (anchored) o.placeFromSlot() } }

    /** Adds [key] in front of the user (or removes it). */
    fun toggle(key: String) {
        val existing = objects.firstOrNull { it.key == key }
        if (existing != null) { existing.release(); objects.remove(existing); save(); return }
        val o = create(key) ?: return
        val f = ctx.input.head.orientation.rotate(Vec3(0f, 0f, -1f))
        o.slot.set(Math.toDegrees((atan2(-f.x, -f.z) - anchorYaw).toDouble()).toFloat(), 1.2f, -0.3f)
        add(o); save()
    }

    fun release() { objects.forEach { it.release() }; labels.values.forEach { Gl.deleteTexture(it.first) }; labels.clear() }

    // ------------------------------------------------------------------ frame
    val hits: List<InteractionSurface> get() = objects.map { it.hit }

    fun update(dt: Float) {
        // things scheduled for later (the HyperBot's chirps)
        clock += dt
        if (laterQueue.isNotEmpty()) {
            // taken out first: a callback may schedule more while they run
            val due = laterQueue.filter { it.first <= clock }
            laterQueue.removeAll(due)
            for ((_, fn) in due) runCatching(fn).onFailure { Diagnostics.log("App-to-World: ${it.message}") }
        }
        syncBot(dt)
        for (o in objects.toList()) guarded(o) { o.update(dt) }
    }

    /** Objects that failed this session (they stay off until the VR mode starts again). */
    private val broken = HashSet<String>()

    /**
     * Runs one object's step; an object that throws is taken off the world instead of closing the
     * whole VR mode, and the error goes to the diagnostics log.
     */
    private inline fun guarded(o: WorldObject, step: () -> Unit) {
        try { step() } catch (t: Throwable) {
            Diagnostics.log("App-to-World: ${o.key} failed and was removed: $t")
            broken += o.key
            objects.remove(o)
            if (moving === o) moving = null
            runCatching { o.release() }
        }
    }

    private var clock = 0f
    private val laterQueue = ArrayList<Pair<Float, () -> Unit>>()
    internal fun later(seconds: Float, fn: () -> Unit) { laterQueue += (clock + seconds) to fn }

    /** The HyperBot is on the table while its setting is on (checked once a second). */
    private var botCheck = 0f
    private fun syncBot(dt: Float) {
        botCheck -= dt
        if (botCheck > 0f || !anchored) return
        botCheck = 1f
        val on = app.settings.current.launcher.hyperBot
        val there = has(HyperBot.KEY)
        if (on && !there && HyperBot.KEY !in broken) {
            // in front of you, a little to the right, at desk height (it finds the table itself)
            add(HyperBot(this).also { it.slot.set(-18f, 0.55f, -0.42f) }); save()
        } else if (!on && there) toggle(HyperBot.KEY)
    }

    internal val input get() = ctx.input
    internal val audio get() = ctx.audio
    /** ARCore, when it runs (planes for the table under the HyperBot). */
    internal val arCore get() = ctx.camera as? com.hyperxr.mixedreality.ArCoreSession

    fun submit() {
        val head = ctx.input.head.position
        for (o in objects.toList()) guarded(o) {
            o.faceUser(head)
            o.draw()
        }
        // what is being carried follows the pointer
        val c = carried
        if (c != null && lastRayValid) {
            val p = lastRay.pointAt(0.55f)
            val target = objects.firstOrNull { it !== carrySource && it.accepts(c) && it.hit.pose.position.distanceTo(p) < 0.6f }
            box(p, Quat(), 0.05f, 0.14f, 0.1f, 0, bookColor(c.file), 0.2f)
            label("${c.file.name}${if (target != null) "  →  ${target.title}" else ""}", Vec3(p.x, p.y + 0.11f, p.z), 0.03f)
        }
    }

    /** Called every frame while a world object is captured by a pointer ([down] = still pressed). */
    fun drag(hit: WorldHit, ray: Ray, down: Boolean) {
        lastRay.origin.set(ray.origin); lastRay.direction.set(ray.direction); lastRayValid = true
        val m = moving
        if (m != null && down) {
            val p = ray.pointAt(movingDistance)
            m.base.set(p.x, p.y - m.hitCenterY, p.z)
        }
        if (!down) finishCarry()
    }

    internal fun startMove(o: WorldObject) {
        moving = o
        movingDistance = ctx.input.head.position.distanceTo(o.hit.pose.position).coerceIn(0.4f, 3f)
    }

    internal fun startCarry(o: WorldObject, p: FilePayload) { carried = p; carrySource = o }

    internal fun finishCarry() {
        moving?.let { m ->
            // store where it was put, relative to the launcher anchor
            val rel = Vec3.sub(m.base, anchor)
            val yaw = atan2(-rel.x, -rel.z)
            m.slot.set(Math.toDegrees((yaw - anchorYaw).toDouble()).toFloat(), kotlin.math.sqrt(rel.x * rel.x + rel.z * rel.z), m.base.y - anchor.y)
            moving = null; save()
        }
        val c = carried ?: return
        carried = null
        if (!lastRayValid) return
        // drop on whatever object the pointer is over (nearest along the ray)
        val uv = FloatArray(2)
        val target = objects.filter { it !== carrySource && it.accepts(c) }
            .mapNotNull { o -> com.hyperxr.input.SurfaceMath.raycast(o.hit, lastRay, uv).takeIf { !it.isNaN() }?.let { o to it } }
            .minByOrNull { it.second }?.first
        if (target != null) target.drop(c) else Diagnostics.log("App-to-World: nothing here accepts ${c.mime}")
        carrySource = null
    }

    // ------------------------------------------------------------------ services for objects
    internal val renderer get() = ctx.renderer
    internal val time get() = ctx.timeSeconds.toFloat()
    internal fun openPanel(p: com.hyperxr.spatialui.XrPanel) = media.openPanel(p, 1.1f)
    internal fun launch(e: AppEntry) = a.launch(e)
    internal fun appIcon(e: AppEntry): Bitmap = a.appIcon(e, 192)
    internal fun openFile(f: File) {
        openViewer(media, listOf(MediaItem.of(f)), 0)?.let { openPanel(it) } ?: Diagnostics.log("No viewer for ${f.name}")
    }
    internal fun activity() = media.activity
    internal fun openKeyboardFor(t: com.hyperxr.windows.KeyTarget) = media.openKeyboardFor(t)
    internal fun closeKeyboard() = media.closeKeyboard()

    /** Shares [p] with [pkg] (ACTION_SEND, read grant on a FileProvider URI). */
    internal fun share(pkg: String, p: FilePayload) = ctx.ui {
        try {
            val act = media.activity
            val uri: Uri = androidx.core.content.FileProvider.getUriForFile(act, "${act.packageName}.files", p.file)
            val i = Intent(Intent.ACTION_SEND).setType(p.mime).putExtra(Intent.EXTRA_STREAM, uri).setPackage(pkg)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            i.clipData = ClipData.newRawUri(p.file.name, uri)
            act.startActivity(i)
        } catch (t: Throwable) { Diagnostics.log("Share to $pkg failed: ${t.message}") }
    }

    internal fun fileUri(f: File): Uri = androidx.core.content.FileProvider.getUriForFile(media.activity, "${media.activity.packageName}.files", f)

    private val m16 = FloatArray(16)
    internal fun draw(mesh: GpuMesh, p: Vec3, q: Quat, sx: Float, sy: Float, sz: Float, tex: Int, color: Int, gloss: Float = 0.2f, metal: Float = 0f, unlit: Boolean = false) {
        val d = ctx.scene.addDraw()
        d.mesh = mesh; d.texture = tex
        d.color[0] = Color.red(color) / 255f; d.color[1] = Color.green(color) / 255f; d.color[2] = Color.blue(color) / 255f; d.color[3] = Color.alpha(color) / 255f
        d.transparent = d.color[3] < 1f
        d.gloss = gloss; d.metal = metal; d.unlit = unlit; d.castShadow = false
        Mat4.compose(p, q, sx, sy, sz, d.matrix)
    }

    internal fun box(p: Vec3, q: Quat, sx: Float, sy: Float, sz: Float, tex: Int, color: Int, gloss: Float = 0.2f) = draw(renderer.cube, p, q, sx, sy, sz, tex, color, gloss)

    /** Text floating at [p], facing the user, [h] metres tall (textures cached by text). */
    internal fun label(text: String, p: Vec3, h: Float, color: Int = Color.WHITE) {
        if (text.isEmpty()) return
        val key = "$color|$text"
        val (tex, aspect) = labels.getOrPut(key) {
            if (labels.size > 80) labels.remove(labels.keys.first())?.let { Gl.deleteTexture(it.first) }
            val b = TextRenderer.render(text, 44f, color, isBold = true, maxWidth = 900)
            (Gl.uploadBitmap(b) to b.width.toFloat() / b.height).also { b.recycle() }
        }
        val pn = ctx.scene.addPanel()
        pn.texture = tex
        pn.pose.position.set(p)
        val e = ctx.input.head.position
        Quat.fromAxisAngle(0f, 1f, 0f, atan2(-(p.x - e.x), -(p.z - e.z)), pn.pose.orientation)
        pn.height = h; pn.width = h * aspect
        pn.radius = h * 0.3f
        pn.tint[3] = 0.95f
    }

    internal fun image(tex: Int, p: Vec3, q: Quat, w: Float, h: Float) {
        val pn = ctx.scene.addPanel()
        pn.texture = tex; pn.pose.position.set(p); pn.pose.orientation.set(q)
        pn.width = w; pn.height = h; pn.radius = w * 0.08f
    }

    internal fun bookColor(f: File): Int = when (FileKinds.kindOf(f.name)) {
        FileKind.IMAGE -> Color.rgb(46, 150, 110); FileKind.VIDEO -> Color.rgb(196, 70, 80); FileKind.AUDIO -> Color.rgb(150, 90, 200)
        FileKind.PDF, FileKind.TEXT -> Color.rgb(70, 110, 170); FileKind.APK -> Color.rgb(70, 160, 80); else -> Color.rgb(150, 120, 80)
    }

    internal fun placeSlot(o: WorldObject) {
        val yaw = anchorYaw + Math.toRadians(o.slot.x.toDouble()).toFloat()
        o.base.set(anchor.x - sin(yaw) * o.slot.y, anchor.y + o.slot.z, anchor.z - cos(yaw) * o.slot.y)
    }
}

/** Pointer surface of a world object: a rectangle around it, always facing the user. */
class WorldHit(val obj: WorldObject) : InteractionSurface {
    override val surfaceId = "world:${obj.key}"
    override val pose = Pose()
    override val widthMeters: Float get() = obj.hitW
    override val heightMeters: Float get() = obj.hitH
    override val hitPriority: Int get() = 0
    var hovered = false; var hoverU = 0.5f; var hoverV = 0.5f
    private var downAt = 0L
    private var downU = 0f; private var downV = 0f
    private var longFired = false

    override fun onPointerEvent(event: PointerEvent) {
        hoverU = event.u; hoverV = event.v
        when (event.phase) {
            PointerPhase.HOVER, PointerPhase.MOVE -> {
                hovered = true
                if (event.phase == PointerPhase.MOVE && downAt != 0L && !longFired && event.timestampNs - downAt > 550_000_000L) {
                    longFired = true
                    // a long pinch either carries something out of the object, or moves it
                    if (obj.world.organize || !obj.onLongPress(downU, downV)) obj.world.startMove(obj)
                }
            }
            PointerPhase.DOWN -> { downAt = event.timestampNs; downU = event.u; downV = event.v; longFired = false }
            PointerPhase.UP -> {
                if (downAt != 0L && !longFired) obj.onTap(event.u, event.v)
                downAt = 0L
                obj.world.finishCarry()
            }
            PointerPhase.CANCEL -> { downAt = 0L; obj.world.finishCarry() }
            PointerPhase.EXIT -> hovered = false
        }
    }
}

/** Base of every object: placement, facing, pointer area and drawing. */
abstract class WorldObject(val world: AppWorld, val key: String) {
    abstract val title: String
    /** Placement relative to the launcher anchor: x = yaw (degrees), y = distance, z = height from the eyes. */
    val slot = Vec3(0f, 1.3f, -0.3f)
    /** Bottom centre of the object. */
    val base = Vec3()
    val facing = Quat()
    open val hitW = 0.36f
    open val hitH = 0.42f
    open val hitCenterY = 0.2f
    val hit = WorldHit(this)

    fun placeFromSlot() = world.placeSlot(this)

    fun faceUser(eye: Vec3) {
        Quat.fromAxisAngle(0f, 1f, 0f, atan2(-(base.x - eye.x), -(base.z - eye.z)), facing)
        hit.pose.orientation.set(facing)
        hit.pose.position.set(base.x, base.y + hitCenterY, base.z)
    }

    /** Point in the object's frame (x right, y up, z towards the user). */
    protected fun at(x: Float, y: Float, z: Float): Vec3 = facing.rotate(Vec3(x, y, z)).add(base)

    abstract fun draw()
    open fun update(dt: Float) {}
    open fun onTap(u: Float, v: Float) {}
    /** Long pinch: true when handled (otherwise the object is moved). */
    open fun onLongPress(u: Float, v: Float): Boolean = false
    open fun accepts(p: FilePayload): Boolean = false
    open fun drop(p: FilePayload) {}
    open fun release() {}
}

// ====================================================================== built-in adapters

/** The playing media session as a speaker: cover on top, tap left/middle/right = previous/play/next. */
class MusicObject(world: AppWorld) : WorldObject(world, "music") {
    override val title = "Música"
    private var artTex = 0
    private var artVersion = -1
    private var pulse = 0f
    override val hitW = 0.34f
    override val hitH = 0.62f
    override val hitCenterY = 0.3f

    private var retry = 0f
    override fun update(dt: Float) {
        val n = MediaHub.now
        // access may be granted while HyperXR runs: keep trying to follow the media sessions
        retry += dt
        if (n == null && retry > 5f) { retry = 0f; world.activity().runOnUiThread { MediaHub.start(world.activity()) } }
        if (MediaHub.version != artVersion) {
            artVersion = MediaHub.version
            Gl.deleteTexture(artTex); artTex = 0
            n?.art?.let { artTex = Gl.uploadBitmap(it) }
        }
        pulse += dt * if (n?.playing == true) 1f else 0f
    }

    override fun draw() {
        val r = world.renderer
        val n = MediaHub.now
        val playing = n?.playing == true
        // wooden cabinet, front baffle, woofer and tweeter
        world.box(at(0f, 0.17f, 0f), facing, 0.22f, 0.34f, 0.2f, 0, Color.rgb(96, 62, 40), 0.35f)
        world.box(at(0f, 0.17f, 0.101f), facing, 0.2f, 0.32f, 0.004f, 0, Color.rgb(28, 28, 30), 0.1f)
        val beat = if (playing) 1f + 0.06f * kotlin.math.abs(sin(pulse * 7.5f)) else 1f
        val faceQ = facing.mul(Quat.fromAxisAngle(1f, 0f, 0f, 1.5708f))
        world.draw(r.cylinder, at(0f, 0.11f, 0.106f), faceQ, 0.15f * beat, 0.012f, 0.15f * beat, 0, Color.rgb(50, 50, 54), 0.5f)
        world.draw(r.cylinder, at(0f, 0.11f, 0.112f), faceQ, 0.05f, 0.012f, 0.05f, 0, Color.rgb(70, 70, 76), 0.8f, 0.6f)
        world.draw(r.cylinder, at(0f, 0.26f, 0.106f), faceQ, 0.05f, 0.01f, 0.05f, 0, Color.rgb(160, 160, 168), 0.9f, 1f)
        if (playing) world.draw(r.sphere, at(0.08f, 0.31f, 0.105f), facing, 0.012f, 0.012f, 0.006f, 0, Color.rgb(80, 220, 120), unlit = true)
        // cover art standing on top
        if (artTex != 0) world.image(artTex, at(0f, 0.46f, 0f), facing, 0.2f, 0.2f)
        val head = when {
            !HxNotificationListener.enabled(world.activity()) -> "Música: permita o acesso às notificações (toque)"
            n == null -> "Nada tocando: abra um app de música"
            else -> "${n.title}${if (n.artist.isNotEmpty()) " — ${n.artist}" else ""}"
        }
        world.label(head, at(0f, if (artTex != 0) 0.6f else 0.42f, 0f), 0.028f)
        if (n != null) world.label(if (hit.hovered) "anterior   ·   ${if (playing) "pausar" else "tocar"}   ·   próxima" else "", at(0f, -0.04f, 0.05f), 0.022f, Color.rgb(200, 200, 205))
    }

    override fun onTap(u: Float, v: Float) {
        if (!HxNotificationListener.enabled(world.activity())) { world.activity().runOnUiThread { HxNotificationListener.openSettings(world.activity()) }; return }
        world.activity().runOnUiThread {
            MediaHub.start(world.activity())
            when { u < 0.33f -> MediaHub.previous(); u > 0.67f -> MediaHub.next(); else -> MediaHub.playPause() }
        }
    }

    override fun release() { Gl.deleteTexture(artTex); artTex = 0 }
}

/** Notifications as a stack of letters; tap opens them with the apps' own actions. */
class NotificationObject(world: AppWorld) : WorldObject(world, "notifications") {
    override val title = "Notificações"
    override val hitW = 0.32f
    override val hitH = 0.3f
    override val hitCenterY = 0.12f

    override fun draw() {
        val list = NotificationHub.list()
        val n = list.size.coerceAtMost(8)
        // a tray with envelopes, newest on top
        world.box(at(0f, 0.015f, 0f), facing, 0.3f, 0.03f, 0.22f, 0, Color.rgb(90, 60, 40), 0.3f)
        for (i in 0 until n) {
            val tilt = Quat.fromAxisAngle(0f, 1f, 0f, (i % 3 - 1) * 0.08f)
            world.box(at((i % 2) * 0.01f, 0.034f + i * 0.008f, 0f), facing.mul(tilt), 0.24f, 0.006f, 0.16f, 0, if (i == n - 1) Color.rgb(250, 248, 240) else Color.rgb(236, 230, 214), 0.1f)
        }
        if (n > 0) world.box(at(0f, 0.038f + (n - 1) * 0.008f, 0.02f), facing, 0.05f, 0.003f, 0.05f, 0, Color.rgb(190, 40, 40), 0.4f) // wax seal
        val text = when {
            !HxNotificationListener.enabled(world.activity()) -> "Notificações: permita o acesso (toque)"
            list.isEmpty() -> "Sem notificações"
            else -> "${list.size} notificaç${if (list.size == 1) "ão" else "ões"} · ${list.first().appLabel}: ${list.first().title}"
        }
        world.label(text, at(0f, 0.2f + n * 0.008f, 0f), 0.026f)
    }

    override fun onTap(u: Float, v: Float) {
        if (!HxNotificationListener.enabled(world.activity())) { world.activity().runOnUiThread { HxNotificationListener.openSettings(world.activity()) }; return }
        world.openPanel(NotificationPanel(world))
    }
}

/** Recent files as books on a shelf: tap a book to open it, long-pinch to carry it to an app. */
class ShelfObject(world: AppWorld) : WorldObject(world, "shelf") {
    override val title = "Estante"
    override val hitW = 0.7f
    override val hitH = 0.62f
    override val hitCenterY = 0.31f
    @Volatile private var books: List<File> = emptyList()
    private var scanAt = -100f

    override fun update(dt: Float) {
        if (world.time - scanAt < 20f) return
        scanAt = world.time
        Thread {
            val ext = android.os.Environment.getExternalStorageDirectory()
            val dirs = listOf("Download", "Documents", "DCIM/Camera", "Pictures", "Movies", "Music").map { File(ext, it) }
            books = dirs.flatMap { d -> d.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") } }
                .sortedByDescending { it.lastModified() }.take(PER_SHELF * 2)
        }.start()
    }

    private fun bookAt(u: Float, v: Float): File? {
        val shelf = if (v < 0.5f) 0 else 1
        val i = (u * PER_SHELF).toInt().coerceIn(0, PER_SHELF - 1)
        return books.getOrNull(shelf * PER_SHELF + i)
    }

    override fun draw() {
        val wood = Color.rgb(120, 82, 52)
        // frame: sides, top, two shelves
        for (sx in floatArrayOf(-0.34f, 0.34f)) world.box(at(sx, 0.3f, 0f), facing, 0.02f, 0.6f, 0.2f, 0, wood, 0.3f)
        for (y in floatArrayOf(0.01f, 0.3f, 0.59f)) world.box(at(0f, y, 0f), facing, 0.7f, 0.02f, 0.2f, 0, wood, 0.3f)
        world.box(at(0f, 0.3f, -0.1f), facing, 0.7f, 0.6f, 0.01f, 0, Color.rgb(96, 64, 40), 0.2f)
        val hovered = if (hit.hovered) bookAt(hit.hoverU, hit.hoverV) else null
        for ((idx, f) in books.withIndex()) {
            val shelf = idx / PER_SHELF; val i = idx % PER_SHELF
            val x = -0.3f + (i + 0.5f) * (0.6f / PER_SHELF)
            val h = 0.2f + (f.name.hashCode() and 7) * 0.008f
            val y = (if (shelf == 0) 0.32f else 0.03f) + h / 2   // top shelf = first half of v
            val pull = if (f == hovered) 0.04f else 0f
            world.box(at(x, y, pull), facing, 0.045f, h, 0.15f, 0, world.bookColor(f), 0.25f)
        }
        world.label(hovered?.let { "${it.name}  ·  toque: abrir · segure: levar" } ?: "Estante de arquivos (${books.size})", at(0f, 0.7f, 0f), 0.026f)
    }

    override fun onTap(u: Float, v: Float) { bookAt(u, v)?.let { world.openFile(it) } }

    override fun onLongPress(u: Float, v: Float): Boolean {
        val f = bookAt(u, v) ?: return false
        world.startCarry(this, FilePayload(f))
        return true
    }

    private companion object { const val PER_SHELF = 9 }
}

/** Any app as an object: a pedestal with its icon floating above. Tap opens it; drop a file to share. */
class PortalObject(world: AppWorld, val entry: AppEntry) : WorldObject(world, "portal:${entry.id}") {
    override val title = entry.title
    private var iconTex = 0
    override val hitW = 0.28f
    override val hitH = 0.5f
    override val hitCenterY = 0.25f

    override fun draw() {
        val r = world.renderer
        if (iconTex == 0) iconTex = Gl.uploadBitmap(world.appIcon(entry))
        world.draw(r.cylinder, at(0f, 0.06f, 0f), facing, 0.22f, 0.12f, 0.22f, 0, Color.rgb(64, 66, 72), 0.6f, 0.3f)
        world.draw(r.cylinder, at(0f, 0.125f, 0f), facing, 0.18f, 0.01f, 0.18f, 0, Color.rgb(120, 170, 255), unlit = true)
        val bob = sin(world.time * 1.6f) * 0.015f
        val spin = facing.mul(Quat.fromAxisAngle(0f, 1f, 0f, sin(world.time * 0.8f) * 0.35f))
        world.image(iconTex, at(0f, 0.3f + bob, 0f), spin, 0.16f, 0.16f)
        world.label(entry.title + if (world.carried != null && accepts(world.carried!!)) "  ·  solte para compartilhar" else "", at(0f, 0.46f, 0f), 0.026f)
    }

    override fun onTap(u: Float, v: Float) = world.launch(entry)
    override fun accepts(p: FilePayload) = entry.kind == AppKind.ANDROID_PACKAGE
    override fun drop(p: FilePayload) = world.share(entry.target.substringBefore('/'), p)
    override fun release() { Gl.deleteTexture(iconTex); iconTex = 0 }
}

/** An object declared by a compatible app (spatial manifest): its gestures run that app's intents. */
class ManifestObject(world: AppWorld, val pkg: String, val spec: WorldObjectSpec) : WorldObject(world, "app:$pkg:${spec.id}") {
    override val title = spec.label
    override val hitW = spec.size * 1.3f
    override val hitH = spec.size * 1.5f
    override val hitCenterY = spec.size * 0.6f
    @Volatile private var state: Map<String, String> = emptyMap()
    private var pollAt = -100f

    override fun update(dt: Float) {
        val uri = spec.stateUri ?: return
        if (world.time - pollAt < 2f) return
        pollAt = world.time
        Thread {
            state = try {
                world.activity().contentResolver.query(Uri.parse(uri), null, null, null, null)?.use { c ->
                    val k = c.getColumnIndex("key"); val v = c.getColumnIndex("value")
                    buildMap { while (c.moveToNext() && size < 16) put(c.getString(k), c.getString(v).take(120)) }
                }.orEmpty()
            } catch (t: Throwable) { emptyMap() }
        }.start()
    }

    override fun draw() {
        val r = world.renderer
        val s = spec.size
        val on = state["on"] == "1" || state["on"] == "true"
        val col = spec.color
        when (spec.model) {
            WorldModel.BOX -> world.box(at(0f, s / 2, 0f), facing, s, s, s, 0, col)
            WorldModel.SPHERE -> world.draw(r.sphere, at(0f, s / 2, 0f), facing, s, s, s, 0, col, 0.6f)
            WorldModel.CYLINDER -> world.draw(r.cylinder, at(0f, s / 2, 0f), facing, s * 0.7f, s, s * 0.7f, 0, col, 0.5f)
            WorldModel.SPEAKER -> { world.box(at(0f, s / 2, 0f), facing, s * 0.65f, s, s * 0.6f, 0, col, 0.3f); world.draw(r.cylinder, at(0f, s * 0.35f, s * 0.31f), facing.mul(Quat.fromAxisAngle(1f, 0f, 0f, 1.5708f)), s * 0.45f, 0.01f, s * 0.45f, 0, Color.rgb(40, 40, 44), 0.5f) }
            WorldModel.SHELF -> { world.box(at(0f, s * 0.5f, 0f), facing, s * 1.2f, 0.02f, s * 0.3f, 0, col); world.box(at(0f, 0.01f, 0f), facing, s * 1.2f, 0.02f, s * 0.3f, 0, col) }
            WorldModel.LAMP -> {
                world.draw(r.cylinder, at(0f, 0.02f, 0f), facing, s * 0.4f, 0.04f, s * 0.4f, 0, Color.rgb(60, 60, 64), 0.6f, 0.5f)
                world.draw(r.cylinder, at(0f, s * 0.45f, 0f), facing, 0.02f, s * 0.85f, 0.02f, 0, Color.rgb(60, 60, 64), 0.6f, 0.5f)
                world.draw(r.cylinder, at(0f, s * 0.92f, 0f), facing, s * 0.6f, s * 0.3f, s * 0.6f, 0, if (on) col else Color.rgb(90, 90, 92), 0.2f, unlit = on)
            }
            WorldModel.PLANT -> { world.draw(r.cylinder, at(0f, s * 0.2f, 0f), facing, s * 0.5f, s * 0.4f, s * 0.5f, 0, Color.rgb(160, 80, 50)); world.draw(r.sphere, at(0f, s * 0.65f, 0f), facing, s * 0.8f, s * 0.6f, s * 0.8f, 0, col) }
            WorldModel.SCREEN -> world.box(at(0f, s / 2, 0f), facing, s * 1.6f, s * 0.9f, 0.02f, 0, if (on) col else Color.rgb(20, 20, 22), 0.8f)
            WorldModel.PEDESTAL -> world.draw(r.cylinder, at(0f, s * 0.3f, 0f), facing, s * 0.8f, s * 0.6f, s * 0.8f, 0, col, 0.5f)
        }
        val text = state["text"]?.let { "${spec.label}: $it" } ?: spec.label
        val hint = if (hit.hovered) spec.actions.joinToString(" · ") { "${when (it.gesture) { Gesture.PINCH -> "toque"; Gesture.LONG_PINCH -> "segure"; Gesture.DROP -> "solte arquivo" }}: ${it.label}" } else ""
        world.label(text, at(0f, s * 1.15f + 0.04f, 0f), 0.026f)
        if (hint.isNotEmpty()) world.label(hint, at(0f, -0.03f, 0.05f), 0.02f, Color.rgb(200, 200, 205))
    }

    private fun fire(action: WorldAction, payload: FilePayload? = null) = world.activity().runOnUiThread {
        val t = action.target
        val i = Intent(t.action).setPackage(pkg)
        t.component?.let { c -> i.setClassName(pkg, if (c.contains('/')) c.substringAfter('/').let { if (it.startsWith(".")) pkg + it else it } else if (c.startsWith(".")) pkg + c else c) }
        t.extras.forEach { (k, v) -> i.putExtra(k, v) }
        i.putExtra("com.hyperxr.extra.OBJECT", spec.id)
        if (payload != null) {
            val uri = world.fileUri(payload.file)
            i.putExtra(Intent.EXTRA_STREAM, uri).setType(payload.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            i.clipData = ClipData.newRawUri(payload.file.name, uri)
        }
        try {
            if (t.type == TargetType.ACTIVITY) world.activity().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else world.activity().sendBroadcast(i)
        } catch (e: Throwable) { Diagnostics.log("App-to-World action ${spec.id}/${action.id} failed: ${e.message}") }
    }

    override fun onTap(u: Float, v: Float) { spec.actions.firstOrNull { it.gesture == Gesture.PINCH }?.let { fire(it) } }
    override fun onLongPress(u: Float, v: Float): Boolean {
        val a = spec.actions.firstOrNull { it.gesture == Gesture.LONG_PINCH } ?: return false
        fire(a); return true
    }
    override fun accepts(p: FilePayload) = spec.actions.any { it.gesture == Gesture.DROP && SpatialManifestParser.mimeMatches(it.accepts ?: "", p.mime) }
    override fun drop(p: FilePayload) { spec.actions.firstOrNull { it.gesture == Gesture.DROP && SpatialManifestParser.mimeMatches(it.accepts ?: "", p.mime) }?.let { fire(it, p) } }
}

/** Apps that declare objects for the world (cached; refreshed when packages change). */
object SpatialRegistry {
    @Volatile private var cache: List<SpatialManifest>? = null
    fun invalidate() { cache = null }

    fun discover(ctx: android.content.Context): List<SpatialManifest> = cache ?: run {
        val pm = ctx.packageManager
        val intent = Intent(SpatialManifestParser.ACTION_DISCOVER)
        val infos = pm.queryBroadcastReceivers(intent, android.content.pm.PackageManager.GET_META_DATA).map { it.activityInfo } +
            pm.queryIntentActivities(intent, android.content.pm.PackageManager.GET_META_DATA).map { it.activityInfo }
        val out = ArrayList<SpatialManifest>()
        for (ai in infos.distinctBy { it.packageName }) {
            val res = ai.metaData?.getInt(SpatialManifestParser.META_DATA, 0) ?: 0
            if (res == 0) continue
            try {
                val text = pm.getResourcesForApplication(ai.packageName).openRawResource(res).use { it.readBytes().toString(Charsets.UTF_8) }
                SpatialManifestParser.parse(ai.packageName, text).onSuccess { out += it }.onFailure { Diagnostics.log("Spatial manifest of ${ai.packageName}: ${it.message}") }
            } catch (t: Throwable) { Diagnostics.log("Spatial manifest of ${ai.packageName} unreadable: ${t.message}") }
        }
        out.also { cache = it }
    }
}
