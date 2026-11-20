package com.usagewidget.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class NyTimeTest {
    private val now = Instant.parse("2026-11-05T15:30:26Z").toEpochMilli()

    @Test
    fun relativeHoursAndMinutes() {
        val ms = NyTime.parseRelative("Resets in 4h 50m", now)!!
        assertEquals(4 * 60 + 50, (ms - now) / 60_000)
    }

    @Test
    fun relativeDaysAndHours() {
        val ms = NyTime.parseRelative("Resets in 6d 23h", now)!!
        assertEquals(6 * 24 + 23, (ms - now) / 3_600_000)
    }

    @Test
    fun monthDayUsesNewYork() {
        val ms = NyTime.parseMonthDay("Nov 11", now)!!
        val zoned = Instant.ofEpochMilli(ms).atZone(ZoneId.of("America/New_York"))
        assertEquals(11, zoned.dayOfMonth)
        assertEquals(2026, zoned.year)
    }

    @Test
    fun unixSecondsVsMillis() {
        assertEquals(1793910026_000L, NyTime.parseTimestamp(1793910026.0, now))
        assertEquals(1793910026000L, NyTime.parseTimestamp(1793910026000.0, now))
    }

    @Test
    fun durationFormatsHoursMinutesAndDays() {
        assertEquals("1h 20m", NyTime.formatDuration(80L * 60_000))
        assertEquals("15m", NyTime.formatDuration(15L * 60_000))
        assertEquals("10h", NyTime.formatDuration(10L * 3_600_000))
        assertEquals("1d 4h", NyTime.formatDuration((24 + 4L) * 3_600_000))
        assertEquals("0m", NyTime.formatDuration(0L))
    }

    @Test
    fun countdownCopy() {
        val inFourHours = now + 4 * 3_600_000 + 50 * 60_000
        assertTrue(NyTime.countdown(inFourHours, now).contains("4h"))
    }

    @Test
    fun resetLineUsesNyWeekdayClockAndDaysHours() {
        val thuAfternoon = Instant.parse("2026-11-05T20:20:26Z").toEpochMilli()
        assertEquals("Reset: Thu 3:20 PM (4h)", NyTime.formatResetLine(thuAfternoon, now))
        val nextThu = Instant.parse("2026-11-12T14:30:26Z").toEpochMilli()
        assertEquals("Reset: Thu 9:30 AM (6d 23h)", NyTime.formatResetLine(nextThu, now))
        val wedThreePm = Instant.parse("2026-11-11T20:00:00Z").toEpochMilli()
        assertEquals("Reset: Wed 3 PM (6d 4h)", NyTime.formatResetLine(wedThreePm, now))
        val thuTwoThirty = Instant.parse("2026-11-05T19:30:26Z").toEpochMilli()
        assertEquals("Reset: Thu 2:30 PM (4h)", NyTime.formatResetLine(thuTwoThirty, now))
        assertEquals("06/11", NyTime.formatDayMonth(Instant.parse("2026-11-06T21:36:00Z").toEpochMilli()))
    }

    @Test
    fun resetSegmentsEmphasizeOnlyClockAndDuration() {
        val wedThreePm = Instant.parse("2026-11-11T20:00:00Z").toEpochMilli()
        val segments = NyTime.formatResetSegments(wedThreePm, now)
        assertEquals(
            listOf("Reset: Wed " to false, "3 PM" to true, " (" to false, "6d 4h" to true, ")" to false),
            segments.map { it.text to it.emphasized }
        )
        assertEquals(NyTime.formatResetLine(wedThreePm, now), segments.joinToString("") { it.text })
        val overdue = NyTime.formatResetSegments(now - 60_000L, now)
        assertEquals("due", overdue[3].text)
        assertTrue(overdue[3].emphasized)
    }

    @Test
    fun overlayUtcMidnightDateWithNyClockFromPeriodStart() {
        val next = Instant.parse("2026-11-06T00:00:00.000Z").toEpochMilli()
        val start = Instant.parse("2026-10-30T13:00:00.000Z").toEpochMilli()
        assertTrue(NyTime.isUtcMidnight(next))
        val combined = NyTime.withClockIfDateTruncated(next, start)!!
        val ny = Instant.ofEpochMilli(combined).atZone(ZoneId.of("America/New_York"))
        assertEquals(6, ny.dayOfMonth)
        assertEquals(9, ny.hour)
        assertEquals(0, ny.minute)
        assertEquals("Reset: Fri 9 AM (22h)", NyTime.formatResetLine(combined, now))
        assertEquals(next, NyTime.withClockIfDateTruncated(next, next))
        val timed = Instant.parse("2026-11-06T18:43:19.645Z").toEpochMilli()
        assertEquals(timed, NyTime.withClockIfDateTruncated(timed, start))
    }

    @Test
    fun grokResetOnNov13_2026RollsDateOnlyNextResetForwardAWeek() {
        val zone = ZoneId.of("America/New_York")
        val resetInstant = java.time.ZonedDateTime.of(2026, 11, 13, 14, 18, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
        val nextDateOnly = Instant.parse("2026-11-13T00:00:00.000Z").toEpochMilli()
        val after = resetInstant + 2 * 60_000L
        val stamped = NyTime.withClockIfDateTruncated(nextDateOnly, resetInstant, after)!!
        val ny = Instant.ofEpochMilli(stamped).atZone(zone)
        assertEquals(20, ny.dayOfMonth)
        assertEquals(11, ny.monthValue)
        assertEquals(14, ny.hour)
        assertEquals(18, ny.minute)
        assertTrue(stamped > after)
        val beforeReset = resetInstant - 20 * 60_000L
        val stillThisWeek = NyTime.withClockIfDateTruncated(
            nextDateOnly,
            Instant.parse("2026-11-06T19:18:00.000Z").toEpochMilli(),
            beforeReset
        )!!
        val stillNy = Instant.ofEpochMilli(stillThisWeek).atZone(zone)
        assertEquals(13, stillNy.dayOfMonth)
        assertEquals(14, stillNy.hour)
        assertEquals(18, stillNy.minute)
    }
}
