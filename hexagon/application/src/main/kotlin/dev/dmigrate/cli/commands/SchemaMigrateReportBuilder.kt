package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.TransactionBehavior

/**
 * `SchemaMigrateReport` construction split out of [SchemaMigrateRunner]
 * to keep the runner under Detekt's `LargeClass` budget. Pure data
 * transformation: Plan + render result + (optional) Down render +
 * dialect → the Report DTO consumed by `--report` and the stdout
 * fallback.
 *
 * `rollbackFinalized` is intentionally always `null` here because at
 * report-build time the rollback artefact write hasn't been attempted
 * yet. The runner's `finalize` step copies the report and updates
 * `execution.rollbackFinalized` with the actual outcome before the
 * report is written (Plan §F.5.c).
 */
internal object SchemaMigrateReportBuilder {

    @Suppress("LongParameterList")
    fun build(
        request: SchemaMigrateRequest,
        source: ResolvedSchemaOperand,
        target: ResolvedSchemaOperand,
        plan: DiffResult,
        rendered: MigrationDdlResult,
        dialect: DatabaseDialect,
        renderedDown: MigrationDdlResult?,
    ): SchemaMigrateReport {
        val isBlocked = rendered.isBlocked
        val isEmpty = plan.operations.isEmpty()
        val status = when {
            isBlocked -> "blocked"
            isEmpty -> "no_op"
            else -> "ok"
        }
        val exitCode = if (isBlocked) 8 else 0
        return SchemaMigrateReport(
            status = status,
            exitCode = exitCode,
            source = source.reference,
            target = target.reference,
            dialect = dialect.name,
            planOnly = request.planOnly,
            blockers = rendered.blockers.map { blocker ->
                SchemaMigrateBlockerView(
                    reason = blocker.reason.name,
                    operationIds = blocker.operationIds.toList(),
                    diagnosticCodes = blocker.diagnostics.map { d -> d.code },
                )
            },
            diagnostics = rendered.diagnostics.map { diag ->
                SchemaMigrateDiagnosticView(
                    code = diag.code,
                    severity = diag.severity.name,
                    message = diag.message,
                    operationId = diag.operationId,
                )
            },
            operations = plan.operations.map { op ->
                SchemaMigrateOperationView(
                    id = op.id,
                    kind = op::class.simpleName ?: "Unknown",
                    objectType = op.objectType.name,
                    path = op.objectRef.path,
                    phase = op.phase.name,
                    reversibility = op.reversibility.name,
                    rendered = op.id in rendered.operationsRendered,
                    skipped = op.id in rendered.operationsSkipped,
                )
            },
            statements = if (request.planOnly) null else rendered.statements.map { s ->
                SchemaMigrateStatementView(
                    sql = s.sql,
                    operationIds = s.operationIds.toList(),
                    phase = s.phase.name,
                    destructive = s.risk.destructive,
                )
            },
            summary = SchemaMigrateSummary(
                operationsTotal = plan.operations.size,
                operationsRendered = rendered.operationsRendered.size,
                operationsSkipped = rendered.operationsSkipped.size,
                statementsTotal = rendered.statements.size,
                destructiveCount = rendered.destructiveOperations.size,
                manualActionCount = rendered.manualActions.size,
                nonReversibleCount = rendered.nonReversibleOperations.size,
                primaryBlockedReason = rendered.primaryBlockedReason?.name,
                downStatementsTotal = renderedDown?.statements?.size,
                downBlocked = renderedDown?.isBlocked ?: false,
                planHasImplicitCommitDdl = rendered.statements.any {
                    it.hints.transactionBehavior == TransactionBehavior.IMPLICIT_COMMIT
                },
                planFullyRollbackable = rendered.statements.all {
                    it.hints.transactionBehavior == TransactionBehavior.FULLY_TRANSACTIONAL
                },
                planRequiresExclusiveAccess = rendered.statements.any { it.hints.requiresExclusiveAccess },
            ),
            execution = if (rendered.executionStarted || rendered.executionError != null) {
                SchemaMigrateExecutionView(
                    started = rendered.executionStarted,
                    completed = rendered.executionCompleted,
                    statementsAttempted = rendered.statementsAttempted,
                    lastStatementOperationIds = rendered.lastStatementOperationIds.toList(),
                    transactionRolledBack = rendered.transactionRolledBack,
                    sideEffectsPossible = rendered.sideEffectsPossible,
                    executionError = rendered.executionError,
                    // Up-DDL was applied to the DB iff the executor was
                    // started AND the runner-managed transaction wasn't
                    // rolled back. A clean rollback after a failed Up
                    // means no side effect — `upExecuted = false`.
                    upExecuted = rendered.executionStarted && !rendered.transactionRolledBack,
                    // `rollbackFinalized` is only known AFTER the
                    // artefact write attempt — populated by `finalize`
                    // via report.copy(...) before the report is written.
                    rollbackFinalized = null,
                )
            } else {
                null
            },
        )
    }
}
