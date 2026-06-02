package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.DefaultValue

/**
 * Computes [DiffOperation.dependencies] edges between operations
 * after [OperationMapper] has produced a flat list. The analyzer
 * does not introduce cycles — circular references are a planner-
 * upstream concern (schema-level FK loops would already fail
 * validation before this layer).
 *
 * Edge rules implemented here (FK / sequence-default / view-table):
 *
 * - `CreateTable` with FK column / FK constraint → depends on the
 *   referenced table's `CreateTable` **or** `RenameTable` (when an
 *   F.4 rename brings the target name into existence in the same
 *   plan).
 * - `AddColumn` with FK reference → depends on the referenced
 *   table's `CreateTable` / `RenameTable`.
 * - `AddConstraint` (FOREIGN_KEY) → depends on the referenced
 *   table's `CreateTable` / `RenameTable`.
 * - `DropTable` → depends on the `DropConstraint` of every FK
 *   pointing at the dropped table (drop dependents first).
 * - `CreateTable` / `AddColumn` / `AlterColumnDefault` with
 *   `SequenceNextVal` → depends on the referenced `CreateSequence`
 *   **or** `RenameSequence` (F.4 Sub-Slice D — when a rename brings
 *   the target sequence name into existence in the same plan, the
 *   column-bearing op must wait for the rename to complete).
 * - `CreateView` → depends on the `CreateTable` / `RenameTable` for
 *   every table listed in `view.dependencies.tables` **and** the
 *   `CreateView`s listed in `view.dependencies.views` (chained views
 *   need create-before-create ordering; matters especially when Phase
 *   G.3 splits a `ReplaceView` into `DropView` + `CreateView` and the
 *   chained sibling does the same around a shared column-altering op).
 *
 * E.1 Routine-Migration Slice D.1: cross-object edges for routines,
 * triggers, and the Drop side are added in a second-phase pass via
 * [RoutineDependencyAnalyzer]. The pass runs after the FK / view
 * pass and additively extends each op's dependencies. The
 * `attach(...)` result also surfaces "unsafe routine pairs" (two
 * routines in the same plan with no manifest edge in either
 * direction) so the planner can emit `UNSAFE_DEPENDENCY_PAIR`
 * diagnostics.
 *
 * Out of scope (carved out for future slices):
 *
 * - Materialized view refresh ordering — owned by a separate F.x
 *   workstream.
 *
 * Schema-qualified FK references (`other_schema.users`) are not yet
 * supported — `ConstraintReferenceDefinition.table` is a plain name
 * today. When cross-schema references land, the analyzer's name
 * comparisons must switch to qualified-name lookups.
 */
internal object DependencyAnalyzer {

    fun attach(ops: List<DiffOperation>): List<DiffOperation> {
        // F.4 second-slice extension: a RenameTable makes the renamed
        // table available under its new name, so FK targets on the new
        // name must wait for the rename to complete. Both CreateTable
        // and RenameTable contribute to the lookup map; the planner-
        // assigned op-id is enough for the dependency edge.
        val tableSourceIdByName: Map<String, String> = buildMap {
            for (op in ops.filterIsInstance<DiffOperation.CreateTable>()) {
                put(op.objectRef.rootName, op.id)
            }
            for (op in ops.filterIsInstance<DiffOperation.RenameTable>()) {
                put(op.toName, op.id)
            }
        }
        // F.4 Sub-Slice D: a RenameSequence makes the renamed sequence
        // available under its new name, so column-default
        // `SequenceNextVal` references on the new name must wait for
        // the rename to complete. Same shape as `tableSourceIdByName`
        // above. Both CreateSequence and RenameSequence contribute to
        // the lookup; the planner-assigned op-id is enough for the
        // dependency edge.
        val sequenceSourceIdByName: Map<String, String> = buildMap {
            for (op in ops.filterIsInstance<DiffOperation.CreateSequence>()) {
                put(op.objectRef.rootName, op.id)
            }
            for (op in ops.filterIsInstance<DiffOperation.RenameSequence>()) {
                put(op.toName, op.id)
            }
        }
        val createViewByName = ops.filterIsInstance<DiffOperation.CreateView>()
            .associateBy { it.objectRef.rootName }
        // Build reverse indices once so DropTable's edge computation is
        // O(referenced-from-N-tables) instead of O(allOps) per drop —
        // matters for large warehouse-tier schemas.
        val dropConstraintsByRefTable = ops.filterIsInstance<DiffOperation.DropConstraint>()
            .groupBy { it.constraint.references?.table }
            .filterKeys { it != null }
            .mapKeys { it.key!! }
        val dropViewsByRefTable = mutableMapOf<String, MutableList<DiffOperation.DropView>>()
        for (op in ops.filterIsInstance<DiffOperation.DropView>()) {
            for (tableName in op.view.dependencies?.tables.orEmpty()) {
                dropViewsByRefTable.getOrPut(tableName) { mutableListOf() } += op
            }
        }
        return ops.map { op ->
            val computed = computeDeps(
                op,
                tableSourceIdByName,
                sequenceSourceIdByName,
                createViewByName,
                dropConstraintsByRefTable,
                dropViewsByRefTable,
            )
            val deps = op.dependencies + computed
            op.withDependencies(deps)
        }
    }

    private fun computeDeps(
        op: DiffOperation,
        tableSourceIdByName: Map<String, String>,
        sequenceSourceIdByName: Map<String, String>,
        createViewByName: Map<String, DiffOperation.CreateView>,
        dropConstraintsByRefTable: Map<String, List<DiffOperation.DropConstraint>>,
        dropViewsByRefTable: Map<String, List<DiffOperation.DropView>>,
    ): Set<String> = when (op) {
        is DiffOperation.CreateTable -> dependenciesForCreateTable(op, tableSourceIdByName, sequenceSourceIdByName)
        is DiffOperation.AddColumn -> dependenciesForAddColumn(op, tableSourceIdByName, sequenceSourceIdByName)
        is DiffOperation.AlterColumnDefault -> dependenciesForAlterColumnDefault(op, sequenceSourceIdByName)
        is DiffOperation.AddConstraint -> dependenciesForAddConstraint(op, tableSourceIdByName)
        is DiffOperation.DropTable -> dependenciesForDropTable(op, dropConstraintsByRefTable, dropViewsByRefTable)
        is DiffOperation.CreateView -> dependenciesForCreateView(op, tableSourceIdByName, createViewByName)
        else -> emptySet()
    }

    private fun dependenciesForCreateTable(
        op: DiffOperation.CreateTable,
        tableSourceIdByName: Map<String, String>,
        sequenceSourceIdByName: Map<String, String>,
    ): Set<String> {
        val deps = mutableSetOf<String>()
        op.table.columns.values
            .mapNotNull { it.references?.table }
            .mapNotNull { tableSourceIdByName[it] }
            .filter { it != op.id }
            .forEach { deps += it }
        op.table.columns.values
            .mapNotNull { sequenceName(it.default) }
            .mapNotNull { sequenceSourceIdByName[it] }
            .filter { it != op.id }
            .forEach { deps += it }
        op.table.constraints
            .filter { it.type == ConstraintType.FOREIGN_KEY }
            .mapNotNull { it.references?.table }
            .mapNotNull { tableSourceIdByName[it] }
            .filter { it != op.id }
            .forEach { deps += it }
        return deps
    }

    private fun dependenciesForAddColumn(
        op: DiffOperation.AddColumn,
        tableSourceIdByName: Map<String, String>,
        sequenceSourceIdByName: Map<String, String>,
    ): Set<String> {
        val deps = mutableSetOf<String>()
        val ref = op.column.references
        val targetId = ref?.let { tableSourceIdByName[it.table] }
        if (targetId != null && targetId != op.id) deps += targetId
        sequenceName(op.column.default)
            ?.let(sequenceSourceIdByName::get)
            ?.takeIf { it != op.id }
            ?.let { deps += it }
        return deps
    }

    private fun dependenciesForAlterColumnDefault(
        op: DiffOperation.AlterColumnDefault,
        sequenceSourceIdByName: Map<String, String>,
    ): Set<String> =
        sequenceName(op.after)
            ?.let(sequenceSourceIdByName::get)
            ?.takeIf { it != op.id }
            ?.let { setOf(it) }
            ?: emptySet()

    private fun sequenceName(defaultValue: DefaultValue?): String? =
        (defaultValue as? DefaultValue.SequenceNextVal)?.sequenceName

    private fun dependenciesForAddConstraint(
        op: DiffOperation.AddConstraint,
        tableSourceIdByName: Map<String, String>,
    ): Set<String> {
        if (op.constraint.type != ConstraintType.FOREIGN_KEY) return emptySet()
        val ref = op.constraint.references ?: return emptySet()
        val targetId = tableSourceIdByName[ref.table] ?: return emptySet()
        if (targetId == op.id) return emptySet()
        return setOf(targetId)
    }

    private fun dependenciesForDropTable(
        op: DiffOperation.DropTable,
        dropConstraintsByRefTable: Map<String, List<DiffOperation.DropConstraint>>,
        dropViewsByRefTable: Map<String, List<DiffOperation.DropView>>,
    ): Set<String> {
        val deps = mutableSetOf<String>()
        val targetTable = op.objectRef.rootName
        dropConstraintsByRefTable[targetTable]?.forEach { deps += it.id }
        dropViewsByRefTable[targetTable]?.forEach { deps += it.id }
        return deps
    }

    /**
     * A `CreateView` depends on:
     *
     * - every `CreateTable` listed in `view.dependencies.tables` —
     *   classic table-before-view ordering.
     * - every other `CreateView` listed in `view.dependencies.views`
     *   — chained views (`CREATE VIEW A AS SELECT * FROM B`) must
     *   create B before A. Phase G.3 makes this matter at plan time:
     *   when both `ReplaceView` ops split into `DropView` + `CreateView`
     *   around a shared column-altering op, the two `createA` /
     *   `createB` ops need an edge or topological sort can place them
     *   in arbitrary order, leading to a render-time "relation does
     *   not exist" failure.
     */
    private fun dependenciesForCreateView(
        op: DiffOperation.CreateView,
        tableSourceIdByName: Map<String, String>,
        createViewByName: Map<String, DiffOperation.CreateView>,
    ): Set<String> {
        val deps = mutableSetOf<String>()
        op.view.dependencies?.tables?.forEach { tableName ->
            tableSourceIdByName[tableName]?.let { if (it != op.id) deps += it }
        }
        op.view.dependencies?.views?.forEach { viewName ->
            createViewByName[viewName]?.let { if (it.id != op.id) deps += it.id }
        }
        return deps
    }
}
