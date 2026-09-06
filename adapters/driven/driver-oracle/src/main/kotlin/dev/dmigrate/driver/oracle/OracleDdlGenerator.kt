package dev.dmigrate.driver.oracle

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

/**
 * Oracle-[dev.dmigrate.driver.DdlGenerator] (ADR 0052 Slice 2).
 *
 * Geltungsbereich: Tabellen (Spalten, Identity, benannte UNIQUE/CHECK/PK,
 * FKs inkl. zirkulaerer und aufgeschobener), einfache B-Tree-Indizes
 * (Function-based/Bitmap folgen in Slice 6), native Sequenzen
 * (`CREATE SEQUENCE`, `.NEXTVAL`), Views (`CREATE OR REPLACE VIEW`).
 * Routinen, Trigger und Aggregate werden nicht als PL/SQL gerendert und
 * landen als E053/E054-`skipped_objects` (Slice 9); Partitionierung als
 * E055 (Tabelle plain, Slice 7); Volltext-Indizes als E057 (Slice 8);
 * Composite-Typen als E054. Spatial ist nicht gescoped (`canGenerateSpatial`
 * bleibt `false`). Render-Regeln: `spec/ddl-generation-rules.md`
 * (Abschnitte Oracle), Typtabelle: `spec/type-mapping.md`.
 */
class OracleDdlGenerator private constructor(
    private val oracleTypeMapper: OracleTypeMapper,
) : AbstractDdlGenerator(oracleTypeMapper), DeferredForeignKeyDdlSupport {

    constructor() : this(OracleTypeMapper())

    override val dialect = DatabaseDialect.ORACLE
    override val supportsDeferredForeignKeys: Boolean = true

    private val columnHelper = OracleColumnConstraintHelper(
        quoteIdentifier = ::quoteIdentifier,
        typeMapper = oracleTypeMapper,
    )
    private val indexBuilder = OracleIndexDdlBuilder(quoteIdentifier = ::quoteIdentifier)

    private fun actionRequired(action: ManualActionRequired): DdlStatement = DdlStatement("", listOf(action.toNote()))

    // ── Quoting ──────────────────────────────────

    override fun quoteIdentifier(name: String): String = SqlIdentifiers.quoteIdentifier(name, dialect)

    // ── Spatial ──────────────────────────────────

    // Nicht gescoped (SDO_GEOMETRY); jede Tabelle mit Geometry-Spalten wird
    // vom generischen Ports-Default (E052) geblockt.
    override fun canGenerateSpatial(profile: SpatialProfile): Boolean = false

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

        for (constraint in table.constraints) {
            if (options.deferForeignKeys && constraint.type == ConstraintType.FOREIGN_KEY) continue
            if ((name to constraint.name) in deferredConstraints) continue
            columnHelper.generateConstraintClause(name, constraint, unkeyableColumns, notes)?.let { lines += it }
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

        table.partitioning?.let { partitioning ->
            notes += ManualActionRequired(
                code = "E055", objectType = "partitioning", objectName = name,
                reason = "${partitioning.type.name} partitioning of table '$name' is not rendered for Oracle " +
                    "(partition clauses are not carried in the neutral model); created as a plain table.",
                hint = "Add PARTITION BY manually and rebuild the table.",
            ).toNote()
        }

        val sql = buildString {
            append("CREATE TABLE ${quoteIdentifier(name)} (\n")
            append(lines.joinToString(",\n") { "    $it" })
            append("\n);")
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
    ): List<DdlStatement> = views.mapNotNull { (name, view) -> generateView(name, view, skipped) }

    private fun generateView(name: String, view: ViewDefinition, skipped: MutableList<SkippedObject>): DdlStatement? {
        val query = view.query
        if (query == null) {
            skipped += SkippedObject("view", name, "No query defined")
            return null
        }
        val transformer = ViewQueryTransformer(DatabaseDialect.ORACLE)
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
            notes += TransformationNote(
                type = NoteType.WARNING, code = "W103", objectName = name,
                message = "Materialized views are not rendered for Oracle yet. Created as a regular view instead.",
                hint = "Oracle materialized views (Slice 10) will restore refresh semantics once built.",
            )
        }
        val (transformedQuery, queryNotes) = transformer.transform(query, view.sourceDialect)
        notes += queryNotes
        // FORCE: eine Sicht kann Objekte referenzieren, die als E053/E054/E055
        // uebersprungen wurden (z.B. eine abhaengige Sicht/Routine). Ohne
        // FORCE lehnt Oracle CREATE VIEW sofort ab (anders als MSSQLs
        // Deferred Name Resolution); mit FORCE entsteht die Sicht als
        // INVALID und wird bei tatsaechlicher Objektexistenz normal nutzbar.
        return DdlStatement("CREATE OR REPLACE FORCE VIEW ${quoteIdentifier(name)} AS\n$transformedQuery;", notes)
    }

    // ── Routines, aggregates, triggers (Slice 9) ──

    override fun generateFunctions(
        functions: Map<String, FunctionDefinition>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> = functions.map { (key, fn) ->
        routineNotRendered("function", key, fn.body, fn.sourceDialect, skipped)
    }

    override fun generateProcedures(
        procedures: Map<String, ProcedureDefinition>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> = procedures.map { (key, proc) ->
        routineNotRendered("procedure", key, proc.body, proc.sourceDialect, skipped)
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
            reason = "Aggregate '$name' cannot be rendered for Oracle: user-defined aggregates require an ODCI " +
                "implementation type (CREATE TYPE ... AS OBJECT ... IMPLEMENTS AGGREGATE).",
            hint = "Implement the aggregate as an ODCIAggregate type, or express it with built-in functions.",
            sourceDialect = aggregate.sourceDialect,
        )
        skipped += action.toSkipped()
        actionRequired(action)
    }

    /**
     * Routinen- und Trigger-Koerper werden nicht als PL/SQL gerendert:
     * fremde Dialekte muessten uebersetzt werden (macht d-migrate nicht),
     * fuer PL/SQL-Koerper fehlt dem Generator der Huellen-Vertrag (Slice 9).
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
            sourceDialect != null && sourceDialect != "oracle" ->
                "$kindLabel '$name' was written for '$sourceDialect' and must be manually rewritten for Oracle."
            else -> "$kindLabel '$name' is not rendered for oracle: d-migrate does not generate PL/SQL $kind DDL yet."
        }
        val action = ManualActionRequired(
            code = "E053", objectType = kind, objectName = name,
            reason = reason,
            hint = "Create the $kind as PL/SQL (CREATE OR REPLACE ...) manually on the target.",
            sourceDialect = sourceDialect,
        )
        skipped += action.toSkipped()
        return actionRequired(action)
    }

    // ── Rollback ─────────────────────────────────

    /**
     * Oracle kennt kein `DROP ... IF EXISTS` (auch nicht 23ai) -- der
     * generische Inverter wuerde ungueltiges DDL erzeugen. Deckt dieselben
     * Faelle wie [dev.dmigrate.driver.AbstractDdlGenerator]s Default ab,
     * nur ohne die IF-EXISTS-Klausel.
     */
    override fun invertStatement(stmt: DdlStatement): DdlStatement? {
        val sql = stmt.sql.trim()
        return when {
            sql.startsWith("CREATE TABLE", ignoreCase = true) ->
                DdlStatement("DROP TABLE ${nameAfter(sql, "CREATE TABLE")};")
            sql.startsWith("CREATE UNIQUE INDEX", ignoreCase = true) ->
                DdlStatement("DROP INDEX ${nameAfter(sql, "CREATE UNIQUE INDEX")};")
            sql.startsWith("CREATE INDEX", ignoreCase = true) ->
                DdlStatement("DROP INDEX ${nameAfter(sql, "CREATE INDEX")};")
            sql.startsWith("CREATE OR REPLACE FORCE VIEW", ignoreCase = true) ->
                DdlStatement("DROP VIEW ${nameAfter(sql, "CREATE OR REPLACE FORCE VIEW")};")
            sql.startsWith("CREATE SEQUENCE", ignoreCase = true) ->
                DdlStatement("DROP SEQUENCE ${nameAfter(sql, "CREATE SEQUENCE")};")
            sql.startsWith("ALTER TABLE", ignoreCase = true) && sql.contains("ADD CONSTRAINT", ignoreCase = true) -> {
                val tableName = nameAfter(sql, "ALTER TABLE")
                val addConstraintIdx = sql.uppercase().indexOf("ADD CONSTRAINT")
                val constraintPart = sql.substring(addConstraintIdx + "ADD CONSTRAINT".length).trimStart()
                val constraintName = constraintPart.split(Regex("[\\s(]"), limit = 2).first()
                DdlStatement("ALTER TABLE $tableName DROP CONSTRAINT $constraintName;")
            }
            else -> null
        }
    }

    private fun nameAfter(sql: String, keyword: String): String =
        sql.substring(keyword.length).trimStart().split(Regex("[\\s(]"), limit = 2).first()
}
