package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.util.sha256Hex

/**
 * Classifies a planner-produced operation list into:
 *
 * - per-table rebuild buckets (any op that requires the SQLite
 *   RebuildTable pipeline: column reshape, PK reshape, constraint
 *   reshape, plus the column-level Add/Drop ops on the same table
 *   which the rebuild absorbs);
 * - simple ops that the renderer can emit individually
 *   (Create/DropTable on tables that *aren't* being rebuilt,
 *   index ops, view ops, plus column-level Add/Drop on tables that
 *   aren't being rebuilt).
 *
 * The classification is deterministic given identical input — both
 * the bucket order (by table name) and the op order within a bucket
 * (preserves planner topo-sort order) are stable.
 */
internal object SqliteRebuildPlanner {

    data class Classification(
        val rebuildBuckets: Map<String, List<DiffOperation>>,
        val simpleOps: List<DiffOperation>,
    )

    fun classify(ops: List<DiffOperation>): Classification {
        val rebuildTables = ops.asSequence()
            .filter { isRebuildTrigger(it) }
            .mapNotNull { tableOf(it) }
            .toSet()
        if (rebuildTables.isEmpty()) {
            return Classification(rebuildBuckets = emptyMap(), simpleOps = ops)
        }
        val buckets = LinkedHashMap<String, MutableList<DiffOperation>>()
        for (table in rebuildTables.sorted()) buckets[table] = mutableListOf()
        val simple = mutableListOf<DiffOperation>()
        for (op in ops) {
            val target = tableOf(op)
            if (target != null && target in rebuildTables && isAbsorbedByRebuild(op)) {
                buckets.getValue(target) += op
            } else {
                simple += op
            }
        }
        return Classification(rebuildBuckets = buckets, simpleOps = simple)
    }

    /**
     * Deterministic temp-table name suffix for one rebuild bucket:
     * `<table>__dmg_rebuild_<short-hex>`. The hex comes from a SHA-256
     * over the sorted op-ids in the bucket so that two planner runs
     * over the same input always produce the same temp name.
     */
    fun tempTableName(table: String, bucket: List<DiffOperation>): String {
        val payload = bucket.map { it.id }.sorted().joinToString("")
        val hex = sha256Hex(payload).take(8)
        return "${table}__dmg_rebuild_$hex"
    }

    private fun isRebuildTrigger(op: DiffOperation): Boolean = when (op) {
        is DiffOperation.AlterColumnType,
        is DiffOperation.AlterColumnNullability,
        is DiffOperation.AlterColumnDefault,
        is DiffOperation.AddPrimaryKey,
        is DiffOperation.DropPrimaryKey,
        is DiffOperation.AddConstraint,
        is DiffOperation.DropConstraint,
        -> true
        else -> false
    }

    /**
     * Ops absorbed into the rebuild when the table is being rebuilt:
     * everything that affects column structure or constraint shape.
     * Index ops are NOT absorbed — they run after the rebuild via
     * the planner's INDEXES phase.
     */
    private fun isAbsorbedByRebuild(op: DiffOperation): Boolean = when (op) {
        is DiffOperation.AddColumn,
        is DiffOperation.DropColumn,
        is DiffOperation.AlterColumnType,
        is DiffOperation.AlterColumnNullability,
        is DiffOperation.AlterColumnDefault,
        is DiffOperation.AddPrimaryKey,
        is DiffOperation.DropPrimaryKey,
        is DiffOperation.AddConstraint,
        is DiffOperation.DropConstraint,
        -> true
        else -> false
    }

    private fun tableOf(op: DiffOperation): String? = when (op) {
        is DiffOperation.CreateTable -> op.objectRef.rootName
        is DiffOperation.DropTable -> op.objectRef.rootName
        is DiffOperation.AddColumn,
        is DiffOperation.DropColumn,
        is DiffOperation.AlterColumnType,
        is DiffOperation.AlterColumnNullability,
        is DiffOperation.AlterColumnDefault,
        -> op.objectRef.path[0]
        is DiffOperation.AddPrimaryKey,
        is DiffOperation.DropPrimaryKey,
        -> op.objectRef.rootName
        is DiffOperation.AddConstraint,
        is DiffOperation.DropConstraint,
        -> op.objectRef.path[0]
        is DiffOperation.AddIndex,
        is DiffOperation.DropIndex,
        -> op.objectRef.path[0]
        else -> null
    }
}
