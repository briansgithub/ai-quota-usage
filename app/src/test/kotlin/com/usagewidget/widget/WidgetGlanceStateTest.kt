package com.usagewidget.widget

import com.usagewidget.domain.FetchAttempt
import com.usagewidget.domain.FetchOutcome
import com.usagewidget.domain.ProviderSnapshot
import com.usagewidget.domain.ProviderStatus
import com.usagewidget.domain.SectionHealth
import com.usagewidget.domain.SoftwareSectionId
import com.usagewidget.domain.UsageMeter
import com.usagewidget.domain.UsageSections
import com.usagewidget.domain.UsageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetGlanceStateTest {
    @Test
    fun roundTripKeepsMetersAndStripsDiagnostics() {
        val snapshot = UsageSnapshot(
            fetchedAtEpochMs = 1_783_000_000_000L,
            providers = listOf(
                ProviderSnapshot(
                    id = "cursor",
                    displayName = "Cursor",
                    status = ProviderStatus.OK.name,
                    meters = listOf(
                        UsageMeter("cursor.models", "Cursor Models", usedPercent = 17.0, remainingPercent = 83.0)
                    ),
                    diagnosticJson = """{"secret":"do-not-store"}"""
                )
            ),
            sectionHealth = listOf(
                SectionHealth(
                    sectionId = "cursor",
                    lastGoodDiagnosticJson = """{"token":"nope"}""",
                    lastGoodMeters = listOf(
                        UsageMeter("cursor.models", "Cursor Models", usedPercent = 17.0, remainingPercent = 83.0)
                    )
                )
            ),
            attemptLog = listOf(
                FetchAttempt.of(
                    atEpochMs = 1_783_000_000_000L,
                    sectionId = SoftwareSectionId.CURSOR,
                    outcome = FetchOutcome.SUCCESS
                )
            )
        )
        val encoded = WidgetGlanceState.encodeSnapshot(snapshot)
        val decoded = WidgetGlanceState.decodeSnapshot(encoded)!!
        assertEquals(1_783_000_000_000L, decoded.fetchedAtEpochMs)
        assertEquals(17.0, decoded.providers.single().meters.single().usedPercent)
        assertEquals(83.0, decoded.providers.single().meters.single().remainingPercent)
        assertNull(decoded.providers.single().diagnosticJson)
        assertNull(decoded.sectionHealth.single().lastGoodDiagnosticJson)
        assertEquals(0, decoded.attemptLog.size)
    }

    @Test
    fun blankJsonIsNull() {
        assertNull(WidgetGlanceState.decodeSnapshot(null))
        assertNull(WidgetGlanceState.decodeSnapshot(""))
        assertNull(WidgetGlanceState.decodeSnapshot("not-json"))
    }

    @Test
    fun updatedAgoLabelUsesLastSuccess() {
        val fetched = 1_783_000_000_000L
        val snapshot = UsageSnapshot(
            fetchedAtEpochMs = fetched,
            providers = emptyList(),
            sectionHealth = listOf(
                SectionHealth(
                    sectionId = "codex",
                    lastAttemptEpochMs = fetched,
                    lastSuccessEpochMs = fetched - 3 * 60_000L,
                    lastOutcome = FetchOutcome.SUCCESS.name,
                    signedIn = true
                )
            )
        )
        val sections = UsageSections.from(snapshot, fetched)
        assertEquals("updated 3m ago", updatedAgoLabel(snapshot, sections, fetched, compact = false))
        assertEquals("3m ago", updatedAgoLabel(snapshot, sections, fetched, compact = true))
        assertTrue(updatedAgoLabel(snapshot, sections, fetched, compact = false).contains("ago"))
    }
}
