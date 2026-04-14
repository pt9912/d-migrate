package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.core.identity.ReverseScopeCodec
import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.metadata.JdbcMetadataSession

/**
 * SQLite [SchemaReader] implementation.
 *
 * Uses `sqlite_master` and `PRAGMA` commands for metadata extraction.
 * Type affinity is mapped conservatively — uncertain mappings produce
 * reverse notes rather than false precision.
 */
class SqliteSchemaReader : SchemaReader {

    override fun read(pool: ConnectionPool, options: SchemaReadOptions): SchemaReadResult {
        val notes = mutableListOf<SchemaReadNote>()
        val skipped = mutableListOf<SkippedObject>()

        pool.borrow().use { conn ->
            val session = JdbcMetadataSession(conn)
            val schema = "main"

            // Tables
            val tableEntries = SqliteMetadataQueries.listAllTableEntries(session)
            val tables = LinkedHashMap<String, TableDefinition>()

            for ((tableName, createSql) in tableEntries) {
                if (isVirtualTable(createSql)) {
                    skipped += SkippedObject(
                        type = "TABLE", name = tableName,
                        reason = "Virtual table not supported in neutral model",
                        code = "S100",
                    )
                    continue
                }
                tables[tableName] = readTable(session, tableName, createSql, notes)
            }

            // Views
            val views = if (options.includeViews) readViews(session) else emptyMap()

            // Triggers
            val triggers = if (options.includeTriggers) readTriggers(session, notes) else emptyMap()

            val schemaDef = SchemaDefinition(
                name = ReverseScopeCodec.sqliteName(schema),
                version = ReverseScopeCodec.REVERSE_VERSION,
                tables = tables,
                views = views,
                triggers = triggers,
            )

            return SchemaReadResult(
                schema = schemaDef,
                notes = notes,
                skippedObjects = skipped,
            )
        }
    }

    private fun readTable(
        session: JdbcMetadataSession,
        tableName: String,
        createSql: String,
        notes: MutableList<SchemaReadNote>,
    ): TableDefinition {
        val columns = SqliteMetadataQueries.listColumns(session, tableName)
        val pkColumns = SqliteMetadataQueries.listPrimaryKeyColumns(session, tableName)
        val fks = SqliteMetadataQueries.listForeignKeys(session, tableName)
        val indices = SqliteMetadataQueries.listIndices(session, tableName)

        val hasAutoincrement = createSql.contains("AUTOINCREMENT", ignoreCase = true)
        val isWithoutRowid = createSql.contains("WITHOUT ROWID", ignoreCase = true)

        // Build single-column FK lookup for column-level references
        val singleColFks = fks.filter { it.columns.size == 1 && it.referencedColumns.size == 1 }
            .associateBy { it.columns[0] }

        // Build single-column UNIQUE lookup (from explicit indices)
        val singleColUnique = indices.filter { it.isUnique && it.columns.size == 1 }
            .map { it.columns[0] }.toSet()

        val columnDefs = LinkedHashMap<String, ColumnDefinition>()
        for (col in columns) {
            val isPkCol = col.name in pkColumns
            val isAutoInc = isPkCol && hasAutoincrement && pkColumns.size == 1
                && col.dataType.equals("INTEGER", ignoreCase = true)

            val neutralType = mapColumnType(col, isAutoInc, notes, tableName)

            // PK-implicit required/unique is NOT duplicated on column level
            val required = if (isPkCol) false else !col.isNullable
            val unique = if (isPkCol) false else col.name in singleColUnique

            val references = singleColFks[col.name]?.let { fk ->
                ReferenceDefinition(
                    table = fk.referencedTable,
                    column = fk.referencedColumns[0],
                    onDelete = fk.onDelete?.toReferentialActionOrNull(),
                    onUpdate = fk.onUpdate?.toReferentialActionOrNull(),
                )
            }

            columnDefs[col.name] = ColumnDefinition(
                type = neutralType,
                required = required,
                unique = unique,
                default = parseDefault(col.columnDefault),
                references = references,
            )
        }

        // Multi-column FK constraints
        val multiColFks = fks.filter { it.columns.size > 1 }
        // Multi-column UNIQUE constraints (from indices)
        val multiColUnique = indices.filter { it.isUnique && it.columns.size > 1 }

        val constraints = mutableListOf<ConstraintDefinition>()
        for (fk in multiColFks) {
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
        for (idx in multiColUnique) {
            constraints += ConstraintDefinition(
                name = idx.name,
                type = ConstraintType.UNIQUE,
                columns = idx.columns,
            )
        }

        // Non-unique, non-autoindex indices
        val regularIndices = indices.filter { !it.isUnique || it.columns.size > 1 }
            .filter { !(it.isUnique && it.columns.size > 1) } // multi-col unique already in constraints
            .map { idx ->
                IndexDefinition(
                    name = idx.name,
                    columns = idx.columns,
                    unique = idx.isUnique,
                )
            }

        val metadata = if (isWithoutRowid) TableMetadata(withoutRowid = true) else null

        return TableDefinition(
            columns = columnDefs,
            primaryKey = pkColumns,
            indices = regularIndices,
            constraints = constraints,
            metadata = metadata,
        )
    }

    private fun mapColumnType(
        col: dev.dmigrate.driver.metadata.ColumnProjection,
        isAutoIncrement: Boolean,
        notes: MutableList<SchemaReadNote>,
        tableName: String,
    ): NeutralType {
        if (isAutoIncrement) return NeutralType.Identifier(autoIncrement = true)

        val raw = col.dataType.uppercase().trim()
        val maxLen = extractMaxLength(raw)

        return when {
            raw == "INTEGER" || raw == "INT" -> NeutralType.Integer
            raw == "BIGINT" -> NeutralType.BigInteger
            raw == "SMALLINT" -> NeutralType.SmallInt
            raw == "TEXT" -> NeutralType.Text()
            raw == "BLOB" -> NeutralType.Binary
            raw == "REAL" || raw == "DOUBLE" || raw == "FLOAT" -> NeutralType.Float()
            raw == "BOOLEAN" || raw == "TINYINT(1)" -> NeutralType.BooleanType
            raw.startsWith("VARCHAR") || raw.startsWith("CHARACTER VARYING") ->
                NeutralType.Text(maxLength = maxLen)
            raw.startsWith("CHAR(") ->
                NeutralType.Char(length = maxLen ?: 1)
            raw.startsWith("DECIMAL") || raw.startsWith("NUMERIC") -> {
                val (p, s) = extractPrecisionScale(raw)
                if (p != null && s != null) NeutralType.Decimal(p, s)
                else NeutralType.Float()
            }
            raw == "DATE" -> NeutralType.Date
            raw == "TIME" -> NeutralType.Time
            raw == "DATETIME" || raw == "TIMESTAMP" -> NeutralType.DateTime()
            raw == "UUID" -> NeutralType.Uuid
            raw == "JSON" || raw == "JSONB" -> NeutralType.Json
            raw == "" -> {
                // SQLite allows untyped columns (affinity BLOB)
                notes += SchemaReadNote(
                    severity = SchemaReadSeverity.INFO,
                    code = "R200",
                    objectName = "$tableName.${col.name}",
                    message = "Untyped column mapped to text",
                )
                NeutralType.Text()
            }
            else -> {
                notes += SchemaReadNote(
                    severity = SchemaReadSeverity.WARNING,
                    code = "R201",
                    objectName = "$tableName.${col.name}",
                    message = "Unknown SQLite type '$raw' mapped to text",
                    hint = "Review the column type manually",
                )
                NeutralType.Text()
            }
        }
    }

    private fun readViews(session: JdbcMetadataSession): Map<String, ViewDefinition> {
        val viewEntries = SqliteMetadataQueries.listViews(session)
        val result = LinkedHashMap<String, ViewDefinition>()
        for ((name, sql) in viewEntries) {
            val query = sql?.let { extractViewQuery(it) }
            result[name] = ViewDefinition(
                query = query,
                sourceDialect = "sqlite",
            )
        }
        return result
    }

    private fun readTriggers(
        session: JdbcMetadataSession,
        notes: MutableList<SchemaReadNote>,
    ): Map<String, TriggerDefinition> {
        val triggerRows = SqliteMetadataQueries.listTriggers(session)
        val result = LinkedHashMap<String, TriggerDefinition>()
        for (row in triggerRows) {
            val name = row["name"] as String
            val table = row["tbl_name"] as String
            val sql = row["sql"] as? String ?: continue
            val parsed = parseTriggerSql(sql, name, table, notes) ?: continue
            val key = ObjectKeyCodec.triggerKey(table, name)
            result[key] = parsed
        }
        return result
    }

    // ── Helpers ─────────────────────────────────

    private fun isVirtualTable(createSql: String): Boolean =
        createSql.trimStart().startsWith("CREATE VIRTUAL TABLE", ignoreCase = true)

    private fun extractMaxLength(raw: String): Int? {
        val match = Regex("\\((\\d+)\\)").find(raw)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractPrecisionScale(raw: String): Pair<Int?, Int?> {
        val match = Regex("\\((\\d+)\\s*,\\s*(\\d+)\\)").find(raw)
        return if (match != null) {
            match.groupValues[1].toIntOrNull() to match.groupValues[2].toIntOrNull()
        } else {
            null to null
        }
    }

    private fun parseDefault(raw: String?): DefaultValue? {
        if (raw == null) return null
        val trimmed = raw.trim()
        return when {
            trimmed.equals("NULL", ignoreCase = true) -> null
            trimmed.equals("TRUE", ignoreCase = true) -> DefaultValue.BooleanLiteral(true)
            trimmed.equals("FALSE", ignoreCase = true) -> DefaultValue.BooleanLiteral(false)
            trimmed.startsWith("'") && trimmed.endsWith("'") ->
                DefaultValue.StringLiteral(trimmed.substring(1, trimmed.length - 1).replace("''", "'"))
            trimmed.toLongOrNull() != null -> DefaultValue.NumberLiteral(trimmed.toLong())
            trimmed.toDoubleOrNull() != null -> DefaultValue.NumberLiteral(trimmed.toDouble())
            trimmed.contains("datetime(", ignoreCase = true) ||
                trimmed.equals("CURRENT_TIMESTAMP", ignoreCase = true) ->
                DefaultValue.FunctionCall("current_timestamp")
            else -> DefaultValue.StringLiteral(trimmed)
        }
    }

    private fun extractViewQuery(createSql: String): String? {
        val idx = createSql.indexOf(" AS ", ignoreCase = true)
        return if (idx >= 0) createSql.substring(idx + 4).trim() else null
    }

    private fun parseTriggerSql(
        sql: String,
        name: String,
        table: String,
        notes: MutableList<SchemaReadNote>,
    ): TriggerDefinition? {
        val upper = sql.uppercase()
        val timing = when {
            upper.contains("BEFORE") -> TriggerTiming.BEFORE
            upper.contains("AFTER") -> TriggerTiming.AFTER
            upper.contains("INSTEAD OF") -> TriggerTiming.INSTEAD_OF
            else -> {
                notes += SchemaReadNote(
                    severity = SchemaReadSeverity.WARNING,
                    code = "R210",
                    objectName = name,
                    message = "Could not determine trigger timing",
                )
                TriggerTiming.BEFORE
            }
        }
        val event = when {
            upper.contains(" INSERT ") || upper.contains(" INSERT\n") -> TriggerEvent.INSERT
            upper.contains(" UPDATE ") || upper.contains(" UPDATE\n") -> TriggerEvent.UPDATE
            upper.contains(" DELETE ") || upper.contains(" DELETE\n") -> TriggerEvent.DELETE
            else -> {
                notes += SchemaReadNote(
                    severity = SchemaReadSeverity.WARNING,
                    code = "R211",
                    objectName = name,
                    message = "Could not determine trigger event",
                )
                TriggerEvent.INSERT
            }
        }
        // Extract body between BEGIN...END
        val bodyMatch = Regex("BEGIN\\s+(.*?)\\s+END", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(sql)
        val body = bodyMatch?.groupValues?.get(1)?.trim()

        return TriggerDefinition(
            table = table,
            event = event,
            timing = timing,
            body = body,
            sourceDialect = "sqlite",
        )
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
