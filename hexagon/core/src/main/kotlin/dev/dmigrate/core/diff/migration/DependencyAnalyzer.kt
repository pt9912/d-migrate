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
 *   referenced table's `CreateTable`.
 * - `AddColumn` with FK reference → depends on the referenced
 *   table's `CreateTable`.
 * - `AddConstraint` (FOREIGN_KEY) → depends on the referenced
 *   table's `CreateTable`.
 * - `DropTable` → depends on the `DropConstraint` of every FK
 *   pointing at the dropped table (drop dependents first).
 * - `CreateTable` / `AddColumn` / `AlterColumnDefault` with
 *   `SequenceNextVal` → depends on the referenced `CreateSequence`.
 * - `CreateView` → depends on the `CreateTable`s declared in
 *   `view.dependencies.tables` **and** the `CreateView`s declared in
 *   `view.dependencies.views` (chained views need create-before-create
 *   ordering; matters especially when Phase G.3 splits a `ReplaceView`
 *   into `DropView` + `CreateView` and the chained sibling does the
 *   same around a shared column-altering op).
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
        val createTableByName = ops.filterIsInstance<DiffOperation.CreateTable>()
            .associateBy { it.objectRef.rootName }
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
                createTableByName,
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
        createTableByName: Map<String, DiffOperation.CreateTable>,
        createSequenceByName: Map<String, DiffOperation.CreateSequence>,
        createViewByName: Map<String, DiffOperation.CreateView>,
        dropConstraintsByRefTable: Map<String, List<DiffOperation.DropConstraint>>,
        dropViewsByRefTable: Map<String, List<DiffOperation.DropView>>,
    ): Set<String> = when (op) {
        is DiffOperation.CreateTable -> dependenciesForCreateTable(op, createTableByName, createSequenceByName)
        is DiffOperation.AddColumn -> dependenciesForAddColumn(op, createTableByName, createSequenceByName)
        is DiffOperation.AlterColumnDefault -> dependenciesForAlterColumnDefault(op, createSequenceByName)
        is DiffOperation.AddConstraint -> dependenciesForAddConstraint(op, createTableByName)
        is DiffOperation.DropTable -> dependenciesForDropTable(op, dropConstraintsByRefTable, dropViewsByRefTable)
        is DiffOperation.CreateView -> dependenciesForCreateView(op, createTableByName, createViewByName)
        else -> emptySet()
    }

    private fun dependenciesForCreateTable(
        op: DiffOperation.CreateTable,
        createTableByName: Map<String, DiffOperation.CreateTable>,
        createSequenceByName: Map<String, DiffOperation.CreateSequence>,
    ): Set<String> {
        val deps = mutableSetOf<String>()
        op.table.columns.values
            .mapNotNull { it.references?.table }
            .mapNotNull { createTableByName[it] }
            .filter { it.id != op.id }
            .forEach { deps += it.id }
        op.table.columns.values
            .mapNotNull { sequenceName(it.default) }
            .mapNotNull { createSequenceByName[it] }
            .filter { it.id != op.id }
            .forEach { deps += it.id }
        op.table.constraints
            .filter { it.type == ConstraintType.FOREIGN_KEY }
            .mapNotNull { it.references?.table }
            .mapNotNull { createTableByName[it] }
            .filter { it.id != op.id }
            .forEach { deps += it.id }
        return deps
    }

    private fun dependenciesForAddColumn(
        op: DiffOperation.AddColumn,
        createTableByName: Map<String, DiffOperation.CreateTable>,
        createSequenceByName: Map<String, DiffOperation.CreateSequence>,
    ): Set<String> {
        val deps = mutableSetOf<String>()
        val ref = op.column.references
        val target = ref?.let { createTableByName[it.table] }
        if (target != null && target.id != op.id) deps += target.id
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
        createTableByName: Map<String, DiffOperation.CreateTable>,
    ): Set<String> {
        if (op.constraint.type != ConstraintType.FOREIGN_KEY) return emptySet()
        val ref = op.constraint.references ?: return emptySet()
        val target = createTableByName[ref.table] ?: return emptySet()
        if (target.id == op.id) return emptySet()
        return setOf(target.id)
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
        createTableByName: Map<String, DiffOperation.CreateTable>,
        createViewByName: Map<String, DiffOperation.CreateView>,
    ): Set<String> {
        val deps = mutableSetOf<String>()
        op.view.dependencies?.tables?.forEach { tableName ->
            createTableByName[tableName]?.let { if (it.id != op.id) deps += it.id }
        }
        op.view.dependencies?.views?.forEach { viewName ->
            createViewByName[viewName]?.let { if (it.id != op.id) deps += it.id }
        }
        return deps
    }
}
