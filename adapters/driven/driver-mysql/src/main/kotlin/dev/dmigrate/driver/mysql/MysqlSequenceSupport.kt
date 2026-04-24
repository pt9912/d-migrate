package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*
import dev.dmigrate.driver.metadata.JdbcOperations

private fun earlySnapshot(
    scope: ReverseScope,
    state: SupportTableState,
    cause: String,
) = MysqlSequenceSupportSnapshot(
    scope = scope,
    supportTableState = state,
    sequenceRows = emptyList(),
    ambiguousKeys = emptySet(),
    routineStates = emptyMap(),
    triggerStates = emptyMap(),
    triggerAssessments = emptyList(),
    diagnostics = listOf(
        SupportDiagnostic(
            key = SequenceDiagnosticKey(scope, MysqlSequenceNaming.SUPPORT_TABLE),
            causes = listOf(cause),
            emitsW116 = true,
        ),
    ),
)

internal class MysqlSequenceSupport {

    fun scanSequenceSupport(
        session: JdbcOperations,
        database: String,
        scope: ReverseScope,
    ): MysqlSequenceSupportSnapshot {
        val exists = MysqlMetadataQueries.checkSupportTableExists(session, database)
        if (exists == null) {
            return MysqlSequenceSupportSnapshot.nonSequence(scope)
                .copy(supportTableState = SupportTableState.NOT_ACCESSIBLE)
        }
        if (exists == false) return MysqlSequenceSupportSnapshot.nonSequence(scope)

        val shapeResult = verifySupportTableShape(session, database, scope)
        if (shapeResult != null) return shapeResult

        val rawRows = try {
            MysqlMetadataQueries.listSupportSequenceRows(session, database)
        } catch (_: Exception) {
            return earlySnapshot(
                scope,
                SupportTableState.NOT_ACCESSIBLE,
                "dmg_sequences exists but rows are not readable",
            )
        }

        val sequenceRows = rawRows.map { row -> mapSequenceRow(row) }
        val (ambiguousKeys, dmigrateRowNames) = detectAmbiguousKeys(sequenceRows)
        val routineStates = scanRoutineStates(session, database)
        val triggerScan = MysqlMetadataQueries.listPotentialSupportTriggers(session, database)
        val diagnostics = buildSupportDiagnostics(
            scope = scope,
            ambiguousKeys = ambiguousKeys,
            triggerAssessments = triggerScan.triggers,
            triggerScanAccessible = triggerScan.accessible,
            confirmedSequenceNames = dmigrateRowNames,
        )

        return MysqlSequenceSupportSnapshot(
            scope = scope,
            supportTableState = SupportTableState.AVAILABLE,
            sequenceRows = sequenceRows,
            ambiguousKeys = ambiguousKeys,
            routineStates = routineStates,
            triggerStates = triggerScan.triggers.associate { it.triggerName to it.state },
            triggerAssessments = triggerScan.triggers,
            diagnostics = diagnostics,
        )
    }

    private fun verifySupportTableShape(
        session: JdbcOperations,
        database: String,
        scope: ReverseScope,
    ): MysqlSequenceSupportSnapshot? {
        val shapeOk = try {
            MysqlMetadataQueries.checkSupportTableShape(session, database)
        } catch (_: Exception) {
            return earlySnapshot(
                scope,
                SupportTableState.NOT_ACCESSIBLE,
                "dmg_sequences exists but metadata is not accessible",
            )
        }
        if (!shapeOk) {
            return earlySnapshot(
                scope,
                SupportTableState.INVALID_SHAPE,
                "dmg_sequences column shape is not canonical",
            )
        }
        return null
    }

    data class D2Result(
        val sequences: Map<String, SequenceDefinition>,
        val notes: List<SchemaReadNote>,
    )

    fun materializeSupportSequences(
        snapshot: MysqlSequenceSupportSnapshot,
    ): D2Result {
        if (snapshot.supportTableState != SupportTableState.AVAILABLE) {
            return D2Result(emptyMap(), aggregateSupportNotes(snapshot))
        }

        val candidates = mutableListOf<ValidCandidate>()
        val invalids = mutableListOf<InvalidEvidence>()

        for (row in snapshot.sequenceRows) {
            when (val assessment = assessSequenceRow(row, snapshot.ambiguousKeys)) {
                is ValidCandidate -> candidates += assessment
                is InvalidEvidence -> invalids += assessment
                null -> Unit
            }
        }

        val sequences = LinkedHashMap<String, SequenceDefinition>()
        for (candidate in candidates.sortedBy { it.key }) {
            sequences[candidate.key] = candidate.sequence
        }

        val allNotes = buildSupportSequenceNotes(
            ambiguousKeys = snapshot.ambiguousKeys,
            invalids = invalids,
            sequenceKeys = sequences.keys,
            routineStates = snapshot.routineStates,
        )

        return D2Result(sequences, allNotes)
    }

    fun aggregateSupportNotes(
        snapshot: MysqlSequenceSupportSnapshot,
    ): List<SchemaReadNote> {
        return snapshot.diagnostics
            .filter { it.emitsW116 }
            .groupBy { it.key }
            .map { (key, diagnostics) ->
                SchemaReadNote(
                    severity = SchemaReadSeverity.WARNING,
                    code = "W116",
                    objectName = when (key) {
                        is SequenceDiagnosticKey -> key.sequenceName
                        is ColumnDiagnosticKey -> "${key.tableName}.${key.columnName}"
                    },
                    message = "Sequence metadata reconstructed, but required support objects are missing or degraded",
                    hint = diagnostics.flatMap { it.causes }.joinToString("; "),
                )
            }
    }

    data class D3Result(
        val enrichedTables: Map<String, TableDefinition>,
        val confirmedTriggerNames: Set<String>,
        val notes: List<SchemaReadNote>,
    ) {
        fun filteredTriggers(
            triggers: Map<String, TriggerDefinition>,
        ): Map<String, TriggerDefinition> {
            if (confirmedTriggerNames.isEmpty()) return triggers
            return triggers.filterKeys { key ->
                val (_, triggerName) = dev.dmigrate.core.identity.ObjectKeyCodec.parseTriggerKey(key)
                triggerName !in confirmedTriggerNames
            }
        }
    }

    fun materializeSequenceDefaults(
        snapshot: MysqlSequenceSupportSnapshot,
        materializedSequences: Map<String, SequenceDefinition>,
        tables: Map<String, TableDefinition>,
    ): D3Result {
        if (snapshot.supportTableState != SupportTableState.AVAILABLE) {
            return D3Result(tables, emptySet(), emptyList())
        }

        val resolution = collectTriggerResolution(snapshot, materializedSequences)
        val d3Notes = buildTriggerVerificationNotes(snapshot, materializedSequences).toMutableList()
        val enrichedTables = enrichTablesWithSequenceDefaults(tables, resolution)
        d3Notes += buildColumnCauseNotes(resolution.columnCauses)

        return D3Result(enrichedTables, resolution.confirmedTriggers, d3Notes)
    }

    fun filterSupportTable(
        tables: Map<String, TableDefinition>,
        snapshot: MysqlSequenceSupportSnapshot,
    ): Map<String, TableDefinition> {
        if (snapshot.supportTableState != SupportTableState.AVAILABLE) return tables
        return tables.filterKeys { it != MysqlSequenceNaming.SUPPORT_TABLE }
    }

    fun filterSupportRoutines(
        functions: Map<String, FunctionDefinition>,
        snapshot: MysqlSequenceSupportSnapshot,
    ): Map<String, FunctionDefinition> {
        val confirmed = snapshot.routineStates
            .filter { it.value == SupportRoutineState.CONFIRMED }
            .keys
        if (confirmed.isEmpty()) return functions
        return functions.filterKeys { key ->
            val rawName = key.substringBefore('(')
            rawName !in confirmed
        }
    }
}
