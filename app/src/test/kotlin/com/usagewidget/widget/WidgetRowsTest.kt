package com.usagewidget.widget

import com.usagewidget.domain.ProviderId
import com.usagewidget.domain.ProviderStatus
import com.usagewidget.domain.SoftwareSection
import com.usagewidget.domain.SoftwareSectionId
import com.usagewidget.domain.UsageMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun singleLimitKeepsProviderAndWindowOnOneLine() {
        val rows = grouped(meter("Weekly", 1000))
        assertFalse(rows.any { it is WidgetRow.Header })
        assertEquals("Codex · Weekly", rows.filterIsInstance<WidgetRow.Meter>().single().title)
    }

    @Test fun onlyIdenticalResetTimesAreShared() {
        val weekly = meter("Weekly", 1000)
        val review = meter("Review", 1000)
        val shared = grouped(weekly, review)
        assertEquals(1000L, shared.filterIsInstance<WidgetRow.SharedReset>().single().at)
        assertEquals(2, shared.filterIsInstance<WidgetRow.Meter>().count { !it.showReset })
        val independent = grouped(weekly, review.copy(resetsAtEpochMs = 1001))
        assertFalse(independent.any { it is WidgetRow.SharedReset })
        assertTrue(independent.filterIsInstance<WidgetRow.Meter>().all { it.showReset })
    }
}
