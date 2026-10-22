package com.hyperxr.gameruntime

import android.graphics.BitmapFactory
import android.os.Process
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.diagnostics.Stage
import com.hyperxr.gltf.GltfLoader
import com.hyperxr.handtracking.HandJoint
import com.hyperxr.luajit.LuaHost
import com.hyperxr.luajit.LuaVm
import com.hyperxr.math.Mat4
import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import com.hyperxr.permissions.HyperPermission
import com.hyperxr.rendering.Background
import com.hyperxr.rendering.GpuModel
import com.hyperxr.rendering.PanelSurface
import com.hyperxr.rendering.TextRenderer
import com.hyperxr.sdk.HyperxPackage
import com.hyperxr.sdkhost.InstalledPackage
import com.hyperxr.xr.XrContent
import com.hyperxr.xr.XrContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * Runs one `.hyperx` package inside the immersive engine. Lives in the `:game` process.
 * Permissions are enforced twice: the Lua bootstrap only exposes granted APIs, and every host call
 * re-checks the grant here (defence in depth against a sandbox escape).
 */
class GameRuntimeContent(private val pkg: InstalledPackage, private val onExit: (String?) -> Unit) : XrContent, LuaHost {
    private lateinit var ctx: XrContext
    private lateinit var vm: LuaVm
    private val granted = pkg.grants.granted
    private val models = HashMap<Int, GpuModel>()
    private var nextModel = 100_000
    private val panels = HashMap<Int, PanelSurface>()
    private val net = Executors.newFixedThreadPool(2)
    private val events = ConcurrentLinkedQueue<NetResult>()
    private class NetResult(val id: Int, val status: Int, val body: String)

    private val rig = Pose()
    private val rigInv = Pose()
    private val rigMatrix = FloatArray(16)
    private val tmpM = FloatArray(16)
    private val tmpV = Vec3()
    @Volatile private var frameStartNs = 0L
    private var exited = false

    // ---------------------------------------------------------------- lifecycle
    override fun onGlInit(ctx: XrContext) {
        this.ctx = ctx
        val assets = ctx.activity.assets
        val sdk = assets.list("hxsdk").orEmpty().filter { it.endsWith(".lua") }
            .associate { it.removeSuffix(".lua") to assets.open("hxsdk/$it").use { s -> s.readBytes().toString(Charsets.UTF_8) } }
        val m = pkg.manifest
        val strict = pkg.strictInterpreter && HyperPermission.UNSAFE !in granted
        vm = LuaVm.create(this, sdk, LuaVm.Config(
            m.id, m.name, m.version, m.mode, m.entry,
            permissions = granted.map { it.key },
            strict = strict,
            windowW = m.window?.width ?: 0.9f, windowH = m.window?.height ?: 0.6f,
        ))
        if (!vm.alive) { finish("Runtime failed to start"); return }
        startWatchdog()
        Diagnostics.log("Started ${m.id} (${if (strict) "strict interpreter" else "JIT"}, LuaJIT ${LuaVm.version()})")
    }

    private fun startWatchdog() {
        Thread({
            while (!exited) {
                Thread.sleep(500)
                val start = frameStartNs
                if (start != 0L && System.nanoTime() - start > WATCHDOG_NS) {
                    val msg = "Watchdog: a frame of ${pkg.manifest.id} ran longer than ${WATCHDOG_NS / 1_000_000_000} s; the game process was terminated."
                    writeCrash(msg)
                    Process.killProcess(Process.myPid())
                }
            }
        }, "hx-watchdog").apply { isDaemon = true }.start()
    }

    override fun onFrame(ctx: XrContext, dt: Float) {
        if (!::vm.isInitialized || !vm.alive) return
        while (true) {
            val e = events.poll() ?: break
            vm.event("net", e.id, e.status, e.body)
        }
        writeInput(dt)
        frameStartNs = System.nanoTime()
        val t0 = frameStartNs
        val draws = vm.frame(dt)
        frameStartNs = 0L
        Diagnostics.record(Stage.LUA_FRAME, System.nanoTime() - t0)
        if (draws < 0) {
            Diagnostics.log("Lua frame failed: ${vm.crashReason()}")
        }
        readHeader()
        buildScene()
    }

    override fun onAction(ctx: XrContext, action: com.hyperxr.settings.ControllerAction, pressed: Boolean, side: com.hyperxr.joycon.JoyConSide?) {
        // HOME long-press style exit is handled by the activity; nothing extra here.
    }

    override fun onPause() { if (::vm.isInitialized) vm.event("pause", 0, 0, "") }
    override fun onResume() { if (::vm.isInitialized) vm.event("resume", 0, 0, "") }

    override fun onGlRelease() {
        exited = true
        if (::vm.isInitialized) vm.destroy()
        models.values.forEach { it.release() }
        com.hyperxr.rendering.Gl.deleteTexture(reticleTex)
        panels.values.forEach { it.release() }
        net.shutdownNow()
    }

    private fun finish(reason: String?) {
        if (exited) return
        exited = true
        reason?.let { writeCrash(it) }
        ctx.ui { onExit(reason) }
    }

    private fun writeCrash(msg: String) {
        val dir = File(ctx.activity.filesDir, "crash").apply { mkdirs() }
        File(dir, "${pkg.manifest.id}.txt").writeText(msg)
    }

    // ---------------------------------------------------------------- buffers
    private fun writeInput(dt: Float) {
        val inb = vm.input
        val i = ctx.input
        inb.put(LuaVm.IN_TIME, ctx.timeSeconds.toFloat())
        inb.put(LuaVm.IN_DT, dt)
        inb.put(LuaVm.IN_MR, if (ctx.passthroughAvailable) 1f else 0f)
        inb.put(LuaVm.IN_HANDS_AVAILABLE, if (i.handsAvailable) 1f else 0f)
        inb.put(LuaVm.IN_HEAD_POS, i.head.position.x); inb.put(LuaVm.IN_HEAD_POS + 1, i.head.position.y); inb.put(LuaVm.IN_HEAD_POS + 2, i.head.position.z)
        val q = i.head.orientation
        inb.put(LuaVm.IN_HEAD_ROT, q.x); inb.put(LuaVm.IN_HEAD_ROT + 1, q.y); inb.put(LuaVm.IN_HEAD_ROT + 2, q.z); inb.put(LuaVm.IN_HEAD_ROT + 3, q.w)
        val handsAllowed = HyperPermission.HAND_TRACKING in granted
        for (h in 0..1) {
            val base = if (h == 0) LuaVm.IN_HAND_LEFT else LuaVm.IN_HAND_RIGHT
            val s = i.hands[h]
            val usable = handsAllowed && s.isUsable
            inb.put(base, if (usable) 1f else 0f)
            inb.put(base + 1, if (usable) s.confidence else 0f)
            inb.put(base + 2, if (usable) s.pinchStrength else 0f)
            inb.put(base + 3, if (usable) s.gesture.id.toFloat() else 0f)
            inb.put(base + 4, s.depthSource.ordinal.toFloat())
            if (usable) for (j in 0 until HandJoint.COUNT) {
                val p = s.joints[j]
                inb.put(base + 5 + j * 3, p.x); inb.put(base + 6 + j * 3, p.y); inb.put(base + 7 + j * 3, p.z)
            }
            val cb = if (h == 0) LuaVm.IN_CTRL_LEFT else LuaVm.IN_CTRL_RIGHT
            val c = i.controllers[h]
            val inputAllowed = HyperPermission.INPUT in granted
            inb.put(cb, if (c.connected && inputAllowed) 1f else 0f)
            inb.put(cb + 1, c.pose.position.x); inb.put(cb + 2, c.pose.position.y); inb.put(cb + 3, c.pose.position.z)
            val cq = c.pose.orientation
            inb.put(cb + 4, cq.x); inb.put(cb + 5, cq.y); inb.put(cb + 6, cq.z); inb.put(cb + 7, cq.w)
            inb.put(cb + 8, if (inputAllowed) c.buttons.toFloat() else 0f)
            inb.put(cb + 9, c.stickX); inb.put(cb + 10, c.stickY)
            inb.put(cb + 11, if (c.positionFromHand) 1f else 0f)
        }
        inb.put(LuaVm.IN_GAZE, 1f)
        inb.put(LuaVm.IN_GAZE + 1, if (i.tapDown) 1f else 0f)
        val c = ctx.settings.controllers
        inb.put(LuaVm.IN_GAZE + 2, if (c.dwellClick) c.dwellMs / 1000f else 0f)
        // hands, head and controllers are in tracking space; bootstrap.lua converts them with the rig
    }

    private fun readHeader() {
        val h = vm.header
        rig.position.set(h.get(0), h.get(1), h.get(2))
        rig.orientation.set(h.get(3), h.get(4), h.get(5), h.get(6)).let { if (it.w == 0f && it.x == 0f && it.y == 0f && it.z == 0f) it.identity() else it.normalize() }
        rig.inverse(rigInv)
        Mat4.compose(rigInv.position, rigInv.orientation, 1f, 1f, 1f, rigMatrix)
        val sc = ctx.scene
        sc.lightDir[0] = h.get(7); sc.lightDir[1] = h.get(8); sc.lightDir[2] = h.get(9)
        rigInv.orientation.rotate(tmpV.set(sc.lightDir[0], sc.lightDir[1], sc.lightDir[2])).let { sc.lightDir[0] = it.x; sc.lightDir[1] = it.y; sc.lightDir[2] = it.z }
        for (k in 0..2) { sc.lightColor[k] = h.get(10 + k); sc.ambient[k] = h.get(13 + k) }
        for (k in 0..3) sc.clear[k] = h.get(16 + k)
        sc.background = when (h.get(20).toInt()) { 0 -> Background.COLOR; 2 -> Background.PASSTHROUGH; else -> Background.ENVIRONMENT }
        if (h.get(LuaVm.H_RECENTER) > 0.5f) { h.put(LuaVm.H_RECENTER, 0f); ctx.requestRecenter() }
        if (h.get(LuaVm.H_EXIT) > 0.5f) { h.put(LuaVm.H_EXIT, 0f); finish(null) }
        if (h.get(LuaVm.H_OVERFLOW) > 0f) Diagnostics.log("${pkg.manifest.id}: draw/UI buffer overflow (${h.get(LuaVm.H_OVERFLOW).toInt()} commands dropped)")
    }

    private val pos = Vec3()
    private val rot = Quat()
    private val local = FloatArray(16)

    private fun buildScene() {
        val sc = ctx.scene
        val d = vm.draws
        val n = vm.header.get(LuaVm.H_DRAWS).toInt()
        for (k in 0 until n) {
            val o = k * LuaVm.DRAW_STRIDE
            val meshId = d.get(o).toInt()
            val tex = d.get(o + 1).toInt()
            val flags = d.get(o + 2).toInt()
            val item = sc.addDraw()
            if (flags and FLAG_MODEL != 0) {
                item.model = models[meshId] ?: continue
            } else item.mesh = ctx.renderer.meshFor(meshId)
            item.texture = if (tex > 0) ctx.images.texture(tex) else 0
            item.color[0] = d.get(o + 3); item.color[1] = d.get(o + 4); item.color[2] = d.get(o + 5); item.color[3] = d.get(o + 6)
            item.unlit = flags and FLAG_UNLIT != 0
            item.doubleSided = flags and FLAG_DOUBLE != 0
            item.transparent = flags and FLAG_TRANSPARENT != 0
            pos.set(d.get(o + 7), d.get(o + 8), d.get(o + 9))
            rot.set(d.get(o + 10), d.get(o + 11), d.get(o + 12), d.get(o + 13))
            Mat4.compose(pos, rot, d.get(o + 14), d.get(o + 15), d.get(o + 16), local)
            android.opengl.Matrix.multiplyMM(item.matrix, 0, rigMatrix, 0, local, 0)
        }
        paintPanels()
        submitReticle()
    }

    private var reticleTex = 0
    /** Head-locked gaze dot so gaze/dwell selection has a visible aim point. */
    private fun submitReticle() {
        if (ctx.input.controllers.any { it.connected }) return
        if (reticleTex == 0) {
            val b = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(b)
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            p.color = android.graphics.Color.argb(170, 0, 0, 0); cv.drawCircle(32f, 32f, 28f, p)
            p.color = android.graphics.Color.WHITE; cv.drawCircle(32f, 32f, 18f, p)
            reticleTex = com.hyperxr.rendering.Gl.uploadBitmap(b); b.recycle()
        }
        val head = ctx.input.head
        val item = ctx.scene.addPanel()
        item.texture = reticleTex
        head.orientation.rotate(tmpV.set(0f, 0f, -1.2f), item.pose.position).add(head.position)
        item.pose.orientation.set(head.orientation)
        item.width = 0.014f; item.height = 0.014f; item.radius = 0f
        item.overlay = true
    }

    private fun paintPanels() {
        val p = vm.panels
        val u = vm.ui
        val np = vm.header.get(LuaVm.H_PANELS).toInt()
        val nu = vm.header.get(LuaVm.H_UI).toInt()
        for (k in 0 until np) {
            val o = k * LuaVm.PANEL_STRIDE
            val id = p.get(o).toInt()
            val visible = p.get(o + 12) > 0.5f
            val dirty = p.get(o + 13) > 0.5f
            val pxW = p.get(o + 10).toInt().coerceIn(8, 4096); val pxH = p.get(o + 11).toInt().coerceIn(8, 4096)
            if (!visible) continue
            val surf = panels.getOrPut(id) { PanelSurface(pxW, pxH) }
            if (dirty || surf.texture == 0) {
                surf.resize(pxW, pxH)
                surf.begin()
                for (c in 0 until nu) {
                    val co = c * LuaVm.UI_STRIDE
                    if (u.get(co).toInt() != id) continue
                    val kind = u.get(co + 1).toInt()
                    val x = u.get(co + 3); val y = u.get(co + 4); val w = u.get(co + 5); val h = u.get(co + 6)
                    if (kind == 1) surf.rect(x, y, w, h, u.get(co + 7), u.get(co + 8), u.get(co + 9), u.get(co + 10), u.get(co + 11))
                    else ctx.images.bitmap(u.get(co + 2).toInt())?.let { surf.image(it, x, y, w, h, u.get(co + 10), u.get(co + 11)) }
                }
                surf.upload()
            }
            val item = ctx.scene.addPanel()
            item.texture = surf.texture
            item.radius = 0f // corners already drawn by the panel background
            pos.set(p.get(o + 1), p.get(o + 2), p.get(o + 3))
            rot.set(p.get(o + 4), p.get(o + 5), p.get(o + 6), p.get(o + 7))
            rigInv.transformPoint(pos, item.pose.position)
            rigInv.orientation.mul(rot, item.pose.orientation)
            item.width = p.get(o + 8); item.height = p.get(o + 9)
        }
        // destroyed panels (sent with zero size) free their surfaces
        for (k in 0 until np) {
            val o = k * LuaVm.PANEL_STRIDE
            if (p.get(o + 8) == 0f) panels.remove(p.get(o).toInt())?.release()
        }
    }

    // ---------------------------------------------------------------- host calls
    private fun need(p: HyperPermission): Boolean {
        if (p in granted) return true
        vm.retNil(); vm.retStr("permission '${p.key}' not granted")
        return false
    }

    /** Resolves a package-relative path, refusing traversal. */
    private fun packageFile(rel: String?): File? {
        if (rel == null || !HyperxPackage.isSafeEntryName(rel)) return null
        val f = File(pkg.dir, rel).canonicalFile
        return if (f.path.startsWith(pkg.dir.canonicalPath + File.separator) && f.isFile) f else null
    }

    private fun dataFile(rel: String?): File? {
        if (rel == null || (rel.isNotEmpty() && !HyperxPackage.isSafeEntryName(rel))) return null
        val root = pkg.dataDir.canonicalFile
        val f = File(root, rel).canonicalFile
        return if (f == root || f.path.startsWith(root.path + File.separator)) f else null
    }

    override fun onAttach(vm: LuaVm) { this.vm = vm }

    override fun onLog(level: Int, message: String) {
        if (level >= 2) Diagnostics.log("[${pkg.manifest.id}] $message")
    }

    override fun hostCall(name: String, argc: Int): Int {
        vm.beginReturn()
        try {
            when (name) {
                "text" -> {
                    val c = android.graphics.Color.argb((vm.argFloat(5) * 255).toInt(), (vm.argFloat(2) * 255).toInt(), (vm.argFloat(3) * 255).toInt(), (vm.argFloat(4) * 255).toInt())
                    val bmp = TextRenderer.render(vm.argStr(0) ?: "", vm.argFloat(1), c, vm.argInt(6) == 1, vm.argInt(7))
                    vm.retNum(ctx.images.add(bmp).toDouble()); vm.retNum(bmp.width.toDouble()); vm.retNum(bmp.height.toDouble())
                }
                "free_texture" -> { ctx.images.free(vm.argInt(0)); vm.retBool(true) }
                "asset_text" -> {
                    val f = packageFile(vm.argStr(0))
                    if (f == null || f.length() > 4 * 1024 * 1024) { vm.retNil(); vm.retStr("asset not found: ${vm.argStr(0)}") } else vm.retStr(f.readText())
                }
                "load_texture" -> {
                    val f = packageFile(vm.argStr(0))
                    val bmp = f?.let { BitmapFactory.decodeFile(it.path) }
                    if (bmp == null) { vm.retNil(); vm.retStr("cannot load texture ${vm.argStr(0)}") } else vm.retNum(ctx.images.add(bmp).toDouble())
                }
                "load_model" -> {
                    val f = packageFile(vm.argStr(0))
                    if (f == null) { vm.retNil(); vm.retStr("model not found") } else {
                        val model = GltfLoader.load(f.readBytes()) { rel ->
                            packageFile(File(vm.argStr(0)!!).parent?.let { "$it/$rel" } ?: rel)?.readBytes() ?: throw IllegalArgumentException("missing $rel")
                        }
                        val id = nextModel++
                        models[id] = GpuModel(model)
                        vm.retNum(id.toDouble())
                    }
                }
                "model_bounds" -> {
                    val m = models[vm.argInt(0)]
                    if (m == null) vm.retNil() else { m.boundsMin.forEach { vm.retNum(it.toDouble()) }; m.boundsMax.forEach { vm.retNum(it.toDouble()) } }
                }
                "audio_load" -> if (need(HyperPermission.AUDIO)) {
                    val f = packageFile(vm.argStr(0))
                    if (f == null) { vm.retNil(); vm.retStr("sound not found") } else vm.retNum(ctx.audio.load(f).toDouble())
                }
                "audio_synth" -> if (need(HyperPermission.AUDIO)) {
                    vm.retNum(ctx.audio.synth(vm.argStr(0) ?: "sine", vm.argFloat(1), vm.argFloat(2), vm.argFloat(3), vm.argFloat(4), vm.argFloat(5), vm.argFloat(6)).toDouble())
                }
                "audio_play" -> if (need(HyperPermission.AUDIO)) {
                    val world = if (vm.argInt(4) == 1) Vec3(vm.argFloat(5), vm.argFloat(6), vm.argFloat(7)) else null
                    val trackingPos = world?.let { rigInv.transformPoint(it) }
                    vm.retNum(ctx.audio.play(vm.argInt(0), vm.argFloat(1), vm.argFloat(2), vm.argInt(3) == 1, trackingPos, ctx.input.head).toDouble())
                }
                "audio_stop" -> { ctx.audio.stop(vm.argInt(0)); vm.retBool(true) }
                "fs_read" -> if (need(HyperPermission.FILESYSTEM)) {
                    val f = dataFile(vm.argStr(0))
                    if (f == null || !f.isFile) vm.retNil() else vm.retStr(f.readText())
                }
                "fs_write" -> if (need(HyperPermission.FILESYSTEM)) {
                    val f = dataFile(vm.argStr(0))
                    val data = vm.argStr(1) ?: ""
                    val used = pkg.dataDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    when {
                        f == null -> { vm.retNil(); vm.retStr("invalid path") }
                        data.length > MAX_FILE || used + data.length > MAX_STORAGE -> { vm.retNil(); vm.retStr("storage quota exceeded") }
                        else -> { f.parentFile?.mkdirs(); f.writeText(data); vm.retBool(true) }
                    }
                }
                "fs_list" -> if (need(HyperPermission.FILESYSTEM)) {
                    val f = dataFile(vm.argStr(0) ?: "")
                    vm.retStr(f?.listFiles()?.joinToString("\n") { it.name } ?: "")
                }
                "fs_delete" -> if (need(HyperPermission.FILESYSTEM)) {
                    val f = dataFile(vm.argStr(0))
                    vm.retBool(f != null && f != pkg.dataDir.canonicalFile && f.delete())
                }
                "net_request" -> if (need(HyperPermission.NETWORK)) {
                    val id = vm.argInt(0); val method = vm.argStr(1) ?: "GET"; val url = vm.argStr(2) ?: ""; val body = vm.argStr(3) ?: ""
                    val host = runCatching { URL(url) }.getOrNull()
                    val allowed = pkg.manifest.network?.allowedHosts.orEmpty()
                    when {
                        host == null || host.protocol != "https" -> { vm.retNil(); vm.retStr("only https:// URLs are allowed") }
                        host.host !in allowed -> { vm.retNil(); vm.retStr("host ${host.host} is not in manifest.network.allowedHosts") }
                        method !in setOf("GET", "POST", "PUT", "DELETE") -> { vm.retNil(); vm.retStr("method not allowed") }
                        else -> { net.execute { doRequest(id, method, host, body) }; vm.retBool(true) }
                    }
                }
                "camera_texture" -> if (need(HyperPermission.CAMERA)) {
                    vm.retNil(); vm.retStr("camera textures are not exposed to scripts in SDK level 1; use hx.scene.setBackground('passthrough')")
                }
                "game_error" -> { writeCrash(vm.argStr(0) ?: "unknown error"); vm.retBool(true) }
                else -> { vm.retNil(); vm.retStr("unknown host call $name") }
            }
        } catch (t: Throwable) {
            vm.beginReturn(); vm.retNil(); vm.retStr("${t.javaClass.simpleName}: ${t.message}")
        }
        return vm.returned()
    }

    private fun doRequest(id: Int, method: String, url: URL, body: String) {
        try {
            val c = url.openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 10000; c.readTimeout = 15000
            c.instanceFollowRedirects = false // a redirect could leave the allow-list
            c.setRequestProperty("User-Agent", "HyperXR-Game/${pkg.manifest.id}")
            if (body.isNotEmpty() && method != "GET") { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.use { readBounded(it, MAX_RESPONSE).toString(Charsets.UTF_8) } ?: ""
            events += NetResult(id, code, text)
        } catch (t: Throwable) {
            events += NetResult(id, -1, t.message ?: "network error")
        }
    }

    /** InputStream.readNBytes is API 33; this reads at most [limit] bytes on any API level. */
    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        const val FLAG_MODEL = 1; const val FLAG_UNLIT = 2; const val FLAG_DOUBLE = 4; const val FLAG_TRANSPARENT = 8
        const val WATCHDOG_NS = 4_000_000_000L
        const val MAX_FILE = 1 shl 20
        const val MAX_STORAGE = 16L shl 20
        const val MAX_RESPONSE = 2 shl 20
    }
}
