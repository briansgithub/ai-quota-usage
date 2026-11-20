package com.usagewidget.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshScheduleTest {
    @Test
    fun nextIsLastPeriodicPlusFifteenMinutes() {
        val last = 1_000_000L
        val next = RefreshSchedule.nextEpochMs(last, armedEpochMs = 0L, nowMs = last + 1_000L)
        assertEquals(last + RefreshSchedule.INTERVAL_MS, next)
        assertEquals(15L * 60_000, RefreshSchedule.INTERVAL_MS)
    }

    @Test
    fun remainingIsClampedAtZero() {
        val next = 5_000L
        assertEquals(2_000L, RefreshSchedule.remainingMs(next, nowMs = 3_000L))
        assertEquals(0L, RefreshSchedule.remainingMs(next, nowMs = 5_000L))
        assertEquals(0L, RefreshSchedule.remainingMs(next, nowMs = 9_000L))
        assertEquals(0L, RefreshSchedule.remainingMs(0L, nowMs = 9_000L))
    }

    @Test
    fun countdownFormatsMmSsAndDoesNotGoNegative() {
        assertEquals("next 07:42", RefreshSchedule.formatCountdown((7 * 60 + 42) * 1_000L))
        assertEquals("next 15:00", RefreshSchedule.formatCountdown(RefreshSchedule.INTERVAL_MS))
        assertEquals("next 00:00", RefreshSchedule.formatCountdown(0L))
        assertEquals("next 00:00", RefreshSchedule.formatCountdown(-5_000L))
    }

    @Test
    fun chronometerBaseIsElapsedRealtimePlusRemaining() {
        val base = RefreshSchedule.chronometerBaseElapsedRealtime(
            nextEpochMs = 20_000L,
            nowEpochMs = 5_000L,
            elapsedRealtime = 100_000L
        )
        assertEquals(115_000L, base)
    }

    @Test
    fun wakeRefreshesOnlyStaleData() {
        val now = 10_000_000L
        val stale = RefreshSchedule.WAKE_STALE_AFTER_MS
        assertEquals(false, RefreshSchedule.shouldRefreshOnWake(now - stale + 1, 0L, now))
        assertEquals(true, RefreshSchedule.shouldRefreshOnWake(now - stale, 0L, now))
        assertEquals(true, RefreshSchedule.shouldRefreshOnWake(0L, 0L, now))
    }

    @Test
    fun wakeRequestsAreThrottled() {
        val now = 10_000_000L
        val old = now - 3_600_000L
        assertEquals(false, RefreshSchedule.shouldRefreshOnWake(old, now - 30_000L, now))
        assertEquals(true, RefreshSchedule.shouldRefreshOnWake(old, now - RefreshSchedule.WAKE_MIN_GAP_MS, now))
    }

    @Test
    fun lastSuccessIsNewestSectionSuccess() {
        val snapshot = UsageSnapshot(
            fetchedAtEpochMs = 9L,
            sectionHealth = listOf(
                SectionHealth(sectionId = "cursor", lastSuccessEpochMs = 100L),
                SectionHealth(sectionId = "codex", lastSuccessEpochMs = 300L)
            )
        )
        assertEquals(300L, RefreshSchedule.lastSuccessEpochMs(snapshot))
        assertEquals(0L, RefreshSchedule.lastSuccessEpochMs(UsageSnapshot(fetchedAtEpochMs = 9L)))
    }
}
