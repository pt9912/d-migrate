package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*
import dev.dmigrate.driver.metadata.JdbcOperations

private sealed interface SequenceRowAssessment

private data class ValidCandidate(
    val key: String,
    val sequence: SequenceDefinition,
) : SequenceRowAssessment

private data class InvalidEvidence(
    val key: String,
    val reason: String,
) : SequenceRowAssessment

private data class TriggerResolution(
    val confirmedTriggers: MutableSet<String> = linkedSetOf(),
    val assignments: MutableMap<String, MutableMap<String, String>> = linkedMapOf(),
    val columnCauses: LinkedHashMap<Pair<String, String>, MutableList<String>> = LinkedHashMap(),
)

private fun java.math.BigInteger.toLongWithinRange(): Long? {
    val min = java.math.BigInteger.valueOf(Long.MIN_VALUE)
    val max = java.math.BigInteger.valueOf(Long.MAX_VALUE)
    return if (this in min..max) toLong() else null
}

private fun java.math.BigDecimal.toLongWithinRange(): Long? {
    val min = java.math.BigDecimal.valueOf(Long.MIN_VALUE)
    val max = java.math.BigDecimal.valueOf(Long.MAX_VALUE)
    return if (scale() == 0 && this >= min && this <= max) {
        toLong()
    } else {
        null
    }
}

private fun java.math.BigInteger.toIntWithinRange(): Int? {
    val min = java.math.BigInteger.valueOf(Int.MIN_VALUE.toLong())
    val max = java.math.BigInteger.valueOf(Int.MAX_VALUE.toLong())
    return if (this in min..max) toInt() else null
}

private fun java.math.BigDecimal.toIntWithinRange(): Int? {
    val min = java.math.BigDecimal.valueOf(Int.MIN_VALUE.toLong())
    val max = java.math.BigDecimal.valueOf(Int.MAX_VALUE.toLong())
    return if (scale() == 0 && this >= min && this <= max) {
        toInt()
    } else {
        null
    }
}

private fun safeLong(value: Any?): Long? {
    if (value == null) return null
    return when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is java.math.BigInteger -> value.toLongWithinRange()
        is java.math.BigDecimal -> value.toLongWithinRange()
        else -> null
    }
}

private fun safeInt(value: Any?): Int? {
    if (value == null) return null
    return when (value) {
        is Int -> value
        is Short -> value.toInt()
        is Byte -> value.toInt()
        is Long -> if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.toInt() else null
        is java.math.BigInteger -> value.toIntWithinRange()
        is java.math.BigDecimal -> value.toIntWithinRange()
        else -> null
    }
}

private fun safeCycleInt(value: Any?): Int? {
    if (value == null) return null
    return when (value) {
        is Boolean -> if (value) 1 else 0
        is Int -> value
        is Short -> value.toInt()
        is Byte -> value.toInt()
        is Long -> if (value in 0L..1L) value.toInt() else value.toInt()
        else -> null
    }
}

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

private fun mapSequenceRow(row: Map<String, Any?>): SequenceRowSnapshot {
    val rawMin = row["min_value"]
    val rawMax = row["max_value"]
    val rawCache = row["cache_size"]
    return SequenceRowSnapshot(
        name = row["name"] as? String,
        nextValue = safeLong(row["next_value"]),
        incrementBy = safeLong(row["increment_by"]),
        minValue = safeLong(rawMin),
        maxValue = safeLong(rawMax),
        cycleEnabledRaw = safeCycleInt(row["cycle_enabled"]),
        cacheSize = safeInt(rawCache),
        managedBy = row["managed_by"] as? String,
        formatVersion = row["format_version"] as? String,
        minValueOverflow = rawMin != null && safeLong(rawMin) == null,
        maxValueOverflow = rawMax != null && safeLong(rawMax) == null,
        cacheSizeOverflow = rawCache != null && safeInt(rawCache) == null,
    )
}

private fun detectAmbiguousKeys(
    rows: List<SequenceRowSnapshot>,
): Pair<Set<String>, Set<String>> {
    fun isDMigrate(row: SequenceRowSnapshot) =
        row.managedBy?.trim() == "d-migrate" &&
            row.formatVersion?.trim() == "mysql-sequence-v1"

    val names = rows
        .filter { isDMigrate(it) && !it.name?.trim().isNullOrEmpty() }
        .map { it.name!!.trim() }
    val ambiguous = names.groupBy { it }.filter { it.value.size > 1 }.keys
    return ambiguous to names.filter { it !in ambiguous }.toSet()
}

private fun scanRoutineStates(
    session: JdbcOperations,
    database: String,
) = linkedMapOf(
    MysqlSequenceNaming.NEXTVAL_ROUTINE to MysqlMetadataQueries.lookupSupportRoutine(
        session,
        database,
        MysqlSequenceNaming.NEXTVAL_ROUTINE,
    ),
    MysqlSequenceNaming.SETVAL_ROUTINE to MysqlMetadataQueries.lookupSupportRoutine(
        session,
        database,
        MysqlSequenceNaming.SETVAL_ROUTINE,
    ),
)

private fun assessSequenceRow(
    row: SequenceRowSnapshot,
    ambiguousKeys: Set<String>,
): SequenceRowAssessment? {
    val managedBy = row.managedBy?.trim() ?: return null
    val formatVersion = row.formatVersion?.trim() ?: return null
    if (managedBy != "d-migrate" || formatVersion != "mysql-sequence-v1") {
        return null
    }

    val name = row.name?.trim()
    if (name.isNullOrEmpty()) {
        return InvalidEvidence("(empty)", "sequence name is empty or null")
    }
    if (name in ambiguousKeys) return null

    return validateSequenceRow(name, row)
}

private fun validateSequenceRow(
    name: String,
    row: SequenceRowSnapshot,
): SequenceRowAssessment {
    val nextValue = row.nextValue
        ?: return InvalidEvidence(name, "next_value is not readable")
    val incrementBy = row.incrementBy
        ?: return InvalidEvidence(name, "increment_by is not readable")
    if (incrementBy == 0L) {
        return InvalidEvidence(name, "increment_by = 0 is invalid")
    }

    val cycleRaw = row.cycleEnabledRaw
    if (cycleRaw == null || (cycleRaw != 0 && cycleRaw != 1)) {
        return InvalidEvidence(
            name,
            "cycle_enabled value '$cycleRaw' is not a canonical boolean (0/1)",
        )
    }
    if (row.minValueOverflow) {
        return InvalidEvidence(name, "min_value exceeds Long range")
    }
    if (row.maxValueOverflow) {
        return InvalidEvidence(name, "max_value exceeds Long range")
    }
    if (row.cacheSizeOverflow) {
        return InvalidEvidence(name, "cache_size exceeds Int range")
    }

    val cacheSize = row.cacheSize
    if (cacheSize != null && cacheSize < 0) {
        return InvalidEvidence(name, "cache_size = $cacheSize is negative")
    }

    return ValidCandidate(
        key = name,
        sequence = SequenceDefinition(
            description = null,
            start = nextValue,
            increment = incrementBy,
            minValue = row.minValue,
            maxValue = row.maxValue,
            cycle = cycleRaw == 1,
            cache = cacheSize,
        ),
    )
}

private fun buildSupportSequenceNotes(
    ambiguousKeys: Set<String>,
    invalids: List<InvalidEvidence>,
    sequenceKeys: Set<String>,
    routineStates: Map<String, SupportRoutineState>,
): List<SchemaReadNote> {
    val allNotes = mutableListOf<SchemaReadNote>()
    allNotes += ambiguousKeys.map { name ->
        SchemaReadNote(
            severity = SchemaReadSeverity.WARNING,
            code = "W116",
            objectName = name,
            message = "Sequence metadata reconstructed, but multiple rows claim key '$name'",
        )
    }
    allNotes += invalids.map { invalid ->
        SchemaReadNote(
            severity = SchemaReadSeverity.WARNING,
            code = "W116",
            objectName = invalid.key,
            message = "Sequence metadata reconstructed, but row is invalid: ${invalid.reason}",
        )
    }

    val routineCauses = mutableListOf<String>()
    for ((name, state) in routineStates) {
        when (state) {
            SupportRoutineState.MISSING ->
                routineCauses += "support routine '$name' is missing"
            SupportRoutineState.NOT_ACCESSIBLE ->
                routineCauses += "support routine '$name' is not accessible"
            SupportRoutineState.NON_CANONICAL ->
                routineCauses += "support routine '$name' is not canonical"
            SupportRoutineState.CONFIRMED -> Unit
        }
    }
    if (sequenceKeys.isNotEmpty() && routineCauses.isNotEmpty()) {
        for (seqKey in sequenceKeys) {
            allNotes += SchemaReadNote(
                severity = SchemaReadSeverity.WARNING,
                code = "W116",
                objectName = seqKey,
                message = "Sequence metadata reconstructed, but required support objects are missing or degraded",
                hint = routineCauses.joinToString("; "),
            )
        }
    }

    return allNotes
}

private fun buildSupportDiagnostics(
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
                    val col = assessment.columnName
                    if (col != null && assessment.tableName.isNotEmpty()) {
                        val colKey = ColumnDiagnosticKey(scope, assessment.tableName, col)
                        addCause(
                            colKey,
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

private fun collectTriggerResolution(
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
    val trigName = assessment.triggerName
    val table = assessment.tableName
    val column = assessment.columnName
    val seqName = assessment.sequenceName

    if (column == null || seqName == null) {
        if (column != null) {
            addColumnCause(
                resolution.columnCauses,
                table,
                column,
                "support trigger '$trigName' confirmed but sequence name not extractable",
            )
        }
        return
    }

    if (seqName !in materializedSequences) {
        addColumnCause(
            resolution.columnCauses,
            table,
            column,
            "support trigger '$trigName' references non-materialized sequence '$seqName'",
        )
        return
    }

    val expectedName = MysqlSequenceNaming.triggerName(table, column)
    if (trigName != expectedName) {
        addColumnCause(
            resolution.columnCauses,
            table,
            column,
            "support trigger name '$trigName' does not match expected '$expectedName'",
        )
        return
    }

    resolution.confirmedTriggers += trigName
    resolution.assignments.getOrPut(table) { mutableMapOf() }[column] = seqName
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

private fun buildTriggerVerificationNotes(
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

    return materializedSequences.keys.map { seqKey ->
        SchemaReadNote(
            severity = SchemaReadSeverity.WARNING,
            code = "W116",
            objectName = seqKey,
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

private fun enrichTablesWithSequenceDefaults(
    tables: Map<String, TableDefinition>,
    resolution: TriggerResolution,
): Map<String, TableDefinition> = tables.mapValues { (tableName, tableDef) ->
    val tableAssignments = resolution.assignments[tableName] ?: return@mapValues tableDef
    val enrichedColumns = tableDef.columns.mapValues { (colName, colDef) ->
        val seqName = tableAssignments[colName] ?: return@mapValues colDef
        val existingDefault = colDef.default
        val compatible = existingDefault == null ||
            (
                existingDefault is DefaultValue.SequenceNextVal &&
                    existingDefault.sequenceName == seqName
                )
        if (!compatible) {
            addColumnCause(
                resolution.columnCauses,
                tableName,
                colName,
                "confirmed trigger but column has conflicting default '$existingDefault'",
            )
            colDef
        } else {
            colDef.copy(default = DefaultValue.SequenceNextVal(seqName))
        }
    }
    tableDef.copy(columns = LinkedHashMap(enrichedColumns))
}

private fun buildColumnCauseNotes(
    columnCauses: Map<Pair<String, String>, List<String>>,
): List<SchemaReadNote> = columnCauses.map { (tableCol, causes) ->
    SchemaReadNote(
        severity = SchemaReadSeverity.WARNING,
        code = "W116",
        objectName = "${tableCol.first}.${tableCol.second}",
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
            .map { (key, diags) ->
                SchemaReadNote(
                    severity = SchemaReadSeverity.WARNING,
                    code = "W116",
                    objectName = when (key) {
                        is SequenceDiagnosticKey -> key.sequenceName
                        is ColumnDiagnosticKey -> "${key.tableName}.${key.columnName}"
                    },
                    message = "Sequence metadata reconstructed, but required support objects are missing or degraded",
                    hint = diags.flatMap { it.causes }.joinToString("; "),
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
                val (_, trigName) = dev.dmigrate.core.identity.ObjectKeyCodec.parseTriggerKey(key)
                trigName !in confirmedTriggerNames
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
        val d3Notes = buildTriggerVerificationNotes(snapshot, materializedSequences)
            .toMutableList()
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
