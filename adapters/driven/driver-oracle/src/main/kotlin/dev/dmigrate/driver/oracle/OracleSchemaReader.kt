package dev.dmigrate.driver.oracle

import dev.dmigrate.core.identity.ReverseScopeCodec
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DependencyInfo
import dev.dmigrate.core.model.DependencyProjectionStatus
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.SchemaReadResult
import dev.dmigrate.driver.SchemaReadSeverity
import dev.dmigrate.driver.SchemaReader
import dev.dmigrate.driver.SkippedObject
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.JdbcOperations
import dev.dmigrate.driver.metadata.SchemaReaderUtils
import java.sql.Connection

/**
 * Oracle [SchemaReader]: Tabellen (Spalten, PK, FKs, Unique-/Nicht-Unique-
 * Indizes, CHECK-Constraints), native Sequenzen, Views sowie Funktionen,
 * Prozeduren und Trigger des aktuellen Schemas (= aktueller User), gelesen aus
 * `all_*`-Katalogsichten.
 *
 * PL/SQL-Packages bleiben aussen vor: das neutrale Modell fuehrt Routinen
 * einzeln und kennt keine Gruppierung. Vorhandene Packages erscheinen als
 * [SkippedObject]s plus einer `R342`-Notiz, damit die Luecke sichtbar statt
 * still ist. Was [OracleRoutineReader] von den gelesenen Objektarten
 * uebergeht, meldet er selbst.
 */
class OracleSchemaReader(
    private val jdbcFactory: (Connection) -> JdbcOperations = ::JdbcMetadataSession,
) : SchemaReader {

    override fun read(pool: ConnectionPool, options: SchemaReadOptions): SchemaReadResult {
        pool.borrow().asJdbc().use { conn ->
            val session = jdbcFactory(conn)
            val schema = OracleIdentifiers.currentSchema(session)
            val notes = mutableListOf<SchemaReadNote>()
            val skipped = mutableListOf<SkippedObject>()

            val tables = readTables(session, schema, notes)
            val views = if (options.includeViews) readViews(session, schema, notes) else emptyMap()
            val sequences = readSequences(session, schema, notes)
            val routines = OracleRoutineReader.read(session, schema, options, notes, skipped)
            noteUnreadObjects(session, schema, options, notes, skipped)

            return SchemaReadResult(
                schema = SchemaDefinition(
                    name = ReverseScopeCodec.oracleName(schema),
                    version = ReverseScopeCodec.REVERSE_VERSION,
                    tables = tables,
                    views = views,
                    sequences = sequences,
                    functions = routines.functions,
                    procedures = routines.procedures,
                    triggers = routines.triggers,
                ),
                notes = notes,
                skippedObjects = skipped,
            )
        }
    }

    private fun readTables(
        session: JdbcOperations,
        schema: String,
        notes: MutableList<SchemaReadNote>,
    ): Map<String, TableDefinition> =
        OracleMetadataQueries.listTableRefs(session, schema).associate { ref ->
            ref.name to readTable(session, schema, ref.name, notes)
        }

    private fun readTable(
        session: JdbcOperations,
        schema: String,
        table: String,
        notes: MutableList<SchemaReadNote>,
    ): TableDefinition {
        val columnRows = OracleMetadataQueries.listColumns(session, schema, table)
        val primaryKey = OracleMetadataQueries.listPrimaryKeyColumns(session, schema, table)
        val foreignKeys = OracleMetadataQueries.listForeignKeys(session, schema, table)
        val indexScan = OracleMetadataQueries.scanIndexes(session, schema, table)
        val checks = OracleMetadataQueries.listCheckConstraints(session, schema, table)
        val geometry = geometryMetadata(session, schema, table, notes)

        val singleColumnUnique = SchemaReaderUtils.singleColumnUniqueFromIndices(indexScan.indices)
        val pkColumns = primaryKey.toSet()

        val columns = columnRows.associate { row ->
            val mapping = OracleTypeMapping.mapColumn(
                "$table.${row.name}",
                OracleTypeMapping.ColumnInput(
                    typeName = row.typeName,
                    length = row.length,
                    precision = row.precision,
                    scale = row.scale,
                    isIdentity = row.isIdentity,
                    identityGeneration = row.identityGeneration,
                    identitySequenceName = row.identitySequenceName,
                    geometrySrid = geometry[row.name]?.srid,
                ),
            )
            mapping.note?.let { notes += it }
            row.name to ColumnDefinition(
                type = mapping.type,
                // PK-Spalten folgen der Reverse-Konvention required=false/
                // unique=false -- PK impliziert beides (MySQL-Praezedenz).
                required = !row.nullable && row.name !in pkColumns,
                unique = row.name in singleColumnUnique && row.name !in pkColumns,
                default = if (row.isIdentity) null else OracleTypeMapping.parseDefault(row.defaultDefinition, mapping.type),
                generation = mapping.generation,
                ordinal = row.ordinal,
            )
        }

        val constraints = SchemaReaderUtils.buildForeignKeyConstraints(foreignKeys) +
            SchemaReaderUtils.buildMultiColumnUniqueFromIndices(indexScan.indices) +
            SchemaReaderUtils.buildCheckConstraints(checks)

        // Einspaltige, ungefilterte Unique-Indizes sind bereits auf
        // column.unique gehoben, mehrspaltige auf eine UNIQUE-Constraint --
        // aber nur, wenn sie ueber SPALTEN gehen. Ein eindeutiger
        // Ausdrucks-Index (`UNIQUE INDEX … (UPPER(nm))`, ein verbreitetes
        // Oracle-Idiom) wird nirgends gehoben und muss deshalb hier bleiben,
        // sonst verschwaende er ganz.
        val indices = indexScan.indices
            .filterNot { it.isUnique && it.columns.size == 1 && it.expressionPositions.isEmpty() }
            .filterNot { it.isUnique && it.columns.size > 1 && it.expressionPositions.isEmpty() }
            .map { idx ->
                IndexDefinition(
                    name = idx.name,
                    columns = idx.indexColumns,
                    type = when (idx.name) {
                        in indexScan.fullTextIndexes -> IndexType.FULLTEXT
                        in indexScan.spatialIndexes -> IndexType.SPATIAL
                        else -> indexTypeOf(idx.type)
                    },
                    unique = idx.isUnique,
                )
            }
        indexScan.foreignDomainIndexes.forEach { name ->
            notes += SchemaReadNote(
                severity = SchemaReadSeverity.WARNING,
                code = "R357",
                objectName = name,
                message = "Index '$name' on table '$table' is a domain index of an index type other than " +
                    "Oracle Text (CTXSYS.CONTEXT) or Oracle Spatial (MDSYS.SPATIAL_INDEX_V2); the neutral " +
                    "model has no equivalent, so it was skipped.",
                hint = "Recreate it manually on the target, where its index type exists.",
            )
        }
        val partitioning = OraclePartitionReader.read(session, schema, table)
        partitioning?.let { notePartitionGaps(table, it, notes) }

        return TableDefinition(
            columns = columns,
            primaryKey = primaryKey,
            indices = indices,
            constraints = constraints,
            partitioning = partitioning?.config,
        )
    }

    /**
     * Ohne installiertes Oracle Spatial gibt es `ALL_SDO_GEOM_METADATA`
     * nicht — die Abfrage scheitert dann mit ORA-00942. Eine Datenbank ohne
     * Spatial hat auch keine Geometriespalten, der leere Fall ist also der
     * richtige; er wird trotzdem gemeldet, damit ein fehlendes Spatial bei
     * einer Datenbank, die welche haette, nicht still als „keine SRID"
     * durchgeht.
     */
    private fun geometryMetadata(
        session: JdbcOperations,
        schema: String,
        table: String,
        notes: MutableList<SchemaReadNote>,
    ): Map<String, OracleMetadataQueries.GeometryMetadataRow> = try {
        OracleMetadataQueries.listGeometryMetadata(session, schema, table).associateBy { it.column }
    } catch (e: Exception) {
        notes += SchemaReadNote(
            severity = SchemaReadSeverity.INFO,
            code = "R365",
            objectName = table,
            message = "ALL_SDO_GEOM_METADATA is not readable (${e.message?.lineSequence()?.firstOrNull()}); " +
                "geometry columns are read without a coordinate system.",
            hint = "Install Oracle Spatial, or grant SELECT on the metadata view.",
        )
        emptyMap()
    }

    /**
     * Die beiden Oracle-Formen, fuer die das neutrale Modell keinen Begriff
     * hat. Beide aendern das Verhalten der Zieltabelle, wenn sie beim
     * Wiedererzeugen fehlen — sie stumm wegzulassen waere der schlimmere Fall.
     */
    private fun notePartitionGaps(
        table: String,
        scan: OraclePartitionReader.PartitionScan,
        notes: MutableList<SchemaReadNote>,
    ) {
        scan.interval?.let { interval ->
            notes += SchemaReadNote(
                severity = SchemaReadSeverity.WARNING,
                code = "R355",
                objectName = table,
                message = "Table '$table' is INTERVAL-partitioned (`$interval`); the neutral model has no " +
                    "such concept, so only the partitions that exist today were read.",
                hint = "A regenerated table will not create new partitions automatically; add the INTERVAL " +
                    "clause manually if that behaviour is required.",
            )
        }
        scan.subpartitioningType?.let { sub ->
            notes += SchemaReadNote(
                severity = SchemaReadSeverity.WARNING,
                code = "R356",
                objectName = table,
                message = "Table '$table' is composite-partitioned (SUBPARTITION BY $sub); the neutral model " +
                    "carries only the top level, so the subpartitions were not read.",
                hint = "A regenerated table keeps the top-level partitioning; recreate the subpartitions manually.",
            )
        }
    }

    /**
     * `ALL_INDEXES.INDEX_TYPE` auf den neutralen Indextyp. Live gemessen kommen
     * vier Werte vor: `NORMAL`, `BITMAP` und beide mit `FUNCTION-BASED `
     * davor -- der Praefix betrifft die Schluesseldarstellung, nicht die
     * Indexart, deshalb entscheidet allein das Vorkommen von `BITMAP`.
     * `DOMAIN` behandelt bereits [OracleMetadataQueries.scanIndexes]: Oracle
     * Text kommt als [IndexType.FULLTEXT] zurueck, Oracle Spatial als
     * [IndexType.SPATIAL], jede andere Indexart wird ausgelassen. Was hier
     * ankommt, ist deshalb `NORMAL` oder `BITMAP`;
     * uebrige Arten (`IOT - TOP`, `CLUSTER`, `LOB`) sind keine neutral
     * darstellbaren Sekundaerindizes und fallen auf [IndexType.BTREE].
     */
    private fun indexTypeOf(catalogType: String?): IndexType =
        if (catalogType?.contains("BITMAP") == true) IndexType.BITMAP else IndexType.BTREE

    private fun readViews(
        session: JdbcOperations,
        schema: String,
        notes: MutableList<SchemaReadNote>,
    ): Map<String, ViewDefinition> {
        val dependencies = OracleMetadataQueries.listViewDependencies(session, schema)
        val views = OracleMetadataQueries.listViews(session, schema).associate { view ->
            view.name to ViewDefinition(
                query = view.text.trim(),
                dependencies = dependencyInfo(dependencies[view.name]),
                sourceDialect = "oracle",
            )
        }
        // Materialized Views stehen NICHT in ALL_VIEWS (gemessen) -- sie
        // kommen aus ALL_MVIEWS und tragen dieselbe neutrale Form mit
        // `materialized = true`.
        val materialized = OracleMetadataQueries.listMaterializedViews(session, schema).associate { mv ->
            if (!OracleMaterializedViewDdl.isReadable(mv.refreshMethod, mv.refreshMode)) {
                notes += SchemaReadNote(
                    severity = SchemaReadSeverity.WARNING,
                    code = "R364",
                    objectName = mv.name,
                    message = "Materialized view '${mv.name}' refreshes as " +
                        "'${mv.refreshMethod} ON ${mv.refreshMode}', which the neutral model has no term " +
                        "for; the setting is carried through verbatim and cannot be regenerated.",
                    hint = "Recreate the view manually on the target with that refresh mode.",
                )
            }
            mv.name to ViewDefinition(
                materialized = true,
                refresh = OracleMaterializedViewDdl.readRefresh(mv.refreshMethod, mv.refreshMode),
                query = mv.query,
                dependencies = dependencyInfo(dependencies[mv.name]),
                sourceDialect = "oracle",
            )
        }
        return views + materialized
    }

    /**
     * Uebersetzt die Katalogzeilen in die Projektion, auf die der Planer
     * seine Waechter stuetzt.
     *
     * `columns` bleibt leer, weil Oracle keine spaltengenaue
     * Abhaengigkeitsquelle hat — das laesst den dialektunabhaengigen
     * `VIEW_DEPENDS_ON_TABLE_LACKS_COLUMN_DEPS`-Waechter greifen, und das
     * ist die gewollte, konservative Wahl: gemessen ist, dass ein
     * Spalten-RENAME die Sichten bricht, die die Spalte nennen; fuer
     * `DropColumn`/`AlterColumnType`/`AlterColumnNullability` ist es nicht
     * gemessen, aber ohne Spalteninformation kann der Planer ohnehin nicht
     * entscheiden, ob genau diese Spalte betroffen ist.
     *
     * Keine einzige Zeile heisst nicht „keine Abhaengigkeiten", sondern
     * fehlende Sichtbarkeit — dann ist die Projektion unvollstaendig und der
     * Planer blockt `ReplaceView` statt zu raten.
     */
    private fun dependencyInfo(row: OracleMetadataQueries.ViewDependencyRow?): DependencyInfo {
        // Fehlt die View im Katalogergebnis komplett, sieht der lesende
        // Nutzer ihre Abhaengigkeiten nicht -- gemessen traegt jede View
        // mindestens eine Zeile. Das als "keine Abhaengigkeiten" zu lesen
        // waere die gefaehrliche Deutung, also wird es als unvollstaendige
        // Projektion gemeldet und der Planer blockt `ReplaceView`.
        if (row == null) {
            return DependencyInfo(
                projectionComplete = false,
                tableProjectionStatus = DependencyProjectionStatus.INCOMPLETE_PRIVILEGE,
                projectionSources = listOf(DEPENDENCY_SOURCE),
            )
        }
        return DependencyInfo(
            tables = row.tables,
            views = row.views,
            tableProjectionStatus = tableProjectionStatus(row),
            projectionSources = listOf(DEPENDENCY_SOURCE),
        )
    }

    /**
     * `EMPTY_VERIFIED` darf nur stehen, wenn im eigenen Schema
     * **wirklich nichts** referenziert wird. Eine View, die ihre Tabelle
     * ueber ein Synonym erreicht, traegt dagegen eine In-Schema-Zeile, die
     * nur nicht auf `tables`/`views` abbildbar ist — sie als verifiziert
     * leer zu melden hiesse, den Reprojector beim Rename nichts finden zu
     * lassen und die Sicht still invalid zurueckzulassen.
     */
    private fun tableProjectionStatus(
        row: OracleMetadataQueries.ViewDependencyRow,
    ): DependencyProjectionStatus = when {
        row.tables.isNotEmpty() || row.views.isNotEmpty() -> DependencyProjectionStatus.COMPLETE
        row.unmappedInSchema > 0 -> DependencyProjectionStatus.UNKNOWN
        else -> DependencyProjectionStatus.EMPTY_VERIFIED
    }

    private fun readSequences(
        session: JdbcOperations,
        schema: String,
        notes: MutableList<SchemaReadNote>,
    ): Map<String, SequenceDefinition> =
        OracleMetadataQueries.listSequences(session, schema).associate { seq ->
            // Oracle BEWAHRT den urspruenglichen START WITH-Wert nicht auf:
            // ALL_SEQUENCES hat keine solche Spalte, und selbst
            // DBMS_METADATA.GET_DDL rekonstruiert ihn nicht, sondern schreibt
            // den aktuellen Stand hin (gemessen: START WITH 42, zweimal
            // gezogen -> GET_DDL meldet START WITH 44). Gefuehrt wird nur
            // LAST_NUMBER, der naechste auszugebende Wert. Fuer frische,
            // ungezogene Sequenzen entspricht das dem Start -- auch bei
            // CACHE n, das erst mit der ersten Ziehung vorspringt; sonst ist
            // es der aktuelle Stand, keine historische Wahrheit.
            notes += SchemaReadNote(
                severity = SchemaReadSeverity.INFO,
                code = "R345",
                objectName = seq.name,
                message = "Oracle does not retain the original START WITH value; " +
                    "the current LAST_NUMBER was read as start instead.",
            )
            seq.name to SequenceDefinition(
                start = seq.lastNumber,
                increment = seq.increment,
                minValue = seq.minValue,
                maxValue = seq.maxValue,
                cycle = seq.cycle,
                cache = seq.cache,
            )
        }

    private fun noteUnreadObjects(
        session: JdbcOperations,
        schema: String,
        options: SchemaReadOptions,
        notes: MutableList<SchemaReadNote>,
        skipped: MutableList<SkippedObject>,
    ) {
        // Nur noch Packages: Funktionen, Prozeduren und Trigger liest
        // [OracleRoutineReader], der auch selbst meldet, was er von ihnen
        // uebergeht. Ein Package hat dagegen keine Entsprechung im neutralen
        // Modell — es gruppiert Routinen, die dort einzeln stehen.
        if (!options.includeProcedures) return
        val unread = OracleMetadataQueries.listUnreadPackages(session, schema)
        unread.forEach { name ->
            skipped += SkippedObject(
                type = "procedure",
                name = name,
                reason = "PL/SQL packages have no neutral representation (ADR 0052).",
                code = "R342",
            )
        }
        if (unread.isNotEmpty()) {
            notes += SchemaReadNote(
                severity = SchemaReadSeverity.WARNING,
                code = "R342",
                objectName = schema,
                message = "${unread.size} PL/SQL package(s) exist but are not read: the neutral model " +
                    "groups no routines (ADR 0052): ${unread.joinToString(", ")}.",
            )
        }
    }

    private companion object {
        const val DEPENDENCY_SOURCE = "ALL_DEPENDENCIES"
    }
}
