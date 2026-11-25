package com.usagewidget.domain

import com.usagewidget.loadFixture
import com.usagewidget.providers.openai.OpenAiHtmlExtractor
import com.usagewidget.providers.openai.OpenAiUsageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ManualResetParserTest {
    private val now = Instant.parse("2026-11-05T15:30:26Z").toEpochMilli()

    @Test
    fun usageSummaryCountWithoutCreditsArray() {
        val json = """
            {
              "plan_type": "plus",
              "rate_limit": {
                "primary_window": { "used_percent": 10, "limit_window_seconds": 18000, "reset_at": 1793910026 }
              },
              "rate_limit_reset_credits": { "available_count": 1 }
            }
        """.trimIndent()
        val usage = OpenAiUsageParser.parseWhamUsage(json, now)
        assertEquals(1, usage.manualResets!!.availableCount)
        assertTrue(usage.manualResets!!.observedJson!!.contains("available_count"))
        assertTrue(usage.manualResets!!.isPresent)
        assertEquals("Manual resets: 1", usage.manualResets!!.displayLine())
        val snapshot = OpenAiUsageParser.toSnapshot(usage)
        assertEquals("Manual resets: 1", snapshot.manualResets!!.displayLine())
        assertFalse(snapshot.diagnosticJson!!.contains("RateLimitResetCredit_"))
    }

    @Test
    fun detailsEndpointAddsExpiryMmDd() {
        val usageJson = """
            {
              "rate_limit": {
                "primary_window": { "used_percent": 0, "limit_window_seconds": 18000, "reset_at": 1793910026 }
              },
              "rate_limit_reset_credits": { "available_count": 1 }
            }
        """.trimIndent()
        val details = """
            {
              "available_count": 1,
              "credits": [
                {
                  "id": "RateLimitResetCredit_SECRET",
                  "reset_type": "codex_rate_limits",
                  "status": "available",
                  "expires_at": "2026-11-12T17:00:00Z",
                  "title": "Full reset (Weekly + 5 hr)"
                }
              ]
            }
        """.trimIndent()
        val usage = OpenAiUsageParser.mergeResetDetails(
            OpenAiUsageParser.parseWhamUsage(usageJson, now),
            details,
            now
        )
        val line = usage.manualResets!!.displayLine()!!
        assertEquals("Manual resets: 1 (exp 11/12)", line)
        assertFalse(usage.observedJson!!.contains("RateLimitResetCredit_SECRET"))
        assertTrue(usage.observedJson!!.contains("expires_at"))
        assertTrue(usage.observedJson!!.contains("codex_rate_limits"))
    }

    @Test
    fun missingResetCreditsHidesLine() {
        val usage = OpenAiUsageParser.parseWhamUsage(loadFixture("openai-wham-usage.json"), now)
        assertFalse(usage.manualResets?.isPresent == true)
        val snapshot = OpenAiUsageParser.toSnapshot(usage)
        assertNull(snapshot.manualResets)
        assertTrue(snapshot.diagnosticJson!!.contains("rate_limit_reset_credits"))
    }

    @Test
    fun htmlFallbackReadsUsageLimitResets() {
        val usage = OpenAiHtmlExtractor.extract(loadFixture("openai-usage-page.txt"), now)
        assertEquals(1, usage.manualResets!!.availableCount)
        assertEquals("Manual resets: 1 (exp 11/06)", usage.manualResets!!.displayLine())
    }

    @Test
    fun twoExpiriesAreCommaSeparated() {
        val resets = ManualResets(
            availableCount = 2,
            credits = listOf(
                ManualResetCredit(expiresAtEpochMs = Instant.parse("2026-11-12T17:00:00Z").toEpochMilli(), status = "available"),
                ManualResetCredit(expiresAtEpochMs = Instant.parse("2026-12-18T17:00:00Z").toEpochMilli(), status = "available")
            )
        )
        assertEquals("Manual resets: 2 (exp 11/12, 12/18)", resets.displayLine())
    }

    @Test
    fun expiryDateIsMonthSlashDayNotDayFirst() {
        // Day > 12: 2026-11-25 must read 11/25, never 25/11 (a non-month).
        fun line(iso: String) = ManualResets(
            1,
            listOf(ManualResetCredit(Instant.parse(iso).toEpochMilli(), "available"))
        ).displayLine()
        assertEquals("Manual resets: 1 (exp 11/25)", line("2026-11-25T17:00:00Z"))
        // Day <= 12: 4 March must read 03/04, and 3 April 04/03 (no flip).
        assertEquals("Manual resets: 1 (exp 03/04)", line("2026-03-04T17:00:00Z"))
        assertEquals("Manual resets: 1 (exp 04/03)", line("2026-04-03T17:00:00Z"))
        // New York calendar date, not UTC: 03:00Z on Dec 11 is still Dec 10 in NY.
        assertEquals("Manual resets: 1 (exp 12/10)", line("2026-12-11T03:00:00Z"))
    }
}
