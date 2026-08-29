package dev.dmigrate.driver.mssql

import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.core.model.AggregateDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.ReferentialAction
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.core.model.inOrdinalOrder
import dev.dmigrate.driver.AbstractDdlGenerator
import dev.dmigrate.driver.CircularFkEdge
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.MssqlHashPartitionMode
import dev.dmigrate.driver.mssqlContext
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.driver.DdlStatement
import dev.dmigrate.driver.DeferredForeignKey
import dev.dmigrate.driver.DeferredForeignKeyDdlSupport
import dev.dmigrate.driver.ManualActionRequired
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.SkippedObject
import dev.dmigrate.driver.SpatialProfile
import dev.dmigrate.driver.SqlIdentifiers
import dev.dmigrate.driver.TransformationNote
import dev.dmigrate.driver.ViewQueryTransformer

/**
 * T-SQL [dev.dmigrate.driver.DdlGenerator] für SQL Server 2017+ ([ADR 0047]).
 *
 * Geltungsbereich: Tabellen (Spalten, Identity, benannte DEFAULT/UNIQUE/
 * CHECK/PK, FKs inkl. zirkulärer und aufgeschobener), Indizes (inkl.
 * gefilterter), native Sequenzen (`CREATE SEQUENCE`, `NEXT VALUE FOR`),
 * Views (`CREATE OR ALTER VIEW`), Custom Types (Enum/Domain inline,
 * Composite E054), Spatial (Profil `native` → `geography`/`geometry`),
 * Routinen und Trigger mit T-SQL-Rumpf (`CREATE OR ALTER FUNCTION` /
 * `… PROCEDURE` / `… TRIGGER`). Fremde Rümpfe, Trigger-Formen, die T-SQL nicht
 * kennt, und schemaweite Trigger-Namenskollisionen landen als E053, Aggregate
 * (CLR-Assembly nötig) als E054; Partitionierung wird als E055 ausgewiesen
 * (Tabelle plain); planare Spatial-/Volltext-Indizes und Schlüssel auf
 * LOB-Spalten als E057. Render-Regeln: `spec/ddl-generation-rules.md`
 * (Abschnitte MSSQL), Typtabelle: `spec/type-mapping.md`.
 */
class MssqlDdlGenerator private constructor(
    private val mssqlTypeMapper: MssqlTypeMapper,
) : AbstractDdlGenerator(mssqlTypeMapper), DeferredForeignKeyDdlSupport {

    constructor() : this(MssqlTypeMapper())

    override val dialect = DatabaseDialect.MSSQL

    override val supportsDeferredForeignKeys: Boolean = true

    /** Schema des laufenden `generate()`; [generateIndices] braucht es für die Enum-/Domain-Auflösung. */
    private var currentSchema: SchemaDefinition? = null

    /** Kaskaden-Zyklus-/Mehrfachpfad-Analyse des laufenden `generate()` (SQL-Server-Fehler 1785). */
    private var cascadeGuard: MssqlCascadePathGuard = MssqlCascadePathGuard.NONE

    private val typeResolver = MssqlColumnTypeResolver(mssqlTypeMapper)
    private val columnHelper =
        MssqlColumnConstraintHelper(::quoteIdentifier, mssqlTypeMapper, typeResolver, ::mssqlReferentialActionSql)
    private val indexHelper = MssqlIndexDdlHelper(::quoteIdentifier, mssqlTypeMapper)
    private val tablePartitioning = MssqlTablePartitioning(columnHelper, ::quoteIdentifier)

    override fun generate(schema: SchemaDefinition, options: DdlGenerationOptions): DdlResult {
        currentSchema = schema
        cascadeGuard = MssqlCascadePathGuard.analyse(schema)
        return super.generate(schema, options)
    }

    // ── Quoting ──────────────────────────────────

    override fun quoteIdentifier(name: String): String = SqlIdentifiers.quoteIdentifier(name, dialect)

    override fun canGenerateSpatial(profile: SpatialProfile): Boolean = profile == SpatialProfile.NATIVE

    /**
     * T-SQL kennt kein `RESTRICT`; ohne aufschiebbare Constraints ist
     * `NO ACTION` in SQL Server semantisch dasselbe (Prüfung am Statement-Ende).
     */
    private fun mssqlReferentialActionSql(action: ReferentialAction): String = when (action) {
        ReferentialAction.RESTRICT, ReferentialAction.NO_ACTION -> "NO ACTION"
        else -> referentialActionSql(action)
    }

    // ── Custom types ─────────────────────────────

    override fun generateCustomTypes(types: Map<String, CustomTypeDefinition>): List<DdlStatement> =
        types.mapNotNull { (name, typeDef) ->
            when (typeDef.kind) {
                // Enum/Domain werden an der Spalte gerendert (NVARCHAR + CHECK bzw. Basistyp + CHECK).
                CustomTypeKind.ENUM, CustomTypeKind.DOMAIN -> null
                CustomTypeKind.COMPOSITE -> DdlStatement(
                    "",
                    listOf(
                        ManualActionRequired(
                            code = "E054", objectType = "composite_type", objectName = name,
                            reason = "Composite type '$name' is not supported in SQL Server and was skipped.",
                            hint = "Flatten the fields into columns or store the value as JSON text.",
                        ).toNote(),
                    ),
                )
            }
        }

    // ── Sequences ────────────────────────────────

    override fun generateSequences(
        schema: SchemaDefinition,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> = schema.sequences.map { (name, seq) -> generateSequence(name, seq) }

    private fun generateSequence(name: String, seq: SequenceDefinition): DdlStatement =
        DdlStatement(MssqlSequenceDdl.createSql(name, seq))

    // ── Tables ───────────────────────────────────

    override fun generateTable(
        name: String,
        table: TableDefinition,
        schema: SchemaDefinition,
        deferredFks: Set<Pair<String, String>>,
        deferredConstraints: Set<Pair<String, String>>,
        options: DdlGenerationOptions,
    ): List<DdlStatement> {
        val notes = mutableListOf<TransformationNote>()
        val lines = mutableListOf<String>()

        // Sub-Slice 7d: HASH-Emulation. Sie greift VOR allem anderen, weil sie
        // die eindeutigen Schluessel der Tabelle veraendert — SQL Server
        // verlangt die Partitionsspalte in jedem davon. Ab hier arbeitet die
        // Methode mit `effective`, nicht mit `table`.
        val hashOutcome = resolveHashPartitionPlan(
            name, table, options.mssqlContext?.hashPartitionMode ?: MssqlHashPartitionMode.ACTION_REQUIRED,
            ::quoteIdentifier, schema,
        )
        val hashPlan = (hashOutcome as? MssqlHashPartitionOutcome.Planned)?.plan
        if (hashOutcome is MssqlHashPartitionOutcome.Refused) {
            notes += ManualActionRequired(
                code = hashOutcome.code, objectType = "partitioning", objectName = name,
                reason = hashOutcome.reason, hint = hashOutcome.hint,
            ).toNote()
        }
        val effective = hashPlan?.table ?: table
        val lobColumns = typeResolver.lobColumns(effective, schema)

        for ((colName, col) in effective.columns.inOrdinalOrder()) {
            lines += columnHelper.generateColumnSql(name, colName, col, effective, schema, notes)
        }
        // Die berechnete Spalte steht nicht im neutralen Modell; ihre Zeile
        // kommt direkt aus der Emulation.
        hashPlan?.let { lines += it.bucketLine }

        for ((colName, col) in effective.columns.inOrdinalOrder()) {
            val ref = col.references ?: continue
            if (options.deferForeignKeys) continue
            if ((name to colName) in deferredFks) continue
            lines += columnHelper.buildForeignKeyClause(
                cascadeGuard,
                MssqlColumnConstraintHelper.ForeignKeySpec(
                    "fk_${name}_$colName", name, listOf(colName), ref.table, listOf(ref.column),
                    ref.onDelete, ref.onUpdate,
                ),
                notes,
            )
        }

        for (constraint in effective.constraints) {
            if (options.deferForeignKeys && constraint.type == ConstraintType.FOREIGN_KEY) continue
            if ((name to constraint.name) in deferredConstraints) continue
            columnHelper.generateConstraintClause(cascadeGuard, name, table, constraint, lobColumns, notes)
                ?.let { lines += it }
        }

        if (effective.primaryKey.isNotEmpty()) {
            val lobKeys = effective.primaryKey.filter { it in lobColumns }
            if (lobKeys.isNotEmpty()) {
                notes += columnHelper.lobKeyNote(name, MssqlConstraintNames.primaryKey(name), "PRIMARY KEY", lobKeys)
            } else {
                val pkCols = effective.primaryKey.joinToString(", ") { quoteIdentifier(it) }
                val pkClause = MssqlClusteredStorage.primaryKeyClause(effective)
                lines += "CONSTRAINT ${quoteIdentifier(MssqlConstraintNames.primaryKey(name))} $pkClause ($pkCols)"
            }
        }

        val partitions = tablePartitioning.render(
            name, effective, schema, hashPlan,
            hashRefused = hashOutcome is MssqlHashPartitionOutcome.Refused,
            options = options, notes = notes,
        )

        val sql = buildString {
            append("CREATE TABLE ${quoteIdentifier(name)} (\n")
            append(lines.joinToString(",\n") { "    $it" })
            append("\n)${partitions.onClause};")
        }
        // Function und Scheme muessen vor der Tabelle stehen, die sich an sie haengt.
        return partitions.prelude.map { DdlStatement(it) } + DdlStatement(sql, notes)
    }

    override fun generateIndices(
        tableName: String,
        table: TableDefinition,
        options: DdlGenerationOptions,
    ): List<DdlStatement> {
        val schema = currentSchema ?: SchemaDefinition(name = "", version = "", tables = mapOf(tableName to table))
        // Die Indizes kommen aus der Schema-Tabelle, nicht aus der in
        // `generateTable` emulierten — der Eimer muss hier also erneut
        // aufgeloest werden. Ohne das entstuende ein `CREATE UNIQUE INDEX` OHNE
        // Partitionsspalte auf einer Tabelle, die an ihr haengt: genau der
        // Serverfehler, um den die Emulation gebaut ist.
        val effective = (
            resolveHashPartitionPlan(
                tableName, table,
                options.mssqlContext?.hashPartitionMode ?: MssqlHashPartitionMode.ACTION_REQUIRED,
                ::quoteIdentifier, schema,
            ) as? MssqlHashPartitionOutcome.Planned
            )?.plan?.table ?: table
        return indexHelper.generateIndices(tableName, effective, typeResolver.lobColumns(effective, schema))
    }

    // ── Foreign keys outside CREATE TABLE ────────

    override fun handleCircularReferences(
        edges: List<CircularFkEdge>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> = edges.map { edge ->
        addConstraintStatement(
            MssqlColumnConstraintHelper.ForeignKeySpec(
                edge.constraintName, edge.fromTable, edge.fromColumns, edge.toTable, edge.toColumns,
                edge.onDelete, edge.onUpdate,
            ),
        )
    }

    override fun generateDeferredForeignKeys(
        foreignKeys: List<DeferredForeignKey>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> = foreignKeys.map { fk ->
        addConstraintStatement(
            MssqlColumnConstraintHelper.ForeignKeySpec(
                fk.constraintName, fk.fromTable, fk.fromColumns, fk.toTable, fk.toColumns, fk.onDelete, fk.onUpdate,
            ),
        )
    }

    private fun addConstraintStatement(fk: MssqlColumnConstraintHelper.ForeignKeySpec): DdlStatement {
        val notes = mutableListOf<TransformationNote>()
        val clause = columnHelper.buildForeignKeyClause(cascadeGuard, fk, notes)
        return DdlStatement("ALTER TABLE ${quoteIdentifier(fk.fromTable)} ADD $clause;", notes)
    }

    // ── Views ────────────────────────────────────

    override fun generateViews(
        views: Map<String, ViewDefinition>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> = views.mapNotNull { (name, view) -> generateView(name, view, skipped) }

    private fun generateView(name: String, view: ViewDefinition, skipped: MutableList<SkippedObject>): DdlStatement? {
        val query = view.query
        if (query == null) {
            skipped += SkippedObject("view", name, "No query defined")
            return null
        }
        val transformer = ViewQueryTransformer(DatabaseDialect.MSSQL)
        val portability = transformer.assessPortability(query, view.sourceDialect)
        if (!portability.portable) {
            val action = ManualActionRequired(
                code = "E053", objectType = "view", objectName = name,
                reason = "View '$name' body is not portable to SQL Server (${portability.reason}); " +
                    "d-migrate does not translate view bodies between dialects.",
                hint = "Rewrite the view body with T-SQL-compatible syntax and re-run.",
                sourceDialect = view.sourceDialect,
            )
            skipped += action.toSkipped()
            return actionRequired(action)
        }
        val notes = mutableListOf<TransformationNote>()
        if (view.materialized) {
            notes += TransformationNote(
                type = NoteType.WARNING, code = "W103", objectName = name,
                message = "Materialized views are not supported in SQL Server. Created as a regular view instead.",
                hint = "Consider an indexed view (schema-bound, with restrictions) or a refreshed table.",
            )
        }
        val (transformedQuery, queryNotes) = transformer.transform(query, view.sourceDialect)
        notes += queryNotes
        return DdlStatement("CREATE OR ALTER VIEW ${quoteIdentifier(name)} AS\n$transformedQuery;", notes)
    }

    // ── Routines, aggregates, triggers ───────────

    override fun generateFunctions(
        functions: Map<String, FunctionDefinition>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> {
        val colliding = MssqlRoutineDdl.collidingNames(functions.keys, ObjectKeyCodec::routineName)
        return functions.map { (key, fn) ->
            val name = ObjectKeyCodec.routineName(key)
            val problem = MssqlRoutineDdl.nameCollision("function", name, colliding)
                ?: MssqlRoutineDdl.bodyProblem("function", name, fn.body, fn.sourceDialect)
                ?: MssqlRoutineDdl.unsupportedFunctionShape(name, fn)
            if (problem != null) {
                notRendered("function", name, problem, fn.sourceDialect, skipped)
            } else {
                DdlStatement(MssqlRoutineDdl.functionSql(name, fn, checkNotNull(fn.body)) { quoteIdentifier(it) })
            }
        }
    }

    override fun generateProcedures(
        procedures: Map<String, ProcedureDefinition>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> {
        val colliding = MssqlRoutineDdl.collidingNames(procedures.keys, ObjectKeyCodec::routineName)
        return procedures.map { (key, proc) ->
            val name = ObjectKeyCodec.routineName(key)
            val problem = MssqlRoutineDdl.nameCollision("procedure", name, colliding)
                ?: MssqlRoutineDdl.bodyProblem("procedure", name, proc.body, proc.sourceDialect)
                ?: MssqlRoutineDdl.unsupportedProcedureShape(name, proc)
            if (problem != null) {
                notRendered("procedure", name, problem, proc.sourceDialect, skipped)
            } else {
                DdlStatement(MssqlRoutineDdl.procedureSql(name, proc, checkNotNull(proc.body)) { quoteIdentifier(it) })
            }
        }
    }

    override fun generateTriggers(
        triggers: Map<String, TriggerDefinition>,
        tables: Map<String, TableDefinition>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> {
        val colliding = MssqlRoutineDdl.collidingNames(triggers.keys, ObjectKeyCodec::triggerName)
        return triggers.map { (key, trigger) ->
            val name = ObjectKeyCodec.triggerName(key)
            val problem = MssqlRoutineDdl.nameCollision("trigger", name, colliding)
                ?: MssqlRoutineDdl.bodyProblem("trigger", name, trigger.body, trigger.sourceDialect)
                ?: MssqlRoutineDdl.unsupportedTriggerShape(name, trigger)
            if (problem != null) {
                notRendered("trigger", name, problem, trigger.sourceDialect, skipped)
            } else {
                DdlStatement(
                    MssqlRoutineDdl.triggerSql(name, trigger, checkNotNull(trigger.body)) { quoteIdentifier(it) },
                )
            }
        }
    }


    override fun generateAggregates(
        aggregates: Map<String, AggregateDefinition>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> = aggregates.map { (name, aggregate) ->
        val action = ManualActionRequired(
            code = "E054", objectType = "aggregate", objectName = name,
            reason = "Aggregate '$name' cannot be rendered for SQL Server: user-defined aggregates require a " +
                "CLR assembly (CREATE AGGREGATE … EXTERNAL NAME).",
            hint = "Implement the aggregate as a CLR assembly or express it with built-in functions.",
            sourceDialect = aggregate.sourceDialect,
        )
        skipped += action.toSkipped()
        actionRequired(action)
    }


    private fun notRendered(
        kind: String,
        name: String,
        problem: MssqlRoutineDdl.Unrenderable,
        sourceDialect: String?,
        skipped: MutableList<SkippedObject>,
    ): DdlStatement {
        val action = ManualActionRequired(
            code = "E053", objectType = kind, objectName = name,
            reason = problem.reason,
            hint = problem.hint,
            sourceDialect = sourceDialect,
        )
        skipped += action.toSkipped()
        return actionRequired(action)
    }

    private fun actionRequired(action: ManualActionRequired): DdlStatement =
        DdlStatement("", listOf(action.toNote()))

    // ── Rollback ─────────────────────────────────

    /**
     * T-SQL-Abweichungen vom generischen Inverter: `DROP INDEX` verlangt die
     * Tabelle (`DROP INDEX … ON [table]`), und `CREATE OR ALTER VIEW` ist kein
     * Präfix, den der generische Inverter kennt.
     */
    override fun invertStatement(stmt: DdlStatement): DdlStatement? {
        val sql = stmt.sql.trim()
        return when {
            sql.startsWith("CREATE TABLE", ignoreCase = true) ->
                DdlStatement("DROP TABLE IF EXISTS ${bracketedNameAfter(sql, "CREATE TABLE")};")
            sql.startsWith("CREATE SEQUENCE", ignoreCase = true) ->
                DdlStatement("DROP SEQUENCE IF EXISTS ${bracketedNameAfter(sql, "CREATE SEQUENCE")};")
            sql.startsWith("CREATE OR ALTER", ignoreCase = true) ->
                MssqlRoutineDdl.invert(sql, ::bracketedNameAfter)?.let { DdlStatement(it) }
                    ?: super.invertStatement(stmt)
            // Volltext steht als Katalog+Index in EINEM Statement und traegt
            // deshalb den Tabellennamen erst hinter `CREATE FULLTEXT INDEX ON`.
            // Ohne diesen Zweig fiele der Rueckbau still weg und liesse den
            // Katalog stehen — `DROP TABLE` nimmt ihn nicht mit.
            sql.contains("CREATE FULLTEXT INDEX", ignoreCase = true) -> {
                val at = sql.indexOf("CREATE FULLTEXT INDEX", ignoreCase = true)
                val table = bracketedNameAfter(sql.substring(at), "CREATE FULLTEXT INDEX ON")
                DdlStatement(
                    MssqlFullTextDdl
                        .dropStatements(table.removeSurrounding("[", "]")) { quoteIdentifier(it) }
                        .joinToString("\n"),
                )
            }
            sql.startsWith("CREATE UNIQUE INDEX", ignoreCase = true) -> invertIndex(sql, "CREATE UNIQUE INDEX")
            sql.startsWith("CREATE SPATIAL INDEX", ignoreCase = true) -> invertIndex(sql, "CREATE SPATIAL INDEX")
            sql.startsWith("CREATE INDEX", ignoreCase = true) -> invertIndex(sql, "CREATE INDEX")
            sql.startsWith("ALTER TABLE", ignoreCase = true) && sql.contains("ADD CONSTRAINT", ignoreCase = true) -> {
                val tableName = bracketedNameAfter(sql, "ALTER TABLE")
                val constraintIdx = sql.indexOf("ADD CONSTRAINT", ignoreCase = true)
                val constraintName = bracketedNameAfter(sql.substring(constraintIdx), "ADD CONSTRAINT")
                DdlStatement("ALTER TABLE $tableName DROP CONSTRAINT IF EXISTS $constraintName;")
            }
            else -> super.invertStatement(stmt)
        }
    }

    private fun invertIndex(sql: String, keyword: String): DdlStatement {
        val indexName = bracketedNameAfter(sql, keyword)
        // Erst HINTER dem (klammer-bewusst gelesenen) Indexnamen nach ` ON ` suchen —
        // der Name selbst darf " on " enthalten.
        val afterName = sql.substring(keyword.length).trimStart().substring(indexName.length)
        val onIdx = afterName.indexOf(" ON ", ignoreCase = true)
        val tableName = bracketedNameAfter(afterName.substring(onIdx + 1), "ON")
        return DdlStatement("DROP INDEX IF EXISTS $indexName ON $tableName;")
    }

    /** Erster (klammer-bewusster) Identifier nach [keyword]: `[my table]` bleibt ganz. */
    private fun bracketedNameAfter(sql: String, keyword: String): String {
        val rest = sql.substring(keyword.length).trimStart()
        if (!rest.startsWith("[")) return rest.split(Regex("[\\s(]"), limit = 2).first()
        var index = 1
        while (index < rest.length) {
            if (rest[index] == ']') {
                if (index + 1 < rest.length && rest[index + 1] == ']') {
                    index += 2
                    continue
                }
                return rest.substring(0, index + 1)
            }
            index++
        }
        return rest
    }
}
