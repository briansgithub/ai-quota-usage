package com.usagewidget.domain

object SnapshotMerger {
    fun merge(
        previous: UsageSnapshot,
        cursorResult: ProviderResult,
        openaiResult: ProviderResult,
        nowMs: Long,
        cursorSignedIn: Boolean,
        openaiSignedIn: Boolean
    ): UsageSnapshot {
        val prior = HealthSynthesis.hydrate(previous)
        val health = prior.sectionHealth.associateBy { it.sectionId }.toMutableMap()
        SoftwareSectionId.entries.forEach { id ->
            health.putIfAbsent(id.wireName, SectionHealth(id.wireName))
        }

        val cursorMetersThisRound = metersFrom(cursorResult)
        val openaiMetersThisRound = metersFrom(openaiResult)

        val attempts = mutableListOf<FetchAttempt>()
        attempts += attemptsOrSynthetic(
            result = cursorResult,
            sections = listOf(SoftwareSectionId.CURSOR, SoftwareSectionId.GROK_BOT),
            signedIn = cursorSignedIn,
            health = health,
            nowMs = nowMs
        )
        attempts += attemptsOrSynthetic(
            result = openaiResult,
            sections = listOf(SoftwareSectionId.CODEX),
            signedIn = openaiSignedIn,
            health = health,
            nowMs = nowMs
        )

        attempts.forEach { attempt ->
            val section = runCatching { SoftwareSectionId.fromWire(attempt.sectionId) }.getOrNull() ?: return@forEach
            val incoming = MeterIds.forSection(
                section,
                if (section == SoftwareSectionId.CODEX) openaiMetersThisRound else cursorMetersThisRound
            )
            val planLabel = when (section) {
                SoftwareSectionId.CODEX -> snapshotOf(openaiResult)?.planLabel
                else -> snapshotOf(cursorResult)?.planLabel
            }
            val signedIn = when (section) {
                SoftwareSectionId.CODEX -> openaiSignedIn || attempt.outcomeEnum == FetchOutcome.AUTH_EXPIRED
                else -> cursorSignedIn || attempt.outcomeEnum == FetchOutcome.AUTH_EXPIRED
            }
            val incomingManual = when (section) {
                SoftwareSectionId.CODEX -> snapshotOf(openaiResult)?.manualResets
                SoftwareSectionId.CURSOR -> snapshotOf(cursorResult)?.manualResets
                SoftwareSectionId.GROK_BOT -> null
            }
            val incomingDiag = when (section) {
                SoftwareSectionId.CODEX -> snapshotOf(openaiResult)?.diagnosticJson
                SoftwareSectionId.CURSOR -> snapshotOf(cursorResult)?.diagnosticJson
                SoftwareSectionId.GROK_BOT -> null
            }
            health[section.wireName] = applyAttempt(
                previous = health.getValue(section.wireName),
                attempt = attempt,
                newMeters = incoming,
                planLabel = planLabel,
                signedIn = signedIn,
                manualResets = incomingManual,
                diagnosticJson = incomingDiag
            )
        }

        markUnsignedOrExpired(
            signedIn = cursorSignedIn,
            sections = listOf(SoftwareSectionId.CURSOR, SoftwareSectionId.GROK_BOT),
            attempts = attempts,
            health = health,
            nowMs = nowMs
        )
        markUnsignedOrExpired(
            signedIn = openaiSignedIn,
            sections = listOf(SoftwareSectionId.CODEX),
            attempts = attempts,
            health = health,
            nowMs = nowMs
        )

        val cursorHealth = health.getValue(SoftwareSectionId.CURSOR.wireName)
        val grokHealth = health.getValue(SoftwareSectionId.GROK_BOT.wireName)
        val codexHealth = health.getValue(SoftwareSectionId.CODEX.wireName)

        val cursorProvider = rebuildProvider(
            id = ProviderId.CURSOR,
            previous = prior.provider(ProviderId.CURSOR),
            thisRound = snapshotOf(cursorResult),
            result = cursorResult,
            signedIn = cursorSignedIn || cursorHealth.signedIn,
            meters = cursorHealth.lastGoodMeters + grokHealth.lastGoodMeters,
            planLabel = cursorHealth.lastGoodPlanLabel,
            manualResets = cursorHealth.lastGoodManualResets,
            diagnosticJson = cursorHealth.lastGoodDiagnosticJson
        )
        val openaiProvider = rebuildProvider(
            id = ProviderId.OPENAI,
            previous = prior.provider(ProviderId.OPENAI),
            thisRound = snapshotOf(openaiResult),
            result = openaiResult,
            signedIn = openaiSignedIn || codexHealth.signedIn,
            meters = codexHealth.lastGoodMeters,
            planLabel = codexHealth.lastGoodPlanLabel,
            manualResets = codexHealth.lastGoodManualResets,
            diagnosticJson = codexHealth.lastGoodDiagnosticJson
        )

        val log = (prior.attemptLog + attempts).takeLast(StalePolicy.ATTEMPT_LOG_LIMIT)
        return UsageSnapshot(
            fetchedAtEpochMs = nowMs,
            providers = listOf(cursorProvider, openaiProvider),
            sectionHealth = SoftwareSectionId.entries.map { health.getValue(it.wireName) },
            attemptLog = log
        )
    }

    fun applyAttempt(
        previous: SectionHealth,
        attempt: FetchAttempt,
        newMeters: List<UsageMeter>,
        planLabel: String?,
        signedIn: Boolean,
        manualResets: ManualResets? = null,
        diagnosticJson: String? = null
    ): SectionHealth {
        val success = attempt.outcomeEnum == FetchOutcome.SUCCESS
        val goodMeters = if (success && newMeters.isNotEmpty()) newMeters else previous.lastGoodMeters
        return previous.copy(
            lastAttemptEpochMs = attempt.atEpochMs,
            lastSuccessEpochMs = if (success) attempt.atEpochMs else previous.lastSuccessEpochMs,
            lastOutcome = attempt.outcome,
            shortReason = attempt.shortReason.takeIf { it.isNotBlank() },
            message = attempt.message,
            httpStatus = attempt.httpStatus,
            endpoint = attempt.endpoint,
            source = attempt.source,
            lastGoodMeters = goodMeters,
            lastGoodPlanLabel = if (success) planLabel ?: previous.lastGoodPlanLabel else previous.lastGoodPlanLabel,
            lastGoodManualResets = if (success) manualResets else previous.lastGoodManualResets,
            lastGoodDiagnosticJson = if (success) diagnosticJson ?: previous.lastGoodDiagnosticJson else previous.lastGoodDiagnosticJson,
            signedIn = signedIn
        )
    }

    fun clearForSignOut(previous: UsageSnapshot, provider: ProviderId): UsageSnapshot {
        val sections = when (provider) {
            ProviderId.CURSOR -> listOf(SoftwareSectionId.CURSOR, SoftwareSectionId.GROK_BOT)
            ProviderId.OPENAI -> listOf(SoftwareSectionId.CODEX)
        }
        val health = previous.sectionHealth.toMutableList()
        SoftwareSectionId.entries.forEach { id ->
            if (health.none { it.sectionId == id.wireName }) {
                health += SectionHealth(id.wireName)
            }
        }
        val updatedHealth = health.map { item ->
            val id = runCatching { SoftwareSectionId.fromWire(item.sectionId) }.getOrNull()
            if (id in sections) {
                SectionHealth(sectionId = item.sectionId, signedIn = false)
            } else item
        }
        val providers = previous.providers.map {
            if (it.id == provider.wireName) {
                it.copy(
                    status = ProviderStatus.UNSIGNED.name,
                    error = null,
                    meters = emptyList(),
                    source = null,
                    planLabel = null,
                    manualResets = null,
                    diagnosticJson = null
                )
            } else it
        }
        return previous.copy(providers = providers, sectionHealth = updatedHealth)
    }

    private fun attemptsOrSynthetic(
        result: ProviderResult,
        sections: List<SoftwareSectionId>,
        signedIn: Boolean,
        health: Map<String, SectionHealth>,
        nowMs: Long
    ): List<FetchAttempt> {
        val explicit = when (result) {
            is ProviderResult.Success -> result.attempts
            is ProviderResult.NeedsSignIn -> result.attempts
            is ProviderResult.Failure -> result.attempts
        }.filter { attempt -> sections.any { it.wireName == attempt.sectionId } }
        if (explicit.isNotEmpty()) return explicit
        if (!signedIn) return emptyList()
        val outcome = when (result) {
            is ProviderResult.Success -> FetchOutcome.SUCCESS
            is ProviderResult.NeedsSignIn -> FetchOutcome.AUTH_EXPIRED
            is ProviderResult.Failure -> FetchOutcome.UNKNOWN
        }
        val message = when (result) {
            is ProviderResult.NeedsSignIn -> result.message
            is ProviderResult.Failure -> result.message
            is ProviderResult.Success -> null
        }
        return sections.map { section ->
            val previous = health[section.wireName]
            if (outcome == FetchOutcome.AUTH_EXPIRED && previous?.hasLastGood != true && !signedIn) {
                return@map FetchAttempt.of(nowMs, section, FetchOutcome.NEVER)
            }
            FetchAttempt.of(
                atEpochMs = nowMs,
                sectionId = section,
                outcome = outcome,
                message = message,
                source = snapshotOf(result)?.source
            )
        }.filter { it.outcomeEnum != FetchOutcome.NEVER }
    }

    private fun markUnsignedOrExpired(
        signedIn: Boolean,
        sections: List<SoftwareSectionId>,
        attempts: MutableList<FetchAttempt>,
        health: MutableMap<String, SectionHealth>,
        nowMs: Long
    ) {
        if (signedIn) return
        if (attempts.any { attempt -> sections.any { it.wireName == attempt.sectionId } }) return
        sections.forEach { section ->
            val previous = health.getValue(section.wireName)
            if (previous.hasLastGood) {
                val attempt = FetchAttempt.of(
                    atEpochMs = nowMs,
                    sectionId = section,
                    outcome = FetchOutcome.AUTH_EXPIRED,
                    message = "Sign-in expired"
                )
                attempts += attempt
                health[section.wireName] = applyAttempt(
                    previous = previous,
                    attempt = attempt,
                    newMeters = emptyList(),
                    planLabel = null,
                    signedIn = true
                )
            } else {
                health[section.wireName] = unsigned(previous)
            }
        }
    }

    private fun unsigned(previous: SectionHealth): SectionHealth =
        SectionHealth(sectionId = previous.sectionId, signedIn = false)

    private fun snapshotOf(result: ProviderResult): ProviderSnapshot? = when (result) {
        is ProviderResult.Success -> result.snapshot
        is ProviderResult.Failure -> result.snapshot
        is ProviderResult.NeedsSignIn -> null
    }

    private fun metersFrom(result: ProviderResult): List<UsageMeter> =
        snapshotOf(result)?.meters.orEmpty()

    private fun rebuildProvider(
        id: ProviderId,
        previous: ProviderSnapshot?,
        thisRound: ProviderSnapshot?,
        result: ProviderResult,
        signedIn: Boolean,
        meters: List<UsageMeter>,
        planLabel: String?,
        manualResets: ManualResets?,
        diagnosticJson: String?
    ): ProviderSnapshot {
        val status = when (result) {
            is ProviderResult.Success -> ProviderStatus.OK
            is ProviderResult.NeedsSignIn ->
                if (meters.isNotEmpty()) ProviderStatus.NEEDS_SIGN_IN else ProviderStatus.NEEDS_SIGN_IN
            is ProviderResult.Failure ->
                if (meters.isNotEmpty()) ProviderStatus.OK else ProviderStatus.ERROR
        }.let { computed ->
            if (!signedIn && meters.isEmpty()) ProviderStatus.UNSIGNED else computed
        }
        val error = when (result) {
            is ProviderResult.Success -> thisRound?.error
            is ProviderResult.NeedsSignIn -> result.message
            is ProviderResult.Failure -> result.message
        }
        return ProviderSnapshot(
            id = id.wireName,
            displayName = id.displayName,
            status = status.name,
            error = error,
            source = thisRound?.source ?: previous?.source,
            planLabel = planLabel ?: thisRound?.planLabel ?: previous?.planLabel,
            meters = meters,
            manualResets = manualResets,
            diagnosticJson = diagnosticJson
        )
    }
}
