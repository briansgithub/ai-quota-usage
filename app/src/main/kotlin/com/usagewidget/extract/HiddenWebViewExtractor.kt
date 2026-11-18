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
import com.usagewidget.providers.claude.ClaudeEndpoints
import com.usagewidget.providers.claude.ClaudeUsageParser
import com.usagewidget.providers.openai.OpenAiEndpoints
import com.usagewidget.util.HttpBodies
import com.usagewidget.ui.login.applyLoginSettings
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.layout(0, 0, 1, 1)
        return webView
    }

    private fun pageUrl(provider: ProviderId): String = when (provider) {
        ProviderId.CURSOR -> CursorEndpoints.SPENDING_PAGE
        ProviderId.OPENAI -> OpenAiEndpoints.USAGE_PAGE
        // Claude is read as JSON via [readClaudeUsageJson]; the page is only a fallback.
        ProviderId.CLAUDE -> ClaudeEndpoints.USAGE_PAGE
    }

    /**
     * claude.ai's JSON API sits behind Cloudflare, which can challenge OkHttp. A WebView
     * with the signed-in cookie jar is a real browser, so read the same JSON documents
     * there: organizations first (for the org uuid), then that org's usage.
     */
    suspend fun readClaudeUsageJson(timeoutMs: Long = 40_000L): String = withTimeout(timeoutMs) {
        val orgs = readJsonDocument(ClaudeEndpoints.ORGANIZATIONS)
        if (ClaudeUsageParser.isAuthErrorBody(orgs)) return@withTimeout orgs
        val lastActive = CookieManager.getInstance().getCookie(ClaudeEndpoints.ORIGIN)
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith(ClaudeEndpoints.LAST_ACTIVE_ORG_COOKIE + "=") }
            ?.substringAfter('=')
        val orgId = ClaudeUsageParser.parseOrgId(orgs, lastActive)
        readJsonDocument(ClaudeEndpoints.usage(orgId))
    }

    /**
     * Loads a JSON URL and polls the body text until it is JSON (a Cloudflare interstitial
     * reloads itself into the real document) or about 20 seconds pass.
     */
    private suspend fun readJsonDocument(url: String): String =
        suspendCancellableCoroutine { cont ->
            val main = Handler(Looper.getMainLooper())
            main.post {
                val webView = createWebView()
                var done = false
                var polls = 0
                var last = ""
                val finish = {
                    main.post {
                        webView.stopLoading()
                        webView.destroy()
                    }
                }
                cont.invokeOnCancellation { done = true; finish() }
                lateinit var poll: Runnable
                poll = Runnable {
                    if (done) return@Runnable
                    webView.evaluateJavascript(INNER_TEXT_JS) { raw ->
                        if (done) return@evaluateJavascript
                        last = decodeJsString(raw).trim()
                        polls++
                        if (HttpBodies.looksLikeJson(last) || polls >= 40) {
                            done = true
                            finish()
                            if (cont.isActive) {
                                if (HttpBodies.looksLikeJson(last)) cont.resume(last)
                                else cont.resumeWithException(IllegalStateException("claude.ai did not return JSON in the browser"))
                            }
                        } else {
                            main.postDelayed(poll, 500)
                        }
                    }
                }
                webView.webViewClient = WebViewClient()
                webView.loadUrl(url)
                main.postDelayed(poll, 800)
            }
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
