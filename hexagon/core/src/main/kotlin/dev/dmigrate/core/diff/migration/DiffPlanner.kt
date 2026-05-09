package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition

/**
 * Maps a [SchemaDiff] to a runnable [DiffResult].
 *
 * Pipeline position: `(current, desired) → SchemaComparator →
 * SchemaDiff → DiffPlanner → DiffResult → DialectRenderer →
 * MigrationDdlResult`. The planner emits no SQL — that is the
 * dialect renderer's responsibility in Phase D.
 *
 * Implementation is split across three internal helpers:
 *
 * - [OperationMapper] turns the [SchemaDiff] into a flat list of
 *   [DiffOperation]s with stable IDs but no dependency edges.
 * - [DependencyAnalyzer] attaches FK / view-dependency edges.
 * - [TopologicalSorter] orders the operations with the
 *   deterministic phase / object-type / display-name / id
 *   comparator as tie-breaker; cycles surface as a `DEPENDENCY_CYCLE`
 *   blocker.
 *
 * Out-of-scope refinements (Phase D / Phase E):
 *
 * - Rename detection (Drop+Add pairs are surfaced; not collapsed
 *   automatically — Plan §4.3).
 * - View-level fine-grained column tracking.
 * - Routine-only dependencies between Functions/Procedures and Views.
 *
 * Phase A decision (CHECK/EXCLUDE constraints): tables carrying these
 * are surfaced via a `CONSTRAINT_NOT_DIFFABLE` blocker diagnostic and
 * skipped in the operation list — see
 * `docs/planning/open/diffresult-migration-plan.md §11.1`. Operations
 * on *unblocked* tables that nonetheless reference a blocked table
 * (FK column / FK constraint) are tagged with a
 * `FK_TO_BLOCKED_TABLE` blocker so the renderer cannot silently emit
 * dangling references.
 */
class DiffPlanner {

    fun plan(
        current: SchemaDefinition,
        desired: SchemaDefinition,
        schemaDiff: SchemaDiff,
    ): DiffResult {
        val diagnostics = mutableListOf<DiffDiagnostic>()
        val blockedTables = detectConstraintNotDiffableTables(current, desired)
        if (blockedTables.isNotEmpty()) {
            diagnostics += DiffDiagnostic(
                code = "CONSTRAINT_NOT_DIFFABLE",
                message = "Table(s) carry CHECK/EXCLUDE constraints which the comparator does not " +
                    "diff lossless: ${blockedTables.sorted().joinToString(", ")}. Migration cannot " +
                    "be planned for these tables (Phase A decision; see " +
                    "`docs/planning/open/diffresult-migration-plan.md §11.1`).",
                severity = DiffDiagnostic.Severity.BLOCKER,
            )
        }

        val rawOps = OperationMapper.map(schemaDiff, current, desired, blockedTables)
        val opsWithDeps = DependencyAnalyzer.attach(rawOps)
        val sortResult = TopologicalSorter.sort(opsWithDeps)

        diagnostics += detectFkToBlockedTables(sortResult.sorted, blockedTables)
        if (sortResult.cycleIds.isNotEmpty()) {
            diagnostics += DiffDiagnostic(
                code = "DEPENDENCY_CYCLE",
                message = "Dependency cycle detected; the following operations could not be " +
                    "topologically sorted: ${sortResult.cycleIds.sorted().joinToString(", ")}. " +
                    "This is a planner-internal bug; the affected operations are appended in " +
                    "deterministic phase / type / name order so the rest of the plan remains " +
                    "renderable, but the cycle members must not be executed without manual " +
                    "review.",
                severity = DiffDiagnostic.Severity.BLOCKER,
            )
        }

        return DiffResult(
            current = endpoint(current),
            desired = endpoint(desired),
            schemaDiff = schemaDiff,
            operations = sortResult.sorted,
            diagnostics = diagnostics,
            currentSchema = current,
            desiredSchema = desired,
        )
    }

    private fun endpoint(schema: SchemaDefinition): DiffEndpoint =
        DiffEndpoint(
            schemaName = schema.name,
            schemaVersion = schema.version,
            fingerprint = MigrationFingerprint.compute(schema),
        )

    private fun detectConstraintNotDiffableTables(
        current: SchemaDefinition,
        desired: SchemaDefinition,
    ): Set<String> {
        val blocked = mutableSetOf<String>()
        for ((name, table) in current.tables) if (hasNotDiffableConstraint(table)) blocked += name
        for ((name, table) in desired.tables) if (hasNotDiffableConstraint(table)) blocked += name
        return blocked
    }

    private fun hasNotDiffableConstraint(table: TableDefinition): Boolean =
        table.constraints.any { it.type == ConstraintType.CHECK || it.type == ConstraintType.EXCLUDE }

    private fun detectFkToBlockedTables(
        ops: List<DiffOperation>,
        blockedTables: Set<String>,
    ): List<DiffDiagnostic> {
        if (blockedTables.isEmpty()) return emptyList()
        val out = mutableListOf<DiffDiagnostic>()
        for (op in ops) {
            val target = fkTargetOf(op) ?: continue
            if (target in blockedTables) {
                out += DiffDiagnostic(
                    code = "FK_TO_BLOCKED_TABLE",
                    message = "Operation ${op.id} on ${op.objectRef.displayName} carries a " +
                        "foreign-key reference to '$target', which is blocked by " +
                        "CONSTRAINT_NOT_DIFFABLE. The renderer must not execute this operation " +
                        "until the target table is migratable.",
                    severity = DiffDiagnostic.Severity.BLOCKER,
                    operationId = op.id,
                )
            }
        }
        return out
    }

    private fun fkTargetOf(op: DiffOperation): String? = when (op) {
        is DiffOperation.CreateTable -> firstFkTarget(op.table)
        is DiffOperation.AddColumn -> op.column.references?.table
        is DiffOperation.AddConstraint ->
            if (op.constraint.type == ConstraintType.FOREIGN_KEY) op.constraint.references?.table else null
        else -> null
    }

    private fun firstFkTarget(table: TableDefinition): String? {
        for (col in table.columns.values) col.references?.table?.let { return it }
        for (c in table.constraints) {
            if (c.type == ConstraintType.FOREIGN_KEY) c.references?.table?.let { return it }
        }
        return null
    }
}
