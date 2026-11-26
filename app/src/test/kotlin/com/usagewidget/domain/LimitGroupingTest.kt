package com.usagewidget.domain

import com.usagewidget.providers.claude.ClaudeMeterIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun groupsShortThenLongThenSubGroupsBySoftwareInGivenOrder() {
        val groups = LimitGrouping.group(listOf(codex, claude))
        assertEquals(listOf(LimitKind.SHORT, LimitKind.LONG), groups.map { it.kind })
        val short = groups[0]
        assertEquals(listOf(SoftwareSectionId.CODEX, SoftwareSectionId.CLAUDE), short.entries.map { it.section.id })
        assertEquals(listOf("openai.five_hour"), short.entries[0].meters.map { it.id })
        assertEquals(listOf(ClaudeMeterIds.FIVE_HOUR), short.entries[1].meters.map { it.id })
        val long = groups[1]
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
        assertEquals(listOf(LimitKind.SHORT, LimitKind.LONG, LimitKind.NO_DATA), groups.map { it.kind })
        val last = groups.last().entries.single()
        assertEquals(SoftwareSectionId.GEMINI, last.section.id)
        assertTrue(last.primary)
        assertTrue(last.meters.isEmpty())
    }

    @Test
    fun noSectionsMeansNoGroups() {
        assertTrue(LimitGrouping.group(emptyList()).isEmpty())
    }

    @Test
    fun byLimitIsOffByDefault() {
        assertFalse(DisplayPreferences().groupByLimit)
    }
}
