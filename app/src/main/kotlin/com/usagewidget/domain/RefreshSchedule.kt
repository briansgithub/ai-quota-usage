package com.usagewidget.domain

/**
 * Sole background cadence: unique WorkManager periodic work at Android's
 * 15-minute minimum. Next refresh is last periodic run + [INTERVAL_MS].
 */
object RefreshSchedule {
    const val INTERVAL_MS = 15L * 60_000

    fun nextEpochMs(lastPeriodicRunEpochMs: Long, armedEpochMs: Long, nowMs: Long): Long {
        if (lastPeriodicRunEpochMs > 0L) return lastPeriodicRunEpochMs + INTERVAL_MS
        if (armedEpochMs > 0L) return armedEpochMs
        return nowMs + INTERVAL_MS
    }

    fun remainingMs(nextEpochMs: Long, nowMs: Long): Long =
        if (nextEpochMs <= 0L) 0L else (nextEpochMs - nowMs).coerceAtLeast(0L)

    fun formatCountdown(remainingMs: Long): String {
        val totalSec = remainingMs.coerceAtLeast(0L) / 1_000L
        return "next %02d:%02d".format(totalSec / 60L, totalSec % 60L)
    }

    fun chronometerBaseElapsedRealtime(
        nextEpochMs: Long,
        nowEpochMs: Long,
        elapsedRealtime: Long
    ): Long = elapsedRealtime + remainingMs(nextEpochMs, nowEpochMs)
}
