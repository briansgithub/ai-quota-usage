package com.usagewidget.domain

import com.usagewidget.util.JsonNav
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Sanitized usage dump for logcat / Copy diagnostics. No cookies or tokens.
 * Compare these parsed fields with chatgpt.com/codex/settings/usage and
 * cursor.com/dashboard/spending the next time a live session is available.
 */
object UsageDump {
    const val LOG_TAG = "UsageWidget"

    private val redactKeys = setOf(
        "email", "user_id", "userId", "account_id", "accountId", "accessToken",
        "access_token", "id_token", "idToken", "refresh_token", "refreshToken",
        "name", "picture", "chatgpt_account_id"
    )

    fun format(
        snapshot: UsageSnapshot,
        nowMs: Long = snapshot.fetchedAtEpochMs
    ): String {
        val hydrated = HealthSynthesis.hydrate(snapshot)
        val builder = StringBuilder()
        builder.appendLine("=== $LOG_TAG dump_usage ===")
        builder.appendLine("fetchedAtEpochMs=${snapshot.fetchedAtEpochMs}")
        builder.appendLine("nowMs=$nowMs")
        SoftwareSectionId.entries.forEach { id ->
            val health = hydrated.health(id)
            val section = UsageSections.from(hydrated, nowMs).first { it.id == id }
            builder.appendLine()
            builder.appendLine(id.title)
            builder.appendLine("  outcome=${health.lastOutcome} source=${health.source ?: "—"} endpoint=${DiagnosticSanitizer.endpoint(health.endpoint) ?: "—"}")
            section.meters.forEach { meter ->
                builder.appendLine(
                    "  ${meter.id} title=${meter.title} used=${meter.usedPercent} displayUsed=${meter.usedLabel() ?: "—"} remaining=${meter.remainingPercent} resetMs=${meter.resetsAtEpochMs} windowStart=${meter.windowStartEpochMs} lengthMs=${meter.windowLengthMs} resetLabel=${meter.resetLabel ?: "—"}"
                )
            }
            health.lastGoodDiagnosticJson?.let { json ->
                builder.appendLine("  diagnosticJson=$json")
            }
        }
        return builder.toString().trimEnd() + "\n"
    }

    fun cursorPayload(summaryRaw: String?, sandRaw: String?): String {
        val obj = buildJsonObject {
            summaryRaw?.let { put("usageSummary", redactElement(it)) }
            sandRaw?.let { put("sandUsage", redactElement(it)) }
        }
        return obj.toString()
    }

    fun claudePayload(usageRaw: String?): String {
        val obj = buildJsonObject {
            usageRaw?.let { put("claudeUsage", redactElement(it)) }
        }
        return obj.toString()
    }

    fun openaiPayload(whamRaw: String?, resetCreditsRaw: String? = null): String {
        val obj = buildJsonObject {
            whamRaw?.let { put("whamUsage", redactElement(it)) }
            resetCreditsRaw?.let { put("rateLimitResetCredits", redactElement(it)) }
        }
        return obj.toString()
    }

    fun redactJson(raw: String): String = redactElement(raw).toString()

    private fun redactElement(raw: String): JsonElement {
        val parsed = runCatching { JsonNav.json.parseToJsonElement(raw) }.getOrNull()
            ?: return JsonPrimitive(sanitizeText(raw) ?: "[unparsed]")
        return redact(parsed)
    }

    private fun redact(element: JsonElement): JsonElement {
        return when (element) {
            is JsonObject -> buildJsonObject {
                element.forEach { (key, value) ->
                    if (redactKeys.any { it.equals(key, ignoreCase = true) }) {
                        put(key, "[redacted]")
                    } else {
                        put(key, redact(value))
                    }
                }
            }
            is JsonArray -> buildJsonArray {
                element.forEach { add(redact(it)) }
            }
            is JsonPrimitive -> {
                if (!element.isString) return element
                JsonPrimitive(sanitizeText(element.content) ?: element.content)
            }
        }
    }

    private fun sanitizeText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var text = raw
        text = jwt.replace(text, "[redacted-jwt]")
        text = bearer.replace(text, "Bearer [redacted]")
        text = cookiePair.replace(text, "$1=[redacted]")
        text = account.replace(text, "acct_[redacted]")
        return text.ifBlank { null }
    }

    private val jwt = Regex("""eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9._-]+""")
    private val bearer = Regex("""(?i)Bearer\s+[A-Za-z0-9._\-+=/]+""")
    private val cookiePair = Regex(
        """(?i)(WorkosCursorSessionToken|__Secure-next-auth\.session-token(?:\.\d+)?|sessionKey|Cookie|Authorization)\s*[:=]\s*\S+"""
    )
    private val account = Regex("""acct_[A-Za-z0-9]+""")
}
