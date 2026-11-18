package com.usagewidget.domain

import com.usagewidget.util.NyTime

object DiagnosticsReport {
    fun format(
        snapshot: UsageSnapshot,
        versionName: String,
        nowMs: Long = System.currentTimeMillis()
    ): String {
        val hydrated = HealthSynthesis.hydrate(snapshot)
        val builder = StringBuilder()
        builder.appendLine("Usage Widget $versionName diagnostics")
        builder.appendLine("Generated ${NyTime.formatAbsolute(nowMs)} (America/New_York)")
        builder.appendLine("Refresh interval: 15 minutes (WorkManager unique periodic)")
        builder.appendLine("No cookies or tokens are included.")
        builder.appendLine()
        SoftwareSectionId.entries.forEach { section ->
            val health = hydrated.health(section)
            val stale = health.staleView(nowMs)
            builder.appendLine(section.title)
            builder.appendLine("  Last attempt: ${formatTs(health.lastAttemptEpochMs)}")
            builder.appendLine("  Last success: ${formatTs(health.lastSuccessEpochMs)}")
            builder.appendLine("  Outcome: ${health.lastOutcome}")
            builder.appendLine("  Reason: ${health.shortReason ?: stale.shortReason ?: "—"}")
            builder.appendLine("  HTTP status: ${health.httpStatus?.toString() ?: "—"}")
            builder.appendLine("  Endpoint: ${DiagnosticSanitizer.endpoint(health.endpoint) ?: "—"}")
            builder.appendLine("  Source: ${health.source ?: "—"}")
            builder.appendLine("  Message: ${DiagnosticSanitizer.message(health.message) ?: "—"}")
            builder.appendLine("  Stale: ${if (stale.isStale) stale.severity.name else "no"}")
            builder.appendLine("  Last-good meters: ${health.lastGoodMeters.size}")
            builder.appendLine("  Manual resets: ${health.lastGoodManualResets?.displayLine() ?: "hidden (none present)"}")
            builder.appendLine("  Observed JSON: ${health.lastGoodDiagnosticJson ?: "—"}")
            UsageSections.from(hydrated, nowMs).first { it.id == section }.meters.forEach { meter ->
                builder.appendLine(
                    "  ${meter.id} used=${meter.usedPercent} display=${meter.usedLabel() ?: "—"} resetMs=${meter.resetsAtEpochMs} windowStart=${meter.windowStartEpochMs} lengthMs=${meter.windowLengthMs}"
                )
            }
            builder.appendLine()
        }
        builder.appendLine("Recent attempts")
        if (hydrated.attemptLog.isEmpty()) {
            builder.appendLine("  (none yet)")
        } else {
            hydrated.attemptLog.asReversed().forEach { attempt ->
                val title = runCatching { SoftwareSectionId.fromWire(attempt.sectionId).title }
                    .getOrDefault(attempt.sectionId)
                val whenText = formatTs(attempt.atEpochMs)
                val http = attempt.httpStatus?.let { " HTTP $it" } ?: ""
                val endpoint = DiagnosticSanitizer.endpoint(attempt.endpoint)?.let { " $it" } ?: ""
                builder.appendLine("  $whenText  $title  ${attempt.outcome}$http$endpoint")
                DiagnosticSanitizer.message(attempt.message)?.let { builder.appendLine("    $it") }
            }
        }
        return builder.toString().trimEnd() + "\n"
    }

    private fun formatTs(epochMs: Long): String {
        if (epochMs <= 0L) return "—"
        return NyTime.formatAbsolute(epochMs)
    }
}
