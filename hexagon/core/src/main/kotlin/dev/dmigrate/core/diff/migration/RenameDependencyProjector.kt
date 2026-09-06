package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.model.SchemaDefinition

/**
 * F.4 dependency-projection T3 projector. Each candidate is
 * classified via the dialect-specific
 * [RenameDependencyPolicy] resolved from
 * [RenameProjectionCapabilities.dialect], and the projector folds
 * the policy outcome together with the candidate's pre-flagged
 * mapper signals (structural mismatch, stale cross-table reference,
 * column reference inside the same table) into the same
 * `Pair<absorbedTo, absorbedFrom>` shape the
 * [OperationMapper.mapTables] / `mapTableColumns` loops consume.
 *
 * Decision precedence per candidate, in order:
 *
 * 1. Structural mismatch — emit
 *    `RENAME_OVERLAY_STRUCTURAL_MISMATCH` (WARNING), no rename.
 * 2. Mapper-pre-flagged dependency (stale FK / same-table
 *    referencing object) — emit
 *    `RENAME_OVERLAY_DEPENDENCY_PROJECTION_REQUIRED` (WARNING), no rename.
 * 3. Policy blocker(s) — emit one
 *    `RENAME_DEPENDENCY_UNPROJECTABLE` per blocker (WARNING by
 *    default; severity escalates when the blocker itself is severe),
 *    no rename.
 * 4. Otherwise — emit the `Rename*` operation, append the
 *    `postRenameDeltaOperations` (empty in T3; T4 will populate),
 *    absorb the candidate's from/to names from the regular
 *    drop+add path.
 */
internal class RenameDependencyProjector(
    private val capabilities: RenameProjectionCapabilities,
    private val policy: RenameDependencyPolicy =
        RenameDependencyPolicy.forDialect(capabilities.dialect),
) {

    fun projectTables(
        items: List<RenameTablePlanningItem>,
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
    ): RenameTableProjection {
        if (items.isEmpty()) return EMPTY_TABLE_PROJECTION
        val ops = mutableListOf<DiffOperation>()
        val diagnostics = mutableListOf<DiffDiagnostic>()
        val absorbedFrom = mutableSetOf<String>()
        val absorbedTo = mutableSetOf<String>()
        val absorbedViews = mutableSetOf<String>()
        val reports = mutableListOf<RenameProjectionReport>()
        for (item in items) {
            val candidate = item.candidate
            when {
                !candidate.renamable -> {
                    diagnostics += RenameOverlayMapper.structuralMismatchTableDiagnostic(candidate)
                    reports += tableFallbackReport(
                        candidate = candidate,
                        fallbackReason = "structural mismatch — drop+create",
                        blockers = emptyList(),
                    )
                }
                candidate.staleReferenceObject != null -> {
                    diagnostics += RenameOverlayMapper.staleReferenceTableDiagnostic(candidate)
                    reports += tableFallbackReport(
                        candidate = candidate,
                        fallbackReason = "stale cross-table reference — drop+create",
                        blockers = emptyList(),
                    )
                }
                else -> {
                    val projection = policy.classifyTableRename(candidate, diff, current, desired, capabilities)
                    if (projection.isAutomatic) {
                        ops += RenameOverlayMapper.buildRenameTableOperation(candidate)
                        ops += item.postRenameDeltaOperations
                        ops += projection.explicit
                        absorbedFrom += candidate.fromName
                        absorbedTo += candidate.toName
                        absorbedViews += projection.absorbedViews
                        reports += tableSuccessReport(candidate, projection)
                    } else {
                        diagnostics += projection.blockers.map { it.toDiagnostic() }
                        reports += tableFallbackReport(
                            candidate = candidate,
                            fallbackReason = "policy blockers — drop+create",
                            blockers = projection.blockers,
                        )
                    }
                }
            }
        }
        return RenameTableProjection(ops, diagnostics, absorbedFrom, absorbedTo, absorbedViews, reports)
    }

    private fun tableSuccessReport(
        candidate: RenameTableCandidate,
        projection: RenameProjection,
    ): RenameProjectionReport = RenameProjectionReport(
        candidateId = candidate.id,
        objectType = "table",
        fromPath = listOf(candidate.fromName),
        toPath = listOf(candidate.toName),
        overlaySource = candidate.overlaySource,
        overlayEntryId = candidate.overlayEntryId,
        overlayHash = candidate.overlayHash,
        renameOperationId = candidate.id,
        fallbackOperationIds = emptyList(),
        fallbackReason = null,
        automatic = projection.automatic,
        // T5 emits `DropView` + `CreateView` today, but other kinds
        // (trigger drop+create, etc.) will land in later tranches —
        // surface them with a generic kind rather than silently
        // dropping. A `kind = "EXPLICIT"` entry tells report
        // consumers "the projector emitted an explicit follow-up,
        // dialect-specific kind not yet classified".
        explicit = projection.explicit.map(::explicitRef),
        blockers = emptyList(),
    )

    /**
     * T5 emits `DropView` + `CreateView` today, but other kinds
     * (trigger drop+create, etc.) will land in later tranches —
     * surface them with a generic kind rather than silently dropping.
     * A `kind = "EXPLICIT"` entry tells report consumers "the
     * projector emitted an explicit follow-up, dialect-specific kind
     * not yet classified".
     *
     * Plan-2 §8 D.3b Sub-Slices A/B: materialized-view reprojection
     * gets its own kinds so report consumers can tell an MV
     * drop+create (or replace, Sub-Slice B) from a regular one.
     */
    private fun explicitRef(op: DiffOperation): ExplicitProjectionRef = ExplicitProjectionRef(
        kind = when (op) {
            is DiffOperation.CreateView -> "VIEW_CREATE"
            is DiffOperation.DropView -> "VIEW_DROP"
            is DiffOperation.CreateMaterializedView -> "MATERIALIZED_VIEW_CREATE"
            is DiffOperation.ReplaceMaterializedView -> "MATERIALIZED_VIEW_REPLACE"
            is DiffOperation.DropMaterializedView -> "MATERIALIZED_VIEW_DROP"
            else -> "EXPLICIT"
        },
        path = op.objectRef.path,
        operationId = op.id,
    )

    private fun tableFallbackReport(
        candidate: RenameTableCandidate,
        fallbackReason: String,
        blockers: List<RenameProjectionBlocker>,
    ): RenameProjectionReport = RenameProjectionReport(
        candidateId = candidate.id,
        objectType = "table",
        fromPath = listOf(candidate.fromName),
        toPath = listOf(candidate.toName),
        overlaySource = candidate.overlaySource,
        overlayEntryId = candidate.overlayEntryId,
        overlayHash = candidate.overlayHash,
        renameOperationId = null,
        fallbackOperationIds = candidate.fallbackOperationIds,
        fallbackReason = fallbackReason,
        blockers = blockers,
    )

    fun projectColumns(
        items: List<RenameColumnPlanningItem>,
        table: TableDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
    ): RenameColumnProjection {
        if (items.isEmpty()) return EMPTY_COLUMN_PROJECTION
        val ops = mutableListOf<DiffOperation>()
        val diagnostics = mutableListOf<DiffDiagnostic>()
        val absorbedFrom = mutableSetOf<String>()
        val absorbedTo = mutableSetOf<String>()
        val absorbedViews = mutableSetOf<String>()
        val reports = mutableListOf<RenameProjectionReport>()
        for (item in items) {
            val candidate = item.candidate
            when {
                !candidate.renamable -> {
                    diagnostics += RenameOverlayMapper.structuralMismatchColumnDiagnostic(candidate)
                    reports += columnFallbackReport(candidate, "structural mismatch — drop+add", emptyList())
                }
                candidate.referencingObject != null -> {
                    diagnostics += RenameOverlayMapper.dependencyProjectionColumnDiagnostic(candidate)
                    reports += columnFallbackReport(
                        candidate, "same-table reference object touches column — drop+add", emptyList(),
                    )
                }
                else -> {
                    val projection = policy.classifyColumnRename(candidate, table, current, desired, capabilities)
                    if (projection.isAutomatic) {
                        ops += RenameOverlayMapper.buildRenameColumnOperation(candidate)
                        ops += item.postRenameDeltaOperations
                        // Dialekte, deren Engine abhaengige Sichten beim
                        // Spalten-Rename invalid zuruecklaesst (Oracle,
                        // gemessen), projizieren sie hier neu. Die
                        // absorbierten Namen MUESSEN mitwandern, sonst
                        // emittiert `mapViews` zusaetzlich ein
                        // `ReplaceView` auf demselben Objekt.
                        ops += projection.explicit
                        absorbedViews += projection.absorbedViews
                        absorbedFrom += candidate.fromColumn
                        absorbedTo += candidate.toColumn
                        reports += columnSuccessReport(candidate, projection)
                    } else {
                        diagnostics += projection.blockers.map { it.toDiagnostic() }
                        reports += columnFallbackReport(
                            candidate, "policy blockers — drop+add", projection.blockers,
                        )
                    }
                }
            }
        }
        return RenameColumnProjection(ops, diagnostics, absorbedFrom, absorbedTo, reports, absorbedViews)
    }

    private fun columnSuccessReport(
        candidate: RenameColumnCandidate,
        projection: RenameProjection,
    ): RenameProjectionReport = RenameProjectionReport(
        candidateId = candidate.id,
        objectType = "column",
        fromPath = listOf(candidate.tableName, candidate.fromColumn),
        toPath = listOf(candidate.tableName, candidate.toColumn),
        overlaySource = candidate.overlaySource,
        overlayEntryId = candidate.overlayEntryId,
        overlayHash = candidate.overlayHash,
        renameOperationId = candidate.id,
        fallbackOperationIds = emptyList(),
        fallbackReason = null,
        automatic = projection.automatic,
        // Frueher hart `emptyList()` -- mit der Spalten-Reprojektion
        // stehen hier echte Folge-Operationen im Plan, und der Report
        // ist der Audit-Traeger dafuer.
        explicit = projection.explicit.map(::explicitRef),
        blockers = emptyList(),
    )

    private fun columnFallbackReport(
        candidate: RenameColumnCandidate,
        fallbackReason: String,
        blockers: List<RenameProjectionBlocker>,
    ): RenameProjectionReport = RenameProjectionReport(
        candidateId = candidate.id,
        objectType = "column",
        fromPath = listOf(candidate.tableName, candidate.fromColumn),
        toPath = listOf(candidate.tableName, candidate.toColumn),
        overlaySource = candidate.overlaySource,
        overlayEntryId = candidate.overlayEntryId,
        overlayHash = candidate.overlayHash,
        renameOperationId = null,
        fallbackOperationIds = candidate.fallbackOperationIds,
        fallbackReason = fallbackReason,
        blockers = blockers,
    )

    private fun RenameProjectionBlocker.toDiagnostic(): DiffDiagnostic = DiffDiagnostic(
        code = code,
        message = message,
        severity = severity,
    )

    companion object {
        private val EMPTY_TABLE_PROJECTION = RenameTableProjection(
            emptyList(), emptyList(), emptySet(), emptySet(),
        )
        private val EMPTY_COLUMN_PROJECTION = RenameColumnProjection(
            emptyList(), emptyList(), emptySet(), emptySet(),
        )
    }
}
