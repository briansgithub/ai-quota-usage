package com.usagewidget.providers.claude

import com.usagewidget.auth.SecureCookieStore
import com.usagewidget.domain.FetchAttempt
import com.usagewidget.domain.FetchOutcome
import com.usagewidget.domain.ManualResetParser
import com.usagewidget.domain.Percent
import com.usagewidget.domain.ProviderId
import com.usagewidget.domain.ProviderResult
import com.usagewidget.domain.ProviderSnapshot
import com.usagewidget.domain.ProviderStatus
import com.usagewidget.domain.SoftwareSectionId
import com.usagewidget.domain.UsageDump
import com.usagewidget.domain.UsageMeter
import com.usagewidget.net.AuthenticatedHttp
import com.usagewidget.net.FetchErrorClassifier
import com.usagewidget.net.SessionExpiredException
import com.usagewidget.util.HttpBodies
import com.usagewidget.util.JsonNav
import com.usagewidget.util.NyTime
import com.usagewidget.util.num
import com.usagewidget.util.obj
import com.usagewidget.util.str
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * claude.ai web usage (the numbers on `claude.ai/settings/usage`). Undocumented:
 * `GET /api/organizations` lists orgs (`uuid`), then `GET /api/organizations/{uuid}/usage`
 * returns `five_hour` / `seven_day` / `seven_day_opus` / `seven_day_sonnet`, each
 * `{ "utilization": 0–100, "resets_at": ISO-8601 | null }` or null. Auth is the
 * `sessionKey` cookie on claude.ai.
 */
object ClaudeEndpoints {
    const val ORIGIN = "https://claude.ai"
    const val USAGE_PAGE = "https://claude.ai/settings/usage"
    const val LOGIN_PAGE = "https://claude.ai/login?returnTo=%2Fsettings%2Fusage"
    const val ORGANIZATIONS = "https://claude.ai/api/organizations"
    const val SESSION_COOKIE = "sessionKey"
    const val LAST_ACTIVE_ORG_COOKIE = "lastActiveOrg"
    val cookieHosts = listOf("claude.ai")

    /** `cedar_ember=1` adds the full-limit-reset grants the usage page shows. */
    fun usage(orgId: String): String =
        "https://claude.ai/api/organizations/${URLEncoder.encode(orgId, "UTF-8")}/usage?cedar_ember=1"
}

object ClaudeMeterIds {
    const val FIVE_HOUR = "claude.five_hour"
    const val WEEKLY = "claude.weekly"
    const val WEEKLY_OPUS = "claude.weekly_opus"
    const val WEEKLY_SONNET = "claude.weekly_sonnet"
    const val FIVE_HOUR_MS = 5L * 60 * 60 * 1000
    const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
}

object ClaudeUsageParser {
    /**
     * Picks the org to read usage for: the `lastActiveOrg` cookie when it is in the list,
     * else the first org with the `chat` capability, else the first org.
     */
    fun parseOrgId(organizationsRaw: String, preferredOrgId: String? = null): String {
        val root = JsonNav.json.parseToJsonElement(organizationsRaw.trim())
        val arr = root as? JsonArray
            ?: (root as? JsonObject)?.get("organizations") as? JsonArray
            ?: throw IllegalArgumentException("claude.ai organizations response is missing the org list")
        val orgs = arr.mapNotNull { it as? JsonObject }
            .mapNotNull { o -> o.str("uuid", "id")?.takeIf { it.isNotBlank() }?.let { o to it } }
        if (orgs.isEmpty()) throw IllegalArgumentException("claude.ai organizations response is missing an org uuid")
        if (!preferredOrgId.isNullOrBlank()) {
            orgs.firstOrNull { it.second.equals(preferredOrgId, ignoreCase = true) }?.let { return it.second }
        }
        val chat = orgs.firstOrNull { (o, _) ->
            (o["capabilities"] as? JsonArray)?.any {
                (it as? JsonPrimitive)?.contentOrNull.equals("chat", ignoreCase = true)
            } == true
        }
        return (chat ?: orgs.first()).second
    }

    fun parseUsage(usageRaw: String, fetchedAtMs: Long, source: String = "json"): ProviderSnapshot {
        val root = JsonNav.parseObject(usageRaw)
        val meters = mutableListOf<UsageMeter>()
        addWindow(meters, root.obj("five_hour", "fiveHour"), ClaudeMeterIds.FIVE_HOUR, "Session", ClaudeMeterIds.FIVE_HOUR_MS, fetchedAtMs)
        addWindow(meters, root.obj("seven_day", "sevenDay"), ClaudeMeterIds.WEEKLY, "Weekly", ClaudeMeterIds.WEEK_MS, fetchedAtMs)
        addWindow(meters, root.obj("seven_day_opus", "sevenDayOpus"), ClaudeMeterIds.WEEKLY_OPUS, "Weekly · Opus", ClaudeMeterIds.WEEK_MS, fetchedAtMs, optional = true)
        addWindow(meters, root.obj("seven_day_sonnet", "sevenDaySonnet"), ClaudeMeterIds.WEEKLY_SONNET, "Weekly · Sonnet", ClaudeMeterIds.WEEK_MS, fetchedAtMs, optional = true)
        if (meters.none { it.id == ClaudeMeterIds.FIVE_HOUR || it.id == ClaudeMeterIds.WEEKLY }) {
            throw IllegalArgumentException("claude.ai usage is missing five_hour and seven_day")
        }
        return ProviderSnapshot(
            id = ProviderId.CLAUDE.wireName,
            displayName = ProviderId.CLAUDE.displayName,
            status = ProviderStatus.OK.name,
            source = source,
            meters = meters,
            manualResets = ManualResetParser.fromClaudeRoot(root, fetchedAtMs),
            diagnosticJson = UsageDump.claudePayload(usageRaw)
        )
    }

    /**
     * `resets_at` is ISO-8601 with a `+00:00` offset and microseconds. The fraction changes
     * on every request (`18:00:00.951556`, then `18:00:00.497762`) while the window does
     * not, so round to the minute; otherwise every refresh looks like a new window and
     * pacing / quota alerts repeat.
     */
    fun parseReset(raw: String?, fetchedAtMs: Long): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        val ms = runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: NyTime.parseTimestampString(text, fetchedAtMs)
            ?: return null
        return roundToMinute(ms)
    }

    fun roundToMinute(ms: Long): Long = (ms + 30_000L) / 60_000L * 60_000L

    /**
     * Optional per-model weekly meters are shown only once they have usage, so an idle
     * "Opus 0%" bar does not crowd the card.
     */
    private fun addWindow(
        meters: MutableList<UsageMeter>,
        obj: JsonObject?,
        id: String,
        title: String,
        lengthMs: Long,
        fetchedAtMs: Long,
        optional: Boolean = false
    ) {
        if (obj == null) return
        val used = obj.num("utilization", "used_percent", "usedPercent")
        val reset = parseReset(obj.str("resets_at", "resetsAt"), fetchedAtMs)
            ?: obj.num("resets_at", "resetsAt")?.let { NyTime.parseTimestamp(it, fetchedAtMs) }?.let(::roundToMinute)
        if (used == null && reset == null) return
        if (optional && (used == null || used <= 0.0)) return
        val pct = (used?.let { Percent.asWirePercent(it) } ?: 0.0).coerceIn(0.0, 100.0)
        meters += UsageMeter(
            id = id,
            title = title,
            usedPercent = pct,
            remainingPercent = Percent.remainingFromUsed(pct),
            resetsAtEpochMs = reset,
            resetLabel = if (reset == null) "Starts with your next message" else null,
            // An idle window (no reset yet) has not started; leave pace bounds unknown.
            windowStartEpochMs = reset?.let { it - lengthMs },
            windowLengthMs = lengthMs
        )
    }

    /**
     * True when the body is claude.ai's own auth error rather than usage, e.g.
     * `{"type":"error","error":{"type":"permission_error","details":{"error_code":"account_session_invalid"}}}`.
     */
    fun isAuthErrorBody(body: String): Boolean =
        body.contains("account_session_invalid", ignoreCase = true) ||
            body.contains("authentication_error", ignoreCase = true) ||
            body.contains("\"permission_error\"", ignoreCase = true) ||
            HttpBodies.isUnauthorizedPayload(body, 200, "")
}

class ClaudeUsageClient(
    private val http: AuthenticatedHttp,
    private val cookies: SecureCookieStore
) {
    fun fetch(fetchedAtMs: Long = System.currentTimeMillis()): ProviderResult {
        if (!cookies.hasSession(ProviderId.CLAUDE)) {
            return ProviderResult.NeedsSignIn("Sign in to claude.ai to load Claude usage.")
        }
        var endpoint = ClaudeEndpoints.ORGANIZATIONS
        val headers = mapOf(
            "Referer" to ClaudeEndpoints.USAGE_PAGE,
            "Origin" to ClaudeEndpoints.ORIGIN,
            "anthropic-client-platform" to "web_claude_ai"
        )
        return try {
            val orgs = http.getJson(ClaudeEndpoints.ORGANIZATIONS, "claude.ai", headers)
            val orgId = ClaudeUsageParser.parseOrgId(orgs.body, lastActiveOrg())
            endpoint = ClaudeEndpoints.usage(orgId)
            val usage = http.getJson(endpoint, "claude.ai", headers)
            ProviderResult.Success(
                ClaudeUsageParser.parseUsage(usage.body, fetchedAtMs),
                attempts = listOf(
                    FetchAttempt.of(
                        atEpochMs = fetchedAtMs,
                        sectionId = SoftwareSectionId.CLAUDE,
                        outcome = FetchOutcome.SUCCESS,
                        endpoint = endpoint,
                        source = "json"
                    )
                )
            )
        } catch (expired: SessionExpiredException) {
            val attempt = FetchErrorClassifier.attempt(expired, SoftwareSectionId.CLAUDE, endpoint, fetchedAtMs)
            if (expired.code == 401) {
                ProviderResult.NeedsSignIn("Claude session expired. Sign in again.", attempts = listOf(attempt))
            } else {
                // claude.ai answers 403 (often a Cloudflare HTML challenge) to plain HTTP
                // clients even with a live session. Let the WebView fallback decide.
                ProviderResult.Failure(message = attempt.shortReason.ifBlank { "HTTP ${expired.code}" }, cause = "json", attempts = listOf(attempt))
            }
        } catch (ex: Exception) {
            val attempt = FetchErrorClassifier.attempt(ex, SoftwareSectionId.CLAUDE, endpoint, fetchedAtMs)
            ProviderResult.Failure(
                message = attempt.shortReason.ifBlank { ex.message ?: ex.javaClass.simpleName },
                cause = "json",
                attempts = listOf(attempt)
            )
        }
    }

    /** Usage JSON read through the signed-in WebView (Cloudflare-safe fallback). */
    fun snapshotFromWebJson(usageRaw: String, fetchedAtMs: Long): ProviderSnapshot =
        ClaudeUsageParser.parseUsage(usageRaw, fetchedAtMs, source = "webview")

    fun webAttempt(fetchedAtMs: Long): FetchAttempt = FetchAttempt.of(
        atEpochMs = fetchedAtMs,
        sectionId = SoftwareSectionId.CLAUDE,
        outcome = FetchOutcome.SUCCESS,
        endpoint = ClaudeEndpoints.USAGE_PAGE,
        source = "webview"
    )

    private fun lastActiveOrg(): String? {
        val header = cookies.cookieHeader("claude.ai") ?: return null
        return SecureCookieStore.cookieMap(header)[ClaudeEndpoints.LAST_ACTIVE_ORG_COOKIE]
            ?.trim()?.trim('"')
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            ?.takeIf { it.isNotBlank() }
    }
}
