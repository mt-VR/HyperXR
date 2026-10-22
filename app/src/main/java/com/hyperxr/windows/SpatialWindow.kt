package com.hyperxr.windows

import android.graphics.SurfaceTexture
import android.view.Surface
import com.hyperxr.input.InteractionSurface
import com.hyperxr.input.PointerEvent
import com.hyperxr.input.PointerPhase
import com.hyperxr.math.Pose
import com.hyperxr.rendering.FrameScene
import com.hyperxr.rendering.Gl

/**
 * Texture fed by a display (a VirtualDisplay of our own or one created by the shell via Shizuku).
 * The SurfaceTexture/OES texture live on the GL thread; [onSurface] is invoked once (GL thread)
 * with the Surface the display must render into.
 */
class VirtualScreen(val widthPx: Int, val heightPx: Int) {
    var texture = 0; private set
    private var st: SurfaceTexture? = null
    var surface: Surface? = null; private set
    @Volatile private var frameAvailable = false
    @Volatile var hasFrame = false; private set
    var onSurface: ((Surface) -> Unit)? = null

    /** GL thread. */
    fun ensure() {
        if (texture != 0) return
        texture = Gl.externalTexture()
        st = SurfaceTexture(texture).apply {
            setDefaultBufferSize(widthPx, heightPx)
            setOnFrameAvailableListener { frameAvailable = true }
        }
        surface = Surface(st).also { s -> onSurface?.invoke(s) }
    }

    /** GL thread: latch the newest frame. */
    fun latch() {
        if (frameAvailable) { frameAvailable = false; st?.updateTexImage(); hasFrame = true }
    }

    fun release() {
        surface?.release(); st?.release(); Gl.deleteTexture(texture)
        surface = null; st = null; texture = 0
    }
}

/**
 * A window in space showing a display: flat or curved, movable, with pointer input converted to
 * display pixels. Subclasses route input to their content (WebView, another Android app...).
 */
abstract class SpatialWindow(val title: String, val screen: VirtualScreen, override var widthMeters: Float) : InteractionSurface {
    override val surfaceId: String = "window:" + System.identityHashCode(this)
    override val pose = Pose()
    override val heightMeters: Float get() = widthMeters * screen.heightPx / screen.widthPx
    var curved = false
    /** Curved windows wrap around the user at this radius. */
    var radius = 1.6f
    override val curvatureRadius: Float get() = if (curved) radius else 0f
    var visible = true
    @Volatile var status: String? = null

    abstract fun injectTouch(action: Int, x: Float, y: Float, downTimeMs: Long, eventTimeMs: Long)
    abstract fun typeText(text: String)
    abstract fun sendKey(keyCode: Int)
    abstract fun back()
    abstract fun close()
    open val supportsReload: Boolean get() = false
    open fun reload() {}
    open fun openExternally() {}

    private var downTime = 0L

    override fun onPointerEvent(event: PointerEvent) {
        val action = when (event.phase) {
            PointerPhase.DOWN -> android.view.MotionEvent.ACTION_DOWN
            PointerPhase.MOVE -> android.view.MotionEvent.ACTION_MOVE
            PointerPhase.UP -> android.view.MotionEvent.ACTION_UP
            PointerPhase.CANCEL -> android.view.MotionEvent.ACTION_CANCEL
            else -> return
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (action == android.view.MotionEvent.ACTION_DOWN) downTime = now
        injectTouch(action, (event.u * screen.widthPx).coerceIn(0f, screen.widthPx - 1f), (event.v * screen.heightPx).coerceIn(0f, screen.heightPx - 1f), downTime, now)
    }

    /** GL thread. */
    fun submit(scene: FrameScene) {
        if (!visible) return
        screen.ensure()
        screen.latch()
        val p = scene.addPanel()
        p.texture = screen.texture
        p.oes = true
        p.pose.set(pose)
        p.width = widthMeters; p.height = heightMeters
        p.curvature = curvatureRadius
        p.radius = 0f
    }
}
