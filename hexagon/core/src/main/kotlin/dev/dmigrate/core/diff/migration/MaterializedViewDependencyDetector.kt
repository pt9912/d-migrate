package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.SchemaDefinition

/**
 * Plan-2 §8 D.3b Sub-Slice C: surface `(materialized-view,
 * dropping-op)` pairs where the dropping/replacing operation would
 * orphan an MV.
 *
 * The detector walks both schemas to collect every MV (a regular view
 * becomes an MV when its `materialized` flag is set), then for each
 * MV's `dependencies.tables/views/functions` checks whether the plan
 * drops or replaces the depended-on object without also dropping or
 * replacing the MV itself. A cross-MV dependency (MV-A references
 * MV-B) follows the same rule: dropping MV-B without dropping MV-A
 * orphans MV-A.
 *
 * The structured carrier ([MaterializedViewDependencyBlocker]) lets
 * the report builder synthesise `materializedViews[]` entries for
 * orphaned MVs that have no in-plan operation of their own. Extracted
 * out of [DiffPlanner] purely to keep the planner file under Detekt's
 * `LargeClass` threshold.
 */
internal object MaterializedViewDependencyDetector {

    fun detect(
        ops: List<DiffOperation>,
        current: SchemaDefinition,
        desired: SchemaDefinition,
    ): List<MaterializedViewDependencyBlocker> {
        val mvsBeingTouched = ops.mapNotNullTo(mutableSetOf()) { op ->
            when (op) {
                is DiffOperation.DropMaterializedView -> op.objectRef.rootName
                is DiffOperation.ReplaceMaterializedView -> op.objectRef.rootName
                else -> null
            }
        }
        val dropOrReplaceByName = collectDropOrReplaceByName(ops)
        val blockers = mutableListOf<MaterializedViewDependencyBlocker>()
        // Deduplicate across current/desired: an MV named the same on
        // both sides should produce one blocker per dropping-op pairing.
        val visitedMvs = mutableSetOf<String>()
        for (schema in listOf(current, desired)) {
            for ((mvName, view) in schema.views) {
                if (!view.materialized) continue
                if (mvName in mvsBeingTouched) continue
                if (!visitedMvs.add(mvName)) continue
                val deps = view.dependencies ?: continue
                collectBlockersFor(mvName, deps.tables, "TABLE", dropOrReplaceByName, blockers)
                // Regular view drop/replace AND MV drop both match here —
                // Cross-MV dependency is naturally covered because MVs
                // share the view name-space in `dependencies.views`.
                collectBlockersFor(mvName, deps.views, "VIEW", dropOrReplaceByName, blockers)
                collectBlockersFor(mvName, deps.views, "MATERIALIZED_VIEW", dropOrReplaceByName, blockers)
                collectBlockersFor(mvName, deps.functions, "FUNCTION", dropOrReplaceByName, blockers)
                collectBlockersFor(mvName, deps.functions, "PROCEDURE", dropOrReplaceByName, blockers)
            }
        }
        return blockers
    }

    private fun collectBlockersFor(
        mvName: String,
        deps: List<String>,
        kind: String,
        index: Map<String, Pair<DiffOperation, String>>,
        out: MutableList<MaterializedViewDependencyBlocker>,
    ) {
        for (depName in deps) {
            val hit = index[depKey(depName, kind)] ?: continue
            out += MaterializedViewDependencyBlocker(
                materializedViewName = mvName,
                materializedViewPath = listOf(mvName),
                droppingOperationId = hit.first.id,
                droppingObjectType = hit.first.objectRef.type,
                droppingPath = hit.first.objectRef.path,
                droppingKind = hit.second,
            )
        }
    }

    private fun depKey(name: String, kind: String): String = "$kind|$name"

    private fun collectDropOrReplaceByName(
        ops: List<DiffOperation>,
    ): Map<String, Pair<DiffOperation, String>> {
        val out = mutableMapOf<String, Pair<DiffOperation, String>>()
        for (op in ops) {
            val (name, kind) = when (op) {
                is DiffOperation.DropTable -> op.objectRef.rootName to "TABLE"
                is DiffOperation.DropView -> op.objectRef.rootName to "VIEW"
                is DiffOperation.ReplaceView -> op.objectRef.rootName to "VIEW"
                is DiffOperation.DropMaterializedView -> op.objectRef.rootName to "MATERIALIZED_VIEW"
                is DiffOperation.DropFunction -> op.objectRef.rootName to "FUNCTION"
                is DiffOperation.ReplaceFunction -> op.objectRef.rootName to "FUNCTION"
                is DiffOperation.DropProcedure -> op.objectRef.rootName to "PROCEDURE"
                is DiffOperation.ReplaceProcedure -> op.objectRef.rootName to "PROCEDURE"
                else -> continue
            }
            // First-write-wins: a single op per `(name, kind)` is enough
            // for the orphan-detection question; ID disambiguation may
            // produce two ops for the same name but the structural
            // outcome (the MV gets orphaned) is identical.
            out.putIfAbsent(depKey(name, kind), op to kind)
        }
        return out
    }
}
