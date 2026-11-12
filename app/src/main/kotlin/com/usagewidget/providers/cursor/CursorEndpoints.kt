package com.usagewidget.providers.cursor

/**
 * Undocumented Cursor dashboard endpoints.
 *
 * These are the same cookie-authenticated JSON routes the spending page uses.
 * They are not a public API and can change without notice. Keep parsing lenient
 * and errors isolated to this module.
 *
 * Verified against community reverse-engineering (OpenUsage, CodexBar, Pulse)
 * and against a Nov 5 2026 spending-page snapshot (percentages + reset copy).
 * Live HTTP was not executed here (no session cookies in the repo).
 */
object CursorEndpoints {
    const val ORIGIN = "https://cursor.com"
    const val SPENDING_PAGE = "https://cursor.com/dashboard/spending"
    const val LOGIN_PAGE = "https://cursor.com/dashboard/spending"
    const val USAGE_SUMMARY = "https://cursor.com/api/usage-summary"
    const val AUTH_ME = "https://cursor.com/api/auth/me"
    const val SAND_USAGE = "https://cursor.com/api/dashboard/get-sand-usage-status"
    const val SESSION_COOKIE = "WorkosCursorSessionToken"

    val cookieHosts = listOf("cursor.com", "www.cursor.com")
}
