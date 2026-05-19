package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.driver.CheckPreflightDeclaration
import dev.dmigrate.driver.CheckPreflightStatus
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationBlocker
import dev.dmigrate.driver.migration.MigrationDdlResult
import java.nio.file.Path

/**
 * F.5 Sub-Slice E.4 (2026-05-19): cross-dialect CHECK live-data
 * preflight probe contract. Wraps the per-dialect probe in
 * (`PostgresCheckPreflightProbe` / `MysqlCheckPreflightProbe` /
 * `SqliteCheckPreflightProbe`) behind a single function reference
 * so the application layer doesn't depend on driver adapters
 * directly.
 *
 * The driving CLI binds this to `CheckPreflightProbeRunner::probe`,
 * which dispatches by [DatabaseDialect] inside the connection-pool
 * scope. The application-layer stage treats the reference as
 * opaque.
 */
typealias CheckPreflightProbeFn =
    (CompareOperand.Database, Path?, DiffResult, DatabaseDialect) -> List<CheckPreflightDeclaration>

/**
 * F.5 Sub-Slice E.4: cross-dialect counterpart to
 * [SqliteCastPreflightStage]. Runs the live CHECK preflight probe
 * (PG / MySQL / SQLite, chosen by the supplied [CheckPreflightProbeFn])
 * when the request is `--execute` against a database target. Surfaces
 * the same three-way [Outcome] shape so [SchemaMigrateRenderPipeline]
 * can short-circuit before render on a probe technical failure.
 *
 * The probe runs only when:
 *
 * - the request has `execute = true` (file-only paths leave the
 *   pre-planned `NOT_RUN_*` declarations untouched);
 * - the target is a [CompareOperand.Database];
 * - a [CheckPreflightProbeFn] was wired by the CLI;
 * - the pre-planned declaration list is non-empty (no CHECK Adds in
 *   the diff → nothing to probe).
 *
 * Otherwise the stage returns [Outcome.NotRun] and the renderer sees
 * the planner-emitted `NOT_RUN_FILE_TARGET` / `NOT_RUN_POLICY`
 * declarations from [MigrationPreflightPlan.checkPreflights].
 *
 * ## Two failure-code paths (do not collapse)
 *
 * Per-row probe failures inside a per-dialect `*Probe.probe(conn, diff)`
 * implementation are caught locally and surface as one
 * `PROBE_RUNTIME_ERROR` declaration per affected operation. The
 * renderer consults `CheckPreflightGate` which translates this to a
 * `CHECK_PREFLIGHT_RUNTIME_ERROR` diagnostic at render time. This
 * path is reachable only when the probe itself ran to completion
 * but at least one individual count-query threw — e.g. a missing
 * table or a malformed expression.
 *
 * Pre-probe wiring failures (connection-pool create, URL parse,
 * resolver miss) bubble out of the probe-runner as Exceptions and
 * are caught here in [run]. The stage stamps every pre-planned
 * declaration as `PROBE_RUNTIME_ERROR` AND emits a top-level
 * `CHECK_PREFLIGHT_RUN_FAILED` diagnostic via [buildFailureResult]
 * so the operator gets one unified header diagnostic plus the per-
 * declaration detail. This path is reachable when no per-op probe
 * ever ran — the probe pipeline failed before any query fired.
 *
 * Same shape as `SqliteCastPreflightStage` which uses the parallel
 * code pair `SQLITE_CAST_PREFLIGHT_RUN_FAILED` (stage) +
 * `SQLITE_CAST_PREFLIGHT_FAILED` (renderer).
 */
object CheckPreflightStage {

    sealed interface Outcome {
        data class Succeeded(val declarations: List<CheckPreflightDeclaration>) : Outcome
        data class Failed(
            val message: String,
            val declarations: List<CheckPreflightDeclaration>,
        ) : Outcome
        data object NotRun : Outcome
    }

    fun run(
        probe: CheckPreflightProbeFn?,
        request: SchemaMigrateRequest,
        target: CompareOperand,
        dialect: DatabaseDialect,
        plan: DiffResult,
        preflightPlan: MigrationPreflightPlan,
    ): Outcome {
        if (!request.execute) return Outcome.NotRun
        val dbTarget = target as? CompareOperand.Database ?: return Outcome.NotRun
        if (probe == null) return Outcome.NotRun
        if (preflightPlan.checkPreflights.isEmpty()) return Outcome.NotRun
        return try {
            Outcome.Succeeded(probe(dbTarget, request.cliConfigPath, plan, dialect))
        } catch (e: Exception) {
            val message = e.message ?: e::class.simpleName.orEmpty()
            Outcome.Failed(
                message = message,
                declarations = preflightPlan.checkPreflights.map { declaration ->
                    declaration.copy(
                        status = CheckPreflightStatus.PROBE_RUNTIME_ERROR,
                        problem = "CHECK preflight failed before render/execute: $message",
                    )
                },
            )
        }
    }

    fun buildFailureResult(
        message: String,
        declarations: List<CheckPreflightDeclaration> = emptyList(),
    ): MigrationDdlResult {
        val diagnostic = DiffDiagnostic(
            code = "CHECK_PREFLIGHT_RUN_FAILED",
            message = "CHECK preflight failed before render/execute: $message",
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
            checkPreflights = declarations,
        )
    }
}
