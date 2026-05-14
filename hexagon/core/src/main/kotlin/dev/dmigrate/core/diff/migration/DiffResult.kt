package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.model.SchemaDefinition

/**
 * Migration-ready operation plan derived from a [SchemaDiff].
 *
 * Position in the pipeline:
 *
 * ```
 * (current, desired)
 *   → SchemaComparator → SchemaDiff
 *   → DiffPlanner      → DiffResult           ← this type
 *   → DialectRenderer  → MigrationDdlResult
 *   → SQL artefacts | DB-Execute
 * ```
 *
 * `DiffResult` adds four things on top of [SchemaDiff]:
 *
 * 1. an ordered list of [DiffOperation]s with stable IDs,
 *    explicit dependencies, phase tie-breakers, and per-direction
 *    risk flags;
 * 2. endpoint metadata ([current], [desired]) for fingerprint /
 *    drift tracking and the SQL metadata-block;
 * 3. optional [migrationOverlays] carrying versioned operator input
 *    such as PostgreSQL USING expressions, bound to the endpoint
 *    fingerprints and consumed by renderers that support that
 *    contract;
 * 4. planner [diagnostics] that surface non-blocking notes and
 *    blocking diagnoses (e.g. `CONSTRAINT_NOT_DIFFABLE` for tables
 *    that carry CHECK / EXCLUDE constraints — see
 *    `docs/planning/done/diffresult-migration-plan.md §11.1`).
 *
 * The planner direction is `current → desired`. The down-side of an
 * automatic rollback uses the inverse via per-operation
 * [DiffOperation.risks].down + the planner's inverse-sort.
 *
 * `DiffResult` is **internal-contract only** in the first slice. A
 * later milestone may serialise it as a public artefact; until then
 * callers should treat field reordering / additions as routine
 * changes per Kotlin data-class semantics.
 */
data class DiffResult(
    val current: DiffEndpoint,
    val desired: DiffEndpoint,
    val schemaDiff: SchemaDiff,
    val operations: List<DiffOperation>,
    val diagnostics: List<DiffDiagnostic> = emptyList(),
    /**
     * Optional full source schemas. Renderers that need to reconstruct
     * the complete target table (notably the SQLite RebuildTable
     * pipeline in Phase D.4.b) read these. Most renderers operate on
     * `operations` alone and ignore them. The planner populates both
     * fields; only artefact-deserialised `DiffResult`s leave them null.
     */
    val currentSchema: SchemaDefinition? = null,
    val desiredSchema: SchemaDefinition? = null,
    val migrationOverlays: List<MigrationOverlayDocument> = emptyList(),
) {
    /** True iff at least one diagnostic is a [DiffDiagnostic.Severity.BLOCKER]. */
    val hasBlockers: Boolean
        get() = diagnostics.any { it.severity == DiffDiagnostic.Severity.BLOCKER }

    /** True iff all operations are reversible (`AUTOMATIC` or `AUTOMATIC_WITH_DATA_RISK`). */
    val isFullyReversible: Boolean
        get() = operations.all {
            it.reversibility == Reversibility.AUTOMATIC ||
                it.reversibility == Reversibility.AUTOMATIC_WITH_DATA_RISK
        }

    companion object {
        /**
         * Plan-2 §F.4 dependency-projection T1: build a blocker-shaped
         * [DiffResult] when the pre-plan overlay gate trips and the
         * runner does NOT call [DiffPlanner.plan]. The endpoint
         * fingerprints are passed through so downstream report code
         * still has the metadata it needs; the operations list is
         * empty by contract and the [diagnostics] carry the blocker
         * reasons.
         *
         * Keeping this factory next to [DiffPlanner]'s endpoint
         * construction prevents drift when the [DiffResult] shape
         * gains new fields (e.g. T6's `renameProjections`).
         */
        @Suppress("LongParameterList")
        fun preplanBlocker(
            current: SchemaDefinition,
            desired: SchemaDefinition,
            schemaDiff: SchemaDiff,
            diagnostics: List<DiffDiagnostic>,
            migrationOverlays: List<MigrationOverlayDocument>,
            currentFingerprint: String,
            desiredFingerprint: String,
        ): DiffResult = DiffResult(
            current = DiffEndpoint(
                schemaName = current.name,
                schemaVersion = current.version,
                fingerprint = currentFingerprint,
            ),
            desired = DiffEndpoint(
                schemaName = desired.name,
                schemaVersion = desired.version,
                fingerprint = desiredFingerprint,
            ),
            schemaDiff = schemaDiff,
            operations = emptyList(),
            diagnostics = diagnostics,
            currentSchema = current,
            desiredSchema = desired,
            migrationOverlays = migrationOverlays,
        )
    }
}

/**
 * Endpoint metadata for a [DiffResult] side. Drives the SQL metadata
 * block (`currentFingerprint`, `desiredFingerprint`) and report
 * metadata.
 *
 * [fingerprint] is computed via the canonical fingerprint projection
 * (Phase B follow-up) and may be `null` when the endpoint is a live
 * database whose fingerprint has not yet been computed (the planner
 * fills it in before serialising).
 */
data class DiffEndpoint(
    val schemaName: String,
    val schemaVersion: String? = null,
    val fingerprint: String? = null,
)
