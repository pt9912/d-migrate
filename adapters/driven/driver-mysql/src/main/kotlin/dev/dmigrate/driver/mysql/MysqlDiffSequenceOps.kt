package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.driver.MysqlNamedSequenceMode
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * E.3 MySQL Sequence-Diff Sub-Slice B: per-operation renderers for
 * `CreateSequence` / `AlterSequence` / `DropSequence` /
 * `RenameSequence` against the helper-table emulation introduced in
 * 0.9.4 (`docs/planning/done/mysql-sequence-emulation-plan.md`).
 *
 * The renderer is stateful only through
 * [MysqlDiffRenderContext.sequenceMigration]: a per-direction latch
 * that emits the helper-table bootstrap (`dmg_sequences` table +
 * `dmg_nextval` / `dmg_setval` routines) at most once. The actual
 * SQL templates live in [MysqlSequenceEmulationTemplates] so the
 * DDL-Generator pipeline (full schema emission) and the diff path
 * share one source of truth.
 *
 * Per-subtype shape:
 *
 * | Op                          | UP                                         | DOWN                                       |
 * |-----------------------------|--------------------------------------------|--------------------------------------------|
 * | `CreateSequence`            | bootstrap (if first) + `INSERT` row        | `DELETE` row                               |
 * | `AlterSequence`             | `UPDATE` managed fields (`op.after`)       | `UPDATE` managed fields (`op.before`)      |
 * | `DropSequence`              | `DROP TRIGGER IF EXISTS` per bound col + `DELETE` row | bootstrap (if first) + `INSERT` row + recreate bound triggers |
 * | `RenameSequence` (defensive) | `UPDATE name` + rebuild bound triggers      | `UPDATE name` + rebuild bound triggers (swapped) |
 *
 * Triggers are NOT created by `CreateSequence` itself — column-level
 * ops (`AddColumn` / `AlterColumnDefault` with a `SequenceNextVal`
 * default) own that side of the contract. `DropSequence` removes
 * any trigger that was bound to the dropped sequence at planning
 * time so the catalog does not leak `dmg_nextval('<deleted>')`
 * references.
 *
 * Mode gate: when `MysqlNamedSequenceMode != HELPER_TABLE` every
 * sequence op is blocked with diagnostic code `E056` →
 * `MANUAL_ACTION_REQUIRED`, no SQL is emitted. The guard lives in
 * this renderer (not in `MysqlDdlGenerator`) so the diff dispatcher
 * stays uniform across modes.
 *
 * Plan-Doc:
 * `docs/planning/in-progress/ImpPlan-0.9.7-mysql-sequence-diff-migration.md`
 * §5.2 (Op-Subtyp-Mapping) + §6 Sub-Slice B.
 */
internal object MysqlDiffSequenceOps {

    /** Diagnostic code emitted when the renderer is invoked outside `HELPER_TABLE` mode. */
    const val MODE_REQUIRED_CODE: String = "E056"

    /**
     * Diagnostic code emitted when a `RenameSequence` op slips
     * through to the renderer while `MysqlObjectRenamePolicy` still
     * marks sequence renames as `Blocked`. The op is short-circuited
     * with `MANUAL_ACTION_REQUIRED` (no SQL) so a planner regression
     * surfaces as a clear blocker instead of writing to
     * `dmg_sequences` against the F.4 contract. Sub-Slice C upgrades
     * the policy to `DropCreateFallback` and re-enables the SQL
     * path here.
     */
    const val RENAME_BLOCKED_CODE: String = "MYSQL_RENAME_SEQUENCE_DEFENSIVE_BLOCK"

    fun renderCreateSequence(op: DiffOperation.CreateSequence, ctx: MysqlDiffRenderContext) {
        if (!ensureHelperMode(op, ctx)) return
        val name = op.objectRef.rootName
        if (ctx.direction == MysqlRenderDirection.DOWN) {
            // Inverse of UP `INSERT` — no bootstrap teardown (the helper
            // table is shared infrastructure and stays put).
            ctx.emit(op, deleteRowSql(name, ctx))
            return
        }
        emitBootstrapIfNeeded(op, ctx)
        ctx.emit(
            op,
            MysqlSequenceEmulationTemplates.sequenceSeedSql(name, op.sequence, ctx.sql::quote),
        )
    }

    fun renderAlterSequence(op: DiffOperation.AlterSequence, ctx: MysqlDiffRenderContext) {
        if (!ensureHelperMode(op, ctx)) return
        val name = op.objectRef.rootName
        val (source, target) = if (ctx.direction == MysqlRenderDirection.UP) {
            op.before to op.after
        } else {
            op.after to op.before
        }
        val sql = updateRowSql(name, source, target, ctx)
            ?: run {
                // No managed field actually differs between before /
                // after — `AlterSequence` was emitted by the Mapper
                // because the [SequenceDiff] flagged a `start` /
                // runtime-state change, which this slice leaves to
                // the `preserveCurrentValue` follow-up. Skip the
                // UPDATE emit; the op is a no-op for this direction.
                return
            }
        ctx.emit(op, sql)
    }

    fun renderDropSequence(op: DiffOperation.DropSequence, ctx: MysqlDiffRenderContext) {
        if (!ensureHelperMode(op, ctx)) return
        val name = op.objectRef.rootName
        // Both directions read bound triggers from `currentSchema` —
        // that's the pre-Up state where the column→sequence bindings
        // still live. `desiredSchema` is post-Up (sequence removed,
        // bindings removed) and would yield an empty trigger set,
        // which would silently break the Down rollback contract.
        val boundTriggers = ctx.triggersForSequence(name, SchemaSide.CURRENT)
        if (ctx.direction == MysqlRenderDirection.DOWN) {
            // Inverse: re-emit the bootstrap (if not yet emitted in
            // this direction's pass), then re-INSERT the row and
            // rebuild every trigger that was dropped during UP.
            emitBootstrapIfNeeded(op, ctx)
            ctx.emit(
                op,
                MysqlSequenceEmulationTemplates.sequenceSeedSql(name, op.sequence, ctx.sql::quote),
            )
            for (spec in boundTriggers) {
                val triggerName = MysqlSequenceNaming.triggerName(spec.tableName, spec.columnName)
                ctx.emit(
                    op,
                    MysqlSequenceEmulationTemplates.sequenceTriggerSql(spec, triggerName, ctx.sql::quote),
                )
            }
            return
        }
        for (spec in boundTriggers) {
            val triggerName = MysqlSequenceNaming.triggerName(spec.tableName, spec.columnName)
            ctx.emit(op, "DROP TRIGGER IF EXISTS ${ctx.sql.quote(triggerName)};")
        }
        ctx.emit(op, deleteRowSql(name, ctx))
    }

    /**
     * Defensive-only path. The Mapper's
     * `MysqlObjectRenamePolicy.classify(SEQUENCE, ...)` still
     * produces `RenameSupport.Blocked` for MySQL today, so a
     * `RenameSequence` op reaching this renderer is a planner
     * regression. Until Sub-Slice C upgrades the policy to
     * `DropCreateFallback`, the defensive path short-circuits with
     * `MANUAL_ACTION_REQUIRED` so the regression surfaces as a
     * clear blocker — emitting the actual `UPDATE dmg_sequences`
     * SQL would silently bypass the F.4 contract that put
     * sequence renames out of scope.
     */
    fun renderRenameSequence(op: DiffOperation.RenameSequence, ctx: MysqlDiffRenderContext) {
        if (!ensureHelperMode(op, ctx)) return
        ctx.skip(
            op,
            "MySQL sequence rename is currently routed through " +
                "`MysqlObjectRenamePolicy = Blocked`; a `RenameSequence` op reaching the " +
                "renderer is a planner regression. Sub-Slice C of " +
                "ImpPlan-0.9.7-mysql-sequence-diff-migration upgrades the policy to " +
                "`DropCreateFallback`, after which this op type is rerouted to " +
                "DropSequence + CreateSequence with RenameProvenance. " +
                "Hint: re-plan with the policy upgrade landed.",
            code = RENAME_BLOCKED_CODE,
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
    }

    private fun ensureHelperMode(op: DiffOperation, ctx: MysqlDiffRenderContext): Boolean {
        if (ctx.options.mysqlNamedSequenceMode == MysqlNamedSequenceMode.HELPER_TABLE) return true
        ctx.skip(
            op,
            "Sequence diff rendering requires --mysql-named-sequences helper_table; current " +
                "mode is ${ctx.options.mysqlNamedSequenceMode}. No SQL is emitted. " +
                "Hint: add --mysql-named-sequences helper_table to enable sequence emulation.",
            code = MODE_REQUIRED_CODE,
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
        return false
    }

    /**
     * Emits the three helper-table bootstrap statements as a single
     * block (table + `dmg_nextval` + `dmg_setval`) before the first
     * row-level INSERT in this direction, then flips
     * [MysqlSequenceMigrationContext.markBootstrapEmitted].
     *
     * Note on order vs the DDL-Generator pipeline: production
     * (`MysqlDdlGenerator`) interleaves the bootstrap with row seed
     * across three pipeline stages — `generateSequences()` emits the
     * table + per-sequence INSERTs, `generateSupportFunctions()`
     * emits the routines, `generateSupportTriggers()` emits column
     * triggers. The diff path keeps the bootstrap atomic and emits
     * it BEFORE the first INSERT instead. The two SQL shapes are
     * functionally equivalent (a row INSERT into `dmg_sequences`
     * does not depend on the routines existing), but golden
     * snapshots that compare full-schema-emit output against
     * from-empty diff-migration output will see the routines in a
     * different relative position. This trade-off is deliberate:
     * tracking a multi-stage finalisation across diff render passes
     * is more invasive than the runtime gain is worth.
     */
    private fun emitBootstrapIfNeeded(op: DiffOperation, ctx: MysqlDiffRenderContext) {
        if (!ctx.sequenceMigration.needsBootstrap()) return
        ctx.emit(op, MysqlSequenceEmulationTemplates.supportTableSql(ctx.sql::quote))
        ctx.emit(op, MysqlSequenceEmulationTemplates.nextvalRoutineSql(ctx.sql::quote))
        ctx.emit(op, MysqlSequenceEmulationTemplates.setvalRoutineSql(ctx.sql::quote))
        ctx.sequenceMigration.markBootstrapEmitted()
    }

    private fun deleteRowSql(sequenceName: String, ctx: MysqlDiffRenderContext): String {
        val literal = MysqlSequenceSqlCodec.quoteStringLiteral(sequenceName)
        return "DELETE FROM ${ctx.sql.quote(MysqlSequenceNaming.SUPPORT_TABLE)} " +
            "WHERE ${ctx.sql.quote("name")} = $literal;"
    }

    /**
     * UPDATE on the managed declarative fields ONLY for fields that
     * actually differ between [source] and [target]. Per Plan §5.2
     * the SET list is the delta, not the full record — so an
     * `AlterSequence` that only flips `cycle` emits a one-field
     * UPDATE instead of overwriting all five.
     *
     * `start` / `next_value` are the runtime state and are never
     * migrated by `AlterSequence` (Plan §3.2 Out-of-Scope:
     * `preserveCurrentValue` is its own slice). Returns `null` when
     * the delta is empty — the caller suppresses the emit so a
     * runtime-state-only diff does not produce a no-op UPDATE.
     */
    private fun updateRowSql(
        sequenceName: String,
        source: SequenceDefinition,
        target: SequenceDefinition,
        ctx: MysqlDiffRenderContext,
    ): String? {
        val literal = MysqlSequenceSqlCodec.quoteStringLiteral(sequenceName)
        val sets = mutableListOf<String>()
        if (source.increment != target.increment) {
            sets += "${ctx.sql.quote("increment_by")} = ${target.increment}"
        }
        if (source.minValue != target.minValue) {
            sets += "${ctx.sql.quote("min_value")} = ${target.minValue?.toString() ?: "NULL"}"
        }
        if (source.maxValue != target.maxValue) {
            sets += "${ctx.sql.quote("max_value")} = ${target.maxValue?.toString() ?: "NULL"}"
        }
        if (source.cycle != target.cycle) {
            sets += "${ctx.sql.quote("cycle_enabled")} = ${if (target.cycle) 1 else 0}"
        }
        if (source.cache != target.cache) {
            sets += "${ctx.sql.quote("cache_size")} = ${target.cache?.toString() ?: "NULL"}"
        }
        if (sets.isEmpty()) return null
        return "UPDATE ${ctx.sql.quote(MysqlSequenceNaming.SUPPORT_TABLE)} SET " +
            sets.joinToString(", ") +
            " WHERE ${ctx.sql.quote("name")} = $literal;"
    }
}
