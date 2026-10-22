package com.hyperxr.xr

import android.annotation.SuppressLint
import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Fullscreen landscape activity hosting the XR engine. Subclasses provide the content
 * (spatial launcher or a game). The 2D management UI never extends this class.
 */
abstract class ImmersiveActivity : Activity() {
    protected lateinit var engine: XrEngine
    protected lateinit var glView: GLSurfaceView
    protected lateinit var root: FrameLayout

    protected abstract fun createContent(): XrContent?

    /** Settings this session runs with (not saved); SteamVR lightens them for weak phones. */
    protected open fun adjustSettings(s: com.hyperxr.settings.HyperSettings): com.hyperxr.settings.HyperSettings = s

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestHighestRefreshRate()
        root = FrameLayout(this)
        val content = createContent() ?: run { finish(); return }
        engine = XrEngine(this, content) { adjustSettings(it) }
        engine.onCreate()
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 24, 0)
            preserveEGLContextOnPause = true
            setRenderer(engine)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setOnTouchListener { _, e -> engine.onTouch(e) }
        }
        root.addView(glView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        hideSystemUi()
    }

    /** Highest refresh rate at the current resolution: lower frame time and photon latency. */
    @Suppress("DEPRECATION")
    private fun requestHighestRefreshRate() {
        val display = windowManager.defaultDisplay
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }

    private fun hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onResume() {
        super.onResume()
        if (!::engine.isInitialized) return
        glView.onResume()
        engine.onResume()
    }

    override fun onPause() {
        if (::engine.isInitialized) {
            engine.onPause()
            glView.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::engine.isInitialized) {
            glView.queueEvent { engine.releaseGl() }
            engine.onDestroy()
        }
        super.onDestroy()
    }

    @Deprecated("Activity result API of the platform Activity")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (!ActivityResults.dispatch(requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::engine.isInitialized && event.keyCode != KeyEvent.KEYCODE_BACK && engine.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (::engine.isInitialized && engine.onGeneric(ev)) return true
        return super.dispatchGenericMotionEvent(ev)
    }
}
