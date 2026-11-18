package com.usagewidget.domain

import com.usagewidget.providers.claude.ClaudeMeterIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageRemainingTest {
    private fun meter(id: String, used: Double, title: String = id, cap: Boolean = false, lengthMs: Long? = null) =
        UsageMeter(
            id = id, title = title, usedPercent = used, remainingPercent = Percent.remainingFromUsed(used),
            windowLengthMs = lengthMs, barKind = if (cap) MeterBarKind.CAP else MeterBarKind.PACE
        )

    private fun section(id: SoftwareSectionId, vararg meters: UsageMeter) = SoftwareSection(
        id = id, signInProvider = ProviderId.CURSOR, status = ProviderStatus.OK, meters = meters.toList()
    )

    private fun ids(sections: List<SoftwareSection>, id: SoftwareSectionId) =
        sections.single { it.id == id }.meters.map { it.id }

    private fun shown(vararg sections: SoftwareSection, drop: Boolean = true) =
        UsageRemaining.apply(sections.toList(), enabled = true, dropSpentCards = drop)

    private val claude = section(
        SoftwareSectionId.CLAUDE,
        meter(ClaudeMeterIds.FIVE_HOUR, 20.0),
        meter(ClaudeMeterIds.WEEKLY, 60.0),
        meter(ClaudeMeterIds.WEEKLY_SONNET, 30.0),
        meter(ClaudeMeterIds.WEEKLY_OPUS, 10.0)
    )

    @Test
    fun offReturnsSameSections() {
        val all = listOf(section(SoftwareSectionId.CLAUDE, meter(ClaudeMeterIds.WEEKLY, 100.0)))
        assertSame(all, UsageRemaining.apply(all, enabled = false, dropSpentCards = true))
    }

    @Test
    fun nothingSpentKeepsEverything() {
        val result = shown(claude)
        assertSame(claude, result.single())
    }

    @Test
    fun claudeWeeklySpentHidesWholeCardOnWidget() {
        val spent = claude.copy(meters = claude.meters.map {
            if (it.id == ClaudeMeterIds.WEEKLY) it.copy(usedPercent = 100.0, remainingPercent = 0.0) else it
        })
        assertTrue(shown(spent).isEmpty())
    }

    @Test
    fun dashboardKeepsTheCardWithoutBars() {
        val spent = section(SoftwareSectionId.CLAUDE, meter(ClaudeMeterIds.FIVE_HOUR, 0.0), meter(ClaudeMeterIds.WEEKLY, 104.0))
        val result = shown(spent, drop = false)
        assertEquals(emptyList<String>(), ids(result, SoftwareSectionId.CLAUDE))
    }

    @Test
    fun claudeSonnetSpentHidesOnlySonnet() {
        val spent = claude.copy(meters = claude.meters.map {
            if (it.id == ClaudeMeterIds.WEEKLY_SONNET) it.copy(usedPercent = 100.0, remainingPercent = 0.0) else it
        })
        assertEquals(
            listOf(ClaudeMeterIds.FIVE_HOUR, ClaudeMeterIds.WEEKLY, ClaudeMeterIds.WEEKLY_OPUS),
            ids(shown(spent), SoftwareSectionId.CLAUDE)
        )
    }

    @Test
    fun spentFiveHourAloneStays() {
        val s = section(SoftwareSectionId.CODEX, meter("openai.five_hour", 100.0), meter("openai.weekly", 40.0))
        assertSame(s, shown(s).single())
    }

    @Test
    fun codexWeeklySpentHidesFiveHourButNotCodeReview() {
        val s = section(
            SoftwareSectionId.CODEX,
            meter("openai.five_hour", 0.0),
            meter("openai.weekly", 100.0),
            meter("openai.code_review", 5.0)
        )
        assertEquals(listOf("openai.code_review"), ids(shown(s), SoftwareSectionId.CODEX))
    }

    @Test
    fun codexThirtyDaySpentHidesMainGroup() {
        val s = section(
            SoftwareSectionId.CODEX,
            meter("openai.five_hour", 10.0),
            meter("openai.thirty_day", 100.0),
            meter("openai.spark_5_hour", 0.0, title = "Spark · 5-hour", lengthMs = 5L * 3600_000)
        )
        assertEquals(listOf("openai.spark_5_hour"), ids(shown(s), SoftwareSectionId.CODEX))
    }

    @Test
    fun codexExtraModelGroupHidesItsOwnFiveHourOnly() {
        val s = section(
            SoftwareSectionId.CODEX,
            meter("openai.five_hour", 10.0),
            meter("openai.weekly", 50.0),
            meter("openai.spark_5_hour", 0.0, title = "Spark · 5-hour", lengthMs = 5L * 3600_000),
            meter("openai.spark_weekly_1", 100.0, title = "Spark · Weekly", lengthMs = 7L * 24 * 3600_000)
        )
        assertEquals(listOf("openai.five_hour", "openai.weekly"), ids(shown(s), SoftwareSectionId.CODEX))
    }

    @Test
    fun cursorPoolsHideIndependently() {
        val s = section(
            SoftwareSectionId.CURSOR,
            meter("cursor.models", 100.0),
            meter("cursor.other", 30.0),
            meter("cursor.on_demand", 12.0, cap = true),
            UsageMeter(id = "cursor.plan_reset", title = "Plan reset", resetsAtEpochMs = 5L)
        )
        assertEquals(listOf("cursor.other", "cursor.on_demand", "cursor.plan_reset"), ids(shown(s), SoftwareSectionId.CURSOR))
    }

    @Test
    fun cursorAllSpentDropsCardEvenWithPlanResetRow() {
        val s = section(
            SoftwareSectionId.CURSOR,
            meter("cursor.models", 100.0),
            meter("cursor.other", 101.0),
            meter("cursor.on_demand", 100.0, cap = true),
            UsageMeter(id = "cursor.plan_reset", title = "Plan reset", resetsAtEpochMs = 5L)
        )
        assertTrue(shown(s).isEmpty())
    }

    @Test
    fun grokWeeklySpentKeepsOnDemand() {
        val s = section(
            SoftwareSectionId.GROK_BOT,
            meter(MeterIds.GROK, 100.0),
            meter(MeterIds.GROK_ON_DEMAND, 40.0, cap = true)
        )
        assertEquals(listOf(MeterIds.GROK_ON_DEMAND), ids(shown(s), SoftwareSectionId.GROK_BOT))
    }

    @Test
    fun ninetyNineIsNotSpentAndResetBringsBarsBack() {
        val nearly = section(SoftwareSectionId.CLAUDE, meter(ClaudeMeterIds.FIVE_HOUR, 0.0), meter(ClaudeMeterIds.WEEKLY, 99.0))
        assertSame(nearly, shown(nearly).single())
    }

    @Test
    fun cardsWithoutBarsAreUntouched() {
        val signIn = SoftwareSection(SoftwareSectionId.CLAUDE, ProviderId.CLAUDE, ProviderStatus.NEEDS_SIGN_IN)
        assertSame(signIn, shown(signIn).single())
    }

    @Test
    fun stacksWithWidgetBarPills() {
        val snapshot = UsageSnapshot(
            fetchedAtEpochMs = 1L,
            providers = listOf(
                ProviderSnapshot(
                    id = "openai", displayName = "Codex", status = "OK",
                    meters = listOf(
                        UsageMeter("openai.five_hour", "5-hour", usedPercent = 10.0, remainingPercent = 90.0),
                        UsageMeter("openai.weekly", "Weekly", usedPercent = 100.0, remainingPercent = 0.0),
                        UsageMeter("openai.code_review", "Review", usedPercent = 10.0, remainingPercent = 90.0)
                    )
                )
            )
        )
        val pills = WidgetBars.apply(UsageSections.from(snapshot, 1L), setOf("openai.code_review"))
        val result = UsageRemaining.apply(pills, enabled = true, dropSpentCards = true)
        assertTrue(result.none { it.id == SoftwareSectionId.CODEX })
    }
}
