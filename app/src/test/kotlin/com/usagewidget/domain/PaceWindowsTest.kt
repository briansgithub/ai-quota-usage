package com.usagewidget.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PaceWindowsTest {
    private val hour = 3_600_000L

    @Test
    fun fiveHourMidpointIsFiftyPercentExpected() {
        val reset = 10_000_000L
        val meter = UsageMeter(
            "openai.five_hour",
            "5-hour",
            usedPercent = 40.0,
            remainingPercent = 60.0,
            resetsAtEpochMs = reset
        )
        val expected = PaceWindows.expectedUsedPercent(meter, reset - PacingPolicy.WINDOW_MS / 2)
        assertNotNull(expected)
        assertEquals(50.0, expected!!, 1e-6)
        assertEquals(PacingPolicy.WINDOW_MS, PaceWindows.knownLengthMs(meter.id))
    }

    @Test
    fun weeklyElapsedFractionUsesSevenDays() {
        val reset = 20_000_000L
        val meter = UsageMeter(
            "openai.weekly",
            "Weekly",
            usedPercent = 12.0,
            remainingPercent = 88.0,
            resetsAtEpochMs = reset
        )
        val now = reset - PacingPolicy.GROK_PERIOD_MS / 10
        val expected = PaceWindows.expectedUsedPercent(meter, now)
        assertEquals(90.0, expected!!, 1e-6)
    }

    @Test
    fun cursorBillingCycleUsesExplicitStartAndEnd() {
        val start = Instant.parse("2026-10-11T00:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-11-11T00:00:00Z").toEpochMilli()
        val now = Instant.parse("2026-11-05T15:30:13Z").toEpochMilli()
        val meter = UsageMeter(
            "cursor.models",
            "Cursor Models",
            usedPercent = 17.0,
            remainingPercent = 83.0,
            resetsAtEpochMs = end,
            windowStartEpochMs = start
        )
        val expected = PaceWindows.expectedUsedPercent(meter, now)
        assertNotNull(expected)
        val frac = (now - start).toDouble() / (end - start) * 100.0
        assertEquals(frac, expected!!, 1e-6)
        assertTrue(expected in 80.0..86.0)
    }

    @Test
    fun unknownMeterWithoutWindowIsNeutral() {
        val meter = UsageMeter("mystery", "Mystery", usedPercent = 40.0)
        assertNull(PaceWindows.bounds(meter))
        assertNull(PaceWindows.expectedUsedPercent(meter, 1))
    }

    @Test
    fun afterResetExpectedIsOneHundred() {
        val reset = 5_000_000L
        val meter = UsageMeter("cursor.grok_bot", "Grok Bot", usedPercent = 80.0, resetsAtEpochMs = reset)
        assertEquals(100.0, PaceWindows.expectedUsedPercent(meter, reset + hour)!!, 1e-6)
    }

    @Test
    fun beforeWindowStartExpectedIsZero() {
        val reset = 5_000_000L
        val meter = UsageMeter("openai.five_hour", "5-hour", usedPercent = 0.0, resetsAtEpochMs = reset)
        val start = reset - PacingPolicy.WINDOW_MS
        assertEquals(0.0, PaceWindows.expectedUsedPercent(meter, start - hour)!!, 1e-6)
    }
}
