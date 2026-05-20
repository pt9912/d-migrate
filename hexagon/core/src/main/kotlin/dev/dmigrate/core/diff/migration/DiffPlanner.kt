package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.ConstraintDiffContract
import dev.dmigrate.core.diff.CrossTableCheckHeuristic
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
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
 * Plan-2 §F.5 vollscheibe (Sub-Slices A-G, 2026-05-20): CHECK and
 * EXCLUDE constraints flow through the standard Add/Drop/Replace
 * pipeline. The `CONSTRAINT_NOT_DIFFABLE` blanket from the first
 * slice is replaced by per-dialect renderers (PG native, MySQL via
 * `MysqlCheckEnforcementCapability`, SQLite via the rebuild
 * pipeline), a live-data preflight gate in execute mode
 * (`CheckPreflightGate`), and the `ConstraintReplaceContract`
 * post-pass that pins per-op reversibility. The only remaining
 * planner-level CHECK/EXCLUDE block is the conservative cross-table
 * heuristic (`CHECK_EXPRESSION_CROSS_TABLE_UNSUPPORTED`). Operations
 * on unblocked tables that nonetheless reference a blocked table (FK
 * column / FK constraint) are tagged with a `FK_TO_BLOCKED_TABLE`
 * blocker so the renderer cannot silently emit dangling references.
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
/**
 * Diff-to-plan orchestrator. `open` purely so tests can subclass
 * it as a spy (see `SchemaMigratePrePlanOverlayGateTest`); the
 * extension surface is **not** part of the public contract and may
 * be replaced by a function-typed runner parameter in a later
 * refactor. Production code should treat `DiffPlanner` as final and
 * inject it as a concrete dependency.
 */
open class DiffPlanner {

    /**
     * Plan a migration. See class-level KDoc for the pipeline
     * ordering.
     *
     * @param capabilities consumed by [RenameDependencyPolicy] to
     *   classify rename candidates. The default
     *   `fileOnly(POSTGRESQL)` exists for dialect-agnostic tests that
     *   don't exercise the rename policy at all; production paths
     *   (currently `SchemaMigrateRunner.execute`) build the
     *   capability bundle from the resolved migrate dialect via
     *   `RenameProjectionCapabilitiesFactory.capabilitiesFor`. A
     *   future tranche may require this argument explicitly once T4
     *   introduces dialect-specific behaviour the tests must opt
     *   into.
     */
    open fun plan(
        current: SchemaDefinition,
        desired: SchemaDefinition,
        schemaDiff: SchemaDiff,
        migrationOverlays: List<MigrationOverlayDocument> = emptyList(),
        capabilities: RenameProjectionCapabilities = RenameProjectionCapabilities.fileOnly(
            RenameProjectionDialect.POSTGRESQL,
        ),
        triggerPlanningContext: TriggerPlanningContext = TriggerPlanningContext(),
    ): DiffResult {
        val diagnostics = mutableListOf<DiffDiagnostic>()
        // F.5 Sub-Slice A (2026-05-19): the planner-level block
        // narrowed from "every CHECK/EXCLUDE diff" to "only CHECK
        // expressions containing cross-table sub-query patterns".
        // Other CHECK / EXCLUDE adds / drops / changes flow through
        // the mapper and surface as `AddConstraint` /
        // `DropConstraint` ops; the renderer (Sub-Slice B/C/D wires
        // PG / MySQL / SQLite specifically) decides per dialect
        // whether to emit DDL or block with
        // `DIALECT_UNSUPPORTED_OPERATION`.
        val blockedTables = detectCrossTableCheckTables(current, desired)
        if (blockedTables.isNotEmpty()) {
            // String constant mirrored in
            // `dev.dmigrate.driver.migration.PlannerBlockerClassifier.CHECK_EXPRESSION_CROSS_TABLE_UNSUPPORTED_CODE`
            // (hexagon:ports-read). Both must stay in sync; the
            // module split prevents a direct reference.
            diagnostics += DiffDiagnostic(
                code = "CHECK_EXPRESSION_CROSS_TABLE_UNSUPPORTED",
                message = "Table(s) declare CHECK expressions that look like cross-table sub-queries " +
                    "(`SELECT` keyword inside the expression): " +
                    "${blockedTables.sorted().joinToString(", ")}. F.5 Sub-Slice A uses a conservative " +
                    "text heuristic to keep cross-table CHECK constraints off the renderer path; " +
                    "rewrite the expression as a stand-alone predicate or remove the sub-query and " +
                    "the migration can proceed. Semantic SQL parsing for CHECK expressions is a " +
                    "separate workstream (see F.5 plan §9).",
                severity = DiffDiagnostic.Severity.BLOCKER,
            )
        }

        val mapperResult = OperationMapper.map(
            schemaDiff, current, desired, blockedTables, migrationOverlays, capabilities, triggerPlanningContext,
        )
        diagnostics += mapperResult.diagnostics
        val splitOps = splitReplaceViewsForColumnConflicts(mapperResult.operations)
        val opsWithFkDeps = DependencyAnalyzer.attach(splitOps)
        // E.1 Slice D.1: second-phase analyzer for routine / view /
        // trigger / sequence cross-edges plus unsafe-routine-pair
        // detection. The unsafe-pair findings turn into
        // `UNSAFE_DEPENDENCY_PAIR` WARNING diagnostics below — see
        // ADR 0002 for the WARNING-vs-BLOCKER rationale.
        val routineResult = RoutineDependencyAnalyzer.attach(opsWithFkDeps)
        val sortResult = TopologicalSorter.sort(routineResult.operations)

        diagnostics += detectFkToBlockedTables(sortResult.sorted, blockedTables)
        diagnostics += detectViewColumnDepsBlockers(sortResult.sorted, current, desired)
        diagnostics += detectIncompleteViewProjections(sortResult.sorted, current, desired)
        val mvDependencyBlockers = MaterializedViewDependencyDetector.detect(
            sortResult.sorted, current, desired,
        )
        for (blocker in mvDependencyBlockers) {
            diagnostics += DiffDiagnostic(
                code = "BLOCKED_DEPENDENCY_UNRESOLVED",
                message = "Materialized view '${blocker.materializedViewName}' depends on " +
                    "${blocker.droppingKind.lowercase()} '${blocker.droppingPath.joinToString(".")}'. " +
                    "Operation ${blocker.droppingOperationId} would orphan the MV — D.3b requires the " +
                    "MV to be dropped or replaced in the same plan, or the depended-on object to stay.",
                severity = DiffDiagnostic.Severity.BLOCKER,
                operationId = blocker.droppingOperationId,
            )
        }
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
        for (pair in routineResult.unsafePairs) {
            // E.1 Slice D.4: kept as a WARNING-severity safety net.
            // The D.4 DependencyGuardEvaluator runs an
            // edge-driven topology check, so an actual hidden
            // dependency would have to be missing from BOTH the
            // manifest AND the engine-metadata projection (D.2 /
            // D.3) to escape. The WARNING covers that residual
            // risk: it nudges the operator to declare the
            // relationship explicitly via `dependencies.functions`
            // when the file-only path lacks engine verification.
            // Promotion to BLOCKER would lock every file-to-file
            // multi-routine plan out by default, so the safer
            // policy is to keep the diagnostic informational and
            // rely on the topology evaluator for the actual
            // routing decision.
            diagnostics += DiffDiagnostic(
                code = "UNSAFE_DEPENDENCY_PAIR",
                message = "Routine pair '${pair.first.displayName}' ↔ '${pair.second.displayName}' " +
                    "co-exists in the plan without a manifest-declared dependency in either " +
                    "direction. The D.4 topology evaluator currently treats them as independent " +
                    "because no edge is visible — if that is wrong, declare the relationship via " +
                    "`dependencies.functions` in the routine's schema entry so the analyzer can " +
                    "sequence the operations correctly.",
                severity = DiffDiagnostic.Severity.WARNING,
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
            migrationOverlays = migrationOverlays,
            renameProjections = mapperResult.renameProjections,
            materializedViewDependencyBlockers = mvDependencyBlockers,
        )
    }

    private fun endpoint(schema: SchemaDefinition): DiffEndpoint =
        DiffEndpoint(
            schemaName = schema.name,
            schemaVersion = schema.version,
            fingerprint = MigrationFingerprint.compute(schema),
        )

    /**
     * F.5 Sub-Slice A: tables that carry a CHECK or EXCLUDE
     * constraint whose expression triggers the
     * [CrossTableCheckHeuristic] (any side — `current` or `desired`
     * — counts). The planner blocks the entire table with
     * `CHECK_EXPRESSION_CROSS_TABLE_UNSUPPORTED` so the operator
     * sees a precise reason without the renderer attempting to emit
     * DDL it cannot validate.
     *
     * Non-cross-table CHECK/EXCLUDE diffs are no longer blocked at
     * the planner level (former `CONSTRAINT_NOT_DIFFABLE` blanket
     * is gone). The mapper emits `AddConstraint`/`DropConstraint`
     * ops for them; the per-dialect renderer (PG / MySQL / SQLite)
     * decides whether to render or block via
     * `DIALECT_UNSUPPORTED_OPERATION` until Sub-Slice B/C/D land.
     */
    private fun detectCrossTableCheckTables(
        current: SchemaDefinition,
        desired: SchemaDefinition,
    ): Set<String> {
        val out = mutableSetOf<String>()
        for (tableName in (current.tables.keys + desired.tables.keys)) {
            val anyCrossTable = sequenceOf(current.tables[tableName], desired.tables[tableName])
                .filterNotNull()
                .flatMap { it.constraints.asSequence() }
                .filter(ConstraintDiffContract::isRawSqlConstraint)
                .any { CrossTableCheckHeuristic.hasCrossTableReference(it.expression) }
            if (anyCrossTable) out += tableName
        }
        return out
    }

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
     * Phase G.3 — Strict-Variante per Plan §G.3 / §10 L2078-L2083:
     *
     * `OperationMapper` emits a [DiffOperation.ReplaceView] for every
     * `viewsChanged` entry. The default renderer path on PostgreSQL /
     * MySQL emits `CREATE OR REPLACE VIEW`, which PG only accepts when
     * the view's *visible* column signature (count / order / types)
     * stays the same AND no referenced table column is altered or
     * dropped in the same migration.
     *
     * When the planner can see (via `dependencies.columns`) that the
     * view references a table-column that another op in the same
     * migration alters (`DropColumn` / `AlterColumnType` /
     * `AlterColumnNullability`), `CREATE OR REPLACE VIEW` cannot work
     * — PG rejects the replace because the underlying column is gone
     * or has shifted; MySQL behaves similarly; SQLite materialises the
     * conflict via its rebuild pipeline.
     *
     * This step rewrites the affected `ReplaceView` as
     * `DropView` (before the column op) + `CreateView` (after), with
     * explicit dependency edges so the topological sorter places them
     * around the column-altering ops. Both edge directions are wired:
     *
     * - column-altering op `dependencies += dropView.id` — the drop
     *   must complete before the column op.
     * - `createView.dependencies += column-altering-op.id` for every
     *   conflicting op — the create must complete after all column
     *   ops it depended on.
     *
     * Both sides of the change are checked (`before.dependencies` and
     * `after.dependencies`): the old view's references must release
     * the underlying column before it changes, and the new view's
     * references must be satisfied by the post-change column shape.
     *
     * Reine Body-Änderungen ohne Tabellen-Impact (no overlap with any
     * column-altering op in the same migration) bleiben `ReplaceView`
     * — der `CREATE OR REPLACE VIEW`-Pfad bleibt aktiv, weil er
     * idempotenter ist als drop + create (kein Berechtigungs-/
     * Owner-/Grants-Drift).
     *
     * Spaltensignatur-Compatibility (view-eigene Spaltenanzahl/-
     * reihenfolge/-typen, Plan-§G.3-Stufe 2) ist Carve-Out auf 0.9.8+
     * — braucht eine `ViewColumn`-Modellebene oder einen Pre-Render-
     * Probe; siehe Plan §10-Akzeptanzkriterien.
     */
    private fun splitReplaceViewsForColumnConflicts(ops: List<DiffOperation>): List<DiffOperation> {
        val replaceViews = ops.filterIsInstance<DiffOperation.ReplaceView>()
        if (replaceViews.isEmpty()) return ops

        data class Split(val drop: DiffOperation.DropView, val create: DiffOperation.CreateView)

        val replacements = mutableMapOf<String, Split>()
        val conflictDepsByOpId = mutableMapOf<String, MutableSet<String>>()

        for (rv in replaceViews) {
            val referenced = referencedTableColumns(rv.before) + referencedTableColumns(rv.after)
            if (referenced.isEmpty()) continue

            val conflicting = ops.mapNotNull { op ->
                val tc = columnAlteringTarget(op) ?: return@mapNotNull null
                if (tc in referenced) op else null
            }
            if (conflicting.isEmpty()) continue

            val drop = DiffOperation.DropView(
                id = OperationIdFactory.makeId(
                    "DropView",
                    rv.objectRef,
                    CanonicalPayload.view(rv.before) + "::g3-split",
                ),
                objectRef = rv.objectRef,
                view = rv.before,
            )
            val create = DiffOperation.CreateView(
                id = OperationIdFactory.makeId(
                    "CreateView",
                    rv.objectRef,
                    CanonicalPayload.view(rv.after) + "::g3-split",
                ),
                objectRef = rv.objectRef,
                view = rv.after,
                dependencies = conflicting.map { it.id }.toSet(),
            )
            replacements[rv.id] = Split(drop, create)
            for (c in conflicting) {
                conflictDepsByOpId.getOrPut(c.id) { mutableSetOf() } += drop.id
            }
        }

        if (replacements.isEmpty()) return ops

        return ops.flatMap { op ->
            when {
                op is DiffOperation.ReplaceView && op.id in replacements -> {
                    val split = replacements.getValue(op.id)
                    listOf(split.drop, split.create)
                }
                op.id in conflictDepsByOpId ->
                    listOf(op.withDependencies(op.dependencies + conflictDepsByOpId.getValue(op.id)))
                else -> listOf(op)
            }
        }
    }

    /**
     * Flatten `view.dependencies?.columns` into a `(table, column)`
     * set. Returns empty when the view has no column-level dependency
     * information — the §F.6.b check already blocks column-altering
     * ops on such views, so the §G.3 split can safely skip them.
     */
    private fun referencedTableColumns(view: ViewDefinition): Set<Pair<String, String>> {
        val cols = view.dependencies?.columns ?: return emptySet()
        val out = mutableSetOf<Pair<String, String>>()
        for ((table, columns) in cols) {
            for (c in columns) out += table to c
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
                    val view = incomplete[viewName] ?: continue
                    out += DiffDiagnostic(
                        code = "VIEW_DEPENDENCY_PROJECTION_INCOMPLETE",
                        message = "Operation ${op.id} replaces view '$viewName' but the adapter " +
                            "reported an incomplete dependency projection (${view.projectionStatusSummary()}). " +
                            "For MySQL this typically means VIEW_TABLE_USAGE / VIEW_ROUTINE_USAGE returned " +
                            "incomplete rows, VIEW_COLUMN_USAGE is unavailable, or the introspecting user " +
                            "lacks SHOW VIEW on referenced objects. The planner cannot reason about " +
                            "cascade effects until projection completeness is restored.",
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
                                "view's projection is incomplete (${view.projectionStatusSummary()}). " +
                                "For MySQL this typically means VIEW_TABLE_USAGE / VIEW_ROUTINE_USAGE " +
                                "returned incomplete rows or column usage is unavailable — the planner " +
                                "cannot tell whether the view's referenced columns include this one. " +
                                "Restore SHOW VIEW privilege on referenced objects and re-introspect.",
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
                if (!deps.dependencyProjectionUsable()) out[viewName] = view
            }
        }
        return out
    }

    private fun ViewDefinition.projectionStatusSummary(): String {
        val deps = dependencies ?: return "no dependency metadata"
        return "projectionComplete=${deps.projectionComplete}, " +
            "table=${deps.tableProjectionStatus}, column=${deps.columnProjectionStatus}, " +
            "routine=${deps.routineProjectionStatus}"
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
