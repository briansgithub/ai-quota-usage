package com.usagewidget.domain

import com.usagewidget.providers.claude.ClaudeMeterIds

/**
 * "Only models with usage remaining": hides bars for quota that is spent until its
 * long window resets. Stacks with [WidgetBars] (a bar shows only if its pill allows it
 * AND it still has usage left).
 *
 * - A long-window bar (weekly, monthly, 30-day, or a monthly spend cap) at 100% or more
 *   used is hidden, together with every bar of the same model group, so its 5-hour bar
 *   goes too: that one resets, but there is nothing behind it to spend.
 * - A short-window bar (one day or less, e.g. 5-hour) at 100% on its own stays: it
 *   frees up within hours.
 *
 * Groups per provider:
 * - Claude: Session + Weekly (all models). Weekly spent hides every Claude bar,
 *   Sonnet/Opus included. A spent Sonnet or Opus weekly hides only that bar.
 * - Codex: 5-hour + Weekly + 30-day. Code review and each extra model limit
 *   ("Name · 5-hour", "Name · Weekly") are their own groups.
 * - Cursor: Models, Other models and On-demand are separate monthly pools, each hidden
 *   alone. There is no 5-hour window.
 * - Grok Bot: Weekly and On-demand, each hidden alone.
 *
 * Bars come back on the first refresh after the quota resets below 100%.
 */
object UsageRemaining {
    private const val SHORT_WINDOW_MAX_MS = 24L * 60 * 60 * 1000

    private val codexMain = setOf("openai.five_hour", "openai.weekly", "openai.thirty_day")

    /** Account-wide windows: when spent, nothing else in the section is usable. */
    private val accountWide = setOf(ClaudeMeterIds.WEEKLY)

    fun isSpent(meter: UsageMeter): Boolean =
        meter.hasUsageBar && (meter.displayUsedPercent ?: 0) >= 100

    fun isShortWindow(meter: UsageMeter): Boolean {
        if (meter.isCapBar) return false
        val length = meter.windowLengthMs ?: PaceWindows.knownLengthMs(meter.id) ?: return false
        return length in 1..SHORT_WINDOW_MAX_MS
    }

    /** Meter ids in [section] to hide because their quota is used up. */
    fun spentMeterIds(section: SoftwareSection): Set<String> {
        val bars = section.meters.filter { it.hasUsageBar }
        val blocking = bars.filter { isSpent(it) && !isShortWindow(it) }
        if (blocking.isEmpty()) return emptySet()
        if (blocking.any { it.id in accountWide }) return bars.mapTo(mutableSetOf()) { it.id }
        val spentGroups = blocking.mapTo(mutableSetOf()) { groupKey(section.id, it) }
        return bars.filter { groupKey(section.id, it) in spentGroups }.mapTo(mutableSetOf()) { it.id }
    }

    /**
     * Drops spent bars when [enabled]. With [dropSpentCards], a card left with no bar
     * (every one was spent) is dropped too, so the widget only lists services you can use;
     * the in-app dashboard keeps the card for its sign-in / sign-out buttons.
     */
    fun apply(
        sections: List<SoftwareSection>,
        enabled: Boolean,
        dropSpentCards: Boolean
    ): List<SoftwareSection> {
        if (!enabled) return sections
        return sections.mapNotNull { section ->
            val spent = spentMeterIds(section)
            if (spent.isEmpty()) return@mapNotNull section
            val kept = section.meters.filter { it.id !in spent }
            if (dropSpentCards && kept.none { it.hasUsageBar }) null else section.copy(meters = kept)
        }
    }

    private fun groupKey(section: SoftwareSectionId, meter: UsageMeter): String = when (section) {
        SoftwareSectionId.CLAUDE -> when (meter.id) {
            ClaudeMeterIds.FIVE_HOUR, ClaudeMeterIds.WEEKLY -> "claude"
            else -> meter.id
        }
        SoftwareSectionId.CODEX -> when {
            meter.id in codexMain -> "codex"
            " · " in meter.title -> "codex:" + meter.title.substringBefore(" · ")
            else -> meter.id
        }
        else -> meter.id
    }
}
