package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.diff.migration.Reversibility
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.driver.NoteType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.TransformationNote
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.DialectExecutionHints
import dev.dmigrate.driver.migration.LockBehavior
import dev.dmigrate.driver.migration.MigrationBlocker
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.MigrationDdlStatement
import dev.dmigrate.driver.migration.PlannerBlockerClassifier
import dev.dmigrate.driver.migration.TransactionBehavior
import dev.dmigrate.driver.migration.TransactionScope

/** Render-Richtung. Up rendert wie geplant, Down laeuft die Topo-Sortierung rueckwaerts. */
internal enum class MssqlRenderDirection { UP, DOWN }

/**
 * Veraenderlicher Sammler fuer einen Renderlauf: Statement-Liste, die vier
 * Buchhaltungs-Mengen (gerendert, uebersprungen, destruktiv, nicht umkehrbar),
 * Blocker und Diagnosen — plus der Zugriff auf Ist- und Soll-Schema.
 *
 * Der Schema-Zugriff ist bei MSSQL kein Komfort, sondern Pflicht:
 * `ALTER TABLE … ALTER COLUMN` ist eine Voll-Neudeklaration, und weder
 * `AlterColumnType` noch `AlterColumnNullability` tragen beide dafuer noetigen
 * Werte. [columnFor] holt die fehlende Haelfte aus dem Schema der jeweiligen
 * Richtung; findet es sie nicht, ist das ein Blocker und keine Annahme.
 */
internal class MssqlDiffRenderContext(
    val direction: MssqlRenderDirection,
    val sql: MssqlDiffSqlBuilders,
    val options: DdlGenerationOptions,
    private val currentSchema: SchemaDefinition? = null,
    private val desiredSchema: SchemaDefinition? = null,
) {
    private val statements = mutableListOf<MigrationDdlStatement>()
    private val rendered = mutableSetOf<String>()
    private val skipped = mutableSetOf<String>()
    private val manualActions = mutableSetOf<String>()
    private val destructive = mutableSetOf<String>()
    private val nonReversible = mutableSetOf<String>()
    private val blockers = mutableListOf<MigrationBlocker>()
    private val diagnostics = mutableListOf<DiffDiagnostic>()

    fun emit(
        op: DiffOperation,
        sqlText: String,
        hints: DialectExecutionHints = MSSQL_TRANSACTIONAL_DDL_HINTS,
    ) {
        if (options.strictGapOperations && riskFor(op).hasGap) {
            if (!isSkipped(op)) {
                skip(
                    op,
                    "Operation ${op.id} renders with a visibility gap (`hasGap = true`) and " +
                        "`--strict-gap-operations` is set.",
                    code = "OPERATION_HAS_GAP_STRICT_BLOCKED",
                )
                addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, setOf(op.id))
            }
            return
        }
        statements += MigrationDdlStatement(
            sql = sqlText,
            operationIds = setOf(op.id),
            risk = riskFor(op),
            phase = op.phase,
            transactionScope = TransactionScope.RUNNER_OWNED,
            hints = hints,
        )
        rendered += op.id
        if (riskFor(op).destructive) destructive += op.id
        if (op.reversibility == Reversibility.NOT_REVERSIBLE) nonReversible += op.id
        if (riskFor(op).requiresManualConfirmation) manualActions += op.id
    }

    private fun riskFor(op: DiffOperation): OperationRisk =
        if (direction == MssqlRenderDirection.UP) {
            op.risks.up
        } else {
            op.risks.down ?: error(
                "emit() called for op ${op.id} (reversibility=${op.reversibility}) in DOWN direction " +
                    "but risks.down is null; the dispatcher should have skipped or blocked first.",
            )
        }

    /**
     * Die Spalte in dem Schema, das die **Zielrichtung** dieses Laufs
     * beschreibt: aufwaerts das Soll, abwaerts das Ist. `null` heisst „nicht
     * auffindbar" — der Aufrufer macht daraus einen Blocker.
     */
    fun columnFor(table: String, column: String): ColumnDefinition? {
        val schema = if (direction == MssqlRenderDirection.UP) desiredSchema else currentSchema
        return schema?.tables?.get(table)?.columns?.get(column)
    }

    /** Das Schema, das die Zielrichtung dieses Laufs beschreibt. */
    fun schemaForDirection(): SchemaDefinition? =
        if (direction == MssqlRenderDirection.UP) desiredSchema else currentSchema

    /**
     * Hinweise des Generate-Spalten-Helfers (W136, W140, E057 …) in die
     * Diagnosen uebernehmen. Sie gelten im Migrate-Pfad genauso — eine Spalte,
     * die beim Generieren eine Warnung wert war, ist es beim Migrieren auch.
     */
    fun carryOverNotes(op: DiffOperation, notes: List<TransformationNote>) {
        for (note in notes) {
            addDiagnostic(
                code = note.code,
                operationId = op.id,
                message = note.message,
                severity = when (note.type) {
                    NoteType.ACTION_REQUIRED, NoteType.WARNING -> DiffDiagnostic.Severity.WARNING
                    NoteType.INFO -> DiffDiagnostic.Severity.INFO
                },
            )
        }
    }

    fun skip(op: DiffOperation, message: String, code: String = "MSSQL_RENDER_SKIP") {
        skipped += op.id
        diagnostics += DiffDiagnostic(
            code = code,
            message = message,
            severity = DiffDiagnostic.Severity.BLOCKER,
            operationId = op.id,
        )
    }

    fun isSkipped(op: DiffOperation): Boolean = op.id in skipped

    fun addBlocker(reason: MigrationBlockedReason, operationIds: Set<String>) {
        blockers += MigrationBlocker(reason = reason, operationIds = operationIds)
    }

    fun addDiagnostic(
        code: String,
        operationId: String,
        message: String,
        severity: DiffDiagnostic.Severity = DiffDiagnostic.Severity.BLOCKER,
    ) {
        diagnostics += DiffDiagnostic(code, message, severity, operationId)
    }

    fun addInfoDiagnostic(code: String, operationId: String, message: String) =
        addDiagnostic(code, operationId, message, DiffDiagnostic.Severity.INFO)

    fun warning(op: DiffOperation, message: String, code: String) =
        addDiagnostic(code, op.id, message, DiffDiagnostic.Severity.WARNING)

    /**
     * Planner-Blocker gehoeren ins Ergebnis, nicht nur die des Renderers: ein
     * CLI-Konsument, der allein die `blockers`-Liste liest, muss JEDEN Grund
     * sehen, aus dem der Plan nicht laufen darf. Gruppierung je klassifiziertem
     * Grund wie bei den drei bestehenden Dialekten.
     */
    fun toResult(diff: DiffResult): MigrationDdlResult {
        val plannerBlockers = diff.diagnostics.filter { it.severity == DiffDiagnostic.Severity.BLOCKER }
        val effectiveBlockers = if (plannerBlockers.isEmpty()) {
            blockers.toList()
        } else {
            blockers + plannerBlockers
                .groupBy { PlannerBlockerClassifier.classify(it.code) }
                .map { (reason, diags) -> MigrationBlocker(reason = reason, diagnostics = diags) }
        }
        return MigrationDdlResult(
            statements = statements.toList(),
            operationsRendered = rendered.toSet(),
            operationsSkipped = skipped.toSet(),
            manualActions = manualActions.toSet(),
            destructiveOperations = destructive.toSet(),
            nonReversibleOperations = nonReversible.toSet(),
            requiresConfirmation = manualActions.isNotEmpty() || destructive.isNotEmpty(),
            blockers = effectiveBlockers,
            primaryBlockedReason = effectiveBlockers.firstOrNull()?.reason,
            diagnostics = plannerBlockers + diagnostics,
            spatialProfile = options.spatialProfile.name,
        )
    }

    companion object {
        /**
         * SQL Server fuehrt DDL **voll transaktional** aus — anders als MySQL
         * und wie PostgreSQL. Ein fehlgeschlagenes Statement rollt mit der
         * Transaktion zurueck, es gibt keinen impliziten Commit.
         *
         * `ALTER TABLE`, `DROP TABLE` und `CREATE TABLE` nehmen eine
         * Sch-Modification-Sperre auf die Tabelle; das ist exklusiv gegenueber
         * jedem Leser und Schreiber.
         */
        internal val MSSQL_TRANSACTIONAL_DDL_HINTS = DialectExecutionHints(
            transactionBehavior = TransactionBehavior.FULLY_TRANSACTIONAL,
            lockBehavior = LockBehavior.TABLE_EXCLUSIVE,
            implicitCommitPossible = false,
            sideEffectsPossible = false,
            requiresExclusiveAccess = true,
        )

        /**
         * `sp_rename` ist eine Systemprozedur, kein DDL-Statement. Sie laeuft
         * ebenfalls in der Transaktion, beruehrt aber nur den Katalog — und sie
         * hat eine Nebenwirkung, die DDL nicht hat: bestehende Verweise
         * (Sichten, Prozeduren, Constraint-Namen) werden **nicht** mitgezogen.
         */
        internal val MSSQL_RENAME_HINTS = DialectExecutionHints(
            transactionBehavior = TransactionBehavior.FULLY_TRANSACTIONAL,
            lockBehavior = LockBehavior.TABLE_EXCLUSIVE,
            implicitCommitPossible = false,
            sideEffectsPossible = true,
            requiresExclusiveAccess = true,
        )
    }
}
