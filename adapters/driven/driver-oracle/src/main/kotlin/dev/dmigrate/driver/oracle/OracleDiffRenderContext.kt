package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.diff.migration.Reversibility
import dev.dmigrate.core.diff.EnumCheckProjection
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.TransformationNote
import dev.dmigrate.driver.migration.DialectExecutionHints
import dev.dmigrate.driver.migration.LockBehavior
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationBlocker
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.MigrationDdlStatement
import dev.dmigrate.driver.migration.PlannerBlockerClassifier
import dev.dmigrate.driver.migration.TransactionBehavior
import dev.dmigrate.driver.migration.TransactionScope

/**
 * Rendering direction. Up emits the migration as planned; Down walks the
 * topo-sort in reverse and applies inverse semantics.
 */
internal enum class OracleRenderDirection { UP, DOWN }

/**
 * Mutable accumulator for one renderer invocation. Owns the statement list
 * and bookkeeping sets, projects per-op risk according to direction.
 */
internal class OracleDiffRenderContext(
    val direction: OracleRenderDirection,
    val sql: OracleDiffSqlBuilders,
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

    fun emit(op: DiffOperation, sqlText: String, hints: DialectExecutionHints = ORACLE_IMPLICIT_COMMIT_DDL_HINTS) {
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

    /**
     * Eine Anweisung des Tabellen-Neubaus. Sie gehoert nicht zu EINER
     * Operation, sondern zum ganzen Eimer: der Neubau erledigt alles, was an
     * der Tabelle haengt, in einer Folge. Wuerde nur die ausloesende Operation
     * als gerendert gelten, fiele der Rest aus der Buchhaltung.
     */
    fun emitRebuild(bucket: List<DiffOperation>, trigger: DiffOperation, sqlText: String) {
        val ids = bucket.map { it.id }.toSet()
        statements += MigrationDdlStatement(
            sql = sqlText,
            operationIds = ids,
            risk = rebuildRisk(bucket),
            phase = trigger.phase,
            transactionScope = TransactionScope.RUNNER_OWNED,
            hints = ORACLE_IMPLICIT_COMMIT_DDL_HINTS,
        )
        rendered += ids
        destructive += ids
        if (rebuildRisk(bucket).requiresManualConfirmation) manualActions += ids
        nonReversible += bucket.filter { it.reversibility == Reversibility.NOT_REVERSIBLE }.map { it.id }
    }

    /**
     * Das Risiko des ganzen Eimers. Der Neubau loescht und legt neu an, ist
     * also immer destruktiv und immer ein Tabellen-Neuschreiben; Datenverlust
     * und Bestaetigungspflicht erbt er von den Operationen darin.
     */
    private fun rebuildRisk(bucket: List<DiffOperation>): OperationRisk = OperationRisk(
        destructive = true,
        dataLossPossible = bucket.any { riskOrNull(it)?.dataLossPossible == true },
        requiresTableRewrite = true,
        requiresManualConfirmation = bucket.any { riskOrNull(it)?.requiresManualConfirmation == true },
    )

    /**
     * Bucht eine Operation als erledigt, OHNE eine Anweisung zu erzeugen.
     *
     * Fuer Faelle, in denen Oracle strukturell nichts zu tun hat -- ein
     * ENUM/DOMAIN-Custom-Type etwa hat hier kein Datenbankobjekt, er lebt an
     * der Spalte. Der Vertrag laesst das ausdruecklich zu: [MigrationDdlResult]
     * verlangt nur, dass jede ANWEISUNG eine gerenderte Operation hat, nicht
     * umgekehrt. Die Begruendung gehoert in die Diagnosen, nicht als
     * Pseudo-Anweisung ins SQL-Skript -- `statements` traegt auszufuehrendes
     * SQL, `diagnostics` traegt Erklaerungen.
     *
     * Die Risiko-Buchfuehrung laeuft wie bei [emit] weiter: die Risiken
     * stehen an der Operation, nicht am Dialekt, und duerfen nicht dadurch
     * verschwinden, dass dieser Dialekt nichts auszufuehren hat.
     */
    fun markRendered(op: DiffOperation) {
        rendered += op.id
        // `riskFor` verlangt ein Risiko der aktiven Richtung und bricht ab,
        // wenn keines da ist. Genau das ist hier der Normalfall: eine
        // NOT_REVERSIBLE-Operation hat in der DOWN-Richtung keines. Sie
        // trotzdem zu verlangen machte den Pfad, der total sein soll, zum
        // Absturz -- was der alte Kommentar-Platzhalter ebenso tat, nur
        // unbemerkt, weil der Dispatcher ihn nie erreichte.
        val risk = riskOrNull(op)
        if (risk?.destructive == true) destructive += op.id
        if (op.reversibility == Reversibility.NOT_REVERSIBLE) nonReversible += op.id
        if (risk?.requiresManualConfirmation == true) manualActions += op.id
    }

    /** Das Risiko der aktiven Richtung, oder `null`, wenn es keines gibt. */
    private fun riskOrNull(op: DiffOperation): OperationRisk? =
        if (direction == OracleRenderDirection.UP) op.risks.up else op.risks.down

    private fun riskFor(op: DiffOperation): OperationRisk =
        if (direction == OracleRenderDirection.UP) {
            op.risks.up
        } else {
            op.risks.down ?: error(
                "emit() called for op ${op.id} (reversibility=${op.reversibility}) in DOWN direction " +
                    "but risks.down is null; the dispatcher should have skipped or blocked first.",
            )
        }

    fun skip(op: DiffOperation, message: String, code: String = "ORACLE_RENDER_SKIP") {
        skipped += op.id
        diagnostics += DiffDiagnostic(
            code = code,
            message = message,
            severity = DiffDiagnostic.Severity.BLOCKER,
            operationId = op.id,
        )
    }

    fun addBlocker(reason: MigrationBlockedReason, operationIds: Set<String>) {
        blockers += MigrationBlocker(reason = reason, operationIds = operationIds)
    }

    fun warning(op: DiffOperation, message: String, code: String) {
        diagnostics += DiffDiagnostic(
            code = code,
            message = message,
            severity = DiffDiagnostic.Severity.WARNING,
            operationId = op.id,
        )
    }

    /** Traegt die Notizen eines wiederverwendeten Generate-Helfers (z. B. [OracleColumnConstraintHelper]) als Diagnosen nach. */
    fun carryOverNotes(op: DiffOperation, notes: List<TransformationNote>) {
        for (note in notes) {
            diagnostics += DiffDiagnostic(
                code = note.code,
                message = note.message,
                severity = when (note.type) {
                    NoteType.ACTION_REQUIRED, NoteType.WARNING -> DiffDiagnostic.Severity.WARNING
                    NoteType.INFO -> DiffDiagnostic.Severity.INFO
                },
                operationId = op.id,
            )
        }
    }

    /** Das Schema auf der Seite, die diese Richtung liest (UP=desired, DOWN=current). */
    fun schemaForDirection(): SchemaDefinition? =
        if (direction == OracleRenderDirection.UP) desiredSchema else currentSchema

    /** Das Schema der GEGENrichtung (UP=current, DOWN=desired). */
    fun schemaOppositeOfDirection(): SchemaDefinition? =
        if (direction == OracleRenderDirection.UP) currentSchema else desiredSchema

    fun addInfoDiagnostic(code: String, operationId: String, message: String) {
        diagnostics += DiffDiagnostic(
            code = code,
            message = message,
            severity = DiffDiagnostic.Severity.INFO,
            operationId = operationId,
        )
    }

    /**
     * Der Name des CHECKs, der den Wertevorrat von [column] aufzaehlt, auf der
     * Seite, von der **weg** geaendert wird — oder `null`, wenn dort keiner
     * steht.
     *
     * Oracle kennt kein `DROP CONSTRAINT IF EXISTS`: eine Anweisung auf einen
     * Constraint, den es nicht gibt, endet in ORA-02443. Gefragt wird deshalb
     * das zurueckgelesene Schema, nicht der Spaltentyp — dort steht der
     * Wertevorrat als eigener Constraint, waehrend die Spalte nur `VARCHAR2`
     * ist.
     */
    fun enumValueCheckName(table: String, column: String): String? =
        schemaOppositeOfDirection()?.tables?.get(table)?.constraints
            ?.firstOrNull {
                it.type == ConstraintType.CHECK && EnumCheckProjection.valuesOf(it.expression, column) != null
            }?.name

    /** Spaltendefinition von `table.column` auf der Seite, die diese Richtung liest. */
    fun columnFor(table: String, column: String): ColumnDefinition? =
        schemaForDirection()?.tables?.get(table)?.columns?.get(column)

    fun toResult(diff: DiffResult): MigrationDdlResult {
        val plannerBlockers = diff.diagnostics.filter { it.severity == DiffDiagnostic.Severity.BLOCKER }
        val combinedDiagnostics = plannerBlockers + diagnostics
        val effectiveBlockers = if (plannerBlockers.isEmpty()) {
            blockers
        } else {
            blockers + plannerBlockers
                .groupBy { PlannerBlockerClassifier.classify(it.code) }
                .map { (reason, diags) -> MigrationBlocker(reason = reason, diagnostics = diags) }
        }
        val primary = effectiveBlockers.firstOrNull()?.reason
        val requiresConfirmation = manualActions.isNotEmpty() || destructive.isNotEmpty()
        return MigrationDdlResult(
            statements = statements,
            operationsRendered = rendered,
            operationsSkipped = skipped,
            manualActions = manualActions,
            destructiveOperations = destructive,
            nonReversibleOperations = nonReversible,
            requiresConfirmation = requiresConfirmation,
            blockers = effectiveBlockers,
            primaryBlockedReason = primary,
            diagnostics = combinedDiagnostics,
            spatialProfile = options.spatialProfile.name,
        )
    }

    companion object {
        /**
         * Oracle-DDL committet implizit vor UND nach jeder Anweisung (wie MySQL,
         * anders als PostgreSQL/SQL Server) -- kein Rollback ueber mehrere
         * Statements hinweg moeglich.
         */
        internal val ORACLE_IMPLICIT_COMMIT_DDL_HINTS = DialectExecutionHints(
            transactionBehavior = TransactionBehavior.IMPLICIT_COMMIT,
            lockBehavior = LockBehavior.TABLE_EXCLUSIVE,
            implicitCommitPossible = true,
            sideEffectsPossible = true,
            requiresExclusiveAccess = true,
        )

        /**
         * View- und Custom-Type-DDL fasst keine Nutzdaten an -- sie schreibt
         * nur den Katalog. `IMPLICIT_COMMIT` bleibt (auch diese Anweisungen
         * committen in Oracle implizit), aber die Sperre ist leichter und
         * kein exklusiver Zugriff noetig.
         */
        internal val ORACLE_METADATA_DDL_HINTS = DialectExecutionHints(
            transactionBehavior = TransactionBehavior.IMPLICIT_COMMIT,
            lockBehavior = LockBehavior.METADATA,
            implicitCommitPossible = true,
            sideEffectsPossible = false,
            requiresExclusiveAccess = false,
        )
    }
}
