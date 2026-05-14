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
 * Edge rules implemented in the first slice:
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
 *   `SequenceNextVal` → depends on the referenced `CreateSequence`.
 * - `CreateView` → depends on the `CreateTable` / `RenameTable` for
 *   every table listed in `view.dependencies.tables` **and** the
 *   `CreateView`s listed in `view.dependencies.views` (chained views
 *   need create-before-create ordering; matters especially when Phase
 *   G.3 splits a `ReplaceView` into `DropView` + `CreateView` and the
 *   chained sibling does the same around a shared column-altering op).
 *
 * Out of scope for this slice (carved out for Phase D — see Plan
 * §6.1 and the integration-test plan §6.4):
 *
 * - Drop-side `DropView` / `DropFunction` ordering (the inverse-sort
 *   in Phase D handles it via the planner's reverse traversal).
 * - `Replace*` body dependencies — `ReplaceFunction`, `ReplaceProcedure`,
 *   `ReplaceTrigger`, `ReplaceView` could in principle reference
 *   newly-created tables/views; today's renderer relies on dialect-
 *   level forward-reference tolerance (`CREATE OR REPLACE …`).
 * - Materialized view refresh ordering.
 * - Trigger → table / function / view ordering (declared via
 *   `trigger.dependencies` later).
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
        val createSequenceByName = ops.filterIsInstance<DiffOperation.CreateSequence>()
            .associateBy { it.objectRef.rootName }
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
                createSequenceByName,
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
        createSequenceByName: Map<String, DiffOperation.CreateSequence>,
        createViewByName: Map<String, DiffOperation.CreateView>,
        dropConstraintsByRefTable: Map<String, List<DiffOperation.DropConstraint>>,
        dropViewsByRefTable: Map<String, List<DiffOperation.DropView>>,
    ): Set<String> = when (op) {
        is DiffOperation.CreateTable -> dependenciesForCreateTable(op, tableSourceIdByName, createSequenceByName)
        is DiffOperation.AddColumn -> dependenciesForAddColumn(op, tableSourceIdByName, createSequenceByName)
        is DiffOperation.AlterColumnDefault -> dependenciesForAlterColumnDefault(op, createSequenceByName)
        is DiffOperation.AddConstraint -> dependenciesForAddConstraint(op, tableSourceIdByName)
        is DiffOperation.DropTable -> dependenciesForDropTable(op, dropConstraintsByRefTable, dropViewsByRefTable)
        is DiffOperation.CreateView -> dependenciesForCreateView(op, tableSourceIdByName, createViewByName)
        else -> emptySet()
    }

    private fun dependenciesForCreateTable(
        op: DiffOperation.CreateTable,
        tableSourceIdByName: Map<String, String>,
        createSequenceByName: Map<String, DiffOperation.CreateSequence>,
    ): Set<String> {
        val deps = mutableSetOf<String>()
        op.table.columns.values
            .mapNotNull { it.references?.table }
            .mapNotNull { tableSourceIdByName[it] }
            .filter { it != op.id }
            .forEach { deps += it }
        op.table.columns.values
            .mapNotNull { sequenceName(it.default) }
            .mapNotNull { createSequenceByName[it] }
            .filter { it.id != op.id }
            .forEach { deps += it.id }
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
        createSequenceByName: Map<String, DiffOperation.CreateSequence>,
    ): Set<String> {
        val deps = mutableSetOf<String>()
        val ref = op.column.references
        val targetId = ref?.let { tableSourceIdByName[it.table] }
        if (targetId != null && targetId != op.id) deps += targetId
        sequenceName(op.column.default)
            ?.let(createSequenceByName::get)
            ?.takeIf { it.id != op.id }
            ?.let { deps += it.id }
        return deps
    }

    private fun dependenciesForAlterColumnDefault(
        op: DiffOperation.AlterColumnDefault,
        createSequenceByName: Map<String, DiffOperation.CreateSequence>,
    ): Set<String> =
        sequenceName(op.after)
            ?.let(createSequenceByName::get)
            ?.takeIf { it.id != op.id }
            ?.let { setOf(it.id) }
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
