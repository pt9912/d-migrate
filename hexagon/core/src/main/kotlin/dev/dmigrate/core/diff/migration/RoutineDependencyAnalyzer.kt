package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.DependencyInfo

/**
 * E.1 Routine-Migration Slice D.1: second-phase dependency analyzer
 * for routine / view / trigger / sequence edges. Runs after the
 * existing [DependencyAnalyzer] (which covers table FK / sequence-
 * default / view-table edges) and adds the remaining cross-object
 * edges that the plan needs to topologically sort all five object
 * classes.
 *
 * Edge model (file-to-file, manifest-driven):
 *
 * - **Create / Replace side** — every routine, view, and trigger
 *   that declares a `dependencies.tables` / `views` / `functions` /
 *   `sequences` link in its `DependencyInfo` adds a Create-time
 *   edge to the matching `Create*` op in the plan (table edges
 *   also accept `RenameTable.toName`).
 *
 * - **Drop side** — every `Drop*` whose definition still listed a
 *   dependency on another object adds a reverse-topology edge to
 *   the matching `Drop*` op: the dependent object must drop first
 *   so the depended-on object can be dropped after.
 *
 * - **Unsafe pairs** — when two routines co-exist in the same plan
 *   and neither's `DependencyInfo` declares an edge in either
 *   direction, the analyzer flags the pair as
 *   `UNSAFE_DEPENDENCY_PAIR`. This is the conservative file-to-file
 *   fallback for Plan §3's "potentially-dependent pair without a
 *   safe edge" rule. Engine-metadata-backed slices (D.2 / D.3) can
 *   refine the answer.
 *
 * Class rules treat the following pairs as *deterministically
 * independent* and never raise `UNSAFE_DEPENDENCY_PAIR`:
 *
 * - Two `Create*Sequence` ops (sequences never reference each
 *   other).
 * - Two `Create*Table` ops without FK between them (already
 *   handled by [DependencyAnalyzer]'s FK pass).
 * - Sequence ↔ Routine (sequences are leaves; routines may
 *   reference them but not vice versa).
 *
 * The analyzer never edits an operation's existing dependencies; it
 * only adds to them via [DiffOperation.withDependencies].
 */
internal object RoutineDependencyAnalyzer {

    data class Result(
        val operations: List<DiffOperation>,
        val unsafePairs: List<UnsafePair>,
    )

    /** A potentially-dependent routine pair with no manifest edge. */
    data class UnsafePair(
        val first: DiffObjectRef,
        val second: DiffObjectRef,
    )

    fun attach(ops: List<DiffOperation>): Result {
        val indexes = buildIndexes(ops)
        val withEdges = ops.map { op ->
            val extra = computeEdges(op, indexes)
            if (extra.isEmpty()) op else op.withDependencies(op.dependencies + extra)
        }
        val unsafePairs = detectUnsafeRoutinePairs(withEdges)
        return Result(operations = withEdges, unsafePairs = unsafePairs)
    }

    // ── Indexes ───────────────────────────────────────────────────

    private data class Indexes(
        val createTableId: Map<String, String>,
        val createViewId: Map<String, String>,
        val createFunctionId: Map<String, String>,
        val createProcedureId: Map<String, String>,
        val createSequenceId: Map<String, String>,
        // Drop-side: name → drop op id. Plus the per-name list of OTHER
        // Drop ops that still depend on this name (for reverse-topology
        // edges).
        val dropTableDependentsByTable: Map<String, List<String>>,
        val dropViewDependentsByTable: Map<String, List<String>>,
        val dropFunctionDependentsByFunction: Map<String, List<String>>,
        val dropProcedureDependentsByProcedure: Map<String, List<String>>,
        val dropSequenceDependentsBySequence: Map<String, List<String>>,
    )

    private fun buildIndexes(ops: List<DiffOperation>): Indexes {
        val createTable = mutableMapOf<String, String>()
        for (op in ops.filterIsInstance<DiffOperation.CreateTable>()) createTable[op.objectRef.rootName] = op.id
        for (op in ops.filterIsInstance<DiffOperation.RenameTable>()) createTable[op.toName] = op.id

        // Plan-2 §8 D.3b Sub-Slice C: materialized views share the view
        // name-space — a view depending on `mv_orders` resolves the
        // same way whether the target is a regular `CreateView` or
        // `CreateMaterializedView`. Including both in `createViewId`
        // lets `viewCreateEdges` / `routineCreateEdges` reuse the
        // existing lookup unchanged. Replace ops also legitimately
        // leave the name in place at the desired state, so depending
        // ops (e.g. a `CreateMaterializedView` referencing a
        // `ReplaceFunction fn_x`) must wait for them too — otherwise
        // the topological sort falls back to phase-tie-breaker order,
        // which is correct today but fragile.
        val createView = (
            ops.filterIsInstance<DiffOperation.CreateView>().associate { it.objectRef.rootName to it.id } +
                ops.filterIsInstance<DiffOperation.ReplaceView>().associate { it.objectRef.rootName to it.id } +
                ops.filterIsInstance<DiffOperation.CreateMaterializedView>()
                    .associate { it.objectRef.rootName to it.id } +
                ops.filterIsInstance<DiffOperation.ReplaceMaterializedView>()
                    .associate { it.objectRef.rootName to it.id }
            )
        val createFunction = (
            ops.filterIsInstance<DiffOperation.CreateFunction>().associate { it.objectRef.rootName to it.id } +
                ops.filterIsInstance<DiffOperation.ReplaceFunction>()
                    .associate { it.objectRef.rootName to it.id }
            )
        val createProcedure = (
            ops.filterIsInstance<DiffOperation.CreateProcedure>().associate { it.objectRef.rootName to it.id } +
                ops.filterIsInstance<DiffOperation.ReplaceProcedure>()
                    .associate { it.objectRef.rootName to it.id }
            )
        val createSequence = ops.filterIsInstance<DiffOperation.CreateSequence>()
            .associate { it.objectRef.rootName to it.id }

        // Build reverse indexes for drop-side edges. For each name we
        // care about (table / view / function / procedure / sequence),
        // collect the Drop op ids that still listed that name in their
        // `DependencyInfo` — those must run BEFORE the matching Drop
        // (which itself targets the depended-on object).
        val dropTableDeps = mutableMapOf<String, MutableList<String>>()
        val dropViewDeps = mutableMapOf<String, MutableList<String>>()
        val dropFunctionDeps = mutableMapOf<String, MutableList<String>>()
        val dropProcedureDeps = mutableMapOf<String, MutableList<String>>()
        val dropSequenceDeps = mutableMapOf<String, MutableList<String>>()

        for (op in ops) {
            val deps = dropOperandDependencies(op) ?: continue
            // The op `op` is a Drop with `deps` pointing at the objects
            // its dropped form once referenced. The dependent name's
            // matching Drop should wait for THIS op to complete.
            for (table in deps.tables) dropTableDeps.getOrPut(table) { mutableListOf() } += op.id
            for (view in deps.views) dropViewDeps.getOrPut(view) { mutableListOf() } += op.id
            for (fn in deps.functions) dropFunctionDeps.getOrPut(fn) { mutableListOf() } += op.id
            for (seq in deps.sequences) dropSequenceDeps.getOrPut(seq) { mutableListOf() } += op.id
            // No separate "procedure dependents" yet — `DependencyInfo`
            // models routines via the shared `functions` list today.
            // Procedures use the same list; the analyzer treats both
            // identically.
            for (fn in deps.functions) dropProcedureDeps.getOrPut(fn) { mutableListOf() } += op.id
        }

        return Indexes(
            createTableId = createTable,
            createViewId = createView,
            createFunctionId = createFunction,
            createProcedureId = createProcedure,
            createSequenceId = createSequence,
            dropTableDependentsByTable = dropTableDeps,
            dropViewDependentsByTable = dropViewDeps,
            dropFunctionDependentsByFunction = dropFunctionDeps,
            dropProcedureDependentsByProcedure = dropProcedureDeps,
            dropSequenceDependentsBySequence = dropSequenceDeps,
        )
    }

    private fun dropOperandDependencies(op: DiffOperation): DependencyInfo? = when (op) {
        is DiffOperation.DropFunction -> op.function.dependencies
        is DiffOperation.DropProcedure -> op.procedure.dependencies
        is DiffOperation.DropTrigger -> op.trigger.dependencies
        is DiffOperation.DropView -> op.view.dependencies
        // Plan-2 §8 D.3b Sub-Slice C: an MV drop carries the same kind of
        // outgoing dependencies as a view drop (its body referenced
        // tables/views/routines). The reverse-topology edges flip
        // `DropTable users` to wait for `DropMaterializedView mv_orders`
        // when the MV's `dependencies.tables` listed `users`.
        is DiffOperation.DropMaterializedView -> op.view.dependencies
        else -> null
    }

    // ── Per-op edge computation ───────────────────────────────────

    private fun computeEdges(op: DiffOperation, idx: Indexes): Set<String> = when (op) {
        is DiffOperation.CreateView -> viewCreateEdges(op.view.dependencies, op.id, idx)
        // Plan-2 §8 D.3b Sub-Slice C review fix: `Replace*` carries both
        // a create-side edge set (the post-replace state needs its
        // referenced objects in place) AND the reverse-topology edges
        // that the matching `Drop*` would have (a co-resident `Drop*`
        // that depended on this name must run BEFORE the replace
        // happens, so it observes the pre-replace state). This makes
        // the topology symmetric with `Create*` (forward edges) +
        // `Drop*` (reverse edges) and removes the phase-tie-breaker
        // dependency for the `DropMV → ReplaceFunction` case.
        is DiffOperation.ReplaceView -> viewCreateEdges(op.after.dependencies, op.id, idx) +
            idx.dropViewDependentsByTable[op.objectRef.rootName].orEmpty().toSet()
        // Plan-2 §8 D.3b Sub-Slice C: MV Create/Replace edges mirror the
        // regular View case — the new MV needs every referenced
        // table/view/routine/sequence created first.
        is DiffOperation.CreateMaterializedView -> viewCreateEdges(op.view.dependencies, op.id, idx)
        is DiffOperation.ReplaceMaterializedView -> viewCreateEdges(op.after.dependencies, op.id, idx) +
            idx.dropViewDependentsByTable[op.objectRef.rootName].orEmpty().toSet()
        is DiffOperation.CreateFunction -> routineCreateEdges(op.function.dependencies, op.id, idx)
        is DiffOperation.ReplaceFunction -> routineCreateEdges(op.after.dependencies, op.id, idx) +
            idx.dropFunctionDependentsByFunction[op.objectRef.rootName].orEmpty().toSet()
        is DiffOperation.CreateProcedure -> routineCreateEdges(op.procedure.dependencies, op.id, idx)
        is DiffOperation.ReplaceProcedure -> routineCreateEdges(op.after.dependencies, op.id, idx) +
            idx.dropProcedureDependentsByProcedure[op.objectRef.rootName].orEmpty().toSet()
        is DiffOperation.CreateTrigger -> triggerCreateEdges(op.trigger, op.id, idx)
        is DiffOperation.ReplaceTrigger -> triggerCreateEdges(op.after, op.id, idx)
        is DiffOperation.DropFunction -> idx.dropFunctionDependentsByFunction[op.objectRef.rootName].orEmpty().toSet()
        is DiffOperation.DropProcedure ->
            idx.dropProcedureDependentsByProcedure[op.objectRef.rootName].orEmpty().toSet()
        is DiffOperation.DropTable -> idx.dropTableDependentsByTable[op.objectRef.rootName].orEmpty().toSet()
        is DiffOperation.DropView -> idx.dropViewDependentsByTable[op.objectRef.rootName].orEmpty().toSet()
        // DropMV reads the same dropViewDependentsByTable index because
        // the view name-space is shared. A DropMV-A waiting for DropMV-B
        // (because MV-A's body referenced MV-B) wires correctly here.
        is DiffOperation.DropMaterializedView ->
            idx.dropViewDependentsByTable[op.objectRef.rootName].orEmpty().toSet()
        is DiffOperation.DropSequence ->
            idx.dropSequenceDependentsBySequence[op.objectRef.rootName].orEmpty().toSet()
        else -> emptySet()
    }

    private fun viewCreateEdges(deps: DependencyInfo?, opId: String, idx: Indexes): Set<String> {
        if (deps == null) return emptySet()
        val edges = mutableSetOf<String>()
        deps.tables.mapNotNull { idx.createTableId[it] }.filter { it != opId }.forEach { edges += it }
        deps.views.mapNotNull { idx.createViewId[it] }.filter { it != opId }.forEach { edges += it }
        deps.functions.mapNotNull { idx.createFunctionId[it] }.filter { it != opId }.forEach { edges += it }
        deps.functions.mapNotNull { idx.createProcedureId[it] }.filter { it != opId }.forEach { edges += it }
        deps.sequences.mapNotNull { idx.createSequenceId[it] }.filter { it != opId }.forEach { edges += it }
        return edges
    }

    private fun routineCreateEdges(deps: DependencyInfo?, opId: String, idx: Indexes): Set<String> {
        if (deps == null) return emptySet()
        val edges = mutableSetOf<String>()
        deps.tables.mapNotNull { idx.createTableId[it] }.filter { it != opId }.forEach { edges += it }
        deps.views.mapNotNull { idx.createViewId[it] }.filter { it != opId }.forEach { edges += it }
        deps.functions.mapNotNull { idx.createFunctionId[it] }.filter { it != opId }.forEach { edges += it }
        deps.functions.mapNotNull { idx.createProcedureId[it] }.filter { it != opId }.forEach { edges += it }
        deps.sequences.mapNotNull { idx.createSequenceId[it] }.filter { it != opId }.forEach { edges += it }
        return edges
    }

    private fun triggerCreateEdges(
        trigger: dev.dmigrate.core.model.TriggerDefinition,
        opId: String,
        idx: Indexes,
    ): Set<String> {
        val edges = mutableSetOf<String>()
        // The trigger's owning table is part of its definition, not its
        // DependencyInfo — wire that edge unconditionally.
        idx.createTableId[trigger.table]?.takeIf { it != opId }?.let { edges += it }
        val deps = trigger.dependencies ?: return edges
        deps.functions.mapNotNull { idx.createFunctionId[it] }.filter { it != opId }.forEach { edges += it }
        deps.functions.mapNotNull { idx.createProcedureId[it] }.filter { it != opId }.forEach { edges += it }
        return edges
    }

    // ── Unsafe-pair detection ─────────────────────────────────────

    private fun detectUnsafeRoutinePairs(ops: List<DiffOperation>): List<UnsafePair> {
        // Plan §3 line 822-824: "Routine ↔ Routine" is the
        // canonical unsafe pair class. Two routine ops in the same
        // plan with neither manifest edge in either direction are
        // flagged. Other potentially-dependent pairs (Routine↔Table
        // etc.) are resolved by Engine-metadata in D.2 / D.3.
        val routineOps = ops.filter { it.isRoutineOp() }
        if (routineOps.size < 2) return emptyList()

        // routineNameByOpId maps a routine op id back to its
        // referenceable name for cheap edge-existence checks.
        val routineNameByOpId = routineOps.associate { it.id to it.objectRef.rootName }
        val pairs = mutableListOf<UnsafePair>()
        for (i in routineOps.indices) {
            for (j in i + 1 until routineOps.size) {
                val a = routineOps[i]
                val b = routineOps[j]
                if (sharesManifestEdge(a, b, routineNameByOpId)) continue
                if (a.objectRef.rootName == b.objectRef.rootName) continue // same routine, different op type
                pairs += UnsafePair(first = a.objectRef, second = b.objectRef)
            }
        }
        return pairs
    }

    private fun sharesManifestEdge(
        a: DiffOperation,
        b: DiffOperation,
        routineNameByOpId: Map<String, String>,
    ): Boolean {
        // An edge in EITHER direction (via op.dependencies that
        // contains the other's id, or via the routine's
        // DependencyInfo.functions list) counts as "safe".
        if (b.id in a.dependencies || a.id in b.dependencies) return true
        val aDeps = routineDependencies(a)?.functions.orEmpty()
        val bDeps = routineDependencies(b)?.functions.orEmpty()
        val bName = routineNameByOpId[b.id] ?: return false
        val aName = routineNameByOpId[a.id] ?: return false
        return bName in aDeps || aName in bDeps
    }

    private fun routineDependencies(op: DiffOperation): DependencyInfo? = when (op) {
        is DiffOperation.CreateFunction -> op.function.dependencies
        is DiffOperation.ReplaceFunction -> op.after.dependencies
        is DiffOperation.DropFunction -> op.function.dependencies
        is DiffOperation.CreateProcedure -> op.procedure.dependencies
        is DiffOperation.ReplaceProcedure -> op.after.dependencies
        is DiffOperation.DropProcedure -> op.procedure.dependencies
        else -> null
    }

    private fun DiffOperation.isRoutineOp(): Boolean = when (this) {
        is DiffOperation.CreateFunction,
        is DiffOperation.ReplaceFunction,
        is DiffOperation.DropFunction,
        is DiffOperation.CreateProcedure,
        is DiffOperation.ReplaceProcedure,
        is DiffOperation.DropProcedure,
        -> true
        else -> false
    }
}
