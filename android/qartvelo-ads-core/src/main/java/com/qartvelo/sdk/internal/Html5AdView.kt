package com.qartvelo.sdk.internal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

/**
 * A shown HTML5 creative. [load] reports exactly one of ready or failed; taps that leave the ad
 * report [load]'s onClick (the caller counts at most one click per impression).
 */
internal interface Html5Surface {
    val view: View
    fun load(onReady: () -> Unit, onFailed: (String) -> Unit, onClick: () -> Unit)
    fun pause()
    fun resume()
    fun destroy()
}

/** Request and navigation decisions of the locked web view. Pure, so they are unit tested. */
internal object Html5Policy {
    const val CLICK_URL = "qartvelo://click"
    const val READY_TIMEOUT_MS = 6_000L
    const val READY_POLL_MS = 100L

    /** Same policy as the bundle route (CONTRACT.md "HTML5 creatives"). */
    const val CSP = "sandbox allow-scripts allow-popups allow-popups-to-escape-sandbox; default-src 'self' data:; " +
        "script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'none'"

    sealed class Decision {
        /** Answer from the downloaded copy. */
        class Serve(val file: File, val mimeType: String) : Decision()

        /** A file of this bundle that was not pre-downloaded (another layout): load it from the bundle URL. */
        object Network : Decision()

        /** Anything else: other hosts, other paths, other methods. */
        object Block : Decision()
    }

    fun intercept(dir: File, baseUrl: String, url: String, method: String): Decision {
        if (!method.equals("GET", ignoreCase = true)) return Decision.Block
        val path = Html5Files.relativePath(baseUrl, url) ?: return Decision.Block
        val mimeType = Html5Files.mimeType(path) ?: return Decision.Block
        val file = Html5Files.resolve(dir, baseUrl, url)
        return if (file != null) Decision.Serve(file, mimeType) else Decision.Network
    }

    /** Every navigation is stopped; `qartvelo://click` and any navigation after the first load are clicks. */
    fun isClick(url: String, firstLoadDone: Boolean): Boolean = url.startsWith(CLICK_URL) || firstLoadDone

    val headers: Map<String, String> = mapOf(
        "Content-Security-Policy" to CSP,
        "X-Content-Type-Options" to "nosniff",
        "Cache-Control" to "no-store",
    )
}

/**
 * HTML5 creatives in a locked [WebView]: JavaScript on (the runtime needs it), no JS bridge, no
 * file, content or DOM storage, no geolocation, no third-party cookies, media only after a gesture,
 * and only the bundle's own files load (from the downloaded copy, else from the bundle URL).
 * Readiness is polled through `window.__qartvelo.ready`.
 */
internal class Html5AdView private constructor(context: Context, private val ad: ServedAd) : Html5Surface {
    private val bundle: Html5Bundle = ad.bundle ?: throw IllegalArgumentException("not an HTML5 ad")
    private val dir: File = ad.file ?: throw IllegalArgumentException("bundle not downloaded")
    private val web = WebView(context)
    private var onReady: (() -> Unit)? = null
    private var onFailed: ((String) -> Unit)? = null
    private var onClick: (() -> Unit)? = null
    private var firstLoadDone = false
    private var settled = false
    private var destroyed = false

    override val view: View get() = web

    private val poll = object : Runnable {
        override fun run() {
            if (settled || destroyed) return
            web.evaluateJavascript(READY_JS) { result -> if (result == "true") ready() }
            Main.postDelayed(Html5Policy.READY_POLL_MS, this)
        }
    }

    private val timeout = Runnable { fail("not ready within ${Html5Policy.READY_TIMEOUT_MS} ms") }

    @SuppressLint("SetJavaScriptEnabled")
    override fun load(onReady: () -> Unit, onFailed: (String) -> Unit, onClick: () -> Unit) {
        this.onReady = onReady
        this.onFailed = onFailed
        this.onClick = onClick
        web.settings.apply {
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            @Suppress("DEPRECATION")
            databaseEnabled = false
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.setBackgroundColor(Color.TRANSPARENT)
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.isLongClickable = false
        web.setOnLongClickListener { true }
        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        Main.postDelayed(Html5Policy.READY_TIMEOUT_MS, timeout)
        web.loadUrl(bundle.baseUrl + Html5Files.INDEX)
        Main.postDelayed(Html5Policy.READY_POLL_MS, poll)
    }

    override fun pause() {
        if (destroyed) return
        web.evaluateJavascript(PAUSE_JS, null)
        web.onPause()
    }

    override fun resume() {
        if (destroyed) return
        web.onResume()
        web.evaluateJavascript(RESUME_JS, null)
    }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        settled = true
        Main.cancel(poll)
        Main.cancel(timeout)
        onReady = null
        onFailed = null
        onClick = null
        (web.parent as? ViewGroup)?.removeView(web)
        guard("web view destroy") {
            web.stopLoading()
            web.webChromeClient = null
            web.destroy()
        }
    }

    private fun ready() {
        if (settled) return
        settled = true
        Main.cancel(poll)
        Main.cancel(timeout)
        onReady?.invoke()
    }

    private fun fail(reason: String) {
        if (settled) return
        settled = true
        Main.cancel(poll)
        Main.cancel(timeout)
        OurLog.i("HTML5 creative failed: $reason")
        onFailed?.invoke(reason)
    }

    private fun click() {
        if (!destroyed) onClick?.invoke()
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            return when (val decision = Html5Policy.intercept(dir, bundle.baseUrl, request.url.toString(), request.method)) {
                is Html5Policy.Decision.Serve -> try {
                    val text = decision.mimeType.startsWith("text/") || decision.mimeType == "image/svg+xml"
                    WebResourceResponse(decision.mimeType, if (text) "UTF-8" else null, 200, "OK", Html5Policy.headers, FileInputStream(decision.file))
                } catch (t: Throwable) {
                    blocked()
                }
                Html5Policy.Decision.Network -> null
                Html5Policy.Decision.Block -> blocked().also { OurLog.d("HTML5 request blocked: ${request.method} ${request.url.scheme}://${request.url.host}${request.url.encodedPath}") }
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (request.isForMainFrame && Html5Policy.isClick(request.url.toString(), firstLoadDone)) Main.post { click() }
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            firstLoadDone = true
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) Main.post { fail("main frame error ${error.errorCode}") }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Never let a crashed or killed renderer take the app down: drop the ad instead.
            Main.post {
                fail("renderer gone")
                destroy()
            }
            return true
        }

        private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", Html5Policy.headers, ByteArrayInputStream(ByteArray(0)))
    }

    private inner class Chrome : WebChromeClient() {
        /** `window.open` (or a target=_blank link) is a click; no window is ever created. */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            if (firstLoadDone) Main.post { click() }
            return false
        }
    }

    companion object {
        private const val READY_JS = "!!(window.__qartvelo && window.__qartvelo.ready === true)"
        private const val PAUSE_JS = "window.__qartvelo && window.__qartvelo.pause && window.__qartvelo.pause();"
        private const val RESUME_JS = "window.__qartvelo && window.__qartvelo.resume && window.__qartvelo.resume();"

        /** The surface for an HTML5 [ad] whose bundle was downloaded. Tests swap in a fake through [TestHooks]. */
        fun create(context: Context, ad: ServedAd): Html5Surface =
            TestHooks.html5SurfaceFactory?.invoke(context, ad) ?: Html5AdView(context, ad)
    }
}
