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
            // VA4/5d Befund 3: SpatiaLite-Registry lesen (leer ohne SpatiaLite) —
            // liefert SRID (sonst via PRAGMA table_info verloren) + Spatial-Index-Flag.
            val geometryColumns = SqliteMetadataQueries.listGeometryColumns(session)
            val geometryByTable = geometryColumns.groupBy { it.table.lowercase() }
            // Befund 3a: die R*Tree-Schattentabellen `idx_<t>_<col>_{node,parent,rowid}`
            // sind reguläre Tabellen (kein VIRTUAL TABLE, keine Metatabellen-Familie) →
            // explizit ausschließen. Die Haupt-VirtualTable `idx_<t>_<col>` fängt
            // bereits [SqliteTypeMapping.isVirtualTable] (S100).
            val rtreeShadowTables = geometryColumns.filter { it.spatialIndexEnabled }.flatMap {
                val base = "idx_${it.table}_${it.column}".lowercase()
                listOf("${base}_node", "${base}_parent", "${base}_rowid")
            }.toSet()
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
                if (SqliteTypeMapping.isSpatiaLiteMetaTable(tableName) ||
                    tableName.lowercase() in rtreeShadowTables
                ) {
                    skipped += SkippedObject(
                        type = "TABLE", name = tableName,
                        reason = "SpatiaLite metadata table",
                        code = "S101",
                    )
                    continue
                }
                tables[tableName] = readTable(
                    session, tableName, createSql,
                    geometryByTable[tableName.lowercase()].orEmpty(), notes,
                )
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
        geometryColumns: List<SqliteGeometryColumn>,
        notes: MutableList<SchemaReadNote>,
    ): TableDefinition {
        val columns = SqliteMetadataQueries.listColumns(session, tableName)
        val pkColumns = SqliteMetadataQueries.listPrimaryKeyColumns(session, tableName)
        val fks = SqliteMetadataQueries.listForeignKeys(session, tableName)
        val indices = SqliteMetadataQueries.listIndices(session, tableName)

        val hasAutoincrement = SqliteTypeMapping.hasAutoincrement(createSql)
        val isWithoutRowid = SqliteTypeMapping.hasWithoutRowid(createSql)

        val singleColUnique = SchemaReaderUtils.singleColumnUniqueFromIndices(indices)
        // VA4/5d Befund 3: SpatiaLite-Registry-Eintrag pro Spalte (case-insensitiv).
        val geometryByColumn = geometryColumns.associateBy { it.column.lowercase() }

        val columnDefs = LinkedHashMap<String, ColumnDefinition>()
        for (col in columns) {
            val isPkCol = col.name in pkColumns
            val isAutoInc = isPkCol && hasAutoincrement && pkColumns.size == 1
                && col.dataType.equals("INTEGER", ignoreCase = true)

            val mapping = SqliteTypeMapping.mapColumn(col.dataType, isAutoInc, tableName, col.name)
            if (mapping.note != null) notes += mapping.note
            // Befund 3: die SRID steht NICHT in PRAGMA table_info (nur der Subtyp),
            // sondern in geometry_columns — sonst käme der Round-Trip mit SRID 0 zurück.
            val neutralType = mapping.type.let { t ->
                val srid = geometryByColumn[col.name.lowercase()]?.srid
                if (t is NeutralType.Geometry && srid != null) t.copy(srid = srid) else t
            }

            // PK-implicit required/unique is NOT duplicated on column level
            val required = if (isPkCol) false else !col.isNullable
            val unique = if (isPkCol) false else col.name in singleColUnique

            columnDefs[col.name] = ColumnDefinition(
                type = neutralType,
                required = required,
                unique = unique,
                default = SqliteTypeMapping.parseDefault(col.columnDefault),
                // PRAGMA table_info.cid ist 0-basiert; +1 für 1-basierte Ordinale
                // konsistent zu PG/MySQL (information_schema.ordinal_position).
                ordinal = col.ordinalPosition + 1,
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

        // VA4/5d Befund 3b: der SpatiaLite-Spatial-Index ist eine R*Tree-VirtualTable
        // + das Flag geometry_columns.spatial_index_enabled — KEIN sqlite_master
        // type='index'-Eintrag, also unsichtbar für PRAGMA index_list (regularIndices).
        // Aus dem Flag den neutralen IndexType.SPATIAL rekonstruieren, damit der
        // Round-Trip den räumlichen Index nicht verliert.
        val spatialIndices = geometryColumns.filter { it.spatialIndexEnabled }.mapNotNull { gc ->
            val colName = columnDefs.keys.firstOrNull { it.equals(gc.column, ignoreCase = true) }
                ?: return@mapNotNull null
            // Index-Name aus dem aufgelösten colName (echte Spaltenschreibweise), nicht aus
            // der geometry_columns-Registry-Schreibweise — sonst Casing-Drift im Round-Trip.
            IndexDefinition(
                name = "idx_${tableName}_${colName}",
                columns = listOf(IndexColumn(colName)),
                type = IndexType.SPATIAL,
            )
        }

        val metadata = if (isWithoutRowid) TableMetadata(withoutRowid = true) else null

        return TableDefinition(
            columns = columnDefs,
            primaryKey = pkColumns,
            indices = regularIndices + spatialIndices,
            constraints = constraints,
            metadata = metadata,
        )
    }

    private fun readViews(session: JdbcMetadataSession): Map<String, ViewDefinition> {
        val viewEntries = SqliteMetadataQueries.listViews(session)
        val result = LinkedHashMap<String, ViewDefinition>()
        for ((name, sql) in viewEntries) {
            // VA4/5d Befund 3 (Views): SpatiaLite-System-Views (vector_layers*,
            // geom_cols_ref_sys, spatial_ref_sys_all) ausschließen — sonst False-Drift.
            if (SqliteTypeMapping.isSpatiaLiteMetaTable(name)) continue
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
            // VA4/5d Befund 3 (Trigger): `InitSpatialMetaData()` legt zahlreiche
            // Integritäts-Trigger AUF den SpatiaLite-Metatabellen an (geometry_columns*,
            // views_/virts_geometry_columns, spatial_ref_sys). Diese Tabellen werden
            // bereits aus dem Reverse gefiltert (S101) — ihre Trigger müssen ebenso raus,
            // sonst referenzieren sie nicht-existente Tabellen (E018) und treiben einen
            // False-Drift im migrate-Post-Compare.
            if (SqliteTypeMapping.isSpatiaLiteMetaTable(table)) continue
            val sql = row["sql"] as? String ?: continue
            // SpatiaLite-Integritäts-/Spatial-Index-Trigger auf der User-Tabelle
            // (gg*/gi*/tm*) ebenfalls ausschließen — siehe isSpatiaLiteGeometryTrigger.
            if (SqliteTypeMapping.isSpatiaLiteGeometryTrigger(name, sql)) continue
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
