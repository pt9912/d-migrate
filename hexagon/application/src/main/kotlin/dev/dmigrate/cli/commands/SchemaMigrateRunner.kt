package dev.dmigrate.cli.commands

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationDdlResult
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Runner for `schema migrate` per `spec/cli-spec.md §6.1`.
 *
 * Slices delivered:
 *
 * - **E.1**: file-to-file mode. `--dialect` mandatory.
 * - **E.2**: DB-target (and DB-source) mode via the injected
 *   `dbLoader`.
 * - **E.3**: `--generate-rollback` + `d-migrate rollback-sql v1`
 *   metadata block on the Down artefact.
 * - **E.4**: `--execute` runs the Up-SQL against the DB target via
 *   the injected `executor`, captures an `ExecutionTrace`, runs a
 *   post-compare against the desired schema, and only finalises the
 *   `--rollback-output` artefact if the post-compare is clean.
 *   Recovery (post-compare drift after a half-applied Up) surfaces
 *   as Exit 5 with the trace populated; full recovery-rollback
 *   artefact emission lands in Phase F.
 *
 * Pipeline: load → normalize → validate → compare → plan → render.
 *
 * Output:
 *
 * - Up-SQL artefact: with `--output` written atomically (write-temp,
 *   rename); without it, rendered to stdout when not `--plan-only`.
 * - Report: with `--report` written atomically; without it, only on
 *   `--plan-only` and only to stdout. `--execute` requires `--report`
 *   (Exit 2) — but `--execute` is rejected upstream in E.1.
 *
 * Exit-Code mapping per spec §6.1:
 *
 * - `0` — successful render (or no-op diff)
 * - `2` — invalid CLI args (missing `--dialect`, file-target with
 *   `--execute`, etc.)
 * - `3` — source/target schema validation error
 * - `7` — local I/O / planning / render / artefact-write error
 * - `8` — `MIGRATION_BLOCKED` (renderer or planner blockers)
 */
class SchemaMigrateRunner(
    private val operandParser: (String) -> CompareOperand = CompareOperandParser::parse,
    private val fileLoader: (CompareOperand.File) -> ResolvedSchemaOperand,
    private val dbLoader: ((CompareOperand.Database, Path?) -> ResolvedSchemaOperand)? = null,
    private val normalizer: (ResolvedSchemaOperand) -> ResolvedSchemaOperand = CompareOperandNormalizer::normalize,
    private val comparator: (SchemaDefinition, SchemaDefinition) -> SchemaDiff,
    private val planner: DiffPlanner = DiffPlanner(),
    private val rendererFor: (DatabaseDialect) -> DiffDdlGenerator?,
    /**
     * Executes the rendered Up-SQL against the resolved DB target.
     * Required when `--execute` is set; null otherwise. The runner
     * passes the target operand and the rendered statements; the
     * executor returns a trace populated according to its own
     * transaction policy.
     */
    private val executor: ExecutorFn? = null,
    private val urlScrubber: (String) -> String = { it },
    private val ensureParentDirectories: (Path) -> Unit = { it.parent?.toFile()?.mkdirs() },
    private val atomicWriter: (Path, String) -> Unit = ::defaultAtomicWriter,
    private val renderReport: (SchemaMigrateReport, format: String) -> String,
    private val printError: (message: String, source: String) -> Unit,
    private val stdout: (String) -> Unit = { println(it) },
    private val stderr: (String) -> Unit = { System.err.println(it) },
    /**
     * Computes a content fingerprint over a schema. Used by the post-
     * `--execute` drift check so observed and desired states are
     * compared via the same content-only contract that the rollback
     * runner uses for `TARGET_STATE_MISMATCH`. Default delegates to
     * [MigrationFingerprint.compute]; override only for tests.
     */
    private val fingerprint: (SchemaDefinition) -> String = MigrationFingerprint::compute,
    /**
     * Clock injected into the recovery-rollback-artefact path
     * derivation so the `<timestamp>` infix
     * (`<output>.recovery.<timestamp>.rollback.sql`, Plan §F.5.d) is
     * deterministic in tests. Production uses
     * [java.time.Clock.systemUTC]; tests pin a `Clock.fixed(...)`.
     */
    private val clock: java.time.Clock = java.time.Clock.systemUTC(),
    /** Embedded into the rollback artefact's `createdByVersion` field. */
    private val createdByVersion: String = "d-migrate (dev)",
) {
    private val userFacingErrors = UserFacingErrors(urlScrubber)
    private val userFacingPrintError = userFacingErrors.printError(printError)
    private val userFacingStderr = userFacingErrors.stderrSink(stderr)

    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")
    fun execute(
        request: SchemaMigrateRequest,
        cancellationToken: CancellationToken = CancellationToken.none(),
    ): Int {
        cancellationToken.throwIfCancellationRequested()

        // 1. CLI validation
        validateRequest(request)?.let { return it }

        // 2. Parse operands
        val sourceOp: CompareOperand
        val targetOp: CompareOperand
        try {
            sourceOp = operandParser(request.source)
            targetOp = operandParser(request.target)
        } catch (e: IllegalArgumentException) {
            userFacingPrintError("Invalid operand: ${e.message}", request.source)
            return 2
        }

        // --execute requires a DB target.
        if (request.execute && targetOp !is CompareOperand.Database) {
            userFacingPrintError("--execute requires a DB target (db:<url>).", request.target)
            return 2
        }

        // Output collision check (only meaningful for file operands)
        val filePaths = listOfNotNull(
            (sourceOp as? CompareOperand.File)?.path,
            (targetOp as? CompareOperand.File)?.path,
        ).toTypedArray()
        if (filePaths.isNotEmpty()) {
            request.output?.let { if (collidesWithOperand(it, *filePaths)) return 2 }
            request.report?.let { if (collidesWithOperand(it, *filePaths)) return 2 }
        }

        cancellationToken.throwIfCancellationRequested()

        // 3. Load + normalize + validate both
        val sourceResolved = loadOperand(sourceOp, request.source, request.cliConfigPath) ?: return lastExitCode
        val targetResolved = loadOperand(targetOp, request.target, request.cliConfigPath) ?: return lastExitCode

        val (sourceNormalized, targetNormalized) = try {
            normalizer(sourceResolved) to normalizer(targetResolved)
        } catch (e: IllegalStateException) {
            userFacingPrintError("Invalid reverse marker: ${e.message}", request.source)
            return 7
        }

        if (!sourceNormalized.validation.isValid || !targetNormalized.validation.isValid) {
            return emitValidationFailure(request, sourceNormalized, targetNormalized)
        }

        // 4. Resolve effective dialect
        val effectiveDialect = resolveDialect(request, targetResolved) ?: return 2

        // 5. Resolve renderer
        val renderer = rendererFor(effectiveDialect)
        if (renderer == null) {
            userFacingPrintError("No renderer registered for dialect ${effectiveDialect.name}", request.source)
            return 2
        }

        cancellationToken.throwIfCancellationRequested()

        // 6. Pipeline: compare → plan → render UP
        val diff = comparator(targetNormalized.schema, sourceNormalized.schema)
        val plan = planner.plan(targetNormalized.schema, sourceNormalized.schema, diff)
        cancellationToken.throwIfCancellationRequested()
        // Phase H.3b: when running with --execute, the SQL stream is
        // consumed by JdbcMigrationExecutor on a live JDBC connection
        // — the SQLite-rebuild renderer can emit runner-hook markers
        // for FK-state save/restore. --plan-only emits self-contained
        // SQL (STANDALONE) for external execution.
        val renderOptions = DdlGenerationOptions(
            executionMode = if (request.execute) {
                dev.dmigrate.driver.ExecutionMode.EXECUTE
            } else {
                dev.dmigrate.driver.ExecutionMode.STANDALONE
            },
        )
        val renderedUp = renderer.generateUp(plan, renderOptions)

        // 7. Block on destructive without --allow-destructive
        val effectiveUp = applyDestructiveGuard(renderedUp, request.allowDestructive)

        // 8. Render DOWN if --generate-rollback (lift any Down-side blockers into the result).
        // Down output is always STANDALONE — rollback consumption goes
        // through the artefact-body split (`SchemaRollbackRunner.
        // splitArtefactBody`), which reconstructs the SQL from a
        // self-contained text file and feeds it back through
        // `JdbcMigrationExecutor`. If a future `schema rollback --execute`
        // path needs runner-owned FK-state (Round-Trip-State-Compat for
        // rollbacks), this is where to set
        // `DdlGenerationOptions(executionMode = EXECUTE)` for the down
        // path — but only after the artefact format has a way to carry
        // the runner-hook semantics (today the artefact body is reparsed
        // as raw SQL and runner hooks would be ambiguous between
        // standalone and runner reads).
        val renderedDown = if (request.generateRollback) {
            cancellationToken.throwIfCancellationRequested()
            renderer.generateDown(plan, DdlGenerationOptions())
        } else {
            null
        }
        val combined = if (renderedDown == null) effectiveUp else mergeDownIntoUp(effectiveUp, renderedDown)

        // 9. Execute Up against the DB target if --execute. Skipped on blockers.
        val executionTrace = maybeExecute(request, targetOp, combined, cancellationToken)
        val withExecution = if (executionTrace != null) combined.copy(
            executionStarted = executionTrace.executionStarted,
            executionCompleted = executionTrace.executionCompleted,
            statementsAttempted = executionTrace.statementsAttempted,
            lastStatementOperationIds = executionTrace.lastStatementOperationIds,
            transactionRolledBack = executionTrace.transactionRolledBack,
            sideEffectsPossible = executionTrace.sideEffectsPossible,
            executionError = executionTrace.executionError,
        ) else combined

        // 10. Post-compare after a successful execute (if --execute and no error).
        val postCompareOutcome: PostCompareOutcome? =
            if (executionTrace != null && executionTrace.executionError == null) {
                runPostCompare(request, sourceNormalized.schema, targetOp)
            } else {
                null
            }

        // 11. Build report
        val report = buildReport(request, sourceResolved, targetResolved, plan, withExecution, effectiveDialect, renderedDown)

        // 12. Build the rollback artefact text if Down rendered cleanly AND the
        //     execute path didn't leave the target in an unknown state. The
        //     observed Post-Up-Fingerprint flows through so the metadata block
        //     can carry it (`postUpFingerprint`, `postUpVerified=true`) per
        //     §10 acceptance — see Plan F.5.b.
        val rollbackArtefact = maybeBuildRollback(
            request,
            combined,
            renderedDown,
            executionTrace,
            postCompareOutcome,
            plan,
            effectiveDialect,
        )
        // 12a. F.5.e/f: when --generate-rollback is set and Down rendered
        //      cleanly, prepare a recovery-artefact builder closure so the
        //      finalize-stage failure paths (Introspection-Fail, Write-Fail)
        //      can emit a marked recovery artefact with the appropriate
        //      `allowedPostUpFingerprints` set (see Plan §F.5.e/f).
        val recoveryContext: RecoveryContext? = if (
            request.generateRollback &&
            renderedDown != null &&
            !combined.isBlocked
        ) {
            RecoveryContext(
                build = { fp, verified ->
                    buildRecoveryArtefact(plan, renderedDown, effectiveDialect, fp, verified)
                },
                desiredFingerprint = plan.desired.fingerprint ?: "",
            )
        } else {
            null
        }

        // 13. Decide outcome
        return finalize(
            request,
            withExecution,
            report,
            rollbackArtefact,
            executionTrace,
            postCompareOutcome,
            recoveryContext,
        )
    }

    /**
     * Execute the rendered Up-SQL via the injected executor when
     * `--execute` is set and the plan isn't blocked. Returns null
     * for non-execute or blocked-plan paths so callers can short-
     * circuit. The executor is responsible for transaction handling
     * per the dialect's own contract — the runner only relays the
     * trace back into the report.
     */
    private fun maybeExecute(
        request: SchemaMigrateRequest,
        target: CompareOperand,
        combined: MigrationDdlResult,
        cancellationToken: CancellationToken,
    ): ExecutionTrace? {
        if (!request.execute) return null
        if (combined.isBlocked) return null
        val exec = executor
        if (exec == null) {
            userFacingPrintError("--execute requires an executor to be wired.", request.target)
            return ExecutionTrace(
                executionStarted = false,
                executionCompleted = false,
                executionError = "no executor wired",
            )
        }
        val dbOperand = target as? CompareOperand.Database
            ?: error("validateRequest must reject --execute with non-DB target before reaching the executor.")
        cancellationToken.throwIfCancellationRequested()
        return try {
            exec(dbOperand, combined.statements, request.cliConfigPath)
        } catch (e: Exception) {
            ExecutionTrace(
                executionStarted = true,
                executionCompleted = true,
                statementsAttempted = combined.statements.size,
                lastStatementOperationIds = combined.statements.lastOrNull()?.operationIds.orEmpty(),
                transactionRolledBack = false,
                sideEffectsPossible = true,
                executionError = e.message ?: e::class.simpleName,
            )
        }
    }

    /**
     * Post-compare hook: re-introspect the target after a successful
     * `--execute` and verify the resulting state matches the desired
     * Soll-schema. Returns one of three outcomes ([PostCompareOutcome]):
     *
     * - [PostCompareOutcome.Clean] — observed fingerprint equals the
     *   desired fingerprint; no drift. Carries the observed FP so the
     *   rollback artefact's metadata block can pin it as
     *   `postUpFingerprint` with `postUpVerified=true` (Plan §F.5.b,
     *   §10 acceptance).
     * - [PostCompareOutcome.Drift] — observed fingerprint differs.
     *   The runner exits `5` and (per Plan §F.5.g) MUST NOT auto-emit
     *   a recovery rollback artefact. The observed FP rides along for
     *   structured reporting (Plan §F.5.c).
     * - [PostCompareOutcome.IntrospectionFailed] — the target could
     *   not be re-read after Up (loader threw or reverse-marker
     *   normalization rejected the result). No observed FP exists;
     *   the runner exits `5`. A recovery artefact emission for this
     *   case (with `desiredFp` as the only allowed post-up
     *   fingerprint) lands in Plan §F.5.e.
     *
     * Uses [fingerprint] (a content-only hash, see
     * [MigrationFingerprint]) symmetric with
     * `verifyTargetMatchesArtefact` in [SchemaRollbackRunner] and
     * immune to the file-side YAML's user-chosen `name`/`version`
     * labels — those are not observable state in a live database and
     * would otherwise produce phantom drift on every real round-trip.
     */
    private fun runPostCompare(
        request: SchemaMigrateRequest,
        desired: SchemaDefinition,
        target: CompareOperand,
    ): PostCompareOutcome? {
        val loader = dbLoader ?: return null
        val dbOperand = target as? CompareOperand.Database ?: return null
        val postResolved = try {
            loader(dbOperand, request.cliConfigPath)
        } catch (e: Exception) {
            userFacingPrintError(
                "Post-execute introspection failed: ${e.message} (post-compare skipped, drift unknown)",
                request.target,
            )
            return PostCompareOutcome.IntrospectionFailed
        }
        val postNormalized = try {
            normalizer(postResolved)
        } catch (e: IllegalStateException) {
            userFacingPrintError("Post-execute reverse marker error: ${e.message}", request.target)
            return PostCompareOutcome.IntrospectionFailed
        }
        val observed = fingerprint(postNormalized.schema)
        val desiredFp = fingerprint(desired)
        return if (observed == desiredFp) {
            PostCompareOutcome.Clean(observed)
        } else {
            userFacingPrintError(
                "Post-execute compare detected drift; the target does not match the desired schema. " +
                    "Per Plan §F.5.g no automatic recovery rollback artefact will be emitted on drift — " +
                    "operator must inspect the target manually before deciding on rollback.",
                request.target,
            )
            PostCompareOutcome.Drift(observed)
        }
    }

    /**
     * Merge Down-rendering blockers into the Up result so the caller
     * sees a unified `MigrationDdlResult`. We don't replace Up's
     * statements / rendered ops — those are still useful for the
     * report — but Down's blockers (`ROLLBACK_NOT_POSSIBLE`,
     * `MANUAL_ACTION_REQUIRED` from `MANUAL_REQUIRED` operations,
     * `DIALECT_UNSUPPORTED_OPERATION` for un-renderable Down-paths)
     * propagate into `combined.blockers` so the runner exits 8.
     */
    private fun mergeDownIntoUp(up: MigrationDdlResult, down: MigrationDdlResult): MigrationDdlResult {
        if (down.blockers.isEmpty()) return up
        val merged = up.blockers + down.blockers
        val primary = up.primaryBlockedReason ?: down.primaryBlockedReason
        return up.copy(
            blockers = merged,
            primaryBlockedReason = primary,
            diagnostics = up.diagnostics + down.diagnostics,
        )
    }

    private fun maybeBuildRollback(
        request: SchemaMigrateRequest,
        combined: MigrationDdlResult,
        renderedDown: MigrationDdlResult?,
        executionTrace: ExecutionTrace?,
        postCompareOutcome: PostCompareOutcome?,
        plan: DiffResult,
        dialect: DatabaseDialect,
    ): String? {
        if (!request.generateRollback || renderedDown == null) return null
        if (combined.isBlocked) return null
        val executeOk = executionTrace == null ||
            (executionTrace.executionError == null && postCompareOutcome !is PostCompareOutcome.Drift &&
                postCompareOutcome !is PostCompareOutcome.IntrospectionFailed)
        if (!executeOk) return null
        // F.5.b: when post-compare confirmed the live target equals the
        // desired Soll, pin the OBSERVED fingerprint into the artefact and
        // mark `postUpVerified=true`. Without --execute (or before E.4
        // restructuring) we fall back to the planned desired fingerprint
        // and leave `postUpVerified=false`.
        val (postUpFp, postUpVerified) = when (postCompareOutcome) {
            is PostCompareOutcome.Clean -> postCompareOutcome.observedFingerprint to true
            else -> (plan.desired.fingerprint ?: "") to false
        }
        return buildRollbackArtefact(plan, renderedDown, dialect, postUpFp, postUpVerified)
    }

    private fun buildRollbackArtefact(
        plan: dev.dmigrate.core.diff.migration.DiffResult,
        down: MigrationDdlResult,
        dialect: DatabaseDialect,
        postUpFingerprint: String,
        postUpVerified: Boolean,
    ): String = SchemaMigrateRollbackArtefactBuilder.buildNormal(
        plan, down, dialect, postUpFingerprint, postUpVerified, createdByVersion,
    )

    private fun buildRecoveryArtefact(
        plan: dev.dmigrate.core.diff.migration.DiffResult,
        down: MigrationDdlResult,
        dialect: DatabaseDialect,
        allowedFingerprint: String,
        postUpVerified: Boolean,
    ): String = SchemaMigrateRollbackArtefactBuilder.buildRecovery(
        plan, down, dialect, allowedFingerprint, postUpVerified, createdByVersion,
    )

    private fun resolveDialect(request: SchemaMigrateRequest, target: ResolvedSchemaOperand): DatabaseDialect? {
        val targetDialect = target.dialect
        val requested = request.dialect
        return when {
            requested == null && targetDialect == null -> {
                userFacingPrintError(
                    "--dialect is required when neither source nor target is a database connection.",
                    request.source,
                )
                null
            }
            requested != null && targetDialect != null && requested != targetDialect -> {
                userFacingPrintError(
                    "Requested dialect ${requested.name} does not match target connection dialect " +
                        "${targetDialect.name} (TARGET_DIALECT_MISMATCH).",
                    request.target,
                )
                null
            }
            else -> requested ?: targetDialect
        }
    }

    private var lastExitCode: Int = 7

    private fun validateRequest(request: SchemaMigrateRequest): Int? {
        if (request.dryRun && request.execute) {
            userFacingPrintError("--dry-run and --execute are mutually exclusive.", request.source)
            return 2
        }
        if (request.execute && request.report == null) {
            userFacingPrintError("--execute requires --report (audit-trail).", request.source)
            return 2
        }
        if (request.execute && request.planOnly) {
            userFacingPrintError("--execute and --plan-only are mutually exclusive.", request.source)
            return 2
        }
        if (request.planOnly && request.rollbackOutput != null) {
            userFacingPrintError("--plan-only forbids --rollback-output.", request.source)
            return 2
        }
        if (request.generateRollback && !request.planOnly && request.rollbackOutput == null) {
            userFacingPrintError(
                "--generate-rollback requires --rollback-output (or use --plan-only for a capability check).",
                request.source,
            )
            return 2
        }
        if (!request.generateRollback && request.rollbackOutput != null) {
            userFacingPrintError("--rollback-output requires --generate-rollback.", request.source)
            return 2
        }
        return null
    }

    private fun collidesWithOperand(out: Path, vararg operands: Path): Boolean {
        val n = out.toAbsolutePath().normalize()
        for (op in operands) {
            if (n == op.toAbsolutePath().normalize()) {
                userFacingPrintError("Output path must not be the same as source or target", out.toString())
                return true
            }
        }
        return false
    }

    private fun loadOperand(
        operand: CompareOperand,
        rawRef: String,
        configPath: Path?,
    ): ResolvedSchemaOperand? = when (operand) {
        is CompareOperand.File -> try {
            fileLoader(operand)
        } catch (e: Exception) {
            userFacingPrintError("Failed to read schema file: ${e.message}", rawRef)
            lastExitCode = 7
            null
        }
        is CompareOperand.Database -> {
            val loader = dbLoader
            if (loader == null) {
                userFacingPrintError("Database operands require a DB loader to be wired.", rawRef)
                lastExitCode = 2
                null
            } else {
                try {
                    loader(operand, configPath)
                } catch (e: CompareConfigException) {
                    userFacingPrintError("Config/URL error: ${e.message}", rawRef)
                    lastExitCode = 7
                    null
                } catch (e: Exception) {
                    userFacingPrintError("Connection/metadata error: ${e.message}", rawRef)
                    lastExitCode = 4
                    null
                }
            }
        }
    }

    private fun emitValidationFailure(
        request: SchemaMigrateRequest,
        source: ResolvedSchemaOperand,
        target: ResolvedSchemaOperand,
    ): Int {
        for (e in source.validation.errors) userFacingStderr("source: ${e.code} ${e.message}")
        for (e in target.validation.errors) userFacingStderr("target: ${e.code} ${e.message}")
        val dialectName = request.dialect?.name ?: target.dialect?.name ?: source.dialect?.name ?: "UNKNOWN"
        val report = SchemaMigrateReport(
            status = "validation_failed",
            exitCode = 3,
            source = source.reference,
            target = target.reference,
            dialect = dialectName,
            planOnly = request.planOnly,
            blockers = emptyList(),
            diagnostics = emptyList(),
            operations = emptyList(),
            statements = null,
            summary = SchemaMigrateSummary(),
        )
        request.report?.let { writeReport(it, report, request.reportFormat) }
        return 3
    }

    private fun applyDestructiveGuard(
        rendered: MigrationDdlResult,
        allowDestructive: Boolean,
    ): MigrationDdlResult {
        if (allowDestructive || rendered.destructiveOperations.isEmpty()) return rendered
        if (rendered.blockers.any { it.reason == MigrationBlockedReason.DESTRUCTIVE_OPERATION_REQUIRES_CONFIRMATION }) {
            return rendered
        }
        val withGuard = rendered.copy(
            blockers = rendered.blockers + dev.dmigrate.driver.migration.MigrationBlocker(
                reason = MigrationBlockedReason.DESTRUCTIVE_OPERATION_REQUIRES_CONFIRMATION,
                operationIds = rendered.destructiveOperations,
            ),
        )
        return withGuard.copy(primaryBlockedReason = MigrationBlockedReason.DESTRUCTIVE_OPERATION_REQUIRES_CONFIRMATION)
    }

    private fun buildReport(
        request: SchemaMigrateRequest,
        source: ResolvedSchemaOperand,
        target: ResolvedSchemaOperand,
        plan: DiffResult,
        rendered: MigrationDdlResult,
        dialect: DatabaseDialect,
        renderedDown: MigrationDdlResult? = null,
    ): SchemaMigrateReport = SchemaMigrateReportBuilder.build(
        request, source, target, plan, rendered, dialect, renderedDown,
    )

    /**
     * F.5.c: report writing is now ALWAYS the last step inside
     * `finalize`, so the report can carry the
     * [SchemaMigrateExecutionView.rollbackFinalized] tri-state with
     * the actual outcome of the rollback artefact write attempt
     * (`true` after success, `false` after a side-effect-bearing
     * failure, `null` when no rollback was requested or possible).
     * Each terminal branch routes through [emitReportAndExit] so the
     * old "report first, artefacts later" race is gone.
     */
    @Suppress("ReturnCount", "LongParameterList")
    private fun finalize(
        request: SchemaMigrateRequest,
        rendered: MigrationDdlResult,
        report: SchemaMigrateReport,
        rollbackArtefact: String?,
        executionTrace: ExecutionTrace?,
        postCompareOutcome: PostCompareOutcome?,
        recoveryContext: RecoveryContext?,
    ): Int {
        // Blocker path: report-only, no Up-SQL or Down artefact.
        if (report.exitCode == 8) {
            return emitReportAndExit(request, report, rollbackFinalized = null, baseExit = 8)
        }

        // --plan-only: report only (capability check for --generate-rollback inclusive).
        if (request.planOnly) {
            return emitReportAndExit(request, report, rollbackFinalized = null, baseExit = report.exitCode)
        }

        // --execute path skips Up-SQL artefact write (DB execution is the artefact).
        if (!request.execute) {
            val upWriteFailedExit = writeOrEchoUpSql(request, rendered)
            if (upWriteFailedExit != null) {
                return emitReportAndExit(request, report, rollbackFinalized = null, baseExit = upWriteFailedExit)
            }
        }

        // Execute-error path: Up did not finish cleanly. The transactionRolledBack
        // / sideEffectsPossible fields on the trace already encode whether the Up
        // partially landed; rollbackFinalized stays `null` because no rollback
        // artefact write was attempted.
        if (executionTrace?.executionError != null) {
            return emitReportAndExit(request, report, rollbackFinalized = null, baseExit = 5)
        }

        // Post-compare introspection failure after a successful Up: Up DID land
        // but we couldn't observe the new state. Per Plan §F.5.e the recovery
        // artefact is finalised with `desiredFp` as the only allowed FP and
        // `postUpVerified=false`. Per Plan §F.5.h / §7.1 (Z. 1156-1159),
        // a recovery-write FAILURE elevates the exit code to `7`: the run
        // had a side effect AND no finalised rollback artefact, the
        // strongest local-error signal so an operator-facing playbook
        // distinguishes "post-compare drift, untouched FS" (Exit 5) from
        // "post-compare drift, recovery I/O dead too" (Exit 7).
        if (postCompareOutcome is PostCompareOutcome.IntrospectionFailed) {
            val recoveryWrite = tryWriteRecoveryArtefact(
                request = request,
                recoveryContext = recoveryContext,
                allowedFingerprint = recoveryContext?.desiredFingerprint,
                postUpVerified = false,
            )
            val exit = if (recoveryWrite == RecoveryWriteOutcome.Failed) 7 else 5
            return emitReportAndExit(request, report, rollbackFinalized = false, baseExit = exit)
        }

        // Post-compare drift case: per Plan §F.5.g NO auto-recovery artefact
        // — the observed state contradicts the Soll, so emitting an
        // executable recovery would risk further damage. Operator must
        // inspect manually.
        val driftCode = postCompareOutcome?.toDriftCode()
        if (driftCode != null) {
            return emitReportAndExit(request, report, rollbackFinalized = false, baseExit = driftCode)
        }

        // Down-SQL artefact emission — only after a clean execute (or no execute at all).
        // F.5.f: if the atomic write of the user-requested --rollback-output
        // fails AFTER a successful Up + clean post-compare, fall back to a
        // marked recovery artefact at the .recovery.<ts>.rollback.sql path
        // pinned to the OBSERVED Post-Up-Fingerprint
        // (`postUpVerified=true`). The user's path is provably untouched
        // (atomic-write failure left no partial bytes), and the recovery
        // artefact carries the actual observed state so a follow-up
        // `schema rollback --execute` can verify against it.
        val rollbackFinalized: Boolean? = when {
            rollbackArtefact == null -> null      // no --generate-rollback or Down was blocked
            request.rollbackOutput == null -> null // upstream validation guarantees this combo
            else -> {
                if (writeRollbackArtefact(request.rollbackOutput, rollbackArtefact)) {
                    true
                } else {
                    val observedFp = (postCompareOutcome as? PostCompareOutcome.Clean)?.observedFingerprint
                    tryWriteRecoveryArtefact(
                        request = request,
                        recoveryContext = recoveryContext,
                        allowedFingerprint = observedFp,
                        postUpVerified = true,
                    )
                    return emitReportAndExit(request, report, rollbackFinalized = false, baseExit = 7)
                }
            }
        }
        return emitReportAndExit(request, report, rollbackFinalized = rollbackFinalized, baseExit = report.exitCode)
    }

    /**
     * Writes (or stdout-echoes) the rendered Up-SQL when not in
     * `--execute` mode. Returns `null` on success or `7` on write
     * failure so callers can route through [emitReportAndExit] with
     * the proper exit code.
     *
     * Phase H Plan-Doc-Vertrag: SQLite-Rebuild-Streams (erkennbar an
     * `__dmg_rebuild_`-Stamp im SQL) bekommen einen Warn-Header
     * voran, der die Standalone-Pfad-Limitationen explizit macht
     * (Temp-Name-Probe nur gegen Schema-Modell; FK-State wird auf
     * pauschal ON gesetzt). Externe Runner sehen den Hinweis im
     * Artefakt; die Header-Zeilen sind reine SQL-Kommentare und
     * brechen keinen Parser.
     */
    private fun writeOrEchoUpSql(request: SchemaMigrateRequest, rendered: MigrationDdlResult): Int? {
        val body = rendered.statements.joinToString("\n\n") { it.sql }
        val upSql = renderSqlArtefactHeader(body) + body
        if (request.output == null) {
            stdout(upSql)
            return null
        }
        return try {
            ensureParentDirectories(request.output)
            atomicWriter(request.output, upSql)
            null
        } catch (e: Exception) {
            userFacingPrintError(
                "Failed to write up-SQL artefact: ${e.message}",
                request.output.toString(),
            )
            7
        }
    }

    /**
     * Phase H header: warns operators about the two standalone-pfad
     * Limitations of SQLite-Rebuild streams. Empty string when the
     * stream contains no SQLite-Rebuild sequences (PG/MySQL streams
     * don't need either caveat).
     */
    private fun renderSqlArtefactHeader(body: String): String {
        if (!body.contains("__dmg_rebuild_")) return ""
        return buildString {
            append("-- d-migrate schema migrate --plan-only artefact\n")
            append("-- \n")
            append("-- SQLite-Rebuild caveats for standalone execution:\n")
            append("-- \n")
            append("--   1. Temp-Name collision probe is against the schema-model only.\n")
            append("--      Ad-hoc objects in the live DB (CREATE INDEX outside the\n")
            append("--      schema, sqlite_stat* tables, etc.) may collide at execute-\n")
            append("--      time. Verify via `schema migrate --execute` against the\n")
            append("--      target — H.2.2 live `sqlite_master` probe is on 0.9.8+.\n")
            append("-- \n")
            append("--   2. The rebuild ends with `PRAGMA foreign_keys = ON;`. If the\n")
            append("--      prior state was OFF, it is NOT restored — use the d-migrate\n")
            append("--      runner (`schema migrate --execute`) for Round-Trip-State-Compat.\n")
            append("-- \n")
            append("-- See docs/planning/in-progress/diffresult-migration-plan.md §H for\n")
            append("-- the full contract.\n")
            append("\n")
        }
    }

    /** Returns true on successful atomic write, false on failure. */
    private fun writeRollbackArtefact(path: Path, artefact: String): Boolean = try {
        ensureParentDirectories(path)
        atomicWriter(path, artefact)
        true
    } catch (e: Exception) {
        userFacingPrintError(
            "Failed to write rollback artefact: ${e.message}",
            path.toString(),
        )
        false
    }

    /**
     * F.5.e/f/h: write a recovery rollback artefact to
     * `<--rollback-output>.recovery.<timestamp>.rollback.sql`. Caller
     * supplies the [allowedFingerprint] (desiredFp for F.5.e
     * Introspection-Fail, observedFp for F.5.f Write-Fail) and the
     * [postUpVerified] flag.
     *
     * Tri-state return per F.5.h:
     *
     * - `null` — no attempt made (no [recoveryContext], no
     *   [allowedFingerprint], or no `--rollback-output` path).
     *   Caller's exit-code stays at the baseline (no escalation).
     * - [RecoveryWriteOutcome.Written] — atomic write succeeded.
     *   Caller's exit-code stays at the baseline.
     * - [RecoveryWriteOutcome.Failed] — attempt fired but the
     *   atomic write threw (FS race, no atomic-replace, permission,
     *   …). Caller MUST elevate the exit code to `7` per Plan §7.1
     *   (Z. 1156-1159) — the run had a side effect AND no
     *   finalised rollback artefact, the strongest local-error
     *   signal. The user-facing error doubly emits a "Up bereits
     *   ausgeführt; manuelle Sicherung erforderlich" hint so the
     *   structured report's Side-Effect-signal is mirrored in the
     *   stderr stream.
     */
    private fun tryWriteRecoveryArtefact(
        request: SchemaMigrateRequest,
        recoveryContext: RecoveryContext?,
        allowedFingerprint: String?,
        postUpVerified: Boolean,
    ): RecoveryWriteOutcome? {
        val ctx = recoveryContext ?: return null
        val fp = allowedFingerprint ?: return null
        val output = request.rollbackOutput ?: return null
        val recoveryPath = RecoveryArtefactPath.recoveryPathFor(output, clock.instant())
        val artefact = ctx.build(fp, postUpVerified)
        return try {
            ensureParentDirectories(recoveryPath)
            atomicWriter(recoveryPath, artefact)
            RecoveryWriteOutcome.Written
        } catch (e: Exception) {
            userFacingPrintError(
                "Failed to write recovery rollback artefact: ${e.message}",
                recoveryPath.toString(),
            )
            userFacingPrintError(
                "Up was executed against the target but no finalised rollback artefact " +
                    "could be written; manual database recovery may be required.",
                request.target,
            )
            RecoveryWriteOutcome.Failed
        }
    }

    /**
     * Single end-of-pipeline report sink. Injects [rollbackFinalized]
     * into the report's execution view (when present) and writes the
     * report atomically when `--report` is set, else echoes to stdout
     * for the blocker- and plan-only branches that were the only ones
     * doing stdout-echo before the F.5.c restructuring.
     */
    private fun emitReportAndExit(
        request: SchemaMigrateRequest,
        report: SchemaMigrateReport,
        rollbackFinalized: Boolean?,
        baseExit: Int,
    ): Int {
        val finalReport = if (report.execution == null) {
            report
        } else {
            report.copy(execution = report.execution.copy(rollbackFinalized = rollbackFinalized))
        }
        request.report?.let { reportPath ->
            if (writeReport(reportPath, finalReport, request.reportFormat) == null) return 7
        }
        if (request.report == null && (baseExit == 8 || request.planOnly)) {
            stdout(renderReport(finalReport, request.reportFormat))
        }
        return baseExit
    }

    private fun writeReport(path: Path, report: SchemaMigrateReport, format: String): Unit? {
        return try {
            ensureParentDirectories(path)
            atomicWriter(path, renderReport(report, format))
            Unit
        } catch (e: Exception) {
            userFacingPrintError("Failed to write report: ${e.message}", path.toString())
            null
        }
    }

    private companion object {
        /**
         * Writes via a `<path>.tmp-<uuid>` sibling and then renames
         * atomically. Failure modes leave the original file unchanged.
         */
        fun defaultAtomicWriter(path: Path, content: String) {
            val tmp = path.resolveSibling("${path.fileName}.tmp-${java.util.UUID.randomUUID()}")
            tmp.writeText(content)
            try {
                java.nio.file.Files.move(
                    tmp,
                    path,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: Exception) {
                java.nio.file.Files.deleteIfExists(tmp)
                throw e
            }
        }
    }
}

// ── Request / Report DTOs ───────────────────────────────────────────

data class SchemaMigrateRequest(
    val source: String,
    val target: String,
    /**
     * Required for file-to-file mode; optional with DB-target — the
     * loader derives it from the connection. If both this field and a
     * DB-target dialect are present, they must match (Exit 2).
     */
    val dialect: DatabaseDialect? = null,
    val output: Path? = null,
    val report: Path? = null,
    val rollbackOutput: Path? = null,
    val reportFormat: String = "json",
    val planOnly: Boolean = false,
    val allowDestructive: Boolean = false,
    val generateRollback: Boolean = false,
    val execute: Boolean = false,
    val dryRun: Boolean = false,
    val cliConfigPath: Path? = null,
)

data class SchemaMigrateReport(
    val status: String,
    val exitCode: Int,
    val source: String,
    val target: String,
    val dialect: String,
    val planOnly: Boolean,
    val operations: List<SchemaMigrateOperationView>,
    val statements: List<SchemaMigrateStatementView>?,
    val blockers: List<SchemaMigrateBlockerView>,
    val diagnostics: List<SchemaMigrateDiagnosticView>,
    val summary: SchemaMigrateSummary,
    val execution: SchemaMigrateExecutionView? = null,
)

data class SchemaMigrateExecutionView(
    val started: Boolean,
    val completed: Boolean,
    val statementsAttempted: Int,
    val lastStatementOperationIds: List<String>,
    val transactionRolledBack: Boolean,
    val sideEffectsPossible: Boolean,
    val executionError: String?,
    /**
     * True iff Up-DDL was applied to the DB and stuck (executor was
     * started AND the runner-managed transaction wasn't rolled back).
     * Per Plan §F.5.c / §10: structured Side-Effect-signal so a
     * downstream operator can see "Up succeeded but rollback was not
     * finalised" (`upExecuted=true`, `rollbackFinalized=false`).
     * `false` for non-`--execute` runs.
     */
    val upExecuted: Boolean = false,
    /**
     * Tri-state finalisation status for the rollback artefact:
     * `true` after successful atomic write to `--rollback-output`,
     * `false` after Up succeeded but the rollback artefact could not
     * be finalised (post-compare drift, introspection failure, write
     * failure — see Plan §F.5.e/f/g/h),
     * `null` when `--generate-rollback` was not requested or when the
     * Down-render itself was blocked.
     */
    val rollbackFinalized: Boolean? = null,
)

data class SchemaMigrateOperationView(
    val id: String,
    val kind: String,
    val objectType: String,
    val path: List<String>,
    val phase: String,
    val reversibility: String,
    val rendered: Boolean,
    val skipped: Boolean,
)

data class SchemaMigrateStatementView(
    val sql: String,
    val operationIds: List<String>,
    val phase: String,
    val destructive: Boolean,
)

data class SchemaMigrateBlockerView(
    val reason: String,
    val operationIds: List<String>,
    val diagnosticCodes: List<String>,
)

data class SchemaMigrateDiagnosticView(
    val code: String,
    val severity: String,
    val message: String,
    val operationId: String?,
)

/**
 * Execution trace returned by the injected executor when
 * `--execute` is set. The runner copies these fields onto the
 * combined [MigrationDdlResult] so the report can surface them and
 * downstream artefact-writers know whether the rollback artefact
 * is finalisable.
 */
/**
 * Function type alias for the executor port. Bundled into a typealias
 * so the [SchemaMigrateRunner] constructor stays under Detekt's
 * MaxLineLength budget.
 */
typealias ExecutorFn = (
    target: CompareOperand.Database,
    statements: List<dev.dmigrate.driver.migration.MigrationDdlStatement>,
    configPath: Path?,
) -> ExecutionTrace

data class ExecutionTrace(
    val executionStarted: Boolean,
    val executionCompleted: Boolean,
    val statementsAttempted: Int = 0,
    val lastStatementOperationIds: Set<String> = emptySet(),
    val transactionRolledBack: Boolean = false,
    val sideEffectsPossible: Boolean = false,
    val executionError: String? = null,
)

/**
 * F.5.h — terminal status of the recovery-artefact write attempt.
 * `null` (caller-side) = no attempt was made (no recoveryContext,
 * no allowedFingerprint, or no `--rollback-output` path). The
 * runner only escalates the exit code to `7` when the value is
 * [Failed].
 */
internal enum class RecoveryWriteOutcome { Written, Failed }

/**
 * Pre-built capability for emitting a **recovery** rollback artefact
 * from inside [SchemaMigrateRunner.finalize]. Constructed in
 * `execute()` once Down has rendered cleanly so the failure-path
 * branches don't need access to the full plan/renderer state.
 *
 * `build(allowedFp, postUpVerified)` produces the artefact text with
 * `recovery=true` and a single-element
 * `allowedPostUpFingerprints=[allowedFp]`. `desiredFingerprint` is
 * the FP the caller pins for the F.5.e Introspection-Fail case.
 */
internal data class RecoveryContext(
    val build: (allowedFingerprint: String, postUpVerified: Boolean) -> String,
    val desiredFingerprint: String,
)

/**
 * Result of `runPostCompare`. Three terminal states the recovery
 * pipeline (Plan §F.5) discriminates on:
 *
 * - [Clean] — observed Post-Up content fingerprint equals desired;
 *   the runner finalises a normal `--rollback-output` artefact with
 *   the observed fingerprint and `postUpVerified=true`.
 * - [Drift] — observed Post-Up fingerprint differs from desired;
 *   the runner exits `5` and (per Plan §F.5.g) MUST NOT auto-emit a
 *   recovery rollback artefact. The observed FP is carried for
 *   structured reporting.
 * - [IntrospectionFailed] — the target could not be re-read after
 *   Up. No observed FP exists. A recovery artefact emission for this
 *   case (with `desiredFp` as the only allowed post-up fingerprint)
 *   is the F.5.e sub-slice.
 *
 * `null` (caller-side) is the non-applicable case: `--execute` was
 * not requested, or the executor reported an error before
 * post-compare could even attempt to read.
 */
internal sealed class PostCompareOutcome {

    abstract val observedFingerprint: String?

    data class Clean(override val observedFingerprint: String) : PostCompareOutcome()

    data class Drift(override val observedFingerprint: String) : PostCompareOutcome()

    data object IntrospectionFailed : PostCompareOutcome() {
        override val observedFingerprint: String? = null
    }

    /**
     * Maps the outcome to the runner's drift-exit-code shape: `null`
     * on a clean post-compare, `5` for both [Drift] and
     * [IntrospectionFailed] (the latter is escalated to `7` by the
     * caller via F.5.h when the recovery-write itself fails). Used
     * only by the drift-only branch in
     * [SchemaMigrateRunner.finalize] — the recovery-emission
     * branches dispatch on the [PostCompareOutcome] subtype directly.
     */
    fun toDriftCode(): Int? = when (this) {
        is Clean -> null
        is Drift, IntrospectionFailed -> 5
    }
}

data class SchemaMigrateSummary(
    val operationsTotal: Int = 0,
    val operationsRendered: Int = 0,
    val operationsSkipped: Int = 0,
    val statementsTotal: Int = 0,
    val destructiveCount: Int = 0,
    val manualActionCount: Int = 0,
    val nonReversibleCount: Int = 0,
    val primaryBlockedReason: String? = null,
    /** Number of statements in the Down-rendering, or null when --generate-rollback was off. */
    val downStatementsTotal: Int? = null,
    /** True iff the Down-rendering produced blockers (independent of Up-side blockers). */
    val downBlocked: Boolean = false,
)
