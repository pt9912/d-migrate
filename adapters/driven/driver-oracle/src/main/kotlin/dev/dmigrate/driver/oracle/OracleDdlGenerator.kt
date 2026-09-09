package dev.dmigrate.driver.oracle

import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.core.model.AggregateDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.ProcedureDefinition
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
import dev.dmigrate.driver.metadata.NamedUniqueConstraints
import dev.dmigrate.driver.oracleContext

/**
 * Oracle-[dev.dmigrate.driver.DdlGenerator] (ADR 0052).
 *
 * Geltungsbereich: Tabellen (Spalten, Identity, benannte UNIQUE/CHECK/PK,
 * FKs inkl. zirkulaerer und aufgeschobener), Indizes (B-Tree, Bitmap,
 * Ausdruecke), native Sequenzen (`CREATE SEQUENCE`, `.NEXTVAL`), Views
 * (`CREATE OR REPLACE VIEW`) sowie Funktionen, Prozeduren und Trigger als
 * PL/SQL. Aggregate landen als E054, ein Rumpf aus einem fremden Dialekt als
 * E053; Partitionierung als E055/E062 (Tabelle plain), Volltext ueber Oracle
 * Text (mehrspaltig E057); Composite-Typen als E054. Spatial ueber
 * `SDO_GEOMETRY` samt raeumlichem Index, der in POST_DATA steht
 * ([OracleSpatialIndexDdl]). Render-Regeln: `spec/ddl-generation-rules.md`
 * (Abschnitte Oracle), Typtabelle: `spec/type-mapping.md`.
 */
class OracleDdlGenerator private constructor(
    private val oracleTypeMapper: OracleTypeMapper,
) : AbstractDdlGenerator(oracleTypeMapper), DeferredForeignKeyDdlSupport {

    constructor() : this(OracleTypeMapper())

    override val dialect = DatabaseDialect.ORACLE
    override val supportsDeferredForeignKeys: Boolean = true

    private val partitionBuilder = OraclePartitionDdlBuilder(::quoteIdentifier)

    private val columnHelper = OracleColumnConstraintHelper(
        quoteIdentifier = ::quoteIdentifier,
        typeMapper = oracleTypeMapper,
    )
    private val indexBuilder = OracleIndexDdlBuilder(quoteIdentifier = ::quoteIdentifier)

    private fun actionRequired(action: ManualActionRequired): DdlStatement = DdlStatement("", listOf(action.toNote()))

    // ── Quoting ──────────────────────────────────

    override fun quoteIdentifier(name: String): String = SqlIdentifiers.quoteIdentifier(name, dialect)

    // ── Spatial ──────────────────────────────────

    /**
     * Oracles Geometrietyp ist Teil des Kerns, nicht einer Erweiterung: eine
     * Geometriespalte braucht kein Gegenstueck zu PostGIS oder SpatiaLite,
     * sondern nur `SDO_GEOMETRY`. Damit gilt hier dasselbe wie fuer SQL
     * Server -- das native Profil, und nur dieses.
     */
    override fun canGenerateSpatial(profile: SpatialProfile): Boolean = profile == SpatialProfile.NATIVE

    // ── Custom types ─────────────────────────────

    override fun generateCustomTypes(types: Map<String, CustomTypeDefinition>): List<DdlStatement> =
        types.mapNotNull { (name, typeDef) ->
            when (typeDef.kind) {
                // Enum/Domain werden an der Spalte gerendert (VARCHAR2 + CHECK bzw. CLOB + E053).
                CustomTypeKind.ENUM, CustomTypeKind.DOMAIN -> null
                CustomTypeKind.COMPOSITE -> DdlStatement(
                    "",
                    listOf(
                        ManualActionRequired(
                            code = "E054", objectType = "composite_type", objectName = name,
                            reason = "Composite type '$name' is not supported in Oracle and was skipped.",
                            hint = "Flatten the fields into columns, or store the value as JSON.",
                        ).toNote(),
                    ),
                )
            }
        }

    // ── Sequences ────────────────────────────────

    override fun generateSequences(
        schema: SchemaDefinition,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> = schema.sequences.map { (name, seq) ->
        DdlStatement(OracleSequenceDdl.createSql(name, seq))
    }

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
        val unkeyableColumns = unkeyableColumns(table)

        for ((colName, col) in table.columns.inOrdinalOrder()) {
            lines += columnHelper.generateColumnSql(name, colName, col, schema, notes)
        }

        for ((colName, col) in table.columns.inOrdinalOrder()) {
            val ref = col.references ?: continue
            if (options.deferForeignKeys) continue
            if ((name to colName) in deferredFks) continue
            lines += columnHelper.buildForeignKeyClause(
                "fk_${name}_$colName", listOf(colName), ref.table, listOf(ref.column), ref.onDelete, notes,
            )
        }

        lines += NamedUniqueConstraints.clauses(table, ::quoteIdentifier)
        for (constraint in table.constraints) {
            if (options.deferForeignKeys && constraint.type == ConstraintType.FOREIGN_KEY) continue
            if ((name to constraint.name) in deferredConstraints) continue
            columnHelper.generateConstraintClause(
                name, constraint, unkeyableColumns, notes,
                OracleIdentifierRequoter.knownIdentifiers(schema),
            )?.let { lines += it }
        }

        if (table.primaryKey.isNotEmpty()) {
            val lobKeys = table.primaryKey.filter { it in unkeyableColumns }
            if (lobKeys.isNotEmpty()) {
                notes += columnHelper.unkeyableKeyNote(name, "pk_$name", "PRIMARY KEY", lobKeys)
            } else {
                val pkCols = table.primaryKey.joinToString(", ") { quoteIdentifier(it) }
                lines += "CONSTRAINT ${quoteIdentifier("pk_$name")} PRIMARY KEY ($pkCols)"
            }
        }

        val partitionClause = table.partitioning
            ?.let { partitionBuilder.clause(name, it, table.columns, notes) }
            .orEmpty()

        val sql = buildString {
            append("CREATE TABLE ${quoteIdentifier(name)} (\n")
            append(lines.joinToString(",\n") { "    $it" })
            append("\n)")
            if (partitionClause.isNotEmpty()) append("\n$partitionClause")
            append(";")
        }
        return listOf(DdlStatement(sql, notes))
    }

    /** Spalten, die als LOB (`CLOB`/`BLOB`) gerendert werden -- keine zulaessigen Schluessel-/Indexspalten. */
    private fun unkeyableColumns(table: TableDefinition): Set<String> =
        table.columns.filterValues { oracleTypeMapper.isUnkeyable(it.type) }.keys

    // ── Indices ──────────────────────────────────

    override fun generateIndices(
        tableName: String,
        table: TableDefinition,
        options: DdlGenerationOptions,
    ): List<DdlStatement> {
        val unkeyableColumns = unkeyableColumns(table)
        return table.indices.map { indexBuilder.render(tableName, table, it, unkeyableColumns) }
    }

    // ── Circular / deferred foreign keys ──────────

    override fun handleCircularReferences(
        edges: List<CircularFkEdge>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> = edges.map { edge ->
        val notes = mutableListOf<TransformationNote>()
        val sql = buildString {
            append("ALTER TABLE ${quoteIdentifier(edge.fromTable)} ADD ")
            append(
                columnHelper.buildForeignKeyClause(
                    edge.constraintName, edge.fromColumns, edge.toTable, edge.toColumns, edge.onDelete, notes,
                ),
            )
            append(";")
        }
        DdlStatement(sql, notes)
    }

    override fun generateDeferredForeignKeys(
        foreignKeys: List<DeferredForeignKey>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> = foreignKeys.map { fk ->
        val notes = mutableListOf<TransformationNote>()
        val sql = buildString {
            append("ALTER TABLE ${quoteIdentifier(fk.fromTable)} ADD ")
            append(
                columnHelper.buildForeignKeyClause(
                    fk.constraintName, fk.fromColumns, fk.toTable, fk.toColumns, fk.onDelete, notes,
                ),
            )
            append(";")
        }
        DdlStatement(sql, notes)
    }

    // ── Views ────────────────────────────────────

    override fun generateViews(
        views: Map<String, ViewDefinition>,
        skipped: MutableList<SkippedObject>,
        schema: SchemaDefinition,
    ): List<DdlStatement> = views.mapNotNull { (name, view) -> generateView(name, view, skipped, schema) }

    private fun generateView(
        name: String,
        view: ViewDefinition,
        skipped: MutableList<SkippedObject>,
        schema: SchemaDefinition,
    ): DdlStatement? {
        val query = view.query
        if (query == null) {
            skipped += SkippedObject("view", name, "No query defined")
            return null
        }
        val transformer = ViewQueryTransformer(OracleViewPortabilityRules)
        val portability = transformer.assessPortability(query, view.sourceDialect)
        if (!portability.portable) {
            val action = ManualActionRequired(
                code = "E053", objectType = "view", objectName = name,
                reason = "View '$name' body is not portable to Oracle (${portability.reason}); d-migrate does " +
                    "not translate view bodies between dialects.",
                hint = "Rewrite the view body with Oracle-compatible syntax and re-run.",
                sourceDialect = view.sourceDialect,
            )
            skipped += action.toSkipped()
            return actionRequired(action)
        }
        val notes = mutableListOf<TransformationNote>()
        if (view.materialized) {
            OracleMaterializedViewDdl.unsupportedShape(name, view)?.let { problem ->
                val action = ManualActionRequired(
                    code = "E053", objectType = "materialized_view", objectName = name,
                    reason = problem.reason, hint = problem.hint, sourceDialect = view.sourceDialect,
                )
                skipped += action.toSkipped()
                return actionRequired(action)
            }
        }
        val (portableQuery, queryNotes) = transformer.transform(query, view.sourceDialect)
        notes += queryNotes
        // Dieselbe Faltrichtung wie beim CHECK-Ausdruck: unquotiert sucht
        // Oracle GROSSSCHREIBUNG und findet die wortgetreu angelegte Tabelle
        // oder Spalte nicht (ORA-00942 / ORA-00904).
        val transformedQuery = OracleIdentifierRequoter.requote(
            portableQuery, OracleIdentifierRequoter.knownIdentifiers(schema), ::quoteIdentifier,
        )
        if (view.materialized) {
            return DdlStatement(
                OracleMaterializedViewDdl.createSql(name, view, transformedQuery, ::quoteIdentifier),
                notes,
            )
        }
        // FORCE: eine Sicht kann Objekte referenzieren, die als E053/E054/E055
        // uebersprungen wurden (z.B. eine abhaengige Sicht/Routine). Ohne
        // FORCE lehnt Oracle CREATE VIEW sofort ab (anders als MSSQLs
        // Deferred Name Resolution); mit FORCE entsteht die Sicht als
        // INVALID und wird bei tatsaechlicher Objektexistenz normal nutzbar.
        return DdlStatement("CREATE OR REPLACE FORCE VIEW ${quoteIdentifier(name)} AS\n$transformedQuery;", notes)
    }

    // ── Routines, aggregates, triggers ──

    override fun generateFunctions(
        functions: Map<String, FunctionDefinition>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> {
        val colliding = OracleRoutineShape.collidingNames(functions.keys, ObjectKeyCodec::routineName)
        return functions.map { (key, fn) ->
            val name = ObjectKeyCodec.routineName(key)
            val problem = OracleRoutineShape.nameCollision("function", name, colliding)
                ?: OracleRoutineShape.bodyProblem("function", name, fn.body, fn.sourceDialect)
                ?: OracleRoutineShape.unsupportedFunctionShape(name, fn)
            if (problem != null) {
                routineNotRendered("function", name, problem, fn.sourceDialect, skipped)
            } else {
                plsqlBlock(OracleRoutineDdl.functionSql(name, fn, checkNotNull(fn.body)) { quoteIdentifier(it) })
            }
        }
    }

    override fun generateProcedures(
        procedures: Map<String, ProcedureDefinition>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> {
        val colliding = OracleRoutineShape.collidingNames(procedures.keys, ObjectKeyCodec::routineName)
        return procedures.map { (key, proc) ->
            val name = ObjectKeyCodec.routineName(key)
            val problem = OracleRoutineShape.nameCollision("procedure", name, colliding)
                ?: OracleRoutineShape.bodyProblem("procedure", name, proc.body, proc.sourceDialect)
                ?: OracleRoutineShape.unsupportedProcedureShape(name, proc)
            if (problem != null) {
                routineNotRendered("procedure", name, problem, proc.sourceDialect, skipped)
            } else {
                plsqlBlock(
                    OracleRoutineDdl.procedureSql(name, proc, checkNotNull(proc.body)) { quoteIdentifier(it) },
                )
            }
        }
    }

    override fun generateTriggers(
        triggers: Map<String, TriggerDefinition>,
        tables: Map<String, TableDefinition>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> {
        // `triggerName` statt `parseTriggerKey`: ein handgeschriebenes Schema
        // darf einen Trigger unter seinem blanken Namen fuehren, und der
        // Parser wirft dafuer.
        val colliding = OracleRoutineShape.collidingNames(triggers.keys, ObjectKeyCodec::triggerName)
        return triggers.map { (key, trigger) ->
            val name = ObjectKeyCodec.triggerName(key)
            val problem = OracleRoutineShape.nameCollision("trigger", name, colliding)
                ?: OracleRoutineShape.bodyProblem("trigger", name, trigger.body, trigger.sourceDialect)
                ?: OracleRoutineShape.unsupportedTriggerShape(name, trigger, tables)
            if (problem != null) {
                routineNotRendered("trigger", name, problem, trigger.sourceDialect, skipped)
            } else {
                plsqlBlock(
                    OracleRoutineDdl.triggerSql(name, trigger, checkNotNull(trigger.body)) { quoteIdentifier(it) },
                )
            }
        }
    }

    /**
     * Ein PL/SQL-Block traegt kein abschliessendes `;` und kein `/` in seinem
     * SQL: ueber JDBC gesendet meldet `execute()` damit zwar Erfolg, laesst
     * die Routine aber `INVALID` zurueck. Das `/` fuer SQL*Plus haengt erst
     * die Skriptdarstellung an.
     */
    private fun plsqlBlock(sql: String): DdlStatement =
        DdlStatement(sql, scriptTerminator = OracleRoutineDdl.PLSQL_SCRIPT_TERMINATOR)

    override fun generateAggregates(
        aggregates: Map<String, AggregateDefinition>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> = aggregates.map { (name, aggregate) ->
        val action = ManualActionRequired(
            code = "E054", objectType = "aggregate", objectName = name,
            reason = "Aggregate '$name' cannot be rendered for Oracle: user-defined aggregates require an ODCI " +
                "implementation type (CREATE TYPE ... AS OBJECT ... IMPLEMENTS AGGREGATE).",
            hint = "Implement the aggregate as an ODCIAggregate type, or express it with built-in functions.",
            sourceDialect = aggregate.sourceDialect,
        )
        skipped += action.toSkipped()
        actionRequired(action)
    }

    /**
     * Was [OracleRoutineShape] nicht darstellbar findet, wird als
     * Handarbeit gemeldet statt geraten — mit demselben Urteil, das der
     * Diff-Pfad faellt.
     */
    private fun routineNotRendered(
        kind: String,
        name: String,
        problem: OracleRoutineShape.Unrenderable,
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

    // ── Rollback ─────────────────────────────────

    /**
     * Ruecknahme-Anweisungen fuer die Formen, die der Oracle-Generator
     * erzeugt.
     *
     * `DROP <objekt> IF EXISTS` gibt es bei Oracle erst ab der 23er-Linie.
     * Steht die Version des Ziels fest und traegt sie die Klausel, wird sie
     * gesetzt — ohne sie bricht ein Ruecknahme-Skript ab, sobald ein Objekt
     * schon fehlt, also genau in dem Fall, fuer den man es faehrt. Ist die
     * Version unbekannt (Rendern ohne Verbindung), bleibt es bei der Form
     * ohne Klausel: die fuehrt jede Oracle-Version aus.
     *
     * `ALTER TABLE … DROP CONSTRAINT` bleibt in jedem Fall ohne die Klausel.
     * Auch die 23er-Linie lehnt sie dort mit `ORA-01735` ab — die Klausel
     * gilt fuer Objekte, nicht fuer Constraints.
     */
    override fun invertStatement(stmt: DdlStatement, options: DdlGenerationOptions): DdlStatement? {
        val sql = stmt.sql.trim()
        OracleSpatialIndexDdl.invertedDrop(sql)?.let { return DdlStatement(it) }
        val ifExists =
            if (options.oracleContext?.serverVersion?.supportsDropIfExists == true) "IF EXISTS " else ""
        fun drop(keyword: String, objectKind: String): DdlStatement =
            DdlStatement("DROP $objectKind $ifExists${nameAfter(sql, keyword)};")
        return when {
            sql.startsWith("CREATE TABLE", ignoreCase = true) ->
                drop("CREATE TABLE", "TABLE")
            sql.startsWith("CREATE UNIQUE INDEX", ignoreCase = true) ->
                drop("CREATE UNIQUE INDEX", "INDEX")
            sql.startsWith("CREATE INDEX", ignoreCase = true) ->
                drop("CREATE INDEX", "INDEX")
            sql.startsWith("CREATE OR REPLACE FORCE VIEW", ignoreCase = true) ->
                drop("CREATE OR REPLACE FORCE VIEW", "VIEW")
            sql.startsWith("CREATE MATERIALIZED VIEW", ignoreCase = true) ->
                drop("CREATE MATERIALIZED VIEW", "MATERIALIZED VIEW")
            // Die drei Routinen-Formen: `DROP` ist gewoehnliches DDL und
            // braucht deshalb weder das `/` noch den PL/SQL-Trenner, den das
            // `CREATE` traegt.
            sql.startsWith("CREATE OR REPLACE FUNCTION", ignoreCase = true) ->
                drop("CREATE OR REPLACE FUNCTION", "FUNCTION")
            sql.startsWith("CREATE OR REPLACE PROCEDURE", ignoreCase = true) ->
                drop("CREATE OR REPLACE PROCEDURE", "PROCEDURE")
            sql.startsWith("CREATE OR REPLACE TRIGGER", ignoreCase = true) ->
                drop("CREATE OR REPLACE TRIGGER", "TRIGGER")
            sql.startsWith("CREATE SEQUENCE", ignoreCase = true) ->
                drop("CREATE SEQUENCE", "SEQUENCE")
            sql.startsWith("ALTER TABLE", ignoreCase = true) && sql.contains("ADD CONSTRAINT", ignoreCase = true) ->
                DdlStatement("ALTER TABLE ${nameAfter(sql, "ALTER TABLE")} DROP CONSTRAINT ${addedConstraintName(sql)};")
            else -> null
        }
    }

    private fun addedConstraintName(sql: String): String {
        val addConstraintIdx = sql.uppercase().indexOf("ADD CONSTRAINT")
        val constraintPart = sql.substring(addConstraintIdx + "ADD CONSTRAINT".length).trimStart()
        return constraintPart.split(Regex("[\\s(]"), limit = 2).first()
    }

    private fun nameAfter(sql: String, keyword: String): String =
        sql.substring(keyword.length).trimStart().split(Regex("[\\s(]"), limit = 2).first()
}
