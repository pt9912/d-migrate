package dev.dmigrate.driver.mssql

import dev.dmigrate.core.identity.ReverseScopeCodec
import dev.dmigrate.core.model.ColumnDefinition
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
 * MSSQL [SchemaReader]: tables (columns, PK, FKs, unique, indexes
 * including filtered ones, CHECK constraints), native sequences and
 * views of the connection's default schema, read from `sys.*` catalog
 * views.
 *
 * Routines and triggers are not read yet; existing objects surface as
 * [SkippedObject]s plus an `R342` note so the gap is visible instead of
 * silent (rollout: docs/planning/in-progress/mssql-dialect-scoping.md).
 */
class MssqlSchemaReader(
    private val jdbcFactory: (Connection) -> JdbcOperations = ::JdbcMetadataSession,
) : SchemaReader {

    override fun read(pool: ConnectionPool, options: SchemaReadOptions): SchemaReadResult {
        pool.borrow().asJdbc().use { conn ->
            val session = jdbcFactory(conn)
            val database = MssqlIdentifiers.currentDatabase(conn)
            val schema = MssqlIdentifiers.currentSchema(session)
            val notes = mutableListOf<SchemaReadNote>()
            val skipped = mutableListOf<SkippedObject>()

            val tables = readTables(session, schema, notes)
            val views = if (options.includeViews) readViews(session, schema, notes) else emptyMap()
            val sequences = readSequences(session, schema)
            noteUnreadObjects(session, schema, options, notes, skipped)

            return SchemaReadResult(
                schema = SchemaDefinition(
                    name = ReverseScopeCodec.mssqlName(database, schema),
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
        MssqlMetadataQueries.listTableRefs(session, schema).associate { ref ->
            ref.name to readTable(session, schema, ref.name, notes)
        }

    private fun readTable(
        session: JdbcOperations,
        schema: String,
        table: String,
        notes: MutableList<SchemaReadNote>,
    ): TableDefinition {
        val qualified = MssqlIdentifiers.qualified(schema, table)
        val columnRows = MssqlMetadataQueries.listColumns(session, qualified)
        val primaryKey = MssqlMetadataQueries.listPrimaryKeyColumns(session, qualified)
        val foreignKeys = MssqlMetadataQueries.listForeignKeys(session, qualified)
        val indexScan = MssqlMetadataQueries.scanIndexes(session, qualified)
        val checks = MssqlMetadataQueries.listCheckConstraints(session, qualified)

        indexScan.indexesWithIncludedColumns.forEach { indexName ->
            notes += SchemaReadNote(
                severity = SchemaReadSeverity.INFO,
                code = "R341",
                objectName = "$table.$indexName",
                message = "Index INCLUDE columns are not carried in the neutral model; " +
                    "only the key columns were read.",
            )
        }

        val singleColumnUnique = SchemaReaderUtils.singleColumnUniqueFromIndices(indexScan.indices)
        val pkColumns = primaryKey.toSet()

        val columns = columnRows.associate { row ->
            val mapping = MssqlTypeMapping.mapColumn(
                "$table.${row.name}",
                MssqlTypeMapping.ColumnInput(
                    typeName = row.typeName,
                    maxLength = row.maxLength,
                    precision = row.precision,
                    scale = row.scale,
                    isIdentity = row.isIdentity,
                ),
            )
            mapping.note?.let { notes += it }
            if (row.isIdentity && (row.identitySeed ?: 1L) to (row.identityIncrement ?: 1L) != 1L to 1L) {
                notes += SchemaReadNote(
                    severity = SchemaReadSeverity.WARNING,
                    code = "R340",
                    objectName = "$table.${row.name}",
                    message = "IDENTITY(${row.identitySeed}, ${row.identityIncrement}) seed/increment " +
                        "is not carried in the neutral model; generate renders IDENTITY(1,1).",
                )
            }
            if (row.isComputed) {
                notes += SchemaReadNote(
                    severity = SchemaReadSeverity.ACTION_REQUIRED,
                    code = "R343",
                    objectName = "$table.${row.name}",
                    message = "Computed column definition ${row.computedDefinition ?: "?"} " +
                        "is not carried in the neutral model; the column was read as a plain column.",
                    hint = "Recreate the computed expression manually on the target.",
                )
            }
            row.name to ColumnDefinition(
                type = mapping.type,
                // PK-Spalten folgen der Reverse-Konvention required=false/
                // unique=false — PK impliziert beides (MySQL-Präzedenz).
                required = !row.nullable && row.name !in pkColumns,
                unique = row.name in singleColumnUnique && row.name !in pkColumns,
                default = if (row.isIdentity || row.isComputed) {
                    null
                } else {
                    MssqlTypeMapping.parseDefault(row.defaultDefinition, mapping.type)
                },
                generation = mapping.generation,
                ordinal = row.ordinal,
            )
        }

        val constraints = SchemaReaderUtils.buildForeignKeyConstraints(foreignKeys) +
            SchemaReaderUtils.buildMultiColumnUniqueFromIndices(indexScan.indices) +
            SchemaReaderUtils.buildCheckConstraints(checks)

        // Einspaltige, ungefilterte Unique-Indizes sind bereits auf
        // column.unique gehoben; clustered/nonclustered-Steuerung liest erst
        // der Index-Slice des Plans — bis dahin ist alles BTREE.
        val indices = indexScan.indices
            .filterNot { it.isUnique && it.columns.size == 1 && it.where == null }
            .filterNot { it.isUnique && it.columns.size > 1 && it.where == null }
            .map { idx ->
                IndexDefinition(
                    name = idx.name,
                    columns = idx.indexColumns,
                    type = IndexType.BTREE,
                    unique = idx.isUnique,
                    where = idx.where,
                )
            }

        return TableDefinition(
            columns = columns,
            primaryKey = primaryKey,
            indices = indices,
            constraints = constraints,
        )
    }

    private fun readViews(
        session: JdbcOperations,
        schema: String,
        notes: MutableList<SchemaReadNote>,
    ): Map<String, ViewDefinition> =
        MssqlMetadataQueries.listViews(session, schema).associate { view ->
            val query = MssqlViewDefinitionScanner.queryOf(view.definition)
            if (query == null) {
                notes += SchemaReadNote(
                    severity = SchemaReadSeverity.WARNING,
                    code = "R344",
                    objectName = view.name,
                    message = "Could not isolate the SELECT query of view '${view.name}'; " +
                        "the full CREATE VIEW definition was carried instead.",
                )
            }
            view.name to ViewDefinition(
                query = query ?: view.definition,
                sourceDialect = "mssql",
            )
        }

    private fun readSequences(session: JdbcOperations, schema: String): Map<String, SequenceDefinition> =
        MssqlMetadataQueries.listSequences(session, schema).associate { seq ->
            val (typeMin, typeMax) = sequenceTypeBounds(seq.typeName)
            seq.name to SequenceDefinition(
                start = seq.start,
                increment = seq.increment,
                minValue = seq.minValue?.takeUnless { it == typeMin },
                maxValue = seq.maxValue?.takeUnless { it == typeMax },
                cycle = seq.cycle,
                cache = seq.cache,
            )
        }

    // sys.sequences meldet min/max immer — die Typgrenzen sind der
    // "kein explizites MINVALUE/MAXVALUE"-Default und bleiben im Modell null.
    private fun sequenceTypeBounds(typeName: String): Pair<Long?, Long?> =
        when (typeName.lowercase()) {
            "bigint" -> Long.MIN_VALUE to Long.MAX_VALUE
            "int" -> Int.MIN_VALUE.toLong() to Int.MAX_VALUE.toLong()
            "smallint" -> Short.MIN_VALUE.toLong() to Short.MAX_VALUE.toLong()
            "tinyint" -> 0L to 255L
            else -> null to null
        }

    private fun noteUnreadObjects(
        session: JdbcOperations,
        schema: String,
        options: SchemaReadOptions,
        notes: MutableList<SchemaReadNote>,
        skipped: MutableList<SkippedObject>,
    ) {
        val kindOf = mapOf(
            "P" to "procedure",
            "FN" to "function",
            "IF" to "function",
            "TF" to "function",
            "TR" to "trigger",
        )
        val wanted = { kind: String ->
            when (kind) {
                "procedure" -> options.includeProcedures
                "function" -> options.includeFunctions
                else -> options.includeTriggers
            }
        }
        val unread = MssqlMetadataQueries.listUnreadObjects(session, schema)
            .mapNotNull { obj -> kindOf[obj.type]?.let { kind -> kind to obj.name } }
            .filter { (kind, _) -> wanted(kind) }
        unread.forEach { (kind, name) ->
            skipped += SkippedObject(
                type = kind,
                name = name,
                reason = "Not read for mssql (MSSQL rollout, ADR 0047).",
                code = "R342",
            )
        }
        unread.groupBy({ it.first }, { it.second }).forEach { (kind, names) ->
            notes += SchemaReadNote(
                severity = SchemaReadSeverity.WARNING,
                code = "R342",
                objectName = schema,
                message = "${names.size} $kind object(s) exist but are not read for mssql " +
                    "(MSSQL rollout, ADR 0047): ${names.joinToString(", ")}.",
            )
        }
    }
}
