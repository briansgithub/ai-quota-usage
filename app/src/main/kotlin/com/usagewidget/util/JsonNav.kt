package com.usagewidget.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Lenient JSON navigation for undocumented provider payloads.
 * Accepts camelCase and snake_case aliases and never throws on missing keys.
 */
object JsonNav {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun parseObject(raw: String): JsonObject {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("Empty JSON body")
        }
        val element = json.parseToJsonElement(trimmed)
        return element as? JsonObject
            ?: throw IllegalArgumentException("JSON root is ${element::class.simpleName}, expected object")
    }

    fun field(obj: JsonObject?, vararg names: String): JsonElement? {
        if (obj == null) return null
        for (name in names) {
            val direct = obj[name]
            if (direct != null && direct !is JsonNull) return direct
        }
        val lowered = obj.entries.associate { it.key.lowercase() to it.value }
        for (name in names) {
            val match = lowered[name.lowercase()]
            if (match != null && match !is JsonNull) return match
        }
        return null
    }

    fun obj(element: JsonElement?): JsonObject? = element as? JsonObject

    fun arr(element: JsonElement?): JsonArray? = element as? JsonArray

    fun str(element: JsonElement?): String? {
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    }

    fun bool(element: JsonElement?): Boolean? {
        val primitive = element as? JsonPrimitive ?: return null
        primitive.booleanOrNull?.let { return it }
        return when (primitive.contentOrNull?.lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
    }

    fun num(element: JsonElement?): Double? {
        val primitive = element as? JsonPrimitive ?: return null
        primitive.doubleOrNull?.let { return it }
        val text = primitive.contentOrNull?.trim()?.trimEnd('%') ?: return null
        return text.toDoubleOrNull()
    }

    fun long(element: JsonElement?): Long? {
        val primitive = element as? JsonPrimitive ?: return null
        primitive.longOrNull?.let { return it }
        val asDouble = primitive.doubleOrNull ?: primitive.contentOrNull?.toDoubleOrNull()
        return asDouble?.toLong()
    }

    fun nested(obj: JsonObject?, vararg path: String): JsonElement? {
        var current: JsonElement? = obj
        for (segment in path) {
            val asObj = current as? JsonObject ?: return null
            current = field(asObj, segment) ?: return null
        }
        return current
    }

    fun firstObject(element: JsonElement?): JsonObject? {
        when (element) {
            is JsonObject -> return element
            is JsonArray -> return element.firstOrNull() as? JsonObject
            else -> return null
        }
    }
}

fun JsonObject.field(vararg names: String): JsonElement? = JsonNav.field(this, *names)

fun JsonObject.str(vararg names: String): String? = JsonNav.str(field(*names))

fun JsonObject.num(vararg names: String): Double? = JsonNav.num(field(*names))

fun JsonObject.bool(vararg names: String): Boolean? = JsonNav.bool(field(*names))

fun JsonObject.long(vararg names: String): Long? = JsonNav.long(field(*names))

fun JsonObject.obj(vararg names: String): JsonObject? = JsonNav.obj(field(*names))
