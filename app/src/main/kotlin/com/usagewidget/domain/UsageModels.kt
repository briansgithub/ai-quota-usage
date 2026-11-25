package com.usagewidget.domain

import kotlinx.serialization.Serializable

enum class ProviderId(val wireName: String, val displayName: String) {
    CURSOR("cursor", "Cursor"),
    OPENAI("openai", "ChatGPT / Codex"),
    CLAUDE("claude", "Claude"),
    GEMINI("gemini", "Gemini (Antigravity)");

    companion object {
        fun fromWire(value: String): ProviderId =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown provider: $value")
    }
}

data class DisplayPreferences(
    val showCursorOnDemand: Boolean = true,
    val showGrokOnDemand: Boolean = true,
    /** Meter ids hidden on the home-screen widget (see [WidgetBars]). */
    val hiddenWidgetBars: Set<String> = emptySet(),
    /** Software order on the home-screen widget (see [WidgetBars]). */
    val widgetSectionOrder: List<SoftwareSectionId> = WidgetBars.defaultSectionOrder,
    /** Hide bars with no quota left, in the app and on the widget (see [UsageRemaining]). */
    val onlyUsageRemaining: Boolean = false
)

enum class ProviderStatus {
    OK,
    NEEDS_SIGN_IN,
    ERROR,
    UNSIGNED,
    LOADING
}

@Serializable
enum class MeterBarKind {
    PACE,
    CAP
}

@Serializable
data class UsageMeter(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val usedPercent: Double? = null,
    val remainingPercent: Double? = null,
    val resetsAtEpochMs: Long? = null,
    val resetLabel: String? = null,
    val windowStartEpochMs: Long? = null,
    val windowLengthMs: Long? = null,
    val barKind: MeterBarKind = MeterBarKind.PACE
) {
    val displayUsedPercent: Int?
        get() = usedPercent?.let { Percent.round(it) }
            ?: remainingPercent?.let { Percent.round((100.0 - it).coerceAtLeast(0.0)) }

    val displayRemainingPercent: Int?
        get() = remainingPercent?.let { Percent.round(it) }
            ?: usedPercent?.let { Percent.round(100.0 - it) }

    val hasUsageBar: Boolean get() = remainingPercent != null || usedPercent != null

    val isCapBar: Boolean get() = barKind == MeterBarKind.CAP

    fun usedLabel(): String? = displayUsedPercent?.let { "$it% used" }

    fun remainingLabel(): String? = displayRemainingPercent?.let { "$it% left" }

    fun remainingAmount(): Double? =
        remainingPercent ?: usedPercent?.let { (100.0 - it).coerceAtLeast(0.0) }

    /** Quota-window identity: period start plus reset, so a reset reseeds even if the overlaid reset timestamp is unchanged. */
    fun quotaWindowKey(): String {
        val start = windowStartEpochMs?.toString().orEmpty()
        val reset = resetsAtEpochMs?.toString() ?: "open"
        return "$start|$reset"
    }
}

@Serializable
data class ProviderSnapshot(
    val id: String,
    val displayName: String,
    val status: String,
    val error: String? = null,
    val source: String? = null,
    val planLabel: String? = null,
    val meters: List<UsageMeter> = emptyList(),
    val manualResets: ManualResets? = null,
    val diagnosticJson: String? = null
) {
    val providerId: ProviderId get() = ProviderId.fromWire(id)
    val providerStatus: ProviderStatus
        get() = runCatching { ProviderStatus.valueOf(status) }.getOrDefault(ProviderStatus.ERROR)
}

@Serializable
data class UsageSnapshot(
    val fetchedAtEpochMs: Long,
    val timezone: String = NyZone.ID,
    val providers: List<ProviderSnapshot> = emptyList(),
    val sectionHealth: List<SectionHealth> = emptyList(),
    val attemptLog: List<FetchAttempt> = emptyList()
) {
    fun provider(id: ProviderId): ProviderSnapshot? = providers.find { it.id == id.wireName }

    fun health(sectionId: SoftwareSectionId): SectionHealth =
        sectionHealth.find { it.sectionId == sectionId.wireName } ?: SectionHealth(sectionId.wireName)

    fun anySectionStale(nowMs: Long = System.currentTimeMillis()): Boolean =
        SoftwareSectionId.entries.any { health(it).staleView(nowMs).isStale }

    companion object {
        fun empty(nowMs: Long = System.currentTimeMillis()): UsageSnapshot =
            UsageSnapshot(
                fetchedAtEpochMs = nowMs,
                providers = listOf(
                    ProviderSnapshot(
                        id = ProviderId.CURSOR.wireName,
                        displayName = ProviderId.CURSOR.displayName,
                        status = ProviderStatus.UNSIGNED.name
                    ),
                    ProviderSnapshot(
                        id = ProviderId.OPENAI.wireName,
                        displayName = ProviderId.OPENAI.displayName,
                        status = ProviderStatus.UNSIGNED.name
                    ),
                    ProviderSnapshot(
                        id = ProviderId.CLAUDE.wireName,
                        displayName = ProviderId.CLAUDE.displayName,
                        status = ProviderStatus.UNSIGNED.name
                    )
                )
            )
    }
}

object Percent {
    fun remainingFromUsed(used: Double?): Double? =
        used?.let { (100.0 - it).coerceAtLeast(0.0) }

    fun usedFromRemaining(remaining: Double?): Double? =
        remaining?.let { (100.0 - it).coerceAtLeast(0.0) }

    /**
     * Wire percents from Cursor, Grok, and Codex WHAM are already 0–100
     * (Cursor `apiPercentUsed: 0.71` is 0.71% used, not 71%). Do not treat
     * (0, 1) as a fraction.
     */
    fun asWirePercent(value: Double): Double {
        if (!value.isFinite()) return 0.0
        return value
    }

    fun round(value: Double): Int {
        if (!value.isFinite()) return 0
        // Half-up, matching Cursor/Codex dashboard whole percents and Windows AwayFromZero.
        return kotlin.math.floor(value + 0.5).toInt()
    }
}

@Serializable
data class ManualResetCredit(
    val expiresAtEpochMs: Long? = null,
    val status: String? = null,
    val title: String? = null,
    val resetType: String? = null
) {
    val isAvailable: Boolean
        get() = status.isNullOrBlank() || status.equals("available", ignoreCase = true)
}

@Serializable
data class ManualResets(
    val availableCount: Int,
    val credits: List<ManualResetCredit> = emptyList(),
    val observedJson: String? = null
) {
    val isPresent: Boolean
        get() = availableCount > 0 || credits.any { it.isAvailable }

    fun displayLine(): String? {
        if (!isPresent) return null
        val availableCredits = credits.filter { it.isAvailable }
        val count = if (availableCount > 0) availableCount else availableCredits.size
        if (count <= 0) return null
        val zone = java.time.ZoneId.of(NyZone.ID)
        val expiries = availableCredits
            .mapNotNull { it.expiresAtEpochMs }
            .sorted()
            .map { epoch ->
                val zoned = java.time.Instant.ofEpochMilli(epoch).atZone(zone)
                "%02d/%02d".format(zoned.dayOfMonth, zoned.monthValue)
            }
        return if (expiries.isEmpty()) {
            "Manual resets: $count"
        } else {
            "Manual resets: $count (exp ${expiries.joinToString(", ")})"
        }
    }
}

object NyZone {
    const val ID = "America/New_York"
}

sealed class ProviderResult {
    data class Success(
        val snapshot: ProviderSnapshot,
        val attempts: List<FetchAttempt> = emptyList()
    ) : ProviderResult()

    data class NeedsSignIn(
        val message: String = "Session expired. Sign in again.",
        val attempts: List<FetchAttempt> = emptyList()
    ) : ProviderResult()

    data class Failure(
        val message: String,
        val cause: String? = null,
        val attempts: List<FetchAttempt> = emptyList(),
        val snapshot: ProviderSnapshot? = null
    ) : ProviderResult()
}
