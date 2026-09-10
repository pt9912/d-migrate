package dev.dmigrate.core.diff.migration.overlay

import dev.dmigrate.core.util.sha256Hex

object MigrationOverlayCanonicalJson {

    fun encode(overlay: MigrationOverlay, includeOverlayHash: Boolean = true): String {
        val fields = mutableListOf<Pair<String, JsonValue>>()
        fields += "formatVersion" to JsonString(overlay.formatVersion)
        fields += "overlayKind" to JsonString(overlay.overlayKind)
        fields += bindingFields(overlay.binding)
        fields += "dialect" to JsonString(overlay.dialect)
        fields += "entries" to JsonArray(overlay.entries.map(::entryValue))
        fields += "createdAt" to JsonString(overlay.createdAt)
        fields += "createdByVersion" to JsonString(overlay.createdByVersion)
        if (overlay.producerMetadata.isNotEmpty()) {
            fields += "producerMetadata" to JsonObject(
                overlay.producerMetadata.entries
                    .sortedBy { it.key }
                    .map { it.key to JsonString(it.value) },
            )
        }
        if (includeOverlayHash) {
            fields += "overlayHash" to (overlay.overlayHash?.let(::JsonString) ?: JsonNull)
        }
        return render(JsonObject(fields))
    }

    /**
     * Die Bindung auf dem Draht. Ein [MigrationOverlayBinding.Transition]
     * steht **flach** unter `sourceFingerprint`/`targetFingerprint` — genau
     * wie vor der Bindungs-Trennung. Das ist kein Ueberbleibsel, sondern
     * Absicht: die Form haengt an der Bindung, nicht an der Formatversion, und
     * so behaelt jedes bestehende v1-Dokument seinen Hash. Nur die neue
     * Darstellungs-Bindung bringt ein neues Feld mit.
     */
    private fun bindingFields(binding: MigrationOverlayBinding): List<Pair<String, JsonValue>> =
        when (binding) {
            is MigrationOverlayBinding.Transition -> listOf(
                "sourceFingerprint" to JsonString(binding.sourceFingerprint),
                "targetFingerprint" to JsonString(binding.targetFingerprint),
            )

            is MigrationOverlayBinding.Representation -> listOf(
                "schemaFingerprint" to JsonString(binding.schemaFingerprint),
            )
        }

    fun encodeUnsigned(overlay: MigrationOverlay): String =
        encode(overlay.copy(overlayHash = null), includeOverlayHash = false)

    fun computeHash(overlay: MigrationOverlay): String =
        sha256Hex(encodeUnsigned(overlay))

    private fun entryValue(entry: MigrationOverlayEntry): JsonValue =
        when (entry) {
            is UsingExpressionOverlayEntry -> JsonObject(
                buildList {
                    add("kind" to JsonString(entry.kind))
                    add("id" to JsonString(entry.id))
                    add("table" to JsonString(entry.table))
                    add("column" to JsonString(entry.column))
                    add("sourceType" to JsonString(entry.sourceType))
                    add("targetType" to JsonString(entry.targetType))
                    add("upUsingExpression" to textValue(entry.upUsingExpression))
                    entry.downUsingExpression?.let { add("downUsingExpression" to textValue(it)) }
                    add("dataRisk" to JsonString(entry.dataRisk.name))
                    add("reversibility" to JsonString(entry.conversionReversibility.name))
                    add("expressionSource" to JsonString(entry.expressionSource))
                    add("reviewedByUser" to JsonBoolean(entry.reviewedByUser))
                    add("requiredFeatures" to stringArray(entry.requiredFeatures.sorted()))
                },
            )

            is PartitionMappingOverlayEntry -> JsonObject(
                buildList {
                    add("kind" to JsonString(entry.kind))
                    add("id" to JsonString(entry.id))
                    add("table" to JsonString(entry.table))
                    add("sourcePartition" to JsonString(entry.sourcePartition))
                    entry.targetPartition?.let { add("targetPartition" to JsonString(it)) }
                    // Die Wertemenge in DEKLARIERTER Reihenfolge: sie ist Teil
                    // der Aussage, nicht bloss eine Menge. Die Pruefung
                    // sortiert selbst, das Dokument bleibt, wie es geschrieben
                    // wurde.
                    entry.values?.let { add("values" to stringArray(it)) }
                    entry.rangeUpperBound?.let { add("rangeUpperBound" to JsonString(it)) }
                    add("requiredFeatures" to stringArray(entry.requiredFeatures.sorted()))
                },
            )

            is RawTextProvenanceOverlayEntry -> JsonObject(
                buildList {
                    add("kind" to JsonString(entry.kind))
                    add("id" to JsonString(entry.id))
                    add("objectType" to JsonString(entry.objectType))
                    // Der Pfad in DEKLARIERTER Reihenfolge: er ist der Weg zum
                    // Objekt, keine Menge.
                    add("objectPath" to stringArray(entry.objectPath))
                    add("field" to JsonString(entry.field))
                    entry.keyPosition?.let { add("keyPosition" to JsonString(it.toString())) }
                    add("appliedAuthorText" to JsonString(entry.appliedAuthorText))
                    add("observedCatalogText" to JsonString(entry.observedCatalogText))
                    add("requiredFeatures" to stringArray(entry.requiredFeatures.sorted()))
                },
            )

            is RenameMappingOverlayEntry -> JsonObject(
                buildList {
                    add("kind" to JsonString(entry.kind))
                    add("id" to JsonString(entry.id))
                    add("objectType" to JsonString(entry.objectType))
                    add("fromName" to JsonString(entry.fromName))
                    add("toName" to JsonString(entry.toName))
                    entry.fromStructureFingerprint?.let { add("fromStructureFingerprint" to JsonString(it)) }
                    entry.toStructureFingerprint?.let { add("toStructureFingerprint" to JsonString(it)) }
                    add("requiredFeatures" to stringArray(entry.requiredFeatures.sorted()))
                },
            )
        }

    private fun textValue(text: OverlayText): JsonValue =
        JsonObject(
            listOf(
                "value" to JsonString(text.value),
                "secret" to JsonBoolean(text.secret),
            ),
        )

    private fun stringArray(values: List<String>): JsonValue =
        JsonArray(values.map(::JsonString))

    private fun render(value: JsonValue): String {
        val out = StringBuilder()
        appendJson(out, value, indent = 0)
        out.append('\n')
        return out.toString()
    }

    private fun appendJson(out: StringBuilder, value: JsonValue, indent: Int) {
        when (value) {
            is JsonString -> out.append('"').append(escape(value.value)).append('"')
            is JsonBoolean -> out.append(value.value)
            JsonNull -> out.append("null")
            is JsonArray -> appendArray(out, value, indent)
            is JsonObject -> appendObject(out, value, indent)
        }
    }

    private fun appendObject(out: StringBuilder, obj: JsonObject, indent: Int) {
        out.append('{')
        if (obj.fields.isNotEmpty()) {
            out.append('\n')
            obj.fields.forEachIndexed { index, (name, value) ->
                appendIndent(out, indent + 2)
                out.append('"').append(escape(name)).append("\": ")
                appendJson(out, value, indent + 2)
                if (index < obj.fields.lastIndex) out.append(',')
                out.append('\n')
            }
            appendIndent(out, indent)
        }
        out.append('}')
    }

    private fun appendArray(out: StringBuilder, array: JsonArray, indent: Int) {
        out.append('[')
        if (array.values.isNotEmpty()) {
            out.append('\n')
            array.values.forEachIndexed { index, value ->
                appendIndent(out, indent + 2)
                appendJson(out, value, indent + 2)
                if (index < array.values.lastIndex) out.append(',')
                out.append('\n')
            }
            appendIndent(out, indent)
        }
        out.append(']')
    }

    private fun appendIndent(out: StringBuilder, indent: Int) {
        repeat(indent) { out.append(' ') }
    }

    private fun escape(value: String): String {
        val out = StringBuilder(value.length)
        for (char in value) {
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    if (char < ' ') {
                        out.append("\\u")
                        out.append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        out.append(char)
                    }
                }
            }
        }
        return out.toString()
    }

    private sealed interface JsonValue
    private data class JsonObject(val fields: List<Pair<String, JsonValue>>) : JsonValue
    private data class JsonArray(val values: List<JsonValue>) : JsonValue
    private data class JsonString(val value: String) : JsonValue
    private data class JsonBoolean(val value: Boolean) : JsonValue
    private data object JsonNull : JsonValue
}
