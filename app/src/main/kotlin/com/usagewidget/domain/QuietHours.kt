package com.usagewidget.domain

import java.time.Instant
import java.time.ZoneId

object QuietHours {
    const val DEFAULT_START_HOUR = 23
    const val DEFAULT_END_HOUR = 8

    /**
     * [startHour, endHour) in America/New_York. A start later than end wraps past midnight
     * (default 23:00–08:00). Disabled quiet hours never match.
     */
    fun isQuiet(
        nowMs: Long,
        enabled: Boolean,
        startHour: Int = DEFAULT_START_HOUR,
        endHour: Int = DEFAULT_END_HOUR,
        zone: ZoneId = ZoneId.of(NyZone.ID)
    ): Boolean {
        if (!enabled) return false
        val hour = Instant.ofEpochMilli(nowMs).atZone(zone).hour
        return if (startHour == endHour) {
            true
        } else if (startHour < endHour) {
            hour in startHour until endHour
        } else {
            hour >= startHour || hour < endHour
        }
    }
}
