package com.itsluminous.hikviewer.core.json

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Hik-Connect responses are loosely typed: numbers arrive as strings, booleans as `"1"`, and whole
 * sub-objects disappear when a device does not support them. Parsing through the JSON tree with
 * tolerant accessors is more robust than strict models that break on every new firmware.
 */
internal object CloudJson {
    val parser: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /** Parses [text]; a non-object root is reported as a [SerializationException] like any other bad body. */
    fun parseObject(text: String): JsonObject =
        parser.parseToJsonElement(text) as? JsonObject ?: throw SerializationException("JSON root is not an object")
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)
    ?.takeUnless {
        it is JsonNull
    }?.content

internal fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.content.trim().toIntOrNull() }

/** Hik-Connect encodes flags as `true`, `1` or `"1"`; anything else is false. */
internal fun JsonObject.flag(key: String): Boolean? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.booleanOrNull ?: (p.content.trim() == "1")
}

/** `meta.code` of a cloud envelope, or null when the response has no meta block. */
internal fun JsonObject.metaCode(): Int? = obj("meta")?.int("code")

internal fun JsonObject.metaMessage(): String? = obj("meta")?.str("message")

internal fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject
