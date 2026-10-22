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

    override fun createContent(): XrContent {
        val ip = intent.getStringExtra(EXTRA_PC_IP) ?: prefs(this).getString("pc_ip", "") ?: ""
        return SteamVrContent(ip).also { content = it }
    }

    override fun onDestroy() {
        content?.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PC_IP = "pc_ip"
        fun prefs(c: android.content.Context) = c.getSharedPreferences("steamvr", android.content.Context.MODE_PRIVATE)
    }
}

class SteamVrContent(private val pcIp: String) : XrContent, EyeOverride {
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
        client = SteamVrClient(pcIp, hello, st).also { c ->
            c.onHaptic = { h -> rumble(h) }
            c.start()
        }
    }

    /** The phone's display as SteamVR should render it: half the landscape screen per eye. */
    private fun hello(ctx: XrContext): HxvrProtocol.Hello {
        val dm = ctx.activity.resources.displayMetrics
        val w = maxOf(dm.widthPixels, dm.heightPixels); val h = minOf(dm.widthPixels, dm.heightPixels)
        val eyeW = (w / 2 + 8) / 16 * 16
        val eyeH = (h + 8) / 16 * 16
        val g = ctx.settings.graphics
        val tv = tan(Math.toRadians(g.fovDegrees / 2.0)).toFloat()
        val th = tv * eyeW / eyeH
        @Suppress("DEPRECATION")
        val hz = ctx.activity.windowManager.defaultDisplay.refreshRate
        val refresh = if (hz >= 89f) 90 else if (hz >= 71f) 72 else 60
        return HxvrProtocol.Hello(eyeW, eyeH, refresh, th, th, tv, tv, g.ipdMm / 1000f, android.os.Build.MODEL)
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
            c.paragraph("IP deste celular (digite no HyperXR PC): " + (localIp() ?: "sem Wi-Fi"), 26f, XrTheme.accent2)
            c.paragraph("PC: " + pcIp.ifBlank { "o PC é que vai se conectar" }, 26f)
            c.paragraph("Joy-Con Home recentraliza. Sem Joy-Con, as mãos viram os controles: pinça = gatilho, fechar a mão = grip.", 22f)
        }
    }

    companion object {
        fun localIp(): String? = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<java.net.Inet4Address>().firstOrNull()?.hostAddress
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
    }
}
