package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.Reversibility
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationDdlResult

/**
 * SQLite-flavoured renderer for the migration pipeline.
 *
 * Phase D.4.a (slice 1): simple ops — Create/DropTable, Add/DropColumn,
 * Add/DropIndex, Create/Replace/DropView. Rebuild-required ops were
 * deferred to MANUAL_ACTION_REQUIRED.
 *
 * Phase D.4.b (this slice): full RebuildTable pipeline per Plan §6.4
 * for ops that SQLite cannot ALTER in place (column-type/nullability/
 * default change, PK reshape, constraint reshape). The rebuild
 * absorbs Add/Drop column ops on the same table since the desired
 * schema already reflects them.
 *
 * Pipeline:
 *
 * 1. [SqliteRebuildPlanner] classifies operations into rebuild
 *    buckets (per table) plus the residual simple ops.
 * 2. Rebuild buckets render via [SqliteRebuildRenderer], which
 *    emits the canonical 10-statement sequence
 *    (`PRAGMA foreign_keys = OFF; BEGIN IMMEDIATE; CREATE temp;
 *    INSERT-SELECT; DROP original; RENAME temp; CREATE INDEX...;
 *    PRAGMA foreign_key_check; COMMIT; PRAGMA foreign_keys = ON;`)
 *    and computes the column mapping (CAST for type changes,
 *    DEFAULT/NULL fill for new columns, BLOCKER for unfillable
 *    NOT NULL).
 * 3. Simple ops render through [SqliteDiffSimpleOps] as before.
 *
 * Down-direction: NOT_REVERSIBLE for rebuild buckets in D.4.b
 * (ROLLBACK_NOT_POSSIBLE blocker); D.5 will add the inverse-rebuild
 * support.
 *
 * Out of first matrix entirely (DIALECT_UNSUPPORTED_OPERATION):
 * routines, triggers, sequences, custom types.
 */
class SqliteDiffDdlGenerator : DiffDdlGenerator {

    override val dialect: DatabaseDialect = DatabaseDialect.SQLITE

    private val sql = SqliteDiffSqlBuilders()
    private val rebuildRenderer = SqliteRebuildRenderer(sql)

    override fun generateUp(diff: DiffResult, options: DdlGenerationOptions): MigrationDdlResult =
        render(diff, options, direction = SqliteRenderDirection.UP)

    override fun generateDown(diff: DiffResult, options: DdlGenerationOptions): MigrationDdlResult =
        render(diff, options, direction = SqliteRenderDirection.DOWN)

    private fun render(
        diff: DiffResult,
        options: DdlGenerationOptions,
        direction: SqliteRenderDirection,
    ): MigrationDdlResult {
        val ctx = SqliteDiffRenderContext(direction = direction, sql = sql, options = options)
        val ops = if (direction == SqliteRenderDirection.UP) diff.operations else diff.operations.reversed()

        val classification = SqliteRebuildPlanner.classify(ops)

        // Render rebuild buckets first (they belong to TABLES phase),
        // then the rest in original (already topo-sorted) order.
        for ((table, bucket) in classification.rebuildBuckets) {
            renderRebuildBucket(table, bucket, diff, ctx)
        }
        for (op in classification.simpleOps) renderSimpleOp(op, ctx)

        return ctx.toResult(diff)
    }

    private fun renderRebuildBucket(
        table: String,
        bucket: List<DiffOperation>,
        diff: DiffResult,
        ctx: SqliteDiffRenderContext,
    ) {
        val current = diff.currentSchema?.tables?.get(table)
        val desired = diff.desiredSchema?.tables?.get(table)
        if (current == null || desired == null) {
            for (op in bucket) {
                ctx.skip(
                    op,
                    "RebuildTable for `$table` requires both current and desired SchemaDefinition; " +
                        "the planner did not propagate them. This typically means the DiffResult " +
                        "was deserialised from an artefact rather than freshly produced.",
                    code = "SQLITE_REBUILD_MISSING_SOURCES",
                )
                ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
            }
            return
        }
        if (ctx.direction == SqliteRenderDirection.DOWN) {
            // Any NOT_REVERSIBLE op in the bucket (notably DropColumn — the
            // dropped data is gone) prevents the inverse-rebuild from
            // succeeding. Short-circuit before invoking the renderer.
            val nonReversible = bucket.filter { it.reversibility == Reversibility.NOT_REVERSIBLE }
            if (nonReversible.isNotEmpty()) {
                for (op in nonReversible) {
                    ctx.skip(
                        op,
                        "Rebuild bucket for `$table` contains NOT_REVERSIBLE operation ${op.id}; " +
                            "down-rebuild cannot reconstruct the dropped data.",
                        code = "SQLITE_REBUILD_NOT_REVERSIBLE",
                    )
                }
                ctx.addBlocker(
                    MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE,
                    operationIds = bucket.map { it.id }.toSet(),
                )
                return
            }
            // Swap schemas: the down-rebuild copies from desired (= post-up
            // state) into a target shaped like the original current.
            val downCatalog = diff.currentSchema?.let { SqliteCatalogSnapshot.fromSchema(it) }
                ?: SqliteCatalogSnapshot.EMPTY
            val downPlan = SqliteRebuildPlanner.planRebuild(
                table = table,
                bucket = bucket,
                source = desired,
                target = current,
                bucketRisk = ctx.bucketRisk(bucket),
                sql = sql,
                catalog = downCatalog,
            )
            rebuildRenderer.render(downPlan, ctx)
            return
        }
        // Phase H.2: synthesise the temp-name collision-probe catalog
        // from the current-schema snapshot. The execute-pipeline can
        // fold a live `sqlite_master` probe on top via
        // SqliteCatalogSnapshot.union() — at the CLI/runner layer,
        // not here.
        val upCatalog = diff.currentSchema?.let { SqliteCatalogSnapshot.fromSchema(it) }
            ?: SqliteCatalogSnapshot.EMPTY
        val upPlan = SqliteRebuildPlanner.planRebuild(
            table = table,
            bucket = bucket,
            source = current,
            target = desired,
            bucketRisk = ctx.bucketRisk(bucket),
            sql = sql,
            catalog = upCatalog,
        )
        rebuildRenderer.render(upPlan, ctx)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun renderSimpleOp(op: DiffOperation, ctx: SqliteDiffRenderContext) {
        if (ctx.direction == SqliteRenderDirection.DOWN && op.reversibility == Reversibility.NOT_REVERSIBLE) {
            ctx.skip(op, "Operation ${op.id} is NOT_REVERSIBLE; cannot render down direction.")
            ctx.addBlocker(MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE, operationIds = setOf(op.id))
            return
        }
        when (op) {
            is DiffOperation.CreateTable -> SqliteDiffSimpleOps.renderCreateTable(op, ctx)
            is DiffOperation.DropTable -> SqliteDiffSimpleOps.renderDropTable(op, ctx)
            is DiffOperation.AddColumn -> SqliteDiffSimpleOps.renderAddColumn(op, ctx)
            is DiffOperation.DropColumn -> SqliteDiffSimpleOps.renderDropColumn(op, ctx)
            is DiffOperation.AddIndex -> SqliteDiffSimpleOps.renderAddIndex(op, ctx)
            is DiffOperation.DropIndex -> SqliteDiffSimpleOps.renderDropIndex(op, ctx)
            is DiffOperation.CreateView -> SqliteDiffSimpleOps.renderCreateView(op, ctx)
            is DiffOperation.ReplaceView -> SqliteDiffSimpleOps.renderReplaceView(op, ctx)
            is DiffOperation.DropView -> SqliteDiffSimpleOps.renderDropView(op, ctx)

            // Rebuild-required ops should have been classified into a rebuild bucket by
            // SqliteRebuildPlanner.classify. If they show up here they're on a table whose
            // current/desired schema couldn't be located — handled in renderRebuildBucket.
            is DiffOperation.AlterColumnType,
            is DiffOperation.AlterColumnNullability,
            is DiffOperation.AlterColumnDefault,
            is DiffOperation.AddPrimaryKey,
            is DiffOperation.DropPrimaryKey,
            is DiffOperation.AddConstraint,
            is DiffOperation.DropConstraint,
            -> ctx.deferToRebuild(op)

            // Out of first matrix entirely
            is DiffOperation.CreateCustomType,
            is DiffOperation.AlterCustomType,
            is DiffOperation.DropCustomType,
            is DiffOperation.CreateSequence,
            is DiffOperation.AlterSequence,
            is DiffOperation.DropSequence,
            is DiffOperation.CreateFunction,
            is DiffOperation.ReplaceFunction,
            is DiffOperation.DropFunction,
            is DiffOperation.CreateProcedure,
            is DiffOperation.ReplaceProcedure,
            is DiffOperation.DropProcedure,
            is DiffOperation.CreateTrigger,
            is DiffOperation.ReplaceTrigger,
            is DiffOperation.DropTrigger,
            -> markUnsupported(op, ctx)
        }
    }

    private fun markUnsupported(op: DiffOperation, ctx: SqliteDiffRenderContext) {
        ctx.skip(op, "Operation ${op::class.simpleName} is not in the first SQLite matrix.")
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
    }
}
