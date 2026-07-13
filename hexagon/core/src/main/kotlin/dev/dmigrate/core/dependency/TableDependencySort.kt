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
 * Result of [sortTablesIntoLayers]: tables grouped into dependency
 * layers plus any edges that form cycles.
 *
 * - [layers] `[i]` contains every table whose FK targets are all in
 *   layers `< i`. Layer `0` holds the roots (no unsatisfied FK). Tables
 *   inside one layer have no FK edge between them → they may be
 *   processed concurrently; a barrier separates layer `i` from `i+1`.
 * - [circularEdges] is empty iff the graph is acyclic. On a cycle the
 *   cyclic tables are NOT placed in any layer (they cannot be ordered).
 */
data class TableLayerResult(
    val layers: List<List<String>>,
    val circularEdges: List<FkEdge>,
) {
    /**
     * Flattened layer order — a valid linear topo order for an acyclic graph.
     * NOTE: on a cycle the cyclic tables are omitted (they are not in any layer),
     * unlike [sortTablesByDependency] which appends them; check [circularEdges].
     */
    val flattened: List<String> get() = layers.flatten()
}

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
    val graph = buildDependencyGraph(tables, edges)
    val deps = graph.deps

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
        val circularEdges = graph.relevantEdges.filter { it.fromTable in remaining && it.toTable in remaining }
        TableSortResult(sorted, circularEdges)
    }
}

/** Filtered FK graph shared by [sortTablesByDependency] and [sortTablesIntoLayers]. */
private class DependencyGraph(
    val relevantEdges: List<FkEdge>,
    /** table → set of tables it depends on (each caller mutates its own copy). */
    val deps: LinkedHashMap<String, MutableSet<String>>,
)

/**
 * Builds the dependency graph both sorts share: relevant edges (within [tables],
 * no self-refs) and the `table → dependencies` adjacency map with every table
 * pre-seeded to an empty set.
 */
private fun buildDependencyGraph(tables: Set<String>, edges: List<FkEdge>): DependencyGraph {
    val relevant = edges.filter { it.fromTable in tables && it.toTable in tables && it.fromTable != it.toTable }
    val deps = linkedMapOf<String, MutableSet<String>>()
    for (t in tables) deps[t] = linkedSetOf()
    for (edge in relevant) deps.getValue(edge.fromTable).add(edge.toTable)
    return DependencyGraph(relevant, deps)
}

/**
 * FK dependency sort that groups tables into concurrency layers (Kahn's
 * algorithm by level).
 *
 * Same edge/self-ref/out-of-set filtering as [sortTablesByDependency],
 * but instead of one linear order it returns [TableLayerResult.layers]:
 * each layer is a set of tables with no FK edge among them, and every
 * FK target of a layer-`i` table sits in a layer `< i`. Consumers run
 * one layer concurrently, then a barrier, then the next layer — the
 * FK-safe basis for parallel table processing (LN-007).
 *
 * On a cycle the cyclic tables are left out of [TableLayerResult.layers]
 * and reported in [TableLayerResult.circularEdges]; callers surface that
 * as an error exactly like the linear sort does.
 */
fun sortTablesIntoLayers(
    tables: Set<String>,
    edges: List<FkEdge>,
): TableLayerResult {
    val graph = buildDependencyGraph(tables, edges)
    val deps = graph.deps

    val layers = mutableListOf<List<String>>()
    val placed = linkedSetOf<String>()

    while (placed.size < tables.size) {
        // Every not-yet-placed table whose remaining deps are all placed.
        val layer = deps.entries
            .filter { it.key !in placed && it.value.all { dep -> dep in placed } }
            .map { it.key }
        if (layer.isEmpty()) break // cycle: no table can be satisfied
        layers += layer
        placed.addAll(layer)
    }

    val remaining = tables - placed
    val circularEdges = if (remaining.isEmpty()) {
        emptyList()
    } else {
        graph.relevantEdges.filter { it.fromTable in remaining && it.toTable in remaining }
    }
    return TableLayerResult(layers, circularEdges)
}
