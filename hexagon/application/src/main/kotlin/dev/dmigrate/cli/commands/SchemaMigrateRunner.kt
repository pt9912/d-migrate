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
        val renderedUp = renderer.generateUp(plan, DdlGenerationOptions())

        // 7. Block on destructive without --allow-destructive
        val effectiveUp = applyDestructiveGuard(renderedUp, request.allowDestructive)

        // 8. Render DOWN if --generate-rollback (lift any Down-side blockers into the result)
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
        val postCompareDriftCode: Int? =
            if (executionTrace != null && executionTrace.executionError == null) {
                runPostCompare(request, sourceNormalized.schema, targetOp)
            } else {
                null
            }

        // 11. Build report
        val report = buildReport(request, sourceResolved, targetResolved, plan, withExecution, effectiveDialect, renderedDown)

        // 12. Build the rollback artefact text if Down rendered cleanly AND the
        //     execute path didn't leave the target in an unknown state.
        val rollbackArtefact = maybeBuildRollback(
            request,
            combined,
            renderedDown,
            executionTrace,
            postCompareDriftCode,
            plan,
            effectiveDialect,
        )

        // 13. Decide outcome
        return finalize(request, withExecution, report, rollbackArtefact, executionTrace, postCompareDriftCode)
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
     * Soll-schema. Returns null on clean state or an exit code on
     * drift / introspection failure.
     *
     * Uses [fingerprint] (a content-only hash, see
     * [MigrationFingerprint]) instead of `SchemaDiff.isEmpty()` so
     * the check is symmetric with `verifyTargetMatchesArtefact` in
     * [SchemaRollbackRunner] and immune to the file-side YAML's
     * user-chosen `name`/`version` labels — those are not observable
     * state in a live database and would otherwise produce phantom
     * drift on every real round-trip.
     */
    private fun runPostCompare(
        request: SchemaMigrateRequest,
        desired: SchemaDefinition,
        target: CompareOperand,
    ): Int? {
        val loader = dbLoader ?: return null
        val dbOperand = target as? CompareOperand.Database ?: return null
        val postResolved = try {
            loader(dbOperand, request.cliConfigPath)
        } catch (e: Exception) {
            userFacingPrintError(
                "Post-execute introspection failed: ${e.message} (post-compare skipped, drift unknown)",
                request.target,
            )
            return 5
        }
        val postNormalized = try {
            normalizer(postResolved)
        } catch (e: IllegalStateException) {
            userFacingPrintError("Post-execute reverse marker error: ${e.message}", request.target)
            return 5
        }
        return if (fingerprint(postNormalized.schema) == fingerprint(desired)) {
            null
        } else {
            userFacingPrintError(
                "Post-execute compare detected drift; the target does not match the desired schema. " +
                    "TODO Phase F: emit a recovery rollback artefact when supported.",
                request.target,
            )
            5
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
        postCompareDriftCode: Int?,
        plan: DiffResult,
        dialect: DatabaseDialect,
    ): String? {
        if (!request.generateRollback || renderedDown == null) return null
        if (combined.isBlocked) return null
        val executeOk = executionTrace == null ||
            (executionTrace.executionError == null && postCompareDriftCode == null)
        if (!executeOk) return null
        return buildRollbackArtefact(plan, renderedDown, dialect)
    }

    private fun buildRollbackArtefact(
        plan: dev.dmigrate.core.diff.migration.DiffResult,
        down: MigrationDdlResult,
        dialect: DatabaseDialect,
    ): String {
        val downRisk = down.statements.fold(
            RollbackArtefactBuilder.Risk(false, false, false, down.operationsRendered),
        ) { acc, s ->
            RollbackArtefactBuilder.Risk(
                destructive = acc.destructive || s.risk.destructive,
                dataLossPossible = acc.dataLossPossible || s.risk.dataLossPossible,
                requiresManualConfirmation = acc.requiresManualConfirmation || s.risk.requiresManualConfirmation,
                operationIds = acc.operationIds,
            )
        }
        val currentFp = plan.current.fingerprint ?: ""
        val desiredFp = plan.desired.fingerprint ?: ""
        return RollbackArtefactBuilder.build(
            RollbackArtefactBuilder.Input(
                dialect = dialect,
                currentFingerprint = currentFp,
                desiredFingerprint = desiredFp,
                // Without --execute, the post-up state is the planned desired state.
                postUpFingerprint = desiredFp,
                postUpVerified = false,
                operationIds = down.operationsRendered,
                risk = downRisk,
                downStatements = down.statements,
                createdByVersion = createdByVersion,
            ),
        )
    }

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
            blockers = rendered.blockers.map {
                SchemaMigrateBlockerView(
                    reason = it.reason.name,
                    operationIds = it.operationIds.toList(),
                    diagnosticCodes = it.diagnostics.map { d -> d.code },
                )
            },
            diagnostics = rendered.diagnostics.map {
                SchemaMigrateDiagnosticView(
                    code = it.code,
                    severity = it.severity.name,
                    message = it.message,
                    operationId = it.operationId,
                )
            },
            operations = plan.operations.map {
                SchemaMigrateOperationView(
                    id = it.id,
                    kind = it::class.simpleName ?: "Unknown",
                    objectType = it.objectType.name,
                    path = it.objectRef.path,
                    phase = it.phase.name,
                    reversibility = it.reversibility.name,
                    rendered = it.id in rendered.operationsRendered,
                    skipped = it.id in rendered.operationsSkipped,
                )
            },
            statements = if (request.planOnly) null else rendered.statements.map {
                SchemaMigrateStatementView(
                    sql = it.sql,
                    operationIds = it.operationIds.toList(),
                    phase = it.phase.name,
                    destructive = it.risk.destructive,
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
                )
            } else {
                null
            },
        )
    }

    @Suppress("ReturnCount")
    private fun finalize(
        request: SchemaMigrateRequest,
        rendered: MigrationDdlResult,
        report: SchemaMigrateReport,
        rollbackArtefact: String?,
        executionTrace: ExecutionTrace?,
        postCompareDriftCode: Int?,
    ): Int {
        // Always emit the report when --report is set (also on blockers / errors).
        request.report?.let { writeReport(it, report, request.reportFormat) ?: return 7 }

        // On blocker: report-only — do not emit Up-SQL or Down artefact.
        if (report.exitCode == 8) {
            if (request.report == null) stdout(renderReport(report, request.reportFormat))
            return 8
        }

        // On --plan-only: report only (capability check for --generate-rollback inclusive).
        if (request.planOnly) {
            if (request.report == null) stdout(renderReport(report, request.reportFormat))
            return report.exitCode
        }

        // --execute path: skip Up-SQL artefact write (DB execution is the artefact).
        // Up-SQL artefact emission only when not executing.
        if (!request.execute) {
            val upSql = rendered.statements.joinToString("\n\n") { it.sql }
            if (request.output != null) {
                try {
                    ensureParentDirectories(request.output)
                    atomicWriter(request.output, upSql)
                } catch (e: Exception) {
                    userFacingPrintError(
                        "Failed to write up-SQL artefact: ${e.message}",
                        request.output.toString(),
                    )
                    return 7
                }
            } else {
                stdout(upSql)
            }
        }

        // Execute-error path: report carries the trace; exit 5 (MIGRATION_ERROR).
        if (executionTrace?.executionError != null) {
            return 5
        }
        // Post-compare drift: report drift, exit 5.
        if (postCompareDriftCode != null) {
            return postCompareDriftCode
        }

        // Down-SQL artefact emission — only after a clean execute (or no execute at all).
        if (rollbackArtefact != null && request.rollbackOutput != null) {
            try {
                ensureParentDirectories(request.rollbackOutput)
                atomicWriter(request.rollbackOutput, rollbackArtefact)
            } catch (e: Exception) {
                userFacingPrintError(
                    "Failed to write rollback artefact: ${e.message}",
                    request.rollbackOutput.toString(),
                )
                return 7
            }
        }
        return report.exitCode
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
