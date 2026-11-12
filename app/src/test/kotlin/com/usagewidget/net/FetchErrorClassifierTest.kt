package com.usagewidget.net

import com.usagewidget.domain.FetchOutcome
import com.usagewidget.domain.SoftwareSectionId
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FetchErrorClassifierTest {
    private val now = 1_783_000_000_000L

    @Test
    fun classifiesNetworkTimeoutHttpAuthAndParse() {
        assertEquals(FetchOutcome.NETWORK, FetchErrorClassifier.classify(UnknownHostException("cursor.com")).outcome)
        assertEquals(FetchOutcome.NETWORK, FetchErrorClassifier.classify(ConnectException("failed to connect")).outcome)
        assertEquals(FetchOutcome.TIMEOUT, FetchErrorClassifier.classify(SocketTimeoutException("timeout")).outcome)
        assertEquals(FetchOutcome.AUTH_EXPIRED, FetchErrorClassifier.classify(SessionExpiredException(401, "https://cursor.com/login")).outcome)
        val http = FetchErrorClassifier.classify(ProviderHttpException(503, "unavailable", "https://chatgpt.com/backend-api/wham/usage"))
        assertEquals(FetchOutcome.HTTP, http.outcome)
        assertEquals(503, http.httpStatus)
        assertEquals(FetchOutcome.PARSE, FetchErrorClassifier.classify(SerializationException("unexpected")).outcome)
        assertEquals(
            FetchOutcome.MISSING_FIELDS,
            FetchErrorClassifier.classify(IllegalArgumentException("Cursor usage-summary is missing plan percentages")).outcome
        )
        assertEquals(
            FetchOutcome.PARSE,
            FetchErrorClassifier.classify(IllegalArgumentException("Could not decode usage windows")).outcome
        )
        assertEquals(
            FetchOutcome.MISSING_FIELDS,
            FetchErrorClassifier.classify(IllegalArgumentException("Could not find ChatGPT 5-hour/weekly limits in page text")).outcome
        )
    }

    @Test
    fun attemptShortReasonsMatchProductCopy() {
        val http = FetchErrorClassifier.attempt(
            ProviderHttpException(503, "nope", "https://chatgpt.com/backend-api/wham/usage"),
            SoftwareSectionId.CODEX,
            "https://chatgpt.com/backend-api/wham/usage",
            now
        )
        assertEquals("HTTP 503", http.shortReason)
        assertEquals("https://chatgpt.com/backend-api/wham/usage", http.endpoint)

        val auth = FetchErrorClassifier.attempt(
            SessionExpiredException(401, "https://cursor.com/api/usage-summary"),
            SoftwareSectionId.CURSOR,
            "https://cursor.com/api/usage-summary",
            now
        )
        assertEquals("Sign-in expired", auth.shortReason)

        val net = FetchErrorClassifier.attempt(
            UnknownHostException("cursor.com"),
            SoftwareSectionId.GROK_BOT,
            "https://cursor.com/api/dashboard/get-sand-usage-status",
            now
        )
        assertEquals("Network error", net.shortReason)
        assertFalse(net.message.orEmpty().contains("WorkosCursorSessionToken"))
    }
}
