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
        val target = if (ctx.direction == MysqlRenderDirection.UP) op.after else op.before
        ctx.emit(op, updateRowSql(name, target, ctx))
    }

    fun renderDropSequence(op: DiffOperation.DropSequence, ctx: MysqlDiffRenderContext) {
        if (!ensureHelperMode(op, ctx)) return
        val name = op.objectRef.rootName
        if (ctx.direction == MysqlRenderDirection.DOWN) {
            // Inverse: re-emit the bootstrap (if not yet emitted in this
            // direction's pass), then re-INSERT the row and rebuild the
            // bound triggers from the desired-side schema.
            emitBootstrapIfNeeded(op, ctx)
            ctx.emit(
                op,
                MysqlSequenceEmulationTemplates.sequenceSeedSql(name, op.sequence, ctx.sql::quote),
            )
            for (spec in ctx.triggersForSequence(name)) {
                val triggerName = MysqlSequenceNaming.triggerName(spec.tableName, spec.columnName)
                ctx.emit(
                    op,
                    MysqlSequenceEmulationTemplates.sequenceTriggerSql(spec, triggerName, ctx.sql::quote),
                )
            }
            return
        }
        for (spec in ctx.triggersForSequence(name)) {
            val triggerName = MysqlSequenceNaming.triggerName(spec.tableName, spec.columnName)
            ctx.emit(op, "DROP TRIGGER IF EXISTS ${ctx.sql.quote(triggerName)};")
        }
        ctx.emit(op, deleteRowSql(name, ctx))
    }

    /**
     * Defensive-only path. The Mapper's `MysqlObjectRenamePolicy`
     * upgrade in Sub-Slice C re-routes sequence renames to
     * `DropSequence + CreateSequence` with `RenameProvenance`, so a
     * direct `RenameSequence` op reaching this renderer indicates a
     * regression. The implementation stays so the path remains
     * exercised under integration / regression tests.
     */
    fun renderRenameSequence(op: DiffOperation.RenameSequence, ctx: MysqlDiffRenderContext) {
        if (!ensureHelperMode(op, ctx)) return
        val (oldName, newName) = if (ctx.direction == MysqlRenderDirection.UP) {
            op.fromName to op.toName
        } else {
            op.toName to op.fromName
        }
        val oldLiteral = MysqlSequenceSqlCodec.quoteStringLiteral(oldName)
        val newLiteral = MysqlSequenceSqlCodec.quoteStringLiteral(newName)
        ctx.emit(
            op,
            "UPDATE ${ctx.sql.quote(MysqlSequenceNaming.SUPPORT_TABLE)} SET " +
                "${ctx.sql.quote("name")} = $newLiteral " +
                "WHERE ${ctx.sql.quote("name")} = $oldLiteral;",
        )
        // Trigger names are derived from (table, column), not the
        // sequence name, so the trigger NAME does not change — but the
        // body literal `dmg_nextval('<sequence>')` does. Drop+recreate
        // covers both.
        for (spec in ctx.triggersForSequence(oldName)) {
            val triggerName = MysqlSequenceNaming.triggerName(spec.tableName, spec.columnName)
            val newSpec = spec.copy(sequenceName = newName)
            ctx.emit(op, "DROP TRIGGER IF EXISTS ${ctx.sql.quote(triggerName)};")
            ctx.emit(
                op,
                MysqlSequenceEmulationTemplates.sequenceTriggerSql(newSpec, triggerName, ctx.sql::quote),
            )
        }
    }

    private fun ensureHelperMode(op: DiffOperation, ctx: MysqlDiffRenderContext): Boolean {
        if (ctx.options.mysqlNamedSequenceMode == MysqlNamedSequenceMode.HELPER_TABLE) return true
        ctx.skip(
            op,
            "Sequence diff rendering requires --mysql-named-sequences helper_table; current " +
                "mode is ${ctx.options.mysqlNamedSequenceMode}. No SQL is emitted.",
            code = MODE_REQUIRED_CODE,
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
        return false
    }

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
     * UPDATE on the managed declarative fields only. `start` /
     * `next_value` are the runtime state and are not migrated by an
     * `AlterSequence` (see Plan §3.1 + §3.2 Out-of-Scope:
     * `preserveCurrentValue` is its own slice).
     */
    private fun updateRowSql(
        sequenceName: String,
        sequence: SequenceDefinition,
        ctx: MysqlDiffRenderContext,
    ): String {
        val literal = MysqlSequenceSqlCodec.quoteStringLiteral(sequenceName)
        val cycle = if (sequence.cycle == true) 1 else 0
        val sets = listOf(
            "${ctx.sql.quote("increment_by")} = ${sequence.increment ?: 1L}",
            "${ctx.sql.quote("min_value")} = ${sequence.minValue?.toString() ?: "NULL"}",
            "${ctx.sql.quote("max_value")} = ${sequence.maxValue?.toString() ?: "NULL"}",
            "${ctx.sql.quote("cycle_enabled")} = $cycle",
            "${ctx.sql.quote("cache_size")} = ${sequence.cache?.toString() ?: "NULL"}",
        ).joinToString(", ")
        return "UPDATE ${ctx.sql.quote(MysqlSequenceNaming.SUPPORT_TABLE)} SET $sets " +
            "WHERE ${ctx.sql.quote("name")} = $literal;"
    }
}
