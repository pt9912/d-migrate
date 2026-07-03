package dev.dmigrate.core.diff.migration.artifact

import dev.dmigrate.core.util.sha256Hex

object MigrationPlanArtifactCanonicalJson {

    fun encode(artifact: MigrationPlanArtifact, includeArtifactHash: Boolean = true): String {
        val fields = mutableListOf<Pair<String, JsonValue>>()
        fields += "formatVersion" to JsonString(artifact.formatVersion)
        fields += "dMigrateVersion" to JsonString(artifact.dMigrateVersion)
        fields += "sourceFingerprint" to JsonString(artifact.sourceFingerprint)
        fields += "targetFingerprint" to JsonString(artifact.targetFingerprint)
        fields += "fingerprintAlgorithm" to JsonString(artifact.fingerprintAlgorithm)
        fields += "dialect" to JsonString(artifact.dialect)
        fields += "operations" to JsonArray(artifact.operations.map(::operationValue))
        fields += "diagnostics" to JsonArray(artifact.diagnostics.map(::diagnosticValue))
        fields += "reversibilitySummary" to reversibilityValue(artifact.reversibilitySummary)
        fields += "requiredFeatures" to stringArray(artifact.requiredFeatures.sorted())
        if (artifact.semanticExtensions.isNotEmpty()) {
            fields += "semanticExtensions" to stringArray(artifact.semanticExtensions.sorted())
        }
        if (artifact.renderedStatements.isNotEmpty()) {
            fields += "renderedStatements" to JsonArray(artifact.renderedStatements.map(::statementValue))
        }
        if (artifact.renameProjections.isNotEmpty()) {
            fields += "renameProjections" to JsonArray(artifact.renameProjections.map(::renameProjectionValue))
        }
        fields += "createdAt" to JsonString(artifact.createdAt)
        if (artifact.producerMetadata.isNotEmpty()) {
            fields += "producerMetadata" to JsonObject(
                artifact.producerMetadata.entries
                    .sortedBy { it.key }
                    .map { it.key to JsonString(it.value) },
            )
        }
        if (includeArtifactHash) {
            fields += "artifactHash" to (artifact.artifactHash?.let(::JsonString) ?: JsonNull)
        }
        return render(JsonObject(fields))
    }

    fun encodeUnsigned(artifact: MigrationPlanArtifact): String =
        encode(artifact.copy(artifactHash = null), includeArtifactHash = false)

    fun computeHash(artifact: MigrationPlanArtifact): String =
        sha256Hex(encodeUnsigned(artifact))

    private fun operationValue(operation: MigrationPlanArtifactOperation): JsonValue =
        JsonObject(
            buildList {
                add("id" to JsonString(operation.id))
                add("kind" to JsonString(operation.kind))
                add("objectType" to JsonString(operation.objectType))
                add("objectPath" to stringArray(operation.objectPath))
                add("phase" to JsonString(operation.phase))
                add("reversibility" to JsonString(operation.reversibility))
                add("upRisk" to riskValue(operation.upRisk))
                operation.downRisk?.let { add("downRisk" to riskValue(it)) }
            },
        )

    private fun riskValue(risk: MigrationPlanRisk): JsonValue =
        JsonObject(
            buildList {
                add("destructive" to JsonBoolean(risk.destructive))
                add("dataLossPossible" to JsonBoolean(risk.dataLossPossible))
                add("requiresTableRewrite" to JsonBoolean(risk.requiresTableRewrite))
                add("requiresManualConfirmation" to JsonBoolean(risk.requiresManualConfirmation))
                add("dataTransformationMode" to JsonString(risk.dataTransformationMode))
                risk.dataTransformationModelVersion?.let {
                    add("dataTransformationModelVersion" to JsonString(it))
                }
                risk.dataTransformationModelId?.let {
                    add("dataTransformationModelId" to JsonString(it))
                }
            },
        )

    private fun diagnosticValue(diagnostic: MigrationPlanArtifactDiagnostic): JsonValue =
        JsonObject(
            buildList {
                add("code" to JsonString(diagnostic.code))
                add("severity" to JsonString(diagnostic.severity))
                diagnostic.operationId?.let { add("operationId" to JsonString(it)) }
            },
        )

    private fun reversibilityValue(summary: MigrationPlanReversibilitySummary): JsonValue =
        JsonObject(
            listOf(
                "fullyReversible" to JsonBoolean(summary.fullyReversible),
                "manualRequiredOperationIds" to stringArray(summary.manualRequiredOperationIds),
                "notReversibleOperationIds" to stringArray(summary.notReversibleOperationIds),
            ),
        )

    private fun statementValue(statement: MigrationPlanRenderedStatement): JsonValue =
        JsonObject(
            listOf(
                "statementId" to JsonString(statement.statementId),
                "operationIds" to stringArray(statement.operationIds),
                "sqlHash" to JsonString(statement.sqlHash),
                "transactionScope" to JsonString(statement.transactionScope),
            ),
        )

    private fun renameProjectionValue(projection: MigrationPlanArtifactRenameProjection): JsonValue =
        JsonObject(
            buildList {
                add("candidateId" to JsonString(projection.candidateId))
                add("objectType" to JsonString(projection.objectType))
                add("fromPath" to stringArray(projection.fromPath))
                add("toPath" to stringArray(projection.toPath))
                add("overlaySource" to JsonString(projection.overlaySource))
                add("overlayEntryId" to JsonString(projection.overlayEntryId))
                projection.overlayHash?.let { add("overlayHash" to JsonString(it)) }
                projection.renameOperationId?.let { add("renameOperationId" to JsonString(it)) }
                if (projection.fallbackOperationIds.isNotEmpty()) {
                    add("fallbackOperationIds" to stringArray(projection.fallbackOperationIds))
                }
                projection.fallbackReason?.let { add("fallbackReason" to JsonString(it)) }
            },
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
