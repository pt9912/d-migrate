package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.core.identity.ReverseScopeCodec
import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.metadata.IndexProjection
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

        pool.borrow().asJdbc().use { conn ->
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
            // ADR 0025 (Slice P5): scan the FTS5 virtual tables so the FULLTEXT expansion
            // (SqliteFullTextExpansion) folds back on reverse — the virtual table itself is a
            // FULLTEXT index on its content table, its `_data/_idx/_docsize/_config` shadow tables
            // are FTS5-internal, and its `_ai/_ad/_au` sync triggers are FTS5-internal. Without this
            // they would surface as user tables/triggers and drive a false migrate post-compare drift.
            val fts5Defs = tableEntries
                .filter { (_, createSql) -> SqliteFts5Reverse.isFts5VirtualTable(createSql) }
                .map { (name, createSql) -> SqliteFts5Reverse.parseFts5(name, createSql) }
            // `_content` is only a real shadow for regular/contentless FTS5 (external-content, the
            // form P4 emits, has none) → external tables must not filter it (avoid dropping a real
            // user table named `<fts>_content`).
            val fts5ShadowTables = fts5Defs
                .flatMap { SqliteFts5Reverse.fts5ShadowTables(it.name, externalContent = it.contentTable != null) }
                .toSet()
            val fts5SyncTriggerNames = fts5Defs.flatMap { SqliteFts5Reverse.fts5SyncTriggerNames(it.name) }.toSet()
            val fts5TableNames = fts5Defs.map { it.name.lowercase() }.toSet()
            val fts5ByContentTable = fts5Defs.filter { it.contentTable != null }
                .groupBy { it.contentTable!!.lowercase() }
            val tables = LinkedHashMap<String, TableDefinition>()

            for ((tableName, createSql) in tableEntries) {
                if (SqliteTypeMapping.isVirtualTable(createSql)) {
                    // The main FTS5 virtual table is folded back into a FULLTEXT index on its
                    // content table (readTable) rather than surfaced as a table — still skipped here.
                    skipped += SkippedObject(
                        type = "TABLE", name = tableName,
                        reason = "Virtual table not supported in neutral model",
                        code = "S100",
                    )
                    continue
                }
                if (tableName.lowercase() in fts5ShadowTables) {
                    skipped += SkippedObject(
                        type = "TABLE", name = tableName,
                        reason = "FTS5 shadow table",
                        code = "S102",
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
                    geometryByTable[tableName.lowercase()].orEmpty(),
                    fts5ByContentTable[tableName.lowercase()].orEmpty(), notes,
                )
            }

            // ADR 0025 (Slice P5): an external-content FTS5 whose `content=` table did NOT survive
            // the reverse (filtered/absent — SQLite doesn't enforce content= referential integrity)
            // would drop its FULLTEXT index silently (readTable only runs for surviving tables).
            // Record it as a skip so the loss is diagnosed rather than silent.
            for (fts in fts5Defs) {
                val ct = fts.contentTable ?: continue
                if (tables.keys.none { it.equals(ct, ignoreCase = true) }) {
                    skipped += SkippedObject(
                        type = "INDEX", name = fts.name,
                        reason = "FTS5 content table '$ct' not present in the reversed schema; " +
                            "FULLTEXT index not reconstructed",
                        code = "S103",
                    )
                }
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

            // Triggers (filtered against the canonical sequence-support pairs + FTS5 sync triggers)
            val triggers =
                if (options.includeTriggers) {
                    readTriggers(session, fts5SyncTriggerNames, fts5TableNames, notes)
                } else {
                    emptyMap()
                }
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
        fts5Defs: List<SqliteFts5Reverse.Fts5Definition>,
        notes: MutableList<SchemaReadNote>,
    ): TableDefinition {
        val columns = SqliteMetadataQueries.listColumns(session, tableName)
        val pkColumns = SqliteMetadataQueries.listPrimaryKeyColumns(session, tableName)
        val fks = SqliteMetadataQueries.listForeignKeys(session, tableName)
        val indices = SqliteMetadataQueries.listIndices(session, tableName)
        // AP4: UNIQUE-Constraint-Autoindizes (origin 'u') — vorher komplett
        // verworfen (Fidelity-Bug: Reverse verlor inline-UNIQUE ganz).
        val uniqueConstraintIndexes = SqliteMetadataQueries.listUniqueConstraintIndexes(session, tableName)

        val hasAutoincrement = SqliteTypeMapping.hasAutoincrement(createSql)
        val isWithoutRowid = SqliteTypeMapping.hasWithoutRowid(createSql)

        val singleColUnique = SchemaReaderUtils.singleColumnUniqueFromIndices(indices) +
            SchemaReaderUtils.singleColumnUniqueFromIndices(uniqueConstraintIndexes)
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
        constraints += multiColumnUniqueConstraints(createSql, uniqueConstraintIndexes)
        // CHECK constraints from CREATE TABLE SQL
        val checkScan = SqliteCheckConstraintScanner.scan(createSql)
        for ((checkName, checkExpr) in checkScan.named) {
            constraints += ConstraintDefinition(
                name = checkName,
                type = ConstraintType.CHECK,
                expression = checkExpr,
            )
        }
        // Unnamed CHECK clauses cannot become ConstraintDefinitions (the
        // model keys constraints by name) — surface the drop loud instead
        // of silently losing the rule.
        for (expr in checkScan.unnamedExpressions) {
            notes += SchemaReadNote(
                severity = SchemaReadSeverity.INFO, code = "R203",
                objectName = tableName,
                message = "Unnamed CHECK constraint not carried on reverse: CHECK ($expr)",
                hint = "Name the constraint (CONSTRAINT <name> CHECK (...)) to include it " +
                    "in the neutral schema",
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

        // ADR 0025 (Slice P5): reconstruct the neutral FULLTEXT index from each FTS5 virtual table
        // whose `content=` is this table — the counterpart to the P4 expansion. The FTS5 virtual
        // table + its shadow tables + sync triggers are filtered elsewhere; here the *index* is
        // recovered so a PG→SQLite (or SQLite→SQLite) round-trip keeps the full-text structure.
        // Source-column casing is resolved against the real column names (no round-trip drift);
        // textSearchConfig/fullTextVectorColumn are not recoverable from FTS5 and stay null (the
        // latter is a generate-only hint excluded from comparison anyway).
        val fullTextIndices = fts5Defs.map { fts ->
            IndexDefinition(
                name = fts.name,
                columns = fts.columns.map { c ->
                    IndexColumn(columnDefs.keys.firstOrNull { it.equals(c, ignoreCase = true) } ?: c)
                },
                type = IndexType.FULLTEXT,
            )
        }

        val metadata = if (isWithoutRowid) TableMetadata(withoutRowid = true) else null

        return TableDefinition(
            columns = columnDefs,
            primaryKey = pkColumns,
            indices = regularIndices + spatialIndices + fullTextIndices,
            constraints = constraints,
            metadata = metadata,
        )
    }

    /**
     * AP4: Multi-Column-UNIQUE-Constraint-Autoindizes → benannte Constraints.
     * Der Constraint-Name steht nur im CREATE-TABLE-SQL (sqlite_master), nicht
     * im Katalog — [SqliteUniqueConstraintScanner] liest ihn; Klauseln werden
     * per Spaltenliste gematcht (verbraucht, damit Duplikate deterministisch
     * bleiben). Unbenannte Klauseln bekommen einen synthetischen Namen
     * (`uq_N`, Präzedenz: FK-Reverse `fk_N`).
     */
    private fun multiColumnUniqueConstraints(
        createSql: String,
        uniqueConstraintIndexes: List<IndexProjection>,
    ): List<ConstraintDefinition> {
        val clauses = SqliteUniqueConstraintScanner.scan(createSql)
            .map { it to it.columns.map(String::lowercase) }
            .toMutableList()
        // Synthetische Namen dürfen nicht mit real rekonstruierten kollidieren
        // (Tabelle mit echtem `uq_0` + unbenannter Klausel → uq_1).
        val taken = clauses.mapNotNull { it.first.name }.toMutableSet()
        return uniqueConstraintIndexes.filter { it.columns.size > 1 }.map { idx ->
            val wanted = idx.columns.map(String::lowercase)
            val match = clauses.firstOrNull { it.second == wanted }
            if (match != null) clauses.remove(match)
            val name = match?.first?.name ?: syntheticUniqueName(taken)
            taken += name
            ConstraintDefinition(name = name, type = ConstraintType.UNIQUE, columns = idx.columns)
        }
    }

    private fun syntheticUniqueName(taken: Set<String>): String {
        var n = 0
        while ("uq_$n" in taken) n++
        return "uq_$n"
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
        fts5SyncTriggerNames: Set<String>,
        fts5TableNames: Set<String>,
        notes: MutableList<SchemaReadNote>,
    ): Map<String, TriggerDefinition> {
        val triggerRows = SqliteMetadataQueries.listTriggers(session)
        val result = LinkedHashMap<String, TriggerDefinition>()
        for (row in triggerRows) {
            val name = row["name"] as String
            val table = row["tbl_name"] as String
            // ADR 0025 (Slice P5): the FTS5 sync triggers (<fts>_ai/_ad/_au) are internal to the
            // FULLTEXT expansion — folded back via the reconstructed index — so they must not
            // surface as user triggers (else false migrate post-compare drift).
            if (name.lowercase() in fts5SyncTriggerNames) continue
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
            // ADR 0025 (Slice P5): a trigger that INSERTs into a known FTS5 table is an FTS5 sync
            // trigger (folded via the reconstructed index), even when its name doesn't match the
            // generated <fts>_{ai,ad,au} — catches custom-named sync triggers on a hand-authored fts5.
            if (SqliteFts5Reverse.isFts5SyncTrigger(sql, fts5TableNames)) continue
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
