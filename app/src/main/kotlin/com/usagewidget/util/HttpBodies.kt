package com.usagewidget.util

object HttpBodies {
    fun looksLikeJson(body: String): Boolean {
        val trimmed = body.trimStart()
        return trimmed.startsWith("{") || trimmed.startsWith("[")
    }

    fun looksLikeHtml(body: String): Boolean {
        val trimmed = body.trimStart().lowercase()
        return trimmed.startsWith("<!doctype") ||
            trimmed.startsWith("<html") ||
            trimmed.contains("<body")
    }

    fun looksLikeLoginUrl(finalUrl: String): Boolean {
        val url = finalUrl.lowercase()
        return url.contains("login") ||
            url.contains("authorize") ||
            url.contains("signin") ||
            url.contains("sign-in") ||
            url.contains("auth.openai") ||
            url.contains("accounts.google") ||
            url.contains("workos.com")
    }

    fun isUnauthorizedPayload(body: String, code: Int, finalUrl: String): Boolean {
        if (code == 401) return true
        if (code == 403 && looksLikeHtml(body)) return true
        if (looksLikeLoginUrl(finalUrl) && (code in 300..399 || looksLikeHtml(body) || body.isBlank())) {
            return true
        }
        val trimmed = body.trim()
        if (trimmed.contains("not_authenticated", ignoreCase = true)) return true
        if (looksLikeJson(body)) {
            runCatching {
                val obj = JsonNav.parseObject(body)
                val error = obj.str("error", "detail", "message")
                if (error.equals("not_authenticated", true) ||
                    error.equals("unauthorized", true) ||
                    error?.contains("sign in", true) == true
                ) {
                    return true
                }
            }
        }
        return false
    }
}
