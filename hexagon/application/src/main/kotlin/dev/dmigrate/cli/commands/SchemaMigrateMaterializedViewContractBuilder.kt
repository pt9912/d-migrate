package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.MaterializedViewDependencyBlocker
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationDdlResult

/**
 * Plan-2 §8 D.3b Sub-Slices A/B/C: builds the `materializedViews[]`
 * contract list for the schema-migrate report. Extracted out of
 * [SchemaMigrateReportBuilder] purely to keep the parent file under
 * Detekt's `LargeClass` threshold; no behavioural change.
 *
 * Precedence per §5 Cross-Slice OOS-Contract:
 *
 * 1. `BLOCKED_DIALECT_UNSUPPORTED` (highest — the MV cannot exist on the target).
 * 2. `BLOCKED_CONCURRENT_REFRESH_UNSUPPORTED` (explicit refresh-contract input requested CONCURRENTLY).
 * 3. `BLOCKED_SCHEMA_REFRESH_UNSUPPORTED` (`schema refresh materialized-view` intent).
 * 4. `BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED` (`ViewDefinition.refresh` is set, no D.3b semantics).
 * 5. `BLOCKED_MATERIALIZED_VIEW_METADATA_UNSUPPORTED` (live-DB reverse-read metadata missing).
 * 6. `BLOCKED_CONVERSION_UNSUPPORTED` (View↔MaterializedView flip).
 * 7. `BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED` (missing/inconsistent query metadata).
 * 8. `BLOCKED_REPLACE_DOWN_BODY_UNKNOWN` (Replace without recoverable `before` body).
 * 9. `BLOCKED_DOWN_QUERY_UNKNOWN` (Drop without recoverable body).
 * 10. `BLOCKED_DEPENDENCY_UNRESOLVED` (Drop/Replace of a depended-on object orphans the MV).
 *
 * If none of the above trigger, the op is treated as renderable on
 * PostgreSQL and the contract maps to `READY` with the action-specific
 * staleness/locking values from §6.4.
 */
/**
 * Top-level extension hoisted out of [SchemaMigrateMaterializedViewContractBuilder]
 * so call sites in [SchemaMigrateReportBuilder] can use the natural
 * `op.materializedViewDefinition()` form. Stays `internal` because the
 * MV-discriminator is a CLI-module implementation detail.
 */
internal fun DiffOperation.materializedViewDefinition(): ViewDefinition? = when (this) {
    is DiffOperation.CreateMaterializedView -> view
    is DiffOperation.ReplaceMaterializedView -> after
    is DiffOperation.DropMaterializedView -> view
    is DiffOperation.CreateView -> view.takeIf { it.materialized }
    is DiffOperation.ReplaceView -> after.takeIf { before.materialized || after.materialized }
    is DiffOperation.DropView -> view.takeIf { it.materialized }
    else -> null
}

internal object SchemaMigrateMaterializedViewContractBuilder {

    fun build(
        plan: DiffResult,
        rendered: MigrationDdlResult,
        dialect: DatabaseDialect,
    ): List<SchemaMigrateMaterializedViewContractView> {
        // Severity filter is intentionally relaxed for MV codes: the
        // mapper emits `BLOCKED_DOWN_QUERY_UNKNOWN` at `WARNING`
        // severity for a `DropMaterializedView` without a recoverable
        // body — Up is safe (BLOCKER would be promoted by the renderer
        // into an `isBlocked=true` plan). The decision precedence
        // dispatches on the code string, so non-MV diagnostics of any
        // severity flow through harmlessly.
        val planCodesByOpId: Map<String?, Set<String>> = plan.diagnostics
            .groupBy { it.operationId }
            .mapValues { entry -> entry.value.mapTo(mutableSetOf()) { it.code } }
        val renderCodesByOpId: Map<String?, Set<String>> = rendered.diagnostics
            .filter { it.severity == DiffDiagnostic.Severity.BLOCKER }
            .groupBy { it.operationId }
            .mapValues { entry -> entry.value.mapTo(mutableSetOf()) { it.code } }
        val dependencyBlockersByMv: Map<String, List<MaterializedViewDependencyBlocker>> =
            plan.materializedViewDependencyBlockers.groupBy { it.materializedViewName }
        val mvNamesInOps = plan.operations.mapNotNullTo(mutableSetOf()) { op ->
            if (op.materializedViewDefinition() != null) op.objectRef.rootName else null
        }

        val contracts = mutableListOf<SchemaMigrateMaterializedViewContractView>()
        for (op in plan.operations) {
            op.materializedViewDefinition() ?: continue
            val mvName = op.objectRef.rootName
            val dependencyBlockers = dependencyBlockersByMv[mvName].orEmpty()
            // Inject a synthetic `BLOCKED_DEPENDENCY_UNRESOLVED` code so
            // the precedence table sees the dependency block alongside
            // the regular plan codes. (The planner already emitted the
            // matching BLOCKER diagnostic on the dropping op — the
            // structured carrier exists for the report contract.)
            val planCodes = planCodesByOpId[op.id].orEmpty().toMutableSet()
            if (dependencyBlockers.isNotEmpty()) planCodes += "BLOCKED_DEPENDENCY_UNRESOLVED"
            val decision = decide(
                op = op,
                rendered = rendered,
                planCodes = planCodes,
                renderCodes = renderCodesByOpId[op.id].orEmpty(),
            )
            contracts += SchemaMigrateMaterializedViewContractView(
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
                dependencyBlockers = dependencyBlockers.map { it.toView() },
            )
        }
        // Orphaned MVs — the MV itself has no in-plan op but a
        // depended-on object is being dropped/replaced. Synthesise a
        // contract row so the operator sees the orphan in
        // `materializedViews[]`. `operationId` is set to the
        // dropping-op id of the first blocker — operators correlate
        // via the `dependencyBlockers` list.
        for ((mvName, blockers) in dependencyBlockersByMv) {
            if (mvName in mvNamesInOps) continue
            val first = blockers.first()
            contracts += SchemaMigrateMaterializedViewContractView(
                operationId = first.droppingOperationId,
                action = "ORPHAN",
                path = first.materializedViewPath,
                dialect = dialect.name,
                status = "BLOCKED_DEPENDENCY_UNRESOLVED",
                stalenessAfterUp = "UNKNOWN_BLOCKED",
                refreshSteps = listOf("BLOCKED_DEPENDENCY_UNRESOLVED"),
                locking = "UNKNOWN_BLOCKED",
                rollback = "ROLLBACK_NOT_POSSIBLE",
                primaryBlockedReason = "MATERIALIZED_VIEW_DEPENDENCY_UNRESOLVED",
                dependencyBlockers = blockers.map { it.toView() },
            )
        }
        return contracts
    }

    private fun decide(
        op: DiffOperation,
        rendered: MigrationDdlResult,
        planCodes: Set<String>,
        renderCodes: Set<String>,
    ): MaterializedViewContractDecision {
        precedenceLookup(planCodes, renderCodes)?.let { return it }
        return readyDecisionFor(op, rendered)
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun precedenceLookup(
        planCodes: Set<String>,
        renderCodes: Set<String>,
    ): MaterializedViewContractDecision? {
        if ("MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT" in renderCodes) {
            return blockedDecision("BLOCKED_DIALECT_UNSUPPORTED", "MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT")
        }
        if ("BLOCKED_CONCURRENT_REFRESH_UNSUPPORTED" in planCodes) {
            return blockedDecision(
                "BLOCKED_CONCURRENT_REFRESH_UNSUPPORTED",
                "MATERIALIZED_VIEW_CONCURRENT_REFRESH_UNSUPPORTED",
            )
        }
        if ("BLOCKED_SCHEMA_REFRESH_UNSUPPORTED" in planCodes) {
            return blockedDecision(
                "BLOCKED_SCHEMA_REFRESH_UNSUPPORTED",
                "MATERIALIZED_VIEW_SCHEMA_REFRESH_UNSUPPORTED",
            )
        }
        if ("BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED" in planCodes) {
            return blockedDecision(
                "BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED",
                "VIEW_DEFINITION_REFRESH_SEMANTICS_UNSPECIFIED",
            )
        }
        if ("BLOCKED_MATERIALIZED_VIEW_METADATA_UNSUPPORTED" in planCodes ||
            "MATERIALIZED_VIEW_METADATA_UNSUPPORTED" in renderCodes
        ) {
            return blockedDecision(
                "BLOCKED_MATERIALIZED_VIEW_METADATA_UNSUPPORTED",
                "MATERIALIZED_VIEW_METADATA_UNSUPPORTED",
            )
        }
        if ("BLOCKED_CONVERSION_UNSUPPORTED" in planCodes) {
            return blockedDecision(
                "BLOCKED_CONVERSION_UNSUPPORTED",
                "MATERIALIZED_VIEW_CONVERSION_UNSUPPORTED",
            )
        }
        if ("BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED" in planCodes ||
            "MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED" in renderCodes
        ) {
            return blockedDecision(
                "BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED",
                "MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED",
            )
        }
        if ("BLOCKED_REPLACE_DOWN_BODY_UNKNOWN" in planCodes ||
            "MATERIALIZED_VIEW_REPLACE_DOWN_BODY_UNKNOWN" in renderCodes
        ) {
            return blockedDecision(
                "BLOCKED_REPLACE_DOWN_BODY_UNKNOWN",
                "MATERIALIZED_VIEW_REPLACE_DOWN_BODY_UNKNOWN",
            )
        }
        if ("BLOCKED_DOWN_QUERY_UNKNOWN" in planCodes ||
            "MATERIALIZED_VIEW_DOWN_QUERY_UNKNOWN" in renderCodes
        ) {
            return blockedDecision(
                "BLOCKED_DOWN_QUERY_UNKNOWN",
                "MATERIALIZED_VIEW_DOWN_QUERY_UNKNOWN",
            )
        }
        if ("BLOCKED_DEPENDENCY_UNRESOLVED" in planCodes) {
            return blockedDecision(
                "BLOCKED_DEPENDENCY_UNRESOLVED",
                "MATERIALIZED_VIEW_DEPENDENCY_UNRESOLVED",
            )
        }
        return null
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
        val wasSkipped = op.id in rendered.operationsSkipped
        if (wasSkipped) {
            // Defense-in-depth: the renderer skipped the op without
            // emitting any of the codes the precedence table maps. A
            // hand-constructed `ReplaceView` with materialized=true is
            // the most common trigger. Surface a generic OOS rather
            // than claim READY.
            return blockedDecision(
                "BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED",
                "MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED",
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
            is DiffOperation.ReplaceMaterializedView ->
                MaterializedViewContractDecision(
                    status = "READY",
                    stalenessAfterUp = "FRESH_AFTER_REPLACE_REFRESH",
                    refreshSteps = listOf("DROP_CREATE_INITIAL_REFRESH"),
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
            else -> error("decide reached for unexpected op type ${op::class.simpleName}")
        }
    }

    private data class MaterializedViewContractDecision(
        val status: String,
        val stalenessAfterUp: String,
        val refreshSteps: List<String>,
        val locking: String,
        val primaryBlockedReason: String?,
    )

    private fun DiffOperation.materializedViewAction(): String = when (this) {
        is DiffOperation.CreateMaterializedView -> "CREATE"
        is DiffOperation.ReplaceMaterializedView -> "REPLACE"
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
            is DiffOperation.ReplaceMaterializedView ->
                if (before.query == null) "MANUAL_RECONSTRUCTION_REQUIRED"
                else "SOURCE_QUERY_AVAILABLE_REFRESH_CONTRACT_REQUIRED"
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

    private fun MaterializedViewDependencyBlocker.toView(): SchemaMigrateMaterializedViewDependencyBlockerView =
        SchemaMigrateMaterializedViewDependencyBlockerView(
            droppingOperationId = droppingOperationId,
            droppingPath = droppingPath,
            droppingKind = droppingKind,
        )
}
