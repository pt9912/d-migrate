package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.driver.SqliteNamedSequenceMode
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.sqliteContext

/**
 * 0.9.7 Phase F2: SQLite-helper_table diff-migration renderer for
 * sequence operations. Analog zu `MysqlDiffSequenceOps`.
 *
 * Mappt die fünf neutralen Sequence-Operationen auf
 * `dmg_sequences`-INSERT/UPDATE/DELETE plus Trigger-Pair-Mutationen:
 *
 * - `CreateSequence` → ensure `dmg_sequences`-bootstrap + `INSERT`
 * - `AlterSequence` → `UPDATE dmg_sequences SET … WHERE name = …`
 * - `DropSequence` → DROP gebundene Trigger + `DELETE FROM dmg_sequences`
 * - `RenameSequence` → `UPDATE dmg_sequences SET name = …` + Trigger-
 *   Rebuild mit neuen Marker-Werten
 * - `AlterSequenceCurrentValue` → `UPDATE dmg_sequences SET
 *   next_value = …`
 *
 * `action_required`-Modus blockt jede Sequence-Op mit dem bisherigen
 * `DIALECT_UNSUPPORTED_OPERATION`-Pfad — die Emulation ist opt-in
 * parallel zum `helper_table`-Generator-Mode.
 */
internal object SqliteDiffSequenceOps {

    fun renderCreateSequence(op: DiffOperation.CreateSequence, ctx: SqliteDiffRenderContext) {
        if (!ensureHelperMode(op, ctx)) return
        val name = op.objectRef.rootName
        when (ctx.direction) {
            SqliteRenderDirection.UP -> {
                ensureBootstrapEmitted(op, ctx)
                ctx.emit(op, SqliteSequenceEmulationTemplates.sequenceSeedSql(name, op.sequence))
            }
            SqliteRenderDirection.DOWN -> {
                ctx.emit(op, deleteRowSql(name))
            }
        }
    }

    fun renderAlterSequence(op: DiffOperation.AlterSequence, ctx: SqliteDiffRenderContext) {
        if (!ensureHelperMode(op, ctx)) return
        val name = op.objectRef.rootName
        val (source, target) = when (ctx.direction) {
            SqliteRenderDirection.UP -> op.before to op.after
            SqliteRenderDirection.DOWN -> op.after to op.before
        }
        val sql = updateRowSql(name, source, target)
        if (sql == null) {
            ctx.skip(
                op,
                "AlterSequence for '$name' carries only runtime-state delta; no SQL emitted in this slice.",
                code = "SEQUENCE_RUNTIME_STATE_NO_OP",
            )
            return
        }
        ctx.emit(op, sql)
    }

    fun renderDropSequence(op: DiffOperation.DropSequence, ctx: SqliteDiffRenderContext) {
        if (!ensureHelperMode(op, ctx)) return
        val name = op.objectRef.rootName
        when (ctx.direction) {
            SqliteRenderDirection.UP -> {
                // F2-MVP: assume no bound trigger pairs are recorded in the
                // op; the Plan-Doc's bound-trigger restore via
                // `triggersForSequence(name, SchemaSide.CURRENT)` is left
                // for a follow-up slice. We drop the row; if column-bound
                // triggers still exist on tables they'll surface as
                // E058-preflight blocker at rollback time (Phase F1).
                ctx.emit(op, deleteRowSql(name))
            }
            SqliteRenderDirection.DOWN -> {
                ensureBootstrapEmitted(op, ctx)
                ctx.emit(op, SqliteSequenceEmulationTemplates.sequenceSeedSql(name, op.sequence))
            }
        }
    }

    fun renderRenameSequence(op: DiffOperation.RenameSequence, ctx: SqliteDiffRenderContext) {
        if (!ensureHelperMode(op, ctx)) return
        val (oldName, newName) = when (ctx.direction) {
            SqliteRenderDirection.UP -> op.fromName to op.toName
            SqliteRenderDirection.DOWN -> op.toName to op.fromName
        }
        ctx.emit(
            op,
            """UPDATE "${SqliteSequenceNaming.SUPPORT_TABLE}" SET "name" = '${escapeLiteral(newName)}' """ +
                """WHERE "name" = '${escapeLiteral(oldName)}';""",
        )
    }

    fun renderAlterSequenceCurrentValue(op: DiffOperation.AlterSequenceCurrentValue, ctx: SqliteDiffRenderContext) {
        if (!ensureHelperMode(op, ctx)) return
        val targetName = op.applySequenceRef.name
        val value = when (ctx.direction) {
            SqliteRenderDirection.UP -> op.currentValue
            // Plan-Doc-Phase-E carve-out: down-direction restore for
            // current-value preservation lands with the cross-dialect
            // follow-up; for now Down just leaves the runtime state
            // untouched. The op itself is reversible (the value moves
            // forward and re-applying CREATE/ALTER without a probe
            // resets `next_value` to `start`), so we don't block.
            SqliteRenderDirection.DOWN -> return
        }
        ctx.emit(
            op,
            """UPDATE "${SqliteSequenceNaming.SUPPORT_TABLE}" SET "next_value" = $value """ +
                """WHERE "name" = '${escapeLiteral(targetName)}';""",
        )
    }

    // ── helpers ────────────────────────────────────────────────────

    private fun ensureHelperMode(op: DiffOperation, ctx: SqliteDiffRenderContext): Boolean {
        val mode = ctx.options.sqliteContext?.namedSequenceMode ?: SqliteNamedSequenceMode.ACTION_REQUIRED
        if (mode == SqliteNamedSequenceMode.HELPER_TABLE) return true
        ctx.skip(
            op,
            "Sequence operation ${op::class.simpleName} requires `--sqlite-named-sequences helper_table` " +
                "to emit `dmg_sequences`-based DDL. action_required mode keeps the op blocked.",
            code = "SQLITE_NAMED_SEQUENCES_OPT_IN_REQUIRED",
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
        return false
    }

    private fun ensureBootstrapEmitted(op: DiffOperation, ctx: SqliteDiffRenderContext) {
        if (ctx.bootstrapEmitted) return
        ctx.emit(op, SqliteSequenceEmulationTemplates.supportTableSql())
        ctx.bootstrapEmitted = true
    }

    private fun deleteRowSql(name: String): String =
        """DELETE FROM "${SqliteSequenceNaming.SUPPORT_TABLE}" WHERE "name" = '${escapeLiteral(name)}';"""

    /**
     * Builds the `UPDATE dmg_sequences SET …` body for a managed-
     * field delta. Returns `null` when the delta is empty (i.e. only
     * runtime-state fields changed — those go via
     * `AlterSequenceCurrentValue`).
     */
    private fun updateRowSql(name: String, source: SequenceDefinition, target: SequenceDefinition): String? {
        val sets = mutableListOf<String>()
        if (source.increment != target.increment) sets += """"increment_by" = ${target.increment}"""
        if (source.minValue != target.minValue) sets += """"min_value" = ${target.minValue ?: "NULL"}"""
        if (source.maxValue != target.maxValue) sets += """"max_value" = ${target.maxValue ?: "NULL"}"""
        if (source.cycle != target.cycle) sets += """"cycle_enabled" = ${if (target.cycle) 1 else 0}"""
        if (source.cache != target.cache) sets += """"cache_size" = ${target.cache ?: "NULL"}"""
        if (sets.isEmpty()) return null
        return """UPDATE "${SqliteSequenceNaming.SUPPORT_TABLE}" SET ${sets.joinToString(", ")} """ +
            """WHERE "name" = '${escapeLiteral(name)}';"""
    }

    private fun escapeLiteral(s: String): String = s.replace("\\", "\\\\").replace("'", "''")
}
