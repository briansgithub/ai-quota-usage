package com.usagewidget.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class UsageAlertEngineTest {
    private val now = 2_000_000_000_000L
    private val hour = 3_600_000L
    private val prefs = AlertPreferences()

    @Test
    fun restoredEventIsDedupedOnSecondEvaluate() {
        val prevReset = now - 20_000L
        val previous = openaiSnapshot(
            fetchedAt = now - hour,
            fiveRemaining = 3.0,
            fiveReset = prevReset,
            weeklyRemaining = 80.0,
            weeklyReset = now + 40 * hour
        )
        val current = openaiSnapshot(
            fetchedAt = now,
            fiveRemaining = 100.0,
            fiveReset = now + 5 * hour,
            weeklyRemaining = 80.0,
            weeklyReset = now + 40 * hour
        )
        val first = UsageAlertEngine.evaluate(previous, current, now, prefs, emptySet())
        assertEquals(1, first.alerts.count { it.channel == AlertChannel.RESTORED })
        assertTrue(first.alerts.first { it.channel == AlertChannel.RESTORED }.title.contains("5-hour"))
        assertTrue(first.alerts.first { it.channel == AlertChannel.RESTORED }.body.contains("(0% used)"))
        val second = UsageAlertEngine.evaluate(previous, current, now, prefs, first.newSeenKeys)
        assertTrue(second.alerts.none { it.channel == AlertChannel.RESTORED })
    }

    @Test
    fun quietHoursSuppressAlertsButKeepWakesAndDoNotDedupe() {
        val zone = ZoneId.of(NyZone.ID)
        val quietNow = ZonedDateTime.of(2026, 11, 6, 1, 0, 0, 0, zone).toInstant().toEpochMilli()
        val prevReset = quietNow - 10_000L
        val previous = openaiSnapshot(
            fetchedAt = quietNow - hour,
            fiveRemaining = 1.0,
            fiveReset = prevReset,
            weeklyRemaining = 48.0,
            weeklyReset = quietNow + 30 * hour
        )
        val current = openaiSnapshot(
            fetchedAt = quietNow,
            fiveRemaining = 100.0,
            fiveReset = quietNow + 5 * hour,
            weeklyRemaining = 48.0,
            weeklyReset = quietNow + 30 * hour
        )
        val plan = UsageAlertEngine.evaluate(
            previous,
            current,
            quietNow,
            prefs.copy(quietHoursEnabled = true),
            emptySet()
        )
        assertTrue(plan.alerts.isEmpty())
        assertTrue(plan.newSeenKeys.isEmpty())
        assertTrue(plan.wakes.any { it.uniqueName.startsWith("timed-reset-") })
    }

    @Test
    fun remainingJumpOverThresholdNotifiesWeekly() {
        val previous = openaiSnapshot(
            fetchedAt = now - hour,
            fiveRemaining = 50.0,
            fiveReset = now + 2 * hour,
            weeklyRemaining = 10.0,
            weeklyReset = now + 4 * hour
        )
        val current = openaiSnapshot(
            fetchedAt = now,
            fiveRemaining = 50.0,
            fiveReset = now + 2 * hour,
            weeklyRemaining = 100.0,
            weeklyReset = now + 7 * 24 * hour
        )
        val plan = UsageAlertEngine.evaluate(previous, current, now, prefs, emptySet())
        assertTrue(plan.alerts.any { it.title.contains("weekly") })
    }

    @Test
    fun comfortableStartReminderUsesOneHourTwentyCopy() {
        val weekly = now + 30 * hour
        val latestFirst = weekly - 20 * hour
        val comfortable = latestFirst - PacingPolicy.COMFORTABLE_LEAD_MS
        val previous = openaiSnapshot(
            fetchedAt = comfortable - hour,
            fiveRemaining = 100.0,
            fiveReset = comfortable + 5 * hour,
            weeklyRemaining = 48.0,
            weeklyReset = weekly
        )
        val current = openaiSnapshot(
            fetchedAt = comfortable,
            fiveRemaining = 100.0,
            fiveReset = comfortable + 5 * hour,
            weeklyRemaining = 48.0,
            weeklyReset = weekly
        )
        val plan = UsageAlertEngine.evaluate(previous, current, comfortable + 1_000L, prefs, emptySet())
        val reminder = plan.alerts.first { it.dedupeKey.contains("comfortable") }
        assertTrue(reminder.body.contains("to stay on pace"))
        assertTrue(reminder.body.contains("1h 20m") || reminder.body.contains("1h"))
        assertEquals(AlertChannel.PACING, reminder.channel)
    }

    @Test
    fun missedWindowAlertAndDisabledTypeIsSkipped() {
        val weekly = now + 30 * hour
        val afterMiss = weekly - 20 * hour + 2_000L
        val current = openaiSnapshot(
            fetchedAt = afterMiss,
            fiveRemaining = 100.0,
            fiveReset = afterMiss + 5 * hour,
            weeklyRemaining = 48.0,
            weeklyReset = weekly
        )
        val previous = current.copy(fetchedAtEpochMs = afterMiss - hour)
        val on = UsageAlertEngine.evaluate(previous, current, afterMiss, prefs, emptySet())
        assertTrue(on.alerts.any { it.dedupeKey.startsWith("pace-missed:") })
        val off = UsageAlertEngine.evaluate(
            previous,
            current,
            afterMiss,
            prefs.copy(notifyPacingMissed = false),
            emptySet()
        )
        assertTrue(off.alerts.none { it.dedupeKey.startsWith("pace-missed:") })
    }

    @Test
    fun schedulesResetWakeShortlyAfterKnownResetTime() {
        val resetAt = now + 90 * 60_000L
        val current = openaiSnapshot(
            fetchedAt = now,
            fiveRemaining = 10.0,
            fiveReset = resetAt,
            weeklyRemaining = 80.0,
            weeklyReset = now + 40 * hour
        )
        val plan = UsageAlertEngine.evaluate(
            UsageSnapshot.empty(now - hour),
            current,
            now,
            prefs,
            emptySet()
        )
        val wake = plan.wakes.first { it.uniqueName == "timed-reset-codex.five_hour" }
        assertEquals(resetAt + UsageAlertEngine.RESET_WAKE_PADDING_MS, wake.atEpochMs)
    }

    @Test
    fun schedulesResetWakesEvenWhenRestoredAlertsDisabledAndIncludesGemini() {
        val resetAt = now + 60 * 60_000L
        val current = UsageSnapshot(
            fetchedAtEpochMs = now,
            providers = listOf(
                ProviderSnapshot(
                    id = ProviderId.OPENAI.wireName,
                    displayName = "ChatGPT / Codex",
                    status = ProviderStatus.OK.name,
                    meters = listOf(
                        UsageMeter("openai.five_hour", "5-hour", usedPercent = 50.0, remainingPercent = 50.0, resetsAtEpochMs = resetAt)
                    )
                ),
                ProviderSnapshot(
                    id = ProviderId.GEMINI.wireName,
                    displayName = "Gemini",
                    status = ProviderStatus.OK.name,
                    meters = listOf(
                        UsageMeter("gemini.gemini-5h", "Gemini 5-hour", usedPercent = 40.0, remainingPercent = 60.0, resetsAtEpochMs = resetAt)
                    )
                )
            )
        )
        val plan = UsageAlertEngine.evaluate(
            UsageSnapshot.empty(now - hour),
            current,
            now,
            prefs.copy(notifyUsageRestored = false),
            emptySet()
        )
        assertTrue(plan.wakes.any { it.uniqueName == "timed-reset-codex.five_hour" })
        assertTrue(plan.wakes.any { it.uniqueName == "timed-reset-gemini.gemini-5h" })
    }

    @Test
    fun notificationDedupeDropsOldestAfterCap() {
        val seen = (1..NotificationDedupe.MAX_KEYS).map { "k$it" }.toCollection(LinkedHashSet())
        val next = NotificationDedupe.remember(seen, "new")
        assertEquals(NotificationDedupe.MAX_KEYS, next.size)
        assertTrue("new" in next)
        assertFalse("k1" in next)
        assertFalse(NotificationDedupe.shouldNotify(next, "new"))
        assertTrue(NotificationDedupe.shouldNotify(next, "k1"))
    }

    @Test
    fun expiringManualResetPostsPacingAlert() {
        val current = openaiSnapshot(
            fetchedAt = now,
            fiveRemaining = 100.0,
            fiveReset = now + 5 * hour,
            weeklyRemaining = 10.0,
            weeklyReset = now + 40 * hour
        ).let { snap ->
            val openai = snap.providers.first().copy(
                manualResets = ManualResets(
                    availableCount = 1,
                    credits = listOf(ManualResetCredit(expiresAtEpochMs = now + 8 * hour, status = "available"))
                )
            )
            snap.copy(providers = listOf(openai))
        }
        val plan = UsageAlertEngine.evaluate(
            current.copy(fetchedAtEpochMs = now - hour),
            current,
            now,
            prefs,
            emptySet()
        )
        assertTrue(plan.alerts.any { it.dedupeKey.startsWith("pace-expire:") })
    }

    private fun openaiSnapshot(
        fetchedAt: Long,
        fiveRemaining: Double,
        fiveReset: Long,
        weeklyRemaining: Double,
        weeklyReset: Long
    ): UsageSnapshot = UsageSnapshot(
        fetchedAtEpochMs = fetchedAt,
        providers = listOf(
            ProviderSnapshot(
                id = ProviderId.OPENAI.wireName,
                displayName = "ChatGPT / Codex",
                status = ProviderStatus.OK.name,
                meters = listOf(
                    UsageMeter(
                        id = "openai.five_hour",
                        title = "5-hour",
                        remainingPercent = fiveRemaining,
                        usedPercent = 100.0 - fiveRemaining,
                        resetsAtEpochMs = fiveReset
                    ),
                    UsageMeter(
                        id = "openai.weekly",
                        title = "Weekly",
                        remainingPercent = weeklyRemaining,
                        usedPercent = 100.0 - weeklyRemaining,
                        resetsAtEpochMs = weeklyReset
                    )
                )
            )
        )
    )
}
