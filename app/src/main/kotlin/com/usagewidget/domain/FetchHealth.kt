package com.usagewidget.domain

import kotlinx.serialization.Serializable

enum class FetchOutcome {
    NEVER,
    SUCCESS,
    NETWORK,
    TIMEOUT,
    HTTP,
    AUTH_EXPIRED,
    PARSE,
    MISSING_FIELDS,
    UNKNOWN;

    val isFailure: Boolean
        get() = this != SUCCESS && this != NEVER

    fun shortReason(httpStatus: Int? = null): String = when (this) {
        NEVER, SUCCESS -> ""
        NETWORK -> "Network error"
        TIMEOUT -> "Timeout"
        AUTH_EXPIRED -> "Sign-in expired"
        PARSE, MISSING_FIELDS -> "Data format changed"
        HTTP -> if (httpStatus != null) "HTTP $httpStatus" else "HTTP error"
        UNKNOWN -> "Refresh failed"
    }

    fun severity(): StaleSeverity = when (this) {
        AUTH_EXPIRED, HTTP, PARSE, MISSING_FIELDS, UNKNOWN -> StaleSeverity.RED
        NETWORK, TIMEOUT -> StaleSeverity.AMBER
        SUCCESS, NEVER -> StaleSeverity.NONE
    }
}

enum class StaleSeverity { NONE, AMBER, RED }

@Serializable
data class FetchAttempt(
    val atEpochMs: Long,
    val sectionId: String,
    val outcome: String,
    val shortReason: String,
    val message: String? = null,
    val httpStatus: Int? = null,
    val endpoint: String? = null,
    val source: String? = null
) {
    val outcomeEnum: FetchOutcome
        get() = runCatching { FetchOutcome.valueOf(outcome) }.getOrDefault(FetchOutcome.UNKNOWN)

    companion object {
        fun of(
            atEpochMs: Long,
            sectionId: SoftwareSectionId,
            outcome: FetchOutcome,
            httpStatus: Int? = null,
            message: String? = null,
            endpoint: String? = null,
            source: String? = null
        ): FetchAttempt = FetchAttempt(
            atEpochMs = atEpochMs,
            sectionId = sectionId.wireName,
            outcome = outcome.name,
            shortReason = outcome.shortReason(httpStatus),
            message = DiagnosticSanitizer.message(message),
            httpStatus = httpStatus,
            endpoint = DiagnosticSanitizer.endpoint(endpoint),
            source = source
        )
    }
}

@Serializable
data class SectionHealth(
    val sectionId: String,
    val lastAttemptEpochMs: Long = 0,
    val lastSuccessEpochMs: Long = 0,
    val lastOutcome: String = FetchOutcome.NEVER.name,
    val shortReason: String? = null,
    val message: String? = null,
    val httpStatus: Int? = null,
    val endpoint: String? = null,
    val source: String? = null,
    val lastGoodMeters: List<UsageMeter> = emptyList(),
    val lastGoodPlanLabel: String? = null,
    val lastGoodManualResets: ManualResets? = null,
    val lastGoodDiagnosticJson: String? = null,
    val signedIn: Boolean = false
) {
    val outcomeEnum: FetchOutcome
        get() = runCatching { FetchOutcome.valueOf(lastOutcome) }.getOrDefault(FetchOutcome.NEVER)

    val hasLastGood: Boolean
        get() = lastGoodMeters.any { it.usedPercent != null || it.resetsAtEpochMs != null }

    fun staleView(nowMs: Long): StaleView = StalePolicy.evaluate(this, nowMs)
}

data class StaleView(
    val isStale: Boolean,
    val severity: StaleSeverity,
    val lastUpdatedLine: String?,
    val shortReason: String?,
    val shouldNotify: Boolean
) {
    companion object {
        val fresh = StaleView(
            isStale = false,
            severity = StaleSeverity.NONE,
            lastUpdatedLine = null,
            shortReason = null,
            shouldNotify = false
        )
    }
}

object StalePolicy {
    const val REFRESH_INTERVAL_MS = RefreshSchedule.INTERVAL_MS
    const val STALE_AGE_MS = 2 * REFRESH_INTERVAL_MS
    const val NOTIFY_AFTER_MS = 2L * 60 * 60 * 1000
    const val ATTEMPT_LOG_LIMIT = 24

    fun evaluate(health: SectionHealth, nowMs: Long): StaleView {
        val hasLastGood = health.hasLastGood
        val outcome = health.outcomeEnum
        val lastSuccess = health.lastSuccessEpochMs
        val failStale = hasLastGood && outcome.isFailure
        val ageStale = hasLastGood &&
            health.signedIn &&
            lastSuccess > 0 &&
            nowMs - lastSuccess >= STALE_AGE_MS
        val isStale = failStale || ageStale
        if (!isStale) return StaleView.fresh.copy(
            lastUpdatedLine = lastUpdatedLine(lastSuccess, nowMs).takeIf { lastSuccess > 0 }
        )
        val reason = when {
            outcome.isFailure -> health.shortReason?.takeIf { it.isNotBlank() } ?: outcome.shortReason(health.httpStatus)
            else -> "Refresh overdue"
        }
        val severity = when {
            outcome.isFailure -> outcome.severity().let {
                if (it == StaleSeverity.NONE) StaleSeverity.AMBER else it
            }
            else -> StaleSeverity.AMBER
        }
        val failingSince = when {
            lastSuccess > 0 -> lastSuccess
            health.lastAttemptEpochMs > 0 -> health.lastAttemptEpochMs
            else -> nowMs
        }
        return StaleView(
            isStale = true,
            severity = severity,
            lastUpdatedLine = lastUpdatedLine(lastSuccess, nowMs),
            shortReason = reason,
            shouldNotify = health.signedIn && nowMs - failingSince >= NOTIFY_AFTER_MS
        )
    }

    fun lastUpdatedLine(lastSuccessEpochMs: Long, nowMs: Long): String {
        if (lastSuccessEpochMs <= 0L) return "Last updated —"
        return "Last updated ${NyTimeAgo.format(lastSuccessEpochMs, nowMs)}"
    }
}

object NyTimeAgo {
    fun format(epochMs: Long, nowMs: Long): String {
        val delta = (nowMs - epochMs).coerceAtLeast(0L)
        if (delta < 45_000L) return "just now"
        val minutes = delta / 60_000L
        if (minutes < 60L) return "${minutes}m ago"
        val hours = minutes / 60L
        if (hours < 48L) return "${hours}h ago"
        val days = hours / 24L
        return "${days}d ago"
    }
}

object MeterIds {
    val CURSOR = setOf("cursor.models", "cursor.other", "cursor.on_demand", "cursor.plan_reset")
    const val GROK = "cursor.grok_bot"
    const val GROK_ON_DEMAND = "cursor.grok_on_demand"
    val CODEX = setOf("openai.five_hour", "openai.weekly", "openai.thirty_day", "openai.code_review")

    fun cursorMeters(meters: List<UsageMeter>): List<UsageMeter> =
        listOfNotNull(
            meters.find { it.id == "cursor.models" },
            meters.find { it.id == "cursor.other" },
            meters.find { it.id == "cursor.on_demand" },
            meters.find { it.id == "cursor.plan_reset" }
        )

    fun grokMeters(meters: List<UsageMeter>): List<UsageMeter> =
        listOfNotNull(
            meters.find { it.id == GROK },
            meters.find { it.id == GROK_ON_DEMAND }
        )

    fun codexMeters(meters: List<UsageMeter>): List<UsageMeter> =
        meters.filter { it.id.startsWith("openai.") }

    fun claudeMeters(meters: List<UsageMeter>): List<UsageMeter> =
        meters.filter { it.id.startsWith("claude.") }

    fun forSection(sectionId: SoftwareSectionId, meters: List<UsageMeter>): List<UsageMeter> =
        when (sectionId) {
            SoftwareSectionId.CURSOR -> cursorMeters(meters)
            SoftwareSectionId.GROK_BOT -> grokMeters(meters)
            SoftwareSectionId.CODEX -> codexMeters(meters)
            SoftwareSectionId.CLAUDE -> claudeMeters(meters)
            SoftwareSectionId.GEMINI -> meters.filter { it.id.startsWith("gemini.") }
        }
}

object HealthSynthesis {
    fun hydrate(snapshot: UsageSnapshot): UsageSnapshot {
        if (snapshot.sectionHealth.isNotEmpty()) return snapshot
        val cursor = snapshot.provider(ProviderId.CURSOR)
        val openai = snapshot.provider(ProviderId.OPENAI)
        val claude = snapshot.provider(ProviderId.CLAUDE)
        val at = snapshot.fetchedAtEpochMs
        val health = listOf(
            synthesized(SoftwareSectionId.CURSOR, cursor, MeterIds.cursorMeters(cursor?.meters.orEmpty()), at),
            synthesized(SoftwareSectionId.GROK_BOT, cursor, MeterIds.grokMeters(cursor?.meters.orEmpty()), at),
            synthesized(SoftwareSectionId.CODEX, openai, MeterIds.codexMeters(openai?.meters.orEmpty()), at),
            synthesized(SoftwareSectionId.CLAUDE, claude, MeterIds.claudeMeters(claude?.meters.orEmpty()), at),
            synthesized(SoftwareSectionId.GEMINI, snapshot.provider(ProviderId.GEMINI), snapshot.provider(ProviderId.GEMINI)?.meters.orEmpty(), at)
        )
        return snapshot.copy(sectionHealth = health)
    }

    private fun synthesized(
        section: SoftwareSectionId,
        provider: ProviderSnapshot?,
        meters: List<UsageMeter>,
        fetchedAt: Long
    ): SectionHealth {
        val status = provider?.providerStatus ?: ProviderStatus.UNSIGNED
        val signedIn = status != ProviderStatus.UNSIGNED
        val hasGood = meters.any { it.usedPercent != null || it.resetsAtEpochMs != null }
        val outcome = when (status) {
            ProviderStatus.OK -> FetchOutcome.SUCCESS
            ProviderStatus.NEEDS_SIGN_IN -> FetchOutcome.AUTH_EXPIRED
            ProviderStatus.ERROR -> FetchOutcome.UNKNOWN
            else -> FetchOutcome.NEVER
        }
        return SectionHealth(
            sectionId = section.wireName,
            lastAttemptEpochMs = if (outcome == FetchOutcome.NEVER) 0 else fetchedAt,
            lastSuccessEpochMs = if (outcome == FetchOutcome.SUCCESS && hasGood) fetchedAt else 0,
            lastOutcome = outcome.name,
            shortReason = provider?.error?.let { DiagnosticSanitizer.message(it) }
                ?: outcome.shortReason().takeIf { it.isNotBlank() },
            lastGoodMeters = meters,
            lastGoodPlanLabel = provider?.planLabel,
            lastGoodManualResets = provider?.manualResets,
            lastGoodDiagnosticJson = provider?.diagnosticJson,
            signedIn = signedIn
        )
    }
}

object DiagnosticSanitizer {
    private val jwt = Regex("""eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9._-]+""")
    private val bearer = Regex("""(?i)Bearer\s+[A-Za-z0-9._\-+=/]+""")
    private val cookiePair = Regex(
        """(?i)(WorkosCursorSessionToken|__Secure-next-auth\.session-token(?:\.\d+)?|Cookie|Authorization)\s*[:=]\s*\S+"""
    )
    private val account = Regex("""acct_[A-Za-z0-9]+""")

    fun endpoint(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return url.substringBefore('?').substringBefore('#').trim().takeIf { it.isNotBlank() }
    }

    fun message(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var text = raw
        text = jwt.replace(text, "[redacted-jwt]")
        text = bearer.replace(text, "Bearer [redacted]")
        text = cookiePair.replace(text, "$1=[redacted]")
        text = account.replace(text, "acct_[redacted]")
        return text.replace('\n', ' ').trim().take(240).ifBlank { null }
    }
}
