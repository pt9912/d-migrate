package dev.dmigrate.format.verify

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.dmigrate.verify.ValueCanonicalizationException
import java.nio.charset.StandardCharsets

/**
 * LN-009 / ADR 0030: semantische JSON-Kanonik für den Verify-Vergleich.
 *
 * - Object-Keys werden **rekursiv sortiert** (Reihenfolge irrelevant).
 * - Array-Reihenfolge bleibt erhalten (in JSON signifikant).
 * - Numerische Knoten werden über [java.math.BigDecimal.stripTrailingZeros]
 *   normalisiert (`1.0` == `1`, `1.50` == `1.5`).
 * - Insignifikanter Whitespace entfällt (kompakte Serialisierung).
 *
 * So liefern PG `jsonb`, MySQL `json` und ein SQLite-`text`-JSON für denselben
 * logischen Inhalt identische Bytes.
 */
class CanonicalJson(private val mapper: ObjectMapper = ObjectMapper()) {

    fun canonicalize(text: String): ByteArray {
        val tree = try {
            mapper.readTree(text)
        } catch (e: Exception) {
            throw ValueCanonicalizationException("JSON nicht parsebar: ${e.message}", e)
        }
        return mapper.writeValueAsString(sortNode(tree)).toByteArray(StandardCharsets.UTF_8)
    }

    private fun sortNode(node: JsonNode): JsonNode = when (node) {
        is ObjectNode -> {
            val sorted = ObjectNode(JsonNodeFactory.instance)
            node.fieldNames().asSequence().sorted().forEach { name ->
                sorted.set<JsonNode>(name, sortNode(node.get(name)))
            }
            sorted
        }
        is ArrayNode -> {
            val out = ArrayNode(JsonNodeFactory.instance)
            node.forEach { out.add(sortNode(it)) }
            out
        }
        else -> if (node.isNumber) {
            // Zahl über BigDecimal normalisieren (1.0 == 1, 1.50 == 1.5).
            JsonNodeFactory.instance.numberNode(node.decimalValue().stripTrailingZeros())
        } else {
            node
        }
    }
}
