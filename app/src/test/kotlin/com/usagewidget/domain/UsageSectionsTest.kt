package com.usagewidget.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageSectionsTest {
    private val resetMs = 1_793_836_800_000L
    private val weekResetMs = 1_793_404_800_000L

    @Test
    fun emptySnapshotAlwaysHasThreeSoftwareSections() {
        val sections = UsageSections.from(UsageSnapshot.empty(0L))
        assertEquals(
            listOf("Cursor", "Grok Bot", "Codex"),
            sections.map { it.id.title }
        )
        assertEquals(ProviderId.CURSOR, sections[0].signInProvider)
        assertEquals(ProviderId.CURSOR, sections[1].signInProvider)
        assertEquals(ProviderId.OPENAI, sections[2].signInProvider)
        assertEquals(ProviderStatus.UNSIGNED, sections[0].status)
        assertEquals(ProviderStatus.UNSIGNED, sections[1].status)
        assertEquals(ProviderStatus.UNSIGNED, sections[2].status)
        assertEquals(listOf("openai.five_hour", "openai.weekly"), sections[2].meters.map { it.id })
    }

    @Test
    fun splitsCursorGrokAndCodexAndKeepsWeekly() {
        val snapshot = UsageSnapshot(
            fetchedAtEpochMs = 1L,
            providers = listOf(
                ProviderSnapshot(
                    id = "cursor",
                    displayName = "Cursor",
                    status = ProviderStatus.OK.name,
                    planLabel = "Pro",
                    meters = listOf(
                        UsageMeter("cursor.models", "Cursor Models", usedPercent = 17.0, remainingPercent = 83.0, resetsAtEpochMs = resetMs),
                        UsageMeter("cursor.other", "Other Models", usedPercent = 100.0, remainingPercent = 0.0, resetsAtEpochMs = resetMs),
                        UsageMeter("cursor.plan_reset", "Plan reset", resetsAtEpochMs = resetMs),
                        UsageMeter("cursor.grok_bot", "Grok Bot", usedPercent = 65.0, remainingPercent = 35.0, resetsAtEpochMs = weekResetMs)
                    )
                ),
                ProviderSnapshot(
                    id = "openai",
                    displayName = "ChatGPT / Codex",
                    status = ProviderStatus.OK.name,
                    planLabel = "Plus",
                    meters = listOf(
                        UsageMeter("openai.five_hour", "5-hour", usedPercent = 0.0, remainingPercent = 100.0, resetsAtEpochMs = resetMs),
                        UsageMeter("openai.weekly", "Weekly", usedPercent = 12.0, remainingPercent = 88.0, resetsAtEpochMs = weekResetMs)
                    )
                )
            )
        )
        val sections = UsageSections.from(snapshot)
        assertEquals(listOf("cursor.models", "cursor.other", "cursor.plan_reset"), sections[0].meters.map { it.id })
        assertEquals(listOf("cursor.grok_bot"), sections[1].meters.map { it.id })
        assertEquals("Weekly usage", sections[1].meters.single().title)
        assertEquals(listOf("openai.five_hour", "openai.weekly"), sections[2].meters.map { it.id })
        assertEquals(12, sections[2].meters[1].displayUsedPercent)
        assertEquals(88, sections[2].meters[1].displayRemainingPercent)
        assertEquals("83% left", sections[0].meters[0].remainingLabel())
        assertNull(sections[0].meters[2].remainingLabel())
        assertTrue(sections[0].meters[2].resetsAtEpochMs != null)
    }

    @Test
    fun codexAlwaysExposesWeeklyEvenWhenProviderOmitsIt() {
        val snapshot = UsageSnapshot(
            fetchedAtEpochMs = 1L,
            providers = listOf(
                ProviderSnapshot(
                    id = "openai",
                    displayName = "ChatGPT / Codex",
                    status = ProviderStatus.OK.name,
                    meters = listOf(
                        UsageMeter("openai.five_hour", "5-hour", usedPercent = 40.0, remainingPercent = 60.0)
                    )
                )
            )
        )
        val weekly = UsageSections.from(snapshot)[2].meters.find { it.id == "openai.weekly" }
        assertNotNull(weekly)
        assertEquals("Weekly", weekly!!.title)
    }

    @Test
    fun grokNeedsCursorSignInWhenCursorIsUnsigned() {
        val grok = UsageSections.from(UsageSnapshot.empty(0L))[1]
        assertEquals(ProviderStatus.UNSIGNED, grok.status)
        assertEquals(ProviderId.CURSOR, grok.signInProvider)
        assertEquals("Weekly usage", grok.meters.single().title)
        assertFalse(grok.stale.isStale)
    }

    @Test
    fun lastGoodMetersStayVisibleWhenSectionIsStale() {
        val now = 1_783_000_000_000L
        val snapshot = UsageSnapshot(
            fetchedAtEpochMs = now,
            providers = listOf(
                ProviderSnapshot(id = "openai", displayName = "ChatGPT / Codex", status = ProviderStatus.ERROR.name)
            ),
            sectionHealth = listOf(
                SectionHealth(
                    sectionId = "codex",
                    lastAttemptEpochMs = now,
                    lastSuccessEpochMs = now - 20 * 60_000L,
                    lastOutcome = FetchOutcome.HTTP.name,
                    shortReason = "HTTP 503",
                    httpStatus = 503,
                    lastGoodMeters = listOf(
                        UsageMeter("openai.five_hour", "5-hour", usedPercent = 40.0, remainingPercent = 60.0),
                        UsageMeter("openai.weekly", "Weekly", usedPercent = 12.0, remainingPercent = 88.0)
                    ),
                    signedIn = true
                )
            )
        )
        val codex = UsageSections.from(snapshot, now)[2]
        assertTrue(codex.stale.isStale)
        assertEquals(StaleSeverity.RED, codex.stale.severity)
        assertEquals("HTTP 503", codex.stale.shortReason)
        assertEquals(40, codex.meters[0].displayUsedPercent)
        assertEquals(12, codex.meters[1].displayUsedPercent)
        assertTrue(codex.stale.lastUpdatedLine!!.startsWith("Last updated"))
    }
}
