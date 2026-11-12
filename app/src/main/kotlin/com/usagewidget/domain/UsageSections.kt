package com.usagewidget.domain

enum class SoftwareSectionId(val title: String, val wireName: String) {
    CURSOR("Cursor", "cursor"),
    GROK_BOT("Grok Bot", "grok_bot"),
    CODEX("Codex", "codex");

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

object UsageSections {
    fun from(
        snapshot: UsageSnapshot,
        nowMs: Long = System.currentTimeMillis()
    ): List<SoftwareSection> {
        val hydrated = HealthSynthesis.hydrate(snapshot)
        val cursor = hydrated.provider(ProviderId.CURSOR)
        val openai = hydrated.provider(ProviderId.OPENAI)
        return listOf(
            cursorSection(cursor, hydrated.health(SoftwareSectionId.CURSOR), nowMs),
            grokSection(cursor, hydrated.health(SoftwareSectionId.GROK_BOT), nowMs),
            codexSection(openai, hydrated.health(SoftwareSectionId.CODEX), nowMs)
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
            cursor?.meters?.find { it.id == "cursor.plan_reset" }
                ?: cursor?.meters
                    ?.firstOrNull { it.resetsAtEpochMs != null && it.id != "cursor.grok_bot" }
                    ?.let { planResetMeter(it.resetsAtEpochMs) }
        )
        val meters = live.ifEmpty { MeterIds.cursorMeters(health.lastGoodMeters) }
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
                ?.copy(title = "Weekly usage", subtitle = null)
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
            meters = listOf(
                grok ?: UsageMeter(
                    id = "cursor.grok_bot",
                    title = "Weekly usage",
                    resetLabel = if (status == ProviderStatus.OK) null else "Waiting for data"
                )
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
        val sourceMeters = openai?.meters.orEmpty().ifEmpty { health.lastGoodMeters }
        val five = sourceMeters.find { it.id == "openai.five_hour" }
            ?: sourceMeters.firstOrNull { it.title.contains("5-hour", ignoreCase = true) }
        val weekly = sourceMeters.find { it.id == "openai.weekly" }
            ?: sourceMeters.firstOrNull {
                it.id != five?.id && it.title.contains("Weekly", ignoreCase = true)
            }
        val stale = health.staleView(nowMs)
        return SoftwareSection(
            id = SoftwareSectionId.CODEX,
            signInProvider = ProviderId.OPENAI,
            status = status,
            subtitle = openai?.planLabel ?: health.lastGoodPlanLabel,
            error = stale.shortReason ?: openai?.error,
            meters = listOf(
                five ?: placeholder("openai.five_hour", "5-hour"),
                weekly ?: placeholder("openai.weekly", "Weekly")
            ),
            health = health,
            stale = stale,
            manualResets = openai?.manualResets ?: health.lastGoodManualResets
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
