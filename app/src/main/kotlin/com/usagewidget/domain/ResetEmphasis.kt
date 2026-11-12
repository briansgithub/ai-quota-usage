package com.usagewidget.domain

/**
 * Visual urgency of a reset line (`Reset: Fri 9 AM (1d 4h)`).
 *
 * Thresholds (time until reset):
 * - **RELAXED** — more than 48 hours: normal white
 * - **SOON** — 24–48 hours: brighter white, bolder
 * - **NEAR** — 5–24 hours: amber, bold
 * - **IMMINENT** — 5 hours or less (including overdue): orange/red, bold
 */
enum class ResetUrgency {
    RELAXED,
    SOON,
    NEAR,
    IMMINENT
}

object ResetEmphasis {
    const val RELAXED_AFTER_MS = 48L * 60 * 60 * 1000
    const val SOON_AFTER_MS = 24L * 60 * 60 * 1000
    const val IMMINENT_AFTER_MS = 5L * 60 * 60 * 1000

    fun of(resetAtEpochMs: Long?, nowMs: Long): ResetUrgency {
        if (resetAtEpochMs == null) return ResetUrgency.RELAXED
        val left = resetAtEpochMs - nowMs
        return when {
            left <= IMMINENT_AFTER_MS -> ResetUrgency.IMMINENT
            left <= SOON_AFTER_MS -> ResetUrgency.NEAR
            left <= RELAXED_AFTER_MS -> ResetUrgency.SOON
            else -> ResetUrgency.RELAXED
        }
    }
}
