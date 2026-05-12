package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqliteLiveCatalog
import dev.dmigrate.driver.migration.MigrationBlocker
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationDdlResult
import java.nio.file.Path

/**
 * Plan-2 §A.2: the SQLite-live-`sqlite_master`-probe stage of
 * `schema migrate`. Lifted out of [SchemaMigrateRunner] to keep
 * the runner under Detekt's `LargeClass` budget; the boundary is
 * the [Outcome] sealed interface so the runner can match on the
 * pre-render decision without leaking probe internals.
 *
 * The stage runs the wired probe callback when the dialect is
 * SQLite and `--execute` is set; everything else classifies as
 * [Outcome.NotRun] (SQLite without execute / no callback wired /
 * file target) or [Outcome.NotApplicable] (non-SQLite dialect).
 * On callback exception the stage produces [Outcome.Failed], which
 * the runner converts into a synthetic blocked result via
 * [buildSqliteProbeFailureResult] — render and execute are
 * skipped entirely so no mutation runs.
 */
internal object SqliteProbeStage {

    /** Classification of the pre-render probe call. */
    sealed interface Outcome {
        /** Live probe ran and returned a catalog. SQLite + `--execute`. */
        data class Succeeded(val catalog: SqliteLiveCatalog) : Outcome
        /** Probe threw before completion. Triggers Exit 8 before render. */
        data class Failed(val message: String) : Outcome
        /** SQLite path that did not run the probe (no execute, file target, probe not wired). */
        data object NotRun : Outcome
        /** Non-SQLite dialect; probe is silently skipped without diagnostic. */
        data object NotApplicable : Outcome
    }

    /**
     * Invoke [probe] when the dialect is SQLite and the request
     * sets `--execute`. The result classifies the outcome; the
     * runner threads it into [DdlGenerationOptions] and the
     * post-render diagnostic stream.
     */
    fun run(
        probe: ((CompareOperand.Database, Path?) -> SqliteLiveCatalog)?,
        request: SchemaMigrateRequest,
        targetOp: CompareOperand,
        effectiveDialect: DatabaseDialect,
    ): Outcome {
        if (effectiveDialect != DatabaseDialect.SQLITE) return Outcome.NotApplicable
        if (!request.execute) return Outcome.NotRun
        val wiredProbe = probe ?: return Outcome.NotRun
        val dbTarget = targetOp as? CompareOperand.Database ?: return Outcome.NotRun
        return try {
            Outcome.Succeeded(wiredProbe(dbTarget, request.cliConfigPath))
        } catch (e: Exception) {
            Outcome.Failed(e.message ?: e::class.simpleName ?: "unknown error")
        }
    }

    /**
     * Synthetic blocked [MigrationDdlResult] emitted by the runner
     * when the probe throws. Carries a MANUAL_ACTION_REQUIRED
     * blocker and a `SQLITE_LIVE_CATALOG_PROBE_FAILED` diagnostic
     * with the failure message; downstream `maybeExecute`
     * short-circuits on `isBlocked` so no mutation runs.
     *
     * The blocker reason maps to existing
     * [MigrationBlockedReason.MANUAL_ACTION_REQUIRED] rather than
     * introducing a new enum value — the precise reason lives in
     * the diagnostic code, and `MigrationBlockedReason` extensions
     * are gated by Plan-2 §G.3.
     */
    fun buildFailureResult(message: String): MigrationDdlResult {
        val diagnostic = DiffDiagnostic(
            code = "SQLITE_LIVE_CATALOG_PROBE_FAILED",
            message = "SQLite live `sqlite_master` probe failed before render: $message. " +
                "Plan-2 §A.2 — execute is blocked before any mutating statement.",
            severity = DiffDiagnostic.Severity.BLOCKER,
        )
        val blocker = MigrationBlocker(
            reason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
            diagnostics = listOf(diagnostic),
        )
        return MigrationDdlResult(
            statements = emptyList(),
            operationsRendered = emptySet(),
            blockers = listOf(blocker),
            primaryBlockedReason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
            diagnostics = listOf(diagnostic),
        )
    }

    /**
     * Builds the `SQLITE_LIVE_CATALOG_NOT_RUN_FILE_TARGET` INFO
     * diagnostic that the runner appends to the rendered result
     * when SQLite was the dialect but no probe ran (file target,
     * non-execute, or probe not wired).
     */
    fun buildNotRunDiagnostic(): DiffDiagnostic = DiffDiagnostic(
        code = "SQLITE_LIVE_CATALOG_NOT_RUN_FILE_TARGET",
        message = "SQLite live `sqlite_master` probe was not run " +
            "(file target, non-execute, or probe not wired); temp-name " +
            "collision avoidance uses the schema-derived snapshot only. " +
            "Plan-2 §A.2.",
        severity = DiffDiagnostic.Severity.INFO,
    )
}
