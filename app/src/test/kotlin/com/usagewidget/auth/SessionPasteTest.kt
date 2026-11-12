package com.usagewidget.auth

import com.usagewidget.domain.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPasteTest {
    @Test
    fun parsesCookieHeader() {
        val map = SessionPaste.parse(
            ProviderId.OPENAI,
            "Cookie: __Secure-next-auth.session-token=abc.def; oai-did=device"
        )
        assertEquals("abc.def", map["__Secure-next-auth.session-token"])
        assertEquals("device", map["oai-did"])
    }

    @Test
    fun wrapsBareChatgptToken() {
        val map = SessionPaste.parse(ProviderId.OPENAI, "eyJhbGciOiJIUzI1NiJ9.payload.sig")
        assertEquals(
            "eyJhbGciOiJIUzI1NiJ9.payload.sig",
            map[SessionPaste.defaultCookieName(ProviderId.OPENAI)]
        )
    }

    @Test
    fun wrapsBareCursorToken() {
        val map = SessionPaste.parse(ProviderId.CURSOR, "user_REDACTED::token")
        assertEquals("user_REDACTED::token", map["WorkosCursorSessionToken"])
    }

    @Test
    fun emptyIsEmpty() {
        assertTrue(SessionPaste.parse(ProviderId.OPENAI, "  ").isEmpty())
    }
}
