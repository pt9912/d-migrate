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
        val ctx = SqliteDiffRenderContext(
            direction = direction,
            sql = sql,
            options = options,
            currentSchema = diff.currentSchema,
            desiredSchema = diff.desiredSchema,
        )
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
            // Phase H.3a: sourceSchema/targetSchema follow the same
            // direction-aware swap (drop from current-of-the-direction,
            // recreate into target-of-the-direction).
            // Plan-2 §A.2: down path renders against the desired→current
            // direction. The DOWN catalog comes from `currentSchema` (the
            // target the down-rebuild reconstructs). A live SQLite probe
            // is meaningful only for the UP path's actual target DB; the
            // down artefact is generated for later external use, so no
            // live probe is unioned here.
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
                sourceSchema = diff.desiredSchema,
                targetSchema = diff.currentSchema,
            ).copy(emissionMode = emissionModeFor(ctx.options))
            rebuildRenderer.render(downPlan, ctx)
            return
        }
        // Phase H.2 + Plan-2 §A.2: synthesise the temp-name collision-
        // probe catalog from the current-schema snapshot, and union
        // with the live `sqlite_master` snapshot when the runner has
        // wired one through `DdlGenerationOptions.liveSqliteCatalog`
        // (SQLite + --execute path). The renderer remains pure
        // consumption: `newTableTempName` is frozen in the plan and
        // never re-resolved during SQL emission.
        val schemaCatalog = diff.currentSchema?.let { SqliteCatalogSnapshot.fromSchema(it) }
            ?: SqliteCatalogSnapshot.EMPTY
        val upCatalog = ctx.options.liveSqliteCatalog
            ?.let { schemaCatalog.union(SqliteCatalogSnapshot.fromLiveCatalog(it)) }
            ?: schemaCatalog
        val upPlan = SqliteRebuildPlanner.planRebuild(
            table = table,
            bucket = bucket,
            source = current,
            target = desired,
            bucketRisk = ctx.bucketRisk(bucket),
            sql = sql,
            catalog = upCatalog,
            sourceSchema = diff.currentSchema,
            targetSchema = diff.desiredSchema,
        ).copy(emissionMode = emissionModeFor(ctx.options))
        rebuildRenderer.render(upPlan, ctx)
    }

    /**
     * Phase H.3b: map the dialect-agnostic
     * [dev.dmigrate.driver.ExecutionMode] from
     * [dev.dmigrate.driver.DdlGenerationOptions] to the SQLite-internal
     * [SqliteRebuildEmissionMode]. Default `STANDALONE` keeps the
     * pauschal `PRAGMA foreign_keys = ON;` at the rebuild tail; EXECUTE
     * activates the runner-hook markers consumed by
     * `JdbcMigrationExecutor.runStreamOwnedTransaction`.
     */
    private fun emissionModeFor(options: dev.dmigrate.driver.DdlGenerationOptions): SqliteRebuildEmissionMode =
        when (options.executionMode) {
            dev.dmigrate.driver.ExecutionMode.STANDALONE -> SqliteRebuildEmissionMode.STANDALONE
            dev.dmigrate.driver.ExecutionMode.EXECUTE -> SqliteRebuildEmissionMode.EXECUTE
        }

    private fun renderSimpleOp(op: DiffOperation, ctx: SqliteDiffRenderContext) {
        if (ctx.direction == SqliteRenderDirection.DOWN && op.reversibility == Reversibility.NOT_REVERSIBLE) {
            ctx.skip(op, "Operation ${op.id} is NOT_REVERSIBLE; cannot render down direction.")
            ctx.addBlocker(MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE, operationIds = setOf(op.id))
            return
        }
        when (categorize(op)) {
            OpCategory.SIMPLE -> renderInlineSimpleOp(op, ctx)
            OpCategory.REBUILD -> ctx.deferToRebuild(op)
            OpCategory.MATERIALIZED_VIEW -> blockMaterializedView(op, ctx)
            OpCategory.UNSUPPORTED -> markUnsupported(op, ctx)
        }
    }

    /**
     * Compile-time exhaustiveness guard: every [DiffOperation] subtype
     * must be triaged here. When a new subtype is added to the sealed
     * hierarchy, this `when` will fail to compile until the new case
     * is categorised. `REBUILD` covers operations that
     * [SqliteRebuildPlanner.classify] should have absorbed into a
     * rebuild bucket — they only reach the simple-op renderer when the
     * planner could not locate the table's current/desired schema.
     */
    private fun categorize(op: DiffOperation): OpCategory = when (op) {
        is DiffOperation.CreateTable,
        is DiffOperation.DropTable,
        is DiffOperation.RenameTable,
        is DiffOperation.AddColumn,
        is DiffOperation.DropColumn,
        is DiffOperation.RenameColumn,
        is DiffOperation.AddIndex,
        is DiffOperation.DropIndex,
        is DiffOperation.CreateView,
        is DiffOperation.ReplaceView,
        is DiffOperation.DropView,
        -> OpCategory.SIMPLE

        is DiffOperation.AlterColumnType,
        is DiffOperation.AlterColumnNullability,
        is DiffOperation.AlterColumnDefault,
        is DiffOperation.AddPrimaryKey,
        is DiffOperation.DropPrimaryKey,
        is DiffOperation.AddConstraint,
        is DiffOperation.DropConstraint,
        -> OpCategory.REBUILD

        is DiffOperation.CreateMaterializedView,
        is DiffOperation.ReplaceMaterializedView,
        is DiffOperation.DropMaterializedView,
        -> OpCategory.MATERIALIZED_VIEW

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
        -> OpCategory.UNSUPPORTED
    }

    private fun renderInlineSimpleOp(op: DiffOperation, ctx: SqliteDiffRenderContext) {
        when (op) {
            is DiffOperation.CreateTable -> SqliteDiffSimpleOps.renderCreateTable(op, ctx)
            is DiffOperation.DropTable -> SqliteDiffSimpleOps.renderDropTable(op, ctx)
            is DiffOperation.RenameTable -> SqliteDiffSimpleOps.renderRenameTable(op, ctx)
            is DiffOperation.AddColumn -> SqliteDiffSimpleOps.renderAddColumn(op, ctx)
            is DiffOperation.DropColumn -> SqliteDiffSimpleOps.renderDropColumn(op, ctx)
            is DiffOperation.RenameColumn -> SqliteDiffSimpleOps.renderRenameColumn(op, ctx)
            is DiffOperation.AddIndex -> SqliteDiffSimpleOps.renderAddIndex(op, ctx)
            is DiffOperation.DropIndex -> SqliteDiffSimpleOps.renderDropIndex(op, ctx)
            is DiffOperation.CreateView -> SqliteDiffSimpleOps.renderCreateView(op, ctx)
            is DiffOperation.ReplaceView -> SqliteDiffSimpleOps.renderReplaceView(op, ctx)
            is DiffOperation.DropView -> SqliteDiffSimpleOps.renderDropView(op, ctx)
            else -> error("Op ${op::class.simpleName} is categorised SIMPLE but renderInlineSimpleOp does not handle it")
        }
    }

    /**
     * Plan-2 §8 D.3b Sub-Slice A: SQLite has no native materialized-view
     * support and §2 explicitly rules out an emulation strategy. The
     * dispatcher blocks any [DiffOperation.CreateMaterializedView] /
     * [DiffOperation.DropMaterializedView] with a dialect-specific
     * diagnostic and an operation blocker.
     */
    private fun blockMaterializedView(op: DiffOperation, ctx: SqliteDiffRenderContext) {
        val name = op.objectRef.rootName
        ctx.skip(
            op,
            "Operation ${op.id} targets materialized view '$name'. SQLite does not natively support " +
                "materialized views; D.3b explicitly carves out an emulation strategy.",
            code = "MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT",
        )
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
    }

    private fun markUnsupported(op: DiffOperation, ctx: SqliteDiffRenderContext) {
        ctx.skip(op, "Operation ${op::class.simpleName} is not in the first SQLite matrix.")
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
    }

    private enum class OpCategory { SIMPLE, REBUILD, MATERIALIZED_VIEW, UNSUPPORTED }
}
