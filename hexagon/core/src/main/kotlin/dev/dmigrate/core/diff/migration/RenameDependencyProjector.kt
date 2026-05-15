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
        for (item in items) {
            val candidate = item.candidate
            when {
                !candidate.renamable -> {
                    diagnostics += RenameOverlayMapper.structuralMismatchTableDiagnostic(candidate)
                }
                candidate.staleReferenceObject != null -> {
                    diagnostics += RenameOverlayMapper.staleReferenceTableDiagnostic(candidate)
                }
                else -> {
                    val projection = policy.classifyTableRename(candidate, diff, current, desired, capabilities)
                    if (projection.isAutomatic) {
                        ops += RenameOverlayMapper.buildRenameTableOperation(candidate)
                        ops += item.postRenameDeltaOperations
                        absorbedFrom += candidate.fromName
                        absorbedTo += candidate.toName
                    } else {
                        diagnostics += projection.blockers.map { it.toDiagnostic() }
                    }
                }
            }
        }
        return RenameTableProjection(ops, diagnostics, absorbedFrom, absorbedTo)
    }

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
        for (item in items) {
            val candidate = item.candidate
            when {
                !candidate.renamable -> {
                    diagnostics += RenameOverlayMapper.structuralMismatchColumnDiagnostic(candidate)
                }
                candidate.referencingObject != null -> {
                    diagnostics += RenameOverlayMapper.dependencyProjectionColumnDiagnostic(candidate)
                }
                else -> {
                    val projection = policy.classifyColumnRename(candidate, table, current, desired, capabilities)
                    if (projection.isAutomatic) {
                        ops += RenameOverlayMapper.buildRenameColumnOperation(candidate)
                        ops += item.postRenameDeltaOperations
                        absorbedFrom += candidate.fromColumn
                        absorbedTo += candidate.toColumn
                    } else {
                        diagnostics += projection.blockers.map { it.toDiagnostic() }
                    }
                }
            }
        }
        return RenameColumnProjection(ops, diagnostics, absorbedFrom, absorbedTo)
    }

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
