package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.core.identity.ReverseScopeCodec
import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.SchemaReaderUtils
import dev.dmigrate.driver.sqlite.parser.SqliteTriggerSqlParser

/**
 * SQLite [SchemaReader] implementation.
 *
 * Uses `sqlite_master` and `PRAGMA` commands for metadata extraction.
 * Type affinity is mapped conservatively — uncertain mappings produce
 * reverse notes rather than false precision.
 */
class SqliteSchemaReader : SchemaReader {

    private val sequenceSupport = SqliteSequenceReverseSupport()

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

            // 0.9.7 Phase D: sequence-support reverse runs unconditionally
            // (Plan §6.1 line 1669: "Sequence-Reverse darf nicht von
            // includeTriggers abhaengen").
            val supportSnapshot = sequenceSupport.scanSequenceSupport(session)
            val sequences = sequenceSupport.materializeSequences(supportSnapshot)
            val enrichedTables = sequenceSupport.materializeSequenceDefaults(supportSnapshot, tables)
            val filteredTables = sequenceSupport.filterSupportTable(enrichedTables, supportSnapshot)
            notes += sequenceSupport.aggregateNotes(supportSnapshot)

            // Triggers (filtered against the canonical sequence-support pairs)
            val triggers = if (options.includeTriggers) readTriggers(session, notes) else emptyMap()
            val filteredTriggers = sequenceSupport.filterSupportTriggers(triggers, supportSnapshot)

            val schemaDef = SchemaDefinition(
                name = ReverseScopeCodec.sqliteName(schema),
                version = ReverseScopeCodec.REVERSE_VERSION,
                tables = filteredTables,
                views = views,
                triggers = filteredTriggers,
                sequences = sequences,
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

        val singleColUnique = SchemaReaderUtils.singleColumnUniqueFromIndices(indices)

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

            columnDefs[col.name] = ColumnDefinition(
                type = neutralType,
                required = required,
                unique = unique,
                default = SqliteTypeMapping.parseDefault(col.columnDefault),
            )
        }

        val constraints = mutableListOf<ConstraintDefinition>()
        constraints += SchemaReaderUtils.buildForeignKeyConstraints(fks)
        constraints += SchemaReaderUtils.buildMultiColumnUniqueFromIndices(indices)
        // CHECK constraints from CREATE TABLE SQL
        for ((checkName, checkExpr) in SqliteTypeMapping.extractCheckConstraints(createSql)) {
            constraints += ConstraintDefinition(
                name = checkName,
                type = ConstraintType.CHECK,
                expression = checkExpr,
            )
        }

        // Non-unique, non-autoindex indices
        val regularIndices = indices.filter { it.where != null || !it.isUnique || it.columns.size > 1 }
            .filter { it.where != null || !(it.isUnique && it.columns.size > 1) } // multi-col unique already in constraints
            .map { idx ->
                IndexDefinition(
                    name = idx.name,
                    columns = idx.indexColumns,
                    unique = idx.isUnique,
                    where = idx.where,
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
            val parsed = SqliteTriggerSqlParser.parse(sql, name)
            notes += parsed.notes
            // Schema-qualified names (R212) are rejected — no TriggerDefinition
            // is built, and no object key is written, so the downstream
            // diff path cannot accidentally collide with `schema.table`
            // keys produced for actual tables.
            if (parsed.rejected) continue
            val key = ObjectKeyCodec.triggerKey(table, name)
            result[key] = TriggerDefinition(
                table = table,
                // SQLite triggers fire on exactly one event ({DELETE|INSERT|
                // UPDATE}); the grammar has no multi-event form, so the parser
                // yields a single event that the neutral model wraps as a
                // one-element set (F4).
                events = setOf(parsed.event),
                timing = parsed.timing,
                forEach = parsed.forEach,
                condition = parsed.condition,
                body = parsed.body,
                sourceDialect = "sqlite",
            )
        }
        return result
    }

}
