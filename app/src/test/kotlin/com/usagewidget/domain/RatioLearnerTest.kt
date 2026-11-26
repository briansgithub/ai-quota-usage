package com.usagewidget.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RatioLearnerTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val t0 = 2_000_000_000_000L
    private val fiveReset = t0 + 4 * hour
    private val weekReset = t0 + 5 * day
    private val codex = RatioLearner.pairs(UsageSnapshot(0L)).first { it.key == "codex" }

    private fun snap(
        at: Long,
        five: Double,
        week: Double,
        fiveAt: Long? = fiveReset,
        weekAt: Long? = weekReset,
        success: Long = at,
        extra: List<UsageMeter> = emptyList()
    ) = UsageSnapshot(
        fetchedAtEpochMs = at,
        providers = listOf(
            ProviderSnapshot(
                id = "openai", displayName = "Codex", status = "OK",
                meters = listOf(
                    UsageMeter("openai.five_hour", "5-hour", usedPercent = five, resetsAtEpochMs = fiveAt),
                    UsageMeter("openai.weekly", "Weekly", usedPercent = week, resetsAtEpochMs = weekAt)
                ) + extra
            )
        ),
        sectionHealth = listOf(SectionHealth("codex", lastAttemptEpochMs = at, lastSuccessEpochMs = success))
    )

    private fun run(vararg snaps: UsageSnapshot): List<PaceSample> =
        snaps.toList().zipWithNext().fold(emptyList()) { acc, (prev, cur) ->
            RatioLearner.observe(acc, prev, cur, cur.fetchedAtEpochMs)
        }

    @Test
    fun deltasWithinOneWindowAccumulateIntoOneSample() {
        val samples = run(snap(t0, 0.0, 40.0), snap(t0 + hour, 30.0, 44.0), snap(t0 + 2 * hour, 60.0, 47.0))
        val s = samples.single()
        assertEquals(60.0, s.fiveDelta, 1e-9)
        assertEquals(7.0, s.longDelta, 1e-9)
    }

    @Test
    fun windowRolloverStartsANewSampleWithoutCrossingIt() {
        val nextFive = fiveReset + 5 * hour
        val samples = run(
            snap(t0, 10.0, 40.0),
            snap(t0 + hour, 50.0, 45.0),
            snap(fiveReset + hour, 20.0, 48.0, fiveAt = nextFive), // reset in between: skipped
            snap(fiveReset + 2 * hour, 70.0, 54.0, fiveAt = nextFive)
        )
        assertEquals(2, samples.size)
        assertEquals(listOf(40.0, 50.0), samples.map { it.fiveDelta }.sorted())
    }

    @Test
    fun manualResetDropIsIgnored() {
        val samples = run(snap(t0, 50.0, 60.0), snap(t0 + hour, 10.0, 5.0))
        assertTrue(samples.isEmpty())
    }

    @Test
    fun staleRoundIsIgnored() {
        val samples = run(snap(t0, 0.0, 40.0), snap(t0 + hour, 30.0, 44.0, success = t0))
        assertTrue(samples.isEmpty())
    }

    @Test
    fun idleWindowStartIsCounted() {
        val samples = run(snap(t0, 0.0, 40.0, fiveAt = null), snap(t0 + hour, 20.0, 43.0))
        assertEquals(20.0, samples.single().fiveDelta, 1e-9)
    }

    @Test
    fun noDataGivesTheStartingGuess() {
        val e = RatioLearner.estimate(emptyList(), codex, 12.0, weekReset, t0)
        assertEquals(12.0, e.rate, 1e-9)
        assertEquals(0, e.samples)
    }

    @Test
    fun largeWindowsDominateSmallNoisyOnes() {
        val samples = listOf(
            PaceSample("codex", 1, 0, fiveDelta = 100.0, longDelta = 20.0, updatedAtMs = t0),
            PaceSample("codex", 2, 0, fiveDelta = 4.0, longDelta = 3.0, updatedAtMs = t0)
        )
        // Ratio of sums with prior 50 pts at 12%: (20 + 3 + 6) / (104 + 50) = 18.8%
        val e = RatioLearner.estimate(samples, codex, 12.0, null, t0)
        assertEquals(100.0 * 29.0 / 154.0, e.rate, 1e-6)
    }

    @Test
    fun recentAndCurrentWeekUsageWeighMore() {
        val old = PaceSample("codex", 1, longResetMs = weekReset - 7 * day, fiveDelta = 100.0, longDelta = 10.0, updatedAtMs = t0 - 7 * day)
        val now = PaceSample("codex", 2, longResetMs = weekReset, fiveDelta = 100.0, longDelta = 30.0, updatedAtMs = t0)
        // weights: old 0.5, current week 1 × 2 = 2
        val e = RatioLearner.estimate(listOf(old, now), codex, 12.0, weekReset, t0)
        val expected = 100.0 * (0.5 * 10 + 2 * 30 + 6) / (0.5 * 100 + 2 * 100 + 50)
        assertEquals(expected, e.rate, 1e-6)
    }

    @Test
    fun tinySamplesAreIgnoredAndRateIsClamped() {
        val tiny = PaceSample("codex", 1, 0, fiveDelta = 2.0, longDelta = 2.0, updatedAtMs = t0)
        assertEquals(12.0, RatioLearner.estimate(listOf(tiny), codex, 12.0, null, t0).rate, 1e-9)
        val huge = PaceSample("codex", 2, 0, fiveDelta = 300.0, longDelta = 400.0, updatedAtMs = t0)
        assertEquals(RatioLearner.LEARNED_MAX, RatioLearner.estimate(listOf(huge), codex, 12.0, null, t0).rate, 1e-9)
    }

    @Test
    fun oldSamplesArePruned() {
        val old = PaceSample("codex", 1, 0, 50.0, 5.0, updatedAtMs = t0 - 29 * day)
        val fresh = PaceSample("codex", 2, 0, 50.0, 5.0, updatedAtMs = t0)
        assertEquals(listOf(fresh), RatioLearner.prune(listOf(old, fresh), t0))
    }

    @Test
    fun codexExtraModelsArePairedByTitle() {
        val s = snap(
            t0, 0.0, 0.0,
            extra = listOf(
                UsageMeter("openai.spark_5", "Spark · 5-hour", usedPercent = 1.0),
                UsageMeter("openai.spark_w", "Spark · Weekly", usedPercent = 1.0)
            )
        )
        val pair = RatioLearner.pairs(s).single { it.key == "codex:Spark" }
        assertEquals("openai.spark_5", pair.fiveId)
        assertEquals("openai.spark_w", pair.longId)
    }

    @Test
    fun pacingUsesTheLearnedRateForThatModel() {
        val section = SoftwareSection(
            SoftwareSectionId.CODEX, ProviderId.OPENAI, ProviderStatus.OK,
            meters = listOf(
                UsageMeter("openai.five_hour", "5-hour", usedPercent = 0.0, remainingPercent = 100.0, resetsAtEpochMs = t0 + 5 * hour),
                UsageMeter("openai.weekly", "Weekly", usedPercent = 60.0, remainingPercent = 40.0, resetsAtEpochMs = t0 + 3 * day)
            )
        )
        val learned = PacingPlanner.forSection(section, t0, PaceRates(12.0, mapOf("codex" to 20.0, "claude" to 5.0)))!!
        assertEquals(20.0, learned.percentPerWindow, 1e-9)
        assertEquals(2, learned.extraWindowsNeeded)
        val manual = PacingPlanner.forSection(section, t0, PaceRates(12.0))!!
        assertEquals(4, manual.extraWindowsNeeded)
    }
}
