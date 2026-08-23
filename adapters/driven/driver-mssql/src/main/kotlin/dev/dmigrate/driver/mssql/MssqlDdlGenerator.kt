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
 * Composite E054), Spatial (Profil `native` → `geography`/`geometry`).
 * Routinen, Trigger und Aggregate werden nicht als T-SQL gerendert und landen
 * als E053/E054-`skipped_objects`; Partitionierung wird als E055 ausgewiesen
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
        val lobColumns = typeResolver.lobColumns(table, schema)

        for ((colName, col) in table.columns.inOrdinalOrder()) {
            lines += columnHelper.generateColumnSql(name, colName, col, table, schema, notes)
        }

        for ((colName, col) in table.columns.inOrdinalOrder()) {
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

        for (constraint in table.constraints) {
            if (options.deferForeignKeys && constraint.type == ConstraintType.FOREIGN_KEY) continue
            if ((name to constraint.name) in deferredConstraints) continue
            columnHelper.generateConstraintClause(cascadeGuard, name, table, constraint, lobColumns, notes)
                ?.let { lines += it }
        }

        if (table.primaryKey.isNotEmpty()) {
            val lobKeys = table.primaryKey.filter { it in lobColumns }
            if (lobKeys.isNotEmpty()) {
                notes += columnHelper.lobKeyNote(name, MssqlConstraintNames.primaryKey(name), "PRIMARY KEY", lobKeys)
            } else {
                val pkCols = table.primaryKey.joinToString(", ") { quoteIdentifier(it) }
                lines += "CONSTRAINT ${quoteIdentifier(MssqlConstraintNames.primaryKey(name))} PRIMARY KEY ($pkCols)"
            }
        }

        table.partitioning?.let { partitioning ->
            notes += ManualActionRequired(
                code = "E055", objectType = "partitioning", objectName = name,
                reason = "${partitioning.type.name} partitioning of table '$name' is not rendered for SQL Server " +
                    "(partition function, scheme and filegroups are not carried in the neutral model); " +
                    "created as a plain table.",
                hint = "Create the partition function and scheme manually and rebuild the table on the scheme.",
            ).toNote()
        }

        val sql = buildString {
            append("CREATE TABLE ${quoteIdentifier(name)} (\n")
            append(lines.joinToString(",\n") { "    $it" })
            append("\n);")
        }
        return listOf(DdlStatement(sql, notes))
    }

    override fun generateIndices(
        tableName: String,
        table: TableDefinition,
        options: DdlGenerationOptions,
    ): List<DdlStatement> {
        val schema = currentSchema ?: SchemaDefinition(name = "", version = "", tables = mapOf(tableName to table))
        return indexHelper.generateIndices(tableName, table, typeResolver.lobColumns(table, schema))
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
    ): List<DdlStatement> = functions.map { (key, fn) ->
        routineNotRendered("function", ObjectKeyCodec.routineName(key), fn.body, fn.sourceDialect, skipped)
    }

    override fun generateProcedures(
        procedures: Map<String, ProcedureDefinition>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> = procedures.map { (key, proc) ->
        routineNotRendered("procedure", ObjectKeyCodec.routineName(key), proc.body, proc.sourceDialect, skipped)
    }

    override fun generateTriggers(
        triggers: Map<String, TriggerDefinition>,
        tables: Map<String, TableDefinition>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> = triggers.map { (name, trigger) ->
        routineNotRendered("trigger", name, trigger.body, trigger.sourceDialect, skipped)
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

    /**
     * Routinen- und Trigger-Körper werden nicht als T-SQL gerendert: fremde
     * Dialekte müssten übersetzt werden (macht d-migrate nicht), und für
     * T-SQL-Körper fehlt dem Generator der Hüllen-Vertrag (Parameter-
     * Präfixe, Trigger ohne FOR EACH ROW). Beides landet sichtbar als E053.
     */
    private fun routineNotRendered(
        kind: String,
        name: String,
        body: String?,
        sourceDialect: String?,
        skipped: MutableList<SkippedObject>,
    ): DdlStatement {
        val kindLabel = kind.replaceFirstChar { it.uppercase() }
        val reason = when {
            body == null -> "$kindLabel '$name' has no body and must be manually implemented."
            sourceDialect != null && sourceDialect != "mssql" ->
                "$kindLabel '$name' was written for '$sourceDialect' and must be manually rewritten for SQL Server."
            else -> "$kindLabel '$name' is not rendered for mssql: d-migrate does not generate T-SQL $kind DDL."
        }
        val action = ManualActionRequired(
            code = "E053", objectType = kind, objectName = name,
            reason = reason,
            hint = "Create the $kind as T-SQL (CREATE OR ALTER …) manually on the target.",
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
            sql.startsWith("CREATE OR ALTER VIEW", ignoreCase = true) ->
                DdlStatement("DROP VIEW IF EXISTS ${bracketedNameAfter(sql, "CREATE OR ALTER VIEW")};")
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
