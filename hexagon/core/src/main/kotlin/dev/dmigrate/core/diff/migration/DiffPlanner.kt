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
 * - [TopologicalSorter] orders the operations with [DiffPhase]
 *   as the deterministic tie-breaker.
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
 * `docs/planning/open/diffresult-migration-plan.md §11.1`.
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
        val sorted = TopologicalSorter.sort(opsWithDeps)

        return DiffResult(
            current = endpoint(current),
            desired = endpoint(desired),
            schemaDiff = schemaDiff,
            operations = sorted,
            diagnostics = diagnostics,
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
}
