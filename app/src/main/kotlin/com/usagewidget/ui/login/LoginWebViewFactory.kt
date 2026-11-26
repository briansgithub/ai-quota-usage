package com.usagewidget.ui.login

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.usagewidget.auth.SecureCookieStore
import com.usagewidget.domain.ProviderId
import com.usagewidget.providers.cursor.CursorEndpoints
import com.usagewidget.providers.claude.ClaudeEndpoints
import com.usagewidget.providers.openai.OpenAiEndpoints

data class LoginPageState(
    val url: String = "",
    val title: String = "",
    val progress: Int = 0,
    val error: String? = null,
    val likelyBlank: Boolean = false,
    val googleBlocked: Boolean = false
)

/**
 * Login WebView tuned so Cloudflare Turnstile and ChatGPT's auth pages can render.
 *
 * Pixel 7a logs showed Turnstile loading, then a logged-out chatgpt.com shell
 * (Statsig without userID) and only "Cookie preferences" visible. Causes we
 * address: spoofed Chrome/131 UA vs real WebView Chromium, stripped
 * X-Requested-With, dark-mode inversion, missing popup windows, and starting
 * on /settings/usage instead of the login route.
 */
class LoginWebViewHost {
    var container: FrameLayout? = null
    var webView: WebView? = null

    fun reload() {
        webView?.reload()
    }

    fun load(url: String) {
        webView?.loadUrl(url)
    }

    fun destroy() {
        webView?.stopLoading()
        webView?.destroy()
        webView = null
        container = null
    }
}

@SuppressLint("SetJavaScriptEnabled")
fun createLoginWebView(
    context: Context,
    provider: ProviderId,
    startUrl: String,
    onState: (LoginPageState) -> Unit,
    onMaybeSignedIn: () -> Unit
): FrameLayout {
    val store = SecureCookieStore.get(context)
    CookieManager.getInstance().setAcceptCookie(true)

    val webContext = if (provider == ProviderId.OPENAI) lightUiContext(context) else context
    val container = FrameLayout(context)
    val webView = WebView(webContext)
    container.addView(
        webView,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    )
    webView.setBackgroundColor(Color.TRANSPARENT)
    applyLoginSettings(webView)
    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

    var state = LoginPageState()
    fun emit(update: LoginPageState.() -> LoginPageState) {
        state = state.update()
        onState(state)
    }

    webView.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            emit { copy(progress = newProgress) }
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            emit { copy(title = title.orEmpty()) }
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message
        ): Boolean {
            val popup = WebView(webContext)
            applyLoginSettings(popup)
            CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)
            popup.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    if (looksLikeGoogleBlock(url, v.title)) emit { copy(googleBlocked = true) }
                    return false
                }
            }
            popup.webChromeClient = object : WebChromeClient() {
                override fun onCloseWindow(window: WebView) {
                    container.removeView(popup)
                    popup.destroy()
                }
            }
            container.addView(
                popup,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            val transport = resultMsg.obj as WebView.WebViewTransport
            transport.webView = popup
            resultMsg.sendToTarget()
            return true
        }
    }

    webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (url.startsWith("http://") || url.startsWith("https://")) {
                if (looksLikeGoogleBlock(url, view.title)) emit { copy(googleBlocked = true) }
                return false
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            emit { copy(url = url, error = null, likelyBlank = false, progress = 0) }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (request.isForMainFrame) {
                emit { copy(error = error.description?.toString() ?: "Page failed to load") }
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            emit { copy(url = url, title = view.title.orEmpty(), progress = 100) }
            if (looksLikeGoogleBlock(url, view.title)) emit { copy(googleBlocked = true) }
            store.captureFromCookieManager(provider)
            if (store.hasSession(provider) && landedOnProviderHome(provider, url)) {
                view.postDelayed({
                    store.captureFromCookieManager(provider)
                    if (store.hasSession(provider)) onMaybeSignedIn()
                }, 600)
            }
            view.postDelayed({
                view.evaluateJavascript(BLANK_PAGE_JS) { raw ->
                    val blank = raw?.contains("true") == true
                    if (blank) emit { copy(likelyBlank = true) }
                }
            }, 2500)
        }
    }

    webView.loadUrl(startUrl)
    return container
}

@SuppressLint("SetJavaScriptEnabled")
fun applyLoginSettings(webView: WebView) {
    val settings = webView.settings
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.databaseEnabled = true
    settings.javaScriptCanOpenWindowsAutomatically = true
    settings.setSupportMultipleWindows(true)
    settings.loadsImagesAutomatically = true
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.mediaPlaybackRequiresUserGesture = true
    settings.cacheMode = WebSettings.LOAD_DEFAULT
    // Do not spoof Chrome versions. Cloudflare Turnstile fingerprints UA
    // against the real WebView Chromium build and client hints.
    // Do not strip X-Requested-With globally for the same reason.
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
        WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
    }
    @Suppress("DEPRECATION")
    if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
        WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF)
    }
    webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
}

fun loginStartUrl(provider: ProviderId): String = when (provider) {
    ProviderId.CURSOR -> CursorEndpoints.LOGIN_PAGE
    ProviderId.OPENAI -> OpenAiEndpoints.LOGIN_PAGE
    ProviderId.CLAUDE -> ClaudeEndpoints.LOGIN_PAGE
    ProviderId.GEMINI -> error("Gemini uses external browser OAuth")
}

fun loginFallbackUrl(provider: ProviderId): String = when (provider) {
    ProviderId.CURSOR -> CursorEndpoints.LOGIN_PAGE
    ProviderId.OPENAI -> OpenAiEndpoints.LOGIN_FALLBACK
    ProviderId.CLAUDE -> ClaudeEndpoints.LOGIN_PAGE
    ProviderId.GEMINI -> error("Gemini uses external browser OAuth")
}

fun landedOnProviderHome(provider: ProviderId, url: String): Boolean {
    val lower = url.lowercase()
    return when (provider) {
        ProviderId.CURSOR ->
            lower.contains("cursor.com") &&
                (lower.contains("/dashboard") || lower.contains("/settings")) &&
                !lower.contains("login") &&
                !lower.contains("authenticator")
        ProviderId.OPENAI ->
            (lower.contains("chatgpt.com") || lower.contains("chat.openai.com")) &&
                !lower.contains("/auth") &&
                !lower.contains("login") &&
                !lower.contains("log-in")
        ProviderId.GEMINI -> false
        ProviderId.CLAUDE ->
            lower.contains("claude.ai") &&
                !lower.contains("/login") &&
                !lower.contains("/magic-link") &&
                !lower.contains("/oauth")
    }
}

private fun looksLikeGoogleBlock(url: String, title: String?): Boolean {
    val lower = url.lowercase()
    return lower.contains("accounts.google.com") &&
        (lower.contains("webview") ||
            lower.contains("deniedsigninrejected") ||
            title?.contains("not secure", true) == true)
}

private fun lightUiContext(base: Context): Context {
    val config = Configuration(base.resources.configuration)
    config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
        Configuration.UI_MODE_NIGHT_NO
    return base.createConfigurationContext(config)
}

private const val BLANK_PAGE_JS =
    "(function(){try{var t=(document.body&&document.body.innerText||'').replace(/\\s+/g,' ').trim();" +
        "var cookieOnly=/cookie preferences/i.test(t)&&t.length<160;" +
        "var hasLogin=/log in|sign in|continue with|email address/i.test(t);" +
        "return (cookieOnly||(t.length<48&&!hasLogin))?'true':'false';}catch(e){return 'false';}})()"
