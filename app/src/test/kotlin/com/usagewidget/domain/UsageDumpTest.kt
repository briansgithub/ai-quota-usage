package com.usagewidget.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageDumpTest {
    @Test
    fun redactsIdentityAndTokensButKeepsUsageFields() {
        val raw = """
            {
              "email": "brian@example.com",
              "user_id": "user_secret",
              "account_id": "acct_LIVEVALUE",
              "accessToken": "eyJhbGciOi.payload.sig",
              "rate_limit": {
                "primary_window": { "used_percent": 0.5, "limit_window_seconds": 18000, "reset_at": 1793910026 }
              }
            }
        """.trimIndent()
        val redacted = UsageDump.redactJson(raw)
        assertFalse(redacted.contains("brian@example.com"))
        assertFalse(redacted.contains("user_secret"))
        assertFalse(redacted.contains("acct_LIVEVALUE"))
        assertFalse(redacted.contains("eyJhbGciOi"))
        assertTrue(redacted.contains("[redacted]"))
        assertTrue(redacted.contains("0.5"))
        assertTrue(redacted.contains("18000"))
        assertTrue(redacted.contains("1793910026"))
    }

    @Test
    fun formatListsParsedMetersAndSanitizedJson() {
        val snapshot = UsageSnapshot(
            fetchedAtEpochMs = 1_783_000_000_000L,
            providers = listOf(
                ProviderSnapshot(
                    id = "openai",
                    displayName = "Codex",
                    status = ProviderStatus.OK.name,
                    meters = listOf(
                        UsageMeter("openai.weekly", "Weekly", usedPercent = 8.0, remainingPercent = 92.0)
                    ),
                    diagnosticJson = UsageDump.openaiPayload(
                        """{"email":"hide@me","rate_limit":{"primary_window":{"used_percent":8}}}"""
                    )
                )
            )
        )
        val text = UsageDump.format(snapshot, nowMs = 1_783_000_000_000L + 60_000L)
        assertTrue(text.contains("openai.weekly"))
        assertTrue(text.contains("used=8.0"))
        assertTrue(text.contains("8% used"))
        assertTrue(text.contains("whamUsage"))
        assertFalse(text.contains("hide@me"))
        assertEquals(false, text.contains("openai.five_hour"))
    }
}
