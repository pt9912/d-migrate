package dev.dmigrate.driver.mysql

import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.core.identity.ReverseScopeCodec
import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.SchemaReaderUtils

/**
 * MySQL [SchemaReader] implementation.
 *
 * Uses `information_schema` with `lower_case_table_names`-aware
 * identifier normalization.
 */
class MysqlSchemaReader : SchemaReader {

    override fun read(pool: ConnectionPool, options: SchemaReadOptions): SchemaReadResult {
        val notes = mutableListOf<SchemaReadNote>()
        val skipped = mutableListOf<SkippedObject>()

        pool.borrow().use { conn ->
            val session = JdbcMetadataSession(conn)
            val database = currentDatabase(conn)
            val lctn = lowerCaseTableNames(conn)

            val metaDb = normalizeMysqlMetadataIdentifier(database, lctn)

            val tables = readTables(session, metaDb, lctn, notes)
            val views = if (options.includeViews) readViews(session, metaDb) else emptyMap()
            val functions = if (options.includeFunctions) readFunctions(session, metaDb, notes) else emptyMap()
            val procedures = if (options.includeProcedures) readProcedures(session, metaDb, notes) else emptyMap()
            val triggers = if (options.includeTriggers) readTriggers(session, metaDb) else emptyMap()

            val schemaDef = SchemaDefinition(
                name = ReverseScopeCodec.mysqlName(database),
                version = ReverseScopeCodec.REVERSE_VERSION,
                tables = tables,
                views = views,
                functions = functions,
                procedures = procedures,
                triggers = triggers,
            )

            return SchemaReadResult(schema = schemaDef, notes = notes, skippedObjects = skipped)
        }
    }

    // ── Tables ──────────────────────────────────

    private fun readTables(
        session: JdbcMetadataSession,
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
        session: JdbcMetadataSession,
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

    private fun readViews(session: JdbcMetadataSession, database: String): Map<String, ViewDefinition> {
        val rows = MysqlMetadataQueries.listViews(session, database)
        val result = LinkedHashMap<String, ViewDefinition>()
        for (row in rows) {
            result[row["table_name"] as String] = ViewDefinition(
                query = row["view_definition"] as? String,
                sourceDialect = "mysql",
            )
        }
        return result
    }

    // ── Functions ───────────────────────────────

    private fun readFunctions(
        session: JdbcMetadataSession,
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
        session: JdbcMetadataSession,
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

    private fun readTriggers(session: JdbcMetadataSession, database: String): Map<String, TriggerDefinition> {
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
