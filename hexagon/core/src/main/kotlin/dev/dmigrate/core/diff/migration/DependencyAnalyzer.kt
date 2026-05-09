package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.ConstraintType

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
 * - `CreateView` → depends on the `CreateTable`s declared in
 *   `view.dependencies.tables`.
 *
 * Out of scope for this slice:
 *
 * - Drop-side `DropView` / `DropFunction` ordering (the inverse-sort
 *   in Phase D handles it via the planner's reverse traversal).
 * - Materialized view refresh ordering.
 * - Trigger → table / function / view ordering (declared via
 *   `trigger.dependencies` later).
 */
internal object DependencyAnalyzer {

    fun attach(ops: List<DiffOperation>): List<DiffOperation> {
        val createTableByName = ops.filterIsInstance<DiffOperation.CreateTable>()
            .associateBy { it.objectRef.rootName }
        return ops.map { op ->
            val deps = op.dependencies + computeDeps(op, createTableByName, ops)
            op.withDependencies(deps)
        }
    }

    private fun computeDeps(
        op: DiffOperation,
        createTableByName: Map<String, DiffOperation.CreateTable>,
        ops: List<DiffOperation>,
    ): Set<String> = when (op) {
        is DiffOperation.CreateTable -> dependenciesForCreateTable(op, createTableByName)
        is DiffOperation.AddColumn -> dependenciesForAddColumn(op, createTableByName)
        is DiffOperation.AddConstraint -> dependenciesForAddConstraint(op, createTableByName)
        is DiffOperation.DropTable -> dependenciesForDropTable(op, ops)
        is DiffOperation.CreateView -> dependenciesForCreateView(op, createTableByName)
        else -> emptySet()
    }

    private fun dependenciesForCreateTable(
        op: DiffOperation.CreateTable,
        createTableByName: Map<String, DiffOperation.CreateTable>,
    ): Set<String> {
        val deps = mutableSetOf<String>()
        op.table.columns.values
            .mapNotNull { it.references?.table }
            .mapNotNull { createTableByName[it] }
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
    ): Set<String> {
        val ref = op.column.references ?: return emptySet()
        val target = createTableByName[ref.table] ?: return emptySet()
        if (target.id == op.id) return emptySet()
        return setOf(target.id)
    }

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
        ops: List<DiffOperation>,
    ): Set<String> {
        val deps = mutableSetOf<String>()
        val targetTable = op.objectRef.rootName
        for (other in ops) {
            if (other === op) continue
            when (other) {
                is DiffOperation.DropConstraint -> {
                    if (other.constraint.references?.table == targetTable) deps += other.id
                }
                is DiffOperation.DropView -> {
                    if (other.view.dependencies?.tables?.contains(targetTable) == true) deps += other.id
                }
                else -> Unit
            }
        }
        return deps
    }

    private fun dependenciesForCreateView(
        op: DiffOperation.CreateView,
        createTableByName: Map<String, DiffOperation.CreateTable>,
    ): Set<String> {
        val deps = mutableSetOf<String>()
        op.view.dependencies?.tables?.forEach { tableName ->
            createTableByName[tableName]?.let { if (it.id != op.id) deps += it.id }
        }
        return deps
    }
}
