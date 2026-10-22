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
