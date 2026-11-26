package com.usagewidget.domain

import com.usagewidget.providers.claude.ClaudeMeterIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetBarsTest {
    private val snapshot = UsageSnapshot(
        fetchedAtEpochMs = 1L,
        providers = listOf(
            ProviderSnapshot(
                id = "openai", displayName = "Codex", status = "OK",
                meters = listOf(
                    UsageMeter("openai.five_hour", "5-hour", usedPercent = 10.0, remainingPercent = 90.0),
                    UsageMeter("openai.weekly", "Weekly", usedPercent = 20.0, remainingPercent = 80.0)
                )
            ),
            ProviderSnapshot(
                id = "claude", displayName = "Claude", status = "OK",
                meters = listOf(
                    UsageMeter(ClaudeMeterIds.FIVE_HOUR, "Session", usedPercent = 30.0, remainingPercent = 70.0),
                    UsageMeter(ClaudeMeterIds.WEEKLY, "Weekly", usedPercent = 40.0, remainingPercent = 60.0)
                )
            )
        )
    )

    @Test
    fun nothingHiddenReturnsSameSections() {
        val sections = UsageSections.from(snapshot, 1L)
        assertSame(sections, WidgetBars.apply(sections, emptySet()))
    }

    @Test
    fun hidesOnlyTheChosenBar() {
        val shown = WidgetBars.apply(UsageSections.from(snapshot, 1L), setOf("openai.five_hour"))
        assertEquals(listOf("openai.weekly"), shown.single { it.id == SoftwareSectionId.CODEX }.meters.map { it.id })
        assertEquals(2, shown.single { it.id == SoftwareSectionId.CLAUDE }.meters.size)
    }

    @Test
    fun hidingEveryBarOfAServiceDropsItsCard() {
        val hidden = WidgetBars.catalog.filter { it.section == SoftwareSectionId.CLAUDE }.map { it.meterId }.toSet()
        val shown = WidgetBars.apply(UsageSections.from(snapshot, 1L), hidden)
        assertTrue(shown.none { it.id == SoftwareSectionId.CLAUDE })
        assertTrue(shown.any { it.id == SoftwareSectionId.CODEX })
    }

    @Test
    fun reordersSectionsByCustomOrder() {
        val custom = listOf(SoftwareSectionId.GEMINI, SoftwareSectionId.CLAUDE, SoftwareSectionId.CODEX)
        val shown = WidgetBars.apply(UsageSections.from(snapshot, 1L), emptySet(), custom)
        assertEquals(
            listOf(
                SoftwareSectionId.GEMINI,
                SoftwareSectionId.CLAUDE,
                SoftwareSectionId.CODEX,
                SoftwareSectionId.CURSOR,
                SoftwareSectionId.GROK_BOT
            ),
            shown.map { it.id }
        )
    }

    @Test
    fun normalizeAndParseSectionOrderHandlesDuplicatesAndUnknowns() {
        val parsed = WidgetBars.parseSectionOrder(listOf(" claude ", "bogus", "gemini", "CLAUDE", ""))
        assertEquals(
            listOf(
                SoftwareSectionId.CLAUDE,
                SoftwareSectionId.GEMINI,
                SoftwareSectionId.CURSOR,
                SoftwareSectionId.GROK_BOT,
                SoftwareSectionId.CODEX
            ),
            parsed
        )
    }

    @Test
    fun moveSectionMovesAndClampsAtBounds() {
        val initial = WidgetBars.defaultSectionOrder
        assertEquals(initial, WidgetBars.moveSection(initial, SoftwareSectionId.CURSOR, -1))
        assertEquals(initial, WidgetBars.moveSection(initial, SoftwareSectionId.GEMINI, 1))

        val movedUp = WidgetBars.moveSection(initial, SoftwareSectionId.GEMINI, -1)
        assertEquals(
            listOf(
                SoftwareSectionId.CURSOR,
                SoftwareSectionId.GROK_BOT,
                SoftwareSectionId.CODEX,
                SoftwareSectionId.GEMINI,
                SoftwareSectionId.CLAUDE
            ),
            movedUp
        )

        val movedDown = WidgetBars.moveSection(initial, SoftwareSectionId.CURSOR, 1)
        assertEquals(
            listOf(
                SoftwareSectionId.GROK_BOT,
                SoftwareSectionId.CURSOR,
                SoftwareSectionId.CODEX,
                SoftwareSectionId.CLAUDE,
                SoftwareSectionId.GEMINI
            ),
            movedDown
        )
    }

    @Test
    fun reorderAndHideAllBarsOfSectionDropsHiddenAndPreservesOrder() {
        val hidden = WidgetBars.catalog.filter { it.section == SoftwareSectionId.CLAUDE }.map { it.meterId }.toSet()
        val order = listOf(SoftwareSectionId.CLAUDE, SoftwareSectionId.CODEX, SoftwareSectionId.GEMINI)
        val shown = WidgetBars.apply(UsageSections.from(snapshot, 1L), hidden, order)
        assertEquals(
            listOf(
                SoftwareSectionId.CODEX,
                SoftwareSectionId.GEMINI,
                SoftwareSectionId.CURSOR,
                SoftwareSectionId.GROK_BOT
            ),
            shown.map { it.id }
        )
    }
}
