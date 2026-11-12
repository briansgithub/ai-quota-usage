package com.usagewidget.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacingPlannerTest {
    private val now = 2_000_000_000_000L
    private val hour = 3_600_000L
    private val window = PacingPolicy.WINDOW_MS

    @Test
    fun windowsNeededCeilsRemainingOverPercentPerWindow() {
        assertEquals(0, PacingPlanner.windowsNeeded(0.0, 12.0))
        assertEquals(0, PacingPlanner.windowsNeeded(0.4, 12.0))
        assertEquals(1, PacingPlanner.windowsNeeded(12.0, 12.0))
        assertEquals(4, PacingPlanner.windowsNeeded(48.0, 12.0))
        assertEquals(5, PacingPlanner.windowsNeeded(48.1, 12.0))
        assertEquals(9, PacingPlanner.windowsNeeded(100.0, 12.0))
    }

    @Test
    fun latestStartsWorkBackwardsFromWeeklyReset() {
        val weekly = now + 40 * hour
        assertEquals(
            listOf(weekly - 20 * hour, weekly - 15 * hour, weekly - 10 * hour, weekly - 5 * hour),
            PacingPlanner.latestStarts(weekly, extraWindows = 4)
        )
        assertTrue(PacingPlanner.latestStarts(weekly, 0).isEmpty())
    }

    @Test
    fun availableWindowSlackIsTimeLeftMinusNeededWindows() {
        val weekly = now + 30 * hour
        val result = PacingPlanner.planFiveAndWeekly(
            weeklyRemaining = 48.0,
            weeklyResetEpochMs = weekly,
            fiveHourRemaining = 100.0,
            fiveHourResetEpochMs = now + window,
            nowMs = now,
            percentPerWindow = 12.0
        )
        assertEquals(FiveHourWindowState.AVAILABLE, result.fiveHourState)
        assertEquals(4, result.extraWindowsNeeded)
        assertEquals(4, result.windowsNeeded)
        assertEquals(10 * hour, result.slackMs)
        assertEquals(weekly - 20 * hour, result.nextLatestStartEpochMs)
        assertEquals("Pace: need 4 windows, 10h slack", result.line)
        assertEquals(PaceUrgency.OK, result.urgency)
        assertFalse(result.missedWindow)
        assertEquals(0.0, result.weeklyLostPercent, 0.01)
        assertEquals(weekly - 20 * hour - PacingPolicy.COMFORTABLE_LEAD_MS, result.comfortableStartEpochMs)
        assertEquals(weekly - 20 * hour - PacingPolicy.FEASIBLE_LEAD_MS, result.feasibleStartEpochMs)
    }

    @Test
    fun activeWindowCountsUnusedCapacityAndWaitsForFiveHourReset() {
        val weekly = now + 30 * hour
        val fiveReset = now + hour
        val result = PacingPlanner.planFiveAndWeekly(
            weeklyRemaining = 48.0,
            weeklyResetEpochMs = weekly,
            fiveHourRemaining = 50.0,
            fiveHourResetEpochMs = fiveReset,
            nowMs = now,
            percentPerWindow = 12.0
        )
        assertEquals(FiveHourWindowState.ACTIVE, result.fiveHourState)
        assertEquals(6.0, result.currentWindowDeliverable, 0.01)
        assertEquals(4, result.extraWindowsNeeded)
        assertEquals(5, result.windowsNeeded)
        assertEquals(9 * hour, result.slackMs)
        assertEquals("Pace: need 4 more, 9h slack", result.line)
        assertFalse(result.missedWindow)
    }

    @Test
    fun exhaustedWindowWaitsForFiveHourResetThenNeedsFullWindows() {
        val weekly = now + 20 * hour
        val fiveReset = now + 2 * hour
        val result = PacingPlanner.planFiveAndWeekly(
            weeklyRemaining = 24.0,
            weeklyResetEpochMs = weekly,
            fiveHourRemaining = 0.0,
            fiveHourResetEpochMs = fiveReset,
            nowMs = now,
            percentPerWindow = 12.0
        )
        assertEquals(FiveHourWindowState.EXHAUSTED, result.fiveHourState)
        assertEquals(2, result.extraWindowsNeeded)
        assertEquals(8 * hour, result.slackMs)
        assertEquals("Pace: need 2 windows, 8h slack", result.line)
        assertEquals(PaceUrgency.TIGHT, result.urgency)
        assertFalse(result.missedWindow)
    }

    @Test
    fun missedWindowWhenLatestStartPassesWithFiveHourStillAvailable() {
        val weekly = now + 30 * hour
        val latestFirst = weekly - 20 * hour
        val afterMiss = latestFirst + 1_000L
        val result = PacingPlanner.planFiveAndWeekly(
            weeklyRemaining = 48.0,
            weeklyResetEpochMs = weekly,
            fiveHourRemaining = 100.0,
            fiveHourResetEpochMs = afterMiss + window,
            nowMs = afterMiss,
            percentPerWindow = 12.0
        )
        assertTrue(result.missedWindow)
        assertEquals(latestFirst, result.nextLatestStartEpochMs)
    }

    @Test
    fun activeWindowIsNotAMissEvenIfLatestStartHasPassed() {
        val weekly = now + 30 * hour
        val afterLatest = weekly - 20 * hour + 1_000L
        val result = PacingPlanner.planFiveAndWeekly(
            weeklyRemaining = 48.0,
            weeklyResetEpochMs = weekly,
            fiveHourRemaining = 40.0,
            fiveHourResetEpochMs = afterLatest + 2 * hour,
            nowMs = afterLatest,
            percentPerWindow = 12.0
        )
        assertEquals(FiveHourWindowState.ACTIVE, result.fiveHourState)
        assertFalse(result.missedWindow)
    }

    @Test
    fun negativeSlackEstimatesWeeklyUsageThatWillBeLost() {
        val weekly = now + 10 * hour
        val result = PacingPlanner.planFiveAndWeekly(
            weeklyRemaining = 48.0,
            weeklyResetEpochMs = weekly,
            fiveHourRemaining = 100.0,
            fiveHourResetEpochMs = now + window,
            nowMs = now,
            percentPerWindow = 12.0
        )
        assertEquals(-10 * hour, result.slackMs)
        assertEquals(24.0, result.weeklyLostPercent, 0.01)
        assertEquals(PaceUrgency.IMPOSSIBLE, result.urgency)
        assertTrue(result.line.contains("likely lost"))
        assertTrue(result.line.contains("24%"))
    }

    @Test
    fun finishThisWindowWhenActiveCapacityCoversRemainingWeekly() {
        val weekly = now + 12 * hour
        val result = PacingPlanner.planFiveAndWeekly(
            weeklyRemaining = 5.0,
            weeklyResetEpochMs = weekly,
            fiveHourRemaining = 80.0,
            fiveHourResetEpochMs = now + 3 * hour,
            nowMs = now,
            percentPerWindow = 12.0
        )
        assertEquals(0, result.extraWindowsNeeded)
        assertEquals(1, result.windowsNeeded)
        assertEquals("Pace: finish this window, 9h slack", result.line)
    }

    @Test
    fun onTrackWhenNothingLeftToBurn() {
        val result = PacingPlanner.planFiveAndWeekly(
            weeklyRemaining = 0.0,
            weeklyResetEpochMs = now + 40 * hour,
            fiveHourRemaining = 100.0,
            fiveHourResetEpochMs = now + window,
            nowMs = now
        )
        assertEquals(0, result.extraWindowsNeeded)
        assertEquals("Pace: on track", result.line)
        assertEquals(PaceUrgency.OK, result.urgency)
    }

    @Test
    fun manualResetExpiringBeforeNineWindowsWarnsUnused() {
        val credits = listOf(
            ManualResetCredit(expiresAtEpochMs = now + 8 * hour, status = "available", title = "Full reset")
        )
        val paces = PacingPlanner.planManuals(credits, now, percentPerWindow = 12.0)
        assertEquals(1, paces.size)
        assertEquals(9, paces[0].windowsNeeded)
        assertEquals(9 * window, paces[0].timeNeededMs)
        assertTrue(paces[0].willExpireUnused)
        assertEquals(88.0, paces[0].unusedPercent, 0.01)
        assertTrue(paces[0].warning!!.contains("88%"))
    }

    @Test
    fun manualResetWithEnoughTimeDoesNotWarn() {
        val credits = listOf(
            ManualResetCredit(expiresAtEpochMs = now + 50 * hour, status = "available")
        )
        val paces = PacingPlanner.planManuals(credits, now, 12.0)
        assertFalse(paces[0].willExpireUnused)
        assertEquals(0.0, paces[0].unusedPercent, 0.01)
        assertEquals(null, paces[0].warning)
    }

    @Test
    fun usedManualCreditIsIgnored() {
        val credits = listOf(
            ManualResetCredit(expiresAtEpochMs = now + hour, status = "used")
        )
        assertTrue(PacingPlanner.planManuals(credits, now).isEmpty())
    }

    @Test
    fun weeklyOnlyWarnsWhenRemainingIsAheadOfEvenPace() {
        val period = PacingPolicy.GROK_PERIOD_MS
        val resetAt = now + period / 10
        val result = PacingPlanner.planWeeklyOnly(
            remaining = 50.0,
            resetAtEpochMs = resetAt,
            nowMs = now,
            periodMs = period,
            sectionId = "grok_bot"
        )
        assertTrue(result.shouldWarnUnused)
        assertEquals(PaceKind.WEEKLY_ONLY, result.kind)
        assertTrue(result.wasteGap >= 39.0)
        assertTrue(result.line.contains("above even pace"))
    }

    @Test
    fun weeklyOnlyOnTrackWhenRemainingMatchesTimeLeft() {
        val period = PacingPolicy.GROK_PERIOD_MS
        val resetAt = now + period / 10
        val result = PacingPlanner.planWeeklyOnly(
            remaining = 10.0,
            resetAtEpochMs = resetAt,
            nowMs = now,
            periodMs = period,
            sectionId = "grok_bot"
        )
        assertFalse(result.shouldWarnUnused)
        assertEquals(PaceUrgency.OK, result.urgency)
        assertTrue(result.line.contains("10% left"))
    }

    @Test
    fun forSectionUsesCodexFiveHourAndWeekly() {
        val weekly = now + 30 * hour
        val section = SoftwareSection(
            id = SoftwareSectionId.CODEX,
            signInProvider = ProviderId.OPENAI,
            status = ProviderStatus.OK,
            meters = listOf(
                UsageMeter("openai.five_hour", "5-hour", remainingPercent = 100.0, resetsAtEpochMs = now + window),
                UsageMeter("openai.weekly", "Weekly", remainingPercent = 48.0, resetsAtEpochMs = weekly)
            )
        )
        val result = PacingPlanner.forSection(section, now, 12.0)!!
        assertEquals(PaceKind.FIVE_AND_WEEKLY, result.kind)
        assertEquals(4, result.extraWindowsNeeded)
        assertEquals("Pace: need 4 windows, 10h slack", result.line)
    }

    @Test
    fun criticalSlackUnderTwoHours() {
        val result = PacingPlanner.planFiveAndWeekly(
            weeklyRemaining = 12.0,
            weeklyResetEpochMs = now + 6 * hour,
            fiveHourRemaining = 100.0,
            fiveHourResetEpochMs = now + window,
            nowMs = now,
            percentPerWindow = 12.0
        )
        assertEquals(hour, result.slackMs)
        assertEquals(PaceUrgency.CRITICAL, result.urgency)
    }
}
