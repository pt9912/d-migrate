package dev.dmigrate.core.diff.migration

/**
 * F.4 dependency-projection T3/T5 policy output. The projector
 * consults a [RenameDependencyPolicy] per candidate and receives one
 * of these structured outcomes:
 *
 * - [automatic] — dependencies the engine handles natively (e.g.
 *   PostgreSQL OID-based foreign keys after `ALTER TABLE … RENAME TO`).
 *   These are surfaced in the report carrier but do not produce
 *   additional operations.
 * - [explicit] — explicit follow-up operations the projector emits
 *   alongside the rename. T5: views referencing the renamed table get
 *   a `DropView` + `CreateView` pair from the desired body, anchored
 *   to the rename via `dependencies = setOf(candidate.id)` plus the
 *   internal Drop→Create chain.
 * - [absorbedViews] — view names whose reprojection was emitted by
 *   the projector and which the regular `mapViews` path MUST skip in
 *   `viewsChanged`. Otherwise a duplicate `ReplaceView` would land in
 *   the plan alongside the projector's `DropView`/`CreateView`.
 * - [blockers] — dependencies the policy cannot project to the new
 *   name. When non-empty, the projector falls back to Drop+Add and
 *   emits one `RENAME_DEPENDENCY_UNPROJECTABLE` diagnostic per
 *   blocker.
 *
 * [isAutomatic] is the projector's primary decision predicate: emit
 * the rename + explicit ops when true, fall back when false.
 */
internal data class RenameProjection(
    val automatic: List<DependencyRef> = emptyList(),
    val explicit: List<DiffOperation> = emptyList(),
    val absorbedViews: Set<String> = emptySet(),
    val blockers: List<RenameProjectionBlocker> = emptyList(),
) {
    val isAutomatic: Boolean get() = blockers.isEmpty()

    companion object {
        val EMPTY: RenameProjection = RenameProjection()
    }
}

/**
 * Dependency that the dialect engine is documented to project
 * automatically across a rename (so d-migrate emits no explicit
 * reprojection operation). Carried into the F.4 report so an operator
 * can audit which dependencies were assumed engine-handled.
 *
 * [kind] is a short identifier ("FK", "INDEX", "PK", "CONSTRAINT",
 * "VIEW", "TRIGGER", "DEFAULT"). [path] is a tuple identifying the
 * dependent object — usually `[tableName, objectName]` or
 * `[tableName, columnName]` for column-scoped dependencies.
 * [rationale] is the policy's documented reason (e.g. "PostgreSQL OID-
 * based dependency model").
 */
internal data class DependencyRef(
    val kind: String,
    val path: List<String>,
    val rationale: String,
)

/**
 * Policy-detected dependency that cannot be projected to the new name
 * for this dialect under the current capabilities. The projector
 * translates each blocker into a `RENAME_DEPENDENCY_UNPROJECTABLE`
 * diagnostic; severity is WARNING when the Drop+Add fallback fully
 * covers the schema delta, BLOCKER when even the fallback is not
 * renderable.
 *
 * [candidateId] is the candidate's pre-computed operation ID so a
 * report-time consumer can correlate the blocker with the (possibly
 * skipped) rename operation. [path] mirrors [DependencyRef.path] for
 * the offending dependency.
 */
internal data class RenameProjectionBlocker(
    val code: String,
    val candidateId: String,
    val path: List<String>,
    val message: String,
    val severity: DiffDiagnostic.Severity = DiffDiagnostic.Severity.WARNING,
)

/**
 * F.4 diagnostic code: the rename was rejected because at least one
 * dependency on the renamed object cannot be projected to the new
 * name under the current dialect + capabilities. The projector emits
 * one diagnostic per [RenameProjectionBlocker] with this code; the
 * operator either aligns the schema (e.g. removes the function-call
 * default), supplies stronger capabilities (e.g. probes the live
 * target so MySQL FK reprojection becomes safe), or accepts the
 * Drop+Add fallback.
 */
internal const val RENAME_DEPENDENCY_UNPROJECTABLE: String = "RENAME_DEPENDENCY_UNPROJECTABLE"

/**
 * Outcome of folding a batch of [RenameTablePlanningItem]s. The
 * `absorbed*` sets describe the source/target table names whose
 * Drop+Add pair was replaced by a `RenameTable`; the mapper skips
 * those names from its regular drop/create path.
 */
internal data class RenameTableProjection(
    val operations: List<DiffOperation>,
    val diagnostics: List<DiffDiagnostic>,
    val absorbedFromNames: Set<String>,
    val absorbedToNames: Set<String>,
    /**
     * T5: view names whose reprojection (`DropView` + `CreateView`)
     * the projector already emitted. The mapper MUST skip these
     * entries in `viewsChanged` so the resulting plan does not
     * contain a duplicate `ReplaceView` alongside the projector's
     * explicit ops.
     */
    val absorbedViews: Set<String> = emptySet(),
)

/**
 * Outcome of folding a batch of [RenameColumnPlanningItem]s for one
 * table. `absorbed*Columns` carry the column names whose drop/add
 * pair was replaced.
 */
internal data class RenameColumnProjection(
    val operations: List<DiffOperation>,
    val diagnostics: List<DiffDiagnostic>,
    val absorbedFromColumns: Set<String>,
    val absorbedToColumns: Set<String>,
)
