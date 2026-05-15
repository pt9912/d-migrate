package dev.dmigrate.core.diff.migration

/**
 * F.4 dependency-projection T2 pass-through projector.
 *
 * Consumes a list of [RenamePlanningItem]s and folds each into either:
 *
 * - a single `RenameTable` / `RenameColumn` operation, when the
 *   candidate is structurally equal AND no cross-object dependency
 *   blocks the rename;
 * - the candidate's [RenamePlanningItem.fallbackOperations] plus a
 *   `RENAME_OVERLAY_STRUCTURAL_MISMATCH` /
 *   `RENAME_OVERLAY_DEPENDENCY_PROJECTION_REQUIRED` diagnostic.
 *
 * Behaviour is intentionally a 1:1 mirror of the pre-T2
 * `RenameOverlayMapper.foldRenameTables` / `foldRenameColumns` paths so
 * the slice is observable-equivalent. T3 will replace the projector
 * with a dialect-aware policy implementation that classifies
 * dependencies into `AUTOMATIC_BY_ENGINE`, `EXPLICIT_REPROJECTION`, and
 * `NO_PROJECTION_AVAILABLE` outcomes.
 *
 * The projector is intentionally stateless and reuses the diagnostic
 * builders + rename operation builders in [RenameOverlayMapper] so the
 * diagnostic codes and messages stay stable across the refactor.
 */
internal object RenamePassThroughProjector {

    /**
     * Outcome of folding a batch of [RenameTablePlanningItem]s. The
     * `absorbed*` sets describe the source/target table names whose
     * Drop+Add pair was replaced by a `RenameTable`; the mapper skips
     * those names from its regular drop/create path.
     */
    data class TableProjection(
        val operations: List<DiffOperation>,
        val diagnostics: List<DiffDiagnostic>,
        val absorbedFromNames: Set<String>,
        val absorbedToNames: Set<String>,
    )

    /**
     * Outcome of folding a batch of [RenameColumnPlanningItem]s for one
     * table. `absorbed*Columns` carry the column names whose drop/add
     * pair was replaced.
     */
    data class ColumnProjection(
        val operations: List<DiffOperation>,
        val diagnostics: List<DiffDiagnostic>,
        val absorbedFromColumns: Set<String>,
        val absorbedToColumns: Set<String>,
    )

    fun projectTables(items: List<RenameTablePlanningItem>): TableProjection {
        if (items.isEmpty()) return EMPTY_TABLE_PROJECTION
        val ops = mutableListOf<DiffOperation>()
        val diagnostics = mutableListOf<DiffDiagnostic>()
        val absorbedFrom = mutableSetOf<String>()
        val absorbedTo = mutableSetOf<String>()
        for (item in items) {
            val candidate = item.candidate
            when {
                !candidate.structurallyEqual -> {
                    diagnostics += RenameOverlayMapper.structuralMismatchTableDiagnostic(candidate)
                }
                candidate.staleReferenceObject != null -> {
                    diagnostics += RenameOverlayMapper.staleReferenceTableDiagnostic(candidate)
                }
                else -> {
                    ops += RenameOverlayMapper.buildRenameTableOperation(candidate)
                    ops += item.postRenameDeltaOperations
                    absorbedFrom += candidate.fromName
                    absorbedTo += candidate.toName
                }
            }
        }
        return TableProjection(ops, diagnostics, absorbedFrom, absorbedTo)
    }

    fun projectColumns(items: List<RenameColumnPlanningItem>): ColumnProjection {
        if (items.isEmpty()) return EMPTY_COLUMN_PROJECTION
        val ops = mutableListOf<DiffOperation>()
        val diagnostics = mutableListOf<DiffDiagnostic>()
        val absorbedFrom = mutableSetOf<String>()
        val absorbedTo = mutableSetOf<String>()
        for (item in items) {
            val candidate = item.candidate
            when {
                !candidate.structurallyEqual -> {
                    diagnostics += RenameOverlayMapper.structuralMismatchColumnDiagnostic(candidate)
                }
                candidate.referencingObject != null -> {
                    diagnostics += RenameOverlayMapper.dependencyProjectionColumnDiagnostic(candidate)
                }
                else -> {
                    ops += RenameOverlayMapper.buildRenameColumnOperation(candidate)
                    ops += item.postRenameDeltaOperations
                    absorbedFrom += candidate.fromColumn
                    absorbedTo += candidate.toColumn
                }
            }
        }
        return ColumnProjection(ops, diagnostics, absorbedFrom, absorbedTo)
    }

    private val EMPTY_TABLE_PROJECTION = TableProjection(emptyList(), emptyList(), emptySet(), emptySet())
    private val EMPTY_COLUMN_PROJECTION = ColumnProjection(emptyList(), emptyList(), emptySet(), emptySet())
}
