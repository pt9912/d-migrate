package dev.dmigrate.cli.commands

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.RoutineBodyDisplay
import dev.dmigrate.driver.SqliteLiveCatalog
import dev.dmigrate.driver.migration.ExecutionRecoverability
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.MigrationExecutionStatementGroup
import java.nio.file.Path

/**
 * Runner for `schema migrate` per `spec/cli-spec.md §6.1`.
 *
 * Slices delivered:
 *
 * - **E.1**: file-to-file mode. `--dialect` mandatory.
 * - **E.2**: DB-target (and DB-source) mode via the injected
 *   `dbLoader`.
 * - **E.3/G.2**: `--generate-rollback` + `d-migrate rollback-sql v2`
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
 * The runner is now a thin orchestrator. Pre-render concerns live in
 * [SchemaMigratePreparation]; render concerns in
 * [SchemaMigrateRenderPipeline]; the `--execute` slice and post-
 * compare in [SchemaMigrateExecutionStage]; rollback-artefact
 * composition in [SchemaMigrateRollbackComposer]; and every artefact
 * write goes through [SchemaMigrateArtefactSink]. The runner keeps
 * ownership of the central plan + overlay-preflight step and the
 * exit-code routing in [finalize] so the exit-code state machine
 * stays in one place.
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
    private val executor: ExecutorFn? = null,
    private val sqliteLiveCatalogProbe: ((CompareOperand.Database, Path?) -> SqliteLiveCatalog)? = null,
    private val sqliteCastPreflightPlanner: SqliteCastPreflightPlannerFn? = null,
    private val sqliteCastPreflightProbe: SqliteCastPreflightProbeFn? = null,
    private val urlScrubber: (String) -> String = { it },
    private val ensureParentDirectories: (Path) -> Unit = { it.parent?.toFile()?.mkdirs() },
    private val atomicWriter: (Path, String) -> Unit = ::defaultAtomicWriter,
    private val renderReport: (SchemaMigrateReport, format: String) -> String,
    private val printError: (message: String, source: String) -> Unit,
    private val stdout: (String) -> Unit = { println(it) },
    private val stderr: (String) -> Unit = { System.err.println(it) },
    private val fingerprint: (SchemaDefinition) -> String = MigrationFingerprint::compute,
    private val clock: java.time.Clock = java.time.Clock.systemUTC(),
    private val createdByVersion: String = "d-migrate (dev)",
) {
    private val userFacingErrors = UserFacingErrors(urlScrubber)
    private val userFacingPrintError = userFacingErrors.printError(printError)
    private val userFacingStderr = userFacingErrors.stderrSink(stderr)

    private val artefactSink = SchemaMigrateArtefactSink(
        ensureParentDirectories = ensureParentDirectories,
        atomicWriter = atomicWriter,
        stdout = stdout,
        printError = userFacingPrintError,
        renderReport = renderReport,
        clock = clock,
    )

    private val preparation = SchemaMigratePreparation(
        operandParser = operandParser,
        fileLoader = fileLoader,
        dbLoader = dbLoader,
        normalizer = normalizer,
        rendererFor = rendererFor,
        printError = userFacingPrintError,
        stderr = userFacingStderr,
        renderReport = renderReport,
        ensureParentDirectories = ensureParentDirectories,
        atomicWriter = atomicWriter,
    )

    private val renderPipeline = SchemaMigrateRenderPipeline(
        sqliteLiveCatalogProbe = sqliteLiveCatalogProbe,
        sqliteCastPreflightPlanner = sqliteCastPreflightPlanner,
        sqliteCastPreflightProbe = sqliteCastPreflightProbe,
    )

    private val executionStage = SchemaMigrateExecutionStage(
        executor = executor,
        dbLoader = dbLoader,
        normalizer = normalizer,
        fingerprint = fingerprint,
        printError = userFacingPrintError,
    )

    private val rollbackComposer = SchemaMigrateRollbackComposer(createdByVersion)

    fun execute(
        request: SchemaMigrateRequest,
        cancellationToken: CancellationToken = CancellationToken.none(),
    ): Int {
        cancellationToken.throwIfCancellationRequested()
        val prepared = when (val r = preparation.prepare(request)) {
            is SchemaMigratePreparationResult.ExitEarly -> return r.exitCode
            is SchemaMigratePreparationResult.Ready -> r.prepared
        }
        cancellationToken.throwIfCancellationRequested()

        // F.4 cli-inline-overlay slice §3.3: build the synthetic
        // `cli-inline` overlay BEFORE plan() so it joins the normal
        // file-overlay pipeline. Parse-time errors (bad syntax,
        // duplicate `from` within the same invocation) exit 2 with
        // a CLI-validation message and never touch DB/I/O.
        // Use MigrationFingerprint.compute directly here — the
        // constructor-injected `fingerprint` lambda exists for the
        // ExecutionStage post-Up check (which a test may override),
        // but the pre-plan-overlay path MUST mirror what
        // DiffPlanner.endpoint() writes to plan.current.fingerprint
        // so a mock-fingerprint test cannot accidentally make the
        // overlay validate against one hash and the plan against
        // another.
        val currentFingerprint = MigrationFingerprint.compute(prepared.targetNormalized.schema)
        val desiredFingerprint = MigrationFingerprint.compute(prepared.sourceNormalized.schema)
        val inlineResult = InlineRenameOverlayBuilder.build(
            renameTableFlags = request.renameTableFlags,
            renameColumnFlags = request.renameColumnFlags,
            sourceFingerprint = currentFingerprint,
            targetFingerprint = desiredFingerprint,
            dialect = prepared.effectiveDialect.name.lowercase(java.util.Locale.ROOT),
            version = createdByVersion,
        )
        val mergedOverlays = when (inlineResult) {
            is InlineRenameOverlayResult.Built -> request.migrationOverlays + inlineResult.document
            is InlineRenameOverlayResult.Empty -> request.migrationOverlays
            is InlineRenameOverlayResult.ParseFailed -> {
                inlineResult.errors.forEach { userFacingPrintError(it, "--rename-table/--rename-column") }
                return 2
            }
        }

        val (plan, overlayPreflight) = computePlanAndOverlay(
            request, prepared,
            mergedOverlays = mergedOverlays,
            currentFingerprint = currentFingerprint,
            desiredFingerprint = desiredFingerprint,
        )
        val render = renderPipeline.run(
            request = request,
            targetOp = prepared.targetOp,
            dialect = prepared.effectiveDialect,
            renderer = prepared.renderer,
            plan = plan,
            overlayPreflight = overlayPreflight,
            cancellationToken = cancellationToken,
            mysqlServerVersion = prepared.targetNormalized.mysqlServerVersion,
        )

        val executionTrace = executionStage.maybeExecute(
            request, prepared.targetOp, render.executableCombined, cancellationToken,
        )
        val withExecution = executionStage.applyExecutionTrace(render.executableCombined, executionTrace)
        val postCompareOutcome = if (executionTrace != null && executionTrace.executionError == null) {
            executionStage.runPostCompare(request, prepared.sourceNormalized.schema, prepared.targetOp)
        } else {
            null
        }

        val report = SchemaMigrateReportBuilder.build(
            request,
            prepared.sourceResolved,
            prepared.targetResolved,
            plan,
            withExecution,
            prepared.effectiveDialect,
            render.renderedDown,
            render.catalogProbeMode,
            overlayPreflight.reportItems,
        )
        val rollbackArtefact = rollbackComposer.maybeBuildRollback(
            request, render.executableCombined, render.renderedDown,
            executionTrace, postCompareOutcome, plan, prepared.effectiveDialect,
        )
        val recoveryContext = rollbackComposer.buildRecoveryContextIfApplicable(
            request, render.executableCombined, render.renderedDown, plan, prepared.effectiveDialect,
        )
        return finalize(
            request, withExecution, report, rollbackArtefact,
            executionTrace, postCompareOutcome, recoveryContext,
        )
    }

    /**
     * Plan-2 §F.4 dependency-projection T1: validate overlays BEFORE
     * `planner.plan(...)` so a Rename-mapping blocker can surface as a
     * pre-plan failure. Naming inversion: the IS-state lives under
     * `target` (the live DB to mutate) and the SOLL-state under
     * `source` (the schema file). Fingerprints are computed up-front
     * via the same `MigrationFingerprint` the planner uses so the
     * pre-plan validation result is identical to the post-plan gate
     * when nothing blocks.
     */
    private fun computePlanAndOverlay(
        request: SchemaMigrateRequest,
        prep: SchemaMigratePrepared,
        mergedOverlays: List<MigrationOverlayDocument>,
        currentFingerprint: String,
        desiredFingerprint: String,
    ): Pair<DiffResult, MigrationOverlayPreflightResult> {
        val diff = comparator(prep.targetNormalized.schema, prep.sourceNormalized.schema)
        val overlayPreflight = MigrationOverlayPreflight.validateBeforePlan(
            documents = mergedOverlays,
            sourceFingerprint = currentFingerprint,
            targetFingerprint = desiredFingerprint,
            dialect = prep.effectiveDialect.name,
            loadFailures = request.migrationOverlayLoadFailures,
        )
        val capabilities = RenameProjectionCapabilitiesFactory.capabilitiesFor(request, prep.effectiveDialect)
        val plan = if (overlayPreflight.hasBlockers) {
            DiffResult.preplanBlocker(
                current = prep.targetNormalized.schema,
                desired = prep.sourceNormalized.schema,
                schemaDiff = diff,
                diagnostics = overlayPreflight.diagnostics,
                migrationOverlays = mergedOverlays,
                currentFingerprint = currentFingerprint,
                desiredFingerprint = desiredFingerprint,
            )
        } else {
            planner.plan(
                current = prep.targetNormalized.schema,
                desired = prep.sourceNormalized.schema,
                schemaDiff = diff,
                migrationOverlays = mergedOverlays,
                capabilities = capabilities,
            )
        }
        return plan to overlayPreflight
    }

    /**
     * F.5.c: report writing is ALWAYS the last step inside `finalize`,
     * so the report can carry the
     * [SchemaMigrateExecutionView.rollbackFinalized] tri-state with the
     * actual outcome of the rollback artefact write attempt (`true`
     * after success, `false` after a side-effect-bearing failure,
     * `null` when no rollback was requested or possible). Each terminal
     * branch routes through [SchemaMigrateArtefactSink.emitReportAndExit].
     */
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
            return artefactSink.emitReportAndExit(request, report, rollbackFinalized = null, baseExit = 8)
        }

        // --plan-only: report only (capability check for --generate-rollback inclusive).
        if (request.planOnly) {
            return artefactSink.emitReportAndExit(request, report, rollbackFinalized = null, baseExit = report.exitCode)
        }

        // --execute path skips Up-SQL artefact write (DB execution is the artefact).
        if (!request.execute) {
            val upWriteFailedExit = artefactSink.writeOrEchoUpSql(request, rendered)
            if (upWriteFailedExit != null) {
                return artefactSink.emitReportAndExit(
                    request, report, rollbackFinalized = null, baseExit = upWriteFailedExit,
                )
            }
        }

        // Execute-error path: Up did not finish cleanly. The trace already encodes
        // whether the Up partially landed; rollbackFinalized stays `null` because
        // no rollback artefact write was attempted.
        if (executionTrace?.executionError != null) {
            return artefactSink.emitReportAndExit(request, report, rollbackFinalized = null, baseExit = 5)
        }

        // Post-compare introspection failure after a successful Up: Up DID land
        // but we couldn't observe the new state. Plan §F.5.e + §F.5.h.
        if (postCompareOutcome is PostCompareOutcome.IntrospectionFailed) {
            val recoveryWrite = artefactSink.tryWriteRecoveryArtefact(
                request = request,
                recoveryContext = recoveryContext,
                allowedFingerprint = recoveryContext?.desiredFingerprint,
                postUpVerified = false,
            )
            val exit = if (recoveryWrite == RecoveryWriteOutcome.Failed) 7 else 5
            return artefactSink.emitReportAndExit(request, report, rollbackFinalized = false, baseExit = exit)
        }

        // Post-compare drift case: per Plan §F.5.g NO auto-recovery artefact.
        val driftCode = postCompareOutcome?.toDriftCode()
        if (driftCode != null) {
            return artefactSink.emitReportAndExit(request, report, rollbackFinalized = false, baseExit = driftCode)
        }

        // Down-SQL artefact emission — only after a clean execute (or no execute at all).
        // F.5.f: if the atomic write of --rollback-output fails AFTER a successful
        // Up + clean post-compare, fall back to a marked recovery artefact at the
        // .recovery.<ts>.rollback.sql path pinned to the observed Post-Up-FP.
        return when (finalizeRollbackArtefact(request, rollbackArtefact, postCompareOutcome, recoveryContext)) {
            RollbackFinalizeOutcome.Skipped ->
                artefactSink.emitReportAndExit(request, report, rollbackFinalized = null, baseExit = report.exitCode)
            RollbackFinalizeOutcome.Written ->
                artefactSink.emitReportAndExit(request, report, rollbackFinalized = true, baseExit = report.exitCode)
            RollbackFinalizeOutcome.WriteFailed ->
                artefactSink.emitReportAndExit(request, report, rollbackFinalized = false, baseExit = 7)
        }
    }

    private fun finalizeRollbackArtefact(
        request: SchemaMigrateRequest,
        rollbackArtefact: String?,
        postCompareOutcome: PostCompareOutcome?,
        recoveryContext: RecoveryContext?,
    ): RollbackFinalizeOutcome {
        if (rollbackArtefact == null) return RollbackFinalizeOutcome.Skipped
        val output = request.rollbackOutput ?: return RollbackFinalizeOutcome.Skipped
        if (artefactSink.writeRollbackArtefact(output, rollbackArtefact)) {
            return RollbackFinalizeOutcome.Written
        }
        val observedFp = (postCompareOutcome as? PostCompareOutcome.Clean)?.observedFingerprint
        artefactSink.tryWriteRecoveryArtefact(
            request = request,
            recoveryContext = recoveryContext,
            allowedFingerprint = observedFp,
            postUpVerified = true,
        )
        return RollbackFinalizeOutcome.WriteFailed
    }

    /**
     * Tri-state result of the rollback-artefact write attempt — matches
     * the [RecoveryWriteOutcome] enum style. [Skipped] means
     * `--generate-rollback` was off or `--rollback-output` was unset
     * (so the runner's `rollbackFinalized` report field stays `null`);
     * [Written] is the atomic-write success path; [WriteFailed] fires
     * when the atomic write threw and the recovery artefact (if any)
     * has already been written by the sink — the runner then elevates
     * the exit code to `7` per Plan §F.5.h.
     */
    private sealed interface RollbackFinalizeOutcome {
        data object Skipped : RollbackFinalizeOutcome
        data object Written : RollbackFinalizeOutcome
        data object WriteFailed : RollbackFinalizeOutcome
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
    val allowExtensionInstall: Boolean = false,
    val generateRollback: Boolean = false,
    val execute: Boolean = false,
    val dryRun: Boolean = false,
    val cliConfigPath: Path? = null,
    val migrationOverlays: List<MigrationOverlayDocument> = emptyList(),
    val migrationOverlayLoadFailures: List<MigrationOverlayLoadFailure> = emptyList(),
    /**
     * F.4 cli-inline-overlay slice: operator-supplied
     * `--rename-table <from>:<to>` flag values, repeatable.
     * Converted by the runner into a synthetic `cli-inline` overlay
     * before the first `DiffPlanner.plan(...)` call.
     */
    val renameTableFlags: List<String> = emptyList(),
    /**
     * F.4 cli-inline-overlay slice: operator-supplied
     * `--rename-column <table>.<from>:<table>.<to>` flag values,
     * repeatable. Both sides must share the same `<table>` prefix.
     */
    val renameColumnFlags: List<String> = emptyList(),
    /**
     * E.1 Routine-Migration Slice C.1.a: unsafe override for the
     * Display-/Diagnostic-Plane. When `true`, routine bodies appear
     * unmasked in the migration report; defaults to `false` so the
     * scrubbed `{hash, length, scrubbedPreview, scrubbingApplied}`
     * shape stays the default. The flag does NOT change Execution-
     * Plane output — DDL statements always carry the raw body.
     */
    val debugBody: Boolean = false,
)

/**
 * Maps the runner-level [SchemaMigrateRequest.debugBody] toggle to
 * the report-level [RoutineBodyDisplay] value. Centralised here so
 * every `SchemaMigrateReport` construction site sets it the same way.
 */
fun SchemaMigrateRequest.bodyDisplay(): RoutineBodyDisplay =
    if (debugBody) RoutineBodyDisplay.RAW_DEBUG else RoutineBodyDisplay.SCRUBBED_ONLY

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
    val materializedViews: List<SchemaMigrateMaterializedViewContractView> = emptyList(),
    val overlays: List<SchemaMigrateOverlayView> = emptyList(),
    val sqliteCastPreflights: List<SchemaMigrateSqliteCastPreflightView> = emptyList(),
    /**
     * F.4 T6: one entry per overlay-bound rename candidate that the
     * planner saw. Successful folds carry [SchemaMigrateRenameProjectionView.renameOperationId];
     * drop+add fallbacks set it to `null` and populate
     * [SchemaMigrateRenameProjectionView.fallbackOperationIds]
     * with the regular mapper-emitted op ids. The list is the only
     * public carrier for rename-projection metadata; report
     * consumers MUST NOT reconstruct entries from `diagnostics` or
     * operation ids.
     */
    val renameProjections: List<SchemaMigrateRenameProjectionView> = emptyList(),
    val summary: SchemaMigrateSummary,
    val execution: SchemaMigrateExecutionView? = null,
    /**
     * E.1 Routine-Migration Slice C.1.a: display-plane switch for
     * routine bodies in this report. Set from `request.debugBody` —
     * `RAW_DEBUG` lets the report renderer emit unmasked bodies,
     * `SCRUBBED_ONLY` (default) keeps the standard scrubbed shape.
     * Execution-Plane (the SQL statements) is unaffected.
     */
    val bodyDisplay: RoutineBodyDisplay = RoutineBodyDisplay.SCRUBBED_ONLY,
)

/**
 * F.4 T6 migrate-report view of a [RenameProjectionReport]. Public
 * application-layer DTO so JSON / YAML renderers can produce stable
 * field order independent of the internal core types.
 */
data class SchemaMigrateRenameProjectionView(
    val candidateId: String,
    val objectType: String,
    val fromPath: List<String>,
    val toPath: List<String>,
    val overlaySource: String,
    val overlayEntryId: String,
    val overlayHash: String?,
    val renameOperationId: String?,
    val fallbackOperationIds: List<String> = emptyList(),
    val fallbackReason: String? = null,
    val automatic: List<SchemaMigrateDependencyRefView> = emptyList(),
    val explicit: List<SchemaMigrateExplicitProjectionView> = emptyList(),
    val blockers: List<SchemaMigrateRenameBlockerView> = emptyList(),
)

data class SchemaMigrateDependencyRefView(
    val kind: String,
    val path: List<String>,
    val rationale: String,
)

data class SchemaMigrateExplicitProjectionView(
    val kind: String,
    val path: List<String>,
    val operationId: String,
)

data class SchemaMigrateRenameBlockerView(
    val code: String,
    val candidateId: String,
    val path: List<String>,
    val message: String,
    val severity: String,
)

data class SchemaMigrateExecutionView(
    val started: Boolean,
    val completed: Boolean,
    val statementsAttempted: Int,
    val lastStatementOperationIds: List<String>,
    val transactionRolledBack: Boolean,
    val sideEffectsPossible: Boolean,
    val executionError: String?,
    val statementGroups: List<SchemaMigrateStatementGroupView> = emptyList(),
    val recoverability: String? = null,
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

data class SchemaMigrateStatementGroupView(
    val statementGroupId: String,
    val operationIds: List<String>,
    val statementStartInclusive: Int,
    val statementEndExclusive: Int,
    val transactionScope: String,
    val transactionBoundary: String,
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

data class SchemaMigrateMaterializedViewContractView(
    val operationId: String,
    val action: String,
    val path: List<String>,
    val dialect: String,
    val status: String,
    val stalenessAfterUp: String,
    val refreshSteps: List<String>,
    val locking: String,
    val rollback: String,
)

data class SchemaMigrateOverlayView(
    val source: String,
    val entryId: String?,
    val overlayHash: String,
    val diagnosticCode: String,
    val severity: String,
)

data class SchemaMigrateSqliteCastPreflightView(
    val operationId: String,
    val dialect: String,
    val table: String,
    val column: String,
    val sourceType: String,
    val targetType: String,
    val status: String,
    val sqlHash: String,
    val totalRows: Long?,
    val failingRows: Long?,
    val sampleRowIds: List<String>,
    val problem: String?,
)

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

/**
 * Execution trace returned by the injected executor when `--execute`
 * is set. The runner copies these fields onto the combined
 * [MigrationDdlResult] so the report can surface them and downstream
 * artefact-writers know whether the rollback artefact is finalisable.
 */
data class ExecutionTrace(
    val executionStarted: Boolean,
    val executionCompleted: Boolean,
    val statementsAttempted: Int = 0,
    val lastStatementOperationIds: Set<String> = emptySet(),
    val transactionRolledBack: Boolean = false,
    val sideEffectsPossible: Boolean = false,
    val executionError: String? = null,
    val statementGroups: List<MigrationExecutionStatementGroup> = emptyList(),
    val recoverability: ExecutionRecoverability? = null,
)

internal fun ExecutionTrace.withG3Defaults(
    statementGroups: List<MigrationExecutionStatementGroup>,
): ExecutionTrace = copy(
    statementGroups = this.statementGroups.ifEmpty { statementGroups },
    recoverability = recoverability ?: MigrationExecutionStatusBuilder.recoverability(this),
)

/**
 * F.5.h — terminal status of the recovery-artefact write attempt.
 * `null` (caller-side) = no attempt was made (no recoveryContext, no
 * allowedFingerprint, or no `--rollback-output` path). The runner only
 * escalates the exit code to `7` when the value is [Failed].
 */
internal enum class RecoveryWriteOutcome { Written, Failed }

/**
 * Pre-built capability for emitting a **recovery** rollback artefact
 * from inside the finalize stage. Constructed once Down has rendered
 * cleanly so the failure-path branches don't need access to the full
 * plan/renderer state. `build(allowedFp, postUpVerified)` produces the
 * artefact text with `recovery=true` and a single-element
 * `allowedPostUpFingerprints=[allowedFp]`. `desiredFingerprint` is the
 * FP pinned for the F.5.e Introspection-Fail case.
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
 * - [Drift] — observed Post-Up fingerprint differs from desired; the
 *   runner exits `5` and (per Plan §F.5.g) MUST NOT auto-emit a
 *   recovery rollback artefact. The observed FP is carried for
 *   structured reporting.
 * - [IntrospectionFailed] — the target could not be re-read after Up.
 *   No observed FP exists. A recovery artefact emission for this case
 *   (with `desiredFp` as the only allowed post-up fingerprint) is the
 *   F.5.e sub-slice.
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
     * only by the drift-only branch in [SchemaMigrateRunner]'s
     * finalize stage — the recovery-emission branches dispatch on the
     * [PostCompareOutcome] subtype directly.
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
    /**
     * Plan-2 §A.1: true iff at least one rendered statement carries
     * `transactionBehavior = IMPLICIT_COMMIT`. Implies the runner
     * cannot guarantee a full rollback on later failure — MySQL DDL
     * is the canonical case. The runner's post-execute
     * `sideEffectsPossible` is a separate observation.
     */
    val planHasImplicitCommitDdl: Boolean = false,
    /**
     * Plan-2 §A.1: true iff every rendered statement carries
     * `transactionBehavior = FULLY_TRANSACTIONAL`. False when any
     * statement is `IMPLICIT_COMMIT`, `NOT_TRANSACTIONAL`, or
     * `UNKNOWN`. Empty plans are trivially rollbackable
     * (no statements ⇒ no risk).
     */
    val planFullyRollbackable: Boolean = true,
    /**
     * Plan-2 §A.1: true iff at least one rendered statement carries
     * `requiresExclusiveAccess = true`. Surfaces concurrency impact
     * without requiring callers to scan per-statement hints.
     */
    val planRequiresExclusiveAccess: Boolean = false,
    /**
     * Plan-2 §A.2: which input fed the SQLite-rebuild temp-name
     * collision catalog. `SCHEMA_ONLY` for file-to-file / non-SQLite /
     * SQLite-without-execute; `LIVE_SQLITE_MASTER` after a successful
     * probe. Reported as the enum name so consumers don't need to
     * import the dialect-specific type.
     */
    val catalogProbeMode: String = "SCHEMA_ONLY",
    /** Spatial profile used by the dialect renderer for this plan. */
    val spatialProfile: String? = null,
    /** Extension names required by rendered or blocked operations. */
    val requiredExtensions: List<String> = emptyList(),
    /** Required extension names whose target availability was verified. */
    val verifiedExtensions: List<String> = emptyList(),
    /** Required extension names missing or not verifiable for the target. */
    val missingExtensions: List<String> = emptyList(),
    /** Extension install statements rendered by an explicit policy; empty by default. */
    val extensionInstallStatements: List<String> = emptyList(),
)
