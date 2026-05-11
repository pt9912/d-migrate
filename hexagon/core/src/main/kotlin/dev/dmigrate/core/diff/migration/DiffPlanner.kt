package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.ViewDefinition

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
 * `docs/planning/in-progress/diffresult-migration-plan.md §11.1`. Operations
 * on *unblocked* tables that nonetheless reference a blocked table
 * (FK column / FK constraint) are tagged with a
 * `FK_TO_BLOCKED_TABLE` blocker so the renderer cannot silently emit
 * dangling references.
 *
 * Phase F.6.b decision (View column-level deps): column-altering
 * operations (`DropColumn`, `AlterColumnType`,
 * `AlterColumnNullability`) on a table referenced by a view that
 * lacks column-level dependency info produce a
 * `VIEW_DEPENDS_ON_TABLE_LACKS_COLUMN_DEPS` blocker diagnostic. See
 * Plan §6.3.
 *
 * Phase G.2 decision (incomplete view projection): when an adapter
 * reports `dependencies.projectionComplete = false` for a view
 * (today only MySQL when `VIEW_TABLE_USAGE` / `VIEW_ROUTINE_USAGE`
 * return 0 rows for an existing view), `ReplaceView` for the view
 * and column-altering operations on listed dependency tables block
 * with `VIEW_DEPENDENCY_PROJECTION_INCOMPLETE`. See Plan §G.2 /
 * §10 L2096-L2099.
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
                    "`docs/planning/in-progress/diffresult-migration-plan.md §11.1`).",
                severity = DiffDiagnostic.Severity.BLOCKER,
            )
        }

        val rawOps = OperationMapper.map(schemaDiff, current, desired, blockedTables)
        val opsWithDeps = DependencyAnalyzer.attach(rawOps)
        val sortResult = TopologicalSorter.sort(opsWithDeps)

        diagnostics += detectFkToBlockedTables(sortResult.sorted, blockedTables)
        diagnostics += detectViewColumnDepsBlockers(sortResult.sorted, current, desired)
        diagnostics += detectIncompleteViewProjections(sortResult.sorted, current, desired)
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

    /**
     * Per Plan §6.3 / §F.6.b: when a view declares a table-level
     * dependency on a table T but no column-level dependency entry
     * for T is supplied, the planner cannot tell whether the view
     * references a specific column of T. Column-altering operations
     * (`DropColumn`, `AlterColumnType`, `AlterColumnNullability`) on
     * such tables MUST block — the renderer would otherwise emit DDL
     * that silently breaks the view at execute time.
     *
     * This is dialect-agnostic: PostgreSQL adapters that supply
     * column-level deps (via `pg_depend`) never trip the block; MySQL
     * adapters can't (no `VIEW_COLUMN_USAGE` source) and must block
     * unless the user supplied an explicit schema file with column-
     * level deps.
     *
     * The check considers views from BOTH `current` and `desired` —
     * a view present only in `current` (slated for `DropView`) still
     * exists in the live target until its drop runs, and a view in
     * `desired` will be created/updated to depend on the table; both
     * sides need the column-level signal to plan safely.
     */
    private fun detectViewColumnDepsBlockers(
        ops: List<DiffOperation>,
        current: SchemaDefinition,
        desired: SchemaDefinition,
    ): List<DiffDiagnostic> {
        val viewsByTable = collectViewsByTable(current, desired)
        if (viewsByTable.isEmpty()) return emptyList()
        val out = mutableListOf<DiffDiagnostic>()
        for (op in ops) {
            val (tableName, columnName) = columnAlteringTarget(op) ?: continue
            for ((viewName, view) in viewsByTable[tableName].orEmpty()) {
                val columnDeps = view.dependencies?.columns?.get(tableName)
                if (columnDeps != null) continue // column-level info present → trust it
                out += DiffDiagnostic(
                    code = "VIEW_DEPENDS_ON_TABLE_LACKS_COLUMN_DEPS",
                    message = "Operation ${op.id} alters column '$tableName.$columnName' but " +
                        "view '$viewName' declares a table-level dependency on '$tableName' " +
                        "without column-level dependency information. Per Plan §6.3 the " +
                        "planner cannot determine if the view references this column; " +
                        "supply column-level deps in the schema file or use an adapter " +
                        "projection that exposes them (PostgreSQL pg_depend; MySQL has no " +
                        "VIEW_COLUMN_USAGE source).",
                    severity = DiffDiagnostic.Severity.BLOCKER,
                    operationId = op.id,
                )
            }
        }
        return out
    }

    /**
     * Per Plan §G.2 / §10 L2096-L2099: when an adapter reports that
     * its view dependency projection is incomplete
     * (`dependencies.projectionComplete == false`), the planner
     * cannot trust the per-view `tables` list. Two operation classes
     * MUST block:
     *
     * - `ReplaceView` for the incomplete view — the renderer would
     *   regenerate the view DDL but the diff cannot reason about
     *   cascade effects on hidden dependencies.
     * - `DropColumn` / `AlterColumnType` / `AlterColumnNullability`
     *   on a table that *is* listed in the incomplete view's
     *   `dependencies.tables`. (Tables NOT in the list might still
     *   be referenced — the projection is incomplete — but blocking
     *   "all column-altering ops if any view in the schema is
     *   incomplete" would be unactionable. The listed-tables block
     *   stays inside the part of the projection the adapter trusts;
     *   the incompleteness of the list itself remains a
     *   ReplaceView-side concern.)
     *
     * Today this only triggers for the MySQL adapter, which sets
     * `projectionComplete=false` when `VIEW_TABLE_USAGE` returns 0
     * rows for an existing view (typically a missing SHOW VIEW
     * privilege). PostgreSQL/SQLite/file-loaders always default to
     * `projectionComplete=true`.
     */
    private fun detectIncompleteViewProjections(
        ops: List<DiffOperation>,
        current: SchemaDefinition,
        desired: SchemaDefinition,
    ): List<DiffDiagnostic> {
        val incomplete = collectIncompleteViews(current, desired)
        if (incomplete.isEmpty()) return emptyList()
        val out = mutableListOf<DiffDiagnostic>()
        for (op in ops) {
            when (op) {
                is DiffOperation.ReplaceView -> {
                    val viewName = op.objectRef.path.firstOrNull() ?: continue
                    if (viewName !in incomplete) continue
                    out += DiffDiagnostic(
                        code = "VIEW_DEPENDENCY_PROJECTION_INCOMPLETE",
                        message = "Operation ${op.id} replaces view '$viewName' but the adapter " +
                            "reported its dependency projection as incomplete " +
                            "(`dependencies.projectionComplete = false`). For MySQL this typically " +
                            "means VIEW_TABLE_USAGE / VIEW_ROUTINE_USAGE returned 0 rows for an " +
                            "existing view — the introspecting user likely lacks SHOW VIEW on " +
                            "referenced tables. The planner cannot reason about cascade effects " +
                            "until projection completeness is restored.",
                        severity = DiffDiagnostic.Severity.BLOCKER,
                        operationId = op.id,
                    )
                }
                else -> {
                    val (tableName, columnName) = columnAlteringTarget(op) ?: continue
                    for ((viewName, view) in incomplete) {
                        if (tableName !in (view.dependencies?.tables ?: emptyList())) continue
                        out += DiffDiagnostic(
                            code = "VIEW_DEPENDENCY_PROJECTION_INCOMPLETE",
                            message = "Operation ${op.id} alters column '$tableName.$columnName' " +
                                "and view '$viewName' lists '$tableName' as a dependency, but the " +
                                "view's projection is incomplete " +
                                "(`dependencies.projectionComplete = false`). For MySQL this " +
                                "typically means VIEW_TABLE_USAGE / VIEW_ROUTINE_USAGE returned " +
                                "incomplete rows — the planner cannot tell whether the view's " +
                                "referenced columns include this one. Restore SHOW VIEW privilege " +
                                "on referenced tables and re-introspect.",
                            severity = DiffDiagnostic.Severity.BLOCKER,
                            operationId = op.id,
                        )
                    }
                }
            }
        }
        return out
    }

    /**
     * `viewName → ViewDefinition` for views whose dependency projection
     * is incomplete on either side. The desired-side definition wins
     * for views present in both schemas — the planner only needs ONE
     * incomplete signal to block.
     */
    private fun collectIncompleteViews(
        current: SchemaDefinition,
        desired: SchemaDefinition,
    ): Map<String, ViewDefinition> {
        val out = mutableMapOf<String, ViewDefinition>()
        for (schema in listOf(current, desired)) {
            for ((viewName, view) in schema.views) {
                val deps = view.dependencies ?: continue
                if (!deps.projectionComplete) out[viewName] = view
            }
        }
        return out
    }

    /**
     * Walk both schemas and produce a `tableName → viewName →
     * ViewDefinition` map. The desired-side definition wins for views
     * present in both schemas — for the F.6.b check that doesn't
     * matter, since either side without column-level deps still
     * triggers the block, but the dedupe keeps each view emitting at
     * most one diagnostic per op.
     */
    private fun collectViewsByTable(
        current: SchemaDefinition,
        desired: SchemaDefinition,
    ): Map<String, Map<String, ViewDefinition>> {
        val out = mutableMapOf<String, MutableMap<String, ViewDefinition>>()
        for (schema in listOf(current, desired)) {
            for ((viewName, view) in schema.views) {
                for (tableName in view.dependencies?.tables.orEmpty()) {
                    out.getOrPut(tableName) { mutableMapOf() }[viewName] = view
                }
            }
        }
        return out
    }

    private fun columnAlteringTarget(op: DiffOperation): Pair<String, String>? = when (op) {
        is DiffOperation.DropColumn -> op.objectRef.path[0] to op.objectRef.path[1]
        is DiffOperation.AlterColumnType -> op.objectRef.path[0] to op.objectRef.path[1]
        is DiffOperation.AlterColumnNullability -> op.objectRef.path[0] to op.objectRef.path[1]
        else -> null
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
