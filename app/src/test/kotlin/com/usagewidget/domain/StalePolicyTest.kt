package com.usagewidget.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StalePolicyTest {
    private val now = 1_783_000_000_000L
    private val meters = listOf(
        UsageMeter(id = "cursor.models", title = "Cursor Models", usedPercent = 17.0, remainingPercent = 83.0)
    )

    @Test
    fun recentSuccessIsNotStale() {
        val view = StalePolicy.evaluate(healthy(now - 5 * 60_000L, FetchOutcome.SUCCESS), now)
        assertFalse(view.isStale)
        assertEquals(StaleSeverity.NONE, view.severity)
        assertFalse(view.shouldNotify)
    }

    @Test
    fun agePastTwoRefreshIntervalsIsAmberEvenWithoutError() {
        val lastSuccess = now - StalePolicy.STALE_AGE_MS - 1_000L
        val view = StalePolicy.evaluate(healthy(lastSuccess, FetchOutcome.SUCCESS), now)
        assertTrue(view.isStale)
        assertEquals(StaleSeverity.AMBER, view.severity)
        assertEquals("Refresh overdue", view.shortReason)
        assertEquals("Last updated 30m ago", view.lastUpdatedLine)
        assertFalse(view.shouldNotify)
    }

    @Test
    fun networkErrorKeepsLastGoodAsAmberStale() {
        val view = StalePolicy.evaluate(
            healthy(now - 10 * 60_000L, FetchOutcome.NETWORK, shortReason = "Network error"),
            now
        )
        assertTrue(view.isStale)
        assertEquals(StaleSeverity.AMBER, view.severity)
        assertEquals("Network error", view.shortReason)
        assertEquals("Last updated 10m ago", view.lastUpdatedLine)
    }

    @Test
    fun http503IsRedWithHttpReason() {
        val health = healthy(now - 15 * 60_000L, FetchOutcome.HTTP, shortReason = "HTTP 503", httpStatus = 503)
        val view = StalePolicy.evaluate(health, now)
        assertTrue(view.isStale)
        assertEquals(StaleSeverity.RED, view.severity)
        assertEquals("HTTP 503", view.shortReason)
    }

    @Test
    fun authExpiredIsRed() {
        val view = StalePolicy.evaluate(
            healthy(now - 20 * 60_000L, FetchOutcome.AUTH_EXPIRED, shortReason = "Sign-in expired"),
            now
        )
        assertTrue(view.isStale)
        assertEquals(StaleSeverity.RED, view.severity)
        assertEquals("Sign-in expired", view.shortReason)
    }

    @Test
    fun parseFailureIsDataFormatChanged() {
        val view = StalePolicy.evaluate(
            healthy(now - 8 * 60_000L, FetchOutcome.PARSE, shortReason = "Data format changed"),
            now
        )
        assertTrue(view.isStale)
        assertEquals(StaleSeverity.RED, view.severity)
        assertEquals("Data format changed", view.shortReason)
    }

    @Test
    fun failureOlderThanTwoHoursNotifiesWhenSignedIn() {
        val lastSuccess = now - StalePolicy.NOTIFY_AFTER_MS - 1_000L
        val view = StalePolicy.evaluate(
            healthy(lastSuccess, FetchOutcome.TIMEOUT, shortReason = "Timeout"),
            now
        )
        assertTrue(view.shouldNotify)
        assertEquals("Last updated 2h ago", view.lastUpdatedLine)
    }

    @Test
    fun unsignedWithoutDataIsNotStale() {
        val view = StalePolicy.evaluate(
            SectionHealth(sectionId = "cursor", signedIn = false),
            now
        )
        assertFalse(view.isStale)
        assertFalse(view.shouldNotify)
    }

    @Test
    fun signedOutDoesNotAgeStale() {
        val view = StalePolicy.evaluate(
            SectionHealth(
                sectionId = "cursor",
                lastSuccessEpochMs = now - 3 * 60 * 60_000L,
                lastOutcome = FetchOutcome.SUCCESS.name,
                lastGoodMeters = meters,
                signedIn = false
            ),
            now
        )
        assertFalse(view.isStale)
    }

    private fun healthy(
        lastSuccess: Long,
        outcome: FetchOutcome,
        shortReason: String? = null,
        httpStatus: Int? = null
    ): SectionHealth = SectionHealth(
        sectionId = "cursor",
        lastAttemptEpochMs = now - 30_000L,
        lastSuccessEpochMs = lastSuccess,
        lastOutcome = outcome.name,
        shortReason = shortReason ?: outcome.shortReason(httpStatus),
        httpStatus = httpStatus,
        lastGoodMeters = meters,
        signedIn = true
    )
}
