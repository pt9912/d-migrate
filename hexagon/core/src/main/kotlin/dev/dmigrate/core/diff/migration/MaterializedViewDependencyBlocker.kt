package dev.dmigrate.core.diff.migration

/**
 * Plan-2 §8 D.3b Sub-Slice C: structured carrier for an MV that is
 * orphaned (or about to be orphaned) by a `Drop`/`Replace` of a
 * table/view/routine it depends on, without a matching `DropMV` /
 * `ReplaceMV` in the same plan.
 *
 * The planner emits one [MaterializedViewDependencyBlocker] per
 * `(materialized-view, dropping-op)` pair. The report builder reads
 * the list directly (never reconstructing from diagnostics or op-ids)
 * so the `materializedViews[]` contract can synthesise entries for
 * orphaned MVs that have no in-plan operation of their own.
 *
 * The `droppingOperationId` references an op that DOES exist in
 * [DiffResult.operations]; the `materializedViewName` and
 * [materializedViewPath] reference an MV that may or may not have an
 * operation in the plan.
 */
data class MaterializedViewDependencyBlocker(
    val materializedViewName: String,
    val materializedViewPath: List<String>,
    val droppingOperationId: String,
    val droppingObjectType: DiffObjectType,
    val droppingPath: List<String>,
    /**
     * The kind of dependency: `TABLE`, `VIEW`, `MATERIALIZED_VIEW`,
     * `FUNCTION`, `PROCEDURE`. Drives the wording the report builder
     * surfaces in the operator-facing message.
     */
    val droppingKind: String,
)
