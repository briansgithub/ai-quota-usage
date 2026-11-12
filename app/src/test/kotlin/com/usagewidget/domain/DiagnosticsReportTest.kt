package com.usagewidget.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportTest {
    @Test
    fun reportContainsHealthWithoutCookiesOrTokens() {
        val snapshot = UsageSnapshot(
            fetchedAtEpochMs = 1_783_000_000_000L,
            providers = emptyList(),
            sectionHealth = listOf(
                SectionHealth(
                    sectionId = "codex",
                    lastAttemptEpochMs = 1_783_000_000_000L,
                    lastSuccessEpochMs = 1_782_900_000_000L,
                    lastOutcome = FetchOutcome.HTTP.name,
                    shortReason = "HTTP 503",
                    message = "Bearer eyJhbGciOi.payload.sig",
                    httpStatus = 503,
                    endpoint = "https://chatgpt.com/backend-api/wham/usage?access_token=secret",
                    lastGoodMeters = listOf(
                        UsageMeter("openai.five_hour", "5-hour limit", usedPercent = 0.0, remainingPercent = 100.0)
                    ),
                    signedIn = true
                )
            ),
            attemptLog = listOf(
                FetchAttempt.of(
                    atEpochMs = 1_783_000_000_000L,
                    sectionId = SoftwareSectionId.CODEX,
                    outcome = FetchOutcome.HTTP,
                    httpStatus = 503,
                    message = "Authorization: Bearer eyJhbGciOi.payload.sig",
                    endpoint = "https://chatgpt.com/backend-api/wham/usage?foo=1"
                )
            )
        )
        val text = DiagnosticsReport.format(snapshot, "1.2.0-debug", 1_783_000_000_000L)
        assertTrue(text.contains("Codex"))
        assertTrue(text.contains("HTTP 503"))
        assertTrue(text.contains("https://chatgpt.com/backend-api/wham/usage"))
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("eyJhbGciOi"))
        assertFalse(text.contains("Bearer eyJ"))
        assertTrue(text.contains("No cookies or tokens are included."))
        assertTrue(text.contains("Refresh interval: 15 minutes"))
        assertTrue(text.contains("Recent attempts"))
    }
}
