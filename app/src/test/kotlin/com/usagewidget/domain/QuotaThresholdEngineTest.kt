package com.usagewidget.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class QuotaThresholdEngineTest {
    private val now = 2_000_000_000_000L
    private val grokReset = now + 3 * 86_400_000L
    private val modelsReset = now + 20 * 86_400_000L
    private val prefs = QuotaThresholdPreferences()

    @Test
    fun defaultStepsAreTenThenFiveBelowThirty() {
        assertEquals(
            listOf(90, 80, 70, 60, 50, 40, 30, 25, 20, 15, 10, 5, 0),
            QuotaThresholds.DEFAULT
        )
        assertEquals(QuotaThresholds.DEFAULT, QuotaThresholds.parseCsv(""))
        assertEquals(listOf(50, 20, 0), QuotaThresholds.parseCsv("0, 20, 50, 50, 200, -1"))
    }

    @Test
    fun firstObservationSeedsWithoutNotifying() {
        val current = liveSnapshot(grokRemaining = 22.0, modelsRemaining = 45.0)
        val plan = QuotaThresholdEngine.evaluate(current, now, prefs, emptyMap(), quiet = false)
        assertTrue(plan.alerts.isEmpty())
        val grok = plan.states.getValue(QuotaRuleId.GROK_WEEKLY.wire)
        assertEquals(22.0, grok.remaining)
        assertEquals(listOf(90, 80, 70, 60, 50, 40, 30, 25), grok.fired)
        val models = plan.states.getValue(QuotaRuleId.CURSOR_MODELS.wire)
        assertEquals(listOf(90, 80, 70, 60, 50), models.fired)
        assertFalse(plan.states.containsKey(QuotaRuleId.CODEX_WEEKLY.wire))
    }

    @Test
    fun sameThresholdIsDedupedUntilWindowResets() {
        val first = liveSnapshot(grokRemaining = 45.0)
        val seeded = QuotaThresholdEngine.evaluate(first, now, prefs, emptyMap(), quiet = false)
        val dropped = liveSnapshot(grokRemaining = 38.0)
        val notified = QuotaThresholdEngine.evaluate(dropped, now, prefs, seeded.states, quiet = false)
        assertEquals(1, notified.alerts.size)
        assertTrue(notified.alerts.single().body.contains("60% weekly quota used"))
        val again = QuotaThresholdEngine.evaluate(
            liveSnapshot(grokRemaining = 35.0),
            now,
            prefs,
            notified.states,
            quiet = false
        )
        assertTrue(again.alerts.isEmpty())
    }

    @Test
    fun multiCrossingNotifiesOnlyTheLowest() {
        val seeded = QuotaThresholdEngine.evaluate(
            liveSnapshot(grokRemaining = 55.0),
            now,
            prefs,
            emptyMap(),
            quiet = false
        )
        val plan = QuotaThresholdEngine.evaluate(
            liveSnapshot(grokRemaining = 18.0),
            now,
            prefs,
            seeded.states,
            quiet = false
        )
        assertEquals(1, plan.alerts.size)
        assertTrue(plan.alerts.single().body.contains("80% weekly quota used"))
        assertTrue(plan.states.getValue(QuotaRuleId.GROK_WEEKLY.wire).fired.containsAll(listOf(50, 40, 30, 25, 20)))
    }

    @Test
    fun resetTimeChangeReseedsWithoutNotify() {
        val seeded = QuotaThresholdEngine.evaluate(
            liveSnapshot(grokRemaining = 18.0, grokResetAt = grokReset),
            now,
            prefs,
            emptyMap(),
            quiet = false
        )
        assertTrue(seeded.states.getValue(QuotaRuleId.GROK_WEEKLY.wire).fired.contains(20))
        val newWindow = QuotaThresholdEngine.evaluate(
            liveSnapshot(grokRemaining = 100.0, grokResetAt = grokReset + 7 * 86_400_000L),
            now,
            prefs,
            seeded.states,
            quiet = false
        )
        assertTrue(newWindow.alerts.isEmpty())
        assertTrue(newWindow.states.getValue(QuotaRuleId.GROK_WEEKLY.wire).fired.isEmpty())
        val drop = QuotaThresholdEngine.evaluate(
            liveSnapshot(grokRemaining = 85.0, grokResetAt = grokReset + 7 * 86_400_000L),
            now,
            prefs,
            newWindow.states,
            quiet = false
        )
        assertEquals(1, drop.alerts.size)
        assertTrue(drop.alerts.single().body.contains("10% weekly quota used"))
    }

    @Test
    fun periodStartAdvanceReseedsEvenIfResetTimestampUnchanged() {
        val start1 = now - 6 * 86_400_000L
        val start2 = now
        val seeded = QuotaThresholdEngine.evaluate(
            liveSnapshot(grokRemaining = 18.0, grokResetAt = grokReset, grokWindowStart = start1),
            now,
            prefs,
            emptyMap(),
            quiet = false
        )
        assertTrue(seeded.states.getValue(QuotaRuleId.GROK_WEEKLY.wire).fired.contains(20))
        val restored = QuotaThresholdEngine.evaluate(
            liveSnapshot(grokRemaining = 100.0, grokResetAt = grokReset, grokWindowStart = start2),
            now,
            prefs,
            seeded.states,
            quiet = false
        )
        assertTrue(restored.alerts.isEmpty())
        assertTrue(restored.states.getValue(QuotaRuleId.GROK_WEEKLY.wire).fired.isEmpty())
        assertEquals(
            "$start2|$grokReset",
            restored.states.getValue(QuotaRuleId.GROK_WEEKLY.wire).windowKey
        )
    }

    @Test
    fun remainingJumpUpPastBoundaryResetsFired() {
        val seeded = QuotaThresholdEngine.evaluate(
            liveSnapshot(grokRemaining = 18.0),
            now,
            prefs,
            emptyMap(),
            quiet = false
        )
        val restored = QuotaThresholdEngine.evaluate(
            liveSnapshot(grokRemaining = 100.0),
            now,
            prefs,
            seeded.states,
            quiet = false
        )
        assertTrue(restored.alerts.isEmpty())
        assertTrue(restored.states.getValue(QuotaRuleId.GROK_WEEKLY.wire).fired.isEmpty())
    }

    @Test
    fun staleOrCachedLastGoodDoesNotFireOrSeed() {
        val live = liveSnapshot(grokRemaining = 45.0)
        val seeded = QuotaThresholdEngine.evaluate(live, now, prefs, emptyMap(), quiet = false)
        val stale = HealthSynthesis.hydrate(live).copy(
            sectionHealth = HealthSynthesis.hydrate(live).sectionHealth.map { health ->
                if (health.sectionId != SoftwareSectionId.GROK_BOT.wireName) health
                else health.copy(
                    lastOutcome = FetchOutcome.NETWORK.name,
                    lastSuccessEpochMs = now - 60_000L,
                    lastGoodMeters = health.lastGoodMeters.ifEmpty {
                        listOf(
                            UsageMeter(
                                id = "cursor.grok_bot",
                                title = "Weekly",
                                remainingPercent = 12.0,
                                usedPercent = 88.0,
                                resetsAtEpochMs = grokReset
                            )
                        )
                    }
                )
            }
        )
        val plan = QuotaThresholdEngine.evaluate(stale, now, prefs, seeded.states, quiet = false)
        assertTrue(plan.alerts.isEmpty())
        assertEquals(45.0, plan.states.getValue(QuotaRuleId.GROK_WEEKLY.wire).remaining)
    }

    @Test
    fun onDemandCapDoesNotCreateThresholdState() {
        val base = liveSnapshot(grokRemaining = 22.0, modelsRemaining = 45.0)
        val withCap = base.copy(
            providers = base.providers.map { provider ->
                if (provider.id != "cursor") provider
                else provider.copy(
                    meters = provider.meters + UsageMeter(
                        id = "cursor.on_demand",
                        title = "On-demand  $45 / $50",
                        usedPercent = 90.0,
                        remainingPercent = 10.0,
                        barKind = MeterBarKind.CAP
                    ) + UsageMeter(
                        id = "cursor.grok_on_demand",
                        title = "On-demand  $45 / $50",
                        usedPercent = 90.0,
                        remainingPercent = 10.0,
                        barKind = MeterBarKind.CAP
                    )
                )
            }
        )
        val plan = QuotaThresholdEngine.evaluate(withCap, now, prefs, emptyMap(), quiet = false)
        assertTrue(plan.alerts.isEmpty())
        assertFalse(plan.states.containsKey("cursor.on_demand"))
        assertFalse(plan.states.containsKey("cursor.grok_on_demand"))
        assertEquals(setOf(QuotaRuleId.GROK_WEEKLY.wire, QuotaRuleId.CURSOR_MODELS.wire), plan.states.keys)
    }

    @Test
    fun unsignedDoesNotSeed() {
        val empty = UsageSnapshot.empty(now)
        val plan = QuotaThresholdEngine.evaluate(empty, now, prefs, emptyMap(), quiet = false)
        assertTrue(plan.alerts.isEmpty())
        assertTrue(plan.states.isEmpty())
    }

    @Test
    fun codexRulesExistButDefaultOff() {
        val snap = liveSnapshot(grokRemaining = 40.0, fiveRemaining = 12.0, weeklyRemaining = 12.0)
        val plan = QuotaThresholdEngine.evaluate(snap, now, prefs, emptyMap(), quiet = false)
        assertFalse(plan.states.containsKey(QuotaRuleId.CODEX_FIVE_HOUR.wire))
        val on = prefs.copy(
            enabledRules = prefs.enabledRules + QuotaRuleId.CODEX_FIVE_HOUR.wire
        )
        val enabled = QuotaThresholdEngine.evaluate(snap, now, on, emptyMap(), quiet = false)
        assertTrue(enabled.alerts.isEmpty())
        assertTrue(enabled.states.containsKey(QuotaRuleId.CODEX_FIVE_HOUR.wire))
    }

    @Test
    fun masterOffLeavesPriorStateAndPostsNothing() {
        val seeded = QuotaThresholdEngine.evaluate(
            liveSnapshot(grokRemaining = 45.0),
            now,
            prefs,
            emptyMap(),
            quiet = false
        )
        val plan = QuotaThresholdEngine.evaluate(
            liveSnapshot(grokRemaining = 10.0),
            now,
            prefs.copy(masterEnabled = false),
            seeded.states,
            quiet = false
        )
        assertTrue(plan.alerts.isEmpty())
        assertEquals(seeded.states, plan.states)
    }

    @Test
    fun quietHoursSeedButHoldCrossingUntilLater() {
        val seeded = QuotaThresholdEngine.evaluate(
            liveSnapshot(grokRemaining = 45.0),
            now,
            prefs,
            emptyMap(),
            quiet = true
        )
        assertTrue(seeded.alerts.isEmpty())
        val quietDrop = QuotaThresholdEngine.evaluate(
            liveSnapshot(grokRemaining = 18.0),
            now,
            prefs,
            seeded.states,
            quiet = true
        )
        assertTrue(quietDrop.alerts.isEmpty())
        assertEquals(45.0, quietDrop.states.getValue(QuotaRuleId.GROK_WEEKLY.wire).remaining)
        val after = QuotaThresholdEngine.evaluate(
            liveSnapshot(grokRemaining = 18.0),
            now,
            prefs,
            quietDrop.states,
            quiet = false
        )
        assertEquals(1, after.alerts.size)
        assertTrue(after.alerts.single().body.contains("80% weekly quota used"))
    }

    @Test
    fun notificationCopyMatchesExampleShape() {
        val mondayThree = ZonedDateTime.of(2026, 11, 9, 15, 0, 0, 0, ZoneId.of(NyZone.ID))
            .toInstant()
            .toEpochMilli()
        val body = QuotaThresholdEngine.bodyFor(QuotaRuleId.GROK_WEEKLY, 20, mondayThree)
        assertEquals("Grok Bot: 80% weekly quota used (resets Mon 3:00 PM ET)", body)
        assertEquals(body, QuotaThresholdEngine.TEST_BODY)
    }

    @Test
    fun customThresholdListIsHonored() {
        val custom = prefs.copy(thresholds = listOf(50, 10))
        val seeded = QuotaThresholdEngine.evaluate(
            liveSnapshot(grokRemaining = 80.0),
            now,
            custom,
            emptyMap(),
            quiet = false
        )
        val plan = QuotaThresholdEngine.evaluate(
            liveSnapshot(grokRemaining = 9.0),
            now,
            custom,
            seeded.states,
            quiet = false
        )
        assertEquals(1, plan.alerts.size)
        assertTrue(plan.alerts.single().body.contains("90% weekly quota used"))
    }

    private fun liveSnapshot(
        grokRemaining: Double = 80.0,
        modelsRemaining: Double = 80.0,
        grokResetAt: Long = grokReset,
        grokWindowStart: Long? = null,
        modelsResetAt: Long = modelsReset,
        fiveRemaining: Double? = null,
        weeklyRemaining: Double? = null
    ): UsageSnapshot {
        val cursorMeters = listOf(
            UsageMeter(
                id = "cursor.models",
                title = "Cursor Models",
                remainingPercent = modelsRemaining,
                usedPercent = 100.0 - modelsRemaining,
                resetsAtEpochMs = modelsResetAt
            ),
            UsageMeter(
                id = "cursor.grok_bot",
                title = "Weekly",
                remainingPercent = grokRemaining,
                usedPercent = 100.0 - grokRemaining,
                resetsAtEpochMs = grokResetAt,
                windowStartEpochMs = grokWindowStart
            )
        )
        val openaiMeters = listOfNotNull(
            fiveRemaining?.let {
                UsageMeter(
                    id = "openai.five_hour",
                    title = "5-hour",
                    remainingPercent = it,
                    usedPercent = 100.0 - it,
                    resetsAtEpochMs = now + 4 * 3_600_000L
                )
            },
            weeklyRemaining?.let {
                UsageMeter(
                    id = "openai.weekly",
                    title = "Weekly",
                    remainingPercent = it,
                    usedPercent = 100.0 - it,
                    resetsAtEpochMs = now + 6 * 86_400_000L
                )
            }
        )
        return UsageSnapshot(
            fetchedAtEpochMs = now,
            providers = listOf(
                ProviderSnapshot(
                    id = ProviderId.CURSOR.wireName,
                    displayName = "Cursor",
                    status = ProviderStatus.OK.name,
                    meters = cursorMeters
                ),
                ProviderSnapshot(
                    id = ProviderId.OPENAI.wireName,
                    displayName = "ChatGPT / Codex",
                    status = if (openaiMeters.isEmpty()) ProviderStatus.UNSIGNED.name else ProviderStatus.OK.name,
                    meters = openaiMeters
                )
            )
        )
    }
}
