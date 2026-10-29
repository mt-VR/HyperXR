package com.hyperxr.steamvr

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.hyperxr.joycon.JoyConManager
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import com.hyperxr.rendering.Background
import com.hyperxr.rendering.EyeView
import com.hyperxr.rendering.FullscreenTriangle
import com.hyperxr.rendering.Gl
import com.hyperxr.rendering.RenderTarget
import com.hyperxr.settings.ControllerAction
import com.hyperxr.joycon.JoyConSide
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme
import com.hyperxr.xr.EyeOverride
import com.hyperxr.xr.ImmersiveActivity
import com.hyperxr.xr.XrContent
import com.hyperxr.xr.XrContext
import kotlin.math.tan

/** SteamVR on the phone: the HyperXR engine tracks and displays, the PC renders. */
class SteamVrActivity : ImmersiveActivity() {
    private var content: SteamVrContent? = null
    private val plan by lazy { SteamVrPlanner.plan(this, SteamVrPlanner.profile(this)) }

    override fun createContent(): XrContent {
        com.hyperxr.mixedreality.ArCoreSession.lightweight = true
        val ip = intent.getStringExtra(EXTRA_PC_IP) ?: prefs(this).getString("pc_ip", "") ?: ""
        return SteamVrContent(ip, plan).also { content = it }
    }

    /**
     * Only what streaming needs: no boundary depth, no ARCore depth for the hands, no hand
     * drawing (the PC draws the controllers), hand tracking at the profile's rate. ARCore still
     * gives 6DoF and the camera still feeds the hands that drive the Joy-Cons.
     */
    override fun adjustSettings(s: com.hyperxr.settings.HyperSettings) = s.copy(
        graphics = s.graphics.copy(vrBoundary = false),
        performance = s.performance.copy(handInferenceHz = plan.handHz, sustainedPerformanceMode = true),
        handTracking = s.handTracking.copy(useArCoreDepth = false, handVisual = com.hyperxr.settings.HandVisual.NONE, directTouch = false),
    )

    override fun onDestroy() {
        com.hyperxr.mixedreality.ArCoreSession.lightweight = false
        content?.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PC_IP = "pc_ip"
        fun prefs(c: android.content.Context) = c.getSharedPreferences("steamvr", android.content.Context.MODE_PRIVATE)
    }
}

class SteamVrContent(private val pcIp: String, private val plan: SteamVrProfiles.Plan) : XrContent, EyeOverride, com.hyperxr.xr.ScreenOverride {
    private lateinit var ctx: XrContext
    private var client: SteamVrClient? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var oesTex = 0
    private var prog = 0
    private var vao = 0
    private val u = HashMap<String, Int>()
    private val texMatrix = FloatArray(16)
    @Volatile private var frameAvailable = false
    private var hasFrame = false
    private var frameQuat = Quat()
    private val playSpace = PlaySpace(1.65f)
    private val tracking = HxvrProtocol.Tracking()
    private val panel = StatusPanel()
    private var panelPlaced = false
    private val tmpPos = Vec3(); private val tmpRot = Quat()
    private val curHead = Quat()
    private val rel = Quat()
    private val rot = FloatArray(9)
    private var lastFrameAt = 0L
    private lateinit var display: HxvrProtocol.Hello

    override fun onGlInit(ctx: XrContext) {
        this.ctx = ctx
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0); oesTex = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        prog = Gl.compile(VS, FS, "steamvr-reproject")
        for (n in listOf("uTex", "uTexMatrix", "uRot", "uCur", "uSrc", "uEye")) u[n] = GLES30.glGetUniformLocation(prog, n)
        GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0]
        GLES30.glBindVertexArray(vao); FullscreenTriangle.bind(); GLES30.glBindVertexArray(0)

        val st = SurfaceTexture(oesTex).also { it.setOnFrameAvailableListener { frameAvailable = true } }
        surfaceTexture = st
        val hello = hello(ctx).also { display = it }
        st.setDefaultBufferSize(hello.eyeWidth * 2, hello.eyeHeight)
        client = SteamVrClient(ctx.activity, pcIp, hello, st).also { c ->
            c.onHaptic = { h -> rumble(h) }
            c.start()
        }
    }

    /** The stream as the profile planned it, with this viewer's field of view. */
    private fun hello(ctx: XrContext): HxvrProtocol.Hello {
        val g = ctx.settings.graphics
        val tv = tan(Math.toRadians(g.fovDegrees / 2.0)).toFloat()
        // same shape as the phone's eye viewport, so the stream covers exactly what is shown
        val dm = ctx.activity.resources.displayMetrics
        val aspect = (maxOf(dm.widthPixels, dm.heightPixels) / 2f) / minOf(dm.widthPixels, dm.heightPixels)
        val th = tv * aspect
        return HxvrProtocol.Hello(plan.eyeWidth, plan.eyeHeight, plan.refreshHz, th, th, tv, tv, g.ipdMm / 1000f,
            android.os.Build.MODEL, codecs = plan.codecs, maxMbps = plan.maxMbps)
    }

    override fun onFrame(ctx: XrContext, dt: Float) {
        val c = client ?: return
        val input = ctx.input
        // recenter at start and again once ARCore has settled (about 1.5 s in)
        if (!playSpace.isSet || ctx.frameIndex == 90L) playSpace.recenter(input.head.position, input.head.orientation)
        // tracking to the PC, in SteamVR's standing space
        tracking.timeNs = System.nanoTime()
        playSpace.apply(input.head.position, input.head.orientation, tmpPos, tmpRot)
        SteamVrInput.putPose(tmpPos, tmpRot, tracking.head)
        for (i in 0..1) controller(ctx, i, tracking.controllers[i])
        c.sendTracking(tracking)

        if (frameAvailable) {
            frameAvailable = false
            surfaceTexture?.updateTexImage()
            surfaceTexture?.getTransformMatrix(texMatrix)
            val id = (surfaceTexture?.timestamp ?: 0L) / 1000
            c.headers[id]?.let { h -> frameQuat.set(h.qx, h.qy, h.qz, h.qw) }
            hasFrame = true
            lastFrameAt = System.nanoTime()
        }
        // no video for a while (PC restarting, Wi-Fi drop): show the status panel again
        if (hasFrame && System.nanoTime() - lastFrameAt > 2_000_000_000L) hasFrame = false
        ctx.scene.background = Background.COLOR
        if (!hasFrame) {
            if (!panelPlaced) { panel.placeInFront(input.head, 1.2f, 0f); panelPlaced = true }
            panel.state = c.state
            panel.update(dt)
            panel.submit(ctx.scene)
        } else panelPlaced = false
    }

    private fun controller(ctx: XrContext, i: Int, out: HxvrProtocol.Controller) {
        val input = ctx.input
        val cs = input.controllers[i]
        val hand = input.hands[i]
        when {
            cs.connected -> {
                val bits = cs.buttons
                SteamVrInput.fromJoyCon({ b -> bits and (JoyConManager.BUTTON_BITS[b] ?: 0) != 0 }, cs.stickX, cs.stickY, out)
                // the stick of a single Joy-Con also arrives on the second stick of combined pairs
                if (out.joyX == 0f && out.joyY == 0f) { out.joyX = cs.stick2X; out.joyY = cs.stick2Y }
                playSpace.apply(cs.pose.position, cs.pose.orientation, tmpPos, tmpRot)
                SteamVrInput.putPose(tmpPos, tmpRot, out.pose)
                if (!hand.isUsable && !cs.positionFromHand) out.flags = HxvrProtocol.CTRL_CONNECTED
            }
            hand.isUsable -> {
                SteamVrInput.fromHand(hand.joints, right = i == 1, pinch = hand.pinchStrength, c = out)
                tmpPos.set(out.pose[0], out.pose[1], out.pose[2]); tmpRot.set(out.pose[3], out.pose[4], out.pose[5], out.pose[6])
                val p = Vec3(); val r = Quat()
                playSpace.apply(tmpPos, tmpRot, p, r)
                SteamVrInput.putPose(p, r, out.pose)
            }
            else -> { out.flags = 0; out.buttons = 0; out.trigger = 0f; out.grip = 0f }
        }
    }

    override fun onAction(ctx: XrContext, action: ControllerAction, pressed: Boolean, side: JoyConSide?) {
        if (action == ControllerAction.RECENTER && pressed) playSpace.recenter(ctx.input.head.position, ctx.input.head.orientation)
    }

    /**
     * Draws the latest PC frame for this eye with rotational reprojection: each pixel's direction
     * under the current head rotation is looked up in the frame rendered for the older rotation,
     * so turning the head stays smooth even when the network adds latency.
     */
    override fun renderEye(eye: EyeView, target: RenderTarget): Boolean {
        val c = client ?: return false
        if (!hasFrame) return false
        val cfg = c.config ?: return false
        playSpace.apply(ctx.input.head.position, ctx.input.head.orientation, tmpPos, curHead)
        frameQuat.conjugate(rel).mul(curHead, rel)  // current eye space -> frame space
        rotationMatrix(rel, rot)
        GLES30.glViewport(0, 0, target.width, target.height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(prog)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glUniform1i(u.getValue("uTex"), 0)
        GLES30.glUniformMatrix4fv(u.getValue("uTexMatrix"), 1, false, texMatrix, 0)
        GLES30.glUniformMatrix3fv(u.getValue("uRot"), 1, false, rot, 0)
        GLES30.glUniform2f(u.getValue("uCur"), 1f / eye.proj[0], 1f / eye.proj[5])
        GLES30.glUniform2f(u.getValue("uSrc"), display.tanLeft, display.tanUp)
        GLES30.glUniform1f(u.getValue("uEye"), if (eye.index == 1) 1f else 0f)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        return cfg.eyeWidth > 0
    }

    /**
     * Weak-phone path: the whole screen in one pass per eye, reprojection and lens correction
     * together, straight from the decoded video. No eye render targets and no composite pass:
     * each screen pixel is read once (three times with chromatic correction).
     */
    override fun drawScreen(eyes: Array<EyeView>, screenW: Int, screenH: Int, xdpi: Float, g: com.hyperxr.settings.GraphicsSettings): Boolean {
        val c = client ?: return false
        if (!hasFrame || c.config == null) return false
        if (directProg == 0) {
            directProg = Gl.compile(VS, DIRECT_FS, "steamvr-direct")
            for (n in listOf("uTex", "uTexMatrix", "uRot", "uSrc", "uEye", "uLensCenter", "uScale", "uK", "uChroma")) du[n] = GLES30.glGetUniformLocation(directProg, n)
        }
        playSpace.apply(ctx.input.head.position, ctx.input.head.orientation, tmpPos, curHead)
        frameQuat.conjugate(rel).mul(curHead, rel)
        rotationMatrix(rel, rot)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, screenW, screenH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(directProg)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glUniform1i(du.getValue("uTex"), 0)
        GLES30.glUniformMatrix4fv(du.getValue("uTexMatrix"), 1, false, texMatrix, 0)
        GLES30.glUniformMatrix3fv(du.getValue("uRot"), 1, false, rot, 0)
        GLES30.glUniform2f(du.getValue("uSrc"), display.tanLeft, display.tanUp)
        if (g.lensDistortion) GLES30.glUniform2f(du.getValue("uK"), g.distortionK1, g.distortionK2) else GLES30.glUniform2f(du.getValue("uK"), 0f, 0f)
        GLES30.glUniform1f(du.getValue("uChroma"), if (plan.chroma && g.lensDistortion) 0.006f else 0f)
        GLES30.glBindVertexArray(vao)
        val halfW = screenW / 2
        val lensOffsetPx = (g.lensSeparationMm / 2f) * (xdpi / 25.4f)
        for (e in 0..1) {
            GLES30.glViewport(e * halfW, 0, halfW, screenH)
            val center = if (!g.lensDistortion) 0.5f else ((if (e == 0) halfW - lensOffsetPx else lensOffsetPx) / halfW).coerceIn(0.2f, 0.8f)
            GLES30.glUniform2f(du.getValue("uLensCenter"), center, 0.5f)
            GLES30.glUniform2f(du.getValue("uScale"), 2f / eyes[e].proj[0], 2f / eyes[e].proj[5])
            GLES30.glUniform1f(du.getValue("uEye"), e.toFloat())
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        return true
    }

    private var directProg = 0
    private val du = HashMap<String, Int>()

    /** Column-major 3x3 rotation matrix of [q]. */
    private fun rotationMatrix(q: Quat, m: FloatArray) {
        val x = q.x; val y = q.y; val z = q.z; val w = q.w
        m[0] = 1 - 2 * (y * y + z * z); m[1] = 2 * (x * y + z * w); m[2] = 2 * (x * z - y * w)
        m[3] = 2 * (x * y - z * w); m[4] = 1 - 2 * (x * x + z * z); m[5] = 2 * (y * z + x * w)
        m[6] = 2 * (x * z + y * w); m[7] = 2 * (y * z - x * w); m[8] = 1 - 2 * (x * x + y * y)
    }

    /** Game rumble on the Joy-Con of that hand (Android 12+ exposes controller vibrators). */
    private fun rumble(h: HxvrProtocol.Haptic) {
        if (android.os.Build.VERSION.SDK_INT < 31 || h.amplitude <= 0f) return
        val side = if (h.hand == 0) JoyConSide.LEFT else JoyConSide.RIGHT
        for (id in android.view.InputDevice.getDeviceIds()) {
            val dev = android.view.InputDevice.getDevice(id) ?: continue
            val s = JoyConManager.classify(dev) ?: continue
            if (s != side && s != JoyConSide.PRO && s != JoyConSide.COMBINED) continue
            val ms = (h.duration * 1000).toLong().coerceIn(10, 1000)
            val amp = (h.amplitude * 255).toInt().coerceIn(1, 255)
            runCatching { dev.vibratorManager.defaultVibrator.vibrate(android.os.VibrationEffect.createOneShot(ms, amp)) }
        }
    }

    override fun onGlRelease() { close() }

    fun close() {
        client?.stop(); client = null
        surfaceTexture?.release(); surfaceTexture = null
    }

    /** Shown until the PC's video arrives: the addresses to type and what is happening. */
    private inner class StatusPanel : XrPanel(1.1f, 0.7f, title = "SteamVR") {
        var state = ""
            set(v) { if (v != field) { field = v; dirty = true } }
        private var t = 0f
        override fun update(dt: Float) { t += dt; if (t > 1f) { t = 0f; dirty = true } }
        override fun draw(c: PanelCanvas) {
            c.paragraph(state, 32f, XrTheme.text, bold = true)
            c.paragraph("IP deste celular (digite no HyperMagic PC): " + (localIp() ?: "sem Wi-Fi"), 26f, XrTheme.accent2)
            val found = client?.foundPc
            c.paragraph(when {
                pcIp.isNotBlank() && found != null && found != pcIp.trim() -> "PC digitado: $pcIp   achado na rede: $found"
                pcIp.isNotBlank() -> "PC: $pcIp"
                found != null -> "PC achado na rede: $found"
                else -> "PC: achado sozinho quando o HyperMagic PC estiver aberto"
            }, 26f)
            c.paragraph("Perfil ${plan.profile.label}: ${plan.eyeWidth * 2}x${plan.eyeHeight} a ${plan.refreshHz} Hz, " +
                (if (plan.codecs and SteamVrProfiles.CODEC_HEVC != 0) "HEVC" else "H.264") + ", até ${plan.maxMbps} Mb/s", 22f)
            c.paragraph("Joy-Con Home recentraliza. Sem Joy-Con, as mãos viram os controles: pinça = gatilho, fechar a mão = grip.", 22f)
        }
    }

    companion object {
        /** The Wi-Fi (or hotspot) address; the mobile data one (rmnet, ccmni) is useless to the PC. */
        fun localIp(): String? = runCatching {
            val all = java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { i -> i.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>().map { i.name to it } }
            val lan = listOf("wlan", "swlan", "ap", "eth", "rndis", "usb")
            (all.firstOrNull { (n, _) -> lan.any { n.startsWith(it) } } ?: all.firstOrNull { it.second.isSiteLocalAddress } ?: all.firstOrNull())
                ?.second?.hostAddress
        }.getOrNull()

        private const val VS = """#version 300 es
layout(location=0) in vec3 aPos;
out vec2 vUv;
void main() { vUv = aPos.xy * 0.5 + 0.5; gl_Position = vec4(aPos.xy, 0.0, 1.0); }"""

        private const val FS = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 vUv;
uniform samplerExternalOES uTex;
uniform mat4 uTexMatrix;
uniform mat3 uRot;
uniform vec2 uCur;   // tangents of this eye's frustum (half width, half height)
uniform vec2 uSrc;   // tangents the PC rendered with
uniform float uEye;
out vec4 fragColor;
void main() {
    vec3 d = uRot * normalize(vec3((vUv.x * 2.0 - 1.0) * uCur.x, (vUv.y * 2.0 - 1.0) * uCur.y, -1.0));
    if (d.z > -0.01) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
    vec2 s = vec2(d.x, d.y) / -d.z;
    vec2 uv = (s / uSrc) * 0.5 + 0.5;
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
    vec2 frame = vec2(uEye * 0.5 + uv.x * 0.5, uv.y);
    fragColor = vec4(texture(uTex, (uTexMatrix * vec4(frame, 0.0, 1.0)).xy).rgb, 1.0);
}"""

        /** Reprojection + lens pre-distortion (+ optional chromatic correction) in one pass. */
        private const val DIRECT_FS = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 vUv;
uniform samplerExternalOES uTex;
uniform mat4 uTexMatrix;
uniform mat3 uRot;
uniform vec2 uSrc;
uniform float uEye;
uniform vec2 uLensCenter;
uniform vec2 uScale;
uniform vec2 uK;
uniform float uChroma;
out vec4 fragColor;
vec2 warp(vec2 t, float s) { float r2 = dot(t, t); return t * (1.0 + uK.x * r2 + uK.y * r2 * r2) * s; }
// screen tangent -> uv in the frame the PC rendered (z < 0 when outside it)
vec3 look(vec2 tn) {
    vec3 d = uRot * normalize(vec3(tn, -1.0));
    if (d.z > -0.01) return vec3(0.0, 0.0, -1.0);
    vec2 uv = (d.xy / -d.z / uSrc) * 0.5 + 0.5;
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) return vec3(0.0, 0.0, -1.0);
    return vec3(uv, 1.0);
}
vec3 fetch(vec2 uv) { return texture(uTex, (uTexMatrix * vec4(uEye * 0.5 + uv.x * 0.5, uv.y, 0.0, 1.0)).xy).rgb; }
void main() {
    vec2 t = (vUv - uLensCenter) * uScale;
    vec3 g = look(warp(t, 1.0));
    if (g.z < 0.0) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
    if (uChroma <= 0.0) { fragColor = vec4(fetch(g.xy), 1.0); return; }
    vec3 r = look(warp(t, 1.0 - uChroma));
    vec3 b = look(warp(t, 1.0 + uChroma));
    fragColor = vec4(fetch(r.z < 0.0 ? g.xy : r.xy).r, fetch(g.xy).g, fetch(b.z < 0.0 ? g.xy : b.xy).b, 1.0);
}"""
    }
}
