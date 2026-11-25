package com.usagewidget.providers.gemini

import com.usagewidget.auth.SecureCookieStore
import com.usagewidget.domain.FetchAttempt
import com.usagewidget.domain.FetchOutcome
import com.usagewidget.domain.ProviderResult
import com.usagewidget.domain.SoftwareSectionId
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

object GeminiOAuth {
    const val CLIENT_ID = "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    const val USER_AGENT = "antigravity/2.34.0 (aidev_client; os_type=android; arch=arm64)"
    private const val REFERENCE = "https://raw.githubusercontent.com/PowerUserZ/OpenTokenUsage/main/docs/providers/antigravity.md"
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).followRedirects(false).build()
    @Volatile private var cachedPublicCredential: String? = null

    internal enum class RefreshStatus { OK, AUTH_REJECTED, TRANSIENT_FAILURE }
    internal data class RefreshResult(val status: RefreshStatus, val outcome: FetchOutcome = FetchOutcome.UNKNOWN)

    // Public installed-app credential resolved in memory only; never persisted or logged.
    private fun publicClientCredential(): String? {
        cachedPublicCredential?.let { return it }
        val resolved = runCatching {
            http.newCall(Request.Builder().url(REFERENCE).build()).execute().use {
                if (!it.isSuccessful) return@use null
                val text = it.body?.string() ?: return@use null
                if (text.length > 100_000 || !text.contains("client_id=$CLIENT_ID")) return@use null
                Regex("client_secret=(GOCSPX-[A-Za-z0-9_-]+)").find(text)?.groupValues?.get(1)
            }
        }.getOrNull()
        if (resolved != null) cachedPublicCredential = resolved
        return resolved
    }

    fun exchange(store: SecureCookieStore, fields: Map<String, String>): Boolean =
        exchangeResult(
            readTokens = { store.geminiTokens() },
            saveTokens = { store.saveGeminiTokens(it) },
            fields = fields
        ).status == RefreshStatus.OK

    internal fun exchangeResult(
        readTokens: () -> String?,
        saveTokens: (String) -> Unit,
        fields: Map<String, String>,
        nowMs: Long = System.currentTimeMillis(),
        clientCredential: () -> String? = ::publicClientCredential,
        postToken: (Map<String, String>) -> GeminiResponse = ::postTokenForm
    ): RefreshResult {
        val secret = clientCredential() ?: return RefreshResult(RefreshStatus.TRANSIENT_FAILURE, FetchOutcome.NETWORK)
        val payload = LinkedHashMap<String, String>(fields.size + 2).apply {
            put("client_id", CLIENT_ID)
            put("client_secret", secret)
            putAll(fields)
        }
        return try {
            val res = postToken(payload)
            if (res.status !in 200..299) {
                return if (res.status == 400 || res.status == 401) {
                    RefreshResult(RefreshStatus.AUTH_REJECTED, FetchOutcome.AUTH_EXPIRED)
                } else {
                    RefreshResult(RefreshStatus.TRANSIENT_FAILURE, if (res.status > 0) FetchOutcome.HTTP else FetchOutcome.NETWORK)
                }
            }
            val response = GeminiUsageParser.obj(res.body)
            val access = GeminiUsageParser.text(response, "access_token")
                ?: return RefreshResult(RefreshStatus.TRANSIENT_FAILURE, FetchOutcome.PARSE)
            val old = GeminiUsageParser.obj(readTokens())
            val refresh = GeminiUsageParser.text(response, "refresh_token") ?: GeminiUsageParser.text(old, "refresh_token")
            val expires = (response["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600L
            saveTokens(buildJsonObject {
                put("access_token", access)
                refresh?.let { token -> put("refresh_token", token) }
                put("expiry_date", nowMs + expires * 1000)
            }.toString())
            RefreshResult(RefreshStatus.OK, FetchOutcome.SUCCESS)
        } catch (_: SocketTimeoutException) {
            RefreshResult(RefreshStatus.TRANSIENT_FAILURE, FetchOutcome.TIMEOUT)
        } catch (_: Exception) {
            RefreshResult(RefreshStatus.TRANSIENT_FAILURE, FetchOutcome.NETWORK)
        }
    }

    private fun postTokenForm(fields: Map<String, String>): GeminiResponse {
        val form = FormBody.Builder()
        fields.forEach { (key, value) -> form.add(key, value) }
        http.newCall(Request.Builder().url(TOKEN_ENDPOINT).post(form.build()).build()).execute().use {
            return GeminiResponse(it.code, it.body?.string() ?: "{}")
        }
    }

    fun fetch(store: SecureCookieStore, now: Long): ProviderResult = fetchWith(
        readTokens = { store.geminiTokens() },
        saveTokens = { store.saveGeminiTokens(it) },
        now = now
    )

    internal fun fetchWith(
        readTokens: () -> String?,
        saveTokens: (String) -> Unit,
        now: Long,
        refreshToken: (String) -> RefreshResult = { value ->
            exchangeResult(readTokens, saveTokens, mapOf("grant_type" to "refresh_token", "refresh_token" to value), now)
        },
        quotaPost: (accessToken: String, host: String, method: String, body: String, seconds: Int) -> GeminiResponse = { access, host, method, body, seconds ->
            val request = Request.Builder().url("$host/v1internal:$method")
                .header("Authorization", "Bearer $access")
                .header("User-Agent", USER_AGENT).post(body.toRequestBody("application/json".toMediaType())).build()
            http.newBuilder().callTimeout(seconds.toLong(), TimeUnit.SECONDS).build().newCall(request).execute().use {
                GeminiResponse(it.code, it.body?.string() ?: "{}")
            }
        }
    ): ProviderResult {
        if (readTokens() == null) return ProviderResult.NeedsSignIn("Sign in with Google (Antigravity).")
        return runCatching {
            var token = GeminiUsageParser.obj(readTokens())
            var refreshed = false
            fun refresh(): RefreshResult {
                val value = GeminiUsageParser.text(token, "refresh_token")
                    ?: return RefreshResult(RefreshStatus.AUTH_REJECTED, FetchOutcome.AUTH_EXPIRED)
                val result = refreshToken(value)
                if (result.status == RefreshStatus.OK) token = GeminiUsageParser.obj(readTokens())
                return result
            }
            if (GeminiUsageParser.text(token, "access_token") == null || ((token["expiry_date"] as? JsonPrimitive)?.longOrNull ?: 0L) <= now + 60_000) {
                val r = refresh()
                if (r.status == RefreshStatus.AUTH_REJECTED) return@runCatching authExpired(now)
                if (r.status != RefreshStatus.OK) return@runCatching failure(now, r.outcome)
                refreshed = true
            }
            fun client(): GeminiQuotaClient {
                val access = GeminiUsageParser.text(token, "access_token") ?: ""
                return GeminiQuotaClient { host, method, body, seconds -> quotaPost(access, host, method, body, seconds) }
            }
            var quota = client()
            var snapshot = quota.fetch()
            if (snapshot == null && quota.authRejected) {
                if (!refreshed) {
                    val retry = refresh()
                    if (retry.status == RefreshStatus.AUTH_REJECTED) return@runCatching authExpired(now)
                    if (retry.status != RefreshStatus.OK) return@runCatching failure(now, retry.outcome)
                    quota = client()
                    snapshot = quota.fetch()
                }
                if (snapshot == null && quota.authRejected) return@runCatching authExpired(now)
            }
            if (snapshot == null) {
                failure(now, FetchOutcome.NETWORK)
            } else {
                ProviderResult.Success(
                    snapshot,
                    attempts = listOf(FetchAttempt.of(now, SoftwareSectionId.GEMINI, FetchOutcome.SUCCESS, source = "cloud-code"))
                )
            }
        }.getOrElse { failure(now, FetchOutcome.NETWORK) }
    }

    private fun authExpired(now: Long): ProviderResult.NeedsSignIn = ProviderResult.NeedsSignIn(
        message = "Antigravity session expired. Sign in again.",
        attempts = listOf(
            FetchAttempt.of(now, SoftwareSectionId.GEMINI, FetchOutcome.AUTH_EXPIRED, message = "Antigravity session expired. Sign in again.", source = "cloud-code")
        )
    )

    private fun failure(now: Long, outcome: FetchOutcome): ProviderResult.Failure = ProviderResult.Failure(
        message = "Gemini quota unavailable. Try again later.",
        cause = "cloud-code",
        attempts = listOf(
            FetchAttempt.of(now, SoftwareSectionId.GEMINI, outcome, message = "Gemini quota unavailable. Try again later.", source = "cloud-code")
        )
    )
}
