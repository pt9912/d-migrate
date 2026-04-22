package dev.dmigrate.driver.postgresql

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
 * PostgreSQL [SchemaReader] implementation.
 *
 * Uses `information_schema` for portable base data and `pg_catalog`
 * for PostgreSQL-specific metadata (sequences, enum types, backing
 * index detection).
 */
class PostgresSchemaReader(
    private val jdbcFactory: (Connection) -> JdbcOperations = ::JdbcMetadataSession,
) : SchemaReader {

    override fun read(pool: ConnectionPool, options: SchemaReadOptions): SchemaReadResult {
        val notes = mutableListOf<SchemaReadNote>()
        val skipped = mutableListOf<SkippedObject>()

        pool.borrow().use { conn ->
            val session = jdbcFactory(conn)
            val schema = currentSchema(conn)
            val database = conn.catalog ?: "unknown"

            val tables = readTables(session, schema, notes)
            val sequences = readSequences(session, schema)
            val customTypes = readCustomTypes(session, schema, notes)

            // Extension notes (Phase B contract: extensions as notes, not model objects)
            val extensions = PostgresMetadataQueries.listInstalledExtensions(session)
            for (ext in extensions) {
                notes += SchemaReadNote(
                    severity = SchemaReadSeverity.INFO,
                    code = "R400",
                    objectName = ext,
                    message = "PostgreSQL extension '$ext' is installed",
                    hint = "Extension-dependent objects may require this extension in the target database",
                )
            }
            val views = if (options.includeViews) readViews(session, schema) else emptyMap()
            val functions = if (options.includeFunctions) readFunctions(session, schema, notes) else emptyMap()
            val procedures = if (options.includeProcedures) readProcedures(session, schema, notes) else emptyMap()
            val triggers = if (options.includeTriggers) readTriggers(session, schema) else emptyMap()

            val schemaDef = SchemaDefinition(
                name = ReverseScopeCodec.postgresName(database, schema),
                version = ReverseScopeCodec.REVERSE_VERSION,
                tables = tables,
                sequences = sequences,
                customTypes = customTypes,
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
        session: JdbcOperations,
        schema: String,
        notes: MutableList<SchemaReadNote>,
    ): Map<String, TableDefinition> {
        val tableRefs = PostgresMetadataQueries.listTableRefs(session, schema)
        val result = LinkedHashMap<String, TableDefinition>()
        for (ref in tableRefs) {
            result[ref.name] = readTable(session, schema, ref.name, notes)
        }
        return result
    }

    private fun readTable(
        session: JdbcOperations,
        schema: String,
        tableName: String,
        notes: MutableList<SchemaReadNote>,
    ): TableDefinition {
        val colRows = PostgresMetadataQueries.listColumns(session, schema, tableName)
        val pkColumns = PostgresMetadataQueries.listPrimaryKeyColumns(session, schema, tableName)
        val fks = PostgresMetadataQueries.listForeignKeys(session, schema, tableName)
        val uniqueConstraints = PostgresMetadataQueries.listUniqueConstraintColumns(session, schema, tableName)
        val checks = PostgresMetadataQueries.listCheckConstraints(session, schema, tableName)
        val indices = PostgresMetadataQueries.listIndices(session, schema, tableName)

        val singleColFks = SchemaReaderUtils.liftSingleColumnFks(fks)
        val singleColUnique = SchemaReaderUtils.singleColumnUniqueFromConstraints(uniqueConstraints)

        val columns = LinkedHashMap<String, ColumnDefinition>()
        for (row in colRows) {
            val colName = row["column_name"] as String
            val isPkCol = colName in pkColumns
            val isIdentity = (row["is_identity"] as? String) == "YES"
            val mapping = PostgresTypeMapping.mapColumn(PostgresTypeMapping.ColumnInput(
                dataType = row["data_type"] as String,
                udtName = (row["udt_name"] as? String) ?: (row["data_type"] as String),
                isPkCol = isPkCol,
                isIdentity = isIdentity,
                colDefault = row["column_default"] as? String,
                charMaxLen = (row["character_maximum_length"] as? Number)?.toInt(),
                numPrecision = (row["numeric_precision"] as? Number)?.toInt(),
                numScale = (row["numeric_scale"] as? Number)?.toInt(),
                tableName = tableName,
                colName = colName,
            ))
            if (mapping.note != null) notes += mapping.note
            val neutralType = mapping.type

            val required = if (isPkCol) false else (row["is_nullable"] as String) == "NO"
            val unique = if (isPkCol) false else colName in singleColUnique

            val references = singleColFks[colName]

            val defaultVal = if (isPkCol && PostgresTypeMapping.isSerialDefault(row["column_default"] as? String))
                null
            else PostgresTypeMapping.parseDefault(row["column_default"] as? String)

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
        constraints += SchemaReaderUtils.buildMultiColumnUniqueFromConstraints(uniqueConstraints)
        constraints += SchemaReaderUtils.buildCheckConstraints(checks)

        val indexDefs = indices.map { idx ->
            IndexDefinition(
                name = idx.name,
                columns = idx.columns,
                type = when (idx.type) {
                    "btree" -> IndexType.BTREE
                    "hash" -> IndexType.HASH
                    "gin" -> IndexType.GIN
                    "gist" -> IndexType.GIST
                    "brin" -> IndexType.BRIN
                    else -> IndexType.BTREE
                },
                unique = idx.isUnique,
            )
        }

        // Partitioning
        val partitioning = readPartitioning(session, schema, tableName)

        return TableDefinition(
            columns = columns,
            primaryKey = pkColumns,
            indices = indexDefs,
            constraints = constraints,
            partitioning = partitioning,
        )
    }

    private fun readPartitioning(
        session: JdbcOperations,
        schema: String,
        tableName: String,
    ): PartitionConfig? {
        val info = PostgresMetadataQueries.getPartitionInfo(session, schema, tableName)
            ?: return null
        val strategy = when (info["partstrat"] as? String) {
            "r" -> PartitionType.RANGE
            "l" -> PartitionType.LIST
            "h" -> PartitionType.HASH
            else -> return null
        }
        val keyCols = info["key_columns"]
        val key = when (keyCols) {
            is java.sql.Array -> (keyCols.array as Array<*>).map { it.toString() }
            is String -> keyCols.removeSurrounding("{", "}").split(",")
            else -> emptyList()
        }
        return PartitionConfig(type = strategy, key = key)
    }

    // ── Sequences ───────────────────────────────

    private fun readSequences(session: JdbcOperations, schema: String): Map<String, SequenceDefinition> {
        val rows = PostgresMetadataQueries.listSequences(session, schema)
        val result = LinkedHashMap<String, SequenceDefinition>()
        for (row in rows) {
            val name = row["sequence_name"] as String
            // information_schema.sequences returns varchar columns;
            // pg_sequences (joined for cache_size) returns bigint.
            result[name] = SequenceDefinition(
                start = toLongOrNull(row["start_value"]) ?: 1,
                increment = toLongOrNull(row["increment"]) ?: 1,
                minValue = toLongOrNull(row["minimum_value"]),
                maxValue = toLongOrNull(row["maximum_value"]),
                cycle = (row["cycle_option"] as? String) == "YES",
                cache = toLongOrNull(row["cache_size"])?.toInt(),
            )
        }
        return result
    }

    /** Parse a value that may be a Number (from pg_sequences) or a String (from information_schema). */
    private fun toLongOrNull(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

    // ── Custom Types (ENUM, DOMAIN, COMPOSITE) ──

    private fun readCustomTypes(
        session: JdbcOperations,
        schema: String,
        notes: MutableList<SchemaReadNote>,
    ): Map<String, CustomTypeDefinition> {
        val result = LinkedHashMap<String, CustomTypeDefinition>()

        // ENUMs
        for ((name, values) in PostgresMetadataQueries.listEnumTypes(session, schema)) {
            result[name] = CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = values)
        }

        // DOMAINs
        for (row in PostgresMetadataQueries.listDomainTypes(session, schema)) {
            val name = row["typname"] as String
            val baseType = row["base_type"] as? String ?: "text"
            val checkClause = row["check_clause"] as? String
            result[name] = CustomTypeDefinition(
                kind = CustomTypeKind.DOMAIN,
                baseType = PostgresTypeMapping.mapParamType(baseType),
                precision = (row["numeric_precision"] as? Number)?.toInt(),
                scale = (row["numeric_scale"] as? Number)?.toInt(),
                check = checkClause,
            )
        }

        // COMPOSITEs
        val compositeRows = PostgresMetadataQueries.listCompositeTypes(session, schema)
        for ((typeName, fieldRows) in compositeRows.groupBy { it["typname"] as String }) {
            val fields = LinkedHashMap<String, ColumnDefinition>()
            for (fRow in fieldRows.sortedBy { (it["attnum"] as Number).toInt() }) {
                val fieldName = fRow["attname"] as String
                val colType = fRow["column_type"] as? String ?: "text"
                fields[fieldName] = ColumnDefinition(
                    type = PostgresTypeMapping.mapCompositeFieldType(colType),
                )
            }
            result[typeName] = CustomTypeDefinition(
                kind = CustomTypeKind.COMPOSITE,
                fields = fields,
            )
        }

        return result
    }

    // ── Views ───────────────────────────────────

    private fun readViews(session: JdbcOperations, schema: String): Map<String, ViewDefinition> {
        val rows = PostgresMetadataQueries.listViews(session, schema)
        val viewFuncDeps = PostgresMetadataQueries.listViewFunctionDependencies(session, schema)
        val result = LinkedHashMap<String, ViewDefinition>()
        for (row in rows) {
            val viewName = row["table_name"] as String
            val funcDeps = viewFuncDeps[viewName] ?: emptyList()
            result[viewName] = ViewDefinition(
                query = row["view_definition"] as? String,
                dependencies = if (funcDeps.isNotEmpty()) DependencyInfo(functions = funcDeps) else null,
                sourceDialect = "postgresql",
            )
        }
        return result
    }

    // ── Functions ───────────────────────────────

    private fun readFunctions(
        session: JdbcOperations,
        schema: String,
        _notes: MutableList<SchemaReadNote>,
    ): Map<String, FunctionDefinition> {
        val rows = PostgresMetadataQueries.listFunctions(session, schema)
        val result = LinkedHashMap<String, FunctionDefinition>()
        for (row in rows) {
            val name = row["routine_name"] as String
            val specificName = row["specific_name"] as String
            val params = readRoutineParams(session, schema, specificName)
            val paramDefs = params.map { p ->
                ParameterDefinition(
                    name = (p["parameter_name"] as? String) ?: "p${p["ordinal_position"]}",
                    type = PostgresTypeMapping.mapParamType(p["udt_name"] as? String ?: p["data_type"] as? String ?: "text"),
                    direction = when ((p["parameter_mode"] as? String)?.uppercase()) {
                        "OUT" -> ParameterDirection.OUT
                        "INOUT" -> ParameterDirection.INOUT
                        else -> ParameterDirection.IN
                    },
                )
            }
            val key = ObjectKeyCodec.routineKey(name, paramDefs)
            val returnType = (row["data_type"] as? String)?.takeIf { it != "void" }?.let {
                ReturnType(type = PostgresTypeMapping.mapParamType(row["type_udt_name"] as? String ?: it))
            }
            result[key] = FunctionDefinition(
                parameters = paramDefs,
                returns = returnType,
                language = row["external_language"] as? String,
                body = row["routine_definition"] as? String,
                deterministic = (row["is_deterministic"] as? String) == "YES",
                sourceDialect = "postgresql",
            )
        }
        return result
    }

    // ── Procedures ──────────────────────────────

    private fun readProcedures(
        session: JdbcOperations,
        schema: String,
        _notes: MutableList<SchemaReadNote>,
    ): Map<String, ProcedureDefinition> {
        val rows = PostgresMetadataQueries.listProcedures(session, schema)
        val result = LinkedHashMap<String, ProcedureDefinition>()
        for (row in rows) {
            val name = row["routine_name"] as String
            val specificName = row["specific_name"] as String
            val params = readRoutineParams(session, schema, specificName)
            val paramDefs = params.map { p ->
                ParameterDefinition(
                    name = (p["parameter_name"] as? String) ?: "p${p["ordinal_position"]}",
                    type = PostgresTypeMapping.mapParamType(p["udt_name"] as? String ?: p["data_type"] as? String ?: "text"),
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
                language = row["external_language"] as? String,
                body = row["routine_definition"] as? String,
                sourceDialect = "postgresql",
            )
        }
        return result
    }

    private fun readRoutineParams(session: JdbcOperations, schema: String, specificName: String): List<Map<String, Any?>> {
        return PostgresMetadataQueries.listRoutineParameters(session, schema, specificName)
    }

    // ── Triggers ────────────────────────────────

    private fun readTriggers(session: JdbcOperations, schema: String): Map<String, TriggerDefinition> {
        val rows = PostgresMetadataQueries.listTriggers(session, schema)
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
                    "INSTEAD OF" -> TriggerTiming.INSTEAD_OF
                    else -> TriggerTiming.BEFORE
                },
                forEach = when ((row["action_orientation"] as? String)?.uppercase()) {
                    "STATEMENT" -> TriggerForEach.STATEMENT
                    else -> TriggerForEach.ROW
                },
                condition = row["action_condition"] as? String,
                body = row["action_statement"] as? String,
                sourceDialect = "postgresql",
            )
        }
        return result
    }

}
