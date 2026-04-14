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
            val enumTypes = readEnumTypes(session, schema)
            val views = if (options.includeViews) readViews(session, schema) else emptyMap()
            val functions = if (options.includeFunctions) readFunctions(session, schema, notes) else emptyMap()
            val procedures = if (options.includeProcedures) readProcedures(session, schema, notes) else emptyMap()
            val triggers = if (options.includeTriggers) readTriggers(session, schema) else emptyMap()

            val schemaDef = SchemaDefinition(
                name = ReverseScopeCodec.postgresName(database, schema),
                version = ReverseScopeCodec.REVERSE_VERSION,
                tables = tables,
                sequences = sequences,
                customTypes = enumTypes,
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
            val neutralType = mapColumnType(row, isPkCol, isIdentity, notes, tableName)

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

            val defaultVal = if (isPkCol && isSerialDefault(row["column_default"] as? String))
                null // Serial/identity default is implicit — don't pollute model
            else parseDefault(row["column_default"] as? String)

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

        return TableDefinition(
            columns = columns,
            primaryKey = pkColumns,
            indices = indexDefs,
            constraints = constraints,
        )
    }

    // ── Type Mapping ────────────────────────────

    private fun mapColumnType(
        row: Map<String, Any?>,
        isPkCol: Boolean,
        isIdentity: Boolean,
        notes: MutableList<SchemaReadNote>,
        tableName: String,
    ): NeutralType {
        val dataType = (row["data_type"] as String).lowercase()
        val udtName = (row["udt_name"] as? String)?.lowercase() ?: dataType
        val colName = row["column_name"] as String
        val colDefault = row["column_default"] as? String

        // Serial/identity → Identifier for integer, BigInteger for bigint
        if (isPkCol && (isIdentity || isSerialDefault(colDefault))) {
            return when {
                udtName == "int4" || udtName == "int2" || dataType == "integer" || dataType == "smallint" ->
                    NeutralType.Identifier(autoIncrement = true)
                udtName == "int8" || dataType == "bigint" -> {
                    notes += SchemaReadNote(
                        severity = SchemaReadSeverity.INFO,
                        code = "R300",
                        objectName = "$tableName.$colName",
                        message = "bigint auto-increment mapped to BigInteger (not Identifier) to preserve type width",
                    )
                    NeutralType.BigInteger
                }
                else -> NeutralType.Identifier(autoIncrement = true)
            }
        }

        return when (dataType) {
            "integer" -> NeutralType.Integer
            "bigint" -> NeutralType.BigInteger
            "smallint" -> NeutralType.SmallInt
            "boolean" -> NeutralType.BooleanType
            "text" -> NeutralType.Text()
            "character varying" -> NeutralType.Text(maxLength = (row["character_maximum_length"] as? Number)?.toInt())
            "character" -> NeutralType.Char(length = (row["character_maximum_length"] as? Number)?.toInt() ?: 1)
            "numeric", "decimal" -> {
                val p = (row["numeric_precision"] as? Number)?.toInt()
                val s = (row["numeric_scale"] as? Number)?.toInt()
                if (p != null && s != null) NeutralType.Decimal(p, s) else NeutralType.Float()
            }
            "real" -> NeutralType.Float(FloatPrecision.SINGLE)
            "double precision" -> NeutralType.Float(FloatPrecision.DOUBLE)
            "timestamp without time zone" -> NeutralType.DateTime(timezone = false)
            "timestamp with time zone" -> NeutralType.DateTime(timezone = true)
            "date" -> NeutralType.Date
            "time without time zone", "time with time zone" -> NeutralType.Time
            "uuid" -> NeutralType.Uuid
            "json", "jsonb" -> NeutralType.Json
            "xml" -> NeutralType.Xml
            "bytea" -> NeutralType.Binary
            "user-defined" -> mapUserDefinedType(udtName, row, notes, tableName, colName)
            "array" -> {
                val elementUdt = udtName.removePrefix("_")
                val elementType = mapArrayElementType(elementUdt)
                NeutralType.Array(elementType)
            }
            else -> {
                notes += SchemaReadNote(
                    severity = SchemaReadSeverity.WARNING,
                    code = "R301",
                    objectName = "$tableName.$colName",
                    message = "Unknown PostgreSQL type '$dataType' (udt: $udtName) mapped to text",
                )
                NeutralType.Text()
            }
        }
    }

    private fun mapUserDefinedType(
        udtName: String,
        row: Map<String, Any?>,
        notes: MutableList<SchemaReadNote>,
        tableName: String,
        colName: String,
    ): NeutralType {
        // Geometry (PostGIS)
        if (udtName == "geometry") {
            return NeutralType.Geometry()
        }
        // Otherwise assume it's an enum ref type
        return NeutralType.Enum(refType = udtName)
    }

    private fun mapArrayElementType(elementUdt: String): String = when (elementUdt) {
        "int4", "int2" -> "integer"
        "int8" -> "biginteger"
        "text", "varchar", "bpchar" -> "text"
        "bool" -> "boolean"
        "uuid" -> "uuid"
        "float4" -> "float"
        "float8" -> "float"
        "numeric" -> "decimal"
        "json", "jsonb" -> "json"
        else -> "text"
    }

    private fun isSerialDefault(default: String?): Boolean {
        if (default == null) return false
        val d = default.lowercase()
        return d.startsWith("nextval(") || d.contains("nextval(")
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

    // ── Enum Types ──────────────────────────────

    private fun readEnumTypes(session: JdbcMetadataSession, schema: String): Map<String, CustomTypeDefinition> {
        val enums = PostgresMetadataQueries.listEnumTypes(session, schema)
        val result = LinkedHashMap<String, CustomTypeDefinition>()
        for ((name, values) in enums) {
            result[name] = CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = values)
        }
        return result
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
            val params = readRoutineParams(session, schema, name)
            val paramDefs = params.map { p ->
                ParameterDefinition(
                    name = (p["parameter_name"] as? String) ?: "p${p["ordinal_position"]}",
                    type = mapParamType(p["udt_name"] as? String ?: p["data_type"] as? String ?: "text"),
                    direction = when ((p["parameter_mode"] as? String)?.uppercase()) {
                        "OUT" -> ParameterDirection.OUT
                        "INOUT" -> ParameterDirection.INOUT
                        else -> ParameterDirection.IN
                    },
                )
            }
            val key = ObjectKeyCodec.routineKey(name, paramDefs)
            val returnType = (row["data_type"] as? String)?.takeIf { it != "void" }?.let {
                ReturnType(type = mapParamType(row["type_udt_name"] as? String ?: it))
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
            val params = readRoutineParams(session, schema, name)
            val paramDefs = params.map { p ->
                ParameterDefinition(
                    name = (p["parameter_name"] as? String) ?: "p${p["ordinal_position"]}",
                    type = mapParamType(p["udt_name"] as? String ?: p["data_type"] as? String ?: "text"),
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

    private fun readRoutineParams(session: JdbcMetadataSession, schema: String, routineName: String): List<Map<String, Any?>> {
        return PostgresMetadataQueries.listRoutineParameters(session, schema, routineName)
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

    private fun parseDefault(raw: String?): DefaultValue? {
        if (raw == null) return null
        val trimmed = raw.trim()
        return when {
            trimmed.equals("NULL", ignoreCase = true) -> null
            trimmed.startsWith("nextval(") -> null // serial default — handled separately
            trimmed.equals("true", ignoreCase = true) -> DefaultValue.BooleanLiteral(true)
            trimmed.equals("false", ignoreCase = true) -> DefaultValue.BooleanLiteral(false)
            trimmed.equals("CURRENT_TIMESTAMP", ignoreCase = true) ||
                trimmed.equals("now()", ignoreCase = true) -> DefaultValue.FunctionCall("current_timestamp")
            trimmed.equals("gen_random_uuid()", ignoreCase = true) -> DefaultValue.FunctionCall("gen_uuid")
            trimmed.startsWith("'") && trimmed.contains("'::") -> {
                val value = trimmed.substringAfter("'").substringBefore("'::")
                DefaultValue.StringLiteral(value.replace("''", "'"))
            }
            trimmed.startsWith("'") && trimmed.endsWith("'") ->
                DefaultValue.StringLiteral(trimmed.substring(1, trimmed.length - 1).replace("''", "'"))
            trimmed.toLongOrNull() != null -> DefaultValue.NumberLiteral(trimmed.toLong())
            trimmed.toDoubleOrNull() != null -> DefaultValue.NumberLiteral(trimmed.toDouble())
            trimmed.contains("::") -> {
                // Cast expression like '0'::numeric — extract the literal
                val literal = trimmed.substringBefore("::")
                if (literal.startsWith("'") && literal.endsWith("'")) {
                    DefaultValue.StringLiteral(literal.substring(1, literal.length - 1))
                } else {
                    literal.toLongOrNull()?.let { DefaultValue.NumberLiteral(it) }
                        ?: literal.toDoubleOrNull()?.let { DefaultValue.NumberLiteral(it) }
                        ?: DefaultValue.FunctionCall(trimmed)
                }
            }
            else -> DefaultValue.FunctionCall(trimmed)
        }
    }

    private fun mapParamType(pgType: String): String = when (pgType.lowercase()) {
        "int4", "integer" -> "integer"
        "int8", "bigint" -> "biginteger"
        "int2", "smallint" -> "smallint"
        "text", "varchar", "bpchar", "character varying" -> "text"
        "bool", "boolean" -> "boolean"
        "float4", "real" -> "float"
        "float8", "double precision" -> "float"
        "numeric", "decimal" -> "decimal"
        "uuid" -> "uuid"
        "json", "jsonb" -> "json"
        "bytea" -> "binary"
        "void" -> "void"
        else -> pgType
    }

    private fun String.toReferentialActionOrNull(): ReferentialAction? = when (this.uppercase()) {
        "CASCADE" -> ReferentialAction.CASCADE
        "SET NULL" -> ReferentialAction.SET_NULL
        "SET DEFAULT" -> ReferentialAction.SET_DEFAULT
        "RESTRICT" -> ReferentialAction.RESTRICT
        "NO ACTION" -> ReferentialAction.NO_ACTION
        else -> null
    }
}
