package dev.dmigrate.format

import com.fasterxml.jackson.databind.JsonNode

internal inline fun <T> parseNamedObjectMap(
    node: JsonNode?,
    transform: (JsonNode) -> T,
): Map<String, T> {
    if (node == null || !node.isObject) return emptyMap()
    val result = LinkedHashMap<String, T>()
    for ((name, childNode) in node.objectEntries()) {
        result[name] = transform(childNode)
    }
    return result
}

internal fun JsonNode.requiredText(field: String): String =
    this[field]?.asText() ?: throw IllegalArgumentException("Missing required field: $field")

internal fun JsonNode.optionalText(field: String): String? =
    this[field]?.takeIf { !it.isNull }?.asText()

internal fun JsonNode.textOrDefault(field: String, default: String): String =
    this[field]?.asText() ?: default

internal fun JsonNode.requiredInt(field: String): Int =
    this[field]?.asInt() ?: throw IllegalArgumentException("Missing required field: $field")

internal fun JsonNode.optionalInt(field: String): Int? =
    this[field]?.takeIf { !it.isNull && it.isNumber }?.asInt()

internal fun JsonNode.optionalLong(field: String): Long? =
    this[field]?.takeIf { !it.isNull && it.isNumber }?.asLong()

internal fun JsonNode.optionalBool(field: String): Boolean? =
    this[field]?.takeIf { !it.isNull && it.isBoolean }?.booleanValue()

internal fun JsonNode.boolOrDefault(field: String, default: Boolean): Boolean =
    this[field]?.takeIf { !it.isNull }?.asBoolean() ?: default

internal fun JsonNode.toStringList(): List<String> =
    if (isArray) map { it.asText() } else emptyList()

internal fun JsonNode.objectEntries(): Sequence<Pair<String, JsonNode>> =
    fieldNames().asSequence().map { fieldName -> fieldName to get(fieldName) }
