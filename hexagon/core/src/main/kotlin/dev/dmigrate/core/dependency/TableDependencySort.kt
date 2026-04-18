package dev.dmigrate.core.dependency

/**
 * A directed FK edge between two tables. [fromColumn] and [toColumn]
 * are optional — call-sites that only need table-level granularity
 * can leave them null.
 */
data class FkEdge(
    val fromTable: String,
    val fromColumn: String? = null,
    val toTable: String,
    val toColumn: String? = null,
)

/**
 * Result of [sortTablesByDependency]: a topologically sorted table
 * list plus any edges that form cycles.
 */
data class TableSortResult(
    val sorted: List<String>,
    val circularEdges: List<FkEdge>,
)

/**
 * Topological sort of tables by FK dependencies using Kahn's algorithm.
 *
 * - Self-references (fromTable == toTable) are ignored.
 * - References to tables not in [tables] are ignored.
 * - On cycles: all non-cyclic tables are sorted first, then cyclic
 *   tables are appended. [TableSortResult.circularEdges] contains the
 *   edges that form the cycle(s).
 *
 * Call-sites build their own [FkEdge] lists from column.references,
 * constraints, or both — this function only handles the graph.
 */
fun sortTablesByDependency(
    tables: Set<String>,
    edges: List<FkEdge>,
): TableSortResult {
    // Filter to relevant edges: within table set, no self-refs
    val relevant = edges.filter { it.fromTable in tables && it.toTable in tables && it.fromTable != it.toTable }

    // Build adjacency: table → set of tables it depends on
    val deps = linkedMapOf<String, MutableSet<String>>()
    for (t in tables) deps[t] = linkedSetOf()
    for (edge in relevant) {
        deps.getOrPut(edge.fromTable) { linkedSetOf() }.add(edge.toTable)
    }

    // Kahn's algorithm
    val inDegree = linkedMapOf<String, Int>()
    for (t in tables) inDegree[t] = deps[t]?.size ?: 0

    val queue = ArrayDeque(inDegree.filterValues { it == 0 }.keys.toList())
    val sorted = mutableListOf<String>()

    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        sorted += current
        for ((table, depSet) in deps) {
            if (depSet.remove(current)) {
                val remaining = (inDegree.getValue(table) - 1).also { inDegree[table] = it }
                if (remaining == 0) queue.addLast(table)
            }
        }
    }

    val remaining = tables - sorted.toSet()
    return if (remaining.isEmpty()) {
        TableSortResult(sorted, emptyList())
    } else {
        sorted.addAll(remaining)
        val circularEdges = relevant.filter { it.fromTable in remaining && it.toTable in remaining }
        TableSortResult(sorted, circularEdges)
    }
}
