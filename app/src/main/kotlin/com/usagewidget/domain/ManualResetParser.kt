package com.usagewidget.domain

import com.usagewidget.util.JsonNav
import com.usagewidget.util.NyTime
import com.usagewidget.util.long
import com.usagewidget.util.num
import com.usagewidget.util.obj
import com.usagewidget.util.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * On-demand / banked full-reset credits.
 *
 * Codex: `rate_limit_reset_credits.available_count` on GET /wham/usage,
 * plus GET /wham/rate-limit-reset-credits `credits[].expires_at` for expiry.
 * Also accepts the same shape on any other provider JSON.
 */
object ManualResetParser {
    private val dropKeys = setOf(
        "id", "credit_id", "creditid", "user_id", "userid", "account_id", "accountid",
        "email", "access_token", "accesstoken", "description"
    )

    fun fromUsageRoot(root: JsonObject, fetchedAtMs: Long): ManualResets? {
        val block = root.obj("rate_limit_reset_credits", "rateLimitResetCredits")
        val nestedCredits = creditsArray(block) ?: creditsArray(root.takeIf { looksLikeResetInventory(it) })
        val count = block?.long("available_count", "availableCount", "applicable_available_count", "applicableAvailableCount")
            ?: nestedCredits?.count { creditIsAvailable(it) }?.toLong()
        if (block == null && nestedCredits == null) return null
        val credits = nestedCredits.orEmpty()
        val observed = observedSnippet(
            usageBlock = block,
            detailsCredits = if (block == null) JsonNav.arr(root["credits"] ?: root["Credits"]) else null,
            detailsCount = null
        )
        val available = (count ?: 0L).toInt().coerceAtLeast(credits.count { it.isAvailable })
        if (available <= 0 && credits.isEmpty() && block != null) {
            return ManualResets(availableCount = 0, observedJson = observed)
        }
        return ManualResets(
            availableCount = available,
            credits = credits,
            observedJson = observed
        ).takeIf { it.availableCount > 0 || it.credits.isNotEmpty() || observed != null }
    }

    fun mergeDetails(existing: ManualResets?, detailsRaw: String?, fetchedAtMs: Long): ManualResets? {
        if (detailsRaw.isNullOrBlank()) return existing
        val root = runCatching { JsonNav.parseObject(detailsRaw) }.getOrNull() ?: return existing
        val count = root.long("available_count", "availableCount")?.toInt()
        val credits = creditsArray(root).orEmpty()
        val observed = observedSnippet(
            usageBlock = null,
            detailsCredits = JsonNav.arr(root["credits"] ?: root["Credits"]),
            detailsCount = count
        )
        val usage = existing
        val available = count
            ?: usage?.availableCount
            ?: credits.count { it.isAvailable }
        val mergedCredits = credits.ifEmpty { usage?.credits.orEmpty() }
        if (available <= 0 && mergedCredits.isEmpty()) {
            return usage?.copy(observedJson = joinObserved(usage.observedJson, observed))
        }
        return ManualResets(
            availableCount = available,
            credits = mergedCredits,
            observedJson = joinObserved(usage?.observedJson, observed)
        )
    }

    fun fromHtml(pageText: String, fetchedAtMs: Long): ManualResets? {
        val text = pageText.replace('\u00a0', ' ')
        val section = Regex(
            """Usage limit resets[\s\S]{0,800}""",
            RegexOption.IGNORE_CASE
        ).find(text)?.value ?: return null
        val expiries = Regex(
            """Expires\s+([A-Za-z]{3,9}\s+\d{1,2}(?:,\s*\d{4})?(?:[,\s]+\d{1,2}:\d{2}\s*(?:AM|PM)?(?:\s+[A-Z]{2,4})?)?)""",
            RegexOption.IGNORE_CASE
        ).findAll(section).mapNotNull { match ->
            NyTime.parseTimestampString(match.groupValues[1], fetchedAtMs)
        }.toList()
        if (expiries.isEmpty() && !section.contains("Full reset", ignoreCase = true)) return null
        val count = expiries.size.coerceAtLeast(
            Regex("""Full reset""", RegexOption.IGNORE_CASE).findAll(section).count()
        ).coerceAtLeast(1)
        return ManualResets(
            availableCount = count,
            credits = expiries.map { ManualResetCredit(expiresAtEpochMs = it, status = "available", title = "Full reset") },
            observedJson = """{"source":"html","usage_limit_resets":true,"full_reset_count":$count}"""
        )
    }

    private fun creditsArray(obj: JsonObject?): List<ManualResetCredit>? {
        if (obj == null) return null
        val arr = JsonNav.arr(obj["credits"] ?: obj["Credits"]) ?: return null
        val parsed = arr.mapNotNull { el ->
            val item = JsonNav.obj(el) ?: return@mapNotNull null
            if (!looksLikeResetCredit(item)) return@mapNotNull null
            ManualResetCredit(
                expiresAtEpochMs = NyTime.parseTimestamp(
                    item.str("expires_at", "expiresAt") ?: item.num("expires_at", "expiresAt"),
                    System.currentTimeMillis()
                ),
                status = item.str("status"),
                title = item.str("title"),
                resetType = item.str("reset_type", "resetType")
            )
        }
        return parsed.takeIf { it.isNotEmpty() }
    }

    private fun looksLikeResetInventory(obj: JsonObject): Boolean {
        val arr = JsonNav.arr(obj["credits"] ?: obj["Credits"]) ?: return false
        return arr.any { JsonNav.obj(it)?.let { credit -> looksLikeResetCredit(credit) } == true }
    }

    private fun looksLikeResetCredit(obj: JsonObject): Boolean {
        if (obj.str("reset_type", "resetType") != null) return true
        if (obj.str("expires_at", "expiresAt") != null && obj.str("status") != null) return true
        val title = obj.str("title").orEmpty()
        return title.contains("reset", ignoreCase = true)
    }

    private fun creditIsAvailable(credit: ManualResetCredit): Boolean = credit.isAvailable

    private fun observedSnippet(
        usageBlock: JsonObject?,
        detailsCredits: JsonArray?,
        detailsCount: Int?
    ): String? {
        val obj = buildJsonObject {
            if (usageBlock != null) {
                put("rate_limit_reset_credits", redact(usageBlock))
            }
            if (detailsCount != null) {
                put("details_available_count", JsonPrimitive(detailsCount))
            }
            if (detailsCredits != null) {
                put("rate_limit_reset_credits_details", redact(detailsCredits))
            }
        }
        if (obj.isEmpty()) return null
        return obj.toString()
    }

    private fun joinObserved(first: String?, second: String?): String? {
        val parts = listOfNotNull(first, second).filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        if (parts.size == 1) return parts.first()
        return """{"usage":${parts[0]},"details":${parts[1]}}"""
    }

    private fun redact(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> buildJsonObject {
            element.forEach { (key, value) ->
                if (key.lowercase() in dropKeys) return@forEach
                put(key, redact(value))
            }
        }
        is JsonArray -> buildJsonArray {
            element.forEach { add(redact(it)) }
        }
        else -> element
    }
}
