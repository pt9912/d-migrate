package dev.dmigrate.driver.mssql

import dev.dmigrate.core.identity.ReverseScopeCodec
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.ParameterDirection
import dev.dmigrate.core.model.ReturnType
import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
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
            val routines = readRoutines(session, schema, options, notes, skipped)
            noteUnreadObjects(session, schema, options, notes, skipped)

            return SchemaReadResult(
                schema = SchemaDefinition(
                    name = ReverseScopeCodec.mssqlName(database, schema),
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

        // Ein ungefilterter Unique-Index wird zu `column.unique` bzw. einem
        // UNIQUE-Constraint gehoben. Beansprucht er aber die Ablage der Tabelle
        // oder schliesst er Spalten ein, laesst sich das als Constraint nicht
        // ausdruecken -- gehoben ginge beides verloren, und was `schema generate`
        // als `CREATE UNIQUE CLUSTERED INDEX … INCLUDE (…)` schreibt, kaeme nie
        // zurueck. Solche Indizes bleiben deshalb Indizes.
        val liftable = indexScan.indices.filter { !it.clustered && it.includeColumns.isEmpty() }

        val singleColumnUnique = SchemaReaderUtils.singleColumnUniqueFromIndices(liftable)
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
            SchemaReaderUtils.buildMultiColumnUniqueFromIndices(liftable) +
            SchemaReaderUtils.buildCheckConstraints(checks)

        // Einspaltige, ungefilterte Unique-Indizes sind bereits auf column.unique
        // gehoben. Der Zugriffsmethoden-Typ bleibt BTREE: SQL Server kennt keine
        // waehlbaren Methoden wie PostgreSQL, `type` unterscheidet dort clustered
        // von nonclustered — das traegt `clustered`, nicht `IndexType`.
        val lifted = liftable.filter { it.isUnique && it.where == null }.toSet()
        val indices = indexScan.indices
            .filterNot { it in lifted }
            .map { idx ->
                IndexDefinition(
                    name = idx.name,
                    columns = idx.indexColumns,
                    type = IndexType.BTREE,
                    unique = idx.isUnique,
                    where = idx.where,
                    includeColumns = idx.includeColumns,
                    clustered = idx.clustered,
                )
            }

        return TableDefinition(
            columns = columns,
            primaryKey = primaryKey,
            indices = indices + readFullText(session, qualified, table, notes),
            constraints = constraints,
            partitioning = readPartitioning(session, qualified, table, notes),
        )
    }

    /**
     * Partitionierung aus `sys.partition_*` ins neutrale Modell.
     *
     * Zwei Dinge gehen dabei nicht eins zu eins:
     *
     * **Die Kinder haben keine Namen.** SQL Server nummeriert sie; PostgreSQL
     * und MySQL benennen sie. Der Reverse vergibt deshalb `p1`, `p2`, … in
     * Grenzreihenfolge und meldet das mit `R346` -- die urspruenglichen Namen
     * stehen nicht in der Datenbank und lassen sich nicht erraten. Wer sie
     * braucht, liefert sie als Overlay nach (siehe Plan).
     *
     * **`RANGE LEFT` liefert keine Grenzen.** Das neutrale Modell beschreibt eine
     * Partition als halboffenes Intervall `[from, to)` -- das ist genau
     * `RANGE RIGHT`. Bei `LEFT` gehoert der Grenzwert zur unteren Partition.
     *
     * Umrechenbar waere die Verschiebung fuer die meisten Schluesseltypen
     * durchaus: `LEFT (10)` auf `int` ist wertgleich mit `RIGHT (11)`, und die
     * noetige Granularitaet steht in `sys.partition_parameters` bzw. in den
     * Spalten, die [readTable] ohnehin schon gelesen hat. Fuer Zeichenketten
     * (kollationsabhaengig) und Gleitkomma gilt das nicht.
     *
     * Dieser Sub-Slice rechnet trotzdem nicht um: der Rueckweg braucht die
     * inverse Umrechnung im Generate-Pfad, und beide zusammen gehoeren in
     * denselben Schnitt. Bis dahin traegt das Modell die Tatsache der
     * Partitionierung -- Strategie und Schluessel -- **ohne Kinder**, statt
     * `null`. Der Unterschied ist nicht kosmetisch: `MssqlRebuildRenderer`
     * blockt einen Tabellen-Neubau auf `partitioning != null`, weil er die
     * Partitionierung sonst still abraeumte. Mit `null` waere genau dieser
     * Waechter blind.
     */
    /**
     * Der Volltext-Index einer Tabelle, oder eine leere Liste.
     *
     * SQL Server benennt Volltext-Indizes nicht — `CREATE FULLTEXT INDEX ON t`
     * kennt keinen Namen. Der Reverse vergibt deshalb `ft_<tabelle>` und meldet
     * das; der urspruengliche Name steht nicht in der Datenbank.
     */
    private fun readFullText(
        session: JdbcOperations,
        qualified: String,
        table: String,
        notes: MutableList<SchemaReadNote>,
    ): List<IndexDefinition> {
        val columns = MssqlMetadataQueries.scanFullTextColumns(session, qualified)
        if (columns.isEmpty()) return emptyList()

        notes += SchemaReadNote(
            severity = SchemaReadSeverity.INFO,
            code = "R348",
            objectName = table,
            message = "SQL Server does not name full-text indexes; the one on '$table' was named " +
                "'ft_$table'. The original name is not stored in the database.",
        )
        return listOf(
            IndexDefinition(
                name = "ft_$table",
                columns = columns.map { IndexColumn(it) },
                type = IndexType.FULLTEXT,
            ),
        )
    }

    private fun readPartitioning(
        session: JdbcOperations,
        qualified: String,
        table: String,
        notes: MutableList<SchemaReadNote>,
    ): PartitionConfig? {
        val scan = MssqlMetadataQueries.scanPartitioning(session, qualified) ?: return null

        if (!scan.boundaryOnRight) {
            notes += SchemaReadNote(
                severity = SchemaReadSeverity.ACTION_REQUIRED,
                code = "R347",
                objectName = table,
                message = "Table '$table' is partitioned with RANGE LEFT (function " +
                    "'${scan.functionName}'); the neutral model describes a partition as the " +
                    "half-open interval [from, to), which is RANGE RIGHT. The boundary shift " +
                    "boundary shift is not converted in this slice, so the partitions are read " +
                    "without bounds. The table is still marked as partitioned, which keeps the " +
                    "table-rebuild guard effective.",
            )
            return PartitionConfig(type = PartitionType.RANGE, key = listOf(scan.column))
        }

        notes += SchemaReadNote(
            severity = SchemaReadSeverity.INFO,
            code = "R346",
            objectName = table,
            message = "SQL Server numbers partitions; the ${scan.boundaries.size + 1} partitions of " +
                "'$table' were named p1…p${scan.boundaries.size + 1} in boundary order. The original " +
                "names are not stored in the database.",
        )

        // n Grenzwerte ergeben n+1 Partitionen: (-inf, b1), [b1, b2), …, [bn, +inf).
        val bounds: List<PartitionBound> = scan.boundaries.map { PartitionBound.Value(it) }
        val partitions = (0..scan.boundaries.size).map { i ->
            PartitionDefinition(
                name = "p${i + 1}",
                from = listOf(if (i == 0) PartitionBound.MinValue else bounds[i - 1]),
                to = listOf(if (i == scan.boundaries.size) PartitionBound.MaxValue else bounds[i]),
            )
        }
        return PartitionConfig(type = PartitionType.RANGE, key = listOf(scan.column), partitions = partitions)
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

    /** Die gelesenen Routinen, nach Art getrennt. */
    private data class Routines(
        val functions: Map<String, FunctionDefinition> = emptyMap(),
        val procedures: Map<String, ProcedureDefinition> = emptyMap(),
        val triggers: Map<String, TriggerDefinition> = emptyMap(),
    )

    /**
     * Funktionen, Prozeduren und Trigger samt Rumpf.
     *
     * Der Rumpf ist die vollstaendige `CREATE`-Anweisung, wie SQL Server sie
     * gespeichert hat — nicht nur der Block dazwischen. `sourceDialect` haelt
     * fest, woher er stammt: auf einem anderen Ziel ist er nicht gueltig.
     */
    private fun readRoutines(
        session: JdbcOperations,
        schema: String,
        options: SchemaReadOptions,
        notes: MutableList<SchemaReadNote>,
        skipped: MutableList<SkippedObject>,
    ): Routines {
        val rows = MssqlMetadataQueries.listRoutines(session, schema)
        val paramsByRoutine = MssqlMetadataQueries.listRoutineParameters(session, schema)
            .groupBy { it.routine }
        val functions = mutableMapOf<String, FunctionDefinition>()
        val procedures = mutableMapOf<String, ProcedureDefinition>()
        val triggers = mutableMapOf<String, TriggerDefinition>()

        for (row in rows) {
            // Der Rumpf ist das, was hinter dem einleitenden `AS` steht; Signatur
            // und Trigger-Angaben stehen im Modell als eigene Felder daneben.
            // Laesst sich der Schnitt nicht sicher setzen, wird gemeldet statt
            // geraten.
            val body = MssqlRoutineBody.extract(row.definition)
            if (body == null) {
                val kind = when (row.type) {
                    "P" -> "procedure"
                    "TR" -> "trigger"
                    else -> "function"
                }
                skipped += SkippedObject(
                    type = kind,
                    name = row.name,
                    reason = "The stored definition carries no top-level AS, so its body cannot be separated " +
                        "from the signature.",
                    code = "R349",
                )
                notes += SchemaReadNote(
                    severity = SchemaReadSeverity.WARNING,
                    code = "R349",
                    objectName = row.name,
                    message = "The $kind '${row.name}' was skipped: its stored definition carries no " +
                        "top-level AS, so the body cannot be separated from the signature.",
                )
                continue
            }
            val params = paramsByRoutine[row.name].orEmpty()
            when (row.type) {
                "P" -> if (options.includeProcedures) {
                    procedures[row.name] = ProcedureDefinition(
                        parameters = parametersOf(params),
                        body = body,
                        language = "sql",
                        sourceDialect = "mssql",
                    )
                }
                "FN", "IF", "TF" -> if (options.includeFunctions) {
                    functions[row.name] = FunctionDefinition(
                        parameters = parametersOf(params),
                        returns = params.firstOrNull { it.isReturnValue }?.let { ReturnType(it.typeName) },
                        body = body,
                        language = "sql",
                        sourceDialect = "mssql",
                    )
                }
                "TR" -> if (options.includeTriggers) {
                    val table = row.table ?: continue
                    triggers[row.name] = TriggerDefinition(
                        table = table,
                        events = triggerEvents(row),
                        // SQL Server kennt kein BEFORE: `AFTER` und
                        // `INSTEAD OF` sind die beiden Zeitpunkte.
                        timing = if (row.isInsteadOf) TriggerTiming.INSTEAD_OF else TriggerTiming.AFTER,
                        // T-SQL-Trigger feuern je Anweisung, nicht je Zeile.
                        forEach = TriggerForEach.STATEMENT,
                        body = body,
                        sourceDialect = "mssql",
                    )
                }
            }
        }
        return Routines(functions, procedures, triggers)
    }

    /** Der Rueckgabewert steht in `sys.parameters` mit `parameter_id = 0` und zaehlt nicht als Parameter. */
    private fun parametersOf(rows: List<MssqlMetadataQueries.RoutineParamRow>): List<ParameterDefinition> =
        rows.filterNot { it.isReturnValue }.map { p ->
            ParameterDefinition(
                name = p.name,
                type = p.typeName,
                direction = if (p.isOutput) ParameterDirection.OUT else ParameterDirection.IN,
            )
        }

    private fun triggerEvents(row: MssqlMetadataQueries.RoutineRow): Set<TriggerEvent> = buildSet {
        if (row.isInsert) add(TriggerEvent.INSERT)
        if (row.isUpdate) add(TriggerEvent.UPDATE)
        if (row.isDelete) add(TriggerEvent.DELETE)
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
            "PC" to "procedure",
            "FN" to "function",
            "FS" to "function",
            "FT" to "function",
            "IF" to "function",
            "TF" to "function",
            "TR" to "trigger",
            "TA" to "trigger",
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
                reason = "No readable T-SQL body: the routine is CLR-based or was created WITH ENCRYPTION.",
                code = "R342",
            )
        }
        unread.groupBy({ it.first }, { it.second }).forEach { (kind, names) ->
            notes += SchemaReadNote(
                severity = SchemaReadSeverity.WARNING,
                code = "R342",
                objectName = schema,
                message = "${names.size} $kind object(s) carry no readable T-SQL body (CLR-based or " +
                    "created WITH ENCRYPTION) and were skipped: ${names.joinToString(", ")}.",
            )
        }
    }
}
