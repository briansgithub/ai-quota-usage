package com.usagewidget.providers.cursor

import com.usagewidget.loadFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import com.usagewidget.util.NyTime
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class CursorUsageParserTest {
    private val snapshotTime = Instant.parse("2026-11-05T15:30:13Z").toEpochMilli()

    @Test
    fun usageSummaryMatchesSpendingSnapshot() {
        val plan = CursorUsageParser.parseUsageSummary(
            loadFixture("cursor-usage-summary.json"),
            snapshotTime
        )
        assertEquals("Pro", plan.planLabel)
        assertEquals(17.0, plan.cursorModelsUsedPercent!!, 0.01)
        assertEquals(100.0, plan.otherModelsUsedPercent!!, 0.01)
        val reset = Instant.ofEpochMilli(plan.resetsAtEpochMs!!)
        val utc = reset.atZone(ZoneOffset.UTC)
        assertEquals(2026, utc.year)
        assertEquals(11, utc.monthValue)
        assertEquals(11, utc.dayOfMonth)
    }

    @Test
    fun sandUsageCamelCaseOverlaysNyClockFromPeriodStart() {
        val grok = CursorUsageParser.parseSandUsage(
            loadFixture("cursor-sand-usage.json"),
            snapshotTime
        )
        assertEquals(65.0, grok.usedPercent, 0.01)
        assertTrue(grok.hasIncludedAllowance)
        val reset = Instant.ofEpochMilli(grok.resetsAtEpochMs!!).atZone(ZoneId.of("America/New_York"))
        assertEquals(6, reset.dayOfMonth)
        assertEquals(9, reset.hour)
        assertEquals(0, reset.minute)
        assertEquals("Reset: Fri 9 AM (22h)", NyTime.formatResetLine(grok.resetsAtEpochMs!!, snapshotTime))
    }

    @Test
    fun sandUsageSnakeCaseOverlaysNyClockFromPeriodStart() {
        val grok = CursorUsageParser.parseSandUsage(
            loadFixture("cursor-sand-usage-snake.json"),
            snapshotTime
        )
        assertEquals(65.0, grok.usedPercent, 0.01)
        val reset = Instant.ofEpochMilli(grok.resetsAtEpochMs!!).atZone(ZoneId.of("America/New_York"))
        assertEquals(6, reset.dayOfMonth)
        assertEquals(9, reset.hour)
        assertEquals("Reset: Fri 9 AM (22h)", NyTime.formatResetLine(grok.resetsAtEpochMs!!, snapshotTime))
    }

    @Test
    fun sandUsageKeepsExactTimedReset() {
        val json = """
            {
              "currentPeriodStart": "2026-09-11T18:43:19.645Z",
              "nextResetTimestampUtc": "2026-11-06T18:43:19.645Z",
              "usagePercent": 19,
              "hasNonZeroIncludedLimit": true
            }
        """.trimIndent()
        val grok = CursorUsageParser.parseSandUsage(json, snapshotTime)
        assertEquals(Instant.parse("2026-11-06T18:43:19.645Z").toEpochMilli(), grok.resetsAtEpochMs)
        val ny = Instant.ofEpochMilli(grok.resetsAtEpochMs!!).atZone(ZoneId.of("America/New_York"))
        assertEquals(13, ny.hour)
        assertEquals(43, ny.minute)
    }

    @Test
    fun sandUsageNumericEpochMs() {
        val epoch = Instant.parse("2026-11-06T14:00:00Z").toEpochMilli()
        val json = """
            {
              "nextResetTimestampUtc": $epoch,
              "usagePercent": 40,
              "hasNonZeroIncludedLimit": true
            }
        """.trimIndent()
        val grok = CursorUsageParser.parseSandUsage(json, snapshotTime)
        assertEquals(epoch, grok.resetsAtEpochMs)
        assertEquals("Reset: Fri 9 AM (22h)", NyTime.formatResetLine(grok.resetsAtEpochMs!!, snapshotTime))
    }

    @Test
    fun sandUsageMidnightUtcWithoutPeriodStartStaysDateOnly() {
        val json = """
            {
              "nextResetTimestampUtc": "2026-11-06T00:00:00.000Z",
              "usagePercent": 65,
              "hasNonZeroIncludedLimit": true
            }
        """.trimIndent()
        val grok = CursorUsageParser.parseSandUsage(json, snapshotTime)
        assertEquals(Instant.parse("2026-11-06T00:00:00.000Z").toEpochMilli(), grok.resetsAtEpochMs)
    }

    @Test
    fun combinedSnapshotHasPlanResetAndGrok() {
        val plan = CursorUsageParser.parseUsageSummary(loadFixture("cursor-usage-summary.json"), snapshotTime)
        val grok = CursorUsageParser.parseSandUsage(loadFixture("cursor-sand-usage.json"), snapshotTime)
        val snapshot = CursorUsageParser.toSnapshot(plan, grok)
        assertEquals(
            listOf("cursor.models", "cursor.other", "cursor.plan_reset", "cursor.grok_bot"),
            snapshot.meters.map { it.id }
        )
        assertEquals(83, snapshot.meters[0].displayRemainingPercent)
        assertEquals(0, snapshot.meters[1].displayRemainingPercent)
        assertEquals(plan.resetsAtEpochMs, snapshot.meters[2].resetsAtEpochMs)
        assertEquals(35, snapshot.meters[3].displayRemainingPercent)
    }

    @Test
    fun htmlFallbackMatchesSpendingPageCopy() {
        val extracted = CursorHtmlExtractor.extract(loadFixture("cursor-spending-page.txt"), snapshotTime)
        assertEquals(17.0, extracted.plan.cursorModelsUsedPercent!!, 0.01)
        assertEquals(100.0, extracted.plan.otherModelsUsedPercent!!, 0.01)
        assertEquals(65.0, extracted.grok!!.usedPercent, 0.01)
        assertNotNull(extracted.plan.resetsAtEpochMs)
        assertNotNull(extracted.grok!!.resetsAtEpochMs)
        val planDay = Instant.ofEpochMilli(extracted.plan.resetsAtEpochMs!!)
            .atZone(java.time.ZoneId.of("America/New_York")).dayOfMonth
        val grokDay = Instant.ofEpochMilli(extracted.grok!!.resetsAtEpochMs!!)
            .atZone(java.time.ZoneId.of("America/New_York")).dayOfMonth
        assertEquals(11, planDay)
        assertEquals(6, grokDay)
    }

    @Test
    fun displayMessageFallbackWhenPercentsMissing() {
        val json = """
            {
              "billingCycleEnd": "2026-11-11T00:00:00.000Z",
              "membershipType": "pro",
              "autoModelSelectedDisplayMessage": "You've used 17% of your included total usage",
              "namedModelSelectedDisplayMessage": "You've used 100% of your included API usage"
            }
        """.trimIndent()
        val plan = CursorUsageParser.parseUsageSummary(json, snapshotTime)
        assertEquals(17.0, plan.cursorModelsUsedPercent!!, 0.01)
        assertEquals(100.0, plan.otherModelsUsedPercent!!, 0.01)
    }
}
