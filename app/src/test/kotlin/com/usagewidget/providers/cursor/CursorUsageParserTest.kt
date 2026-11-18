package com.usagewidget.providers.cursor

import com.usagewidget.domain.MeterBarKind
import com.usagewidget.loadFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        assertEquals(Instant.parse("2026-10-11T00:00:00.000Z").toEpochMilli(), plan.windowStartEpochMs)
        assertNull(plan.onDemand)
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
        assertEquals(Instant.parse("2026-10-30T13:00:00.000Z").toEpochMilli(), grok.windowStartEpochMs)
    }

    @Test
    fun sandUsageAfterNov13_2026ResetRollsDateOnlyNextResetForward() {
        val zone = ZoneId.of("America/New_York")
        val resetInstant = java.time.ZonedDateTime.of(2026, 11, 13, 14, 18, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
        val fetchedAt = resetInstant + 3 * 60_000L
        val json = """
            {
              "currentPeriodStart": "2026-11-13T19:18:00.000Z",
              "nextResetTimestampUtc": "2026-11-13T00:00:00.000Z",
              "usagePercent": 0,
              "hasNonZeroIncludedLimit": true
            }
        """.trimIndent()
        val grok = CursorUsageParser.parseSandUsage(json, fetchedAt)
        assertEquals(0.0, grok.usedPercent, 0.01)
        assertEquals(resetInstant, grok.windowStartEpochMs)
        val ny = Instant.ofEpochMilli(grok.resetsAtEpochMs!!).atZone(zone)
        assertEquals(20, ny.dayOfMonth)
        assertEquals(14, ny.hour)
        assertEquals(18, ny.minute)
        val meter = CursorUsageParser.toSnapshot(null, grok).meters.single()
        assertEquals("0% used", meter.usedLabel())
        assertEquals(resetInstant, meter.windowStartEpochMs)
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
        assertNull(extracted.plan.onDemand)
        assertNull(extracted.grok!!.onDemand)
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

    @Test
    fun fractionalWirePercentIsAlreadyPercentUsedNotAFraction() {
        val json = """
            {
              "billingCycleEnd": "2026-11-11T00:00:00.000Z",
              "membershipType": "pro",
              "individualUsage": {
                "plan": {
                  "autoPercentUsed": 0.4,
                  "apiPercentUsed": 0.711
                }
              }
            }
        """.trimIndent()
        val plan = CursorUsageParser.parseUsageSummary(json, snapshotTime)
        assertEquals(0.4, plan.cursorModelsUsedPercent!!, 0.0001)
        assertEquals(0.711, plan.otherModelsUsedPercent!!, 0.0001)
        val snapshot = CursorUsageParser.toSnapshot(plan, null)
        assertEquals("0% used", snapshot.meters[0].usedLabel())
        assertEquals("1% used", snapshot.meters[1].usedLabel())
    }

    @Test
    fun grokUsagePercentBelowOneStaysAPercent() {
        val json = """
            {
              "usagePercent": 0.4,
              "hasNonZeroIncludedLimit": true,
              "nextResetTimestampUtc": "2026-11-13T00:00:00.000Z"
            }
        """.trimIndent()
        val grok = CursorUsageParser.parseSandUsage(json, snapshotTime)
        assertEquals(0.4, grok.usedPercent, 0.0001)
        assertEquals("0% used", CursorUsageParser.toSnapshot(null, grok).meters.single().usedLabel())
    }

    @Test
    fun onDemandReadsCentsFromIndividualUsage() {
        val json = """
            {
              "billingCycleEnd": "2026-11-11T00:00:00.000Z",
              "membershipType": "pro",
              "individualUsage": {
                "plan": { "autoPercentUsed": 17, "apiPercentUsed": 40 },
                "onDemand": { "enabled": true, "used": 1234, "limit": 5000, "remaining": 3766 }
              }
            }
        """.trimIndent()
        val plan = CursorUsageParser.parseUsageSummary(json, snapshotTime)
        assertEquals(1234.0, plan.onDemand!!.usedCents, 0.01)
        assertEquals(5000.0, plan.onDemand!!.limitCents, 0.01)
        assertEquals("On-demand  $12.34 / $50", plan.onDemand!!.title())
        val meter = plan.onDemand!!.toMeter("cursor.on_demand")
        assertEquals(MeterBarKind.CAP, meter.barKind)
        assertEquals("25% used", meter.usedLabel())
        val snapshot = CursorUsageParser.toSnapshot(plan, null)
        assertEquals(
            listOf("cursor.models", "cursor.other", "cursor.on_demand", "cursor.plan_reset", "cursor.grok_on_demand"),
            snapshot.meters.map { it.id }
        )
        assertEquals("On-demand  $12.34 / $50", snapshot.meters[2].title)
    }

    @Test
    fun onDemandHidesWhenDisabledMissingOrZeroLimit() {
        assertNull(
            CursorUsageParser.parseUsageSummary(
                """{"billingCycleEnd":"2026-11-11T00:00:00.000Z","individualUsage":{"plan":{"autoPercentUsed":1},"onDemand":{"enabled":false,"used":1234,"limit":5000}}}""",
                snapshotTime
            ).onDemand
        )
        assertNull(
            CursorUsageParser.parseUsageSummary(
                """{"billingCycleEnd":"2026-11-11T00:00:00.000Z","individualUsage":{"plan":{"autoPercentUsed":1},"onDemand":{"enabled":true,"used":1234,"limit":null}}}""",
                snapshotTime
            ).onDemand
        )
        assertNull(
            CursorUsageParser.parseUsageSummary(
                """{"billingCycleEnd":"2026-11-11T00:00:00.000Z","individualUsage":{"plan":{"autoPercentUsed":1},"onDemand":{"enabled":true,"used":1234,"limit":0}}}""",
                snapshotTime
            ).onDemand
        )
        assertNull(
            CursorUsageParser.parseUsageSummary(
                """{"billingCycleEnd":"2026-11-11T00:00:00.000Z","individualUsage":{"plan":{"autoPercentUsed":1},"onDemand":{"enabled":true,"limit":5000}}}""",
                snapshotTime
            ).onDemand
        )
    }

    @Test
    fun onDemandDerivesSpendFromRemainingAndFallsBackToTeamUsage() {
        val remainingOnly = CursorUsageParser.parseUsageSummary(
            """{"billingCycleEnd":"2026-11-11T00:00:00.000Z","individualUsage":{"plan":{"autoPercentUsed":1},"onDemand":{"enabled":true,"limit":5000,"remaining":3766}}}""",
            snapshotTime
        )
        assertEquals(1234.0, remainingOnly.onDemand!!.usedCents, 0.01)
        val team = CursorUsageParser.parseUsageSummary(
            """{"billingCycleEnd":"2026-11-11T00:00:00.000Z","individualUsage":{"plan":{"autoPercentUsed":1},"onDemand":{"enabled":true,"used":0,"limit":null}},"teamUsage":{"onDemand":{"enabled":true,"used":2309,"limit":10000}}}""",
            snapshotTime
        )
        assertEquals(2309.0, team.onDemand!!.usedCents, 0.01)
        assertEquals("On-demand  $23.09 / $100", team.onDemand!!.title())
    }

    @Test
    fun grokSandWithoutDollarsFallsBackToCursorOnDemand() {
        val plan = CursorUsageParser.parseUsageSummary(
            """{"billingCycleEnd":"2026-11-11T00:00:00.000Z","individualUsage":{"plan":{"autoPercentUsed":17,"apiPercentUsed":40},"onDemand":{"enabled":true,"used":1234,"limit":5000}}}""",
            snapshotTime
        )
        val grok = CursorUsageParser.parseSandUsage(loadFixture("cursor-sand-usage.json"), snapshotTime)
        assertNull(grok.onDemand)
        val snapshot = CursorUsageParser.toSnapshot(plan, grok)
        val grokCap = snapshot.meters.single { it.id == "cursor.grok_on_demand" }
        assertEquals("On-demand  $12.34 / $50", grokCap.title)
        assertEquals(MeterBarKind.CAP, grokCap.barKind)
    }

    @Test
    fun grokSandParsesOnDemandSettingsWhenPresent() {
        val grok = CursorUsageParser.parseSandUsage(
            """
            {
              "usagePercent": 10,
              "hasNonZeroIncludedLimit": true,
              "nextResetTimestampUtc": "2026-11-13T00:00:00.000Z",
              "onDemandSettings": { "enabled": true, "used": 2500, "limit": 5000 }
            }
            """.trimIndent(),
            snapshotTime
        )
        assertEquals(2500.0, grok.onDemand!!.usedCents, 0.01)
        val snapshot = CursorUsageParser.toSnapshot(null, grok)
        assertEquals("On-demand  $25 / $50", snapshot.meters.single { it.id == "cursor.grok_on_demand" }.title)
    }

    @Test
    fun htmlOnDemandParsesDollarPairAndSkipsDisabledCopy() {
        val enabled = CursorHtmlExtractor.extract(
            """
            Cursor Models 17% used
            Other Models 40% used
            On-demand $12.34 / $50
            """.trimIndent(),
            snapshotTime
        )
        assertEquals("On-demand  $12.34 / $50", enabled.plan.onDemand!!.title())
        val disabled = CursorHtmlExtractor.extract(loadFixture("cursor-spending-page.txt"), snapshotTime)
        assertNull(disabled.plan.onDemand)
    }
}
