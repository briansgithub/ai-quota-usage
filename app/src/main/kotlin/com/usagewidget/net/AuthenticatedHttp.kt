package com.usagewidget.net

import com.usagewidget.auth.SecureCookieStore
import com.usagewidget.util.HttpBodies
import okhttp3.CacheControl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

data class HttpJsonResult(
    val code: Int,
    val body: String,
    val finalUrl: String,
    val setCookies: List<String>
) {
    val isSuccessful: Boolean get() = code in 200..299
}

class AuthenticatedHttp(
    private val cookies: SecureCookieStore
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun getJson(
        url: String,
        cookieHost: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): HttpJsonResult = execute(
        Request.Builder().url(url).get(),
        cookieHost,
        extraHeaders
    )

    fun postJson(
        url: String,
        cookieHost: String,
        jsonBody: String = "{}",
        extraHeaders: Map<String, String> = emptyMap()
    ): HttpJsonResult = execute(
        Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())),
        cookieHost,
        extraHeaders
    )

    private fun execute(
        builder: Request.Builder,
        cookieHost: String,
        extraHeaders: Map<String, String>
    ): HttpJsonResult {
        builder.header("Accept", "application/json")
        builder.header("User-Agent", AppHeaders.USER_AGENT)
        builder.header("Accept-Language", "en-US,en;q=0.9")
        builder.header("Cache-Control", "no-cache, no-store")
        builder.header("Pragma", "no-cache")
        builder.cacheControl(CacheControl.FORCE_NETWORK)
        val cookie = cookies.cookieHeader(cookieHost)
        if (!cookie.isNullOrBlank()) {
            builder.header("Cookie", cookie)
        }
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }

        val response: Response = try {
            client.newCall(builder.build()).execute()
        } catch (io: IOException) {
            throw io
        }

        response.use { res ->
            val body = res.body?.string().orEmpty()
            val setCookies = res.headers("Set-Cookie")
            if (setCookies.isNotEmpty()) {
                cookies.applySetCookieHeaders(cookieHost, setCookies)
            }
            val result = HttpJsonResult(
                code = res.code,
                body = body,
                finalUrl = res.request.url.toString(),
                setCookies = setCookies
            )
            if (HttpBodies.isUnauthorizedPayload(result.body, result.code, result.finalUrl)) {
                throw SessionExpiredException(result.code, result.finalUrl)
            }
            if (!result.isSuccessful) {
                throw ProviderHttpException(result.code, summarizeBody(result.body), result.finalUrl)
            }
            if (!HttpBodies.looksLikeJson(result.body)) {
                if (HttpBodies.looksLikeHtml(result.body)) {
                    throw SessionExpiredException(result.code, result.finalUrl)
                }
                throw ProviderHttpException(result.code, "Non-JSON response", result.finalUrl)
            }
            return result
        }
    }

    private fun summarizeBody(body: String): String {
        val trimmed = body.replace("\n", " ").trim()
        return trimmed.take(160)
    }
}

class SessionExpiredException(val code: Int, val url: String) :
    RuntimeException("Session expired (HTTP $code)")

class ProviderHttpException(val code: Int, val snippet: String, val url: String) :
    RuntimeException("HTTP $code from $url")
