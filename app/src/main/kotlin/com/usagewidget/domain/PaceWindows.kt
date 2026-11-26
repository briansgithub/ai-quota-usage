package com.usagewidget.domain

import com.usagewidget.providers.claude.ClaudeMeterIds
import com.usagewidget.providers.gemini.GeminiUsageParser

/**
 * Even-pace expected used-% for a quota window: elapsedFraction × 100.
 * Window bounds come from the meter’s start+reset when known, otherwise reset
 * minus a known length (5-hour, 7-day weekly, ~30-day Cursor billing cycle).
 * Matches Windows [PaceWindows].
 */
object PaceWindows {
    fun knownLengthMs(meterId: String): Long? = when (meterId) {
        "openai.five_hour" -> PacingPolicy.WINDOW_MS
        "openai.weekly" -> PacingPolicy.GROK_PERIOD_MS
        "openai.thirty_day" -> 30L * 24 * 60 * 60 * 1000
        ClaudeMeterIds.FIVE_HOUR -> ClaudeMeterIds.FIVE_HOUR_MS
        ClaudeMeterIds.WEEKLY, ClaudeMeterIds.WEEKLY_OPUS, ClaudeMeterIds.WEEKLY_SONNET -> ClaudeMeterIds.WEEK_MS
        "gemini.gemini-5h", "gemini.3p-5h" -> GeminiUsageParser.FIVE_HOURS
        "gemini.gemini-weekly", "gemini.3p-weekly", "gemini.argon-weekly" -> GeminiUsageParser.WEEK
        "cursor.grok_bot" -> PacingPolicy.GROK_PERIOD_MS
        "cursor.models", "cursor.other" -> PacingPolicy.CURSOR_PERIOD_MS
        else -> null
    }

    fun bounds(meter: UsageMeter): Pair<Long, Long>? {
        val start = meter.windowStartEpochMs
        val end = meter.resetsAtEpochMs
        val length = meter.windowLengthMs ?: knownLengthMs(meter.id)
        if (start != null && end != null && end > start) return start to end
        if (end != null && length != null && length > 0) return (end - length) to end
        if (start != null && length != null && length > 0) return start to (start + length)
        return null
    }

    /** On-pace used percent, or null when the window cannot be determined. */
    fun expectedUsedPercent(meter: UsageMeter, nowMs: Long): Double? {
        val (start, end) = bounds(meter) ?: return null
        val length = end - start
        if (length <= 0L) return null
        val elapsed = ((nowMs - start).toDouble() / length).coerceIn(0.0, 1.0)
        return elapsed * 100.0
    }
}
