package com.usagewidget.providers.gemini

import com.usagewidget.domain.ProviderSnapshot
import com.usagewidget.domain.UsageMeter
import kotlinx.serialization.json.*
import java.time.Instant

object GeminiUsageParser {
    const val FIVE_HOURS = 18_000_000L
    const val WEEK = 604_800_000L
    fun obj(raw: String?): JsonObject = runCatching { Json.parseToJsonElement(raw ?: "{}").jsonObject }.getOrDefault(JsonObject(emptyMap()))
    fun text(obj: JsonObject?, key: String): String? = (obj?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    fun plan(root: JsonObject): String? {
        val paid = root["paidTier"] as? JsonObject
        return text(paid, "name") ?: when (text(paid, "id")) {
            "g1-pro-tier" -> "Pro"
            "g1-ultra-tier" -> "Ultra"
            "g1-ultra-lite-tier" -> "Ultra Lite"
            else -> text(root["currentTier"] as? JsonObject, "name")
        }
    }

    /** Ordered daily then production; exact IDs, first usable bucket wins. */
    fun merge(vararg summaries: String?): List<UsageMeter> {
        val meters = linkedMapOf<String, UsageMeter>()
        for (raw in summaries) {
            val root = obj(raw)
            val groups = (root["groups"] ?: (root["response"] as? JsonObject)?.get("groups") ?: root["quotaGroups"]) as? JsonArray ?: continue
            val argonBucketCount = groups.filterIsInstance<JsonObject>().filter {
                val name = text(it, "displayName") ?: text(it, "name") ?: ""
                name.contains("Gemini 4", true) && name.contains("Argon", true)
            }.sumOf { (it["buckets"] as? JsonArray)?.size ?: 0 }
            for (group in groups.filterIsInstance<JsonObject>()) {
                val buckets = group["buckets"] as? JsonArray ?: continue
                val name = text(group, "displayName") ?: text(group, "name") ?: ""
                val argon = name.contains("Gemini 4", true) && name.contains("Argon", true)
                if (argon && (argonBucketCount != 1 || meters.values.any { it.id == "gemini.argon-weekly" })) continue
                for (bucket in buckets.filterIsInstance<JsonObject>()) {
                    val id = text(bucket, "bucketId") ?: continue
                    if (id in meters) continue
                    if (argon && id in listOf("gemini-5h", "gemini-weekly", "3p-5h", "3p-weekly")) continue
                    val title = when (id) {
                        "gemini-5h" -> "Gemini 5-hour"
                        "gemini-weekly" -> "Gemini weekly"
                        "3p-5h" -> "Third-party (Claude/GPT) 5-hour"
                        "3p-weekly" -> "Third-party (Claude/GPT) weekly"
                        else -> if (argon && buckets.size == 1 && text(bucket, "window") == "weekly") "Gemini 4 Argon weekly" else null
                    } ?: continue
                    meter(bucket, if (title == "Gemini 4 Argon weekly") "gemini.argon-weekly" else "gemini.$id", title, if (id.endsWith("5h")) FIVE_HOURS else WEEK)?.let { meters[id] = it }
                }
            }
        }
        return meters.values.sortedBy { when { it.id == "gemini.gemini-5h" -> 0; it.id == "gemini.gemini-weekly" -> 1; it.title.contains("Argon") -> 2; else -> 3 } }
    }

    private fun meter(obj: JsonObject, id: String, title: String, duration: Long): UsageMeter? {
        if ("disabled" in obj && ((obj["disabled"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == false } != true)) return null
        val value = obj["remainingFraction"] as? JsonPrimitive ?: return null
        val fraction = value.doubleOrNull?.takeIf { !value.isString && it.isFinite() && it in 0.0..1.0 } ?: return null
        val reset = runCatching { Instant.parse(text(obj, "resetTime")).toEpochMilli() }.getOrNull()
        if ("resetTime" in obj && reset == null) return null
        return UsageMeter(id, title, usedPercent = (1 - fraction) * 100, remainingPercent = fraction * 100,
            resetsAtEpochMs = reset, windowStartEpochMs = reset?.minus(duration), windowLengthMs = duration)
    }

    fun models(raw: String?): UsageMeter? {
        val models = obj(raw)["models"] as? JsonObject ?: return null
        return models.mapNotNull { (key, value) ->
            val model = value as? JsonObject ?: return@mapNotNull null
            val id = key.lowercase()
            val name = text(model, "displayName") ?: return@mapNotNull null
            val internalId = text(model, "model") ?: key
            if (internalId in setOf("MODEL_CHAT_20706", "MODEL_CHAT_23310", "MODEL_GOOGLE_GEMINI_2_5_FLASH", "MODEL_GOOGLE_GEMINI_2_5_FLASH_THINKING", "MODEL_GOOGLE_GEMINI_2_5_FLASH_LITE", "MODEL_GOOGLE_GEMINI_2_5_PRO", "MODEL_PLACEHOLDER_M19", "MODEL_PLACEHOLDER_M9", "MODEL_PLACEHOLDER_M12", "MODEL_PLACEHOLDER_M421")) return@mapNotNull null
            if ((!id.startsWith("gemini-") && !name.startsWith("Gemini", true)) || listOf("image", "argon", "2.5", "placeholder", "internal").any { id.contains(it) }
                || name.contains("image", true) || name.contains("Argon", true) || (model["isInternal"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
            val quota = model["quotaInfo"] as? JsonObject ?: return@mapNotNull null
            meter(quota, "gemini.gemini-5h", "Gemini 5-hour", FIVE_HOURS)
        }.maxByOrNull { it.usedPercent ?: 0.0 }
    }
}

data class GeminiResponse(val status: Int, val body: String)

class GeminiQuotaClient(private val post: (String, String, String, Int) -> GeminiResponse) {
    var authRejected = false
        private set
    fun fetch(): ProviderSnapshot? {
        fun request(host: String, method: String, body: String, timeout: Int = 10): GeminiResponse =
            runCatching { post(host, method, body, timeout) }.getOrDefault(GeminiResponse(0, "{}"))
        val load = request(PRODUCTION, "loadCodeAssist", "{}", 5)
        val metadata = GeminiUsageParser.obj(load.body.takeIf { load.status in 200..299 })
        val project = GeminiUsageParser.text(metadata, "cloudaicompanionProject")
        val body = if (project == null) "{}" else buildJsonObject { put("project", project) }.toString()
        val hosts = listOf(DAILY, PRODUCTION)
        val responses = hosts.map { request(it, "retrieveUserQuotaSummary", body) }.toMutableList()
        val answered = responses.filter { it.status != 0 }
        if (project != null && answered.isNotEmpty() && answered.all { it.status in listOf(401, 403) }) {
            responses.indices.forEach { i -> if (responses[i].status in listOf(401, 403)) responses[i] = request(hosts[i], "retrieveUserQuotaSummary", "{}") }
        }
        authRejected = responses.any { it.status in listOf(401, 403) }
        val meters = GeminiUsageParser.merge(*responses.map { it.body.takeIf { _ -> it.status in 200..299 } }.toTypedArray()).toMutableList()
        if (meters.none { it.id in listOf("gemini.gemini-5h", "gemini.gemini-weekly") }) {
            for (host in listOf(PRODUCTION, DAILY)) {
                val fallback = request(host, "fetchAvailableModels", "{}")
                authRejected = authRejected || fallback.status in listOf(401, 403)
                val meter = if (fallback.status in 200..299) GeminiUsageParser.models(fallback.body) else null
                if (meter != null) { meters.add(0, meter); break }
            }
        }
        return if (meters.isEmpty()) null else ProviderSnapshot("gemini", "Gemini (Antigravity)", "OK", source = "cloud-code", planLabel = GeminiUsageParser.plan(metadata), meters = meters)
    }
    companion object {
        const val DAILY = "https://daily-cloudcode-pa.googleapis.com"
        const val PRODUCTION = "https://cloudcode-pa.googleapis.com"
    }
}
