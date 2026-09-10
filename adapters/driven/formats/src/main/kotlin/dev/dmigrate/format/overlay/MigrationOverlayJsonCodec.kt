package dev.dmigrate.format.overlay

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlay
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayBinding
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayCanonicalJson
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayConversionReversibility
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDataRisk
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDiagnostics
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayEntry
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.OverlayText
import dev.dmigrate.core.diff.migration.overlay.PartitionMappingOverlayEntry
import dev.dmigrate.core.diff.migration.overlay.RawTextProvenanceOverlayEntry
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

        val formatVersion = root.requiredText("formatVersion", "$")
        return MigrationOverlay(
            formatVersion = formatVersion,
            overlayKind = root.requiredText("overlayKind", "$"),
            binding = parseBinding(root, formatVersion),
            dialect = root.requiredText("dialect", "$"),
            entries = parseEntries(root.requiredArray("entries", "$")),
            createdAt = root.requiredText("createdAt", "$"),
            createdByVersion = root.requiredText("createdByVersion", "$"),
            overlayHash = root.optionalText("overlayHash", "$"),
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

    /**
     * Die Bindung aus der Draht-Form. Welche es ist, sagen die vorhandenen
     * Felder — nicht die Formatversion: ein Uebergang steht flach unter
     * `sourceFingerprint`/`targetFingerprint`, wie seit jeher, eine
     * Darstellung unter `schemaFingerprint`.
     *
     * Beide zugleich oder keines von beiden wird abgelehnt, nicht umgedeutet.
     * Und `schemaFingerprint` in einem v1-Dokument ist ein Widerspruch: diese
     * Formatversion kennt die Bindung nicht, also kann sie sie auch nicht
     * meinen.
     */
    private fun parseBinding(root: JsonNode, formatVersion: String): MigrationOverlayBinding {
        val schemaFingerprint = root.optionalText("schemaFingerprint", "$")
        val hasTransitionFields = root.has("sourceFingerprint") || root.has("targetFingerprint")
        if (schemaFingerprint != null && hasTransitionFields) {
            decode(
                MigrationOverlayDiagnostics.FIELD_TYPE_MISMATCH,
                "$",
                "Overlay carries both a representation binding (schemaFingerprint) and a transition " +
                    "binding (sourceFingerprint/targetFingerprint)",
            )
        }
        if (schemaFingerprint == null) {
            return MigrationOverlayBinding.Transition(
                sourceFingerprint = root.requiredText("sourceFingerprint", "$"),
                targetFingerprint = root.requiredText("targetFingerprint", "$"),
            )
        }
        if (formatVersion == MigrationOverlay.FORMAT_VERSION_V1) {
            decode(
                MigrationOverlayDiagnostics.UNKNOWN_FORMAT_VERSION,
                "$.schemaFingerprint",
                "A representation binding requires formatVersion '${MigrationOverlay.FORMAT_VERSION_V2}'",
            )
        }
        return MigrationOverlayBinding.Representation(schemaFingerprint)
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
            MigrationOverlayKinds.PARTITION_MAPPING -> parsePartitionEntry(node, path)
            MigrationOverlayKinds.RAW_TEXT_PROVENANCE -> parseProvenanceEntry(node, path)
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
            sourceType = node.requiredText("sourceType", path),
            targetType = node.requiredText("targetType", path),
            upUsingExpression = parseText(node.requiredObject("upUsingExpression", path), "$path.upUsingExpression"),
            downUsingExpression = node.get("downUsingExpression")?.let {
                parseText(node.requiredObject("downUsingExpression", path), "$path.downUsingExpression")
            },
            dataRisk = node.requiredEnum("dataRisk", path, MigrationOverlayDataRisk.entries.associateBy { it.name }),
            conversionReversibility = node.requiredEnum(
                "reversibility",
                path,
                MigrationOverlayConversionReversibility.entries.associateBy { it.name },
            ),
            expressionSource = node.requiredText("expressionSource", path),
            reviewedByUser = node.requiredBoolean("reviewedByUser", path),
            requiredFeatures = parseRequiredFeatures(node.get("requiredFeatures"), "$path.requiredFeatures"),
        )
    }

    private fun parsePartitionEntry(node: JsonNode, path: String): PartitionMappingOverlayEntry {
        requireOnlyFields(node, PARTITION_ENTRY_FIELDS, path)
        return PartitionMappingOverlayEntry(
            id = node.requiredText("id", path),
            table = node.requiredText("table", path),
            sourcePartition = node.requiredText("sourcePartition", path),
            targetPartition = node.optionalText("targetPartition", path),
            // Die Reihenfolge bleibt, wie sie geschrieben wurde: sie ist Teil
            // des Dokuments und geht in den Hash ein. Sortiert wird erst in der
            // Pruefung.
            values = node.get("values")?.let { values ->
                if (!values.isArray) {
                    decode(MigrationOverlayDiagnostics.FIELD_TYPE_MISMATCH, "$path.values", "Expected array")
                }
                values.mapIndexed { index, element ->
                    if (!element.isTextual) {
                        decode(
                            MigrationOverlayDiagnostics.FIELD_TYPE_MISMATCH,
                            "$path.values[$index]", "Expected string",
                        )
                    }
                    element.asText()
                }
            },
            rangeUpperBound = node.optionalText("rangeUpperBound", path),
            requiredFeatures = parseRequiredFeatures(node.get("requiredFeatures"), "$path.requiredFeatures"),
        )
    }

    private fun parseProvenanceEntry(node: JsonNode, path: String): RawTextProvenanceOverlayEntry {
        requireOnlyFields(node, PROVENANCE_ENTRY_FIELDS, path)
        return RawTextProvenanceOverlayEntry(
            id = node.requiredText("id", path),
            objectType = node.requiredText("objectType", path),
            objectPath = node.requiredArray("objectPath", path).mapIndexed { index, segment ->
                if (!segment.isTextual) {
                    decode(
                        MigrationOverlayDiagnostics.FIELD_TYPE_MISMATCH,
                        "$path.objectPath[$index]",
                        "Expected string",
                    )
                }
                segment.asText()
            },
            field = node.requiredText("field", path),
            // Die Stellung steht als Zeichenkette auf dem Draht, wie jede
            // andere Zahl im kanonischen JSON auch: die Form ist ueber alle
            // Eintragsarten dieselbe, und der Hash haengt daran.
            keyPosition = node.optionalText("keyPosition", path)?.let { raw ->
                raw.toIntOrNull() ?: decode(
                    MigrationOverlayDiagnostics.FIELD_TYPE_MISMATCH,
                    "$path.keyPosition",
                    "Expected an integer, got '$raw'",
                )
            },
            appliedAuthorText = node.requiredText("appliedAuthorText", path),
            observedCatalogText = node.requiredText("observedCatalogText", path),
            requiredFeatures = parseRequiredFeatures(node.get("requiredFeatures"), path),
        )
    }

    private fun parseRenameEntry(node: JsonNode, path: String): RenameMappingOverlayEntry {
        requireOnlyFields(node, RENAME_ENTRY_FIELDS, path)
        return RenameMappingOverlayEntry(
            id = node.requiredText("id", path),
            objectType = node.requiredText("objectType", path),
            fromName = node.requiredText("fromName", path),
            toName = node.requiredText("toName", path),
            fromStructureFingerprint = node.optionalText("fromStructureFingerprint", path),
            toStructureFingerprint = node.optionalText("toStructureFingerprint", path),
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

    private fun JsonNode.optionalText(field: String, path: String): String? {
        val value = get(field) ?: return null
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

    private fun JsonNode.requiredBoolean(field: String, path: String): Boolean {
        val value = get(field)
            ?: decode(MigrationOverlayDiagnostics.REQUIRED_FIELD_MISSING, "$path.$field", "Required field is missing")
        if (!value.isBoolean) {
            decode(MigrationOverlayDiagnostics.FIELD_TYPE_MISMATCH, "$path.$field", "Expected boolean")
        }
        return value.asBoolean()
    }

    private fun <T : Enum<T>> JsonNode.requiredEnum(
        field: String,
        path: String,
        values: Map<String, T>,
    ): T {
        val raw = requiredText(field, path)
        return values[raw] ?: decode(
            MigrationOverlayDiagnostics.FIELD_TYPE_MISMATCH,
            "$path.$field",
            "Unsupported enum value '$raw'",
        )
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
            "schemaFingerprint",
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
            "sourceType",
            "targetType",
            "upUsingExpression",
            "downUsingExpression",
            "dataRisk",
            "reversibility",
            "expressionSource",
            "reviewedByUser",
            "requiredFeatures",
        )
        private val PARTITION_ENTRY_FIELDS = setOf(
            "kind",
            "id",
            "table",
            "sourcePartition",
            "targetPartition",
            "values",
            "rangeUpperBound",
            "requiredFeatures",
        )
        private val RENAME_ENTRY_FIELDS = setOf(
            "kind",
            "id",
            "objectType",
            "fromName",
            "toName",
            "fromStructureFingerprint",
            "toStructureFingerprint",
            "requiredFeatures",
        )
        private val PROVENANCE_ENTRY_FIELDS = setOf(
            "kind",
            "id",
            "objectType",
            "objectPath",
            "field",
            "keyPosition",
            "appliedAuthorText",
            "observedCatalogText",
            "requiredFeatures",
        )
        private val TEXT_FIELDS = setOf("value", "secret")
    }
}
