package com.hyperxr.windows

import android.app.Activity
import android.app.Presentation
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.hyperxr.webapps.SafeWebClient
import com.hyperxr.webapps.WebAppConfig
import org.json.JSONObject

/**
 * Browser window: the WebView lives in a Presentation on a private VirtualDisplay whose surface is
 * our texture. The WebView therefore renders through its own window and hardware renderer (video,
 * WebGL and scrolling work), which drawing it into a foreign Canvas cannot do.
 */
class WebWindow(
    private val activity: Activity,
    val url: String,
    title: String,
    private val desktop: Boolean,
    widthPx: Int = 1280,
    heightPx: Int = 800,
) : SpatialWindow(title, VirtualScreen(widthPx, heightPx), 1.6f) {
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var web: WebView? = null

    init {
        screen.onSurface = { s -> activity.runOnUiThread { start(s) } }
    }

    private fun start(surface: Surface) {
        val dm = activity.getSystemService(DisplayManager::class.java)
        val dpi = 240
        val vd = dm.createVirtualDisplay("hyperxr-web-${System.identityHashCode(this)}", screen.widthPx, screen.heightPx, dpi, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION)
        display = vd
        val wv = WebView(activity)
        WebAppConfig.configure(wv, desktop)
        wv.webViewClient = SafeWebClient { err -> status = "Falha: $err" }
        wv.webChromeClient = WebChromeClient()
        web = wv
        presentation = object : Presentation(activity, vd.display) {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                setContentView(wv)
            }
        }.also {
            try { it.show() } catch (t: Throwable) { status = "Não foi possível abrir a janela: ${t.message}" }
        }
        wv.isFocusableInTouchMode = true
        wv.requestFocus()
        wv.loadUrl(if (url == com.hyperxr.browser.UrlInput.HOME) com.hyperxr.browser.UrlInput.HOME_WEBVIEW else url)
    }

    override fun injectTouch(action: Int, x: Float, y: Float, downTimeMs: Long, eventTimeMs: Long) {
        activity.runOnUiThread {
            val ev = MotionEvent.obtain(downTimeMs, eventTimeMs, action, x, y, 0)
            ev.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            presentation?.window?.decorView?.dispatchTouchEvent(ev)
            ev.recycle()
        }
    }

    /** Inserts text into the focused field (works for inputs, textareas and contenteditable). */
    override fun typeText(text: String) {
        activity.runOnUiThread {
            web?.evaluateJavascript(
                "(function(t){var e=document.activeElement;if(!e)return;" +
                    "if(!document.execCommand('insertText',false,t)){if('value' in e){e.value+=t;e.dispatchEvent(new Event('input',{bubbles:true}));}}})(" +
                    JSONObject.quote(text) + ")", null,
            )
        }
    }

    override fun sendKey(keyCode: Int) {
        activity.runOnUiThread {
            val w = web ?: return@runOnUiThread
            val now = android.os.SystemClock.uptimeMillis()
            w.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0))
            w.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0))
        }
    }

    /** Scrolls the scrollable element under the window centre (or the page) without touching it. */
    override fun scroll(deltaPx: Float) {
        activity.runOnUiThread {
            web?.evaluateJavascript(
                "(function(dy){var e=document.elementFromPoint(innerWidth/2,innerHeight/2);" +
                    "while(e&&e!==document.body){var o=getComputedStyle(e).overflowY;" +
                    "if((o==='auto'||o==='scroll')&&e.scrollHeight>e.clientHeight){e.scrollBy(0,dy);return;}e=e.parentElement;}" +
                    "window.scrollBy(0,dy);})(" + deltaPx / (web?.resources?.displayMetrics?.density ?: 1f) + ")", null,
            )
        }
    }

    override fun openUrl(url: String) { activity.runOnUiThread { web?.loadUrl(if (url == com.hyperxr.browser.UrlInput.HOME) com.hyperxr.browser.UrlInput.HOME_WEBVIEW else url) } }

    /** Long pinch on a link picks it up for drag-and-drop to another window. */
    override fun onLongPress(x: Float, y: Float, pointerId: Int) {
        activity.runOnUiThread {
            val w = web ?: return@runOnUiThread
            w.evaluateJavascript(
                "(function(x,y){var r=window.devicePixelRatio||1;var e=document.elementFromPoint(x/r,y/r);" +
                    "var a=e&&e.closest?e.closest('a[href]'):null;return a?a.href:'';})($x,$y)",
            ) { result ->
                val href = runCatching { org.json.JSONTokener(result).nextValue() as? String }.getOrNull().orEmpty()
                if (href.startsWith("http")) {
                    // cancel the press inside the page so releasing does not also follow the link
                    suppressUntilUp = true
                    val now = android.os.SystemClock.uptimeMillis()
                    val ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, x, y, 0)
                    presentation?.window?.decorView?.dispatchTouchEvent(ev)
                    ev.recycle()
                    onLinkDrag?.invoke(href, pointerId)
                }
            }
        }
    }

    override fun back() { activity.runOnUiThread { web?.let { if (it.canGoBack()) it.goBack() } } }
    override val supportsReload get() = true
    override fun reload() { activity.runOnUiThread { web?.reload() } }
    override fun openExternally() { WebAppConfig.openExternal(activity, web?.url ?: url) }

    override fun close() {
        visible = false
        activity.runOnUiThread {
            web?.stopLoading()
            presentation?.dismiss()
            web?.destroy()
            display?.release()
            web = null; presentation = null; display = null
        }
    }
}
