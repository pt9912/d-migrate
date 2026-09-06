package dev.dmigrate.core.diff.migration

// API stability note: the public types in this file
// ([DependencyRef], [ExplicitProjectionRef], [RenameProjectionBlocker],
// [RenameProjectionReport], [RENAME_DEPENDENCY_UNPROJECTABLE]) form
// the F.4 report contract because [DiffResult.renameProjections]
// surfaces them. Application-layer renderers map them to
// `SchemaMigrateRenameProjectionView` (and friends), shielding
// downstream consumers from core-side churn — but additive / breaking
// changes to the types below are still SemVer-relevant. Internal call
// sites that don't need stability use the application-layer view DTOs.

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
 *   the projector. The regular `mapViews` path MUST skip these in
 *   `viewsChanged`; `viewsAdded` / `viewsRemoved` are not filtered
 *   because the reprojector only iterates `current.views` and the
 *   missing-from-`desired` case is reported as a blocker (which
 *   means the candidate falls back to drop+create, no absorption).
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
data class DependencyRef(
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
data class RenameProjectionBlocker(
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
const val RENAME_DEPENDENCY_UNPROJECTABLE: String = "RENAME_DEPENDENCY_UNPROJECTABLE"

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
    /** T6 structured per-candidate report carriers. */
    val reports: List<RenameProjectionReport> = emptyList(),
)

/**
 * Outcome of folding a batch of [RenameColumnPlanningItem]s for one
 * table. `absorbed*Columns` carry the column names whose drop/add
 * pair was replaced.
 *
 * [absorbedViews] carries the same contract as
 * [RenameProjection.absorbedViews] and for the same reason: a policy
 * that reprojects views on a COLUMN rename (Oracle does — the engine
 * leaves the dependent view invalid) emits `DropView` + `CreateView`
 * itself, and `mapViews` must then skip that view in `viewsChanged`.
 * Without this field the plan would carry a third, unanchored
 * `ReplaceView` on the same object.
 */
internal data class RenameColumnProjection(
    val operations: List<DiffOperation>,
    val diagnostics: List<DiffDiagnostic>,
    val absorbedFromColumns: Set<String>,
    val absorbedToColumns: Set<String>,
    val reports: List<RenameProjectionReport> = emptyList(),
    val absorbedViews: Set<String> = emptySet(),
)

/**
 * F.4 dependency-projection T6 report carrier. Each entry corresponds
 * to one overlay-bound rename candidate — successful or fallback —
 * and is the single source of truth the
 * [dev.dmigrate.cli.commands.SchemaMigrateReportBuilder] reads when
 * rendering the migrate report's `renameProjections` section.
 *
 * Per Plan-2 §F.4 §3.6:
 *
 * - Successful fold: [renameOperationId] points at the emitted
 *   `Rename*` op; [fallbackOperationIds] is empty; [blockers] is
 *   empty. [automatic] / [explicit] document which dependencies the
 *   engine handles natively vs which d-migrate re-renders via
 *   explicit follow-up operations (T5 view reprojection).
 * - Drop+Add fallback: [renameOperationId] is `null`;
 *   [fallbackOperationIds] carries the regular `DropTable` /
 *   `CreateTable` (resp. `DropColumn` / `AddColumn`) ids the mapper
 *   emitted for this candidate; [blockers] lists the projector
 *   reasons; [fallbackReason] is a short human-readable summary.
 *
 * [overlayEntryId] is the stable identifier of the overlay entry
 * that authorised the rename. Reports MUST NOT reconstruct entry
 * provenance from `(overlaySource, overlayHash)` because multiple
 * entries can share the same hash; the entry id is the only safe
 * key.
 */
data class RenameProjectionReport(
    val candidateId: String,
    val objectType: String,
    /**
     * Pre-rename path. For a table rename this is the single-element
     * `[fromName]`; for a column rename it is `[tableName, fromColumn]`.
     */
    val fromPath: List<String>,
    /** Same shape as [fromPath] but pinned at the post-rename name(s). */
    val toPath: List<String>,
    val overlaySource: String,
    val overlayEntryId: String,
    val overlayHash: String?,
    val renameOperationId: String?,
    val fallbackOperationIds: List<String> = emptyList(),
    val fallbackReason: String? = null,
    val automatic: List<DependencyRef> = emptyList(),
    val explicit: List<ExplicitProjectionRef> = emptyList(),
    val blockers: List<RenameProjectionBlocker> = emptyList(),
)

/**
 * Reference to one of the projector's T5 explicit follow-up
 * operations (today: view drop+create). [operationId] points at the
 * emitted op in the final plan so the report can be correlated with
 * the operation list.
 */
data class ExplicitProjectionRef(
    val kind: String,
    val path: List<String>,
    val operationId: String,
)
