package com.usagewidget.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotMergerTest {
    private val now = 1_783_000_000_000L
    private val cursorMeters = listOf(
        UsageMeter("cursor.models", "Cursor Models", usedPercent = 17.0, remainingPercent = 83.0),
        UsageMeter("cursor.other", "Other Models", usedPercent = 100.0, remainingPercent = 0.0),
        UsageMeter("cursor.plan_reset", "Plan reset", resetsAtEpochMs = now + 86_400_000L)
    )
    private val grokMeter = UsageMeter("cursor.grok_bot", "Grok Bot", usedPercent = 65.0, remainingPercent = 35.0)
    private val codexMeters = listOf(
        UsageMeter("openai.five_hour", "5-hour limit", usedPercent = 0.0, remainingPercent = 100.0),
        UsageMeter("openai.weekly", "Weekly limit", usedPercent = 12.0, remainingPercent = 88.0)
    )

    @Test
    fun failedRefreshKeepsLastGoodCursorMeters() {
        val previous = SnapshotMerger.merge(
            previous = UsageSnapshot.empty(now - 10_000L),
            cursorResult = ProviderResult.Success(
                snapshot = ProviderSnapshot("cursor", "Cursor", "OK", meters = cursorMeters + grokMeter, planLabel = "Pro"),
                attempts = listOf(
                    success(SoftwareSectionId.CURSOR),
                    success(SoftwareSectionId.GROK_BOT)
                )
            ),
            openaiResult = ProviderResult.Success(
                snapshot = ProviderSnapshot("openai", "ChatGPT / Codex", "OK", meters = codexMeters),
                attempts = listOf(success(SoftwareSectionId.CODEX))
            ),
            nowMs = now - 10_000L,
            cursorSignedIn = true,
            openaiSignedIn = true
        )
        val failed = SnapshotMerger.merge(
            previous = previous,
            cursorResult = ProviderResult.Failure(
                message = "connection refused",
                attempts = listOf(
                    FetchAttempt.of(now, SoftwareSectionId.CURSOR, FetchOutcome.NETWORK, endpoint = "https://cursor.com/api/usage-summary"),
                    FetchAttempt.of(now, SoftwareSectionId.GROK_BOT, FetchOutcome.NETWORK, endpoint = "https://cursor.com/api/dashboard/get-sand-usage-status")
                )
            ),
            openaiResult = ProviderResult.Success(
                snapshot = ProviderSnapshot("openai", "ChatGPT / Codex", "OK", meters = codexMeters),
                attempts = listOf(success(SoftwareSectionId.CODEX))
            ),
            nowMs = now,
            cursorSignedIn = true,
            openaiSignedIn = true
        )
        val cursor = failed.health(SoftwareSectionId.CURSOR)
        assertEquals(listOf("cursor.models", "cursor.other", "cursor.plan_reset"), cursor.lastGoodMeters.map { it.id })
        assertEquals(17.0, cursor.lastGoodMeters.first().usedPercent!!, 0.01)
        assertEquals(FetchOutcome.NETWORK, cursor.outcomeEnum)
        assertTrue(cursor.staleView(now).isStale)
        assertEquals("Network error", cursor.staleView(now).shortReason)
        val grok = failed.health(SoftwareSectionId.GROK_BOT)
        assertEquals(65.0, grok.lastGoodMeters.single().usedPercent!!, 0.01)
        assertFalse(failed.health(SoftwareSectionId.CODEX).staleView(now).isStale)
    }

    @Test
    fun grokCanFailIndependentlyOfCursor() {
        val previous = seeded()
        val mixed = SnapshotMerger.merge(
            previous = previous,
            cursorResult = ProviderResult.Success(
                snapshot = ProviderSnapshot("cursor", "Cursor", "OK", meters = cursorMeters, error = "Grok Bot: HTTP 503"),
                attempts = listOf(
                    success(SoftwareSectionId.CURSOR),
                    FetchAttempt.of(now, SoftwareSectionId.GROK_BOT, FetchOutcome.HTTP, httpStatus = 503, endpoint = "https://cursor.com/api/dashboard/get-sand-usage-status")
                )
            ),
            openaiResult = ProviderResult.Success(
                snapshot = ProviderSnapshot("openai", "ChatGPT / Codex", "OK", meters = codexMeters),
                attempts = listOf(success(SoftwareSectionId.CODEX))
            ),
            nowMs = now,
            cursorSignedIn = true,
            openaiSignedIn = true
        )
        assertFalse(mixed.health(SoftwareSectionId.CURSOR).staleView(now).isStale)
        val grok = mixed.health(SoftwareSectionId.GROK_BOT)
        assertTrue(grok.staleView(now).isStale)
        assertEquals("HTTP 503", grok.staleView(now).shortReason)
        assertEquals(65.0, grok.lastGoodMeters.single().usedPercent!!, 0.01)
    }

    @Test
    fun authExpiredKeepsLastGoodAndMarksSignInExpired() {
        val previous = seeded()
        val expired = SnapshotMerger.merge(
            previous = previous,
            cursorResult = ProviderResult.NeedsSignIn(
                message = "Cursor session expired. Sign in again.",
                attempts = listOf(
                    FetchAttempt.of(now, SoftwareSectionId.CURSOR, FetchOutcome.AUTH_EXPIRED),
                    FetchAttempt.of(now, SoftwareSectionId.GROK_BOT, FetchOutcome.AUTH_EXPIRED)
                )
            ),
            openaiResult = ProviderResult.NeedsSignIn("Sign in to ChatGPT to load Codex usage."),
            nowMs = now,
            cursorSignedIn = false,
            openaiSignedIn = false
        )
        val cursor = expired.health(SoftwareSectionId.CURSOR)
        assertTrue(cursor.hasLastGood)
        assertEquals("Sign-in expired", cursor.staleView(now).shortReason)
        assertEquals(StaleSeverity.RED, cursor.staleView(now).severity)
        val codex = expired.health(SoftwareSectionId.CODEX)
        assertTrue(codex.hasLastGood)
        assertEquals("Sign-in expired", codex.staleView(now).shortReason)
    }

    @Test
    fun signOutClearsLastGood() {
        val cleared = SnapshotMerger.clearForSignOut(seeded(), ProviderId.CURSOR)
        assertFalse(cleared.health(SoftwareSectionId.CURSOR).hasLastGood)
        assertFalse(cleared.health(SoftwareSectionId.GROK_BOT).signedIn)
        assertTrue(cleared.health(SoftwareSectionId.CODEX).hasLastGood)
    }

    @Test
    fun attemptLogRollsAndOmitsSecrets() {
        var snapshot = UsageSnapshot.empty(now)
        repeat(30) { index ->
            snapshot = SnapshotMerger.merge(
                previous = snapshot,
                cursorResult = ProviderResult.Failure(
                    message = "HTTP 503",
                    attempts = listOf(
                        FetchAttempt.of(
                            now + index,
                            SoftwareSectionId.CURSOR,
                            FetchOutcome.HTTP,
                            httpStatus = 503,
                            message = "Bearer eyJhbGciOi.payload.sig failed",
                            endpoint = "https://cursor.com/api/usage-summary?token=secret"
                        )
                    )
                ),
                openaiResult = ProviderResult.NeedsSignIn("Sign in"),
                nowMs = now + index,
                cursorSignedIn = true,
                openaiSignedIn = false
            )
        }
        assertEquals(StalePolicy.ATTEMPT_LOG_LIMIT, snapshot.attemptLog.size)
        val last = snapshot.attemptLog.last()
        assertEquals("https://cursor.com/api/usage-summary", last.endpoint)
        assertFalse(last.message.orEmpty().contains("eyJ"))
        assertFalse(last.message.orEmpty().contains("Bearer eyJ"))
    }

    private fun seeded(): UsageSnapshot = SnapshotMerger.merge(
        previous = UsageSnapshot.empty(now - 60_000L),
        cursorResult = ProviderResult.Success(
            snapshot = ProviderSnapshot("cursor", "Cursor", "OK", meters = cursorMeters + grokMeter, planLabel = "Pro"),
            attempts = listOf(success(SoftwareSectionId.CURSOR, at = now - 60_000L), success(SoftwareSectionId.GROK_BOT, at = now - 60_000L))
        ),
        openaiResult = ProviderResult.Success(
            snapshot = ProviderSnapshot("openai", "ChatGPT / Codex", "OK", meters = codexMeters, planLabel = "Plus"),
            attempts = listOf(success(SoftwareSectionId.CODEX, at = now - 60_000L))
        ),
        nowMs = now - 60_000L,
        cursorSignedIn = true,
        openaiSignedIn = true
    )

    private fun success(section: SoftwareSectionId, at: Long = now): FetchAttempt =
        FetchAttempt.of(at, section, FetchOutcome.SUCCESS, source = "json")
}
