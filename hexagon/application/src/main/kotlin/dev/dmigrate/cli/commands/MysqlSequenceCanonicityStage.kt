package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.MysqlSequenceCanonicityDeclaration
import dev.dmigrate.driver.MysqlSequenceCanonicityStatus
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationBlocker
import dev.dmigrate.driver.migration.MigrationDdlResult
import java.nio.file.Path

/**
 * E.3 MySQL Sequence Drift-Check Sub-Slice C (2026-05-20):
 * application-layer dispatcher for the live-DB drift probe. Wraps
 * the per-connection probe (which lives in the driver adapter as
 * [dev.dmigrate.driver.mysql.MysqlSequenceCanonicityProbeAdapter])
 * behind a single function reference so the CLI layer doesn't
 * depend on driver adapters directly.
 *
 * Same shape as [CheckPreflightStage] / [SqliteCastPreflightStage]:
 * three-state [Outcome] (Succeeded / Failed / NotRun) so
 * [SchemaMigrateRenderPipeline] can short-circuit before render on
 * a probe technical failure.
 */
typealias MysqlSequenceCanonicityProbeFn =
    (CompareOperand.Database, Path?, DiffResult) -> List<MysqlSequenceCanonicityDeclaration>

/**
 * Runs the MySQL helper-table drift probe when the request is
 * `--execute` against a MySQL database target and the plan
 * contains at least one sequence operation. Otherwise returns
 * [Outcome.NotRun] and the renderer sees an empty
 * `mysqlSequenceCanonicity` list on `DdlGenerationOptions` —
 * meaning no live verification ran, the renderer falls back to
 * the bootstrap-idempotency safety net from Sub-Slice F of the
 * MySQL Sequence Diff slice.
 *
 * ## Two failure-code paths (mirror [CheckPreflightStage]'s pair)
 *
 * - Per-probe failures inside an individual `*Probe.probeXxx`
 *   call (e.g. permission denied on
 *   `INFORMATION_SCHEMA.COLUMNS`) surface as one declaration
 *   with `status = PROBE_RUNTIME_ERROR` per affected probe; the
 *   renderer gate translates this to the
 *   `E124_MYSQL_SEQUENCE_DRIFT_PROBE_FAILED` diagnostic.
 * - Pre-probe wiring failures (connection-pool create, dialect
 *   resolver miss) bubble out of the probe function as
 *   Exceptions and are caught here in [run]. The stage stamps
 *   every collected declaration as `PROBE_RUNTIME_ERROR` AND
 *   emits a top-level `MYSQL_SEQUENCE_DRIFT_RUN_FAILED`
 *   diagnostic via [buildFailureResult] so the operator gets
 *   one unified header diagnostic plus per-op detail.
 */
object MysqlSequenceCanonicityStage {

    sealed interface Outcome {
        data class Succeeded(val declarations: List<MysqlSequenceCanonicityDeclaration>) : Outcome
        data class Failed(
            val message: String,
            val declarations: List<MysqlSequenceCanonicityDeclaration>,
        ) : Outcome
        data object NotRun : Outcome
    }

    fun run(
        probe: MysqlSequenceCanonicityProbeFn?,
        request: SchemaMigrateRequest,
        target: CompareOperand,
        dialect: DatabaseDialect,
        plan: DiffResult,
    ): Outcome {
        if (!request.execute) return Outcome.NotRun
        val dbTarget = target as? CompareOperand.Database ?: return Outcome.NotRun
        if (dialect != DatabaseDialect.MYSQL) return Outcome.NotRun
        if (probe == null) return Outcome.NotRun
        if (!hasSequenceOps(plan)) return Outcome.NotRun
        return try {
            Outcome.Succeeded(probe(dbTarget, request.cliConfigPath, plan))
        } catch (e: Exception) {
            val message = e.message ?: e::class.simpleName.orEmpty()
            Outcome.Failed(
                message = message,
                declarations = stampStageFailure(plan, message),
            )
        }
    }

    /**
     * Builds a [MigrationDdlResult] header for the pre-probe wiring
     * failure path. Used when [run] returns [Outcome.Failed] before
     * any rendering can proceed.
     */
    fun buildFailureResult(
        message: String,
        declarations: List<MysqlSequenceCanonicityDeclaration> = emptyList(),
    ): MigrationDdlResult {
        val diagnostic = DiffDiagnostic(
            code = "MYSQL_SEQUENCE_DRIFT_RUN_FAILED",
            message = "MySQL sequence drift-check failed before render/execute: $message",
            severity = DiffDiagnostic.Severity.BLOCKER,
        )
        return MigrationDdlResult(
            statements = emptyList(),
            operationsRendered = emptySet(),
            blockers = listOf(
                MigrationBlocker(
                    reason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
                    diagnostics = listOf(diagnostic),
                ),
            ),
            primaryBlockedReason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
            diagnostics = listOf(diagnostic),
            mysqlSequenceCanonicity = declarations,
        )
    }

    private fun hasSequenceOps(plan: DiffResult): Boolean =
        plan.operations.any {
            it is DiffOperation.CreateSequence ||
                it is DiffOperation.AlterSequence ||
                it is DiffOperation.DropSequence ||
                it is DiffOperation.RenameSequence
        }

    /**
     * Synthesises one `PROBE_RUNTIME_ERROR` declaration per
     * sequence op in the plan. When the probe function never
     * delivered a per-op result (it threw before producing any),
     * the renderer still needs a per-op declaration so the gate
     * can attribute the block to the right operation id.
     */
    private fun stampStageFailure(plan: DiffResult, message: String): List<MysqlSequenceCanonicityDeclaration> {
        val dialect = DatabaseDialect.MYSQL.name.lowercase()
        return plan.operations.mapNotNull { op ->
            val opName = when (op) {
                is DiffOperation.CreateSequence -> op.objectRef.rootName
                is DiffOperation.AlterSequence -> op.objectRef.rootName
                is DiffOperation.DropSequence -> op.objectRef.rootName
                is DiffOperation.RenameSequence -> op.fromName
                else -> return@mapNotNull null
            }
            MysqlSequenceCanonicityDeclaration(
                operationId = op.id,
                dialect = dialect,
                kind = dev.dmigrate.driver.MysqlSequenceCanonicityKind.SEQUENCE_ROW,
                objectName = opName,
                status = MysqlSequenceCanonicityStatus.PROBE_RUNTIME_ERROR,
                sqlHash = "stage-failure",
                problem = "MySQL sequence drift-check failed before render/execute: $message",
            )
        }
    }
}
