package com.usagewidget.domain

/** Rate-limit window length class used by the widget's "group by limit" mode. */
enum class LimitKind(val title: String) {
    /** Windows of 24 hours or less (5-hour sessions, daily caps). */
    SHORT("Short limits"),

    /** Everything longer: weekly, 30-day, monthly billing cycles, and spend caps. */
    LONG("Long limits"),

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
 * Regroups software cards by rate-limit window length: long windows first, then short, then a
 * trailing group for software with nothing to show. Order inside groups: see [LimitGrouping.group].
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

    /**
     * Used-% minus even-pace expected-% (the widget's own bar-color delta, see [UsedGradient]);
     * negative means behind even pace (less used than the clock allows, red bar). Null when
     * there is no pace marker: spend caps, unknown windows, or no used figure.
     */
    fun paceDelta(meter: UsageMeter, nowMs: Long): Double? {
        if (meter.isCapBar) return null
        val used = meter.usedPercent ?: meter.displayUsedPercent?.toDouble() ?: return null
        val expected = PaceWindows.expectedUsedPercent(meter, nowMs) ?: return null
        return used - expected
    }

    /** 0 = behind even pace, 1 = on pace or ahead, 2 = no pace data. */
    private fun paceRank(meter: UsageMeter, nowMs: Long): Int {
        val delta = paceDelta(meter, nowMs) ?: return 2
        return if (delta < 0.0) 0 else 1
    }

    /**
     * Groups are ordered Long, Short, then NO_DATA. Within a group, software are ordered by
     * their most-behind bar (lowest [paceDelta]; software with no pace data last), ties keeping
     * the incoming [sections] order. Within a software, bars behind even pace come first, then
     * bars on or ahead of pace, then bars without pace data, each keeping their original order.
     */
    fun group(
        sections: List<SoftwareSection>,
        nowMs: Long = System.currentTimeMillis()
    ): List<LimitGroup> {
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
            if (short.isNotEmpty()) shortEntries += LimitGroupEntry(section, short.byPace(nowMs), primary = false)
            if (long.isNotEmpty()) longEntries += LimitGroupEntry(section, long.byPace(nowMs), primary = false)
        }
        val seen = mutableSetOf<SoftwareSectionId>()
        fun List<LimitGroupEntry>.ordered() = sortedBy { entry ->
            entry.meters.mapNotNull { paceDelta(it, nowMs) }.minOrNull() ?: Double.POSITIVE_INFINITY
        }.map { it.copy(primary = seen.add(it.section.id)) }
        // Long first: card-level lines (primary) land under each software's first displayed entry.
        val long = longEntries.ordered()
        val short = shortEntries.ordered()
        return listOf(
            LimitGroup(LimitKind.LONG, long),
            LimitGroup(LimitKind.SHORT, short),
            LimitGroup(LimitKind.NO_DATA, noData)
        ).filter { it.entries.isNotEmpty() }
    }

    private fun List<UsageMeter>.byPace(nowMs: Long): List<UsageMeter> = sortedBy { paceRank(it, nowMs) }
}
