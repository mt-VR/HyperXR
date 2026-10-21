package com.hyperxr.webapps

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.browser.customtabs.CustomTabsIntent
import com.hyperxr.HyperXRApp
import com.hyperxr.input.InteractionSurface
import com.hyperxr.input.PointerEvent
import com.hyperxr.input.PointerPhase
import com.hyperxr.math.Pose
import com.hyperxr.rendering.FrameScene
import com.hyperxr.rendering.Gl

object WebAppConfig {
    /** Desktop mode is the standard "request desktop site" feature: same engine, desktop user agent. */
    fun desktopUserAgent(default: String): String = default.replace(Regex("; wv\\)"), ")").replace(" Mobile", "").replace(Regex("Version/[0-9.]+ "), "")

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(web: WebView, desktop: Boolean) {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            if (desktop) userAgentString = desktopUserAgent(WebSettings.getDefaultUserAgent(web.context))
        }
        // Sessions persist like a browser profile; third-party cookies are required by several login flows.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
    }

    fun openExternal(context: Context, url: String) {
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
        } catch (t: Throwable) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Restricts navigation to http(s); other schemes (intent:, market:) are handed to the system. */
open class SafeWebClient(private val onError: (String) -> Unit = {}) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val scheme = request.url.scheme ?: return true
        if (scheme == "http" || scheme == "https") return false
        try { view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
        return true
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) onError("${error.description} (${error.errorCode})")
    }
}

/**
 * A WebView rendered into an OES texture for the XR session. The view is attached to the
 * activity (so it lays out, runs JS and plays media) but draws into a Surface instead of the screen.
 */
class SpatialWebPanel(
    private val activity: Activity,
    private val root: FrameLayout,
    val url: String,
    val title: String,
    val pxW: Int = 1280,
    val pxH: Int = 800,
    override var widthMeters: Float = 1.6f,
    desktop: Boolean = false,
) : InteractionSurface {
    override val surfaceId = "web:$url"
    override val pose = Pose()
    override val heightMeters: Float get() = widthMeters * pxH / pxW
    var visible = true
    @Volatile var error: String? = null
    @Volatile var currentTitle: String = title

    private var texture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    @Volatile private var frameAvailable = false
    private lateinit var container: CaptureLayout
    lateinit var webView: WebView; private set
    private var downTime = 0L

    private inner class CaptureLayout(c: Context) : FrameLayout(c) {
        override fun draw(canvas: Canvas) {
            val s = surface ?: return
            val hw = try { s.lockHardwareCanvas() } catch (t: Throwable) { null } ?: return
            hw.drawColor(Color.WHITE)
            super.draw(hw)
            s.unlockCanvasAndPost(hw)
        }
    }

    /** UI thread. */
    fun attach(desktop: Boolean) {
        container = CaptureLayout(activity)
        webView = WebView(activity)
        WebAppConfig.configure(webView, desktop)
        webView.webViewClient = SafeWebClient { error = it }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, t: String?) { currentTitle = t ?: title }
        }
        container.addView(webView, FrameLayout.LayoutParams(pxW, pxH))
        // Positioned behind the GL view; it never paints to the screen itself.
        root.addView(container, 0, FrameLayout.LayoutParams(pxW, pxH))
        webView.loadUrl(url)
        tick()
    }

    private fun tick() {
        if (!visible || !::container.isInitialized || container.parent == null) return
        container.invalidate()
        container.postOnAnimationDelayed({ tick() }, 33)
    }

    /** GL thread: create texture lazily, latch new frames, submit the quad. */
    fun submit(scene: FrameScene) {
        if (!visible) return
        if (texture == 0) {
            texture = Gl.externalTexture()
            surfaceTexture = SurfaceTexture(texture).apply {
                setDefaultBufferSize(pxW, pxH)
                setOnFrameAvailableListener { frameAvailable = true }
            }
            surface = Surface(surfaceTexture)
        }
        if (frameAvailable) { frameAvailable = false; surfaceTexture?.updateTexImage() }
        val p = scene.addPanel()
        p.texture = texture
        p.oes = true
        p.pose.set(pose)
        p.width = widthMeters; p.height = heightMeters
    }

    override fun onPointerEvent(event: PointerEvent) {
        val x = event.u * pxW; val y = event.v * pxH
        val action = when (event.phase) {
            PointerPhase.DOWN -> MotionEvent.ACTION_DOWN
            PointerPhase.MOVE -> MotionEvent.ACTION_MOVE
            PointerPhase.UP -> MotionEvent.ACTION_UP
            PointerPhase.CANCEL -> MotionEvent.ACTION_CANCEL
            else -> return
        }
        activity.runOnUiThread {
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now
            val ev = MotionEvent.obtain(downTime, now, action, x, y, 0)
            webView.dispatchTouchEvent(ev)
            ev.recycle()
        }
    }

    fun back() = activity.runOnUiThread { if (webView.canGoBack()) webView.goBack() }
    fun reload() = activity.runOnUiThread { webView.reload() }

    fun close() {
        visible = false
        activity.runOnUiThread {
            if (::webView.isInitialized) { webView.stopLoading(); webView.destroy() }
            (container.parent as? ViewGroup)?.removeView(container)
        }
    }

    /** GL thread. */
    fun releaseGl() {
        surface?.release(); surfaceTexture?.release(); Gl.deleteTexture(texture)
        surface = null; surfaceTexture = null; texture = 0
    }

    init { activity.runOnUiThread { attach(desktop) } }
}

/** 2D web app window used from the management UI and as a fallback outside XR. */
class WebAppActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: url
        val desktop = intent.getBooleanExtra(EXTRA_DESKTOP, HyperXRApp.instance.settings.current.network.webAppsDesktopMode)
        web = WebView(this)
        WebAppConfig.configure(web, desktop)
        val status = TextView(this).apply { setTextColor(Color.rgb(255, 160, 170)); setPadding(24, 0, 24, 0) }
        web.webViewClient = SafeWebClient { status.text = "Falha ao carregar: $it — tente o navegador externo" }
        web.webChromeClient = WebChromeClient()
        val bar = LinearLayout(this).apply {
            setBackgroundColor(Color.rgb(22, 22, 36))
            setPadding(12, 8, 12, 8)
            fun btn(t: String, a: () -> Unit) = addView(Button(context).apply { text = t; setOnClickListener { a() } })
            addView(TextView(context).apply { text = title; setTextColor(Color.WHITE); textSize = 18f; setPadding(16, 0, 32, 0) })
            btn("Voltar") { if (web.canGoBack()) web.goBack() else finish() }
            btn("Recarregar") { web.reload() }
            btn("Navegador externo") { WebAppConfig.openExternal(this@WebAppActivity, web.url ?: url) }
            addView(status)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(layout)
        web.loadUrl(url)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed() }

    override fun onDestroy() { if (::web.isInitialized) web.destroy(); CookieManager.getInstance().flush(); super.onDestroy() }

    companion object {
        const val EXTRA_URL = "url"; const val EXTRA_TITLE = "title"; const val EXTRA_DESKTOP = "desktop"
        fun open(context: Context, url: String, title: String, desktop: Boolean? = null) {
            context.startActivity(Intent(context, WebAppActivity::class.java).putExtra(EXTRA_URL, url).putExtra(EXTRA_TITLE, title).apply {
                if (desktop != null) putExtra(EXTRA_DESKTOP, desktop)
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
