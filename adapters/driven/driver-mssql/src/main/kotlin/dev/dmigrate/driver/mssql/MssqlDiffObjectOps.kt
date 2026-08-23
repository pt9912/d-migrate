package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintReferenceDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * Renderer fuer Constraint- und Index-Operationen.
 *
 * Zwei T-SQL-Eigenheiten bestimmen den Inhalt:
 *
 * 1. **Gefilterte Indizes brauchen SET-Optionen zur DDL-Zeit** (Msg 1934).
 *    Slice 2a loest das fuer die Skript-Darstellung ueber eine Praeambel und
 *    Slice 3 fuer die Import-Session; der Migrate-Pfad fuehrt Statements
 *    einzeln ueber den Runner aus und ist von beidem nicht abgedeckt. Das
 *    gerenderte Statement traegt die Optionen deshalb selbst
 *    ([MssqlDiffSqlBuilders.withFilteredIndexSetOptions]).
 * 2. **Fremdschluessel mit Kaskade koennen Mehrfachpfade erzeugen** (Fehler
 *    1785). Der Generate-Pfad analysiert dafuer das ganze Schema; der Diff
 *    fuegt einzelne FKs hinzu, muss also gegen den ZIELZUSTAND pruefen — die
 *    Vereinigung aus vorhandenen und neuen Kanten, und die steht im
 *    Soll-Schema.
 */
internal object MssqlDiffObjectOps {

    fun renderAddIndex(op: DiffOperation.AddIndex, ctx: MssqlDiffRenderContext) {
        val table = op.objectRef.rootName
        if (ctx.direction == MssqlRenderDirection.DOWN) {
            ctx.emit(op, ctx.sql.dropIndexSql(table, op.index))
            return
        }
        emitCreateIndex(op, ctx, table, op.index)
    }

    fun renderDropIndex(op: DiffOperation.DropIndex, ctx: MssqlDiffRenderContext) {
        val table = op.objectRef.rootName
        if (ctx.direction == MssqlRenderDirection.DOWN) {
            emitCreateIndex(op, ctx, table, op.index)
            return
        }
        ctx.emit(op, ctx.sql.dropIndexSql(table, op.index))
    }

    fun renderAddConstraint(op: DiffOperation.AddConstraint, ctx: MssqlDiffRenderContext) {
        val table = op.objectRef.rootName
        if (ctx.direction == MssqlRenderDirection.DOWN) {
            ctx.emit(op, ctx.sql.dropConstraintSql(table, op.constraint.name))
            return
        }
        emitAddConstraint(op, ctx, table, op.constraint)
    }

    fun renderDropConstraint(op: DiffOperation.DropConstraint, ctx: MssqlDiffRenderContext) {
        val table = op.objectRef.rootName
        if (ctx.direction == MssqlRenderDirection.DOWN) {
            emitAddConstraint(op, ctx, table, op.constraint)
            return
        }
        ctx.emit(op, ctx.sql.dropConstraintSql(table, op.constraint.name))
    }

    // ── Gemeinsam genutzt, auch vom Spalten-Pfad ─────────────

    /**
     * Ein Index des Modells als `CREATE INDEX` — oder `null`, wenn er sich in
     * T-SQL nicht rendern laesst (dann ist die Operation bereits als Blocker
     * vermerkt).
     *
     * **Aufloesen vor dem ersten `emit`.** Wer erst emittiert und dann blockt,
     * legt die Operation in `rendered` UND `skipped`; die beiden Mengen muessen
     * disjunkt sein, und `MigrationDdlResult` erzwingt das mit `require()`.
     * Der Renderer flaege dann mit IllegalArgumentException statt einen Blocker
     * zu liefern.
     */
    fun resolveIndexSql(
        op: DiffOperation,
        ctx: MssqlDiffRenderContext,
        table: String,
        index: IndexDefinition,
        tableDef: TableDefinition? = null,
    ): String? {
        val schema = ctx.schemaForDirection()
        val effectiveTable = tableDef ?: schema?.tables?.get(table)
        if (schema == null || effectiveTable == null) {
            ctx.skip(
                op,
                "Operation ${op.id} needs table '$table' in the schema to render its index, " +
                    "but the DiffResult carries none for this direction.",
                code = "MSSQL_COLUMN_NOT_IN_SCHEMA",
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, setOf(op.id))
            return null
        }
        val statement = ctx.sql.createIndexStatement(table, effectiveTable, index, schema)
        if (statement.sql.isBlank()) {
            ctx.skip(
                op,
                "Index '${index.name ?: index.columnNames.joinToString("_")}' on '$table' is not renderable " +
                    "in T-SQL: " + statement.notes.joinToString("; ") { it.message },
                code = "DIALECT_UNSUPPORTED_OPERATION",
            )
            ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, setOf(op.id))
            return null
        }
        ctx.carryOverNotes(op, statement.notes)
        return ctx.sql.withFilteredIndexSetOptions(index, statement.sql)
    }

    /** Wie [resolveIndexSql], aber fuer Constraints. */
    fun resolveConstraintSql(
        op: DiffOperation,
        ctx: MssqlDiffRenderContext,
        table: String,
        constraint: ConstraintDefinition,
    ): String? {
        if (constraint.type == ConstraintType.EXCLUDE) {
            ctx.skip(
                op,
                "EXCLUDE constraint '${constraint.name}' on '$table' has no T-SQL equivalent.",
                code = "DIALECT_UNSUPPORTED_OPERATION",
            )
            ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, setOf(op.id))
            return null
        }
        val guard = ctx.cascadeGuard()
        val line = ctx.sql.constraintLine(table, constraint, guard)
        if (line == null) {
            ctx.skip(
                op,
                "Constraint '${constraint.name}' (${constraint.type}) on '$table' cannot be rendered: " +
                    "its definition is incomplete for T-SQL.",
                code = "DIALECT_UNSUPPORTED_OPERATION",
            )
            ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, setOf(op.id))
            return null
        }
        if (guard.mustNeutralise(constraint.name)) {
            ctx.warning(
                op,
                "Cascading referential action of '${constraint.name}' was rendered as NO ACTION: " +
                    "SQL Server rejects cascade cycles and multiple cascade paths (error 1785).",
                code = "E057",
            )
        }
        // `WITH CHECK` ist Absicht: SQL Server prueft einen nachtraeglich
        // hinzugefuegten FK oder CHECK per Default NICHT gegen Bestandsdaten
        // (der Constraint gilt dann als „not trusted"), und ein Constraint, dem
        // der Optimizer nicht traut, ist einer, den die Migration nur scheinbar
        // hergestellt hat.
        return "ALTER TABLE ${ctx.sql.quote(table)} WITH CHECK ADD $line;"
    }

    private fun emitCreateIndex(
        op: DiffOperation,
        ctx: MssqlDiffRenderContext,
        table: String,
        index: IndexDefinition,
    ) {
        resolveIndexSql(op, ctx, table, index)?.let { ctx.emit(op, it) }
    }

    private fun emitAddConstraint(
        op: DiffOperation,
        ctx: MssqlDiffRenderContext,
        table: String,
        constraint: ConstraintDefinition,
    ) {
        resolveConstraintSql(op, ctx, table, constraint)?.let { ctx.emit(op, it) }
    }

    /**
     * Ein `references` an der Spalte ist im Modell kein Constraint; der
     * Generate-Pfad macht daraus `fk_<tabelle>_<spalte>`. Wer den Fremdschluessel
     * abraeumt, muss ihn unter genau diesem Namen wieder anlegen — die Synthese
     * steht deshalb hier und nicht in jedem Aufrufer neu.
     */
    fun columnForeignKey(table: String, column: String, ref: ReferenceDefinition) = ConstraintDefinition(
        name = MssqlConstraintNames.foreignKey(table, column),
        type = ConstraintType.FOREIGN_KEY,
        columns = listOf(column),
        references = ConstraintReferenceDefinition(
            table = ref.table,
            columns = listOf(ref.column),
            onDelete = ref.onDelete,
            onUpdate = ref.onUpdate,
        ),
    )

    /**
     * Die Objekte, die einer Spaltenaenderung im Weg stehen (Msg 5074).
     *
     * Ein spaltenlevel `unique: true` steht in KEINER der beiden Modell-Listen —
     * der Generate-Pfad rendert es als `uq_<tabelle>_<spalte>` an der Spalte.
     * Es wird deshalb als Constraint nachgebildet, sonst bliebe es beim
     * `ALTER COLUMN` haengen.
     */
    fun dependentsOf(table: String, tableDef: TableDefinition, column: String): Dependents {
        val columnUnique = tableDef.columns[column]
            ?.takeIf { it.unique }
            ?.let {
                ConstraintDefinition(
                    name = MssqlConstraintNames.unique(table, column),
                    type = ConstraintType.UNIQUE,
                    columns = listOf(column),
                )
            }
        return Dependents(
            indices = tableDef.indices.filter { idx -> idx.columns.any { it.name == column } },
            constraints = tableDef.constraints.filter { column in (it.columns ?: emptyList()) } +
                listOfNotNull(columnUnique),
        )
    }

    data class Dependents(val indices: List<IndexDefinition>, val constraints: List<ConstraintDefinition>) {
        val isEmpty: Boolean get() = indices.isEmpty() && constraints.isEmpty()
    }
}
