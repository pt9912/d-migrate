package dev.dmigrate.core.diff.migration

/**
 * Sorts a list of [DiffOperation]s by their declared
 * [DiffOperation.dependencies], using [DiffPhase] as the
 * deterministic tie-breaker per
 * `docs/planning/open/diffresult-migration-plan.md §4.4`.
 *
 * Cross-plan IDs in `dependencies` (i.e. references to operations
 * not present in this list) are silently dropped. A future slice
 * may surface them as a `BLOCKER` diagnostic; currently they're
 * an internal-bug indicator.
 *
 * If a dependency cycle remains after the topological pass, the
 * surviving operations are appended in deterministic phase + id
 * order so the caller still gets a complete (if not strictly
 * sorted) result.
 */
internal object TopologicalSorter {

    fun sort(ops: List<DiffOperation>): List<DiffOperation> {
        val byId = ops.associateBy { it.id }
        val deps = ops.associate { op ->
            op.id to op.dependencies.filter { it in byId }.toSet()
        }
        val ready = ArrayDeque<DiffOperation>()
        val remaining = ops.toMutableList()
        val resolvedIds = mutableSetOf<String>()
        val result = mutableListOf<DiffOperation>()

        for (op in remaining) {
            if (deps.getValue(op.id).isEmpty()) ready += op
        }
        sortInPlace(ready)

        while (ready.isNotEmpty()) {
            val next = ready.removeFirst()
            result += next
            resolvedIds += next.id
            remaining.remove(next)
            val newlyReady = remaining.filter { op ->
                op !in result && deps.getValue(op.id).all { it in resolvedIds }
            }
            for (op in newlyReady) if (op !in ready) ready += op
            sortInPlace(ready)
        }

        if (remaining.isNotEmpty()) {
            result += remaining.sortedWith(phaseAndIdOrder)
        }
        return result
    }

    private val phaseAndIdOrder: Comparator<DiffOperation> =
        compareBy<DiffOperation> { it.phase.ordinal }.thenBy { it.id }

    private fun sortInPlace(deque: ArrayDeque<DiffOperation>) {
        val sorted = deque.sortedWith(phaseAndIdOrder)
        deque.clear()
        deque.addAll(sorted)
    }
}
