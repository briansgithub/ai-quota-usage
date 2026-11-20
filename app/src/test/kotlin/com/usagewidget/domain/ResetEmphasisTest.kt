package com.usagewidget.domain

import com.usagewidget.util.NyTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

class ResetSegmentLookTest {
    private val now = 1_000_000_000_000L

    @Test
    fun plainSegmentsIgnoreUrgency() {
        for (urgency in ResetUrgency.values()) {
            assertEquals(ResetSegmentLook.Plain, ResetSegmentLook.of(emphasized = false, urgency = urgency))
        }
    }

    @Test
    fun emphasizedSegmentsCarryUrgencyColourSizeAndBold() {
        val imminent = ResetSegmentLook.of(emphasized = true, urgency = ResetUrgency.IMMINENT)
        assertEquals(ResetSegmentLook(ResetUrgency.IMMINENT, bold = true, enlarged = true), imminent)
        val relaxed = ResetSegmentLook.of(emphasized = true, urgency = ResetUrgency.RELAXED)
        assertEquals(ResetSegmentLook(ResetUrgency.RELAXED, bold = true, enlarged = false), relaxed)
    }

    @Test
    fun onlyClockAndDurationOfTheResetLineAreStyled() {
        val resetAt = now + 3L * 3_600_000
        val urgency = ResetEmphasis.of(resetAt, now)
        assertEquals(ResetUrgency.IMMINENT, urgency)
        val segments = NyTime.formatResetSegments(resetAt, now)
        val styled = segments.filter { ResetSegmentLook.of(it.emphasized, urgency) != ResetSegmentLook.Plain }
        // Exactly two runs: the clock time and the duration.
        assertEquals(2, styled.size)
        assertEquals("3h", styled[1].text)
        // "Reset: <day> ", " (" and ")" stay plain.
        val plain = segments.filter { it !in styled }.map { it.text }
        assertEquals(3, plain.size)
        assertTrue(plain[0].startsWith("Reset: "))
        assertEquals(" (", plain[1])
        assertEquals(")", plain[2])
    }
}
