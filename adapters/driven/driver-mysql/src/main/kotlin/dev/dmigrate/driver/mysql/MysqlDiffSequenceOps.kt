package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.driver.MysqlNamedSequenceMode
import dev.dmigrate.driver.MysqlSequenceCanonicityGate
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
     * Sub-Slice F: `AlterSequence` whose `before → after` delta only
     * touches the runtime-state (`start` / `next_value`) emits this
     * INFO-severity diagnostic and is skipped without a blocker. The
     * actual runtime-state migration lives in the
     * `preserveCurrentValue` cross-dialect follow-up slice; for now
     * the renderer documents that the op was acknowledged but
     * deferred.
     */
    const val RUNTIME_STATE_NO_OP_CODE: String = "MYSQL_SEQUENCE_RUNTIME_STATE_NO_OP"

    fun renderCreateSequence(op: DiffOperation.CreateSequence, ctx: MysqlDiffRenderContext) {
        if (!ensureHelperMode(op, ctx)) return
        if (canonicityBlocks(op, MysqlSequenceCanonicityGate.OpIntent.CREATE, ctx)) return
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
        if (canonicityBlocks(op, MysqlSequenceCanonicityGate.OpIntent.ALTER, ctx)) return
        val name = op.objectRef.rootName
        val (source, target) = if (ctx.direction == MysqlRenderDirection.UP) {
            op.before to op.after
        } else {
            op.after to op.before
        }
        val sql = updateRowSql(name, source, target, ctx)
        if (sql == null) {
            // Sub-Slice F: `SequenceDiff` flagged only the runtime-
            // state field (`start` / `next_value`) — `AlterSequence`
            // was emitted by the Mapper, but the managed-fields delta
            // is empty. Skip with an INFO-severity diagnostic so the
            // report tracks the op without surfacing a blocker. The
            // actual runtime-state migration is the `preserveCurrent-
            // Value` cross-dialect follow-up.
            ctx.skip(
                op,
                "AlterSequence for `$name` carries only a runtime-state " +
                    "change (start / next_value); the managed-fields delta is empty. " +
                    "Deferred to the `preserveCurrentValue` follow-up slice " +
                    "(`docs/planning/in-progress/ImpPlan-0.9.7-sequence-preserve-current-value.md`); " +
                    "no SQL emitted in this slice.",
                code = RUNTIME_STATE_NO_OP_CODE,
                severity = dev.dmigrate.core.diff.migration.DiffDiagnostic.Severity.INFO,
            )
            return
        }
        ctx.emit(op, sql)
    }

    fun renderDropSequence(op: DiffOperation.DropSequence, ctx: MysqlDiffRenderContext) {
        if (!ensureHelperMode(op, ctx)) return
        if (canonicityBlocks(op, MysqlSequenceCanonicityGate.OpIntent.DROP, ctx)) return
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
     * Defensive regression path. Sub-Slice C upgraded
     * `MysqlObjectRenamePolicy.classify(SEQUENCE, ...)` from
     * `RenameSupport.Blocked` to `RenameSupport.DropCreateFallback`,
     * so the Mapper now decomposes sequence renames into
     * `DropSequence(from) + CreateSequence(to)` with
     * `RenameProvenance` — a `RenameSequence` op should NOT reach
     * this renderer under normal flow.
     *
     * If one does land here (planner regression, custom plan,
     * artefact replay), the renderer emits a best-effort sequence:
     *
     * - `UPDATE dmg_sequences SET name = '<to>' WHERE name = '<from>'`
     *   to rename the helper-table row.
     * - For every column-bound trigger (`SequenceNextVal('<from>')`)
     *   in the source-side schema: `DROP TRIGGER IF EXISTS …` +
     *   `CREATE TRIGGER` with the body literal rewritten to
     *   `dmg_nextval('<to>')`. Trigger names are derived from
     *   `(table, column)` per [MysqlSequenceNaming.triggerName] and
     *   therefore stay stable across the rename — only the body
     *   literal changes.
     *
     * Side picks: UP reads bindings from `currentSchema` (where
     * `<from>` is still the active name), DOWN reads them from
     * `desiredSchema` (where `<to>` is the active name post-Up).
     */
    fun renderRenameSequence(op: DiffOperation.RenameSequence, ctx: MysqlDiffRenderContext) {
        if (!ensureHelperMode(op, ctx)) return
        // Rename emits an UPDATE on the existing row → ALTER intent for
        // the drift gate (the row must exist and not have drifted).
        if (canonicityBlocks(op, MysqlSequenceCanonicityGate.OpIntent.ALTER, ctx)) return
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
        // For `RenameSequence` the binding lives in `currentSchema`
        // for UP (old name is the active name pre-Up) and
        // `desiredSchema` for DOWN (new name is the active name
        // post-Up; DOWN reverts back). The trigger NAME is derived
        // from (table, column) so it does not change — only the body
        // literal `dmg_nextval('<sequence>')` does. Drop + recreate.
        val triggerSide = if (ctx.direction == MysqlRenderDirection.UP) {
            SchemaSide.CURRENT
        } else {
            SchemaSide.DESIRED
        }
        for (spec in ctx.triggersForSequence(oldName, triggerSide)) {
            val triggerName = MysqlSequenceNaming.triggerName(spec.tableName, spec.columnName)
            val newSpec = spec.copy(sequenceName = newName)
            ctx.emit(op, "DROP TRIGGER IF EXISTS ${ctx.sql.quote(triggerName)};")
            ctx.emit(
                op,
                MysqlSequenceEmulationTemplates.sequenceTriggerSql(newSpec, triggerName, ctx.sql::quote),
            )
        }
    }

    /**
     * Sub-Slice F: emit the helper-table support trigger for a
     * column whose default is a `SequenceNextVal`. Called by
     * `MysqlDiffTableOps` after `CREATE TABLE` / `ADD COLUMN` or an
     * `AlterColumnDefault` that introduces a sequence default.
     *
     * Includes the mode gate (`E056` if not `HELPER_TABLE`) and the
     * bootstrap-once latch via [MysqlSequenceMigrationContext]. The
     * caller is expected to call [requireHelperModeForColumnDefault]
     * BEFORE emitting the column-bearing statement when at least one
     * column carries a sequence default; this method only emits the
     * trigger side (idempotent `DROP TRIGGER IF EXISTS` +
     * `CREATE TRIGGER` so a re-run does not collide with a leftover
     * trigger).
     */
    fun emitSupportTriggerForColumn(
        op: DiffOperation,
        ctx: MysqlDiffRenderContext,
        tableName: String,
        columnName: String,
        sequenceName: String,
    ) {
        emitBootstrapIfNeeded(op, ctx)
        val spec = MysqlSequenceTriggerSpec(tableName, columnName, sequenceName)
        val triggerName = MysqlSequenceNaming.triggerName(tableName, columnName)
        ctx.emit(op, "DROP TRIGGER IF EXISTS ${ctx.sql.quote(triggerName)};")
        ctx.emit(
            op,
            MysqlSequenceEmulationTemplates.sequenceTriggerSql(spec, triggerName, ctx.sql::quote),
        )
    }

    /**
     * Sub-Slice F: emit `DROP TRIGGER IF EXISTS` for a column whose
     * `SequenceNextVal` default is being removed or replaced.
     */
    fun emitDropSupportTriggerForColumn(
        op: DiffOperation,
        ctx: MysqlDiffRenderContext,
        tableName: String,
        columnName: String,
    ) {
        val triggerName = MysqlSequenceNaming.triggerName(tableName, columnName)
        ctx.emit(op, "DROP TRIGGER IF EXISTS ${ctx.sql.quote(triggerName)};")
    }

    /**
     * Sub-Slice F: mode gate that table-level renderers call when at
     * least one column carries a `SequenceNextVal` default. Returns
     * `true` when the renderer may proceed with the column-bearing
     * statement; returns `false` after emitting an `E056` block
     * (no SQL must follow on the same op).
     */
    fun requireHelperModeForColumnDefault(op: DiffOperation, ctx: MysqlDiffRenderContext): Boolean =
        ensureHelperMode(op, ctx)

    /**
     * E.3 MySQL Sequence Drift-Check Sub-Slice D (2026-05-20): consults
     * the live-probe declarations on [ctx.options.mysqlSequenceCanonicity]
     * for [op] and routes the per-declaration [MysqlSequenceCanonicityGate]
     * decision into the render context:
     *
     * - [MysqlSequenceCanonicityGate.Decision.Proceed]: continue to the
     *   next declaration; if every declaration is Proceed, return
     *   `false` and the caller emits SQL.
     * - [MysqlSequenceCanonicityGate.Decision.Info]: emit an INFO-level
     *   diagnostic but keep iterating — render still proceeds.
     * - [MysqlSequenceCanonicityGate.Decision.Block]: skip the op,
     *   add the gate's blocker, return `true` immediately (first
     *   Block wins; subsequent declarations are not consulted).
     *
     * An op whose probe set is empty proceeds unconditionally — that's
     * the file-target / `--plan-only` / pre-probe path where no
     * declarations were threaded in. The dedicated NOT_RUN_FILE_TARGET
     * / NOT_RUN_POLICY declarations are emitted by the planner /
     * stage (Sub-Slice E) and arrive here as Info-routed
     * declarations, NOT as an empty list.
     */
    private fun canonicityBlocks(
        op: DiffOperation,
        intent: MysqlSequenceCanonicityGate.OpIntent,
        ctx: MysqlDiffRenderContext,
    ): Boolean {
        val declarations = ctx.options.mysqlSequenceCanonicity.filter { it.operationId == op.id }
        if (declarations.isEmpty()) return false
        for (declaration in declarations) {
            when (val decision = MysqlSequenceCanonicityGate.decide(declaration, intent)) {
                MysqlSequenceCanonicityGate.Decision.Proceed -> continue
                is MysqlSequenceCanonicityGate.Decision.Info -> ctx.info(op, decision.message, decision.code)
                is MysqlSequenceCanonicityGate.Decision.Block -> {
                    ctx.skip(op, decision.message, code = decision.code)
                    ctx.addBlocker(decision.reason, operationIds = setOf(op.id))
                    return true
                }
            }
        }
        return false
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
        // Sub-Slice F: DROP-then-CREATE for each routine, in
        // separate statements. The drop keeps the bootstrap
        // idempotent; the create is the `DELIMITER //`-wrapped
        // body. Keeping them as separate `emit()` calls preserves
        // the inverse-pass parser's `startsWith("DELIMITER //")`
        // contract.
        ctx.emit(op, MysqlSequenceEmulationTemplates.dropNextvalRoutineSql(ctx.sql::quote))
        ctx.emit(op, MysqlSequenceEmulationTemplates.nextvalRoutineSql(ctx.sql::quote))
        ctx.emit(op, MysqlSequenceEmulationTemplates.dropSetvalRoutineSql(ctx.sql::quote))
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
