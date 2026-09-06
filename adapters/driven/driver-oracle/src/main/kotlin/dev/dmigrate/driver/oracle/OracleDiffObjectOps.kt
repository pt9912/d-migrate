package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers
import dev.dmigrate.driver.TransformationNote
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * Per-operation renderers for constraint and index DDL (Sub-Slice 5b).
 * Stateless, same shape as [OracleDiffTableOps].
 *
 * Oracle-Eigenheiten, alle live gemessen (2026-09-06,
 * `gvenzl/oracle-free:23-slim-faststart`) statt aus dem MSSQL-Vorbild
 * uebernommen:
 * - **Kein `WITH CHECK`-Aequivalent noetig.** Oracle validiert einen
 *   nachgezogenen CHECK/FK per Default GEGEN den Bestand und scheitert an
 *   verletzenden Zeilen (`ORA-02293` bzw. `ORA-02298`) -- genau die strenge
 *   Semantik, die MSSQL sich mit `WITH CHECK` erst erkaufen muss. Die
 *   Gegenrichtung (`ENABLE NOVALIDATE`, nur kuenftige DML pruefen) waere
 *   eine stille Abschwaechung und wird deshalb NICHT gerendert.
 * - **Ein UNIQUE-Constraint traegt seinen Index selbst**: `ADD CONSTRAINT`
 *   legt ihn unter dem Constraint-Namen an, `DROP CONSTRAINT` raeumt ihn
 *   mit weg -- kein separates `DROP INDEX`. Umgekehrt laesst Oracle den
 *   Stuetzindex nicht einzeln droppen (`ORA-02429`); dieser Fall kann hier
 *   aber nicht entstehen, weil der Reverse Unique-Indizes gar nicht als
 *   Index fuehrt (`OracleSchemaReader` hebt sie auf `column.unique` bzw.
 *   einen UNIQUE-Constraint).
 * - **`DROP INDEX` nimmt keinen Tabellennamen** (anders als MySQL).
 * - **Kein `IF EXISTS`**: ein `DROP CONSTRAINT` auf einen unbekannten Namen
 *   scheitert (`ORA-02443`). Die Down-Richtung darf also nicht auf
 *   Idempotenz bauen.
 * - **Constraint- und Indexnamen sind schema-global**, nicht tabellenlokal
 *   (`ORA-02264` / `ORA-00955`) -- dieselbe Falle wie MSSQLs Msg 2714. Der
 *   Renderer kann sie ohne Katalogzugriff nicht vorhersehen; sie schlaegt
 *   beim Ausfuehren zu.
 */
internal object OracleDiffObjectOps {

    private val typeMapper = OracleTypeMapper()
    private fun quoteIdentifier(name: String): String = SqlIdentifiers.quoteIdentifier(name, DatabaseDialect.ORACLE)
    private val columnHelper = OracleColumnConstraintHelper(quoteIdentifier = ::quoteIdentifier, typeMapper = typeMapper)
    private val indexBuilder = OracleIndexDdlBuilder(quoteIdentifier = ::quoteIdentifier)

    // ── Constraints ──────────────────────────────

    fun renderAddConstraint(op: DiffOperation.AddConstraint, ctx: OracleDiffRenderContext) {
        val table = op.objectRef.path[0]
        if (ctx.direction == OracleRenderDirection.DOWN) {
            ctx.emit(op, dropConstraintSql(ctx, table, op.constraint.name))
            return
        }
        emitAddConstraint(op, ctx, table, op.constraint)
    }

    fun renderDropConstraint(op: DiffOperation.DropConstraint, ctx: OracleDiffRenderContext) {
        val table = op.objectRef.path[0]
        if (ctx.direction == OracleRenderDirection.DOWN) {
            emitAddConstraint(op, ctx, table, op.constraint)
            return
        }
        ctx.emit(op, dropConstraintSql(ctx, table, op.constraint.name))
    }

    private fun dropConstraintSql(ctx: OracleDiffRenderContext, table: String, name: String): String =
        "ALTER TABLE ${ctx.sql.quote(table)} DROP CONSTRAINT ${ctx.sql.quote(name)};"

    /**
     * Gemeinsamer Anlege-Pfad beider Constraint-Operationen. Prueft VOR dem
     * Aufruf des Generate-Helfers, ob die Klausel ueberhaupt rekonstruierbar
     * ist: [OracleColumnConstraintHelper.generateConstraintClause] geht fuer
     * den CREATE-TABLE-Pfad von wohlgeformten Schemata aus und wuerde einen
     * fehlenden CHECK-Ausdruck als `CHECK (null)` interpolieren bzw. bei
     * fehlendem `references` mit einer NPE abbrechen.
     */
    private fun emitAddConstraint(
        op: DiffOperation,
        ctx: OracleDiffRenderContext,
        table: String,
        constraint: ConstraintDefinition,
    ) {
        if (constraint.type == ConstraintType.EXCLUDE) {
            return blockConstraint(
                op, ctx,
                "EXCLUDE constraint '${constraint.name}' on '$table' has no Oracle equivalent.",
                "ORACLE_EXCLUDE_CONSTRAINT_UNSUPPORTED",
                MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION,
            )
        }
        if (constraint.type == ConstraintType.CHECK && constraint.expression.isNullOrBlank()) {
            // Der Ausdruck ist die ganze Substanz eines CHECK -- ohne ihn ist
            // die Klausel nicht rekonstruierbar. In der Down-Richtung ist das
            // ein Rollback-, in der Up-Richtung ein Modellproblem.
            // Der Down-Zweig ist ueber den Planner derzeit unerreichbar:
            // ConstraintReplaceContract pinnt genau diese Operation auf
            // NOT_REVERSIBLE, der Dispatcher springt also vorher ab. Er bleibt
            // stehen, weil der Renderer auch fuer handgebaute DiffResults
            // (Artefakt-Deserialisierung) korrekt antworten soll -- dieselbe
            // Form wie im PostgreSQL-Pfad.
            val down = ctx.direction == OracleRenderDirection.DOWN
            return blockConstraint(
                op, ctx,
                "CHECK constraint '${constraint.name}' on '$table' carries no expression; the renderer cannot " +
                    "reconstruct the ADD CONSTRAINT clause.",
                if (down) "CONSTRAINT_ROLLBACK_EXPRESSION_MISSING" else "ORACLE_CHECK_EXPRESSION_MISSING",
                if (down) MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE else MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION,
            )
        }
        if (constraint.type == ConstraintType.FOREIGN_KEY && constraint.references == null) {
            return blockConstraint(
                op, ctx,
                "FOREIGN KEY constraint '${constraint.name}' on '$table' carries no reference target.",
                "ORACLE_FOREIGN_KEY_REFERENCE_MISSING",
                MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION,
            )
        }
        if (constraint.columns.isNullOrEmpty() &&
            (constraint.type == ConstraintType.UNIQUE || constraint.type == ConstraintType.FOREIGN_KEY)
        ) {
            // Ohne Spalten rendert der Helfer `UNIQUE ()` bzw. `FOREIGN KEY ()`
            // -- syntaktisch kaputt. PostgreSQL prueft dasselbe
            // (PostgresDiffSqlBuilders.constraintLine).
            return blockConstraint(
                op, ctx,
                "${constraint.type.name} constraint '${constraint.name}' on '$table' names no columns.",
                "ORACLE_CONSTRAINT_COLUMNS_MISSING",
                MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION,
            )
        }
        // Nur UNIQUE braucht die LOB-Spalten der Tabelle (Oracle laesst
        // CLOB/BLOB nicht als Schluesselspalte zu, ORA-02329). Fehlt die
        // Tabelle im Schema dieser Richtung, waere die Menge leer und der
        // Waechter entschaerfte sich still -- deshalb blockt dieser Fall.
        // CHECK und FOREIGN KEY brauchen sie nicht und rendern weiter.
        val tableDef = ctx.schemaForDirection()?.tables?.get(table)
        if (constraint.type == ConstraintType.UNIQUE && tableDef == null) {
            return blockConstraint(
                op, ctx,
                "Operation ${op.id} needs table '$table' from the schema of this rendering direction to decide " +
                    "whether the UNIQUE constraint's columns are key-eligible (Oracle rejects CLOB/BLOB keys).",
                "ORACLE_TABLE_NOT_IN_SCHEMA",
                MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
            )
        }
        val notes = mutableListOf<TransformationNote>()
        val clause = columnHelper.generateConstraintClause(table, constraint, tableDef?.let(::lobColumns).orEmpty(), notes)
            ?: return blockConstraint(
                op, ctx,
                "Constraint '${constraint.name}' on '$table' is not renderable for Oracle" +
                    notes.firstOrNull()?.let { ": ${it.message}" }.orEmpty(),
                "ORACLE_CONSTRAINT_NOT_RENDERABLE",
                MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
            )
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} ADD $clause;")
        ctx.carryOverNotes(op, notes)
    }

    // ── Indices ──────────────────────────────────

    fun renderAddIndex(op: DiffOperation.AddIndex, ctx: OracleDiffRenderContext) {
        val table = op.objectRef.path[0]
        if (ctx.direction == OracleRenderDirection.DOWN) {
            ctx.emit(op, dropIndexSql(ctx, table, op.index))
            return
        }
        emitCreateIndex(op, ctx, table, op.index)
    }

    fun renderDropIndex(op: DiffOperation.DropIndex, ctx: OracleDiffRenderContext) {
        val table = op.objectRef.path[0]
        if (ctx.direction == OracleRenderDirection.DOWN) {
            emitCreateIndex(op, ctx, table, op.index)
            return
        }
        ctx.emit(op, dropIndexSql(ctx, table, op.index))
    }

    /** `DROP INDEX` nennt in Oracle nur den Index, nicht die Tabelle. */
    private fun dropIndexSql(ctx: OracleDiffRenderContext, table: String, index: IndexDefinition): String =
        "DROP INDEX ${ctx.sql.quote(indexBuilder.effectiveName(table, index))};"

    private fun emitCreateIndex(
        op: DiffOperation,
        ctx: OracleDiffRenderContext,
        table: String,
        index: IndexDefinition,
    ) {
        val tableDef = ctx.schemaForDirection()?.tables?.get(table)
            ?: return blockConstraint(
                op, ctx,
                "Operation ${op.id} needs table '$table' from the schema of this rendering direction to decide " +
                    "whether the index is renderable (large-object and geometry columns are not indexable).",
                "ORACLE_TABLE_NOT_IN_SCHEMA",
                MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
            )
        val stmt = indexBuilder.render(table, tableDef, index, lobColumns(tableDef))
        if (stmt.sql.isBlank()) {
            // Leeres SQL heisst: der Index ist nicht renderbar (E057 Volltext,
            // E052 Spatial, W152 LOB-Spalte). Die Notiz traegt den Grund.
            return blockConstraint(
                op, ctx,
                "Index '${indexBuilder.effectiveName(table, index)}' on '$table' is not renderable for Oracle" +
                    stmt.notes.firstOrNull()?.let { ": ${it.message}" }.orEmpty(),
                "ORACLE_INDEX_NOT_RENDERABLE",
                MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
            )
        }
        ctx.emit(op, stmt.sql)
        ctx.carryOverNotes(op, stmt.notes)
    }

    // ── Shared ───────────────────────────────────

    private fun lobColumns(table: TableDefinition): Set<String> =
        table.columns.filterValues { typeMapper.isLargeObject(it.type) }.keys

    private fun blockConstraint(
        op: DiffOperation,
        ctx: OracleDiffRenderContext,
        message: String,
        code: String,
        reason: MigrationBlockedReason,
    ) {
        ctx.skip(op, "Operation ${op.id}: $message", code = code)
        ctx.addBlocker(reason, setOf(op.id))
    }
}
