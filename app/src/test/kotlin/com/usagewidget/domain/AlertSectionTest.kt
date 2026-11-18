package com.usagewidget.domain

import com.usagewidget.providers.claude.ClaudeMeterIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each alert names its service so the notification can show that service's logo. */
class AlertSectionTest {
    private val now = 1_800_000_000_000L

    private fun snap(at: Long, providerId: String, meterId: String, used: Double, reset: Long) = UsageSnapshot(
        fetchedAtEpochMs = at,
        providers = listOf(
            ProviderSnapshot(
                id = providerId, displayName = providerId, status = "OK",
                meters = listOf(UsageMeter(meterId, "x", usedPercent = used, remainingPercent = 100 - used, resetsAtEpochMs = reset))
            )
        )
    )

    @Test
    fun restoredAlertsCarryTheirService() {
        val reset = now + 60_000
        fun restoredSection(providerId: String, meterId: String): SoftwareSectionId? {
            val plan = UsageAlertEngine.evaluate(
                previous = snap(now, providerId, meterId, 90.0, reset),
                current = snap(reset + 5_000, providerId, meterId, 0.0, reset + 7L * 24 * 3_600_000),
                nowMs = reset + 5_000,
                prefs = AlertPreferences(notifyPacingReminders = false, notifyPacingMissed = false),
                seenKeys = emptySet()
            )
            return plan.alerts.single { it.channel == AlertChannel.RESTORED }.section
        }
        assertEquals(SoftwareSectionId.CLAUDE, restoredSection("claude", ClaudeMeterIds.FIVE_HOUR))
        assertEquals(SoftwareSectionId.CODEX, restoredSection("openai", "openai.weekly"))
        assertEquals(SoftwareSectionId.GROK_BOT, restoredSection("cursor", "cursor.grok_bot"))
    }

    @Test
    fun quotaRulesMapToTheirService() {
        assertEquals(SoftwareSectionId.GROK_BOT, QuotaThresholdEngine.testAlert().section)
        assertTrue(QuotaRuleId.entries.all { it.section in SoftwareSectionId.entries })
    }
}
