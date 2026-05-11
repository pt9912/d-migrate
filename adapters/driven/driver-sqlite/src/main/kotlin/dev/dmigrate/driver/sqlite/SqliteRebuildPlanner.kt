package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.TableDefinition
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


    /**
     * Phase H.2: collision-aware temp-table-name resolver. Computes
     * the base name via [tempTableName] and, if it collides with any
     * object in [catalog], deterministically falls back to
     * `<base>__2`, `<base>__3`, ... until a free name is found.
     *
     * The probe is plan-time: the caller passes a
     * [SqliteCatalogSnapshot] **before** plan-build, the result is
     * frozen into `SqliteRebuildPlan.newTableTempName`, and the
     * renderer treats it as final.
     *
     * Worst-case termination: the catalog size N is finite, so at
     * most N+1 attempts are needed. The deterministic suffix order
     * keeps replanning idempotent.
     */
    fun resolveTempTableName(
        table: String,
        bucket: List<DiffOperation>,
        catalog: SqliteCatalogSnapshot,
    ): String {
        val base = tempTableName(table, bucket)
        if (!catalog.contains(base)) return base
        var suffix = 2
        while (true) {
            val candidate = "${base}__$suffix"
            if (!catalog.contains(candidate)) return candidate
            suffix++
        }
    }

    /**
     * Phase H.1b: build the per-bucket [SqliteRebuildPlan] consumed by
     * [SqliteRebuildRenderer.render]. Pure function — no context, no
     * live-DB lookups. The caller pre-computes [bucketRisk] via
     * `SqliteDiffRenderContext.bucketRisk` (which is direction-aware)
     * and passes it in; the planner can't read direction on its own.
     *
     * SQL emission stays bit-identical to the pre-H.1b path because
     * the plan's field values are exactly the inputs the renderer
     * used to compute inline. [sql] is threaded for identifier
     * quoting + type-mapping during the column-mapping build.
     *
     * Empty defaults for [SqliteRebuildPlan.dependentViewsToDrop] /
     * [SqliteRebuildPlan.dependentViewsToRecreate] /
     * [SqliteRebuildPlan.dependentTriggersToDrop] /
     * [SqliteRebuildPlan.dependentTriggersToRecreate] /
     * [SqliteRebuildPlan.preflight] — placeholders for H.3 / H.4.
     */
    fun planRebuild(
        table: String,
        bucket: List<DiffOperation>,
        source: TableDefinition,
        target: TableDefinition,
        bucketRisk: OperationRisk,
        sql: SqliteDiffSqlBuilders,
        catalog: SqliteCatalogSnapshot = SqliteCatalogSnapshot.EMPTY,
    ): SqliteRebuildPlan {
        val mapping = computeColumnMapping(source, target, sql)
        return SqliteRebuildPlan(
            originalTableName = table,
            oldTable = source,
            newTable = target,
            newTableTempName = resolveTempTableName(table, bucket, catalog),
            bucketOperations = bucket,
            sourceOperationIds = bucket.map { it.id }.toSet(),
            risk = bucketRisk,
            mapping = mapping,
            indexesToRecreate = target.indices,
        )
    }

    private fun computeColumnMapping(
        source: TableDefinition,
        target: TableDefinition,
        sql: SqliteDiffSqlBuilders,
    ): SqliteColumnMappingModel {
        val preserved = mutableListOf<ColumnCopyMapping>()
        val added = mutableListOf<AddedColumnFill>()
        val notNullBlocked = mutableListOf<String>()
        val castBlocked = mutableListOf<CastBlockEntry>()
        // Deterministic key order — same as pre-H.1b `targetColumnOrder`
        // so resulting SQL doesn't shift.
        for ((name, targetCol) in target.columns.entries.sortedBy { it.key }) {
            val sourceCol = source.columns[name]
            val quoted = sql.quote(name)
            when {
                sourceCol != null && sourceCol.type == targetCol.type ->
                    preserved += ColumnCopyMapping(
                        sourceColumn = name,
                        targetColumn = name,
                        expressionSql = quoted,
                        typeChanged = false,
                    )
                sourceCol != null -> {
                    if (!SqliteCastMatrix.isWhitelisted(sourceCol.type, targetCol.type)) {
                        castBlocked += CastBlockEntry(name, sourceCol.type, targetCol.type)
                        preserved += ColumnCopyMapping(
                            sourceColumn = name,
                            targetColumn = name,
                            expressionSql = "/* unsafe cast */",
                            typeChanged = true,
                        )
                    } else {
                        preserved += ColumnCopyMapping(
                            sourceColumn = name,
                            targetColumn = name,
                            expressionSql = "CAST($quoted AS ${sql.toSql(targetCol.type)})",
                            typeChanged = true,
                        )
                    }
                }
                targetCol.default is DefaultValue.SequenceNextVal -> {
                    notNullBlocked += name
                    added += AddedColumnFill(name, "/* sequence-default not supported */")
                }
                targetCol.default != null ->
                    added += AddedColumnFill(name, defaultLiteral(targetCol))
                !targetCol.required ->
                    added += AddedColumnFill(name, "NULL")
                else -> {
                    notNullBlocked += name
                    added += AddedColumnFill(name, "/* unfilled */")
                }
            }
        }
        val droppedNames = source.columns.keys.filter { it !in target.columns }.sorted()
        return SqliteColumnMappingModel(
            preservedColumns = preserved,
            addedColumns = added,
            droppedColumnNames = droppedNames,
            notNullBackfillBlocked = notNullBlocked,
            castNotWhitelisted = castBlocked,
        )
    }

    private fun defaultLiteral(col: ColumnDefinition): String {
        val dv = col.default ?: return "NULL"
        return when (dv) {
            is DefaultValue.StringLiteral -> "'${dv.value.replace("'", "''")}'"
            is DefaultValue.NumberLiteral -> dv.value.toString()
            is DefaultValue.BooleanLiteral -> if (dv.value) "1" else "0"
            is DefaultValue.FunctionCall -> if (dv.name == "current_timestamp") "CURRENT_TIMESTAMP"
                else "${dv.name}()"
            is DefaultValue.SequenceNextVal -> "NULL"
        }
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
