package com.usagewidget.providers.claude

import com.usagewidget.domain.PaceWindows
import com.usagewidget.domain.ProviderId
import com.usagewidget.domain.ProviderResult
import com.usagewidget.domain.ProviderSnapshot
import com.usagewidget.domain.ProviderStatus
import com.usagewidget.domain.QuotaRuleId
import com.usagewidget.domain.ResetDetector
import com.usagewidget.domain.SnapshotMerger
import com.usagewidget.domain.SoftwareSectionId
import com.usagewidget.domain.UsageMeter
import com.usagewidget.domain.UsageSections
import com.usagewidget.domain.UsageSnapshot
import com.usagewidget.loadFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ClaudeUsageParserTest {
    private val now = Instant.parse("2026-11-17T21:00:00Z").toEpochMilli()

    @Test
    fun picksChatOrgUnlessLastActiveOrgMatches() {
        val raw = loadFixture("claude-organizations.json")
        assertEquals("22222222-2222-2222-2222-222222222222", ClaudeUsageParser.parseOrgId(raw))
        assertEquals(
            "11111111-1111-1111-1111-111111111111",
            ClaudeUsageParser.parseOrgId(raw, "11111111-1111-1111-1111-111111111111")
        )
        assertEquals("22222222-2222-2222-2222-222222222222", ClaudeUsageParser.parseOrgId(raw, "not-an-org"))
    }

    @Test
    fun usageBecomesSessionWeeklyAndActiveModelMeters() {
        val snapshot = ClaudeUsageParser.parseUsage(loadFixture("claude-usage.json"), now)
        assertEquals("claude", snapshot.id)
        // Opus is 0% with no reset, so it is not shown; Sonnet has usage.
        assertEquals(
            listOf(ClaudeMeterIds.FIVE_HOUR, ClaudeMeterIds.WEEKLY, ClaudeMeterIds.WEEKLY_SONNET),
            snapshot.meters.map { it.id }
        )
        val session = snapshot.meters[0]
        assertEquals("Session", session.title)
        assertEquals("37% used", session.usedLabel())
        // +00:00 offset with microseconds must not collapse to midnight; jitter rounds to the minute.
        assertEquals(Instant.parse("2026-11-18T00:00:00Z").toEpochMilli(), session.resetsAtEpochMs)
        assertEquals(ClaudeMeterIds.FIVE_HOUR_MS, session.windowLengthMs)
        val weekly = snapshot.meters[1]
        assertEquals("Weekly", weekly.title)
        assertEquals(38, weekly.displayRemainingPercent)
        assertNotNull(PaceWindows.expectedUsedPercent(weekly, now))
    }

    @Test
    fun idleSessionWithoutResetStillShowsZeroBar() {
        val snapshot = ClaudeUsageParser.parseUsage(
            """{"five_hour":{"utilization":0,"resets_at":null},"seven_day":{"utilization":5,"resets_at":"2026-11-20T18:00:00Z"}}""",
            now
        )
        val session = snapshot.meters.first { it.id == ClaudeMeterIds.FIVE_HOUR }
        assertEquals("0% used", session.usedLabel())
        assertNull(session.resetsAtEpochMs)
        assertNull(PaceWindows.expectedUsedPercent(session, now))
    }

    @Test
    fun resetJitterBetweenRequestsKeepsTheSameWindow() {
        fun reset(raw: String) = ClaudeUsageParser.parseUsage(
            """{"five_hour":{"utilization":5,"resets_at":"$raw"},"seven_day":{"utilization":5,"resets_at":"$raw"}}""", now
        ).meters.first().resetsAtEpochMs
        assertEquals(reset("2026-11-18T18:00:00.951556+00:00"), reset("2026-11-18T18:00:00.497762+00:00"))
    }

    @Test
    fun missingWindowsIsAParseFailure() {
        assertThrows(IllegalArgumentException::class.java) {
            ClaudeUsageParser.parseUsage("""{"extra_usage":null}""", now)
        }
    }

    @Test
    fun authErrorBodiesAreRecognised() {
        assertTrue(
            ClaudeUsageParser.isAuthErrorBody(
                """{"type":"error","error":{"type":"permission_error","message":"Invalid authorization","details":{"error_code":"account_session_invalid"}}}"""
            )
        )
        assertFalse(ClaudeUsageParser.isAuthErrorBody(loadFixture("claude-usage.json")))
    }

    @Test
    fun claudeIsItsOwnSectionAndMergesWithoutTouchingOthers() {
        val usage = ClaudeUsageParser.parseUsage(loadFixture("claude-usage.json"), now)
        val merged = SnapshotMerger.merge(
            previous = UsageSnapshot.empty(now - 1000),
            rounds = listOf(
                SnapshotMerger.ProviderRound(ProviderId.CURSOR, ProviderResult.NeedsSignIn(), false),
                SnapshotMerger.ProviderRound(ProviderId.OPENAI, ProviderResult.NeedsSignIn(), false),
                SnapshotMerger.ProviderRound(ProviderId.CLAUDE, ProviderResult.Success(usage), true)
            ),
            nowMs = now
        )
        val sections = UsageSections.from(merged, now)
        val claude = sections.single { it.id == SoftwareSectionId.CLAUDE }
        assertEquals(ProviderStatus.OK, claude.status)
        assertEquals(ProviderId.CLAUDE, claude.signInProvider)
        assertEquals(3, claude.meters.size)
        assertEquals(ProviderStatus.UNSIGNED, sections.single { it.id == SoftwareSectionId.CODEX }.status)

        val signedOut = SnapshotMerger.clearForSignOut(merged, ProviderId.CLAUDE)
        assertEquals(
            ProviderStatus.UNSIGNED,
            UsageSections.from(signedOut, now).single { it.id == SoftwareSectionId.CLAUDE }.status
        )
    }

    @Test
    fun claudeSessionResetPostsUsageIsBack() {
        val resetAt = now + 60_000
        fun snap(at: Long, used: Double, reset: Long) = UsageSnapshot(
            fetchedAtEpochMs = at,
            providers = listOf(
                ProviderSnapshot(
                    id = "claude", displayName = "Claude", status = "OK",
                    meters = listOf(
                        UsageMeter(ClaudeMeterIds.FIVE_HOUR, "Session", usedPercent = used, remainingPercent = 100 - used, resetsAtEpochMs = reset)
                    )
                )
            )
        )
        val events = ResetDetector.detect(
            snap(now, 90.0, resetAt),
            snap(resetAt + 5_000, 0.0, resetAt + ClaudeMeterIds.FIVE_HOUR_MS),
            resetAt + 5_000
        )
        assertEquals(1, events.size)
        assertEquals("claude.five_hour", events[0].group)
        assertEquals("Claude session usage is back", events[0].title)
    }

    @Test
    fun claudeQuotaRulesAreOnByDefault() {
        assertTrue(QuotaRuleId.CLAUDE_FIVE_HOUR.wire in QuotaRuleId.DEFAULT_ENABLED)
        assertTrue(QuotaRuleId.CLAUDE_WEEKLY.wire in QuotaRuleId.DEFAULT_ENABLED)
        assertEquals(ClaudeMeterIds.WEEKLY, QuotaRuleId.CLAUDE_WEEKLY.meterId)
    }
}
