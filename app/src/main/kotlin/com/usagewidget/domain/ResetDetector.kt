package com.usagewidget.domain

enum class RestoredKind { FIVE_HOUR, WEEKLY }

enum class RestoredReason { RESET_TIME_PASSED, REMAINING_JUMP }

data class RestoredUsageEvent(
    val meterId: String,
    val group: String,
    val title: String,
    val kind: RestoredKind,
    val serviceLabel: String,
    val previousResetAt: Long?,
    val newRemaining: Double?,
    val previousRemaining: Double?,
    val reason: RestoredReason,
    val dedupeKey: String
)

/**
 * Detects a 5-hour or weekly limit coming back from consecutive snapshots.
 *
 * A reset is a previous `resetsAt` that was still in the future at the last fetch
 * and has now passed, or remaining % jumping by at least [JUMP_THRESHOLD] points.
 * Cursor Models / plan reset collapse to one Cursor-plan event so the monthly
 * cycle does not fire three times. `cursor.other` is skipped for the same reason.
 */
object ResetDetector {
    const val JUMP_THRESHOLD = 15.0

    private val groups = listOf(
        "codex.five_hour",
        "codex.weekly",
        "grok.weekly",
        "cursor.plan"
    )

    fun detect(
        previous: UsageSnapshot,
        current: UsageSnapshot,
        nowMs: Long
    ): List<RestoredUsageEvent> {
        val prevMeters = flatten(previous)
        val currMeters = flatten(current)
        if (prevMeters.isEmpty() || currMeters.isEmpty()) return emptyList()

        val events = mutableListOf<RestoredUsageEvent>()
        for (group in groups) {
            val ids = meterIdsFor(group)
            val prev = ids.mapNotNull { prevMeters[it] }
            val curr = ids.mapNotNull { currMeters[it] }
            if (prev.isEmpty() || curr.isEmpty()) continue

            val resetPassed = prev.any { meter ->
                val resetAt = meter.resetsAtEpochMs ?: return@any false
                resetAt > previous.fetchedAtEpochMs && resetAt <= nowMs
            }
            val jumpedPair = ids.firstNotNullOfOrNull { id ->
                val before = remainingOf(prevMeters[id]) ?: return@firstNotNullOfOrNull null
                val after = remainingOf(currMeters[id]) ?: return@firstNotNullOfOrNull null
                if (after - before >= JUMP_THRESHOLD) Triple(id, before, after) else null
            }
            if (!resetPassed && jumpedPair == null) continue

            val representative = curr.first()
            val prevReset = prev.mapNotNull { it.resetsAtEpochMs }.minOrNull()
            val currReset = curr.mapNotNull { it.resetsAtEpochMs }.minOrNull()
            val cycleId = prevReset ?: currReset ?: hourBucket(nowMs)
            val remaining = jumpedPair?.third ?: remainingOf(representative)
            val previousRemaining = jumpedPair?.second ?: remainingOf(prev.first())
            events += RestoredUsageEvent(
                meterId = representative.id,
                group = group,
                title = titleFor(group),
                kind = kindFor(group),
                serviceLabel = serviceFor(group),
                previousResetAt = prevReset,
                newRemaining = remaining,
                previousRemaining = previousRemaining,
                reason = if (resetPassed) RestoredReason.RESET_TIME_PASSED else RestoredReason.REMAINING_JUMP,
                dedupeKey = "restored:$group:$cycleId"
            )
        }
        return events
    }

    fun notifyGroup(meterId: String): String? = when (meterId) {
        "openai.five_hour" -> "codex.five_hour"
        "openai.weekly" -> "codex.weekly"
        "cursor.grok_bot" -> "grok.weekly"
        "cursor.models", "cursor.plan_reset" -> "cursor.plan"
        else -> null
    }

    private fun meterIdsFor(group: String): List<String> = when (group) {
        "codex.five_hour" -> listOf("openai.five_hour")
        "codex.weekly" -> listOf("openai.weekly")
        "grok.weekly" -> listOf("cursor.grok_bot")
        "cursor.plan" -> listOf("cursor.models", "cursor.plan_reset")
        else -> emptyList()
    }

    private fun titleFor(group: String): String = when (group) {
        "codex.five_hour" -> "Codex 5-hour usage is back"
        "codex.weekly" -> "Codex weekly usage is back"
        "grok.weekly" -> "Grok Bot weekly usage is back"
        "cursor.plan" -> "Cursor usage is back"
        else -> "Usage is back"
    }

    private fun serviceFor(group: String): String = when (group) {
        "codex.five_hour", "codex.weekly" -> "Codex"
        "grok.weekly" -> "Grok Bot"
        "cursor.plan" -> "Cursor"
        else -> "Usage"
    }

    private fun kindFor(group: String): RestoredKind =
        if (group == "codex.five_hour") RestoredKind.FIVE_HOUR else RestoredKind.WEEKLY

    private fun flatten(snapshot: UsageSnapshot): Map<String, UsageMeter> {
        val out = linkedMapOf<String, UsageMeter>()
        snapshot.providers.forEach { provider ->
            provider.meters.forEach { meter -> out[meter.id] = meter }
        }
        return out
    }

    private fun remainingOf(meter: UsageMeter?): Double? {
        if (meter == null) return null
        return meter.remainingPercent
            ?: meter.usedPercent?.let { (100.0 - it).coerceAtLeast(0.0) }
    }

    private fun hourBucket(nowMs: Long): Long = nowMs / 3_600_000L * 3_600_000L
}
