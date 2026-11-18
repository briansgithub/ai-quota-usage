package com.usagewidget.providers.openai

import com.usagewidget.domain.ManualResetParser
import com.usagewidget.domain.ManualResets
import com.usagewidget.domain.PaceWindows
import com.usagewidget.domain.PacingPolicy
import com.usagewidget.domain.Percent
import com.usagewidget.domain.ProviderId
import com.usagewidget.domain.ProviderSnapshot
import com.usagewidget.domain.ProviderStatus
import com.usagewidget.domain.UsageMeter
import com.usagewidget.util.JsonNav
import com.usagewidget.util.NyTime
import com.usagewidget.util.bool
import com.usagewidget.util.long
import com.usagewidget.util.num
import com.usagewidget.util.obj
import com.usagewidget.util.str
import kotlinx.serialization.json.JsonObject

object OpenAiUsageParser {
    const val FIVE_HOUR_SECONDS = 18_000L
    const val WEEKLY_SECONDS = 604_800L

    fun parseSession(raw: String): OpenAiSession {
        val root = JsonNav.parseObject(raw)
        val access = root.str("accessToken", "access_token")
            ?: root.obj("user")?.str("accessToken")
            ?: throw IllegalArgumentException("ChatGPT session JSON is missing accessToken")
        val accountId = root.str("accountId", "account_id")
            ?: root.obj("user")?.str("accountId", "account_id")
            ?: accountIdFromJwt(access)
        val plan = root.str("planType", "plan_type")
            ?: root.obj("user")?.str("planType", "plan_type")
        return OpenAiSession(accessToken = access, accountId = accountId, planLabel = plan)
    }

    fun parseWhamUsage(
        raw: String,
        fetchedAtMs: Long = System.currentTimeMillis()
    ): OpenAiUsage {
        val root = JsonNav.parseObject(raw)
        val rate = root.obj("rate_limit", "rateLimit") ?: root
        val windows = mutableListOf<OpenAiWindow>()
        windowFrom(rate.obj("primary_window", "primaryWindow"), fetchedAtMs, fallbackSlot = "primary")
            ?.let { windows += it }
        windowFrom(rate.obj("secondary_window", "secondaryWindow"), fetchedAtMs, fallbackSlot = "secondary")
            ?.let { windows += it }

        if (windows.isEmpty()) {
            throw IllegalArgumentException("wham/usage is missing rate_limit windows")
        }

        val fiveHour = windows.firstOrNull { it.kind == WindowKind.FIVE_HOUR }
        val weekly = windows.firstOrNull { it.kind == WindowKind.WEEKLY }
        val thirtyDay = windows.firstOrNull { it.kind == WindowKind.THIRTY_DAY }
        val assigned = setOfNotNull(fiveHour, weekly, thirtyDay)
        val leftover = windows.filter { it !in assigned }

        val extras = leftover.toMutableList()
        JsonNav.arr(root["additional_rate_limits"] ?: root["additionalRateLimits"])
            ?.forEach { el ->
                val obj = JsonNav.obj(el) ?: return@forEach
                val name = obj.str("title", "limit_name", "limitName", "metered_feature", "id") ?: "Extra"
                val nested = obj.obj("rate_limit", "rateLimit") ?: obj
                extras += windowsFromLimit(nested, fetchedAtMs, name)
            }

        val codeReview = root.obj("code_review_rate_limit", "codeReviewRateLimit")?.let { review ->
            val nested = review.obj("rate_limit", "rateLimit") ?: review
            windowFrom(
                nested.obj("primary_window", "primaryWindow") ?: nested,
                fetchedAtMs,
                titleHint = "Code review",
                fallbackSlot = "code_review"
            )
        }

        val creditsLabel = creditsLine(root.obj("credits"))
        val manual = ManualResetParser.fromUsageRoot(root, fetchedAtMs)
            ?: ManualResets(availableCount = 0, observedJson = """{"rate_limit_reset_credits":null}""")
        return OpenAiUsage(
            planLabel = root.str("plan_type", "planType")?.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase() else it.toString()
            },
            fiveHour = fiveHour,
            weekly = weekly,
            extras = extras,
            manualResets = manual,
            observedJson = manual?.observedJson,
            thirtyDay = thirtyDay,
            codeReview = codeReview,
            creditsLabel = creditsLabel
        )
    }

    fun mergeResetDetails(
        usage: OpenAiUsage,
        detailsRaw: String?,
        fetchedAtMs: Long
    ): OpenAiUsage {
        val merged = ManualResetParser.mergeDetails(usage.manualResets, detailsRaw, fetchedAtMs)
        return usage.copy(
            manualResets = merged,
            observedJson = merged?.observedJson ?: usage.observedJson
        )
    }

    fun toSnapshot(usage: OpenAiUsage, source: String = "json"): ProviderSnapshot {
        val meters = mutableListOf<UsageMeter>()
        usage.fiveHour?.let { meters += it.toMeter("openai.five_hour") }
        usage.weekly?.let { meters += it.toMeter("openai.weekly") }
        usage.thirtyDay?.let { meters += it.toMeter("openai.thirty_day") }
        usage.codeReview?.let { meters += it.toMeter("openai.code_review") }
        usage.extras.forEachIndexed { index, window ->
            meters += window.toMeter(extraMeterId(window, index))
        }
        usage.creditsLabel?.let { label ->
            meters += UsageMeter(id = "openai.credits", title = "Credits", resetLabel = label)
        }
        val visibleResets = usage.manualResets?.takeIf { it.isPresent }
        return ProviderSnapshot(
            id = ProviderId.OPENAI.wireName,
            displayName = "Codex",
            status = ProviderStatus.OK.name,
            error = null,
            source = source,
            planLabel = usage.planLabel,
            meters = meters,
            manualResets = visibleResets,
            diagnosticJson = usage.observedJson ?: visibleResets?.observedJson
        )
    }

    fun accountIdFromJwt(token: String): String? {
        val parts = token.split('.')
        if (parts.size < 2) return null
        val payload = decodeBase64Url(parts[1]) ?: return null
        val obj = runCatching { JsonNav.parseObject(payload) }.getOrNull() ?: return null
        obj.str("account_id", "accountId")?.let { return it }
        val auth = obj.obj("https://api.openai.com/auth", "https://api.openai.com/profile")
        return auth?.str("chatgpt_account_id", "account_id", "chatgptAccountId")
    }

    private fun windowFrom(
        obj: JsonObject?,
        fetchedAtMs: Long,
        titleHint: String? = null,
        fallbackSlot: String? = null
    ): OpenAiWindow? {
        if (obj == null) return null
        val used = obj.num("used_percent", "usedPercent")
        val seconds = obj.long("limit_window_seconds", "limitWindowSeconds")
            ?: obj.num("limit_window_seconds", "limitWindowSeconds")?.toLong()
        val resetMs = obj.long("reset_at", "resetAt")?.let { NyTime.parseTimestamp(it, fetchedAtMs) }
            ?: obj.num("reset_at", "resetAt")?.let { NyTime.parseTimestamp(it, fetchedAtMs) }
            ?: obj.num("reset_after_seconds", "resetAfterSeconds")?.let { fetchedAtMs + (it * 1000).toLong() }
        if (used == null && resetMs == null) return null
        val kind = classifyWindow(seconds, fallbackSlot)
        val title = titleFor(kind, seconds, titleHint, fallbackSlot)
        return OpenAiWindow(
            title = title,
            kind = kind,
            slot = fallbackSlot,
            usedPercent = used?.let { Percent.asWirePercent(it) },
            resetsAtEpochMs = resetMs,
            limitWindowSeconds = seconds
        )
    }

    internal fun classifyWindow(seconds: Long?, slot: String?): WindowKind {
        if (seconds != null) {
            return when {
                seconds in 10_800L..25_200L -> WindowKind.FIVE_HOUR
                seconds in 500_000L..800_000L -> WindowKind.WEEKLY
                seconds in 2_000_000L..4_000_000L -> WindowKind.THIRTY_DAY
                seconds >= 500_000L -> WindowKind.WEEKLY
                else -> WindowKind.UNKNOWN
            }
        }
        return when (slot) {
            "secondary" -> WindowKind.WEEKLY
            "primary" -> WindowKind.FIVE_HOUR
            else -> WindowKind.UNKNOWN
        }
    }

    internal fun titleFor(
        kind: WindowKind,
        seconds: Long?,
        titleHint: String?,
        fallbackSlot: String?
    ): String {
        if (!titleHint.isNullOrBlank() && fallbackSlot != "primary" && fallbackSlot != "secondary") {
            return stripLimitWord(titleHint)
        }
        return when (kind) {
            WindowKind.FIVE_HOUR -> "5-hour"
            WindowKind.WEEKLY -> "Weekly"
            WindowKind.THIRTY_DAY -> "30-day"
            WindowKind.UNKNOWN -> stripLimitWord(
                titleHint ?: durationLabel(seconds) ?: if (fallbackSlot == "secondary") "Weekly" else "5-hour"
            )
        }
    }

    private fun stripLimitWord(title: String): String =
        title.replace(Regex("""(?i)\s*limits?\b"""), "").trim().ifBlank { title }

    private fun OpenAiWindow.toMeter(id: String): UsageMeter {
        val length = when {
            limitWindowSeconds != null && limitWindowSeconds > 0L -> limitWindowSeconds * 1000L
            kind == WindowKind.FIVE_HOUR -> PacingPolicy.WINDOW_MS
            kind == WindowKind.WEEKLY -> PacingPolicy.GROK_PERIOD_MS
            kind == WindowKind.THIRTY_DAY -> 30L * 24 * 60 * 60 * 1000
            else -> PaceWindows.knownLengthMs(id)
        }
        return UsageMeter(
            id = id,
            title = title,
            subtitle = "Shared across Codex, Work, and agents",
            usedPercent = usedPercent,
            remainingPercent = Percent.remainingFromUsed(usedPercent),
            resetsAtEpochMs = resetsAtEpochMs,
            windowStartEpochMs = resetsAtEpochMs?.let { reset -> length?.let { reset - it } },
            windowLengthMs = length
        )
    }

    private fun windowsFromLimit(
        nested: JsonObject,
        fetchedAtMs: Long,
        name: String
    ): List<OpenAiWindow> {
        val found = mutableListOf<OpenAiWindow>()
        windowFrom(nested.obj("primary_window", "primaryWindow"), fetchedAtMs, fallbackSlot = "primary")
            ?.let { found += it.copy(title = "$name · ${it.title}") }
        windowFrom(nested.obj("secondary_window", "secondaryWindow"), fetchedAtMs, fallbackSlot = "secondary")
            ?.let { found += it.copy(title = "$name · ${it.title}") }
        if (found.isEmpty()) {
            windowFrom(nested, fetchedAtMs, name, name)?.let { found += it }
        }
        return found
    }

    private fun extraMeterId(window: OpenAiWindow, index: Int): String {
        val slug = window.title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "extra_$index" }
        return if (index == 0) "openai.$slug" else "openai.${slug}_$index"
    }

    private fun durationLabel(seconds: Long?): String? {
        if (seconds == null || seconds <= 0L) return null
        val hours = seconds / 3600.0
        return when {
            hours in 4.5..5.5 -> "5-hour"
            hours in 23.0..25.0 -> "Daily"
            hours in 167.0..169.0 -> "Weekly"
            hours in 720.0..744.0 -> "30-day"
            hours >= 24 -> "${(hours / 24).toInt()}-day"
            else -> "${hours.toInt()}-hour"
        }
    }

    private fun creditsLine(credits: JsonObject?): String? {
        if (credits == null) return null
        val hasCredits = credits.bool("has_credits", "hasCredits")
        val remaining = creditNumber(credits, "remaining", "balance", "credits_remaining", "creditsRemaining")
        val total = creditNumber(credits, "total", "limit", "credits_total", "creditsTotal")
        val used = creditNumber(credits, "used", "credits_used", "creditsUsed")
        if (hasCredits != true && (remaining == null || remaining <= 0.0) && used == null) return null
        return when {
            remaining != null && total != null ->
                "${remaining.toInt()} of ${total.toInt()} credits remaining"
            remaining != null ->
                "${remaining.toInt()} credits remaining"
            used != null && total != null ->
                "${used.toInt()} of ${total.toInt()} credits used"
            hasCredits == true -> "Credits available"
            else -> null
        }
    }

    private fun creditNumber(obj: JsonObject, vararg names: String): Double? =
        obj.num(*names) ?: obj.str(*names)?.toDoubleOrNull()

    private fun decodeBase64Url(value: String): String? {
        return try {
            val padded = value + "=".repeat((4 - value.length % 4) % 4)
            val bytes = java.util.Base64.getUrlDecoder().decode(padded)
            String(bytes, Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

enum class WindowKind { FIVE_HOUR, WEEKLY, THIRTY_DAY, UNKNOWN }

data class OpenAiSession(
    val accessToken: String,
    val accountId: String?,
    val planLabel: String?
)

data class OpenAiWindow(
    val title: String,
    val kind: WindowKind,
    val slot: String?,
    val usedPercent: Double?,
    val resetsAtEpochMs: Long?,
    val limitWindowSeconds: Long? = null
)

data class OpenAiUsage(
    val planLabel: String?,
    val fiveHour: OpenAiWindow?,
    val weekly: OpenAiWindow?,
    val extras: List<OpenAiWindow>,
    val manualResets: ManualResets? = null,
    val observedJson: String? = null,
    val thirtyDay: OpenAiWindow? = null,
    val codeReview: OpenAiWindow? = null,
    val creditsLabel: String? = null
)
