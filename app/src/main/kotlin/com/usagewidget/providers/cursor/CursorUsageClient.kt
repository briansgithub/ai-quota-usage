package com.usagewidget.providers.cursor

import com.usagewidget.auth.SecureCookieStore
import com.usagewidget.domain.FetchAttempt
import com.usagewidget.domain.FetchOutcome
import com.usagewidget.domain.ManualResetParser
import com.usagewidget.domain.ProviderId
import com.usagewidget.domain.UsageDump
import com.usagewidget.domain.ProviderResult
import com.usagewidget.domain.ProviderSnapshot
import com.usagewidget.domain.ProviderStatus
import com.usagewidget.domain.SoftwareSectionId
import com.usagewidget.net.AuthenticatedHttp
import com.usagewidget.util.JsonNav
import com.usagewidget.net.FetchErrorClassifier
import com.usagewidget.net.SessionExpiredException

class CursorUsageClient(
    private val http: AuthenticatedHttp,
    private val cookies: SecureCookieStore
) {
    fun fetch(fetchedAtMs: Long = System.currentTimeMillis()): ProviderResult {
        if (!cookies.hasSession(ProviderId.CURSOR)) {
            return ProviderResult.NeedsSignIn("Sign in to Cursor to load plan usage.")
        }
        var plan: CursorPlanUsage? = null
        var grok: CursorGrokUsage? = null
        val attempts = mutableListOf<FetchAttempt>()
        var planError: String? = null
        var grokError: String? = null
        var summaryRaw: String? = null
        var sandRaw: String? = null

        try {
            val summary = http.getJson(
                url = CursorEndpoints.USAGE_SUMMARY,
                cookieHost = "cursor.com",
                extraHeaders = mapOf("Referer" to CursorEndpoints.SPENDING_PAGE)
            )
            summaryRaw = summary.body
            plan = CursorUsageParser.parseUsageSummary(summary.body, fetchedAtMs)
            attempts += FetchAttempt.of(
                atEpochMs = fetchedAtMs,
                sectionId = SoftwareSectionId.CURSOR,
                outcome = FetchOutcome.SUCCESS,
                endpoint = CursorEndpoints.USAGE_SUMMARY,
                source = "json"
            )
        } catch (expired: SessionExpiredException) {
            val auth = FetchErrorClassifier.attempt(
                expired, SoftwareSectionId.CURSOR, CursorEndpoints.USAGE_SUMMARY, fetchedAtMs
            )
            val grokAuth = auth.copy(sectionId = SoftwareSectionId.GROK_BOT.wireName)
            return ProviderResult.NeedsSignIn(
                message = "Cursor session expired. Sign in again.",
                attempts = listOf(auth, grokAuth)
            )
        } catch (ex: Exception) {
            val attempt = FetchErrorClassifier.attempt(
                ex, SoftwareSectionId.CURSOR, CursorEndpoints.USAGE_SUMMARY, fetchedAtMs
            )
            attempts += attempt
            planError = attempt.shortReason
        }

        try {
            val sand = http.postJson(
                url = CursorEndpoints.SAND_USAGE,
                cookieHost = "cursor.com",
                jsonBody = "{}",
                extraHeaders = mapOf(
                    "Origin" to CursorEndpoints.ORIGIN,
                    "Referer" to CursorEndpoints.SPENDING_PAGE
                )
            )
            sandRaw = sand.body
            grok = CursorUsageParser.parseSandUsage(sand.body, fetchedAtMs)
            attempts += FetchAttempt.of(
                atEpochMs = fetchedAtMs,
                sectionId = SoftwareSectionId.GROK_BOT,
                outcome = FetchOutcome.SUCCESS,
                endpoint = CursorEndpoints.SAND_USAGE,
                source = "json"
            )
        } catch (expired: SessionExpiredException) {
            val attempt = FetchErrorClassifier.attempt(
                expired, SoftwareSectionId.GROK_BOT, CursorEndpoints.SAND_USAGE, fetchedAtMs
            )
            attempts += attempt
            grokError = attempt.shortReason
            if (plan == null) {
                return ProviderResult.NeedsSignIn(
                    message = "Cursor session expired. Sign in again.",
                    attempts = attempts + FetchAttempt.of(
                        atEpochMs = fetchedAtMs,
                        sectionId = SoftwareSectionId.CURSOR,
                        outcome = FetchOutcome.AUTH_EXPIRED,
                        endpoint = CursorEndpoints.USAGE_SUMMARY,
                        message = "Cursor session expired. Sign in again.",
                        source = "json"
                    )
                )
            }
        } catch (ex: Exception) {
            val attempt = FetchErrorClassifier.attempt(
                ex, SoftwareSectionId.GROK_BOT, CursorEndpoints.SAND_USAGE, fetchedAtMs
            )
            attempts += attempt
            grokError = attempt.shortReason
        }

        if (plan == null && grok == null) {
            return ProviderResult.Failure(
                message = planError ?: grokError ?: "Cursor endpoints returned no usage data",
                cause = "json",
                attempts = attempts
            )
        }
        val snapshot = CursorUsageParser.toSnapshot(plan, grok, planError, grokError, source = "json")
        val manual = listOfNotNull(summaryRaw, sandRaw).firstNotNullOfOrNull { raw ->
            runCatching { ManualResetParser.fromUsageRoot(JsonNav.parseObject(raw), fetchedAtMs) }.getOrNull()
                ?.takeIf { it.isPresent }
        }
        return ProviderResult.Success(
            snapshot.copy(
                manualResets = manual,
                diagnosticJson = UsageDump.cursorPayload(summaryRaw, sandRaw)
            ),
            attempts
        )
    }

    fun snapshotFromHtml(pageText: String, fetchedAtMs: Long): ProviderSnapshot {
        val extracted = CursorHtmlExtractor.extract(pageText, fetchedAtMs)
        return CursorUsageParser.toSnapshot(extracted.plan, extracted.grok, source = "html")
    }

    fun htmlAttempts(fetchedAtMs: Long, snapshot: ProviderSnapshot): List<FetchAttempt> {
        val attempts = mutableListOf<FetchAttempt>()
        if (snapshot.meters.any { it.id in MeterIdsCursor }) {
            attempts += FetchAttempt.of(
                fetchedAtMs, SoftwareSectionId.CURSOR, FetchOutcome.SUCCESS,
                endpoint = CursorEndpoints.SPENDING_PAGE, source = "html"
            )
        }
        if (snapshot.meters.any { it.id == "cursor.grok_bot" }) {
            attempts += FetchAttempt.of(
                fetchedAtMs, SoftwareSectionId.GROK_BOT, FetchOutcome.SUCCESS,
                endpoint = CursorEndpoints.SPENDING_PAGE, source = "html"
            )
        }
        return attempts
    }

    fun unsigned(): ProviderSnapshot = ProviderSnapshot(
        id = ProviderId.CURSOR.wireName,
        displayName = ProviderId.CURSOR.displayName,
        status = ProviderStatus.UNSIGNED.name
    )

    private companion object {
        val MeterIdsCursor = setOf("cursor.models", "cursor.other", "cursor.plan_reset")
    }
}
