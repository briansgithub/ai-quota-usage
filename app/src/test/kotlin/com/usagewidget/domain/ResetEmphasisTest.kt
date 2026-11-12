package com.usagewidget.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ResetEmphasisTest {
    private val now = 1_000_000_000_000L

    @Test
    fun moreThanTwoDaysIsRelaxed() {
        assertEquals(
            ResetUrgency.RELAXED,
            ResetEmphasis.of(now + 49L * 3_600_000, now)
        )
        assertEquals(
            ResetUrgency.RELAXED,
            ResetEmphasis.of(now + 3L * 86_400_000, now)
        )
    }

    @Test
    fun betweenOneAndTwoDaysIsSoon() {
        assertEquals(
            ResetUrgency.SOON,
            ResetEmphasis.of(now + 36L * 3_600_000, now)
        )
        assertEquals(
            ResetUrgency.SOON,
            ResetEmphasis.of(now + ResetEmphasis.RELAXED_AFTER_MS, now)
        )
    }

    @Test
    fun underTwentyFourHoursIsNear() {
        assertEquals(
            ResetUrgency.NEAR,
            ResetEmphasis.of(now + 23L * 3_600_000, now)
        )
        assertEquals(
            ResetUrgency.NEAR,
            ResetEmphasis.of(now + 6L * 3_600_000, now)
        )
    }

    @Test
    fun fiveHoursOrLessIsImminent() {
        assertEquals(
            ResetUrgency.IMMINENT,
            ResetEmphasis.of(now + ResetEmphasis.IMMINENT_AFTER_MS, now)
        )
        assertEquals(
            ResetUrgency.IMMINENT,
            ResetEmphasis.of(now + 30L * 60_000, now)
        )
        assertEquals(ResetUrgency.IMMINENT, ResetEmphasis.of(now - 1_000L, now))
        assertEquals(ResetUrgency.RELAXED, ResetEmphasis.of(null, now))
    }
}
