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
                continue
            }
            // Phase H.3a: view/trigger ops on a rebuilt table are absorbed
            // into the rebuild — the canonical sequence emits explicit
            // DROP VIEW / DROP TRIGGER before `DROP TABLE` and CREATE
            // statements after RENAME from the plan's
            // dependentViewsTo{Drop,Recreate} / dependentTriggersTo{Drop,Recreate}
            // lists. Letting the simpleOp run after the rebuild would
            // double-emit. The absorbing bucket is the alphabetically-
            // first matching rebuilt table the op references.
            val absorbingBucket = absorbingRebuildTableFor(op, rebuildTables)
            if (absorbingBucket != null) {
                buckets.getValue(absorbingBucket) += op
            } else {
                simple += op
            }
        }
        return Classification(rebuildBuckets = buckets, simpleOps = simple)
    }

    /**
     * Phase H.3a: returns the alphabetically-first rebuild table this
     * view/trigger op references, or `null` if the op is not a
     * view/trigger op or references no rebuilt table.
     *
     * Edge case: a view referencing multiple rebuilt tables lands in
     * one bucket only. The other bucket(s) drop the table without
     * dropping this view first — SQLite tolerates the dangling view
     * reference until a `SELECT` against the view fires. Documented
     * as H.3a-Limitation; the planner could be lifted to multi-bucket
     * absorption in a follow-up if real schemas hit this.
     */
    private fun absorbingRebuildTableFor(op: DiffOperation, rebuildTables: Set<String>): String? {
        val referenced = viewOrTriggerTableRefs(op)
        if (referenced.isEmpty()) return null
        return (referenced intersect rebuildTables).minOrNull()
    }

    private fun viewOrTriggerTableRefs(op: DiffOperation): Set<String> = when (op) {
        is DiffOperation.CreateTrigger -> setOf(op.trigger.table)
        is DiffOperation.DropTrigger -> setOf(op.trigger.table)
        is DiffOperation.ReplaceTrigger -> setOf(op.before.table, op.after.table)
        is DiffOperation.CreateView -> (op.view.dependencies?.tables ?: emptyList()).toSet()
        is DiffOperation.DropView -> (op.view.dependencies?.tables ?: emptyList()).toSet()
        is DiffOperation.ReplaceView ->
            ((op.before.dependencies?.tables ?: emptyList()) +
                (op.after.dependencies?.tables ?: emptyList())).toSet()
        else -> emptySet()
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
        sourceSchema: dev.dmigrate.core.model.SchemaDefinition? = null,
        targetSchema: dev.dmigrate.core.model.SchemaDefinition? = null,
    ): SqliteRebuildPlan {
        val mapping = computeColumnMapping(source, target, sql)
        val resolvedTempName = resolveTempTableName(table, bucket, catalog)
        return SqliteRebuildPlan(
            originalTableName = table,
            oldTable = source,
            newTable = target,
            newTableTempName = resolvedTempName,
            bucketOperations = bucket,
            sourceOperationIds = bucket.map { it.id }.toSet(),
            risk = bucketRisk,
            mapping = mapping,
            indexesToRecreate = target.indices,
            dependentViewsToDrop = collectDependentViews(sourceSchema, table),
            dependentViewsToRecreate = collectDependentViews(targetSchema, table),
            dependentTriggersToDrop = collectDependentTriggers(sourceSchema, table),
            dependentTriggersToRecreate = collectDependentTriggers(targetSchema, table),
            preflight = buildPreflightChecks(table, source, mapping, resolvedTempName, catalog),
        )
    }

    /**
     * Phase H.4: build the 6-entry preflight list per Plan §6.4
     * (L928-934 Typentwurf). Every plan carries all 6 entries with
     * per-kind [SqliteRebuildPreflightOutcome] so consumers (Migrate-
     * Report, MCP, JSON serialisation) can inspect the rebuild's
     * readiness declaratively.
     */
    private fun buildPreflightChecks(
        table: String,
        source: TableDefinition,
        mapping: SqliteColumnMappingModel,
        resolvedTempName: String,
        catalog: SqliteCatalogSnapshot,
    ): List<SqliteRebuildPreflightCheck> {
        val out = mutableListOf<SqliteRebuildPreflightCheck>()

        // TABLE_EXISTS: source TableDefinition is non-null by contract
        // (Generator already short-circuits if the table is missing
        // from current/desired). The check is therefore PASS at this
        // layer; the missing-source path is covered by the
        // SQLITE_REBUILD_MISSING_SOURCES diagnostic in the dispatcher.
        out += SqliteRebuildPreflightCheck(
            kind = SqliteRebuildPreflightKind.TABLE_EXISTS,
            outcome = SqliteRebuildPreflightOutcome.PASS,
            target = table,
            message = "table `$table` is present in the source schema snapshot",
        )

        // TEMP_NAME_AVAILABLE: Phase H.2 resolves the name with a
        // deterministic __2/__3 suffix when the hash collides. The
        // resolved name itself is guaranteed free (resolveTempTableName
        // iterates until catalog.contains(...) is false).
        //
        // - FAIL: defensive — only if resolveTempTableName has a bug.
        // - INFO: a suffix was appended → base name collided.
        // - PASS: no suffix needed → base name was free.
        val hadSuffix = resolvedTempName != resolvedTempName.removeSuffixIfMatches()
        val tempNameInfo = when {
            catalog.contains(resolvedTempName) -> SqliteRebuildPreflightCheck(
                kind = SqliteRebuildPreflightKind.TEMP_NAME_AVAILABLE,
                outcome = SqliteRebuildPreflightOutcome.FAIL,
                target = resolvedTempName,
                message = "resolved temp name `$resolvedTempName` still collides with the catalog — planner bug",
            )
            hadSuffix -> SqliteRebuildPreflightCheck(
                kind = SqliteRebuildPreflightKind.TEMP_NAME_AVAILABLE,
                outcome = SqliteRebuildPreflightOutcome.INFO,
                target = resolvedTempName,
                message = "temp name resolved with collision-suffix → `$resolvedTempName`",
            )
            else -> SqliteRebuildPreflightCheck(
                kind = SqliteRebuildPreflightKind.TEMP_NAME_AVAILABLE,
                outcome = SqliteRebuildPreflightOutcome.PASS,
                target = resolvedTempName,
                message = "temp name `$resolvedTempName` does not collide with the catalog snapshot",
            )
        }
        out += tempNameInfo

        // SOURCE_COLUMNS_EXIST: every preservedColumns.sourceColumn
        // must exist in source.columns.keys. Reads the structured
        // sourceColumn field of ColumnCopyMapping (H.1a contract) so
        // no SQL expression parsing is needed.
        val missingSources = mapping.preservedColumns
            .map { it.sourceColumn }
            .filter { it !in source.columns }
        if (missingSources.isEmpty()) {
            out += SqliteRebuildPreflightCheck(
                kind = SqliteRebuildPreflightKind.SOURCE_COLUMNS_EXIST,
                outcome = SqliteRebuildPreflightOutcome.PASS,
                target = table,
                message = "all preservedColumns.sourceColumn entries exist in oldTable.columns",
            )
        } else {
            out += SqliteRebuildPreflightCheck(
                kind = SqliteRebuildPreflightKind.SOURCE_COLUMNS_EXIST,
                outcome = SqliteRebuildPreflightOutcome.FAIL,
                target = missingSources.joinToString(","),
                message = "ColumnCopyMapping references source column(s) not present in oldTable: " +
                    missingSources.joinToString(", "),
            )
        }

        // DEPENDENCIES_KNOWN: F.6.b (column-deps) and G.2 (view-table-
        // usage privilege) blockers run in the DiffPlanner layer, not
        // in the SQLite adapter. The preflight entry is informational
        // here — it documents the runtime check chain rather than
        // re-evaluating it.
        out += SqliteRebuildPreflightCheck(
            kind = SqliteRebuildPreflightKind.DEPENDENCIES_KNOWN,
            outcome = SqliteRebuildPreflightOutcome.INFO,
            target = table,
            message = "view/trigger dependency projection completeness is validated by " +
                "DiffPlanner.detectViewColumnDepsBlockers (F.6.b) and " +
                "DiffPlanner.detectIncompleteViewProjections (G.2)",
        )

        // ADDED_COLUMNS_FILLABLE: aggregate NOT_NULL_BACKFILL +
        // CAST_MATRIX outcomes from the mapping. The renderer's
        // existing blocker codes stay parallel; this entry pins the
        // failure declaratively.
        if (mapping.isBlocked) {
            val reasons = buildList {
                if (mapping.notNullBackfillBlocked.isNotEmpty()) {
                    add("NOT NULL backfill blocked: ${mapping.notNullBackfillBlocked.joinToString(", ")}")
                }
                if (mapping.castNotWhitelisted.isNotEmpty()) {
                    add("cast-matrix block(s): ${mapping.castNotWhitelisted.joinToString(", ") { it.column }}")
                }
            }
            out += SqliteRebuildPreflightCheck(
                kind = SqliteRebuildPreflightKind.ADDED_COLUMNS_FILLABLE,
                outcome = SqliteRebuildPreflightOutcome.FAIL,
                target = table,
                message = reasons.joinToString("; "),
            )
        } else {
            out += SqliteRebuildPreflightCheck(
                kind = SqliteRebuildPreflightKind.ADDED_COLUMNS_FILLABLE,
                outcome = SqliteRebuildPreflightOutcome.PASS,
                target = table,
                message = "NOT NULL backfill + cast-matrix are satisfied for all added columns",
            )
        }

        // FOREIGN_KEYS_CHECKABLE: runner-vertrag (execute-time). The
        // renderer emits `PRAGMA foreign_key_check;` in the CLEANUP
        // phase; the d-migrate runner must treat a non-empty result as
        // a hard abort rather than informational.
        out += SqliteRebuildPreflightCheck(
            kind = SqliteRebuildPreflightKind.FOREIGN_KEYS_CHECKABLE,
            outcome = SqliteRebuildPreflightOutcome.INFO,
            target = table,
            message = "runner-vertrag: a non-empty `PRAGMA foreign_key_check;` result must " +
                "abort the migration (not be treated as informational)",
        )

        return out
    }

    /**
     * Returns the temp-name with any trailing `__<digit>+` suffix
     * removed — i.e. the base name as `tempTableName` would have
     * emitted. Used by [buildPreflightChecks] to recompute the
     * collision-probe target without re-running [tempTableName].
     */
    private fun String.removeSuffixIfMatches(): String =
        replace(Regex("""__\d+$"""), "")

    /**
     * Phase H.3a: views from [schema] whose `dependencies.tables`
     * contains [table]. Returned in alphabetical order by view name
     * for deterministic SQL emission. Returns empty when [schema] is
     * null (legacy callers that don't pass schemas).
     */
    private fun collectDependentViews(
        schema: dev.dmigrate.core.model.SchemaDefinition?,
        table: String,
    ): List<NamedViewDefinition> {
        if (schema == null) return emptyList()
        return schema.views.entries
            .asSequence()
            .filter { (_, view) -> (view.dependencies?.tables ?: emptyList()).contains(table) }
            .map { (name, view) -> NamedViewDefinition(name, view) }
            .sortedBy { it.name }
            .toList()
    }

    /**
     * Phase H.3a: triggers from [schema] whose `table` is [table].
     * Returned in alphabetical order by trigger name for deterministic
     * SQL emission.
     *
     * **Key vs. SQL-Name**: `SchemaDefinition.triggers` is keyed by
     * `ObjectKeyCodec.triggerKey(table, name) = "<table>::<name>"` to
     * disambiguate triggers across tables with the same trigger name.
     * For the rebuild's DROP/CREATE TRIGGER SQL we need the **bare
     * trigger name** (the SQLite-side identifier), not the canonical
     * map-key. Decode the key via `parseTriggerKey` and emit only the
     * name component.
     */
    private fun collectDependentTriggers(
        schema: dev.dmigrate.core.model.SchemaDefinition?,
        table: String,
    ): List<NamedTriggerDefinition> {
        if (schema == null) return emptyList()
        return schema.triggers.entries
            .asSequence()
            .filter { (_, trigger) -> trigger.table == table }
            .map { (key, trigger) ->
                // Parse only when the key looks canonical; tolerate
                // hand-written file paths where the map-key already is
                // the SQL name (no `::` separator).
                val sqlName = if (key.contains("::")) {
                    dev.dmigrate.core.identity.ObjectKeyCodec.parseTriggerKey(key).second
                } else {
                    key
                }
                NamedTriggerDefinition(sqlName, trigger)
            }
            .sortedBy { it.name }
            .toList()
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
