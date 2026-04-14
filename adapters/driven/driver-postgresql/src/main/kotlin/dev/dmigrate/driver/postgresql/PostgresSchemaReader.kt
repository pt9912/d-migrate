package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.core.identity.ReverseScopeCodec
import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.metadata.JdbcMetadataSession

/**
 * PostgreSQL [SchemaReader] implementation.
 *
 * Uses `information_schema` for portable base data and `pg_catalog`
 * for PostgreSQL-specific metadata (sequences, enum types, backing
 * index detection).
 */
class PostgresSchemaReader : SchemaReader {

    override fun read(pool: ConnectionPool, options: SchemaReadOptions): SchemaReadResult {
        val notes = mutableListOf<SchemaReadNote>()
        val skipped = mutableListOf<SkippedObject>()

        pool.borrow().use { conn ->
            val session = JdbcMetadataSession(conn)
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
        session: JdbcMetadataSession,
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
        session: JdbcMetadataSession,
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

        val singleColFks = fks.filter { it.columns.size == 1 && it.referencedColumns.size == 1 }
            .associateBy { it.columns[0] }
        val singleColUnique = uniqueConstraints.values.filter { it.size == 1 }.map { it[0] }.toSet()

        val columns = LinkedHashMap<String, ColumnDefinition>()
        for (row in colRows) {
            val colName = row["column_name"] as String
            val isPkCol = colName in pkColumns
            val isIdentity = (row["is_identity"] as? String) == "YES"
            val mapping = PostgresTypeMapping.mapColumn(
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
            )
            if (mapping.note != null) notes += mapping.note
            val neutralType = mapping.type

            val required = if (isPkCol) false else (row["is_nullable"] as String) == "NO"
            val unique = if (isPkCol) false else colName in singleColUnique

            val references = singleColFks[colName]?.let { fk ->
                ReferenceDefinition(
                    table = fk.referencedTable,
                    column = fk.referencedColumns[0],
                    onDelete = fk.onDelete?.toReferentialActionOrNull(),
                    onUpdate = fk.onUpdate?.toReferentialActionOrNull(),
                )
            }

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
        for (fk in fks.filter { it.columns.size > 1 }) {
            constraints += ConstraintDefinition(
                name = fk.name,
                type = ConstraintType.FOREIGN_KEY,
                columns = fk.columns,
                references = ConstraintReferenceDefinition(
                    table = fk.referencedTable,
                    columns = fk.referencedColumns,
                    onDelete = fk.onDelete?.toReferentialActionOrNull(),
                    onUpdate = fk.onUpdate?.toReferentialActionOrNull(),
                ),
            )
        }
        for ((name, cols) in uniqueConstraints.filter { it.value.size > 1 }) {
            constraints += ConstraintDefinition(name = name, type = ConstraintType.UNIQUE, columns = cols)
        }
        for (check in checks) {
            constraints += ConstraintDefinition(
                name = check.name, type = ConstraintType.CHECK, expression = check.expression,
            )
        }

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
        session: JdbcMetadataSession,
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

    private fun readSequences(session: JdbcMetadataSession, schema: String): Map<String, SequenceDefinition> {
        val rows = PostgresMetadataQueries.listSequences(session, schema)
        val result = LinkedHashMap<String, SequenceDefinition>()
        for (row in rows) {
            val name = row["sequence_name"] as String
            result[name] = SequenceDefinition(
                start = (row["start_value"] as? Number)?.toLong() ?: 1,
                increment = (row["increment"] as? Number)?.toLong() ?: 1,
                minValue = (row["minimum_value"] as? Number)?.toLong(),
                maxValue = (row["maximum_value"] as? Number)?.toLong(),
                cycle = (row["cycle_option"] as? String) == "YES",
                cache = (row["cache_size"] as? Number)?.toInt(),
            )
        }
        return result
    }

    // ── Custom Types (ENUM, DOMAIN, COMPOSITE) ──

    private fun readCustomTypes(
        session: JdbcMetadataSession,
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
                    type = mapCompositeFieldType(colType),
                )
            }
            result[typeName] = CustomTypeDefinition(
                kind = CustomTypeKind.COMPOSITE,
                fields = fields,
            )
        }

        return result
    }

    private fun mapCompositeFieldType(pgType: String): NeutralType {
        val lower = pgType.lowercase().trim()
        return when {
            lower == "integer" || lower == "int4" -> NeutralType.Integer
            lower == "bigint" || lower == "int8" -> NeutralType.BigInteger
            lower == "smallint" || lower == "int2" -> NeutralType.SmallInt
            lower == "text" -> NeutralType.Text()
            lower == "boolean" || lower == "bool" -> NeutralType.BooleanType
            lower.startsWith("character varying") || lower.startsWith("varchar") -> {
                val len = Regex("\\((\\d+)\\)").find(lower)?.groupValues?.get(1)?.toIntOrNull()
                NeutralType.Text(maxLength = len)
            }
            lower.startsWith("numeric") || lower.startsWith("decimal") -> {
                val match = Regex("\\((\\d+),(\\d+)\\)").find(lower)
                if (match != null) NeutralType.Decimal(match.groupValues[1].toInt(), match.groupValues[2].toInt())
                else NeutralType.Float()
            }
            lower == "uuid" -> NeutralType.Uuid
            lower == "json" || lower == "jsonb" -> NeutralType.Json
            lower == "bytea" -> NeutralType.Binary
            lower == "date" -> NeutralType.Date
            lower == "time" || lower.startsWith("time ") -> NeutralType.Time
            lower.startsWith("timestamp") -> NeutralType.DateTime(timezone = lower.contains("with time zone"))
            else -> NeutralType.Text()
        }
    }

    // ── Views ───────────────────────────────────

    private fun readViews(session: JdbcMetadataSession, schema: String): Map<String, ViewDefinition> {
        val rows = PostgresMetadataQueries.listViews(session, schema)
        val result = LinkedHashMap<String, ViewDefinition>()
        for (row in rows) {
            result[row["table_name"] as String] = ViewDefinition(
                query = row["view_definition"] as? String,
                sourceDialect = "postgresql",
            )
        }
        return result
    }

    // ── Functions ───────────────────────────────

    private fun readFunctions(
        session: JdbcMetadataSession,
        schema: String,
        notes: MutableList<SchemaReadNote>,
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
        session: JdbcMetadataSession,
        schema: String,
        notes: MutableList<SchemaReadNote>,
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

    private fun readRoutineParams(session: JdbcMetadataSession, schema: String, specificName: String): List<Map<String, Any?>> {
        return PostgresMetadataQueries.listRoutineParameters(session, schema, specificName)
    }

    // ── Triggers ────────────────────────────────

    private fun readTriggers(session: JdbcMetadataSession, schema: String): Map<String, TriggerDefinition> {
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

    // ── Helpers ──────────────────────────────────

    private fun String.toReferentialActionOrNull(): ReferentialAction? = when (this.uppercase()) {
        "CASCADE" -> ReferentialAction.CASCADE
        "SET NULL" -> ReferentialAction.SET_NULL
        "SET DEFAULT" -> ReferentialAction.SET_DEFAULT
        "RESTRICT" -> ReferentialAction.RESTRICT
        "NO ACTION" -> ReferentialAction.NO_ACTION
        else -> null
    }
}
