package com.usagewidget.util

import com.usagewidget.domain.NyZone
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.abs

/** A run of reset-line text; [emphasized] runs (time, duration) are drawn bold. */
data class ResetSegment(val text: String, val emphasized: Boolean)

object NyTime {
    val zone: ZoneId = ZoneId.of(NyZone.ID)
    private const val GROK_WEEK_MS = 7L * 24 * 60 * 60 * 1000

    private val absolute: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a z", Locale.US)
    private val shortDate: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val shortDateTime: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d, h:mm a z", Locale.US)
    private val weekday: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE", Locale.US)
    private val clockHour: DateTimeFormatter =
        DateTimeFormatter.ofPattern("h a", Locale.US)
    private val clockHourMinute: DateTimeFormatter =
        DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val monthDay: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM/dd", Locale.US)

    fun formatAbsolute(epochMs: Long): String {
        val zoned = Instant.ofEpochMilli(epochMs).atZone(zone)
        return zoned.format(absolute)
    }

    fun formatShort(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val instant = Instant.ofEpochMilli(epochMs)
        val now = Instant.ofEpochMilli(nowMs)
        val zoned = instant.atZone(zone)
        val hours = java.time.Duration.between(now, instant).toHours()
        return if (abs(hours) < 36) {
            zoned.format(shortDateTime)
        } else {
            zoned.format(shortDate)
        }
    }

    fun formatResetLine(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String =
        formatResetSegments(epochMs, nowMs).joinToString("") { it.text }

    /**
     * The reset line split so a UI can bold only the clock time and the time-left
     * duration: `Reset: Fri ` + **`9 AM`** + ` (` + **`1d 4h`** + `)`.
     */
    fun formatResetSegments(epochMs: Long, nowMs: Long = System.currentTimeMillis(), label: String = "Reset"): List<ResetSegment> {
        val zoned = Instant.ofEpochMilli(epochMs).atZone(zone)
        val day = zoned.format(weekday)
        return listOf(
            ResetSegment("$label: $day ", emphasized = false),
            ResetSegment(formatResetClock(zoned), emphasized = true),
            ResetSegment(" (", emphasized = false),
            ResetSegment(formatResetRelative(epochMs, nowMs), emphasized = true),
            ResetSegment(")", emphasized = false)
        )
    }

    fun formatResetClock(zoned: ZonedDateTime): String {
        val formatter = if (zoned.minute == 0) clockHour else clockHourMinute
        return zoned.format(formatter)
    }

    /** Weekday plus always-minutes clock, e.g. `Mon 3:00 PM ET`. */
    fun formatQuotaReset(epochMs: Long): String {
        val zoned = Instant.ofEpochMilli(epochMs).atZone(zone)
        val day = zoned.format(weekday)
        val clock = zoned.format(clockHourMinute)
        return "$day $clock ET"
    }

    fun isUtcMidnight(epochMs: Long): Boolean {
        val utc = Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC)
        return utc.toLocalTime() == LocalTime.MIDNIGHT && utc.nano == 0
    }

    /**
     * When a reset instant is truncated to 00:00:00.000Z (a UTC calendar date),
     * stamp [clockEpochMs]'s America/New_York wall time onto that UTC date.
     * Used for Grok Bot: nextResetTimestampUtc is often date-only while
     * currentPeriodStart carries the rolling weekly clock.
     */
    fun overlayUtcDateWithNyClock(dateEpochMs: Long, clockEpochMs: Long): Long {
        val date = Instant.ofEpochMilli(dateEpochMs).atZone(ZoneOffset.UTC).toLocalDate()
        val time = Instant.ofEpochMilli(clockEpochMs).atZone(zone).toLocalTime()
        return ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
    }

    fun withClockIfDateTruncated(
        nextEpochMs: Long?,
        clockEpochMs: Long?,
        nowMs: Long? = null
    ): Long? {
        if (nextEpochMs == null) return null
        if (clockEpochMs == null) return nextEpochMs
        if (!isUtcMidnight(nextEpochMs)) return nextEpochMs
        if (isUtcMidnight(clockEpochMs)) return nextEpochMs
        var stamped = overlayUtcDateWithNyClock(nextEpochMs, clockEpochMs)
        val week = GROK_WEEK_MS
        var guard = 0
        // Date-only nextReset stamped with the new period-start clock equals that start
        // (the reset that just fired) instead of +7 days. Roll until it is after both
        // the period start and "now" so the widget does not show "due" / old pacing.
        while (stamped <= clockEpochMs && guard++ < 4) {
            stamped += week
        }
        if (nowMs != null) {
            while (stamped <= nowMs && guard++ < 8) {
                stamped += week
            }
        }
        return stamped
    }

    fun formatResetRelative(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val deltaSec = (epochMs - nowMs) / 1000L
        if (deltaSec <= 0) return "due"
        return formatDuration(deltaSec * 1000L)
    }

    fun formatDuration(ms: Long): String {
        val deltaSec = abs(ms) / 1000L
        if (deltaSec < 60) return "0m"
        val days = deltaSec / 86_400
        val hours = (deltaSec % 86_400) / 3_600
        val minutes = (deltaSec % 3_600) / 60
        return when {
            days >= 1 && hours > 0 -> "${days}d ${hours}h"
            days >= 1 -> "${days}d"
            hours >= 1 && minutes > 0 && hours < 3 -> "${hours}h ${minutes}m"
            hours >= 1 -> "${hours}h"
            else -> "${minutes}m"
        }
    }

    fun formatMonthDay(epochMs: Long): String {
        return Instant.ofEpochMilli(epochMs).atZone(zone).format(monthDay)
    }

    fun countdown(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val deltaSec = (epochMs - nowMs) / 1000L
        if (deltaSec <= 0) return "reset due"
        val days = deltaSec / 86_400
        val hours = (deltaSec % 86_400) / 3_600
        val minutes = (deltaSec % 3_600) / 60
        return when {
            days >= 2 -> "${days}d ${hours}h left"
            days >= 1 -> "${days}d ${hours}h left"
            hours >= 1 -> "${hours}h ${minutes}m left"
            else -> "${minutes}m left"
        }
    }

    fun parseTimestamp(value: Any?, nowMs: Long = System.currentTimeMillis()): Long? {
        when (value) {
            null -> return null
            is Number -> return epochFromNumber(value.toDouble())
            is String -> return parseTimestampString(value, nowMs)
        }
        return null
    }

    fun parseTimestampString(raw: String, nowMs: Long = System.currentTimeMillis()): Long? {
        val text = raw.trim()
        if (text.isEmpty() || text == "null") return null
        text.toDoubleOrNull()?.let { return epochFromNumber(it) }

        val isoCandidates = listOf(
            text,
            text.replace(' ', 'T'),
            if (text.endsWith("Z") || text.contains('+') || Regex("T.*-").containsMatchIn(text)) text
            else text + "Z"
        )
        for (candidate in isoCandidates) {
            try {
                return Instant.parse(candidate).toEpochMilli()
            } catch (_: DateTimeParseException) {
                // try next
            }
            try {
                val local = LocalDateTime.parse(candidate.removeSuffix("Z"))
                return local.atZone(zone).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                // try next
            }
            try {
                val date = LocalDate.parse(candidate.take(10))
                return date.atStartOfDay(zone).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                // try next
            }
        }
        parseMonthDay(text, nowMs)?.let { return it }
        parseRelative(text, nowMs)?.let { return it }
        return null
    }

    fun parseRelative(text: String, nowMs: Long): Long? {
        val lower = text.lowercase(Locale.US)
        val dayHour = Regex("""(\d+)\s*d(?:ays?)?[^\d]*?(\d+)\s*h(?:ours?)?""").find(lower)
        if (dayHour != null) {
            val days = dayHour.groupValues[1].toLong()
            val hours = dayHour.groupValues[2].toLong()
            return nowMs + (days * 86_400 + hours * 3_600) * 1000
        }
        val hourMin = Regex("""(\d+)\s*h(?:ours?)?[^\d]*?(\d+)\s*m(?:in(?:utes?)?)?""").find(lower)
        if (hourMin != null) {
            val hours = hourMin.groupValues[1].toLong()
            val minutes = hourMin.groupValues[2].toLong()
            return nowMs + (hours * 3_600 + minutes * 60) * 1000
        }
        val daysOnly = Regex("""(\d+)\s*d(?:ays?)?""").find(lower)
        val hoursOnly = Regex("""(\d+)\s*h(?:ours?)?""").find(lower)
        val minutesOnly = Regex("""(\d+)\s*m(?:in(?:utes?)?)?""").find(lower)
        if (daysOnly != null && hoursOnly == null) {
            return nowMs + daysOnly.groupValues[1].toLong() * 86_400_000
        }
        if (hoursOnly != null && daysOnly == null && minutesOnly == null) {
            return nowMs + hoursOnly.groupValues[1].toLong() * 3_600_000
        }
        if (minutesOnly != null && daysOnly == null && hoursOnly == null) {
            return nowMs + minutesOnly.groupValues[1].toLong() * 60_000
        }
        val daysLeft = Regex("""(\d+)\s+days?\s+left""").find(lower)
        if (daysLeft != null) {
            return nowMs + daysLeft.groupValues[1].toLong() * 86_400_000
        }
        return null
    }

    fun parseMonthDay(text: String, nowMs: Long): Long? {
        val match = Regex(
            """\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+(\d{1,2})(?:,\s*(\d{4}))?(?:[,\s]+(\d{1,2}):(\d{2})\s*(am|pm)?)?""",
            RegexOption.IGNORE_CASE
        ).find(text) ?: return null
        val month = monthFromName(match.groupValues[1]) ?: return null
        val day = match.groupValues[2].toInt()
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val year = match.groupValues[3].toIntOrNull() ?: now.year
        val hourRaw = match.groupValues[4].toIntOrNull()
        val minute = match.groupValues[5].toIntOrNull() ?: 0
        val ampm = match.groupValues[6].lowercase(Locale.US)
        val hour = when {
            hourRaw == null -> 0
            ampm == "pm" && hourRaw < 12 -> hourRaw + 12
            ampm == "am" && hourRaw == 12 -> 0
            else -> hourRaw
        }
        return try {
            var date = LocalDate.of(year, month, day)
            if (match.groupValues[3].isEmpty() && date.atStartOfDay(zone).toInstant().toEpochMilli() < nowMs - 2 * 86_400_000L) {
                date = date.plusYears(1)
            }
            ZonedDateTime.of(date.year, date.monthValue, date.dayOfMonth, hour, minute, 0, 0, zone)
                .toInstant()
                .toEpochMilli()
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun monthFromName(name: String): Month? {
        return when (name.take(3).lowercase(Locale.US)) {
            "jan" -> Month.JANUARY
            "feb" -> Month.FEBRUARY
            "mar" -> Month.MARCH
            "apr" -> Month.APRIL
            "may" -> Month.MAY
            "jun" -> Month.JUNE
            "jul" -> Month.JULY
            "aug" -> Month.AUGUST
            "sep" -> Month.SEPTEMBER
            "oct" -> Month.OCTOBER
            "nov" -> Month.NOVEMBER
            "dec" -> Month.DECEMBER
            else -> null
        }
    }

    private fun epochFromNumber(value: Double): Long? {
        if (!value.isFinite() || value <= 0) return null
        return if (value < 1e12) (value * 1000.0).toLong() else value.toLong()
    }
}
