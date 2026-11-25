package com.usagewidget.domain

import com.usagewidget.util.NyTime
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

object QuotaThresholds {
    val DEFAULT: List<Int> = listOf(90, 80, 70, 60, 50, 40, 30, 25, 20, 15, 10, 5, 0)
    const val JUMP_RESET = 15.0
    const val DEFAULT_CSV = "90,80,70,60,50,40,30,25,20,15,10,5,0"

    fun normalize(raw: List<Int>): List<Int> =
        raw.filter { it in 0..100 }.distinct().sortedDescending().ifEmpty { DEFAULT }

    fun parseCsv(csv: String?): List<Int> {
        if (csv.isNullOrBlank()) return DEFAULT
        val parsed = csv.split(',', ';', ' ', '\n', '\t')
            .mapNotNull { token -> token.trim().removeSuffix("%").trim().toIntOrNull() }
        return normalize(parsed)
    }

    fun toCsv(list: List<Int> = DEFAULT): String = normalize(list).joinToString(",")
}

enum class QuotaRuleId(
    val wire: String,
    val meterId: String,
    val section: SoftwareSectionId,
    val serviceLabel: String,
    val windowLabel: String,
    val defaultEnabled: Boolean,
    val notificationId: Int
) {
    GROK_WEEKLY(
        wire = "grok.weekly",
        meterId = "cursor.grok_bot",
        section = SoftwareSectionId.GROK_BOT,
        serviceLabel = "Grok Bot",
        windowLabel = "weekly",
        defaultEnabled = true,
        notificationId = 6401
    ),
    CURSOR_MODELS(
        wire = "cursor.models",
        meterId = "cursor.models",
        section = SoftwareSectionId.CURSOR,
        serviceLabel = "Cursor",
        windowLabel = "models",
        defaultEnabled = true,
        notificationId = 6402
    ),
    CODEX_FIVE_HOUR(
        wire = "codex.five_hour",
        meterId = "openai.five_hour",
        section = SoftwareSectionId.CODEX,
        serviceLabel = "Codex",
        windowLabel = "5-hour",
        defaultEnabled = false,
        notificationId = 6403
    ),
    CODEX_WEEKLY(
        wire = "codex.weekly",
        meterId = "openai.weekly",
        section = SoftwareSectionId.CODEX,
        serviceLabel = "Codex",
        windowLabel = "weekly",
        defaultEnabled = false,
        notificationId = 6404
    ),
    CLAUDE_FIVE_HOUR(
        wire = "claude.five_hour",
        meterId = "claude.five_hour",
        section = SoftwareSectionId.CLAUDE,
        serviceLabel = "Claude",
        windowLabel = "session",
        defaultEnabled = true,
        notificationId = 6405
    ),
    CLAUDE_WEEKLY(
        wire = "claude.weekly",
        meterId = "claude.weekly",
        section = SoftwareSectionId.CLAUDE,
        serviceLabel = "Claude",
        windowLabel = "weekly",
        defaultEnabled = true,
        notificationId = 6406
    ),
    GEMINI_FIVE_HOUR("gemini.gemini-5h", "gemini.gemini-5h", SoftwareSectionId.GEMINI, "Gemini", "5-hour", false, 6407),
    GEMINI_WEEKLY("gemini.gemini-weekly", "gemini.gemini-weekly", SoftwareSectionId.GEMINI, "Gemini", "weekly", false, 6408),
    GEMINI_THIRD_PARTY_FIVE_HOUR("gemini.3p-5h", "gemini.3p-5h", SoftwareSectionId.GEMINI, "Gemini", "third-party 5-hour", false, 6409),
    GEMINI_THIRD_PARTY_WEEKLY("gemini.3p-weekly", "gemini.3p-weekly", SoftwareSectionId.GEMINI, "Gemini", "third-party weekly", false, 6410),
    GEMINI_ARGON_WEEKLY("gemini.argon-weekly", "gemini.argon-weekly", SoftwareSectionId.GEMINI, "Gemini", "Argon weekly", false, 6411);

    companion object {
        val DEFAULT_ENABLED: Set<String> = entries.filter { it.defaultEnabled }.map { it.wire }.toSet()

        fun fromWire(value: String): QuotaRuleId? =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
    }
}

data class QuotaThresholdPreferences(
    val masterEnabled: Boolean = true,
    val thresholds: List<Int> = QuotaThresholds.DEFAULT,
    val enabledRules: Set<String> = QuotaRuleId.DEFAULT_ENABLED
) {
    val thresholdsCsv: String get() = QuotaThresholds.toCsv(thresholds)

    fun isRuleEnabled(id: QuotaRuleId): Boolean = masterEnabled && id.wire in enabledRules
}

@Serializable
data class QuotaThresholdState(
    val windowKey: String = "",
    val remaining: Double? = null,
    val fired: List<Int> = emptyList()
)

data class QuotaThresholdPlan(
    val alerts: List<AlertToPost>,
    val states: Map<String, QuotaThresholdState>
)

/**
 * Remaining-quota threshold crossings. Fires once per threshold per quota window,
 * seeds on first observation without notifying, and ignores stale / cached last-good.
 * Quiet hours still seed; unposted crossings stay unfired so they can notify later.
 */
object QuotaThresholdEngine {
    const val TEST_TITLE = "Quota used"
    const val TEST_BODY = "Grok Bot: 80% weekly quota used (resets Mon 3:00 PM ET)"
    const val CHANNEL_ID = "quota_thresholds"

    fun testAlert(): AlertToPost = AlertToPost(
        channel = AlertChannel.QUOTA,
        title = TEST_TITLE,
        body = TEST_BODY,
        dedupeKey = "quota:test",
        notificationId = 6499,
        section = SoftwareSectionId.GROK_BOT
    )

    /** One sample per service, so "Send test notification" previews every logo. */
    fun testAlerts(): List<AlertToPost> = listOf(
        testAlert(),
        AlertToPost(AlertChannel.QUOTA, TEST_TITLE, "Cursor: 80% models quota used (test)", "quota:test:cursor", 6496, SoftwareSectionId.CURSOR),
        AlertToPost(AlertChannel.QUOTA, TEST_TITLE, "Codex: 80% weekly quota used (test)", "quota:test:codex", 6497, SoftwareSectionId.CODEX),
        AlertToPost(AlertChannel.QUOTA, TEST_TITLE, "Claude: 80% session quota used (test)", "quota:test:claude", 6498, SoftwareSectionId.CLAUDE)
    )

    fun evaluate(
        snapshot: UsageSnapshot,
        nowMs: Long,
        prefs: QuotaThresholdPreferences,
        prior: Map<String, QuotaThresholdState>,
        quiet: Boolean
    ): QuotaThresholdPlan {
        if (!prefs.masterEnabled) return QuotaThresholdPlan(emptyList(), prior)
        val thresholds = QuotaThresholds.normalize(prefs.thresholds)
        val sections = UsageSections.from(snapshot, nowMs).associateBy { it.id }
        val alerts = mutableListOf<AlertToPost>()
        val next = prior.toMutableMap()

        for (rule in QuotaRuleId.entries) {
            if (rule.wire !in prefs.enabledRules) continue
            val section = sections[rule.section] ?: continue
            if (!isLive(section)) continue
            val meter = section.meters.find { it.id == rule.meterId } ?: continue
            val remaining = meter.remainingAmount() ?: continue
            val windowKey = meter.quotaWindowKey()
            val prev = prior[rule.wire]
            val first = prev == null || prev.remaining == null
            val windowChanged = prev != null &&
                prev.windowKey.isNotEmpty() &&
                prev.windowKey != windowKey
            val previousRemaining = prev?.remaining
            val jumpedUp = previousRemaining != null &&
                remaining - previousRemaining >= QuotaThresholds.JUMP_RESET

            if (first || windowChanged || jumpedUp) {
                next[rule.wire] = seed(windowKey, remaining, thresholds)
                continue
            }

            val lastRemaining = previousRemaining
            if (remaining > lastRemaining) {
                next[rule.wire] = QuotaThresholdState(
                    windowKey = windowKey,
                    remaining = remaining,
                    fired = prev.fired.filter { remaining <= it }
                )
                continue
            }

            val crossed = thresholds.filter { t ->
                lastRemaining > t && remaining <= t && t !in prev.fired
            }
            if (crossed.isEmpty()) {
                next[rule.wire] = prev.copy(windowKey = windowKey, remaining = remaining)
                continue
            }
            if (quiet) continue
            val lowest = crossed.min()
            next[rule.wire] = QuotaThresholdState(
                windowKey = windowKey,
                remaining = remaining,
                fired = (prev.fired + crossed).distinct().sortedDescending()
            )
            alerts += alertFor(rule, lowest, meter.resetsAtEpochMs, windowKey)
        }
        return QuotaThresholdPlan(alerts, next)
    }

    fun bodyFor(rule: QuotaRuleId, threshold: Int, resetsAtEpochMs: Long?): String {
        val core = "${rule.serviceLabel}: ${100 - threshold}% ${rule.windowLabel} quota used"
        val reset = resetsAtEpochMs?.let { NyTime.formatQuotaReset(it) } ?: return core
        return "$core (resets $reset)"
    }

    private fun isLive(section: SoftwareSection): Boolean {
        if (section.stale.isStale) return false
        if (section.status != ProviderStatus.OK) return false
        if (section.health.outcomeEnum != FetchOutcome.SUCCESS) return false
        return true
    }

    private fun seed(windowKey: String, remaining: Double, thresholds: List<Int>): QuotaThresholdState =
        QuotaThresholdState(
            windowKey = windowKey,
            remaining = remaining,
            fired = thresholds.filter { remaining <= it }
        )

    private fun alertFor(
        rule: QuotaRuleId,
        threshold: Int,
        resetsAtEpochMs: Long?,
        windowKey: String
    ): AlertToPost = AlertToPost(
        channel = AlertChannel.QUOTA,
        title = TEST_TITLE,
        body = bodyFor(rule, threshold, resetsAtEpochMs),
        dedupeKey = "quota:${rule.wire}:$windowKey:$threshold",
        notificationId = rule.notificationId,
        section = rule.section
    )
}
