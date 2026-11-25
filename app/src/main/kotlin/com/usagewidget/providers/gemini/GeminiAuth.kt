package com.usagewidget.providers.gemini

import com.usagewidget.auth.SecureCookieStore
import com.usagewidget.domain.ProviderResult
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object GeminiOAuth {
    const val CLIENT_ID = "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    const val USER_AGENT = "antigravity/2.34.0 (aidev_client; os_type=android; arch=arm64)"
    private const val REFERENCE = "https://raw.githubusercontent.com/PowerUserZ/OpenTokenUsage/main/docs/providers/antigravity.md"
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).followRedirects(false).build()

    // Public installed-app credential resolved in memory only; never persisted or logged.
    private fun publicClientCredential(): String? = runCatching {
        http.newCall(Request.Builder().url(REFERENCE).build()).execute().use {
            if (!it.isSuccessful) return@use null
            val text = it.body?.string() ?: return@use null
            if (text.length > 100_000 || !text.contains("client_id=$CLIENT_ID")) return@use null
            Regex("client_secret=(GOCSPX-[A-Za-z0-9_-]+)").find(text)?.groupValues?.get(1)
        }
    }.getOrNull()

    fun exchange(store: SecureCookieStore, fields: Map<String, String>): Boolean = runCatching {
        val secret = publicClientCredential() ?: return false
        val form = FormBody.Builder().add("client_id", CLIENT_ID).add("client_secret", secret)
        fields.forEach { (key, value) -> form.add(key, value) }
        http.newCall(Request.Builder().url(TOKEN_ENDPOINT).post(form.build()).build()).execute().use {
            if (!it.isSuccessful) return@use false
            val response = GeminiUsageParser.obj(it.body?.string())
            val access = GeminiUsageParser.text(response, "access_token") ?: return@use false
            val old = GeminiUsageParser.obj(store.geminiTokens())
            val refresh = GeminiUsageParser.text(response, "refresh_token") ?: GeminiUsageParser.text(old, "refresh_token")
            val expires = (response["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600
            store.saveGeminiTokens(buildJsonObject {
                put("access_token", access)
                refresh?.let { token -> put("refresh_token", token) }
                put("expiry_date", System.currentTimeMillis() + expires * 1000)
            }.toString())
            true
        }
    }.getOrDefault(false)

    fun fetch(store: SecureCookieStore, now: Long): ProviderResult {
        if (store.geminiTokens() == null) return ProviderResult.NeedsSignIn("Sign in with Google (Antigravity).")
        return runCatching {
            var token = GeminiUsageParser.obj(store.geminiTokens())
            var refreshed = false
            fun refresh(): Boolean {
                val value = GeminiUsageParser.text(token, "refresh_token") ?: return false
                val success = exchange(store, mapOf("grant_type" to "refresh_token", "refresh_token" to value))
                token = GeminiUsageParser.obj(store.geminiTokens())
                return success
            }
            if (GeminiUsageParser.text(token, "access_token") == null || ((token["expiry_date"] as? JsonPrimitive)?.longOrNull ?: 0L) <= now + 60_000) {
                if (!refresh()) return ProviderResult.NeedsSignIn("Antigravity session expired. Sign in again.")
                refreshed = true
            }
            fun client(): GeminiQuotaClient = GeminiQuotaClient { host, method, body, seconds ->
                val request = Request.Builder().url("$host/v1internal:$method")
                    .header("Authorization", "Bearer ${GeminiUsageParser.text(token, "access_token")}")
                    .header("User-Agent", USER_AGENT).post(body.toRequestBody("application/json".toMediaType())).build()
                http.newBuilder().callTimeout(seconds.toLong(), TimeUnit.SECONDS).build().newCall(request).execute().use {
                    GeminiResponse(it.code, it.body?.string() ?: "{}")
                }
            }
            val quota = client()
            var snapshot = quota.fetch()
            if (snapshot == null && quota.authRejected && !refreshed && refresh()) snapshot = client().fetch()
            if (snapshot == null) ProviderResult.Failure("Gemini quota unavailable. Try again later.", "cloud-code") else ProviderResult.Success(snapshot)
        }.getOrElse { ProviderResult.Failure("Gemini quota unavailable. Try again later.", "cloud-code") }
    }
}
