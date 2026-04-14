package dev.dmigrate.driver.mysql

import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.core.identity.ReverseScopeCodec
import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.metadata.JdbcMetadataSession

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
        val engine = MysqlMetadataQueries.listTableEngine(session, database, metaTable)

        // Separate FK-backing indices from user indices
        val fkNames = fks.map { it.name }.toSet()
        val indices = allIndices.filter { it.name !in fkNames }

        val singleColFks = fks.filter { it.columns.size == 1 && it.referencedColumns.size == 1 }
            .associateBy { it.columns[0] }
        val singleColUnique = indices.filter { it.isUnique && it.columns.size == 1 }
            .map { it.columns[0] }.toSet()

        val columns = LinkedHashMap<String, ColumnDefinition>()
        for (row in colRows) {
            val colName = row["column_name"] as String
            val isPkCol = colName in pkColumns
            val extra = (row["extra"] as? String) ?: ""
            val isAutoIncrement = extra.contains("auto_increment", ignoreCase = true)
            val neutralType = mapColumnType(row, isPkCol, isAutoIncrement, notes, displayName)

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

            val defaultVal = if (isAutoIncrement) null
            else parseDefault(row["column_default"] as? String, neutralType)

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
        for (idx in indices.filter { it.isUnique && it.columns.size > 1 }) {
            constraints += ConstraintDefinition(name = idx.name, type = ConstraintType.UNIQUE, columns = idx.columns)
        }

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

    // ── Type Mapping ────────────────────────────

    private fun mapColumnType(
        row: Map<String, Any?>,
        isPkCol: Boolean,
        isAutoIncrement: Boolean,
        notes: MutableList<SchemaReadNote>,
        tableName: String,
    ): NeutralType {
        val dataType = (row["data_type"] as String).lowercase()
        val columnType = (row["column_type"] as? String)?.lowercase() ?: dataType
        val colName = row["column_name"] as String

        if (isAutoIncrement) {
            return when (dataType) {
                "int" -> NeutralType.Identifier(autoIncrement = true)
                "bigint" -> {
                    notes += SchemaReadNote(
                        severity = SchemaReadSeverity.INFO,
                        code = "R300",
                        objectName = "$tableName.$colName",
                        message = "bigint auto_increment mapped to BigInteger (not Identifier) to preserve type width",
                    )
                    NeutralType.BigInteger
                }
                "smallint" -> NeutralType.Identifier(autoIncrement = true)
                "mediumint" -> NeutralType.Identifier(autoIncrement = true)
                "tinyint" -> NeutralType.Identifier(autoIncrement = true)
                else -> NeutralType.Identifier(autoIncrement = true)
            }
        }

        return when (dataType) {
            "int" -> NeutralType.Integer
            "bigint" -> NeutralType.BigInteger
            "smallint" -> NeutralType.SmallInt
            "mediumint" -> NeutralType.Integer
            "tinyint" -> {
                if (columnType == "tinyint(1)") NeutralType.BooleanType
                else NeutralType.SmallInt
            }
            "varchar" -> NeutralType.Text(maxLength = (row["character_maximum_length"] as? Number)?.toInt())
            "char" -> {
                val len = (row["character_maximum_length"] as? Number)?.toInt() ?: 1
                if (len == 36) {
                    notes += SchemaReadNote(
                        severity = SchemaReadSeverity.INFO,
                        code = "R310",
                        objectName = "$tableName.$colName",
                        message = "char(36) mapped to Uuid — if not a UUID, review manually",
                    )
                    NeutralType.Uuid
                } else NeutralType.Char(length = len)
            }
            "text", "mediumtext", "longtext", "tinytext" -> NeutralType.Text()
            "decimal", "numeric" -> {
                val p = (row["numeric_precision"] as? Number)?.toInt()
                val s = (row["numeric_scale"] as? Number)?.toInt()
                if (p != null && s != null) NeutralType.Decimal(p, s) else NeutralType.Float()
            }
            "float" -> NeutralType.Float(FloatPrecision.SINGLE)
            "double" -> NeutralType.Float(FloatPrecision.DOUBLE)
            "boolean" -> NeutralType.BooleanType
            "date" -> NeutralType.Date
            "time" -> NeutralType.Time
            "datetime", "timestamp" -> NeutralType.DateTime()
            "json" -> NeutralType.Json
            "blob", "mediumblob", "longblob", "tinyblob", "binary", "varbinary" -> NeutralType.Binary
            "enum" -> {
                val values = extractEnumValues(columnType)
                NeutralType.Enum(values = values)
            }
            "set" -> {
                notes += SchemaReadNote(
                    severity = SchemaReadSeverity.ACTION_REQUIRED,
                    code = "R320",
                    objectName = "$tableName.$colName",
                    message = "MySQL SET type '$columnType' has no neutral equivalent — mapped to text",
                    hint = "Review and convert to enum or text with application-level validation",
                )
                NeutralType.Text()
            }
            "geometry", "point", "linestring", "polygon",
            "multipoint", "multilinestring", "multipolygon", "geometrycollection" -> {
                val geoType = GeometryType.of(dataType)
                NeutralType.Geometry(geometryType = geoType)
            }
            else -> {
                notes += SchemaReadNote(
                    severity = SchemaReadSeverity.WARNING,
                    code = "R301",
                    objectName = "$tableName.$colName",
                    message = "Unknown MySQL type '$dataType' mapped to text",
                )
                NeutralType.Text()
            }
        }
    }

    private fun extractEnumValues(columnType: String): List<String> {
        // enum('a','b','c') → [a, b, c]
        val match = Regex("enum\\((.+)\\)", RegexOption.IGNORE_CASE).find(columnType)
        return match?.groupValues?.get(1)
            ?.split(",")
            ?.map { it.trim().removeSurrounding("'") }
            ?: emptyList()
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
                    type = mapParamType(p["data_type"] as? String ?: "text"),
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
                returns = (row["dtd_identifier"] as? String)?.let { ReturnType(type = mapParamType(it)) },
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
                    type = mapParamType(p["data_type"] as? String ?: "text"),
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

    // ── Helpers ──────────────────────────────────

    private fun parseDefault(raw: String?, type: NeutralType): DefaultValue? {
        if (raw == null) return null
        val trimmed = raw.trim()
        return when {
            trimmed.equals("NULL", ignoreCase = true) -> null
            trimmed == "CURRENT_TIMESTAMP" || trimmed == "current_timestamp()" ->
                DefaultValue.FunctionCall("current_timestamp")
            trimmed == "1" && type is NeutralType.BooleanType -> DefaultValue.BooleanLiteral(true)
            trimmed == "0" && type is NeutralType.BooleanType -> DefaultValue.BooleanLiteral(false)
            trimmed.startsWith("'") && trimmed.endsWith("'") ->
                DefaultValue.StringLiteral(trimmed.substring(1, trimmed.length - 1).replace("''", "'"))
            trimmed.toLongOrNull() != null -> DefaultValue.NumberLiteral(trimmed.toLong())
            trimmed.toDoubleOrNull() != null -> DefaultValue.NumberLiteral(trimmed.toDouble())
            else -> DefaultValue.FunctionCall(trimmed)
        }
    }

    private fun mapParamType(mysqlType: String): String = when (mysqlType.lowercase().trim()) {
        "int", "integer" -> "integer"
        "bigint" -> "biginteger"
        "smallint", "tinyint", "mediumint" -> "smallint"
        "varchar", "text", "char", "mediumtext", "longtext" -> "text"
        "boolean", "tinyint(1)" -> "boolean"
        "float", "double", "real" -> "float"
        "decimal", "numeric" -> "decimal"
        "json" -> "json"
        "blob", "binary", "varbinary" -> "binary"
        "date" -> "date"
        "time" -> "time"
        "datetime", "timestamp" -> "datetime"
        else -> mysqlType.lowercase()
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
