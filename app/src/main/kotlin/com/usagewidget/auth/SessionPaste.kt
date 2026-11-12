package com.usagewidget.auth

import com.usagewidget.domain.ProviderId
import com.usagewidget.providers.cursor.CursorEndpoints
import com.usagewidget.providers.openai.OpenAiEndpoints

/**
 * Turns a pasted Cookie header, name=value list, or bare token into a cookie map.
 * Never logs the values.
 */
object SessionPaste {
    fun parse(provider: ProviderId, raw: String): Map<String, String> {
        var text = raw.trim().trim('"')
        if (text.startsWith("Cookie:", ignoreCase = true)) {
            text = text.substringAfter(':').trim()
        }
        if (text.isEmpty()) return emptyMap()
        if (!text.contains('=')) {
            return mapOf(defaultCookieName(provider) to text)
        }
        return SecureCookieStore.cookieMap(text)
            .filterKeys { it.isNotBlank() }
            .filterValues { it.isNotBlank() }
    }

    fun defaultCookieName(provider: ProviderId): String = when (provider) {
        ProviderId.CURSOR -> CursorEndpoints.SESSION_COOKIE
        ProviderId.OPENAI -> OpenAiEndpoints.SESSION_COOKIE_PREFIX
    }
}
