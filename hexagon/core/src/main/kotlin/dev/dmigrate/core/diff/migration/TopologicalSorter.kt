package dev.dmigrate.core.diff.migration

import java.util.PriorityQueue

/**
 * Sorts a list of [DiffOperation]s by their declared
 * [DiffOperation.dependencies], using a deterministic tie-breaker
 * (phase → object type → object name → id) per
 * `docs/planning/done-archive/diffresult-migration-plan.md §4.4`. The
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

    /**
     * Kahn-Topologie-Sortierung mit einer nach [stableOrder] geordneten Bereit-
     * Schlange (PriorityQueue). Das liefert **exakt** dieselbe Auswahlreihenfolge wie
     * „die aktuell bereite Menge jeden Schritt neu sortieren und das erste Element nehmen"
     * (stabile Total-Ordnung über die eindeutige `id`), aber in **O((V+E)·log V)** statt der
     * früheren **O(n³)** (pro Schritt voller `remaining`-Scan mit `List`-`in`-Lookup + voller
     * Re-Sort der Bereit-Menge) — relevant für große gemischte Schemas mit vielen Views/Triggern.
     */
    fun sort(ops: List<DiffOperation>): Result {
        val byId = ops.associateBy { it.id }
        // In-Grad je Op = Anzahl der **vorhandenen** Abhängigkeiten (Cross-Plan-IDs fallen
        // weg); plus die Rückwärtskanten (je Dep-ID die Ops, die darauf warten). Self-Deps
        // bleiben mitgezählt → die Op wird nie bereit → Zyklus (Verhalten wie zuvor).
        val remainingDeps = HashMap<String, Int>(ops.size * 2)
        val dependents = HashMap<String, MutableList<DiffOperation>>(ops.size * 2)
        for (op in ops) {
            val present = op.dependencies.filter { it in byId }
            remainingDeps[op.id] = present.size
            for (depId in present) dependents.getOrPut(depId) { mutableListOf() } += op
        }

        val ready = PriorityQueue(stableOrder)
        for (op in ops) if (remainingDeps.getValue(op.id) == 0) ready += op

        val result = ArrayList<DiffOperation>(ops.size)
        val resolvedIds = HashSet<String>(ops.size * 2)
        while (ready.isNotEmpty()) {
            val next = ready.poll()
            result += next
            resolvedIds += next.id
            for (dependent in dependents[next.id].orEmpty()) {
                val left = remainingDeps.getValue(dependent.id) - 1
                remainingDeps[dependent.id] = left
                if (left == 0) ready += dependent
            }
        }

        // Residuum = Ops, die nie In-Grad 0 erreichten = Zyklus-Mitglieder. Deterministisch
        // anhängen (vollständiges Ergebnis fürs Diagnose-Rendering); IDs als cycleIds melden.
        val cycle = ops.filter { it.id !in resolvedIds }
        if (cycle.isNotEmpty()) result += cycle.sortedWith(stableOrder)
        return Result(sorted = result, cycleIds = cycle.mapTo(mutableSetOf()) { it.id })
    }

    private val stableOrder: Comparator<DiffOperation> =
        compareBy<DiffOperation> { it.phase.ordinal }
            .thenBy { it.objectRef.type.ordinal }
            .thenBy { it.objectRef.displayName }
            .thenBy { it.id }
}
