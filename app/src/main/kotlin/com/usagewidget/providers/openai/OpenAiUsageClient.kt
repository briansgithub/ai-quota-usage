package com.usagewidget.providers.openai

import com.usagewidget.auth.SecureCookieStore
import com.usagewidget.domain.FetchAttempt
import com.usagewidget.domain.FetchOutcome
import com.usagewidget.domain.ProviderId
import com.usagewidget.domain.UsageDump
import com.usagewidget.domain.ProviderResult
import com.usagewidget.domain.ProviderSnapshot
import com.usagewidget.domain.ProviderStatus
import com.usagewidget.domain.SoftwareSectionId
import com.usagewidget.net.AuthenticatedHttp
import com.usagewidget.net.FetchErrorClassifier
import com.usagewidget.net.SessionExpiredException

class OpenAiUsageClient(
    private val http: AuthenticatedHttp,
    private val cookies: SecureCookieStore
) {
    fun fetch(fetchedAtMs: Long = System.currentTimeMillis()): ProviderResult {
        if (!cookies.hasSession(ProviderId.OPENAI)) {
            return ProviderResult.NeedsSignIn("Sign in to ChatGPT to load Codex usage.")
        }
        var endpoint = OpenAiEndpoints.SESSION
        return try {
            val sessionResponse = http.getJson(
                url = OpenAiEndpoints.SESSION,
                cookieHost = "chatgpt.com",
                extraHeaders = mapOf("Referer" to OpenAiEndpoints.USAGE_PAGE)
            )
            val session = OpenAiUsageParser.parseSession(sessionResponse.body)
            val headers = mutableMapOf(
                "Authorization" to "Bearer ${session.accessToken}",
                "Referer" to OpenAiEndpoints.USAGE_PAGE,
                "Origin" to OpenAiEndpoints.ORIGIN
            )
            val accountId = session.accountId
                ?: accountIdFromCookie()
            if (!accountId.isNullOrBlank()) {
                headers["ChatGPT-Account-Id"] = accountId
            }
            endpoint = OpenAiEndpoints.WHAM_USAGE
            val usageResponse = http.getJson(
                url = OpenAiEndpoints.WHAM_USAGE,
                cookieHost = "chatgpt.com",
                extraHeaders = headers
            )
            val usage = OpenAiUsageParser.parseWhamUsage(usageResponse.body, fetchedAtMs)
            val detailsBody = runCatching {
                http.getJson(
                    url = OpenAiEndpoints.WHAM_RESET_CREDITS,
                    cookieHost = "chatgpt.com",
                    extraHeaders = headers
                ).body
            }.getOrNull()
            val merged = OpenAiUsageParser.mergeResetDetails(usage, detailsBody, fetchedAtMs)
            val snapshot = OpenAiUsageParser.toSnapshot(merged, source = "json").copy(
                diagnosticJson = UsageDump.openaiPayload(usageResponse.body, detailsBody)
            )
            ProviderResult.Success(
                snapshot,
                attempts = listOf(
                    FetchAttempt.of(
                        atEpochMs = fetchedAtMs,
                        sectionId = SoftwareSectionId.CODEX,
                        outcome = FetchOutcome.SUCCESS,
                        endpoint = OpenAiEndpoints.WHAM_USAGE,
                        source = "json"
                    )
                )
            )
        } catch (expired: SessionExpiredException) {
            ProviderResult.NeedsSignIn(
                message = "ChatGPT session expired. Sign in again.",
                attempts = listOf(
                    FetchErrorClassifier.attempt(expired, SoftwareSectionId.CODEX, endpoint, fetchedAtMs)
                )
            )
        } catch (ex: Exception) {
            val attempt = FetchErrorClassifier.attempt(ex, SoftwareSectionId.CODEX, endpoint, fetchedAtMs)
            if (attempt.outcomeEnum == FetchOutcome.AUTH_EXPIRED) {
                ProviderResult.NeedsSignIn(
                    message = "ChatGPT session expired. Sign in again.",
                    attempts = listOf(attempt)
                )
            } else {
                ProviderResult.Failure(
                    message = attempt.shortReason.ifBlank { ex.message ?: ex.javaClass.simpleName },
                    cause = "json",
                    attempts = listOf(attempt)
                )
            }
        }
    }

    fun snapshotFromHtml(pageText: String, fetchedAtMs: Long): ProviderSnapshot {
        val usage = OpenAiHtmlExtractor.extract(pageText, fetchedAtMs)
        return OpenAiUsageParser.toSnapshot(usage, source = "html")
    }

    fun htmlAttempt(fetchedAtMs: Long): FetchAttempt = FetchAttempt.of(
        atEpochMs = fetchedAtMs,
        sectionId = SoftwareSectionId.CODEX,
        outcome = FetchOutcome.SUCCESS,
        endpoint = OpenAiEndpoints.USAGE_PAGE,
        source = "html"
    )

    fun unsigned(): ProviderSnapshot = ProviderSnapshot(
        id = ProviderId.OPENAI.wireName,
        displayName = ProviderId.OPENAI.displayName,
        status = ProviderStatus.UNSIGNED.name
    )

    private fun accountIdFromCookie(): String? {
        val header = cookies.cookieHeader("chatgpt.com") ?: return null
        return SecureCookieStore.cookieMap(header)["_account"]
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotBlank() && it != "0" }
    }
}
