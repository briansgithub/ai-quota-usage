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
}
