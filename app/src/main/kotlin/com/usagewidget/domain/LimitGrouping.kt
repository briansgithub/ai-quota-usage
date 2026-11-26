package com.usagewidget.domain

/** Rate-limit window length class used by the widget's "group by limit" mode. */
enum class LimitKind(val title: String) {
    /** Windows of 24 hours or less (5-hour sessions, daily caps). */
    SHORT("Short limits (5-hr)"),

    /** Everything longer: weekly, 30-day, monthly billing cycles, and spend caps. */
    LONG("Long limits (weekly / monthly)"),

    /** Software with no bars at all (sign-in prompt, error, loading): kept visible at the end. */
    NO_DATA("Needs attention")
}

/**
 * One software's slice of a [LimitGroup]: the meters of that group's window class.
 * [section] is the full original card; [primary] marks the one entry per software that also
 * carries the card-level lines (stale notice, manual resets, pacing, sign-in prompt) so they
 * are shown once, not once per group.
 */
data class LimitGroupEntry(
    val section: SoftwareSection,
    val meters: List<UsageMeter>,
    val primary: Boolean
)

data class LimitGroup(val kind: LimitKind, val entries: List<LimitGroupEntry>)

/**
 * Regroups software cards by rate-limit window length: short windows first, then long, then a
 * trailing group for software with nothing to show. Software order within a group follows the
 * incoming [sections] order (so the widget's custom software order is kept). Matches Windows
 * `LimitGrouping`.
 */
object LimitGrouping {
    /** Same cutoff as [UsageRemaining.isShortWindow]: 24 hours. */
    const val SHORT_WINDOW_MAX_MS = 24L * 60 * 60 * 1000

    /**
     * Window length from the data model, best source first: the meter's own length, the span
     * between its window start and reset, then the known length for its id. Null when the
     * window can't be determined.
     */
    fun windowLengthMs(meter: UsageMeter): Long? {
        meter.windowLengthMs?.takeIf { it > 0 }?.let { return it }
        val start = meter.windowStartEpochMs
        val end = meter.resetsAtEpochMs
        if (start != null && end != null && end > start) return end - start
        return PaceWindows.knownLengthMs(meter.id)
    }

    /**
     * SHORT for windows up to 24 h. Everything else is LONG, including odd cases: spend caps
     * (monthly billing cycle) and meters whose window can't be determined, since an unknown
     * window is far more likely a billing-cycle quota than a rolling short one.
     */
    fun classify(meter: UsageMeter): LimitKind {
        if (meter.isCapBar) return LimitKind.LONG
        val length = windowLengthMs(meter) ?: return LimitKind.LONG
        return if (length <= SHORT_WINDOW_MAX_MS) LimitKind.SHORT else LimitKind.LONG
    }

    fun group(sections: List<SoftwareSection>): List<LimitGroup> {
        val shortEntries = mutableListOf<LimitGroupEntry>()
        val longEntries = mutableListOf<LimitGroupEntry>()
        val noData = mutableListOf<LimitGroupEntry>()
        sections.forEach { section ->
            if (section.meters.isEmpty()) {
                noData += LimitGroupEntry(section, emptyList(), primary = true)
                return@forEach
            }
            val byKind = section.meters.groupBy { classify(it) }
            val short = byKind[LimitKind.SHORT].orEmpty()
            val long = byKind[LimitKind.LONG].orEmpty()
            if (short.isNotEmpty()) shortEntries += LimitGroupEntry(section, short, primary = true)
            if (long.isNotEmpty()) longEntries += LimitGroupEntry(section, long, primary = short.isEmpty())
        }
        return listOf(
            LimitGroup(LimitKind.SHORT, shortEntries),
            LimitGroup(LimitKind.LONG, longEntries),
            LimitGroup(LimitKind.NO_DATA, noData)
        ).filter { it.entries.isNotEmpty() }
    }
}
