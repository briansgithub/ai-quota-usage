package com.usagewidget.domain

/**
 * Background cadence: unique WorkManager periodic work at Android's 15-minute
 * minimum (Doze, standby and job quotas can delay it by much more), plus an
 * expedited one-off refresh when the screen turns on or the widget's refresh
 * button is tapped. Next periodic refresh is last periodic run + [INTERVAL_MS].
 */
object RefreshSchedule {
    const val INTERVAL_MS = 15L * 60_000

    /** Data older than this is refreshed when the screen turns on / unlocks. */
    const val WAKE_STALE_AFTER_MS = 5L * 60_000

    /** Floor between two wake-triggered refresh requests (screen flicker, unlock + on). */
    const val WAKE_MIN_GAP_MS = 60_000L

    fun lastSuccessEpochMs(snapshot: UsageSnapshot): Long =
        snapshot.sectionHealth.maxOfOrNull { it.lastSuccessEpochMs } ?: 0L

    fun shouldRefreshOnWake(lastSuccessEpochMs: Long, lastWakeRequestEpochMs: Long, nowMs: Long): Boolean {
        if (lastWakeRequestEpochMs > 0L && nowMs - lastWakeRequestEpochMs < WAKE_MIN_GAP_MS) return false
        return lastSuccessEpochMs <= 0L || nowMs - lastSuccessEpochMs >= WAKE_STALE_AFTER_MS
    }

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
