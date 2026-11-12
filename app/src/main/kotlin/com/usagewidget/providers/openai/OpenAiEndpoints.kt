package com.usagewidget.providers.openai

/**
 * Undocumented ChatGPT / Codex usage endpoints.
 *
 * The settings/usage overview page loads numbers from the same WHAM usage
 * JSON the Codex CLI polls. Auth is a rolling web session cookie exchanged
 * for a short-lived access token.
 *
 * Not a public API. Isolated here so a shape change is a local fix.
 *
 * Verified against community docs (CodexBar, ChatGPT Usage Meter, wham gist)
 * and a Nov 5 2026 chatgpt.com/settings/usage snapshot (5-hour + weekly copy).
 * Live HTTP was not executed here (no session cookies in the repo).
 */
object OpenAiEndpoints {
    const val ORIGIN = "https://chatgpt.com"
    const val USAGE_PAGE = "https://chatgpt.com/settings/usage?tab=overview"
    const val LOGIN_PAGE = "https://chatgpt.com/auth/login?next=%2Fsettings%2Fusage%3Ftab%3Doverview"
    const val LOGIN_FALLBACK = "https://auth.openai.com/log-in"
    const val SESSION = "https://chatgpt.com/api/auth/session"
    const val WHAM_USAGE = "https://chatgpt.com/backend-api/wham/usage"
    const val WHAM_RESET_CREDITS = "https://chatgpt.com/backend-api/wham/rate-limit-reset-credits"
    const val SESSION_COOKIE_PREFIX = "__Secure-next-auth.session-token"

    val cookieHosts = listOf("chatgpt.com", "www.chatgpt.com", "chat.openai.com", "auth.openai.com")
}
