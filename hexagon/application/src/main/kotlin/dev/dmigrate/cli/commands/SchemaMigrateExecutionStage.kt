package dev.dmigrate.cli.commands

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.diff.routine.RoutineBodyLogRedactor
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.RoutineBodyDisplay
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveBatch
import dev.dmigrate.driver.migration.preserve.segmentForExecute
import java.nio.file.Path

/**
 * Pipeline stage for the `--execute` slice of `schema migrate`: invokes
 * the injected executor against the resolved DB target, captures the
 * trace, optionally re-introspects the target via [dbLoader] for a
 * post-Up content-fingerprint comparison, and lifts the trace fields
 * onto the rendered [MigrationDdlResult] so downstream report/artefact
 * code sees the unified state.
 *
 * Carved out of [SchemaMigrateRunner] to keep that class under Detekt's
 * `LargeClass` budget. Pure data transformation per call; instance
 * state is exclusively the injected ports.
 */
internal class SchemaMigrateExecutionStage(
    private val executor: SegmentAwareExecutorFn?,
    private val dbLoader: ((CompareOperand.Database, Path?) -> ResolvedSchemaOperand)?,
    private val normalizer: (ResolvedSchemaOperand) -> ResolvedSchemaOperand,
    private val fingerprint: (SchemaDefinition) -> String,
    private val printError: (message: String, source: String) -> Unit,
    private val lockTimeoutMillis: Long = DEFAULT_LOCK_TIMEOUT_MILLIS,
) {

    companion object {
        /**
         * Atomic-Preserve Phase C.1 default — mirrors §4.0 of the
         * lock-matrix and `AtomicSequencePreserveRunner.DEFAULT_LOCK_TIMEOUT_MILLIS`.
         * A future CLI / request slot may flow a per-call override
         * down into the executor.
         */
        const val DEFAULT_LOCK_TIMEOUT_MILLIS: Long = 5_000L
    }

    /**
     * Execute the rendered Up-SQL via the injected executor when
     * `--execute` is set and the plan isn't blocked. Returns null
     * for non-execute or blocked-plan paths so callers can short-
     * circuit. The executor is responsible for transaction handling
     * per the dialect's own contract — the stage only relays the
     * trace back into the report.
     */
    fun maybeExecute(
        request: SchemaMigrateRequest,
        target: CompareOperand,
        combined: MigrationDdlResult,
        atomicBatch: AtomicSequencePreserveBatch?,
        cancellationToken: CancellationToken,
    ): ExecutionTrace? {
        if (!request.execute) return null
        if (combined.isBlocked) return null
        val exec = executor
        if (exec == null) {
            printError("--execute requires an executor to be wired.", request.target)
            return ExecutionTrace(
                executionStarted = false,
                executionCompleted = false,
                executionError = "no executor wired",
            )
        }
        val dbOperand = target as? CompareOperand.Database
            ?: error("validateRequest must reject --execute with non-DB target before reaching the executor.")
        cancellationToken.throwIfCancellationRequested()
        val statementGroups = MigrationExecutionStatusBuilder.statementGroups(combined.statements)
        // Phase C.1: derive the segment-list view from the rendered
        // statements + the optional atomic-preserve batch (null when
        // no preserve candidates were classified). When the batch is
        // null the list degenerates to a single PlainSqlSegment and
        // the segment-aware executor's PG/MySQL/SQLite path runs
        // identically to the heutige JdbcMigrationExecutor flow.
        val segments = segmentForExecute(combined.statements, atomicBatch)
        return try {
            exec(dbOperand, request.cliConfigPath, segments, lockTimeoutMillis)
                .withG3Defaults(statementGroups)
        } catch (e: Exception) {
            // E.1 Slice F.1: JDBC driver exception messages frequently
            // quote a fragment of the failing SQL (e.g. PostgreSQL
            // `ERROR: syntax error at or near "BEGIN" Position: 42 ...`),
            // which on a `CREATE FUNCTION ... AS $$ body $$` failure
            // leaks the body into reports and stderr. Route through the
            // central log-redactor; `RAW_DEBUG` (via `--debug-body`)
            // bypasses scrubbing.
            val allowRaw = request.bodyDisplay() == RoutineBodyDisplay.RAW_DEBUG
            val rawMessage = e.message ?: e::class.simpleName
            ExecutionTrace(
                executionStarted = true,
                executionCompleted = true,
                statementsAttempted = combined.statements.size,
                lastStatementOperationIds = combined.statements.lastOrNull()?.operationIds.orEmpty(),
                transactionRolledBack = false,
                sideEffectsPossible = true,
                executionError = RoutineBodyLogRedactor.redact(rawMessage, allowRaw = allowRaw),
            ).withG3Defaults(statementGroups)
        }
    }

    /**
     * Post-compare hook: re-introspect the target after a successful
     * `--execute` and verify the resulting state matches the desired
     * Soll-schema. Returns one of three [PostCompareOutcome]s or null
     * when the post-compare is not applicable (no loader / not a DB
     * target). Uses [fingerprint] (a content-only hash) symmetric with
     * the rollback runner so the comparison is immune to file-side
     * `name`/`version` labels.
     */
    fun runPostCompare(
        request: SchemaMigrateRequest,
        desired: SchemaDefinition,
        target: CompareOperand,
    ): PostCompareOutcome? {
        val loader = dbLoader ?: return null
        val dbOperand = target as? CompareOperand.Database ?: return null
        val postResolved = try {
            loader(dbOperand, request.cliConfigPath)
        } catch (e: Exception) {
            printError(
                "Post-execute introspection failed: ${e.message} (post-compare skipped, drift unknown)",
                request.target,
            )
            return PostCompareOutcome.IntrospectionFailed
        }
        val postNormalized = try {
            normalizer(postResolved)
        } catch (e: IllegalStateException) {
            printError("Post-execute reverse marker error: ${e.message}", request.target)
            return PostCompareOutcome.IntrospectionFailed
        }
        val observed = fingerprint(postNormalized.schema)
        val desiredFp = fingerprint(desired)
        return if (observed == desiredFp) {
            PostCompareOutcome.Clean(observed)
        } else {
            printError(
                "Post-execute compare detected drift; the target does not match the desired schema. " +
                    "Per Plan §F.5.g no automatic recovery rollback artefact will be emitted on drift — " +
                    "operator must inspect the target manually before deciding on rollback.",
                request.target,
            )
            PostCompareOutcome.Drift(observed)
        }
    }

    /**
     * Lifts the execution trace fields onto the rendered Up-stream so
     * report/artefact code reads a unified [MigrationDdlResult]. Returns
     * [base] unchanged when no execution was performed.
     */
    fun applyExecutionTrace(base: MigrationDdlResult, trace: ExecutionTrace?): MigrationDdlResult =
        if (trace == null) base else base.copy(
            executionStarted = trace.executionStarted,
            executionCompleted = trace.executionCompleted,
            statementsAttempted = trace.statementsAttempted,
            lastStatementOperationIds = trace.lastStatementOperationIds,
            transactionRolledBack = trace.transactionRolledBack,
            sideEffectsPossible = trace.sideEffectsPossible,
            executionError = trace.executionError,
            executionStatementGroups = trace.statementGroups,
            recoverability = trace.recoverability,
        )
}
