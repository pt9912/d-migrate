package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.RenameProjectionReport
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayReportItem
import dev.dmigrate.core.diff.routine.RoutineBodyLogRedactor
import dev.dmigrate.core.diff.routine.RoutineBodyScrubber
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.ExtensionAvailabilityStatus
import dev.dmigrate.driver.ExtensionDependencyReport
import dev.dmigrate.driver.RoutineBodyDisplay
import dev.dmigrate.driver.SqliteCatalogProbeMode
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

    fun build(
        request: SchemaMigrateRequest,
        source: ResolvedSchemaOperand,
        target: ResolvedSchemaOperand,
        plan: DiffResult,
        rendered: MigrationDdlResult,
        dialect: DatabaseDialect,
        renderedDown: MigrationDdlResult?,
        catalogProbeMode: SqliteCatalogProbeMode = SqliteCatalogProbeMode.SCHEMA_ONLY,
        overlayReportItems: List<MigrationOverlayReportItem> = emptyList(),
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
            diagnostics = mergeDiagnostics(plan, rendered),
            materializedViews = buildMaterializedViewContracts(plan, rendered, dialect),
            overlays = overlayReportItems.map { item ->
                SchemaMigrateOverlayView(
                    source = item.source,
                    entryId = item.entryId,
                    overlayHash = item.overlayHash,
                    diagnosticCode = item.diagnosticCode,
                    severity = item.severity.name,
                )
            },
            sqliteCastPreflights = buildSqliteCastPreflightViews(rendered),
            renameProjections = plan.renameProjections.map { it.toReportView() },
            operations = plan.operations.map { op ->
                SchemaMigrateOperationView(
                    id = op.id,
                    kind = op::class.simpleName ?: "Unknown",
                    // D.3b Sub-Slice A: native MV ops carry the new
                    // `MATERIALIZED_VIEW` DiffObjectType; legacy
                    // CreateView/ReplaceView/DropView with materialized=true
                    // still surface under that label too so consumers
                    // can rely on the object-type discriminator.
                    objectType = if (op.materializedViewDefinition() != null) {
                        "MATERIALIZED_VIEW"
                    } else {
                        op.objectType.name
                    },
                    path = op.objectRef.path,
                    phase = op.phase.name,
                    reversibility = op.reversibility.name,
                    rendered = op.id in rendered.operationsRendered,
                    skipped = op.id in rendered.operationsSkipped,
                )
            },
            statements = buildStatementViews(request, rendered),
            summary = buildSummary(plan, rendered, renderedDown, catalogProbeMode),
            bodyDisplay = request.bodyDisplay(),
            execution = buildExecutionView(request, rendered),
        )
    }

    private fun buildStatementViews(
        request: SchemaMigrateRequest,
        rendered: MigrationDdlResult,
    ): List<SchemaMigrateStatementView>? {
        if (request.planOnly) return null
        val bodyDisplay = request.bodyDisplay()
        return rendered.statements.map { s ->
            // E.1 Slice F.2: every statement carries scrub metadata
            // (hash, length, scrubbedPreview, scrubbingApplied). The
            // `sql` field is the scrubbed body by default; only
            // `--debug-body` (RAW_DEBUG) emits the raw text.
            val preview = RoutineBodyScrubber.preview(s.sql)
            val sqlForDisplay = when (bodyDisplay) {
                RoutineBodyDisplay.RAW_DEBUG -> s.sql
                RoutineBodyDisplay.SCRUBBED_ONLY -> RoutineBodyScrubber.scrub(s.sql).text
            }
            SchemaMigrateStatementView(
                sql = sqlForDisplay,
                operationIds = s.operationIds.toList(),
                phase = s.phase.name,
                destructive = s.risk.destructive,
                sqlHash = preview.hash.orEmpty(),
                sqlLength = preview.length,
                scrubbedPreview = preview.preview,
                scrubbingApplied = preview.scrubbingApplied,
            )
        }
    }

    private fun buildExecutionView(
        request: SchemaMigrateRequest,
        rendered: MigrationDdlResult,
    ): SchemaMigrateExecutionView? {
        if (!rendered.executionStarted && rendered.executionError == null) return null
        // E.1 Slice F.7: executors that catch their own JDBC exceptions
        // (e.g. JdbcMigrationExecutor.kt:247) put `cause.message` into
        // `ExecutionTrace.executionError` directly. Driver messages
        // often quote a fragment of the failing SQL (incl. routine
        // bodies). Redact centrally here so all executor wiring is
        // covered — F.1's catch-only redaction stays as defense in
        // depth for executors that DO throw.
        val allowRaw = request.bodyDisplay() == RoutineBodyDisplay.RAW_DEBUG
        val redactedError = RoutineBodyLogRedactor.redact(rendered.executionError, allowRaw = allowRaw)
        return SchemaMigrateExecutionView(
            started = rendered.executionStarted,
            completed = rendered.executionCompleted,
            statementsAttempted = rendered.statementsAttempted,
            lastStatementOperationIds = rendered.lastStatementOperationIds.toList(),
            transactionRolledBack = rendered.transactionRolledBack,
            sideEffectsPossible = rendered.sideEffectsPossible,
            executionError = redactedError,
            statementGroups = rendered.executionStatementGroups.map { group ->
                SchemaMigrateStatementGroupView(
                    statementGroupId = group.statementGroupId,
                    operationIds = group.operationIds.toList(),
                    statementStartInclusive = group.statementStartInclusive,
                    statementEndExclusive = group.statementEndExclusive,
                    transactionScope = group.transactionScope.name,
                    transactionBoundary = group.transactionBoundary.name,
                )
            },
            recoverability = rendered.recoverability?.name,
            // Up-DDL was applied to the DB iff the executor was
            // started AND the runner-managed transaction wasn't
            // rolled back. A clean rollback after a failed Up means
            // no side effect — `upExecuted = false`.
            upExecuted = rendered.executionStarted && !rendered.transactionRolledBack,
            // `rollbackFinalized` is only known AFTER the artefact
            // write attempt — populated by `finalize` via
            // report.copy(...) before the report is written.
            rollbackFinalized = null,
        )
    }

    private fun buildSqliteCastPreflightViews(
        rendered: MigrationDdlResult,
    ): List<SchemaMigrateSqliteCastPreflightView> =
        rendered.sqliteCastPreflights.map { preflight ->
            SchemaMigrateSqliteCastPreflightView(
                operationId = preflight.operationId,
                dialect = preflight.dialect,
                table = preflight.table,
                column = preflight.column,
                sourceType = preflight.sourceType,
                targetType = preflight.targetType,
                status = preflight.status.name,
                sqlHash = preflight.sqlHash,
                totalRows = preflight.totalRows,
                failingRows = preflight.failingRows,
                sampleRowIds = preflight.sampleRowIds,
                problem = preflight.problem,
            )
        }

    private fun buildMaterializedViewContracts(
        plan: DiffResult,
        rendered: MigrationDdlResult,
        dialect: DatabaseDialect,
    ): List<SchemaMigrateMaterializedViewContractView> {
        // Severity filter is intentionally relaxed for MV codes: the
        // mapper emits `BLOCKED_DOWN_QUERY_UNKNOWN` at `WARNING` severity
        // for a `DropMaterializedView` without a recoverable body — Up
        // is safe (BLOCKER would be promoted by the renderer into an
        // `isBlocked=true` plan), only the rollback contract is affected.
        // The decision precedence below dispatches on the code string,
        // so non-MV diagnostics of any severity flow through harmlessly.
        val planCodesByOpId: Map<String?, Set<String>> = plan.diagnostics
            .groupBy { it.operationId }
            .mapValues { entry -> entry.value.mapTo(mutableSetOf()) { it.code } }
        val renderCodesByOpId: Map<String?, Set<String>> = rendered.diagnostics
            .filter { it.severity == DiffDiagnostic.Severity.BLOCKER }
            .groupBy { it.operationId }
            .mapValues { entry -> entry.value.mapTo(mutableSetOf()) { it.code } }
        return plan.operations.mapNotNull { op ->
            op.materializedViewDefinition() ?: return@mapNotNull null
            val decision = decideMaterializedViewContract(
                op = op,
                rendered = rendered,
                planCodes = planCodesByOpId[op.id].orEmpty(),
                renderCodes = renderCodesByOpId[op.id].orEmpty(),
            )
            SchemaMigrateMaterializedViewContractView(
                operationId = op.id,
                action = op.materializedViewAction(),
                path = op.objectRef.path,
                dialect = dialect.name,
                status = decision.status,
                stalenessAfterUp = decision.stalenessAfterUp,
                refreshSteps = decision.refreshSteps,
                locking = decision.locking,
                rollback = op.materializedViewRollbackContract(decision.status),
                primaryBlockedReason = decision.primaryBlockedReason,
            )
        }
    }

    /**
     * Plan-2 §8 D.3b Sub-Slice A precedence per §5 Cross-Slice OOS-Contract:
     *
     * 1. `BLOCKED_DIALECT_UNSUPPORTED` (highest — the MV cannot exist on the target).
     * 2. `BLOCKED_CONCURRENT_REFRESH_UNSUPPORTED` (explicit refresh-contract input requested CONCURRENTLY).
     * 3. `BLOCKED_SCHEMA_REFRESH_UNSUPPORTED` (`schema refresh materialized-view` intent).
     * 4. `BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED` (`ViewDefinition.refresh` is set but D.3b has no semantic evaluation for it).
     * 5. `BLOCKED_MATERIALIZED_VIEW_METADATA_UNSUPPORTED` (live-DB reverse-read metadata missing).
     * 6. `BLOCKED_CONVERSION_UNSUPPORTED` (View↔MaterializedView flip).
     * 7. `BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED` (missing/inconsistent query metadata in the diff).
     * 8. `BLOCKED_DOWN_QUERY_UNKNOWN` (Drop without recoverable body).
     *
     * If none of the above trigger, the op is treated as renderable on
     * PostgreSQL and the contract maps to `READY` with the action-specific
     * staleness/locking values from §6.4.
     */
    private fun decideMaterializedViewContract(
        op: DiffOperation,
        rendered: MigrationDdlResult,
        planCodes: Set<String>,
        renderCodes: Set<String>,
    ): MaterializedViewContractDecision {
        if ("MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT" in renderCodes) {
            return blockedDecision(
                status = "BLOCKED_DIALECT_UNSUPPORTED",
                primary = "MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT",
            )
        }
        if ("BLOCKED_CONCURRENT_REFRESH_UNSUPPORTED" in planCodes) {
            return blockedDecision(
                status = "BLOCKED_CONCURRENT_REFRESH_UNSUPPORTED",
                primary = "MATERIALIZED_VIEW_CONCURRENT_REFRESH_UNSUPPORTED",
            )
        }
        if ("BLOCKED_SCHEMA_REFRESH_UNSUPPORTED" in planCodes) {
            return blockedDecision(
                status = "BLOCKED_SCHEMA_REFRESH_UNSUPPORTED",
                primary = "MATERIALIZED_VIEW_SCHEMA_REFRESH_UNSUPPORTED",
            )
        }
        if ("BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED" in planCodes) {
            return blockedDecision(
                status = "BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED",
                primary = "VIEW_DEFINITION_REFRESH_SEMANTICS_UNSPECIFIED",
            )
        }
        if ("BLOCKED_MATERIALIZED_VIEW_METADATA_UNSUPPORTED" in planCodes ||
            "MATERIALIZED_VIEW_METADATA_UNSUPPORTED" in renderCodes
        ) {
            return blockedDecision(
                status = "BLOCKED_MATERIALIZED_VIEW_METADATA_UNSUPPORTED",
                primary = "MATERIALIZED_VIEW_METADATA_UNSUPPORTED",
            )
        }
        if ("BLOCKED_CONVERSION_UNSUPPORTED" in planCodes) {
            return blockedDecision(
                status = "BLOCKED_CONVERSION_UNSUPPORTED",
                primary = "MATERIALIZED_VIEW_CONVERSION_UNSUPPORTED",
            )
        }
        if ("BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED" in planCodes ||
            "MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED" in renderCodes
        ) {
            return blockedDecision(
                status = "BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED",
                primary = "MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED",
            )
        }
        if ("BLOCKED_DOWN_QUERY_UNKNOWN" in planCodes ||
            "MATERIALIZED_VIEW_DOWN_QUERY_UNKNOWN" in renderCodes
        ) {
            return blockedDecision(
                status = "BLOCKED_DOWN_QUERY_UNKNOWN",
                primary = "MATERIALIZED_VIEW_DOWN_QUERY_UNKNOWN",
            )
        }
        return readyDecisionFor(op, rendered)
    }

    private fun blockedDecision(
        status: String,
        primary: String,
    ): MaterializedViewContractDecision = MaterializedViewContractDecision(
        status = status,
        stalenessAfterUp = "UNKNOWN_BLOCKED",
        refreshSteps = listOf(status),
        locking = "UNKNOWN_BLOCKED",
        primaryBlockedReason = primary,
    )

    private fun readyDecisionFor(
        op: DiffOperation,
        rendered: MigrationDdlResult,
    ): MaterializedViewContractDecision {
        // Legacy bridge for Slice A: the dedicated ReplaceMaterializedView
        // op lands in Sub-Slice B. Until then, a `ReplaceView` with a
        // materialized flag falls through here without a planner diagnostic.
        // It is still blocked by the D.3a guard inside the renderer; keep
        // the conservative placeholder so the report does not claim READY.
        if (op is DiffOperation.ReplaceView) {
            return MaterializedViewContractDecision(
                status = "BLOCKED_UNTIL_REFRESH_STALENESS_CONTRACT",
                stalenessAfterUp = "UNKNOWN_BLOCKED",
                refreshSteps = listOf("BLOCKED_REFRESH_CONTRACT_REQUIRED"),
                locking = "UNKNOWN_REQUIRES_MANUAL_CONTRACT",
                primaryBlockedReason = null,
            )
        }
        val wasSkipped = op.id in rendered.operationsSkipped
        if (wasSkipped) {
            // Defense-in-depth: the renderer skipped without emitting any
            // of the codes mapped above. Keep the conservative bridge
            // status so downstream consumers do not see a misleading READY.
            return MaterializedViewContractDecision(
                status = "BLOCKED_UNTIL_REFRESH_STALENESS_CONTRACT",
                stalenessAfterUp = "UNKNOWN_BLOCKED",
                refreshSteps = listOf("BLOCKED_REFRESH_CONTRACT_REQUIRED"),
                locking = "UNKNOWN_REQUIRES_MANUAL_CONTRACT",
                primaryBlockedReason = null,
            )
        }
        return when (op) {
            is DiffOperation.CreateMaterializedView, is DiffOperation.CreateView ->
                MaterializedViewContractDecision(
                    status = "READY",
                    stalenessAfterUp = "FRESH_AFTER_INITIAL_REFRESH",
                    refreshSteps = listOf("INITIAL_REFRESH_VIA_CREATE"),
                    locking = "ACCESS_EXCLUSIVE",
                    primaryBlockedReason = null,
                )
            is DiffOperation.DropMaterializedView, is DiffOperation.DropView ->
                MaterializedViewContractDecision(
                    status = "READY",
                    stalenessAfterUp = "NOT_APPLICABLE_DROP",
                    refreshSteps = emptyList(),
                    locking = "ACCESS_EXCLUSIVE",
                    primaryBlockedReason = null,
                )
            else -> error("decideMaterializedViewContract reached for unexpected op type ${op::class.simpleName}")
        }
    }

    private data class MaterializedViewContractDecision(
        val status: String,
        val stalenessAfterUp: String,
        val refreshSteps: List<String>,
        val locking: String,
        val primaryBlockedReason: String?,
    )

    private fun DiffOperation.materializedViewDefinition(): ViewDefinition? = when (this) {
        is DiffOperation.CreateMaterializedView -> view
        is DiffOperation.DropMaterializedView -> view
        is DiffOperation.CreateView -> view.takeIf { it.materialized }
        is DiffOperation.ReplaceView -> after.takeIf { before.materialized || after.materialized }
        is DiffOperation.DropView -> view.takeIf { it.materialized }
        else -> null
    }

    private fun DiffOperation.materializedViewAction(): String = when (this) {
        is DiffOperation.CreateMaterializedView -> "CREATE"
        is DiffOperation.DropMaterializedView -> "DROP"
        is DiffOperation.CreateView -> "CREATE"
        is DiffOperation.ReplaceView -> "REPLACE"
        is DiffOperation.DropView -> "DROP"
        else -> "UNKNOWN"
    }

    /**
     * Rollback-contract value per §6.4. Blocked statuses short-circuit
     * to `ROLLBACK_NOT_POSSIBLE` so the report does not claim a query
     * body is recoverable when the operation itself can't run.
     */
    private fun DiffOperation.materializedViewRollbackContract(status: String): String {
        if (status != "READY") return "ROLLBACK_NOT_POSSIBLE"
        return when (this) {
            is DiffOperation.CreateMaterializedView, is DiffOperation.CreateView ->
                "DROP_CREATED_MATERIALIZED_VIEW_REFRESH_NOT_REQUIRED"
            is DiffOperation.DropMaterializedView ->
                if (view.query == null) "MANUAL_RECONSTRUCTION_REQUIRED"
                else "SOURCE_QUERY_AVAILABLE_REFRESH_CONTRACT_REQUIRED"
            is DiffOperation.ReplaceView ->
                if (before.query == null) "MANUAL_RECONSTRUCTION_REQUIRED"
                else "SOURCE_QUERY_AVAILABLE_REFRESH_CONTRACT_REQUIRED"
            is DiffOperation.DropView ->
                if (view.query == null) "MANUAL_RECONSTRUCTION_REQUIRED"
                else "SOURCE_QUERY_AVAILABLE_REFRESH_CONTRACT_REQUIRED"
            else -> "MANUAL_RECONSTRUCTION_REQUIRED"
        }
    }

    private fun buildSummary(
        plan: DiffResult,
        rendered: MigrationDdlResult,
        renderedDown: MigrationDdlResult?,
        catalogProbeMode: SqliteCatalogProbeMode,
    ): SchemaMigrateSummary {
        val extensionDependencies = rendered.extensionDependencies
        return SchemaMigrateSummary(
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
            catalogProbeMode = catalogProbeMode.name,
            spatialProfile = rendered.spatialProfile,
            requiredExtensions = extensionDependencies.namesWithStatus(),
            verifiedExtensions = extensionDependencies.namesWithStatus(ExtensionAvailabilityStatus.VERIFIED_PRESENT),
            missingExtensions = extensionDependencies.namesWithoutStatus(ExtensionAvailabilityStatus.VERIFIED_PRESENT),
            extensionInstallStatements = extensionDependencies
                .mapNotNull { it.installStatement }
                .distinct(),
        )
    }

    private fun List<ExtensionDependencyReport>.namesWithStatus(
        status: ExtensionAvailabilityStatus? = null,
    ): List<String> = asSequence()
        .filter { status == null || it.status == status }
        .map { it.extension }
        .distinct()
        .sorted()
        .toList()

    private fun List<ExtensionDependencyReport>.namesWithoutStatus(
        status: ExtensionAvailabilityStatus,
    ): List<String> = asSequence()
        .filter { it.status != status }
        .map { it.extension }
        .distinct()
        .sorted()
        .toList()

    /**
     * Planner-emitted diagnostics (e.g. F.4 rename
     * `RENAME_OVERLAY_STRUCTURAL_MISMATCH` warnings) sit on
     * [DiffResult.diagnostics]; the renderer only copies BLOCKERs
     * into [MigrationDdlResult.diagnostics] so the planner blocker
     * surfaces as a renderer blocker, but WARNING/INFO entries would
     * be lost otherwise. The report merges both sources, deduping by
     * `(code, operationId, message)` so a diagnostic that the
     * renderer chose to forward (e.g. a re-issued blocker) is not
     * shown twice.
     */
    private fun mergeDiagnostics(plan: DiffResult, rendered: MigrationDdlResult): List<SchemaMigrateDiagnosticView> {
        val combined = LinkedHashMap<Triple<String, String?, String>, SchemaMigrateDiagnosticView>()
        for (d in rendered.diagnostics + plan.diagnostics) {
            val key = Triple(d.code, d.operationId, d.message)
            combined.putIfAbsent(
                key,
                SchemaMigrateDiagnosticView(
                    code = d.code,
                    severity = d.severity.name,
                    message = d.message,
                    operationId = d.operationId,
                ),
            )
        }
        return combined.values.toList()
    }

    private fun RenameProjectionReport.toReportView(): SchemaMigrateRenameProjectionView =
        SchemaMigrateRenameProjectionView(
            candidateId = candidateId,
            objectType = objectType,
            fromPath = fromPath,
            toPath = toPath,
            overlaySource = overlaySource,
            overlayEntryId = overlayEntryId,
            overlayHash = overlayHash,
            renameOperationId = renameOperationId,
            fallbackOperationIds = fallbackOperationIds,
            fallbackReason = fallbackReason,
            automatic = automatic.map {
                SchemaMigrateDependencyRefView(kind = it.kind, path = it.path, rationale = it.rationale)
            },
            explicit = explicit.map {
                SchemaMigrateExplicitProjectionView(kind = it.kind, path = it.path, operationId = it.operationId)
            },
            blockers = blockers.map {
                SchemaMigrateRenameBlockerView(
                    code = it.code,
                    candidateId = it.candidateId,
                    path = it.path,
                    message = it.message,
                    severity = it.severity.name,
                )
            },
        )
}
