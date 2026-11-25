package com.usagewidget.domain

import com.usagewidget.providers.claude.ClaudeMeterIds

enum class SoftwareSectionId(val title: String, val wireName: String) {
    CURSOR("Cursor", "cursor"),
    GROK_BOT("Grok Bot", "grok_bot"),
    CODEX("Codex", "codex"),
    CLAUDE("Claude", "claude"),
    GEMINI("Gemini", "gemini");

    companion object {
        fun fromWire(value: String): SoftwareSectionId =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown section: $value")
    }
}

data class SoftwareSection(
    val id: SoftwareSectionId,
    val signInProvider: ProviderId,
    val status: ProviderStatus,
    val subtitle: String? = null,
    val error: String? = null,
    val meters: List<UsageMeter> = emptyList(),
    val health: SectionHealth = SectionHealth(id.wireName),
    val stale: StaleView = StaleView.fresh,
    val manualResets: ManualResets? = null
)

object OnDemandDisplay {
    const val CURSOR_METER = "cursor.on_demand"
    const val GROK_METER = "cursor.grok_on_demand"

    fun apply(
        sections: List<SoftwareSection>,
        showCursor: Boolean,
        showGrok: Boolean
    ): List<SoftwareSection> {
        if (showCursor && showGrok) return sections
        return sections.map { section ->
            val hidden = when (section.id) {
                SoftwareSectionId.CURSOR -> if (showCursor) null else CURSOR_METER
                SoftwareSectionId.GROK_BOT -> if (showGrok) null else GROK_METER
                else -> null
            }
            if (hidden == null || section.meters.none { it.id == hidden }) section
            else section.copy(meters = section.meters.filter { it.id != hidden })
        }
    }
}

object UsageSections {
    fun from(
        snapshot: UsageSnapshot,
        nowMs: Long = System.currentTimeMillis()
    ): List<SoftwareSection> {
        val hydrated = HealthSynthesis.hydrate(snapshot)
        val cursor = hydrated.provider(ProviderId.CURSOR)
        val openai = hydrated.provider(ProviderId.OPENAI)
        val claude = hydrated.provider(ProviderId.CLAUDE)
        return listOf(
            cursorSection(cursor, hydrated.health(SoftwareSectionId.CURSOR), nowMs),
            grokSection(cursor, hydrated.health(SoftwareSectionId.GROK_BOT), nowMs),
            codexSection(openai, hydrated.health(SoftwareSectionId.CODEX), nowMs),
            claudeSection(claude, hydrated.health(SoftwareSectionId.CLAUDE), nowMs),
            SoftwareSection(SoftwareSectionId.GEMINI, ProviderId.GEMINI,
                hydrated.provider(ProviderId.GEMINI)?.providerStatus ?: ProviderStatus.UNSIGNED,
                subtitle = hydrated.provider(ProviderId.GEMINI)?.planLabel,
                error = hydrated.provider(ProviderId.GEMINI)?.error,
                meters = hydrated.provider(ProviderId.GEMINI)?.meters.orEmpty(),
                health = hydrated.health(SoftwareSectionId.GEMINI),
                stale = hydrated.health(SoftwareSectionId.GEMINI).staleView(nowMs))
        )
    }

    private fun cursorSection(
        cursor: ProviderSnapshot?,
        health: SectionHealth,
        nowMs: Long
    ): SoftwareSection {
        val status = cursor?.providerStatus ?: ProviderStatus.UNSIGNED
        val live = listOfNotNull(
            cursor?.meters?.find { it.id == "cursor.models" },
            cursor?.meters?.find { it.id == "cursor.other" },
            cursor?.meters?.find { it.id == "cursor.on_demand" },
            cursor?.meters?.find { it.id == "cursor.plan_reset" }
                ?: cursor?.meters
                    ?.firstOrNull {
                        it.resetsAtEpochMs != null &&
                            it.id != "cursor.grok_bot" &&
                            it.id != "cursor.on_demand" &&
                            it.id != "cursor.grok_on_demand"
                    }
                    ?.let { planResetMeter(it.resetsAtEpochMs) }
        )
        val meters = if (health.outcomeEnum == FetchOutcome.SUCCESS) {
            live
        } else {
            live.ifEmpty { MeterIds.cursorMeters(health.lastGoodMeters) }
        }
        val stale = health.staleView(nowMs)
        return SoftwareSection(
            id = SoftwareSectionId.CURSOR,
            signInProvider = ProviderId.CURSOR,
            status = when {
                status == ProviderStatus.OK && meters.isEmpty() -> ProviderStatus.ERROR
                else -> status
            },
            subtitle = cursor?.planLabel ?: health.lastGoodPlanLabel,
            error = stale.shortReason ?: withoutGrokError(cursor?.error) ?: when {
                status == ProviderStatus.OK && meters.isEmpty() ->
                    "Cursor Models, Other Models, and plan reset were not in the response."
                else -> null
            },
            meters = meters,
            health = health,
            stale = stale,
            manualResets = cursor?.manualResets ?: health.lastGoodManualResets
        )
    }

    private fun grokSection(
        cursor: ProviderSnapshot?,
        health: SectionHealth,
        nowMs: Long
    ): SoftwareSection {
        val grok = cursor?.meters?.find { it.id == "cursor.grok_bot" }
            ?.copy(title = "Weekly usage", subtitle = null)
            ?: health.lastGoodMeters.find { it.id == "cursor.grok_bot" }
                ?.takeIf { health.outcomeEnum != FetchOutcome.SUCCESS }
                ?.copy(title = "Weekly usage", subtitle = null)
        val onDemand = cursor?.meters?.find { it.id == MeterIds.GROK_ON_DEMAND }
            ?: health.lastGoodMeters.find { it.id == MeterIds.GROK_ON_DEMAND }
                ?.takeIf { health.outcomeEnum != FetchOutcome.SUCCESS }
        val status = when {
            cursor == null -> ProviderStatus.UNSIGNED
            cursor.providerStatus == ProviderStatus.NEEDS_SIGN_IN ||
                cursor.providerStatus == ProviderStatus.UNSIGNED -> cursor.providerStatus
            grok != null -> ProviderStatus.OK
            cursor.providerStatus == ProviderStatus.LOADING -> ProviderStatus.LOADING
            else -> ProviderStatus.ERROR
        }
        val stale = health.staleView(nowMs)
        return SoftwareSection(
            id = SoftwareSectionId.GROK_BOT,
            signInProvider = ProviderId.CURSOR,
            status = status,
            subtitle = when (status) {
                ProviderStatus.UNSIGNED, ProviderStatus.NEEDS_SIGN_IN -> "Uses your Cursor sign-in"
                else -> null
            },
            error = stale.shortReason ?: when (status) {
                ProviderStatus.OK -> null
                ProviderStatus.NEEDS_SIGN_IN, ProviderStatus.UNSIGNED ->
                    "Sign in to Cursor to load Grok Bot weekly usage."
                ProviderStatus.LOADING -> null
                else -> grokOnlyError(cursor?.error)
                    ?: "Grok Bot weekly usage was not in the Cursor response."
            },
            meters = listOfNotNull(
                grok ?: UsageMeter(
                    id = "cursor.grok_bot",
                    title = "Weekly usage",
                    resetLabel = if (status == ProviderStatus.OK) null else "Waiting for data"
                ),
                onDemand
            ),
            health = health,
            stale = stale
        )
    }

    private fun codexSection(
        openai: ProviderSnapshot?,
        health: SectionHealth,
        nowMs: Long
    ): SoftwareSection {
        val status = openai?.providerStatus ?: ProviderStatus.UNSIGNED
        val sourceMeters = openai?.meters.orEmpty().ifEmpty {
            if (health.outcomeEnum == FetchOutcome.SUCCESS) emptyList() else health.lastGoodMeters
        }
        val live = MeterIds.codexMeters(sourceMeters).ifEmpty { sourceMeters }
        val meters = live.ifEmpty {
            listOf(
                placeholder("openai.five_hour", "5-hour"),
                placeholder("openai.weekly", "Weekly")
            )
        }
        val stale = health.staleView(nowMs)
        return SoftwareSection(
            id = SoftwareSectionId.CODEX,
            signInProvider = ProviderId.OPENAI,
            status = status,
            subtitle = openai?.planLabel ?: health.lastGoodPlanLabel,
            error = stale.shortReason ?: openai?.error,
            meters = meters,
            health = health,
            stale = stale,
            manualResets = openai?.manualResets ?: health.lastGoodManualResets
        )
    }

    private fun claudeSection(
        claude: ProviderSnapshot?,
        health: SectionHealth,
        nowMs: Long
    ): SoftwareSection {
        val status = claude?.providerStatus ?: ProviderStatus.UNSIGNED
        val sourceMeters = claude?.meters.orEmpty().ifEmpty {
            if (health.outcomeEnum == FetchOutcome.SUCCESS) emptyList() else health.lastGoodMeters
        }
        val meters = MeterIds.claudeMeters(sourceMeters).ifEmpty {
            listOf(
                placeholder(ClaudeMeterIds.FIVE_HOUR, "Session"),
                placeholder(ClaudeMeterIds.WEEKLY, "Weekly")
            )
        }
        val stale = health.staleView(nowMs)
        return SoftwareSection(
            id = SoftwareSectionId.CLAUDE,
            signInProvider = ProviderId.CLAUDE,
            status = status,
            subtitle = claude?.planLabel ?: health.lastGoodPlanLabel,
            error = stale.shortReason ?: claude?.error,
            meters = meters,
            health = health,
            stale = stale
        )
    }

    private fun planResetMeter(epochMs: Long?): UsageMeter = UsageMeter(
        id = "cursor.plan_reset",
        title = "Plan reset",
        resetsAtEpochMs = epochMs
    )

    private fun placeholder(id: String, title: String): UsageMeter = UsageMeter(
        id = id,
        title = title,
        resetLabel = "Waiting for data"
    )

    private fun withoutGrokError(error: String?): String? =
        error?.split(" · ")
            ?.filterNot { it.contains("Grok", ignoreCase = true) }
            ?.joinToString(" · ")
            ?.takeIf { it.isNotBlank() }

    private fun grokOnlyError(error: String?): String? =
        error?.split(" · ")
            ?.firstOrNull { it.contains("Grok", ignoreCase = true) }
            ?: error
}
