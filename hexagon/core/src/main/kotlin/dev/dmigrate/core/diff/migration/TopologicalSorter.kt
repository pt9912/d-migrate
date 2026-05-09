package dev.dmigrate.core.diff.migration

/**
 * Sorts a list of [DiffOperation]s by their declared
 * [DiffOperation.dependencies], using a deterministic tie-breaker
 * (phase → object type → object name → id) per
 * `docs/planning/open/diffresult-migration-plan.md §4.4`. The
 * `objectRef`-based ordering keeps the SQL output of unrelated
 * operations stable across schema revisions, which makes cross-
 * version diffs of generated migrations reviewable.
 *
 * Cross-plan IDs in `dependencies` (i.e. references to operations
 * not present in this list) are silently dropped. A future slice
 * may surface them as a `BLOCKER` diagnostic; currently they're
 * an internal-bug indicator.
 *
 * Cycle detection: if a residual set survives the topological pass,
 * those operations are reported as `cycleIds` so the caller can
 * surface a `DEPENDENCY_CYCLE` blocker. The residual ops are also
 * appended to `sorted` in deterministic order so the caller still
 * gets a complete (if not strictly sorted) result for diagnostic
 * rendering.
 */
internal object TopologicalSorter {

    data class Result(
        val sorted: List<DiffOperation>,
        val cycleIds: Set<String>,
    )

    fun sort(ops: List<DiffOperation>): Result {
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

        val cycleIds = remaining.map { it.id }.toSet()
        if (remaining.isNotEmpty()) {
            result += remaining.sortedWith(stableOrder)
        }
        return Result(sorted = result, cycleIds = cycleIds)
    }

    private val stableOrder: Comparator<DiffOperation> =
        compareBy<DiffOperation> { it.phase.ordinal }
            .thenBy { it.objectRef.type.ordinal }
            .thenBy { it.objectRef.displayName }
            .thenBy { it.id }

    private fun sortInPlace(deque: ArrayDeque<DiffOperation>) {
        val sorted = deque.sortedWith(stableOrder)
        deque.clear()
        deque.addAll(sorted)
    }
}
