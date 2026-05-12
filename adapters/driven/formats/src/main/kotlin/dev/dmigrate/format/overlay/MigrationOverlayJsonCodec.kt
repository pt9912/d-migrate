package dev.dmigrate.format.overlay

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlay
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayCanonicalJson
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDiagnostics
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayEntry
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.OverlayText
import dev.dmigrate.core.diff.migration.overlay.RenameMappingOverlayEntry
import dev.dmigrate.core.diff.migration.overlay.UsingExpressionOverlayEntry
import java.io.InputStream
import java.io.OutputStream

class MigrationOverlayJsonDecodeException(
    val code: String,
    val path: String,
    message: String,
) : IllegalArgumentException("$code at $path: $message")

class MigrationOverlayJsonEncodeException(
    val code: String,
    message: String,
) : IllegalArgumentException("$code: $message")

class MigrationOverlayJsonCodec {

    private val readMapper = ObjectMapper(JsonFactory()).apply {
        enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
    }

    fun read(input: InputStream): MigrationOverlay {
        val root = readMapper.readTree(input)
            ?: decode(MigrationOverlayDiagnostics.REQUIRED_FIELD_MISSING, "$", "Empty JSON document")
        if (!root.isObject) {
            decode(MigrationOverlayDiagnostics.FIELD_TYPE_MISMATCH, "$", "Overlay document must be a JSON object")
        }
        requireOnlyFields(root, TOP_LEVEL_FIELDS, "$")

        return MigrationOverlay(
            formatVersion = root.requiredText("formatVersion", "$"),
            overlayKind = root.requiredText("overlayKind", "$"),
            sourceFingerprint = root.requiredText("sourceFingerprint", "$"),
            targetFingerprint = root.requiredText("targetFingerprint", "$"),
            dialect = root.requiredText("dialect", "$"),
            entries = parseEntries(root.requiredArray("entries", "$")),
            createdAt = root.requiredText("createdAt", "$"),
            createdByVersion = root.requiredText("createdByVersion", "$"),
            overlayHash = root.requiredText("overlayHash", "$"),
            producerMetadata = parseProducerMetadata(root.get("producerMetadata")),
        )
    }

    fun write(output: OutputStream, overlay: MigrationOverlay) {
        val actualHash = MigrationOverlayCanonicalJson.computeHash(overlay)
        when {
            overlay.overlayHash.isNullOrBlank() -> encode(
                MigrationOverlayDiagnostics.HASH_MISSING,
                "overlayHash is required before writing a migration overlay",
            )

            overlay.overlayHash != actualHash -> encode(
                MigrationOverlayDiagnostics.HASH_MISMATCH,
                "overlayHash does not match canonical overlay content",
            )
        }
        output.write(MigrationOverlayCanonicalJson.encode(overlay).toByteArray(Charsets.UTF_8))
    }

    private fun parseEntries(node: JsonNode): List<MigrationOverlayEntry> =
        node.mapIndexed { index, entry ->
            parseEntry(entry, "$.entries[$index]")
        }

    private fun parseEntry(node: JsonNode, path: String): MigrationOverlayEntry {
        if (!node.isObject) {
            decode(MigrationOverlayDiagnostics.FIELD_TYPE_MISMATCH, path, "Overlay entry must be a JSON object")
        }
        return when (val kind = node.requiredText("kind", path)) {
            MigrationOverlayKinds.USING_EXPRESSION -> parseUsingEntry(node, path)
            MigrationOverlayKinds.RENAME_MAPPING -> parseRenameEntry(node, path)
            else -> decode(
                MigrationOverlayDiagnostics.UNKNOWN_ENTRY_KIND,
                "$path.kind",
                "Unsupported overlay entry kind '$kind'",
            )
        }
    }

    private fun parseUsingEntry(node: JsonNode, path: String): UsingExpressionOverlayEntry {
        requireOnlyFields(node, USING_ENTRY_FIELDS, path)
        return UsingExpressionOverlayEntry(
            id = node.requiredText("id", path),
            table = node.requiredText("table", path),
            column = node.requiredText("column", path),
            expression = parseText(node.requiredObject("expression", path), "$path.expression"),
            requiredFeatures = parseRequiredFeatures(node.get("requiredFeatures"), "$path.requiredFeatures"),
        )
    }

    private fun parseRenameEntry(node: JsonNode, path: String): RenameMappingOverlayEntry {
        requireOnlyFields(node, RENAME_ENTRY_FIELDS, path)
        return RenameMappingOverlayEntry(
            id = node.requiredText("id", path),
            objectType = node.requiredText("objectType", path),
            fromName = node.requiredText("fromName", path),
            toName = node.requiredText("toName", path),
            requiredFeatures = parseRequiredFeatures(node.get("requiredFeatures"), "$path.requiredFeatures"),
        )
    }

    private fun parseText(node: JsonNode, path: String): OverlayText {
        requireOnlyFields(node, TEXT_FIELDS, path)
        return OverlayText(
            value = node.requiredText("value", path),
            secret = node.get("secret")?.let { secret ->
                if (!secret.isBoolean) {
                    decode(MigrationOverlayDiagnostics.FIELD_TYPE_MISMATCH, "$path.secret", "Expected boolean")
                }
                secret.asBoolean()
            } ?: false,
        )
    }

    private fun parseRequiredFeatures(node: JsonNode?, path: String): Set<String> {
        if (node == null || node.isNull) return emptySet()
        if (!node.isArray) {
            decode(MigrationOverlayDiagnostics.FIELD_TYPE_MISMATCH, path, "Expected array")
        }
        return node.mapIndexed { index, value ->
            if (!value.isTextual) {
                decode(MigrationOverlayDiagnostics.FIELD_TYPE_MISMATCH, "$path[$index]", "Expected string")
            }
            value.asText()
        }.toSet()
    }

    private fun parseProducerMetadata(node: JsonNode?): Map<String, String> {
        if (node == null || node.isNull) return emptyMap()
        if (!node.isObject) {
            decode(MigrationOverlayDiagnostics.FIELD_TYPE_MISMATCH, "$.producerMetadata", "Expected object")
        }
        return node.properties().asSequence().associate { (name, value) ->
            if (!value.isTextual) {
                decode(MigrationOverlayDiagnostics.FIELD_TYPE_MISMATCH, "$.producerMetadata.$name", "Expected string")
            }
            name to value.asText()
        }
    }

    private fun JsonNode.requiredText(field: String, path: String): String {
        val value = get(field)
            ?: decode(MigrationOverlayDiagnostics.REQUIRED_FIELD_MISSING, "$path.$field", "Required field is missing")
        if (!value.isTextual) {
            decode(MigrationOverlayDiagnostics.FIELD_TYPE_MISMATCH, "$path.$field", "Expected string")
        }
        return value.asText()
    }

    private fun JsonNode.requiredArray(field: String, path: String): JsonNode {
        val value = get(field)
            ?: decode(MigrationOverlayDiagnostics.REQUIRED_FIELD_MISSING, "$path.$field", "Required field is missing")
        if (!value.isArray) {
            decode(MigrationOverlayDiagnostics.FIELD_TYPE_MISMATCH, "$path.$field", "Expected array")
        }
        return value
    }

    private fun JsonNode.requiredObject(field: String, path: String): JsonNode {
        val value = get(field)
            ?: decode(MigrationOverlayDiagnostics.REQUIRED_FIELD_MISSING, "$path.$field", "Required field is missing")
        if (!value.isObject) {
            decode(MigrationOverlayDiagnostics.FIELD_TYPE_MISMATCH, "$path.$field", "Expected object")
        }
        return value
    }

    private fun requireOnlyFields(node: JsonNode, allowedFields: Set<String>, path: String) {
        val unknown = node.fieldNames().asSequence().filter { it !in allowedFields }.toList()
        if (unknown.isNotEmpty()) {
            val field = unknown.sorted().first()
            decode(
                MigrationOverlayDiagnostics.UNKNOWN_REQUIRED_FIELD,
                "$path.$field",
                "Unknown field '$field' is not part of migration-overlay.v1",
            )
        }
    }

    private fun decode(code: String, path: String, message: String): Nothing =
        throw MigrationOverlayJsonDecodeException(code, path, message)

    private fun encode(code: String, message: String): Nothing =
        throw MigrationOverlayJsonEncodeException(code, message)

    companion object {
        private val TOP_LEVEL_FIELDS = setOf(
            "formatVersion",
            "overlayKind",
            "sourceFingerprint",
            "targetFingerprint",
            "dialect",
            "entries",
            "createdAt",
            "createdByVersion",
            "producerMetadata",
            "overlayHash",
        )
        private val USING_ENTRY_FIELDS = setOf(
            "kind",
            "id",
            "table",
            "column",
            "expression",
            "requiredFeatures",
        )
        private val RENAME_ENTRY_FIELDS = setOf(
            "kind",
            "id",
            "objectType",
            "fromName",
            "toName",
            "requiredFeatures",
        )
        private val TEXT_FIELDS = setOf("value", "secret")
    }
}
