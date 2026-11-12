package com.usagewidget.providers.openai

import com.usagewidget.loadFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class OpenAiUsageParserTest {
    private val snapshotTime = Instant.parse("2026-11-05T15:30:26Z").toEpochMilli()

    @Test
    fun sessionReadsAccessTokenWithoutStoringIdentity() {
        val session = OpenAiUsageParser.parseSession(loadFixture("openai-session.json"))
        assertTrue(session.accessToken.startsWith("eyJ"))
        assertEquals("acct_REDACTED", session.accountId)
    }

    @Test
    fun whamUsageMatchesOverviewSnapshot() {
        val usage = OpenAiUsageParser.parseWhamUsage(loadFixture("openai-wham-usage.json"), snapshotTime)
        assertEquals("Plus", usage.planLabel)
        assertEquals(WindowKind.FIVE_HOUR, usage.fiveHour!!.kind)
        assertEquals(WindowKind.WEEKLY, usage.weekly!!.kind)
        assertEquals(0.0, usage.fiveHour!!.usedPercent!!, 0.01)
        assertEquals(0.0, usage.weekly!!.usedPercent!!, 0.01)
        val fiveReset = Instant.ofEpochMilli(usage.fiveHour!!.resetsAtEpochMs!!)
        val weekReset = Instant.ofEpochMilli(usage.weekly!!.resetsAtEpochMs!!)
        assertEquals(Instant.parse("2026-11-05T20:20:26Z"), fiveReset)
        assertEquals(Instant.parse("2026-11-12T14:30:26Z"), weekReset)
        val snapshot = OpenAiUsageParser.toSnapshot(usage)
        assertEquals(listOf("openai.five_hour", "openai.weekly"), snapshot.meters.map { it.id })
        assertEquals(100, snapshot.meters[0].displayRemainingPercent)
        assertEquals(100, snapshot.meters[1].displayRemainingPercent)
        assertEquals("100% left", snapshot.meters[1].remainingLabel())
        assertEquals("5-hour", snapshot.meters[0].title)
        assertEquals("Weekly", snapshot.meters[1].title)
    }

    @Test
    fun htmlFallbackReadsPercentLeftAndRelativeResets() {
        val usage = OpenAiHtmlExtractor.extract(loadFixture("openai-usage-page.txt"), snapshotTime)
        assertEquals(0.0, usage.fiveHour!!.usedPercent!!, 0.01)
        assertEquals(0.0, usage.weekly!!.usedPercent!!, 0.01)
        assertNotNull(usage.fiveHour!!.resetsAtEpochMs)
        assertNotNull(usage.weekly!!.resetsAtEpochMs)
        val fiveDeltaMin = (usage.fiveHour!!.resetsAtEpochMs!! - snapshotTime) / 60_000
        val weekDeltaHour = (usage.weekly!!.resetsAtEpochMs!! - snapshotTime) / 3_600_000
        assertEquals(4 * 60 + 50, fiveDeltaMin)
        assertEquals(6 * 24 + 23, weekDeltaHour)
    }

    @Test
    fun classifiesWindowsByDurationNotSlotName() {
        val json = """
            {
              "rate_limit": {
                "primary_window": {
                  "used_percent": 12,
                  "limit_window_seconds": 604800,
                  "reset_at": 1794493826
                },
                "secondary_window": {
                  "used_percent": 40,
                  "limit_window_seconds": 18000,
                  "reset_at": 1793910026
                }
              }
            }
        """.trimIndent()
        val usage = OpenAiUsageParser.parseWhamUsage(json, snapshotTime)
        assertEquals(WindowKind.WEEKLY, usage.weekly!!.kind)
        assertEquals(12.0, usage.weekly!!.usedPercent!!, 0.01)
        assertEquals(WindowKind.FIVE_HOUR, usage.fiveHour!!.kind)
        assertEquals(40.0, usage.fiveHour!!.usedPercent!!, 0.01)
    }

    @Test
    fun snapshotAlwaysIncludesWeeklyMeter() {
        val json = """
            {
              "plan_type": "plus",
              "rate_limit": {
                "primary_window": {
                  "used_percent": 22,
                  "limit_window_seconds": 18000,
                  "reset_at": 1793910026
                }
              }
            }
        """.trimIndent()
        val usage = OpenAiUsageParser.parseWhamUsage(json, snapshotTime)
        val snapshot = OpenAiUsageParser.toSnapshot(usage)
        assertEquals(listOf("openai.five_hour", "openai.weekly"), snapshot.meters.map { it.id })
        assertEquals(22, snapshot.meters[0].displayUsedPercent)
        assertEquals(78, snapshot.meters[0].displayRemainingPercent)
        assertEquals("Weekly", snapshot.meters[1].title)
        assertEquals("No weekly window in response", snapshot.meters[1].resetLabel)
    }

    @Test
    fun classifiesNearWeeklyDurationsAsWeekly() {
        assertEquals(WindowKind.WEEKLY, OpenAiUsageParser.classifyWindow(604_800L, "primary"))
        assertEquals(WindowKind.WEEKLY, OpenAiUsageParser.classifyWindow(691_200L, null))
        assertEquals(WindowKind.WEEKLY, OpenAiUsageParser.classifyWindow(500_000L, "primary"))
        assertEquals(WindowKind.FIVE_HOUR, OpenAiUsageParser.classifyWindow(18_000L, "secondary"))
        assertEquals(WindowKind.WEEKLY, OpenAiUsageParser.classifyWindow(null, "secondary"))
    }
}
