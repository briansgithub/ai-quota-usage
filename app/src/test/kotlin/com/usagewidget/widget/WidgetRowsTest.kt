package com.usagewidget.widget

import com.usagewidget.domain.ManualResetCredit
import com.usagewidget.domain.ManualResets
import com.usagewidget.domain.ProviderId
import com.usagewidget.domain.ProviderStatus
import com.usagewidget.domain.SoftwareSection
import com.usagewidget.domain.SoftwareSectionId
import com.usagewidget.domain.UsageMeter
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetRowsTest {
    private fun meter(id: String, title: String, reset: Long) = UsageMeter(
        id = id, title = title, usedPercent = 40.0, resetsAtEpochMs = reset
    )

    @Test fun groupedWindowAppearsOnce() {
        val section = SoftwareSection(
            SoftwareSectionId.CODEX, ProviderId.OPENAI, ProviderStatus.OK,
            meters = listOf(meter("openai.weekly", "Weekly", 1000))
        )
        val rows = widgetRows(listOf(section), nowMs = 0, groupByLimit = true)
        assertEquals(listOf("Weekly"), rows.filterIsInstance<WidgetRow.Heading>().map { it.title })
        assertEquals("Codex", rows.filterIsInstance<WidgetRow.Meter>().single().title)
        assertEquals(1000L, rows.filterIsInstance<WidgetRow.Reset>().single().at)
    }

    @Test fun identicalResetsShareOneCaptionWithinGemini() {
        val section = SoftwareSection(
            SoftwareSectionId.GEMINI, ProviderId.GEMINI, ProviderStatus.OK,
            meters = listOf(
                meter("gemini.gemini-5h", "Gemini 5-hour", 1000),
                meter("gemini.3p-5h", "Third-party 5-hour", 1000),
                meter("gemini.gemini-weekly", "Gemini weekly", 2000)
            )
        )
        val rows = widgetRows(listOf(section), nowMs = 0, groupByLimit = true)
        val thirdParty = rows.filterIsInstance<WidgetRow.Meter>().single { it.title == "3rd-party" }
        assertEquals(true, thirdParty.indented)
        val gemini = rows.indexOf(WidgetRow.Heading("Gemini", software = true))
        assertEquals("Google", (rows[gemini + 1] as WidgetRow.Meter).title)
        assertEquals(thirdParty, rows[gemini + 2])
        assertEquals(listOf(2000L, 1000L), rows.filterIsInstance<WidgetRow.Reset>().map { it.at })

        val thirdPartyOnly = section.copy(meters = listOf(thirdParty.meter))
        val singleRows = widgetRows(listOf(thirdPartyOnly), nowMs = 0, groupByLimit = true)
        assertEquals(WidgetRow.Heading("Gemini", software = true), singleRows[1])
        assertEquals("3rd-party", (singleRows[2] as WidgetRow.Meter).title)
    }

    @Test fun manualResetCountAndExpiryAppearOnceAcrossGroups() {
        val expiry = Instant.parse("2026-11-12T02:00:00Z").toEpochMilli()
        val section = SoftwareSection(
            SoftwareSectionId.CODEX, ProviderId.OPENAI, ProviderStatus.OK,
            meters = listOf(
                meter("openai.weekly", "Weekly", 1000),
                meter("openai.five_hour", "5-hour", 2000)
            ),
            manualResets = ManualResets(2, List(2) { ManualResetCredit(expiresAtEpochMs = expiry) })
        )
        val rows = widgetRows(listOf(section), nowMs = 0, groupByLimit = true)
        assertEquals("Manual [2]  11/11, 11/11", rows.filterIsInstance<WidgetRow.Manual>().single().text)
        assertEquals(listOf(1000L, 2000L), rows.filterIsInstance<WidgetRow.Reset>().map { it.at })
    }

    @Test fun slackAppliesToWeeklyBarAndUsesTheHiddenSession() {
        val hour = 3_600_000L
        val weekly = meter("openai.weekly", "Weekly", 40 * hour)
        val session = meter("openai.five_hour", "5-hour", 3 * hour)
        val section = SoftwareSection(
            SoftwareSectionId.CODEX, ProviderId.OPENAI, ProviderStatus.OK, meters = listOf(weekly, session)
        )
        val allRows = widgetRows(listOf(section), nowMs = 0, groupByLimit = true)
        assertEquals(listOf(12 * hour, null), allRows.filterIsInstance<WidgetRow.Meter>().map { it.slackMs })
        val filtered = section.copy(meters = listOf(weekly))
        val rows = widgetRows(listOf(filtered), nowMs = 0, pacingSections = listOf(section))
        assertEquals(12 * hour, rows.filterIsInstance<WidgetRow.Meter>().single().slackMs)
    }

    @Test fun distinctPlanResetIsVisibleEvenWithoutBars() {
        val section = SoftwareSection(
            SoftwareSectionId.CURSOR, ProviderId.CURSOR, ProviderStatus.OK,
            meters = listOf(UsageMeter(id = "cursor.plan_reset", title = "Plan reset", resetsAtEpochMs = 3000))
        )
        val rows = widgetRows(listOf(section), nowMs = 0, groupByLimit = true)
        assertEquals(
            listOf(WidgetRow.Heading("Cursor", software = true), WidgetRow.Reset(3000, "Plan", indented = true)),
            rows
        )
    }
}
