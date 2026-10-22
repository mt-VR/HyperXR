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

/** Anything the XR keyboard can type into (a window's focused field, the browser address bar...). */
interface KeyTarget {
    fun typeText(text: String)
    fun sendKey(keyCode: Int)
}

/**
 * A window in space showing a display: flat or curved, movable, with pointer input converted to
 * display pixels. Subclasses route input to their content (WebView, another Android app...).
 */
abstract class SpatialWindow(val title: String, val screen: VirtualScreen, override var widthMeters: Float) : InteractionSurface, KeyTarget {
    override val surfaceId: String = "window:" + System.identityHashCode(this)
    override val pose = Pose()
    override val heightMeters: Float get() = widthMeters * screen.heightPx / screen.widthPx
    var curved = false
    /** Curved windows wrap around the user at this radius. */
    var radius = 1.6f
    override val curvatureRadius: Float get() = if (curved) radius else 0f
    var visible = true
    @Volatile var status: String? = null

    /** Set by the launcher: a link was picked up by long-pinching it (url, pointer id). */
    var onLinkDrag: ((String, Int) -> Unit)? = null

    abstract fun injectTouch(action: Int, x: Float, y: Float, downTimeMs: Long, eventTimeMs: Long)
    /** Opens a dropped link in this window. */
    abstract fun openUrl(url: String)
    /** Long press without moving; subclasses may start a drag from here. */
    protected open fun onLongPress(x: Float, y: Float, pointerId: Int) {}

    /** After a drag starts, the rest of that press is not sent to the content (no accidental click). */
    @Volatile protected var suppressUntilUp = false
    private var downX = 0f
    private var downY = 0f
    private var longFired = false
    abstract override fun typeText(text: String)
    abstract override fun sendKey(keyCode: Int)
    /** Pointer that pressed most recently (used to hand a drag-and-drop to the same pointer). */
    protected var lastPointerId = 0
    /** Shown by the launcher: the window asks for the XR keyboard (a text field got focus). */
    var onKeyboardRequest: (() -> Unit)? = null
    abstract fun back()
    abstract fun close()
    open val supportsReload: Boolean get() = false
    open fun reload() {}
    open fun openExternally() {}

    /**
     * Scrolls the content by [deltaPx] display pixels (positive = further down the page). The default
     * is a slow drag at the window centre ending with a short hold, so the content does not fling.
     */
    open fun scroll(deltaPx: Float) {
        if (deltaPx == 0f) return
        val cx = screen.widthPx / 2f
        val cy = screen.heightPx / 2f
        val half = (deltaPx / 2).coerceIn(-screen.heightPx * 0.45f, screen.heightPx * 0.45f)
        val t0 = android.os.SystemClock.uptimeMillis()
        val steps = 6
        injectTouch(android.view.MotionEvent.ACTION_DOWN, cx, cy + half, t0, t0)
        for (k in 1..steps) injectTouch(android.view.MotionEvent.ACTION_MOVE, cx, cy + half - 2 * half * k / steps, t0, t0 + 16L * k)
        injectTouch(android.view.MotionEvent.ACTION_MOVE, cx, cy - half, t0, t0 + 16L * steps + 120)
        injectTouch(android.view.MotionEvent.ACTION_UP, cx, cy - half, t0, t0 + 16L * steps + 130)
    }

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
        val x = (event.u * screen.widthPx).coerceIn(0f, screen.widthPx - 1f)
        val y = (event.v * screen.heightPx).coerceIn(0f, screen.heightPx - 1f)
        if (action == android.view.MotionEvent.ACTION_DOWN) { lastPointerId = event.pointerId; downTime = now; downX = x; downY = y; longFired = false; suppressUntilUp = false }
        if (action == android.view.MotionEvent.ACTION_MOVE && !longFired && now - downTime > LONG_PRESS_MS &&
            kotlin.math.abs(x - downX) < 14f && kotlin.math.abs(y - downY) < 14f) {
            longFired = true
            onLongPress(x, y, event.pointerId)
        }
        if (suppressUntilUp) {
            if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) suppressUntilUp = false
            return
        }
        injectTouch(action, x, y, downTime, now)
    }

    companion object {
        const val LONG_PRESS_MS = 550L
        private val props = arrayOf(android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER })
        private val coords = arrayOf(android.view.MotionEvent.PointerCoords())

        /** A single-finger touchscreen event (tool type FINGER, as engines expect from a real touch). */
        fun fingerEvent(downTimeMs: Long, eventTimeMs: Long, action: Int, x: Float, y: Float): android.view.MotionEvent {
            val c = coords[0]
            c.clear(); c.x = x; c.y = y; c.pressure = 1f; c.size = 1f
            return android.view.MotionEvent.obtain(downTimeMs, eventTimeMs, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        }
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
