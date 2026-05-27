package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.Reversibility
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.CheckPreflightGate
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.sqliteContext
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.PlannerBlockerClassifier

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
        if (blockExcludeConstraintsInRebuild(table, bucket, current, desired, ctx)) return
        if (blockCheckPreflightFailures(bucket, ctx)) return
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
        val upCatalog = ctx.options.sqliteContext?.liveCatalog
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
            OpCategory.TRIGGER -> renderTriggerOp(op, ctx)
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

        is DiffOperation.CreateTrigger,
        is DiffOperation.ReplaceTrigger,
        is DiffOperation.DropTrigger,
        -> OpCategory.TRIGGER

        // F.4 Sub-Slice C: `SqliteObjectRenamePolicy` returns
        // `DropCreateFallback` for views and triggers (when both body
        // hashes are known and equal) and `Blocked` for every other
        // kind. The Mapper therefore emits `Drop*`+`Create*` with
        // `renameProvenance` for views/triggers, or an
        // `OBJECT_RENAME_UNSUPPORTED` blocker for routines/sequences/
        // materialized views — no `Rename*` subtype ever reaches this
        // renderer under the contract. The defensive `UNSUPPORTED`
        // routing exists so a future planner regression that lets one
        // through surfaces as `DIALECT_UNSUPPORTED_OPERATION` instead
        // of being silently emitted as garbled SQL.
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
        is DiffOperation.RenameView,
        is DiffOperation.RenameTrigger,
        is DiffOperation.RenameFunction,
        is DiffOperation.RenameProcedure,
        is DiffOperation.RenameSequence,
        // SQLite has no sequence concept (the dialect was carved out
        // of the entire E.3 sequence workstream — see file-level
        // KDoc "Out of first matrix entirely"). Every sequence-related
        // op — including 0.9.7's `AlterSequenceCurrentValue`
        // preserve-current-value follow-up — is permanently routed
        // to UNSUPPORTED. This will change only when (and if)
        // `docs/planning/open/sqlite-sequence-emulation-plan.md`
        // lands a SQLite emulation; until then `DIALECT_UNSUPPORTED_OPERATION`
        // is the correct end state, not a placeholder.
        is DiffOperation.AlterSequenceCurrentValue,
        -> OpCategory.UNSUPPORTED
    }

    private fun renderTriggerOp(op: DiffOperation, ctx: SqliteDiffRenderContext) {
        when (op) {
            is DiffOperation.CreateTrigger -> SqliteTriggerDdlHelper.renderCreateTrigger(op, ctx)
            is DiffOperation.ReplaceTrigger -> SqliteTriggerDdlHelper.renderReplaceTrigger(op, ctx)
            is DiffOperation.DropTrigger -> SqliteTriggerDdlHelper.renderDropTrigger(op, ctx)
            else -> error("Op ${op::class.simpleName} is categorised TRIGGER but renderTriggerOp does not handle it")
        }
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

    /**
     * F.5 Sub-Slice D: SQLite has no syntactic equivalent for an
     * `EXCLUDE` constraint. A rebuild bucket that touches one — either
     * by mutating it (Add/Drop EXCLUDE in the op list) or by carrying
     * one through silently (EXCLUDE present in current or desired
     * table, no op mentioning it) — would otherwise drop the
     * constraint without notice because `constraintLine` returns null
     * for EXCLUDE.
     *
     * The dispatcher blocks the entire bucket with
     * `EXCLUDE_NOT_SUPPORTED_BY_DIALECT` /
     * `DIALECT_UNSUPPORTED_OPERATION` before invoking the rebuild
     * renderer. Returns `true` when the bucket was blocked.
     */
    private fun blockExcludeConstraintsInRebuild(
        table: String,
        bucket: List<DiffOperation>,
        current: TableDefinition,
        desired: TableDefinition,
        ctx: SqliteDiffRenderContext,
    ): Boolean {
        val opSources = bucket
            .mapNotNull { op ->
                when (op) {
                    is DiffOperation.AddConstraint -> op.constraint.takeIf { it.type == ConstraintType.EXCLUDE }?.let { op to it }
                    is DiffOperation.DropConstraint -> op.constraint.takeIf { it.type == ConstraintType.EXCLUDE }?.let { op to it }
                    else -> null
                }
            }
        val schemaExcludes = (current.constraints + desired.constraints)
            .filter { it.type == ConstraintType.EXCLUDE }
            .map { it.name }
            .distinct()
        if (opSources.isEmpty() && schemaExcludes.isEmpty()) return false

        val message = buildString {
            append("SQLite has no EXCLUDE constraint syntax (PostgreSQL-only feature); ")
            append("the rebuild for `").append(table).append("` cannot preserve or apply it. ")
            if (schemaExcludes.isNotEmpty()) {
                append("Existing EXCLUDE constraint(s) on the table: ")
                append(schemaExcludes.joinToString(", "))
                append(". ")
            }
            append("Re-model the invariant via UNIQUE + CHECK, or move it into the application.")
        }
        for (op in bucket) {
            ctx.skip(op, message, code = PlannerBlockerClassifier.EXCLUDE_NOT_SUPPORTED_BY_DIALECT_CODE)
        }
        ctx.addBlocker(
            MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION,
            operationIds = bucket.map { it.id }.toSet(),
        )
        return true
    }

    /**
     * F.5 Sub-Slice E.3 (2026-05-19): block the whole rebuild bucket
     * when any `AddConstraint(CHECK)` op in it has a FAILED or
     * PROBE_RUNTIME_ERROR preflight declaration in
     * `options.checkPreflights`. Returns `true` when the bucket was
     * blocked.
     *
     * SQLite's rebuild is all-or-nothing: the new CHECK clause lands
     * in `CREATE TABLE <temp>` and the INSERT-SELECT fires immediately
     * against the source rows. There's no per-op render path, so the
     * gate has to deny the whole bucket — same shape as the
     * EXCLUDE block. Op-by-op skipping with bucket-level blocker
     * mirrors that contract.
     */
    private fun blockCheckPreflightFailures(
        bucket: List<DiffOperation>,
        ctx: SqliteDiffRenderContext,
    ): Boolean {
        if (ctx.options.checkPreflights.isEmpty()) return false
        val checkAdds = bucket.filterIsInstance<DiffOperation.AddConstraint>()
            .filter { it.constraint.type == ConstraintType.CHECK }
        if (checkAdds.isEmpty()) return false
        val blockingDecisions = checkAdds.mapNotNull { op ->
            when (val decision = CheckPreflightGate.decide(op.id, ctx.options.checkPreflights)) {
                CheckPreflightGate.Decision.Proceed -> null
                is CheckPreflightGate.Decision.Block -> op to decision
            }
        }
        if (blockingDecisions.isEmpty()) return false
        for (op in bucket) {
            // Surface the most specific message: prefer the decision
            // for this op's own id, fall back to the first blocking
            // decision in the bucket so non-CHECK ops carry a clear
            // diagnostic too.
            val ownDecision = blockingDecisions.firstOrNull { it.first.id == op.id }?.second
                ?: blockingDecisions.first().second
            ctx.skip(op, ownDecision.message, code = ownDecision.code)
        }
        ctx.addBlocker(
            blockingDecisions.first().second.reason,
            operationIds = bucket.map { it.id }.toSet(),
        )
        return true
    }

    private enum class OpCategory { SIMPLE, REBUILD, TRIGGER, MATERIALIZED_VIEW, UNSUPPORTED }
}
