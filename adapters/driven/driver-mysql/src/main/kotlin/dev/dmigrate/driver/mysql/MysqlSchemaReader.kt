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

            // Phase 3: D2 — materialize sequences from support snapshot
            val d2Result = materializeSupportSequences(supportSnapshot)

            // Phase 4: assemble result — filter support objects, aggregate diagnostics
            val filteredTables = filterSupportTable(tables, supportSnapshot)
            val filteredFunctions = filterSupportRoutines(functions, supportSnapshot)
            val filteredTriggers = filterSupportTriggers(triggers, supportSnapshot)
            notes += d2Result.notes

            val schemaDef = SchemaDefinition(
                name = ReverseScopeCodec.mysqlName(database),
                version = ReverseScopeCodec.REVERSE_VERSION,
                tables = filteredTables,
                views = views,
                functions = filteredFunctions,
                procedures = procedures,
                triggers = filteredTriggers,
                sequences = d2Result.sequences,
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
        // Step 1: two-phase existence check for dmg_sequences (§4.3 Gating)
        val exists = MysqlMetadataQueries.checkSupportTableExists(session, database)

        // Gate A: existence undecidable → no legitimation → non-sequence path
        if (exists == null) {
            return MysqlSequenceSupportSnapshot.nonSequence(scope)
                .copy(supportTableState = SupportTableState.NOT_ACCESSIBLE)
        }
        // Gate B: existence negatively confirmed → non-sequence path
        if (exists == false) {
            return MysqlSequenceSupportSnapshot.nonSequence(scope)
        }

        // --- Existence legitimated: dmg_sequences is confirmed present ---

        // Step 2: verify canonical column shape
        // Shape-check failure AFTER existence legitimation → hard NOT_ACCESSIBLE
        // (this is the "vorhandene, aber nicht lesbare Primärquelle" case from §4.3)
        val shapeOk = try {
            MysqlMetadataQueries.checkSupportTableShape(session, database)
        } catch (_: Exception) {
            return MysqlSequenceSupportSnapshot(
                scope = scope,
                supportTableState = SupportTableState.NOT_ACCESSIBLE,
                sequenceRows = emptyList(),
                ambiguousKeys = emptySet(),
                routineStates = emptyMap(),
                triggerStates = emptyMap(),
                diagnostics = listOf(
                    SupportDiagnostic(
                        key = SequenceDiagnosticKey(scope, MysqlSequenceNaming.SUPPORT_TABLE),
                        causes = listOf("dmg_sequences exists but metadata is not accessible"),
                        emitsW116 = true,
                    )
                ),
            )
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
        // Row-read failure AFTER existence legitimation → hard NOT_ACCESSIBLE with diagnostic
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
                diagnostics = listOf(
                    SupportDiagnostic(
                        key = SequenceDiagnosticKey(scope, MysqlSequenceNaming.SUPPORT_TABLE),
                        causes = listOf("dmg_sequences exists but rows are not readable"),
                        emitsW116 = true,
                    )
                ),
            )
        }

        val sequenceRows = rawRows.map { row ->
            SequenceRowSnapshot(
                name = (row["name"] as? String),
                nextValue = (row["next_value"] as? Number)?.toLong(),
                incrementBy = (row["increment_by"] as? Number)?.toLong(),
                minValue = (row["min_value"] as? Number)?.toLong(),
                maxValue = (row["max_value"] as? Number)?.toLong(),
                cycleEnabledRaw = (row["cycle_enabled"] as? Number)?.toInt(),
                cacheSize = (row["cache_size"] as? Number)?.toInt(),
                managedBy = (row["managed_by"] as? String),
                formatVersion = (row["format_version"] as? String),
            )
        }

        // D2 trim-normalization for name keys
        val trimmedRows = sequenceRows.map { it to it.name?.trim() }

        // Detect ambiguous keys: same trimmed name appears multiple times
        val byName = trimmedRows.filter { it.second != null }.groupBy { it.second!! }
        val ambiguousKeys = byName.filter { it.value.size > 1 }.keys

        // Step 4: targeted routine lookups
        val routineStates = mapOf(
            MysqlSequenceNaming.NEXTVAL_ROUTINE to
                MysqlMetadataQueries.lookupSupportRoutine(session, database, MysqlSequenceNaming.NEXTVAL_ROUTINE),
            MysqlSequenceNaming.SETVAL_ROUTINE to
                MysqlMetadataQueries.lookupSupportRoutine(session, database, MysqlSequenceNaming.SETVAL_ROUTINE),
        )

        // Step 5: targeted trigger lookups
        val triggerScan = MysqlMetadataQueries.listPotentialSupportTriggers(session, database)
        val triggerStates = triggerScan.triggers.associate { it.triggerName to it.state }

        // Step 6: identify d-migrate rows and aggregate diagnostics
        fun isDMigrateRow(row: SequenceRowSnapshot): Boolean =
            row.managedBy?.trim() == "d-migrate" && row.formatVersion?.trim() == "mysql-sequence-v1"

        val dmigrateRowNames = trimmedRows
            .filter { isDMigrateRow(it.first) && it.second != null && it.second!!.isNotEmpty() }
            .map { it.second!! }
            .filter { it !in ambiguousKeys }
            .toSet()

        val diagnostics = buildSupportDiagnostics(
            scope, sequenceRows, ambiguousKeys, routineStates,
            triggerScan.triggers, triggerScanAccessible = triggerScan.accessible,
            confirmedSequenceNames = dmigrateRowNames,
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

    // ── D2: Sequence materialization (0.9.4 AP 6.2) ──

    internal data class D2Result(
        val sequences: Map<String, SequenceDefinition>,
        val notes: List<SchemaReadNote>,
    )

    /**
     * Materializes SequenceDefinitions from the D1 support snapshot.
     * Only produces sequences when supportTableState == AVAILABLE.
     */
    internal fun materializeSupportSequences(
        snapshot: MysqlSequenceSupportSnapshot,
    ): D2Result {
        if (snapshot.supportTableState != SupportTableState.AVAILABLE) {
            return D2Result(emptyMap(), aggregateSupportNotes(snapshot))
        }

        val sequences = LinkedHashMap<String, SequenceDefinition>()
        val d2Notes = mutableListOf<SchemaReadNote>()

        // Phase 1: filter d-migrate rows, validate, detect conflicts
        data class ValidCandidate(val key: String, val row: SequenceRowSnapshot)
        data class InvalidEvidence(val key: String, val reason: String)

        val candidates = mutableListOf<ValidCandidate>()
        val invalids = mutableListOf<InvalidEvidence>()

        for (row in snapshot.sequenceRows) {
            val managedBy = row.managedBy?.trim() ?: continue  // foreign row → skip silently
            val formatVersion = row.formatVersion?.trim() ?: continue
            if (managedBy != "d-migrate" || formatVersion != "mysql-sequence-v1") continue  // foreign → skip, no W116

            val name = row.name?.trim()
            if (name.isNullOrEmpty()) {
                invalids += InvalidEvidence("(empty)", "sequence name is empty or null")
                continue
            }
            if (name in snapshot.ambiguousKeys) continue  // handled separately

            // Validate required numerics — no silent fallbacks
            val nextValue = row.nextValue
            if (nextValue == null) {
                invalids += InvalidEvidence(name, "next_value is not readable")
                continue
            }
            val incrementBy = row.incrementBy
            if (incrementBy == null) {
                invalids += InvalidEvidence(name, "increment_by is not readable")
                continue
            }
            if (incrementBy == 0L) {
                invalids += InvalidEvidence(name, "increment_by = 0 is invalid")
                continue
            }

            // Validate cycle_enabled — only 0/1 accepted
            val cycleRaw = row.cycleEnabledRaw
            if (cycleRaw == null || (cycleRaw != 0 && cycleRaw != 1)) {
                invalids += InvalidEvidence(name, "cycle_enabled value '$cycleRaw' is not a canonical boolean (0/1)")
                continue
            }

            // Validate cache_size — null ok, negative invalid, overflow invalid
            val cacheSize = row.cacheSize
            if (cacheSize != null && cacheSize < 0) {
                invalids += InvalidEvidence(name, "cache_size = $cacheSize is negative")
                continue
            }

            candidates += ValidCandidate(name, row)
        }

        // Phase 2: materialize valid candidates (sorted by key for determinism)
        for (candidate in candidates.sortedBy { it.key }) {
            val row = candidate.row
            sequences[candidate.key] = SequenceDefinition(
                description = null,
                start = row.nextValue!!,
                increment = row.incrementBy!!,
                minValue = row.minValue,
                maxValue = row.maxValue,
                cycle = row.cycleEnabledRaw == 1,
                cache = row.cacheSize,
            )
        }

        // Phase 3: aggregate notes
        val allNotes = mutableListOf<SchemaReadNote>()

        // Ambiguous keys → W116 per key
        for (name in snapshot.ambiguousKeys) {
            allNotes += SchemaReadNote(
                severity = SchemaReadSeverity.WARNING,
                code = "W116",
                objectName = name,
                message = "Sequence metadata reconstructed, but multiple rows claim key '$name'",
            )
        }

        // Invalid d-migrate rows → W116 per key
        for (invalid in invalids) {
            allNotes += SchemaReadNote(
                severity = SchemaReadSeverity.WARNING,
                code = "W116",
                objectName = invalid.key,
                message = "Sequence metadata reconstructed, but row is invalid: ${invalid.reason}",
            )
        }

        // Routine diagnostics — only when sequences were materialized
        if (sequences.isNotEmpty()) {
            val routineCauses = mutableListOf<String>()
            for ((name, state) in snapshot.routineStates) {
                when (state) {
                    SupportRoutineState.MISSING -> routineCauses += "support routine '$name' is missing"
                    SupportRoutineState.NOT_ACCESSIBLE -> routineCauses += "support routine '$name' is not accessible"
                    SupportRoutineState.NON_CANONICAL -> routineCauses += "support routine '$name' is not canonical"
                    SupportRoutineState.CONFIRMED -> { /* no cause */ }
                }
            }
            if (routineCauses.isNotEmpty()) {
                // Emit one W116 per materialized sequence, not per routine
                for (seqKey in sequences.keys) {
                    allNotes += SchemaReadNote(
                        severity = SchemaReadSeverity.WARNING,
                        code = "W116",
                        objectName = seqKey,
                        message = "Sequence metadata reconstructed, but required support objects are missing or degraded",
                        hint = routineCauses.joinToString("; "),
                    )
                }
            }
        }

        return D2Result(sequences, allNotes)
    }

    /**
     * Produces notes from the D1 snapshot for non-AVAILABLE states
     * (INVALID_SHAPE, NOT_ACCESSIBLE after legitimation).
     */
    private fun aggregateSupportNotes(
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

    private fun buildSupportDiagnostics(
        scope: ReverseScope,
        rows: List<SequenceRowSnapshot>,
        ambiguousKeys: Set<String>,
        routineStates: Map<String, SupportRoutineState>,
        triggerAssessments: List<MysqlMetadataQueries.SupportTriggerAssessment>,
        triggerScanAccessible: Boolean,
        confirmedSequenceNames: Set<String>,
    ): List<SupportDiagnostic> {
        // Collect causes per key, then deduplicate
        val causesByKey = LinkedHashMap<DiagnosticKey, MutableList<String>>()
        fun addCause(key: DiagnosticKey, cause: String) {
            causesByKey.getOrPut(key) { mutableListOf() } += cause
        }

        // Sequence-level: ambiguous keys (among d-migrate rows only)
        for (name in ambiguousKeys) {
            addCause(SequenceDiagnosticKey(scope, name), "multiple rows for sequence key '$name'")
        }

        // Note: foreign marker rows (managed_by != d-migrate) are silently
        // ignored — they are not d-migrate support objects and produce no W116.
        // Only d-migrate rows with fachlich invalid content emit W116.
        // (This is handled in D2 materializeSupportSequences, not here.)

        // Routine-level: only emit when confirmed sequences exist (fachliche Legitimierung)
        if (confirmedSequenceNames.isNotEmpty()) {
            for ((name, state) in routineStates) {
                val cause = when (state) {
                    SupportRoutineState.NOT_ACCESSIBLE -> "support routine '$name' is not accessible"
                    SupportRoutineState.NON_CANONICAL -> "support routine '$name' is not canonical"
                    SupportRoutineState.MISSING -> "support routine '$name' is missing"
                    SupportRoutineState.CONFIRMED -> null
                }
                if (cause != null) {
                    // Bind to each confirmed sequence key (not per routine name)
                    for (seq in confirmedSequenceNames) {
                        addCause(SequenceDiagnosticKey(scope, seq), cause)
                    }
                }
            }
        }

        // Trigger-scan-level: entire scan inaccessible — emit per confirmed sequence
        if (!triggerScanAccessible && confirmedSequenceNames.isNotEmpty()) {
            for (seq in confirmedSequenceNames) {
                addCause(SequenceDiagnosticKey(scope, seq),
                    "support trigger metadata is not accessible; column-level verification not possible")
            }
        }

        // Trigger-level: degraded states — only when confirmed sequences exist
        // (fachliche Verankerung am bestätigten Sequence-Kontext)
        if (confirmedSequenceNames.isNotEmpty()) {
            for (assessment in triggerAssessments) {
                when (assessment.state) {
                    SupportTriggerState.MISSING_MARKER,
                    SupportTriggerState.NON_CANONICAL,
                    SupportTriggerState.NOT_ACCESSIBLE -> {
                        val colKey = ColumnDiagnosticKey(
                            scope, assessment.tableName, assessment.columnName ?: assessment.triggerName,
                        )
                        addCause(colKey, "support trigger '${assessment.triggerName}' has state ${assessment.state}")
                    }
                    SupportTriggerState.CONFIRMED,
                    SupportTriggerState.USER_OBJECT -> { /* no diagnostic */ }
                }
            }
        }

        // Build deduplicated diagnostics: one per key
        return causesByKey.map { (key, causes) ->
            SupportDiagnostic(key = key, causes = causes, emitsW116 = true)
        }
    }

    // ── Support object filtering ───────────────────

    private fun filterSupportTable(
        tables: Map<String, TableDefinition>,
        snapshot: MysqlSequenceSupportSnapshot,
    ): Map<String, TableDefinition> {
        // D2 §4.6: suppress dmg_sequences ONLY when the canonical support
        // form is confirmed (AVAILABLE). All other states — INVALID_SHAPE,
        // NOT_ACCESSIBLE, MISSING — leave the table as a normal user object.
        if (snapshot.supportTableState != SupportTableState.AVAILABLE) return tables
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
            // Trigger keys use ObjectKeyCodec format: "table::name"
            val (_, trigName) = ObjectKeyCodec.parseTriggerKey(key)
            trigName !in confirmed
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
