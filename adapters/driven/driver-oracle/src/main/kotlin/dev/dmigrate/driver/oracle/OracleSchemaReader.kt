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
 * Indizes, CHECK-Constraints), native Sequenzen und Views des aktuellen
 * Schemas (= aktueller User), gelesen aus `all_*`-Katalogsichten.
 *
 * Routinen, Trigger und Packages werden noch nicht gelesen; vorhandene
 * Objekte erscheinen als [SkippedObject]s plus einer `R342`-Notiz, damit
 * die Lücke sichtbar statt still ist (Rollout:
 * docs/planning/in-progress/oracle-dialect-scoping.md).
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
            val views = if (options.includeViews) readViews(session, schema) else emptyMap()
            val sequences = readSequences(session, schema, notes)
            noteUnreadObjects(session, schema, options, notes, skipped)

            return SchemaReadResult(
                schema = SchemaDefinition(
                    name = ReverseScopeCodec.oracleName(schema),
                    version = ReverseScopeCodec.REVERSE_VERSION,
                    tables = tables,
                    views = views,
                    sequences = sequences,
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
        // column.unique gehoben, mehrspaltige auf eine UNIQUE-Constraint.
        val indices = indexScan.indices
            .filterNot { it.isUnique && it.columns.size == 1 }
            .filterNot { it.isUnique && it.columns.size > 1 }
            .map { idx ->
                IndexDefinition(
                    name = idx.name,
                    columns = idx.indexColumns,
                    type = indexTypeOf(idx.type),
                    unique = idx.isUnique,
                )
            }
        indexScan.expressionIndexes.forEach { name ->
            notes += SchemaReadNote(
                severity = SchemaReadSeverity.WARNING,
                code = "R354",
                objectName = name,
                message = "Index '$name' on table '$table' is function-based over an expression, " +
                    "which the neutral model cannot represent; the index was skipped.",
                hint = "Recreate it manually on the target, or index a generated column instead.",
            )
        }

        return TableDefinition(
            columns = columns,
            primaryKey = primaryKey,
            indices = indices,
            constraints = constraints,
        )
    }

    /**
     * `ALL_INDEXES.INDEX_TYPE` auf den neutralen Indextyp. Live gemessen kommen
     * vier Werte vor: `NORMAL`, `BITMAP` und beide mit `FUNCTION-BASED `
     * davor -- der Praefix betrifft die Schluesseldarstellung, nicht die
     * Indexart, deshalb entscheidet allein das Vorkommen von `BITMAP`.
     * Andere Arten (`DOMAIN`, `IOT - TOP`, `CLUSTER`, `LOB`) fuehrt Oracle zwar
     * ebenfalls, keine davon ist ein neutral darstellbarer Sekundaerindex; sie
     * fallen auf [IndexType.BTREE].
     */
    private fun indexTypeOf(catalogType: String?): IndexType =
        if (catalogType?.contains("BITMAP") == true) IndexType.BITMAP else IndexType.BTREE

    private fun readViews(
        session: JdbcOperations,
        schema: String,
    ): Map<String, ViewDefinition> {
        val dependencies = OracleMetadataQueries.listViewDependencies(session, schema)
        return OracleMetadataQueries.listViews(session, schema).associate { view ->
            view.name to ViewDefinition(
                query = view.text.trim(),
                dependencies = dependencyInfo(dependencies[view.name]),
                sourceDialect = "oracle",
            )
        }
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
        val kindOf = mapOf(
            "PROCEDURE" to "procedure",
            "FUNCTION" to "function",
            "TRIGGER" to "trigger",
            "PACKAGE" to "procedure",
        )
        val wanted = { kind: String ->
            when (kind) {
                "procedure" -> options.includeProcedures
                "function" -> options.includeFunctions
                else -> options.includeTriggers
            }
        }
        val unread = OracleMetadataQueries.listUnreadObjects(session, schema)
            .mapNotNull { obj -> kindOf[obj.type]?.let { kind -> kind to obj.name } }
            .filter { (kind, _) -> wanted(kind) }
        unread.forEach { (kind, name) ->
            skipped += SkippedObject(
                type = kind,
                name = name,
                reason = "Not read for oracle (Oracle rollout, ADR 0052).",
                code = "R342",
            )
        }
        unread.groupBy({ it.first }, { it.second }).forEach { (kind, names) ->
            notes += SchemaReadNote(
                severity = SchemaReadSeverity.WARNING,
                code = "R342",
                objectName = schema,
                message = "${names.size} $kind object(s) exist but are not read for oracle " +
                    "(Oracle rollout, ADR 0052): ${names.joinToString(", ")}.",
            )
        }
    }

    private companion object {
        const val DEPENDENCY_SOURCE = "ALL_DEPENDENCIES"
    }
}
