package com.usagewidget.widget

import com.usagewidget.domain.ProviderId
import com.usagewidget.domain.ProviderStatus
import com.usagewidget.domain.SoftwareSection
import com.usagewidget.domain.SoftwareSectionId
import com.usagewidget.domain.UsageMeter
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetRowsTest {
    private fun meter(title: String, reset: Long) = UsageMeter(
        id = title, title = title, usedPercent = 40.0, resetsAtEpochMs = reset
    )

    private fun grouped(vararg meters: UsageMeter): List<WidgetRow> {
        val section = SoftwareSection(
            SoftwareSectionId.CODEX, ProviderId.OPENAI, ProviderStatus.OK, meters = meters.toList()
        )
        return widgetRows(listOf(section), nowMs = 0, groupByLimit = true)
    }

    @Test fun groupedWindowAppearsOnce() {
        val rows = grouped(meter("Weekly", 1000).copy(id = "openai.weekly"))
        assertEquals(listOf("Weekly"), rows.filterIsInstance<WidgetRow.Heading>().map { it.title })
        assertEquals("Codex", rows.filterIsInstance<WidgetRow.Meter>().single().title)
    }

    @Test fun onlyNumericLimitsAppear() {
        val rows = grouped(meter("Weekly", 1000), meter("Review", 1001),
            UsageMeter(id = "reset", title = "Plan reset", resetsAtEpochMs = 1000))
        assertEquals(listOf("Codex Weekly", "Review"), rows.filterIsInstance<WidgetRow.Meter>().map { it.title })
        assertEquals(3, rows.size)
    }
}
