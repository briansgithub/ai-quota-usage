package com.usagewidget.providers.openai

import com.usagewidget.domain.ManualResetParser
import com.usagewidget.domain.ManualResets
import com.usagewidget.domain.Percent
import com.usagewidget.domain.ProviderId
import com.usagewidget.domain.ProviderSnapshot
import com.usagewidget.domain.ProviderStatus
import com.usagewidget.domain.UsageMeter
import com.usagewidget.util.JsonNav
import com.usagewidget.util.NyTime
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
            ?: windows.firstOrNull { it.kind == WindowKind.UNKNOWN && it.slot == "primary" }
        val weekly = windows.firstOrNull { it.kind == WindowKind.WEEKLY }
            ?: windows.firstOrNull { it.kind == WindowKind.UNKNOWN && it.slot == "secondary" }
            ?: windows.firstOrNull { it !== fiveHour && it.kind != WindowKind.FIVE_HOUR }

        val extras = mutableListOf<OpenAiWindow>()
        JsonNav.arr(root["additional_rate_limits"] ?: root["additionalRateLimits"])
            ?.forEach { el ->
                val obj = JsonNav.obj(el) ?: return@forEach
                val name = obj.str("title", "limit_name", "limitName", "metered_feature") ?: "Extra"
                val nested = obj.obj("rate_limit", "rateLimit") ?: obj
                windowFrom(nested.obj("primary_window", "primaryWindow"), fetchedAtMs, name)
                    ?.let { extras += it.copy(title = "$name · ${it.title}") }
                windowFrom(nested.obj("secondary_window", "secondaryWindow"), fetchedAtMs, name)
                    ?.let { extras += it.copy(title = "$name · ${it.title}") }
            }

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
            observedJson = manual?.observedJson
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
        val five = usage.fiveHour?.toMeter("openai.five_hour")
            ?: UsageMeter(id = "openai.five_hour", title = "5-hour", resetLabel = "No 5-hour window in response")
        val weekly = usage.weekly?.toMeter("openai.weekly")
            ?: UsageMeter(id = "openai.weekly", title = "Weekly", resetLabel = "No weekly window in response")
        val visibleResets = usage.manualResets?.takeIf { it.isPresent }
        return ProviderSnapshot(
            id = ProviderId.OPENAI.wireName,
            displayName = "Codex",
            status = ProviderStatus.OK.name,
            error = null,
            source = source,
            planLabel = usage.planLabel,
            meters = listOf(five, weekly),
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
            ?: obj.num("reset_after_seconds", "resetAfterSeconds")?.let { fetchedAtMs + (it * 1000).toLong() }
        if (used == null && resetMs == null) return null
        val kind = classifyWindow(seconds, fallbackSlot)
        val title = when (kind) {
            WindowKind.FIVE_HOUR -> "5-hour"
            WindowKind.WEEKLY -> "Weekly"
            WindowKind.UNKNOWN -> stripLimitWord(
                titleHint ?: if (fallbackSlot == "secondary") "Weekly" else "5-hour"
            )
        }
        return OpenAiWindow(
            title = title,
            kind = kind,
            slot = fallbackSlot,
            usedPercent = used?.let { normalizePercent(it) },
            resetsAtEpochMs = resetMs
        )
    }

    internal fun classifyWindow(seconds: Long?, slot: String?): WindowKind {
        if (seconds != null) {
            when {
                seconds in 10_800L..25_200L -> return WindowKind.FIVE_HOUR
                seconds in 500_000L..800_000L -> return WindowKind.WEEKLY
                seconds >= 500_000L -> return WindowKind.WEEKLY
            }
        }
        return when (slot) {
            "secondary" -> WindowKind.WEEKLY
            "primary" -> WindowKind.FIVE_HOUR
            else -> WindowKind.UNKNOWN
        }
    }

    private fun stripLimitWord(title: String): String =
        title.replace(Regex("""(?i)\s*limits?\b"""), "").trim().ifBlank { title }

    private fun OpenAiWindow.toMeter(id: String): UsageMeter = UsageMeter(
        id = id,
        title = title,
        subtitle = "Shared across Codex, Work, and agents",
        usedPercent = usedPercent,
        remainingPercent = Percent.remainingFromUsed(usedPercent),
        resetsAtEpochMs = resetsAtEpochMs
    )

    private fun normalizePercent(value: Double): Double {
        if (!value.isFinite()) return 0.0
        return if (value > 0.0 && value < 1.0) value * 100.0 else value
    }

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

enum class WindowKind { FIVE_HOUR, WEEKLY, UNKNOWN }

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
    val resetsAtEpochMs: Long?
)

data class OpenAiUsage(
    val planLabel: String?,
    val fiveHour: OpenAiWindow?,
    val weekly: OpenAiWindow?,
    val extras: List<OpenAiWindow>,
    val manualResets: ManualResets? = null,
    val observedJson: String? = null
)
