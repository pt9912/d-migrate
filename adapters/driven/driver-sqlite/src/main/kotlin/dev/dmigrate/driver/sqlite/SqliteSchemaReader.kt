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
                if (SqliteTypeMapping.isVirtualTable(createSql)) {
                    skipped += SkippedObject(
                        type = "TABLE", name = tableName,
                        reason = "Virtual table not supported in neutral model",
                        code = "S100",
                    )
                    continue
                }
                if (SqliteTypeMapping.isSpatiaLiteMetaTable(tableName)) {
                    skipped += SkippedObject(
                        type = "TABLE", name = tableName,
                        reason = "SpatiaLite metadata table",
                        code = "S101",
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

        val hasAutoincrement = SqliteTypeMapping.hasAutoincrement(createSql)
        val isWithoutRowid = SqliteTypeMapping.hasWithoutRowid(createSql)

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

            val mapping = SqliteTypeMapping.mapColumn(col.dataType, isAutoInc, tableName, col.name)
            if (mapping.note != null) notes += mapping.note
            val neutralType = mapping.type

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
                default = SqliteTypeMapping.parseDefault(col.columnDefault),
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

    private fun readViews(session: JdbcMetadataSession): Map<String, ViewDefinition> {
        val viewEntries = SqliteMetadataQueries.listViews(session)
        val result = LinkedHashMap<String, ViewDefinition>()
        for ((name, sql) in viewEntries) {
            val query = sql?.let { SqliteTypeMapping.extractViewQuery(it) }
            result[name] = ViewDefinition(query = query, sourceDialect = "sqlite")
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
            val parsed = SqliteTypeMapping.parseTriggerSql(sql, name)
            notes += parsed.notes
            val key = ObjectKeyCodec.triggerKey(table, name)
            result[key] = TriggerDefinition(
                table = table, event = parsed.event, timing = parsed.timing,
                body = parsed.body, sourceDialect = "sqlite",
            )
        }
        return result
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
