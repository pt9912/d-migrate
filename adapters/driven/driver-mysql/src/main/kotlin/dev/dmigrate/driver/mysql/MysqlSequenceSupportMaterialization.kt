package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadSeverity

internal data class TriggerResolution(
    val confirmedTriggers: MutableSet<String> = linkedSetOf(),
    val assignments: MutableMap<String, MutableMap<String, String>> = linkedMapOf(),
    val columnCauses: LinkedHashMap<Pair<String, String>, MutableList<String>> = LinkedHashMap(),
)

internal fun buildSupportDiagnostics(
    scope: ReverseScope,
    ambiguousKeys: Set<String>,
    triggerAssessments: List<MysqlMetadataQueries.SupportTriggerAssessment>,
    triggerScanAccessible: Boolean,
    confirmedSequenceNames: Set<String>,
): List<SupportDiagnostic> {
    val causesByKey = LinkedHashMap<DiagnosticKey, MutableList<String>>()

    fun addCause(key: DiagnosticKey, cause: String) {
        causesByKey.getOrPut(key) { mutableListOf() } += cause
    }

    for (name in ambiguousKeys) {
        addCause(
            SequenceDiagnosticKey(scope, name),
            "multiple rows for sequence key '$name'",
        )
    }

    if (!triggerScanAccessible && confirmedSequenceNames.isNotEmpty()) {
        for (seq in confirmedSequenceNames) {
            addCause(
                SequenceDiagnosticKey(scope, seq),
                "support trigger metadata is not accessible; column-level verification not possible",
            )
        }
    }

    if (confirmedSequenceNames.isNotEmpty()) {
        for (assessment in triggerAssessments) {
            when (assessment.state) {
                SupportTriggerState.MISSING_MARKER,
                SupportTriggerState.NON_CANONICAL,
                SupportTriggerState.NOT_ACCESSIBLE -> {
                    val column = assessment.columnName
                    if (column != null && assessment.tableName.isNotEmpty()) {
                        val columnKey = ColumnDiagnosticKey(scope, assessment.tableName, column)
                        addCause(
                            columnKey,
                            "support trigger '${assessment.triggerName}' has state ${assessment.state}",
                        )
                    }
                }
                SupportTriggerState.CONFIRMED,
                SupportTriggerState.USER_OBJECT -> Unit
            }
        }
    }

    return causesByKey.map { (key, causes) ->
        SupportDiagnostic(key = key, causes = causes, emitsW116 = true)
    }
}

internal fun collectTriggerResolution(
    snapshot: MysqlSequenceSupportSnapshot,
    materializedSequences: Map<String, SequenceDefinition>,
): TriggerResolution {
    val resolution = TriggerResolution()
    for (assessment in snapshot.triggerAssessments) {
        processTriggerAssessment(assessment, materializedSequences, resolution)
    }
    return resolution
}

private fun processTriggerAssessment(
    assessment: MysqlMetadataQueries.SupportTriggerAssessment,
    materializedSequences: Map<String, SequenceDefinition>,
    resolution: TriggerResolution,
) {
    when (assessment.state) {
        SupportTriggerState.CONFIRMED ->
            processConfirmedTrigger(assessment, materializedSequences, resolution)
        SupportTriggerState.MISSING_MARKER,
        SupportTriggerState.NON_CANONICAL,
        SupportTriggerState.NOT_ACCESSIBLE ->
            processDegradedTrigger(assessment, resolution)
        SupportTriggerState.USER_OBJECT -> Unit
    }
}

private fun processConfirmedTrigger(
    assessment: MysqlMetadataQueries.SupportTriggerAssessment,
    materializedSequences: Map<String, SequenceDefinition>,
    resolution: TriggerResolution,
) {
    val triggerName = assessment.triggerName
    val table = assessment.tableName
    val column = assessment.columnName
    val sequenceName = assessment.sequenceName

    if (column == null || sequenceName == null) {
        if (column != null) {
            addColumnCause(
                resolution.columnCauses,
                table,
                column,
                "support trigger '$triggerName' confirmed but sequence name not extractable",
            )
        }
        return
    }

    if (sequenceName !in materializedSequences) {
        addColumnCause(
            resolution.columnCauses,
            table,
            column,
            "support trigger '$triggerName' references non-materialized sequence '$sequenceName'",
        )
        return
    }

    val expectedName = MysqlSequenceNaming.triggerName(table, column)
    if (triggerName != expectedName) {
        addColumnCause(
            resolution.columnCauses,
            table,
            column,
            "support trigger name '$triggerName' does not match expected '$expectedName'",
        )
        return
    }

    resolution.confirmedTriggers += triggerName
    resolution.assignments.getOrPut(table) { mutableMapOf() }[column] = sequenceName
}

private fun processDegradedTrigger(
    assessment: MysqlMetadataQueries.SupportTriggerAssessment,
    resolution: TriggerResolution,
) {
    val column = assessment.columnName
    if (column != null && assessment.tableName.isNotEmpty()) {
        addColumnCause(
            resolution.columnCauses,
            assessment.tableName,
            column,
            "support trigger '${assessment.triggerName}' has state ${assessment.state}",
        )
    }
}

internal fun buildTriggerVerificationNotes(
    snapshot: MysqlSequenceSupportSnapshot,
    materializedSequences: Map<String, SequenceDefinition>,
): List<SchemaReadNote> {
    if (
        snapshot.triggerAssessments.isNotEmpty() ||
        materializedSequences.isEmpty() ||
        !hasInaccessibleTriggerScan(snapshot)
    ) {
        return emptyList()
    }

    return materializedSequences.keys.map { sequenceKey ->
        SchemaReadNote(
            severity = SchemaReadSeverity.WARNING,
            code = "W116",
            objectName = sequenceKey,
            message = "Sequence metadata reconstructed, but trigger verification was not possible",
            hint = "support trigger metadata is not accessible",
        )
    }
}

private fun hasInaccessibleTriggerScan(
    snapshot: MysqlSequenceSupportSnapshot,
): Boolean = snapshot.diagnostics.any { diagnostic ->
    diagnostic.emitsW116 &&
        diagnostic.key is SequenceDiagnosticKey &&
        diagnostic.causes.any { cause ->
            "trigger" in cause.lowercase() && "not accessible" in cause.lowercase()
        }
}

internal fun enrichTablesWithSequenceDefaults(
    tables: Map<String, TableDefinition>,
    resolution: TriggerResolution,
): Map<String, TableDefinition> = tables.mapValues { (tableName, tableDefinition) ->
    val tableAssignments = resolution.assignments[tableName] ?: return@mapValues tableDefinition
    val enrichedColumns = tableDefinition.columns.mapValues { (columnName, columnDefinition) ->
        val sequenceName = tableAssignments[columnName] ?: return@mapValues columnDefinition
        val existingDefault = columnDefinition.default
        val compatible = existingDefault == null ||
            (
                existingDefault is DefaultValue.SequenceNextVal &&
                    existingDefault.sequenceName == sequenceName
                )
        if (!compatible) {
            addColumnCause(
                resolution.columnCauses,
                tableName,
                columnName,
                "confirmed trigger but column has conflicting default '$existingDefault'",
            )
            columnDefinition
        } else {
            columnDefinition.copy(default = DefaultValue.SequenceNextVal(sequenceName))
        }
    }
    tableDefinition.copy(columns = LinkedHashMap(enrichedColumns))
}

internal fun buildColumnCauseNotes(
    columnCauses: Map<Pair<String, String>, List<String>>,
): List<SchemaReadNote> = columnCauses.map { (tableColumn, causes) ->
    SchemaReadNote(
        severity = SchemaReadSeverity.WARNING,
        code = "W116",
        objectName = "${tableColumn.first}.${tableColumn.second}",
        message = "Sequence metadata reconstructed, but required support objects are missing or degraded",
        hint = causes.joinToString("; "),
    )
}

private fun addColumnCause(
    columnCauses: MutableMap<Pair<String, String>, MutableList<String>>,
    table: String,
    column: String,
    cause: String,
) {
    columnCauses.getOrPut(table to column) { mutableListOf() } += cause
}
