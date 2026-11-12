package com.usagewidget.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResetDetectorTest {
    private val now = 2_000_000_000_000L
    private val hour = 3_600_000L

    @Test
    fun resetTimePassingFiresOncePerCycle() {
        val prevReset = now - 30_000L
        val previous = snapshot(
            fetchedAt = now - hour,
            meters = listOf(
                meter("openai.five_hour", remaining = 2.0, resetAt = prevReset)
            )
        )
        val current = snapshot(
            fetchedAt = now,
            meters = listOf(
                meter("openai.five_hour", remaining = 100.0, resetAt = now + 5 * hour)
            )
        )
        val events = ResetDetector.detect(previous, current, now)
        assertEquals(1, events.size)
        assertEquals("codex.five_hour", events[0].group)
        assertEquals(RestoredReason.RESET_TIME_PASSED, events[0].reason)
        assertEquals("restored:codex.five_hour:$prevReset", events[0].dedupeKey)
        assertEquals(RestoredKind.FIVE_HOUR, events[0].kind)
    }

    @Test
    fun remainingJumpWithoutKnownResetStillFires() {
        val previous = snapshot(
            fetchedAt = now - hour,
            meters = listOf(meter("openai.weekly", remaining = 10.0, resetAt = now + 3 * hour))
        )
        val current = snapshot(
            fetchedAt = now,
            meters = listOf(meter("openai.weekly", remaining = 40.0, resetAt = now + 7 * 24 * hour))
        )
        val events = ResetDetector.detect(previous, current, now)
        assertEquals(1, events.size)
        assertEquals(RestoredReason.REMAINING_JUMP, events[0].reason)
        assertEquals("codex.weekly", events[0].group)
        assertTrue(events[0].dedupeKey.startsWith("restored:codex.weekly:"))
    }

    @Test
    fun smallRemainingIncreaseIsIgnored() {
        val previous = snapshot(
            fetchedAt = now - hour,
            meters = listOf(meter("openai.weekly", remaining = 40.0, resetAt = now + 2 * hour))
        )
        val current = snapshot(
            fetchedAt = now,
            meters = listOf(meter("openai.weekly", remaining = 45.0, resetAt = now + 2 * hour))
        )
        assertTrue(ResetDetector.detect(previous, current, now).isEmpty())
    }

    @Test
    fun resetAlreadyPastAtPreviousFetchDoesNotRefire() {
        val previous = snapshot(
            fetchedAt = now - hour,
            meters = listOf(meter("openai.five_hour", remaining = 100.0, resetAt = now - 2 * hour))
        )
        val current = snapshot(
            fetchedAt = now,
            meters = listOf(meter("openai.five_hour", remaining = 100.0, resetAt = now + 3 * hour))
        )
        assertTrue(ResetDetector.detect(previous, current, now).isEmpty())
    }

    @Test
    fun firstAppearanceDoesNotNotify() {
        val previous = snapshot(fetchedAt = now - hour, meters = emptyList())
        val current = snapshot(
            fetchedAt = now,
            meters = listOf(meter("openai.five_hour", remaining = 100.0, resetAt = now + hour))
        )
        assertTrue(ResetDetector.detect(previous, current, now).isEmpty())
    }

    @Test
    fun cursorModelsAndPlanResetCollapseToOneEvent() {
        val prevReset = now - 10_000L
        val previous = snapshot(
            fetchedAt = now - hour,
            providerId = "cursor",
            meters = listOf(
                meter("cursor.models", remaining = 5.0, resetAt = prevReset),
                meter("cursor.other", remaining = 0.0, resetAt = prevReset),
                meter("cursor.plan_reset", remaining = null, resetAt = prevReset)
            )
        )
        val current = snapshot(
            fetchedAt = now,
            providerId = "cursor",
            meters = listOf(
                meter("cursor.models", remaining = 100.0, resetAt = now + 30 * 24 * hour),
                meter("cursor.other", remaining = 100.0, resetAt = now + 30 * 24 * hour),
                meter("cursor.plan_reset", remaining = null, resetAt = now + 30 * 24 * hour)
            )
        )
        val events = ResetDetector.detect(previous, current, now)
        assertEquals(1, events.size)
        assertEquals("cursor.plan", events[0].group)
    }

    @Test
    fun grokWeeklyIsItsOwnGroup() {
        val prevReset = now - 5_000L
        val previous = snapshot(
            fetchedAt = now - hour,
            providerId = "cursor",
            meters = listOf(meter("cursor.grok_bot", remaining = 1.0, resetAt = prevReset))
        )
        val current = snapshot(
            fetchedAt = now,
            providerId = "cursor",
            meters = listOf(meter("cursor.grok_bot", remaining = 100.0, resetAt = now + 7 * 24 * hour))
        )
        val events = ResetDetector.detect(previous, current, now)
        assertEquals(listOf("grok.weekly"), events.map { it.group })
    }

    @Test
    fun emptyPreviousSnapshotYieldsNothing() {
        val current = snapshot(
            fetchedAt = now,
            meters = listOf(meter("openai.five_hour", remaining = 100.0, resetAt = now + hour))
        )
        assertTrue(ResetDetector.detect(UsageSnapshot.empty(now - hour), current, now).isEmpty())
    }

    private fun meter(id: String, remaining: Double?, resetAt: Long?): UsageMeter = UsageMeter(
        id = id,
        title = id,
        remainingPercent = remaining,
        usedPercent = remaining?.let { 100.0 - it },
        resetsAtEpochMs = resetAt
    )

    private fun snapshot(
        fetchedAt: Long,
        meters: List<UsageMeter>,
        providerId: String = "openai"
    ): UsageSnapshot = UsageSnapshot(
        fetchedAtEpochMs = fetchedAt,
        providers = listOf(
            ProviderSnapshot(
                id = providerId,
                displayName = providerId,
                status = ProviderStatus.OK.name,
                meters = meters
            )
        )
    )
}
