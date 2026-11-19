package com.usagewidget.domain

import com.usagewidget.util.NyTime
import kotlin.math.roundToInt

data class AlertPreferences(
    val percentPerWindow: Double = PacingPolicy.DEFAULT_PERCENT_PER_WINDOW,
    val notifyStaleFailures: Boolean = true,
    val notifyUsageRestored: Boolean = true,
    val notifyPacingReminders: Boolean = true,
    val notifyPacingMissed: Boolean = true,
    val quietHoursEnabled: Boolean = false,
    val quietHoursStartHour: Int = QuietHours.DEFAULT_START_HOUR,
    val quietHoursEndHour: Int = QuietHours.DEFAULT_END_HOUR
)

enum class AlertChannel { RESTORED, PACING, QUOTA }

data class AlertToPost(
    val channel: AlertChannel,
    val title: String,
    val body: String,
    val dedupeKey: String,
    val notificationId: Int,
    /** Service the alert is about; picks the notification's logo icon. */
    val section: SoftwareSectionId? = null
)

data class ScheduledWake(
    val uniqueName: String,
    val atEpochMs: Long
)

data class UsageAlertPlan(
    val alerts: List<AlertToPost>,
    val wakes: List<ScheduledWake>,
    val newSeenKeys: Set<String>
)

/**
 * Pure evaluation of usage-restored and pacing alerts from two snapshots.
 * Quiet hours suppress posting (keys stay unseen). Dedupe keys are remembered
 * only for alerts that would actually post.
 */
object UsageAlertEngine {
    const val RESET_WAKE_PADDING_MS = 5_000L
    const val MAX_WAKE_AHEAD_MS = 7L * 24 * 60 * 60 * 1000

    fun evaluate(
        previous: UsageSnapshot,
        current: UsageSnapshot,
        nowMs: Long,
        prefs: AlertPreferences,
        seenKeys: Set<String>
    ): UsageAlertPlan {
        val restored = if (prefs.notifyUsageRestored) {
            ResetDetector.detect(previous, current, nowMs)
        } else {
            emptyList()
        }
        val sections = UsageSections.from(current, nowMs)
        val pacing = sections.mapNotNull { section ->
            PacingPlanner.forSection(section, nowMs, prefs.percentPerWindow)
        }

        val candidates = mutableListOf<AlertToPost>()
        if (prefs.notifyUsageRestored) {
            restored.forEach { event ->
                candidates += restoredAlert(event)
            }
        }
        pacing.forEach { result ->
            candidates += pacingAlerts(result, nowMs, prefs)
        }

        val quiet = QuietHours.isQuiet(
            nowMs = nowMs,
            enabled = prefs.quietHoursEnabled,
            startHour = prefs.quietHoursStartHour,
            endHour = prefs.quietHoursEndHour
        )
        val toPost = if (quiet) {
            emptyList()
        } else {
            candidates.filter { NotificationDedupe.shouldNotify(seenKeys, it.dedupeKey) }
        }
        val newSeen = if (quiet) {
            seenKeys
        } else {
            NotificationDedupe.rememberAll(seenKeys, toPost.map { it.dedupeKey })
        }

        val wakes = mutableListOf<ScheduledWake>()
        if (prefs.notifyUsageRestored) {
            wakes += resetWakes(current, nowMs)
        }
        if (prefs.notifyPacingReminders || prefs.notifyPacingMissed) {
            wakes += pacingWakes(pacing, nowMs)
        }

        return UsageAlertPlan(
            alerts = toPost,
            wakes = wakes.distinctBy { it.uniqueName },
            newSeenKeys = newSeen
        )
    }

    private fun restoredAlert(event: RestoredUsageEvent): AlertToPost {
        val remaining = event.newRemaining?.roundToInt()?.let { "${100 - it}% used" } ?: "ready to use"
        val body = when (event.group) {
            "codex.five_hour" -> "Codex 5-hour limit has reset ($remaining)."
            "codex.weekly" -> "Codex weekly limit has reset ($remaining)."
            "claude.five_hour" -> "Claude session limit has reset ($remaining)."
            "claude.weekly" -> "Claude weekly limit has reset ($remaining)."
            "grok.weekly" -> "Grok Bot weekly limit has reset ($remaining)."
            "cursor.plan" -> "Cursor plan usage has reset ($remaining)."
            else -> "${event.serviceLabel} usage has reset ($remaining)."
        }
        return AlertToPost(
            channel = AlertChannel.RESTORED,
            title = event.title,
            body = body,
            dedupeKey = event.dedupeKey,
            notificationId = notificationId(AlertChannel.RESTORED, event.dedupeKey),
            section = sectionForGroup(event.group)
        )
    }

    private fun pacingAlerts(
        result: PacingResult,
        nowMs: Long,
        prefs: AlertPreferences
    ): List<AlertToPost> {
        val alerts = mutableListOf<AlertToPost>()
        val name = displayName(result.sectionId)

        if (prefs.notifyPacingReminders &&
            result.kind == PaceKind.FIVE_AND_WEEKLY &&
            result.fiveHourState == FiveHourWindowState.AVAILABLE &&
            result.extraWindowsNeeded > 0
        ) {
            val latest = result.nextLatestStartEpochMs
            if (latest != null && latest > nowMs) {
                val until = latest - nowMs
                val comfortable = result.comfortableStartEpochMs
                val feasible = result.feasibleStartEpochMs
                if (comfortable != null &&
                    nowMs >= comfortable &&
                    nowMs < (feasible ?: latest)
                ) {
                    alerts += alert(
                        channel = AlertChannel.PACING,
                        title = "Start a $name session",
                        body = "Start a $name session within ${NyTime.formatDuration(until)} to stay on pace.",
                        key = "pace-start:${result.sectionId}:$latest:comfortable"
                    )
                }
                if (feasible != null && nowMs >= feasible && nowMs < latest) {
                    alerts += alert(
                        channel = AlertChannel.PACING,
                        title = "Last chance for this $name window",
                        body = "Start a $name session within ${NyTime.formatDuration(until)} — latest start is now.",
                        key = "pace-start:${result.sectionId}:$latest:feasible"
                    )
                }
            }
        }

        if (prefs.notifyPacingMissed && result.missedWindow) {
            val slack = result.slackMs
            val body = when (result.urgency) {
                PaceUrgency.IMPOSSIBLE ->
                    "Missed a $name window. You can no longer use everything (~${result.weeklyLostPercent.roundToInt()}% weekly will likely be lost)."
                PaceUrgency.CRITICAL, PaceUrgency.TIGHT ->
                    "Missed a $name window. Cutting it close — ${NyTime.formatDuration(slack.coerceAtLeast(0))} float left."
                PaceUrgency.OK ->
                    "Missed a $name window. ${NyTime.formatDuration(slack.coerceAtLeast(0))} float left before it becomes critical."
            }
            alerts += alert(
                channel = AlertChannel.PACING,
                title = "Missed a $name window",
                body = body,
                key = "pace-missed:${result.sectionId}:${result.nextLatestStartEpochMs}"
            )
        }

        if (prefs.notifyPacingMissed &&
            result.kind == PaceKind.FIVE_AND_WEEKLY &&
            (result.urgency == PaceUrgency.CRITICAL || result.urgency == PaceUrgency.IMPOSSIBLE) &&
            !result.missedWindow
        ) {
            val body = if (result.urgency == PaceUrgency.IMPOSSIBLE) {
                "$name slack is gone. About ${result.weeklyLostPercent.roundToInt()}% weekly will likely be left unused."
            } else {
                "$name slack is down to ${NyTime.formatDuration(result.slackMs.coerceAtLeast(0))}."
            }
            alerts += alert(
                channel = AlertChannel.PACING,
                title = "$name pacing is ${result.urgency.name.lowercase()}",
                body = body,
                key = "pace-critical:${result.sectionId}:${result.weeklyResetEpochMs}"
            )
        }

        if (prefs.notifyPacingReminders &&
            result.kind == PaceKind.WEEKLY_ONLY &&
            result.shouldWarnUnused
        ) {
            alerts += alert(
                channel = AlertChannel.PACING,
                title = "$name usage is ahead of the clock",
                body = "${result.wasteGap.roundToInt()}% less used than even pace — on track to leave usage unused.",
                key = "pace-weekly:${result.sectionId}:${result.weeklyResetEpochMs}"
            )
        }

        if (prefs.notifyPacingMissed) {
            result.manuals.filter { it.willExpireUnused }.forEach { manual ->
                val warning = manual.warning ?: return@forEach
                alerts += alert(
                    channel = AlertChannel.PACING,
                    title = "Codex manual reset expiring unused",
                    body = warning,
                    key = "pace-expire:${manual.expiresAtEpochMs}"
                )
            }
        }
        val section = runCatching { SoftwareSectionId.fromWire(result.sectionId) }.getOrNull()
        return alerts.map { it.copy(section = section) }
    }

    private fun resetWakes(current: UsageSnapshot, nowMs: Long): List<ScheduledWake> {
        return current.providers.flatMap { it.meters }.mapNotNull { meter ->
            val group = ResetDetector.notifyGroup(meter.id) ?: return@mapNotNull null
            val resetAt = meter.resetsAtEpochMs ?: return@mapNotNull null
            val at = resetAt + RESET_WAKE_PADDING_MS
            if (at <= nowMs || at - nowMs > MAX_WAKE_AHEAD_MS) return@mapNotNull null
            ScheduledWake(uniqueName = "timed-reset-$group", atEpochMs = at)
        }
    }

    private fun pacingWakes(results: List<PacingResult>, nowMs: Long): List<ScheduledWake> {
        val wakes = mutableListOf<ScheduledWake>()
        for (result in results) {
            if (result.kind != PaceKind.FIVE_AND_WEEKLY) continue
            val latest = result.nextLatestStartEpochMs ?: continue
            result.comfortableStartEpochMs?.let { at ->
                if (at > nowMs && at - nowMs <= MAX_WAKE_AHEAD_MS) {
                    wakes += ScheduledWake("timed-pace-${result.sectionId}-comfortable", at)
                }
            }
            result.feasibleStartEpochMs?.let { at ->
                if (at > nowMs && at - nowMs <= MAX_WAKE_AHEAD_MS) {
                    wakes += ScheduledWake("timed-pace-${result.sectionId}-feasible", at)
                }
            }
            val missedAt = latest + RESET_WAKE_PADDING_MS
            if (missedAt > nowMs && missedAt - nowMs <= MAX_WAKE_AHEAD_MS) {
                wakes += ScheduledWake("timed-pace-${result.sectionId}-missed", missedAt)
            }
        }
        return wakes
    }

    private fun alert(
        channel: AlertChannel,
        title: String,
        body: String,
        key: String
    ): AlertToPost = AlertToPost(
        channel = channel,
        title = title,
        body = body,
        dedupeKey = key,
        notificationId = notificationId(channel, key)
    )

    fun notificationId(channel: AlertChannel, key: String): Int {
        val base = if (channel == AlertChannel.RESTORED) 4200 else 5300
        return base + (key.hashCode() and 0x3FF)
    }

    private fun sectionForGroup(group: String): SoftwareSectionId? = when (group.substringBefore('.')) {
        "codex" -> SoftwareSectionId.CODEX
        "claude" -> SoftwareSectionId.CLAUDE
        "grok" -> SoftwareSectionId.GROK_BOT
        "cursor" -> SoftwareSectionId.CURSOR
        else -> null
    }

    private fun displayName(sectionId: String): String =
        runCatching { SoftwareSectionId.fromWire(sectionId).title }.getOrDefault(sectionId)
}
