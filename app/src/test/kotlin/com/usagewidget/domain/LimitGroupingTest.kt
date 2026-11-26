package com.usagewidget.domain

import com.usagewidget.providers.claude.ClaudeMeterIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitGroupingTest {
    private val hour = 60L * 60 * 1000

    private fun meter(
        id: String,
        lengthMs: Long? = null,
        cap: Boolean = false,
        start: Long? = null,
        reset: Long? = null
    ) = UsageMeter(
        id = id, title = id, usedPercent = 10.0, remainingPercent = 90.0,
        windowLengthMs = lengthMs, windowStartEpochMs = start, resetsAtEpochMs = reset,
        barKind = if (cap) MeterBarKind.CAP else MeterBarKind.PACE
    )

    private fun section(
        id: SoftwareSectionId,
        vararg meters: UsageMeter,
        status: ProviderStatus = ProviderStatus.OK
    ) = SoftwareSection(id = id, signInProvider = ProviderId.CURSOR, status = status, meters = meters.toList())

    private val claude = section(
        SoftwareSectionId.CLAUDE,
        meter(ClaudeMeterIds.FIVE_HOUR),
        meter(ClaudeMeterIds.WEEKLY),
        meter(ClaudeMeterIds.WEEKLY_SONNET)
    )
    private val codex = section(
        SoftwareSectionId.CODEX,
        meter("openai.five_hour"),
        meter("openai.weekly"),
        meter("openai.thirty_day")
    )

    @Test
    fun classifiesByWindowLength() {
        assertEquals(LimitKind.SHORT, LimitGrouping.classify(meter("x", lengthMs = 5 * hour)))
        assertEquals(LimitKind.SHORT, LimitGrouping.classify(meter("x", lengthMs = 24 * hour)))
        assertEquals(LimitKind.LONG, LimitGrouping.classify(meter("x", lengthMs = 24 * hour + 1)))
        assertEquals(LimitKind.LONG, LimitGrouping.classify(meter("x", lengthMs = 7 * 24 * hour)))
        assertEquals(LimitKind.LONG, LimitGrouping.classify(meter("x", lengthMs = 30 * 24 * hour)))
    }

    @Test
    fun lengthFallsBackToWindowSpanThenKnownIdThenLong() {
        assertEquals(LimitKind.SHORT, LimitGrouping.classify(meter("x", start = 0, reset = 5 * hour)))
        assertEquals(LimitKind.LONG, LimitGrouping.classify(meter("x", start = 0, reset = 168 * hour)))
        assertEquals(LimitKind.SHORT, LimitGrouping.classify(meter(ClaudeMeterIds.FIVE_HOUR)))
        assertEquals(LimitKind.LONG, LimitGrouping.classify(meter(ClaudeMeterIds.WEEKLY)))
        // Unknown window: assumed to be a billing-cycle quota.
        assertEquals(LimitKind.LONG, LimitGrouping.classify(meter("mystery")))
    }

    @Test
    fun explicitLengthBeatsKnownIdAndCapBarsAreLong() {
        assertEquals(LimitKind.LONG, LimitGrouping.classify(meter(ClaudeMeterIds.FIVE_HOUR, lengthMs = 48 * hour)))
        assertEquals(LimitKind.LONG, LimitGrouping.classify(meter("cursor.on_demand", lengthMs = 2 * hour, cap = true)))
    }

    @Test
    fun groupsLongThenShortThenSubGroupsBySoftwareInGivenOrderWithoutPaceData() {
        val groups = LimitGrouping.group(listOf(codex, claude))
        assertEquals(listOf(LimitKind.LONG, LimitKind.SHORT), groups.map { it.kind })
        val short = groups[1]
        assertEquals(listOf(SoftwareSectionId.CODEX, SoftwareSectionId.CLAUDE), short.entries.map { it.section.id })
        assertEquals(listOf("openai.five_hour"), short.entries[0].meters.map { it.id })
        assertEquals(listOf(ClaudeMeterIds.FIVE_HOUR), short.entries[1].meters.map { it.id })
        val long = groups[0]
        assertEquals(listOf("openai.weekly", "openai.thirty_day"), long.entries[0].meters.map { it.id })
        assertEquals(listOf(ClaudeMeterIds.WEEKLY, ClaudeMeterIds.WEEKLY_SONNET), long.entries[1].meters.map { it.id })
    }

    @Test
    fun everyMeterAppearsExactlyOnce() {
        val all = listOf(claude, codex)
        val grouped = LimitGrouping.group(all).flatMap { g -> g.entries.flatMap { it.meters } }
        assertEquals(all.flatMap { it.meters }.map { it.id }.sorted(), grouped.map { it.id }.sorted())
    }

    @Test
    fun cardLevelLinesGoOnTheFirstEntryOnly() {
        val groups = LimitGrouping.group(listOf(claude))
        assertEquals(listOf(LimitKind.LONG, LimitKind.SHORT), groups.map { it.kind })
        assertTrue(groups[0].entries.single().primary)
        assertFalse(groups[1].entries.single().primary)
    }

    @Test
    fun softwareWithOnlyLongBarsIsPrimaryThere() {
        val cursor = section(SoftwareSectionId.CURSOR, meter("cursor.models"), meter("cursor.on_demand", cap = true))
        val groups = LimitGrouping.group(listOf(cursor))
        assertEquals(listOf(LimitKind.LONG), groups.map { it.kind })
        assertTrue(groups.single().entries.single().primary)
    }

    @Test
    fun emptyGroupsAreOmitted() {
        val groups = LimitGrouping.group(
            listOf(section(SoftwareSectionId.CLAUDE, meter(ClaudeMeterIds.FIVE_HOUR)))
        )
        assertEquals(listOf(LimitKind.SHORT), groups.map { it.kind })
    }

    @Test
    fun softwareWithoutBarsLandsInTrailingNeedsAttentionGroup() {
        val signedOut = section(SoftwareSectionId.GEMINI, status = ProviderStatus.NEEDS_SIGN_IN)
        val groups = LimitGrouping.group(listOf(signedOut, claude))
        assertEquals(listOf(LimitKind.LONG, LimitKind.SHORT, LimitKind.NO_DATA), groups.map { it.kind })
        val last = groups.last().entries.single()
        assertEquals(SoftwareSectionId.GEMINI, last.section.id)
        assertTrue(last.primary)
        assertTrue(last.meters.isEmpty())
    }

    @Test
    fun noSectionsMeansNoGroups() {
        assertTrue(LimitGrouping.group(emptyList()).isEmpty())
    }

    // --- pace ordering. Fixed clock: `now`; a window of length L resetting at now + (L - elapsed)
    // has expected used = elapsed / L.
    private val now = 1_000_000_000_000L

    /** A meter [usedPct] used in a window of [lengthMs] that is [elapsedPct] percent elapsed. */
    private fun paced(id: String, lengthMs: Long, elapsedPct: Double, usedPct: Double) = UsageMeter(
        id = id, title = id, usedPercent = usedPct, remainingPercent = 100.0 - usedPct,
        windowLengthMs = lengthMs,
        windowStartEpochMs = now - (lengthMs * elapsedPct / 100.0).toLong(),
        resetsAtEpochMs = now + (lengthMs * (100.0 - elapsedPct) / 100.0).toLong()
    )

    private val week = 7 * 24 * hour

    @Test
    fun paceDeltaIsUsedMinusEvenPaceAndNegativeMeansBehind() {
        assertEquals(-30.0, LimitGrouping.paceDelta(paced("a", week, 50.0, 20.0), now)!!, 0.01)
        assertEquals(30.0, LimitGrouping.paceDelta(paced("a", week, 50.0, 80.0), now)!!, 0.01)
        assertNull(LimitGrouping.paceDelta(meter("cursor.on_demand", cap = true), now))
        assertNull(LimitGrouping.paceDelta(meter("mystery"), now))
    }

    @Test
    fun barsBehindPaceComeFirstThenOnOrAheadThenNoPaceData() {
        val s = section(
            SoftwareSectionId.CLAUDE,
            meter("nopace"),
            paced("ahead", week, 50.0, 80.0),
            paced("behind1", week, 50.0, 40.0),
            paced("behind2", week, 50.0, 10.0),
            paced("exact", week, 50.0, 50.0)
        )
        val ids = LimitGrouping.group(listOf(s), now).single().entries.single().meters.map { it.id }
        // Behind pace in original relative order, then on/ahead in original order, then no data.
        assertEquals(listOf("behind1", "behind2", "ahead", "exact", "nopace"), ids)
    }

    @Test
    fun spendCapsGoAfterBarsWithPaceData() {
        val s = section(
            SoftwareSectionId.CURSOR,
            meter("cursor.on_demand", cap = true),
            paced("cursor.models", week, 50.0, 90.0)
        )
        val ids = LimitGrouping.group(listOf(s), now).single().entries.single().meters.map { it.id }
        assertEquals(listOf("cursor.models", "cursor.on_demand"), ids)
    }

    @Test
    fun softwareWithTheMostBehindBarComesFirstTiesKeepSoftwareOrder() {
        val a = section(SoftwareSectionId.CURSOR, paced("a", week, 50.0, 45.0)) // -5
        val b = section(SoftwareSectionId.CODEX, paced("b", week, 50.0, 10.0), paced("b2", week, 50.0, 90.0)) // -40
        val c = section(SoftwareSectionId.CLAUDE, paced("c", week, 50.0, 95.0)) // +45
        val d = section(SoftwareSectionId.GEMINI, meter("d")) // no pace data
        val e = section(SoftwareSectionId.GROK_BOT, paced("e", week, 50.0, 45.0)) // -5, ties with a
        val order = LimitGrouping.group(listOf(a, d, c, b, e), now).single().entries.map { it.section.id }
        assertEquals(
            listOf(SoftwareSectionId.CODEX, SoftwareSectionId.CURSOR, SoftwareSectionId.GROK_BOT, SoftwareSectionId.CLAUDE, SoftwareSectionId.GEMINI),
            order
        )
    }

    @Test
    fun paceOrderingAppliesWithinEachLimitGroupIndependently() {
        val fiveHour = 5 * hour
        val x = section(
            SoftwareSectionId.CLAUDE,
            paced("x.short", fiveHour, 50.0, 90.0), // ahead in short
            paced("x.long", week, 50.0, 10.0)       // behind in long
        )
        val y = section(
            SoftwareSectionId.CODEX,
            paced("y.short", fiveHour, 50.0, 10.0), // behind in short
            paced("y.long", week, 50.0, 90.0)       // ahead in long
        )
        val groups = LimitGrouping.group(listOf(x, y), now)
        assertEquals(listOf(LimitKind.LONG, LimitKind.SHORT), groups.map { it.kind })
        assertEquals(listOf(SoftwareSectionId.CLAUDE, SoftwareSectionId.CODEX), groups[0].entries.map { it.section.id })
        assertEquals(listOf(SoftwareSectionId.CODEX, SoftwareSectionId.CLAUDE), groups[1].entries.map { it.section.id })
    }

    @Test
    fun primaryFollowsDisplayOrderNotSoftwareSortOrder() {
        val x = section(SoftwareSectionId.CLAUDE, paced("x.short", 5 * hour, 50.0, 10.0), paced("x.long", week, 50.0, 90.0))
        val groups = LimitGrouping.group(listOf(x), now)
        assertTrue(groups[0].entries.single().primary) // Long group is displayed first
        assertFalse(groups[1].entries.single().primary)
    }

    @Test
    fun byLimitIsOffByDefault() {
        assertFalse(DisplayPreferences().groupByLimit)
    }
}
