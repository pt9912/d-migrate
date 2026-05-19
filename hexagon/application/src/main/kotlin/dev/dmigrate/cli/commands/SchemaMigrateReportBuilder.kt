package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.RenameProjectionReport
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayReportItem
import dev.dmigrate.core.diff.routine.RoutineBodyLogRedactor
import dev.dmigrate.core.diff.routine.RoutineBodyScrubber
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
            materializedViews = SchemaMigrateMaterializedViewContractBuilder.build(plan, rendered, dialect),
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
            checkPreflights = buildCheckPreflightViews(rendered),
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

    /**
     * F.5 Sub-Slice E.4: project `MigrationDdlResult.checkPreflights`
     * into the report view shape so report formatters (JSON, human)
     * can iterate without coupling to the ports-read declaration
     * type.
     */
    private fun buildCheckPreflightViews(
        rendered: MigrationDdlResult,
    ): List<SchemaMigrateCheckPreflightView> =
        rendered.checkPreflights.map { preflight ->
            SchemaMigrateCheckPreflightView(
                operationId = preflight.operationId,
                dialect = preflight.dialect,
                table = preflight.table,
                constraintName = preflight.constraintName,
                expression = preflight.expression,
                status = preflight.status.name,
                sqlHash = preflight.sqlHash,
                totalRows = preflight.totalRows,
                failingRows = preflight.failingRows,
                sampleRowIds = preflight.sampleRowIds,
                problem = preflight.problem,
            )
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
