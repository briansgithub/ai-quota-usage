package com.usagewidget.extract

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.usagewidget.domain.ProviderId
import com.usagewidget.providers.cursor.CursorEndpoints
import com.usagewidget.providers.openai.OpenAiEndpoints
import com.usagewidget.ui.login.applyLoginSettings
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

class HiddenWebViewExtractor(private val appContext: Context) {

    suspend fun extractPageText(provider: ProviderId, timeoutMs: Long = 25_000L): String {
        return withTimeout(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                Handler(Looper.getMainLooper()).post {
                    val webView = createWebView()
                    val finish = {
                        Handler(Looper.getMainLooper()).post {
                            webView.stopLoading()
                            webView.destroy()
                        }
                    }
                    cont.invokeOnCancellation { finish() }
                    webView.webViewClient = object : WebViewClient() {
                        private var done = false
                        override fun onPageFinished(view: WebView, url: String) {
                            if (done) return
                            view.postDelayed({
                                if (done) return@postDelayed
                                view.evaluateJavascript(INNER_TEXT_JS) { raw ->
                                    if (done) return@evaluateJavascript
                                    done = true
                                    val text = decodeJsString(raw)
                                    finish()
                                    if (cont.isActive) cont.resume(text)
                                }
                            }, 2500)
                        }
                    }
                    webView.loadUrl(pageUrl(provider))
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        CookieManager.getInstance().setAcceptCookie(true)
        val webView = WebView(appContext)
        applyLoginSettings(webView)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.layout(0, 0, 1, 1)
        return webView
    }

    private fun pageUrl(provider: ProviderId): String = when (provider) {
        ProviderId.CURSOR -> CursorEndpoints.SPENDING_PAGE
        ProviderId.OPENAI -> OpenAiEndpoints.USAGE_PAGE
    }

    companion object {
        private const val INNER_TEXT_JS =
            "(function(){return document.body ? document.body.innerText : '';})()"

        fun decodeJsString(raw: String?): String {
            if (raw.isNullOrBlank() || raw == "null") return ""
            val unquoted = if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                raw.substring(1, raw.length - 1)
            } else {
                raw
            }
            return unquoted
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\\\", "\\")
        }
    }
}
