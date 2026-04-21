package dev.dmigrate.driver.mysql

import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.core.identity.ReverseScopeCodec
import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.JdbcOperations
import dev.dmigrate.driver.metadata.SchemaReaderUtils
import java.sql.Connection

/**
 * MySQL [SchemaReader] implementation.
 *
 * Uses `information_schema` with `lower_case_table_names`-aware
 * identifier normalization.
 */
class MysqlSchemaReader(
    private val jdbcFactory: (Connection) -> JdbcOperations = ::JdbcMetadataSession,
) : SchemaReader {

    override fun read(pool: ConnectionPool, options: SchemaReadOptions): SchemaReadResult {
        val notes = mutableListOf<SchemaReadNote>()
        val skipped = mutableListOf<SkippedObject>()

        pool.borrow().use { conn ->
            val session = jdbcFactory(conn)
            val database = currentDatabase(conn)
            val lctn = lowerCaseTableNames(conn)

            val metaDb = normalizeMysqlMetadataIdentifier(database, lctn)
            val scope = ReverseScope(catalogName = metaDb, schemaName = metaDb)

            // Phase 1: read base objects (existing paths)
            val tables = readTables(session, metaDb, lctn, notes)
            val views = if (options.includeViews) readViews(session, metaDb) else emptyMap()
            val functions = if (options.includeFunctions) readFunctions(session, metaDb, notes) else emptyMap()
            val procedures = if (options.includeProcedures) readProcedures(session, metaDb, notes) else emptyMap()
            val triggers = if (options.includeTriggers) readTriggers(session, metaDb) else emptyMap()

            // Phase 2: internal sequence support scan
            val supportSnapshot = scanSequenceSupport(session, metaDb, scope)

            // Phase 3: assemble result — filter support objects, aggregate diagnostics
            val filteredTables = filterSupportTable(tables, supportSnapshot)
            val filteredFunctions = filterSupportRoutines(functions, supportSnapshot)
            val filteredTriggers = filterSupportTriggers(triggers, supportSnapshot)
            notes += aggregateSupportDiagnostics(supportSnapshot)

            val schemaDef = SchemaDefinition(
                name = ReverseScopeCodec.mysqlName(database),
                version = ReverseScopeCodec.REVERSE_VERSION,
                tables = filteredTables,
                views = views,
                functions = filteredFunctions,
                procedures = procedures,
                triggers = filteredTriggers,
            )

            return SchemaReadResult(schema = schemaDef, notes = notes, skippedObjects = skipped)
        }
    }

    // ── Sequence support scan (0.9.4 AP 6.1) ──────

    internal fun scanSequenceSupport(
        session: JdbcOperations,
        database: String,
        scope: ReverseScope,
    ): MysqlSequenceSupportSnapshot {
        // Step 1: two-phase existence check for dmg_sequences
        val exists = MysqlMetadataQueries.checkSupportTableExists(session, database)
        if (exists == null) {
            return MysqlSequenceSupportSnapshot.nonSequence(scope)
                .copy(supportTableState = SupportTableState.NOT_ACCESSIBLE)
        }
        if (exists == false) {
            return MysqlSequenceSupportSnapshot.nonSequence(scope)
        }

        // Step 2: verify canonical column shape
        val shapeOk = try {
            MysqlMetadataQueries.checkSupportTableShape(session, database)
        } catch (_: Exception) {
            return MysqlSequenceSupportSnapshot.nonSequence(scope)
                .copy(supportTableState = SupportTableState.NOT_ACCESSIBLE)
        }
        if (!shapeOk) {
            return MysqlSequenceSupportSnapshot(
                scope = scope,
                supportTableState = SupportTableState.INVALID_SHAPE,
                sequenceRows = emptyList(),
                ambiguousKeys = emptySet(),
                routineStates = emptyMap(),
                triggerStates = emptyMap(),
                diagnostics = listOf(
                    SupportDiagnostic(
                        key = SequenceDiagnosticKey(scope, MysqlSequenceNaming.SUPPORT_TABLE),
                        causes = listOf("dmg_sequences column shape is not canonical"),
                        emitsW116 = true,
                    )
                ),
            )
        }

        // Step 3: read rows, validate markers, detect conflicts
        val rawRows = try {
            MysqlMetadataQueries.listSupportSequenceRows(session, database)
        } catch (_: Exception) {
            return MysqlSequenceSupportSnapshot(
                scope = scope,
                supportTableState = SupportTableState.NOT_ACCESSIBLE,
                sequenceRows = emptyList(),
                ambiguousKeys = emptySet(),
                routineStates = emptyMap(),
                triggerStates = emptyMap(),
                diagnostics = emptyList(),
            )
        }

        val sequenceRows = rawRows.map { row ->
            val managedBy = row["managed_by"] as? String ?: ""
            val formatVersion = row["format_version"] as? String ?: ""
            val valid = managedBy == "d-migrate" && formatVersion == "mysql-sequence-v1"
            SequenceRowSnapshot(
                name = row["name"] as? String ?: "",
                nextValue = (row["next_value"] as? Number)?.toLong() ?: 0L,
                incrementBy = (row["increment_by"] as? Number)?.toLong() ?: 1L,
                minValue = (row["min_value"] as? Number)?.toLong(),
                maxValue = (row["max_value"] as? Number)?.toLong(),
                cycleEnabled = (row["cycle_enabled"] as? Number)?.toInt() == 1,
                cacheSize = (row["cache_size"] as? Number)?.toInt(),
                managedBy = managedBy,
                formatVersion = formatVersion,
                valid = valid,
                conflictReason = if (!valid) "invalid markers: managed_by=$managedBy, format_version=$formatVersion" else null,
            )
        }

        // Detect ambiguous keys: same name appears multiple times, or mixed validity
        val byName = sequenceRows.groupBy { it.name }
        val ambiguousKeys = mutableSetOf<String>()
        for ((name, rows) in byName) {
            if (rows.size > 1) {
                ambiguousKeys += name
            }
        }

        // Step 4: targeted routine lookups
        val routineStates = mapOf(
            MysqlSequenceNaming.NEXTVAL_ROUTINE to
                MysqlMetadataQueries.lookupSupportRoutine(session, database, MysqlSequenceNaming.NEXTVAL_ROUTINE),
            MysqlSequenceNaming.SETVAL_ROUTINE to
                MysqlMetadataQueries.lookupSupportRoutine(session, database, MysqlSequenceNaming.SETVAL_ROUTINE),
        )

        // Step 5: targeted trigger lookups
        val triggerScan = MysqlMetadataQueries.listPotentialSupportTriggers(session, database)
        val triggerStates = triggerScan.triggers.toMap()

        // Step 6: aggregate diagnostics
        val diagnostics = buildSupportDiagnostics(
            scope, sequenceRows, ambiguousKeys, routineStates, triggerStates,
            triggerScanAccessible = triggerScan.accessible,
        )

        return MysqlSequenceSupportSnapshot(
            scope = scope,
            supportTableState = SupportTableState.AVAILABLE,
            sequenceRows = sequenceRows,
            ambiguousKeys = ambiguousKeys,
            routineStates = routineStates,
            triggerStates = triggerStates,
            diagnostics = diagnostics,
        )
    }

    private fun buildSupportDiagnostics(
        scope: ReverseScope,
        rows: List<SequenceRowSnapshot>,
        ambiguousKeys: Set<String>,
        routineStates: Map<String, SupportRoutineState>,
        triggerStates: Map<String, SupportTriggerState>,
        triggerScanAccessible: Boolean,
    ): List<SupportDiagnostic> {
        val diags = mutableListOf<SupportDiagnostic>()

        // Sequence-level: ambiguous keys
        for (name in ambiguousKeys) {
            diags += SupportDiagnostic(
                key = SequenceDiagnosticKey(scope, name),
                causes = listOf("multiple rows for sequence key '$name'"),
                emitsW116 = true,
            )
        }

        // Sequence-level: invalid marker rows (non-ambiguous)
        for (row in rows) {
            if (row.name !in ambiguousKeys && !row.valid) {
                diags += SupportDiagnostic(
                    key = SequenceDiagnosticKey(scope, row.name),
                    causes = listOf(row.conflictReason ?: "invalid markers"),
                    emitsW116 = true,
                )
            }
        }

        // Routine-level: degraded states
        for ((name, state) in routineStates) {
            when (state) {
                SupportRoutineState.NOT_ACCESSIBLE -> diags += SupportDiagnostic(
                    key = SequenceDiagnosticKey(scope, name),
                    causes = listOf("support routine '$name' is not accessible"),
                    emitsW116 = true,
                )
                SupportRoutineState.NON_CANONICAL -> diags += SupportDiagnostic(
                    key = SequenceDiagnosticKey(scope, name),
                    causes = listOf("support routine '$name' has marker but signature does not match canonical form"),
                    emitsW116 = true,
                )
                SupportRoutineState.CONFIRMED,
                SupportRoutineState.MISSING -> { /* no diagnostic */ }
            }
        }

        // Trigger-scan-level: entire scan inaccessible — emit per confirmed sequence
        if (!triggerScanAccessible) {
            val confirmedSeqs = rows.filter { it.valid && it.name !in ambiguousKeys }
            if (confirmedSeqs.isNotEmpty()) {
                for (seq in confirmedSeqs) {
                    diags += SupportDiagnostic(
                        key = SequenceDiagnosticKey(scope, seq.name),
                        causes = listOf("support trigger metadata is not accessible; column-level verification for sequence '${seq.name}' not possible"),
                        emitsW116 = true,
                    )
                }
            } else {
                diags += SupportDiagnostic(
                    key = SequenceDiagnosticKey(scope, MysqlSequenceNaming.SUPPORT_TABLE),
                    causes = listOf("support trigger metadata is not accessible"),
                    emitsW116 = true,
                )
            }
        }

        // Trigger-level: degraded states — keyed by trigger hash
        // (actual table/column resolution deferred to D3)
        for ((name, state) in triggerStates) {
            val hash = extractTriggerHash(name)
            when (state) {
                SupportTriggerState.MISSING_MARKER,
                SupportTriggerState.NON_CANONICAL,
                SupportTriggerState.NOT_ACCESSIBLE -> {
                    diags += SupportDiagnostic(
                        key = SequenceDiagnosticKey(scope, "trigger:$hash"),
                        causes = listOf("support trigger '$name' has state $state"),
                        emitsW116 = true,
                    )
                }
                SupportTriggerState.CONFIRMED,
                SupportTriggerState.USER_OBJECT -> { /* no diagnostic */ }
            }
        }

        return diags
    }

    /**
     * Extracts the hash10 segment from a canonical support trigger name.
     * Format: `dmg_seq_<table16>_<column16>_<hash10>_bi`
     *
     * Since table16/column16 can themselves contain underscores,
     * string-splitting is unreliable. Instead we extract the hash
     * (always 10 hex chars before `_bi`) and use it as the diagnostic key.
     * The actual table/column resolution happens in D3 by matching the
     * hash against known sequence-column combinations.
     */
    private fun extractTriggerHash(triggerName: String): String {
        // Remove suffix "_bi" → "dmg_seq_<table16>_<column16>_<hash10>"
        val withoutSuffix = triggerName.removeSuffix("_bi")
        // Hash is the last 10 characters
        return if (withoutSuffix.length >= 10) withoutSuffix.takeLast(10) else triggerName
    }

    // ── Support object filtering ───────────────────

    private fun filterSupportTable(
        tables: Map<String, TableDefinition>,
        snapshot: MysqlSequenceSupportSnapshot,
    ): Map<String, TableDefinition> {
        if (snapshot.supportTableState == SupportTableState.MISSING) return tables
        // Filter dmg_sequences for AVAILABLE, INVALID_SHAPE, and NOT_ACCESSIBLE
        // (when existence was legitimated before the access failure)
        return tables.filterKeys { it != MysqlSequenceNaming.SUPPORT_TABLE }
    }

    private fun filterSupportRoutines(
        functions: Map<String, FunctionDefinition>,
        snapshot: MysqlSequenceSupportSnapshot,
    ): Map<String, FunctionDefinition> {
        val confirmed = snapshot.routineStates
            .filter { it.value == SupportRoutineState.CONFIRMED }
            .keys
        if (confirmed.isEmpty()) return functions
        // Routine map keys use ObjectKeyCodec format: "name(params)"
        // Match on the raw name prefix before the '(' delimiter
        return functions.filterKeys { key ->
            val rawName = key.substringBefore('(')
            rawName !in confirmed
        }
    }

    private fun filterSupportTriggers(
        triggers: Map<String, TriggerDefinition>,
        snapshot: MysqlSequenceSupportSnapshot,
    ): Map<String, TriggerDefinition> {
        val confirmed = snapshot.triggerStates
            .filter { it.value == SupportTriggerState.CONFIRMED }
            .keys
        if (confirmed.isEmpty()) return triggers
        return triggers.filterKeys { key ->
            // trigger keys use ObjectKeyCodec format — extract trigger name
            confirmed.none { trigName -> key.endsWith(trigName) || key == trigName }
        }
    }

    private fun aggregateSupportDiagnostics(
        snapshot: MysqlSequenceSupportSnapshot,
    ): List<SchemaReadNote> {
        return snapshot.diagnostics
            .filter { it.emitsW116 }
            .map { diag ->
                SchemaReadNote(
                    severity = SchemaReadSeverity.WARNING,
                    code = "W116",
                    objectName = when (val k = diag.key) {
                        is SequenceDiagnosticKey -> k.sequenceName
                        is ColumnDiagnosticKey -> "${k.tableName}.${k.columnName}"
                        else -> "unknown"
                    },
                    message = "Sequence metadata reconstructed, but required support objects are missing or degraded",
                    hint = diag.causes.joinToString("; "),
                )
            }
    }

    // ── Tables ──────────────────────────────────

    private fun readTables(
        session: JdbcOperations,
        database: String,
        lctn: Int,
        notes: MutableList<SchemaReadNote>,
    ): Map<String, TableDefinition> {
        val tableRefs = MysqlMetadataQueries.listTableRefs(session, database)
        val result = LinkedHashMap<String, TableDefinition>()
        for (ref in tableRefs) {
            val metaTable = normalizeMysqlMetadataIdentifier(ref.name, lctn)
            result[ref.name] = readTable(session, database, metaTable, ref.name, notes)
        }
        return result
    }

    private fun readTable(
        session: JdbcOperations,
        database: String,
        metaTable: String,
        displayName: String,
        notes: MutableList<SchemaReadNote>,
    ): TableDefinition {
        val colRows = MysqlMetadataQueries.listColumns(session, database, metaTable)
        val pkColumns = MysqlMetadataQueries.listPrimaryKeyColumns(session, database, metaTable)
        val fks = MysqlMetadataQueries.listForeignKeys(session, database, metaTable)
        val allIndices = MysqlMetadataQueries.listIndices(session, database, metaTable)
        val checks = MysqlMetadataQueries.listCheckConstraints(session, database, metaTable)
        val engine = MysqlMetadataQueries.listTableEngine(session, database, metaTable)

        // MySQL auto-creates a support index for each FK. We suppress indices
        // whose name matches a FK constraint name (MySQL's default naming).
        // When the name does NOT match but columns overlap, we keep the index
        // as a regular entry and add a note (false-positive before silent loss).
        val fkConstraintNames = fks.map { it.name }.toSet()
        val fkColumnLists = fks.map { it.columns }
        val indices = mutableListOf<dev.dmigrate.driver.metadata.IndexProjection>()
        for (idx in allIndices) {
            if (idx.name in fkConstraintNames) {
                // Safely identified as FK backing index — suppress
                continue
            }
            if (fkColumnLists.any { it == idx.columns }) {
                // Columns match an FK but name differs — keep with note
                notes += SchemaReadNote(
                    severity = SchemaReadSeverity.INFO,
                    code = "R330",
                    objectName = "$displayName.${idx.name}",
                    message = "Index columns match FK — may be an auto-generated support index",
                    hint = "Review if this index was explicitly created or is FK-backing",
                )
            }
            indices += idx
        }

        val singleColFks = SchemaReaderUtils.liftSingleColumnFks(fks)
        val singleColUnique = SchemaReaderUtils.singleColumnUniqueFromIndices(indices)

        val columns = LinkedHashMap<String, ColumnDefinition>()
        for (row in colRows) {
            val colName = row["column_name"] as String
            val isPkCol = colName in pkColumns
            val extra = (row["extra"] as? String) ?: ""
            val isAutoIncrement = extra.contains("auto_increment", ignoreCase = true)
            val mapping = MysqlTypeMapping.mapColumn(
                dataType = row["data_type"] as String,
                columnType = (row["column_type"] as? String) ?: (row["data_type"] as String),
                isAutoIncrement = isAutoIncrement,
                charMaxLen = (row["character_maximum_length"] as? Number)?.toInt(),
                numPrecision = (row["numeric_precision"] as? Number)?.toInt(),
                numScale = (row["numeric_scale"] as? Number)?.toInt(),
                tableName = displayName,
                colName = colName,
            )
            if (mapping.note != null) notes += mapping.note
            val neutralType = mapping.type

            val required = if (isPkCol) false else (row["is_nullable"] as String) == "NO"
            val unique = if (isPkCol) false else colName in singleColUnique

            val references = singleColFks[colName]

            val defaultVal = if (isAutoIncrement) null
            else MysqlTypeMapping.parseDefault(row["column_default"] as? String, neutralType)

            columns[colName] = ColumnDefinition(
                type = neutralType,
                required = required,
                unique = unique,
                default = defaultVal,
                references = references,
            )
        }

        // Multi-column constraints
        val constraints = mutableListOf<ConstraintDefinition>()
        constraints += SchemaReaderUtils.buildMultiColumnFkConstraints(fks)
        constraints += SchemaReaderUtils.buildMultiColumnUniqueFromIndices(indices)
        constraints += SchemaReaderUtils.buildCheckConstraints(checks)

        // Non-unique indices + single-col unique (not in constraints)
        val indexDefs = indices.filter { !it.isUnique || it.columns.size == 1 }
            .filter { !(it.isUnique && it.columns.size == 1) } // single-col unique on ColumnDefinition
            .map { idx ->
                IndexDefinition(
                    name = idx.name,
                    columns = idx.columns,
                    type = when (idx.type?.uppercase()) {
                        "HASH" -> IndexType.HASH
                        else -> IndexType.BTREE
                    },
                    unique = idx.isUnique,
                )
            }

        val metadata = engine?.let { TableMetadata(engine = it) }

        return TableDefinition(
            columns = columns,
            primaryKey = pkColumns,
            indices = indexDefs,
            constraints = constraints,
            metadata = metadata,
        )
    }

    // ── Views ───────────────────────────────────

    private fun readViews(session: JdbcOperations, database: String): Map<String, ViewDefinition> {
        val rows = MysqlMetadataQueries.listViews(session, database)
        val viewFuncDeps = MysqlMetadataQueries.listViewRoutineUsage(session, database)
        val result = LinkedHashMap<String, ViewDefinition>()
        for (row in rows) {
            val viewName = row["table_name"] as String
            val funcDeps = viewFuncDeps[viewName] ?: emptyList()
            result[viewName] = ViewDefinition(
                query = row["view_definition"] as? String,
                dependencies = if (funcDeps.isNotEmpty()) DependencyInfo(functions = funcDeps) else null,
                sourceDialect = "mysql",
            )
        }
        return result
    }

    // ── Functions ───────────────────────────────

    private fun readFunctions(
        session: JdbcOperations,
        database: String,
        notes: MutableList<SchemaReadNote>,
    ): Map<String, FunctionDefinition> {
        val rows = MysqlMetadataQueries.listFunctions(session, database)
        val result = LinkedHashMap<String, FunctionDefinition>()
        for (row in rows) {
            val name = row["routine_name"] as String
            val params = MysqlMetadataQueries.listRoutineParameters(session, database, name, "FUNCTION")
            val paramDefs = params.map { p ->
                ParameterDefinition(
                    name = (p["parameter_name"] as? String) ?: "p${p["ordinal_position"]}",
                    type = MysqlTypeMapping.mapParamType(p["data_type"] as? String ?: "text"),
                    direction = when ((p["parameter_mode"] as? String)?.uppercase()) {
                        "OUT" -> ParameterDirection.OUT
                        "INOUT" -> ParameterDirection.INOUT
                        else -> ParameterDirection.IN
                    },
                )
            }
            val key = ObjectKeyCodec.routineKey(name, paramDefs)
            result[key] = FunctionDefinition(
                parameters = paramDefs,
                returns = (row["dtd_identifier"] as? String)?.let { ReturnType(type = MysqlTypeMapping.mapParamType(it)) },
                language = row["routine_body"] as? String,
                body = row["routine_definition"] as? String,
                deterministic = (row["is_deterministic"] as? String) == "YES",
                sourceDialect = "mysql",
            )
        }
        return result
    }

    // ── Procedures ──────────────────────────────

    private fun readProcedures(
        session: JdbcOperations,
        database: String,
        notes: MutableList<SchemaReadNote>,
    ): Map<String, ProcedureDefinition> {
        val rows = MysqlMetadataQueries.listProcedures(session, database)
        val result = LinkedHashMap<String, ProcedureDefinition>()
        for (row in rows) {
            val name = row["routine_name"] as String
            val params = MysqlMetadataQueries.listRoutineParameters(session, database, name, "PROCEDURE")
            val paramDefs = params.map { p ->
                ParameterDefinition(
                    name = (p["parameter_name"] as? String) ?: "p${p["ordinal_position"]}",
                    type = MysqlTypeMapping.mapParamType(p["data_type"] as? String ?: "text"),
                    direction = when ((p["parameter_mode"] as? String)?.uppercase()) {
                        "OUT" -> ParameterDirection.OUT
                        "INOUT" -> ParameterDirection.INOUT
                        else -> ParameterDirection.IN
                    },
                )
            }
            val key = ObjectKeyCodec.routineKey(name, paramDefs)
            result[key] = ProcedureDefinition(
                parameters = paramDefs,
                language = row["routine_body"] as? String,
                body = row["routine_definition"] as? String,
                sourceDialect = "mysql",
            )
        }
        return result
    }

    // ── Triggers ────────────────────────────────

    private fun readTriggers(session: JdbcOperations, database: String): Map<String, TriggerDefinition> {
        val rows = MysqlMetadataQueries.listTriggers(session, database)
        val result = LinkedHashMap<String, TriggerDefinition>()
        for (row in rows) {
            val name = row["trigger_name"] as String
            val table = row["event_object_table"] as String
            val key = ObjectKeyCodec.triggerKey(table, name)
            result[key] = TriggerDefinition(
                table = table,
                event = when ((row["event_manipulation"] as String).uppercase()) {
                    "INSERT" -> TriggerEvent.INSERT
                    "UPDATE" -> TriggerEvent.UPDATE
                    "DELETE" -> TriggerEvent.DELETE
                    else -> TriggerEvent.INSERT
                },
                timing = when ((row["action_timing"] as String).uppercase()) {
                    "BEFORE" -> TriggerTiming.BEFORE
                    "AFTER" -> TriggerTiming.AFTER
                    else -> TriggerTiming.BEFORE
                },
                forEach = when ((row["action_orientation"] as? String)?.uppercase()) {
                    "STATEMENT" -> TriggerForEach.STATEMENT
                    else -> TriggerForEach.ROW
                },
                body = row["action_statement"] as? String,
                sourceDialect = "mysql",
            )
        }
        return result
    }

}
