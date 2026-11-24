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

/**
 * Ein Skalar, den der YAML-Resolver beim Lesen verlustbehaftet typisiert hat (`OFF` → `false`,
 * `010` → `8`); siehe `yaml.YamlWrittenScalars`. [written] ist der Text, wie er in der Datei steht.
 */
internal interface WrittenScalar {
    val written: String
    val path: String
    val kind: String
}

/**
 * Der Wert als **Text**, wie ihn das Schema meint. Ein verlustbehaftet typisierter YAML-Skalar ist hier
 * ein Fehler: still `'false'` statt `'OFF'` oder `'8'` statt `'010'` zu lesen, aenderte den Wert, und
 * die Datei saehe dabei richtig aus.
 */
internal fun JsonNode.asSchemaText(): String {
    if (this is WrittenScalar) {
        throw IllegalArgumentException(
            "$path: YAML liest `$written` als $kind (`${asText()}`), nicht als Text. " +
                "Setzen Sie den Wert in Anfuehrungszeichen: \"$written\".",
        )
    }
    return asText()
}

internal fun JsonNode.requiredText(field: String): String =
    this[field]?.asSchemaText() ?: throw IllegalArgumentException("Missing required field: $field")

internal fun JsonNode.optionalText(field: String): String? =
    this[field]?.takeIf { !it.isNull }?.asSchemaText()

internal fun JsonNode.textOrDefault(field: String, default: String): String =
    this[field]?.asSchemaText() ?: default

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
    if (isArray) map { it.asSchemaText() } else emptyList()

internal fun JsonNode.objectEntries(): Sequence<Pair<String, JsonNode>> =
    fieldNames().asSequence().map { fieldName -> fieldName to get(fieldName) }
