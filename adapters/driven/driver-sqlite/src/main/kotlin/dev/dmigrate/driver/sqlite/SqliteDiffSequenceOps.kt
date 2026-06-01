package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.DefaultValue
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
                // If the target schema already has columns bound to
                // this sequence (a `CreateSequence` paired with
                // pre-existing `SequenceNextVal`-defaults from the
                // current schema), emit the trigger pair too. The
                // `AddColumn`-with-SequenceNextVal-pathway in
                // `SqliteDiffSimpleOps` handles the inverse case
                // (sequence already present, column being added).
                for ((tableName, columnName) in collectBoundColumns(name, ctx, SqliteRenderDirection.DOWN)) {
                    emitTriggerPair(op, ctx, tableName, columnName, name)
                }
            }
            SqliteRenderDirection.DOWN -> {
                // Bound triggers are dropped first so the row delete
                // doesn't leave orphan triggers behind.
                for ((tableName, columnName) in collectBoundColumns(name, ctx, SqliteRenderDirection.DOWN)) {
                    val biName = SqliteSequenceNaming.beforeInsertTriggerName(tableName, columnName, name)
                    val aiName = SqliteSequenceNaming.afterInsertTriggerName(tableName, columnName, name)
                    ctx.emit(op, """DROP TRIGGER IF EXISTS "$biName";""")
                    ctx.emit(op, """DROP TRIGGER IF EXISTS "$aiName";""")
                }
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
        // Plan §6.1 Round-Trip: bound `_bi`/`_ai`-trigger pairs must
        // be dropped together with the `dmg_sequences`-row, otherwise
        // an INSERT on the host table later runs the orphaned _bi-
        // trigger and surfaces "sequence row not found" via the
        // canonical RAISE(ABORT).
        val boundColumns = collectBoundColumns(name, ctx, SqliteRenderDirection.UP)
        when (ctx.direction) {
            SqliteRenderDirection.UP -> {
                for ((tableName, columnName) in boundColumns) {
                    val biName = SqliteSequenceNaming.beforeInsertTriggerName(tableName, columnName, name)
                    val aiName = SqliteSequenceNaming.afterInsertTriggerName(tableName, columnName, name)
                    ctx.emit(op, """DROP TRIGGER IF EXISTS "$biName";""")
                    ctx.emit(op, """DROP TRIGGER IF EXISTS "$aiName";""")
                }
                ctx.emit(op, deleteRowSql(name))
            }
            SqliteRenderDirection.DOWN -> {
                ensureBootstrapEmitted(op, ctx)
                ctx.emit(op, SqliteSequenceEmulationTemplates.sequenceSeedSql(name, op.sequence))
                for ((tableName, columnName) in boundColumns) {
                    emitTriggerPair(op, ctx, tableName, columnName, name)
                }
            }
        }
    }

    fun renderRenameSequence(op: DiffOperation.RenameSequence, ctx: SqliteDiffRenderContext) {
        if (!ensureHelperMode(op, ctx)) return
        val (oldName, newName) = when (ctx.direction) {
            SqliteRenderDirection.UP -> op.fromName to op.toName
            SqliteRenderDirection.DOWN -> op.toName to op.fromName
        }
        // The trigger bodies embed the sequence name as a string
        // literal (`WHERE "name" = 'old_seq'`); just renaming the
        // `dmg_sequences`-row would leave the triggers pointing at a
        // row that no longer exists. Drop the old pair and re-emit
        // it under the new sequence name. Bound columns are the
        // *current*-direction view (UP: pre-rename schema).
        val sideForBindings = when (ctx.direction) {
            SqliteRenderDirection.UP -> SqliteRenderDirection.UP
            SqliteRenderDirection.DOWN -> SqliteRenderDirection.DOWN
        }
        val boundColumns = collectBoundColumns(oldName, ctx, sideForBindings)
        ctx.emit(
            op,
            """UPDATE "${SqliteSequenceNaming.SUPPORT_TABLE}" SET "name" = '${escapeLiteral(newName)}' """ +
                """WHERE "name" = '${escapeLiteral(oldName)}';""",
        )
        for ((tableName, columnName) in boundColumns) {
            val oldBi = SqliteSequenceNaming.beforeInsertTriggerName(tableName, columnName, oldName)
            val oldAi = SqliteSequenceNaming.afterInsertTriggerName(tableName, columnName, oldName)
            ctx.emit(op, """DROP TRIGGER IF EXISTS "$oldBi";""")
            ctx.emit(op, """DROP TRIGGER IF EXISTS "$oldAi";""")
            emitTriggerPair(op, ctx, tableName, columnName, newName)
        }
    }

    fun renderAlterSequenceCurrentValue(op: DiffOperation.AlterSequenceCurrentValue, ctx: SqliteDiffRenderContext) {
        if (!ensureHelperMode(op, ctx)) return
        when (ctx.direction) {
            SqliteRenderDirection.UP -> {
                // 0.9.7 SQLite preserve-current-value Folge-Slice: UP
                // targets `applySequenceRef` (the new name after a
                // rename; the same name as the parent op otherwise).
                //
                // Atomic-Preserve follow-up (Finding #2, 2026-06-01):
                // the sentinel current-value (0L) marks the op as
                // runtime-probed by `SqliteAtomicSequencePreserveExecutor`.
                // Emit an audit comment instead of an `UPDATE` that
                // would set `next_value = 0` if copy-pasted out of a
                // report.
                if (op.currentValue == DiffOperation.AlterSequenceCurrentValue.ATOMIC_PRESERVE_SENTINEL_CURRENT_VALUE) {
                    ctx.emit(
                        op,
                        "-- atomic-preserve audit: UPDATE dmg_sequences for " +
                            "${op.applySequenceRef.name} is probed + restored at " +
                            "execute time inside the lock " +
                            "(value not yet known at render time).",
                    )
                } else {
                    ctx.emit(op, updateNextValueSql(op.applySequenceRef.name, op.currentValue))
                }
            }
            SqliteRenderDirection.DOWN -> {
                // 0.9.7 SQLite preserve-current-value Folge-Slice plan
                // §7.3: Down restores against `probeSequenceRef`
                // (the original name; for rename ops this is the
                // pre-rename name that exists again once the rename
                // Down has run). `restoreValue` is the canonical
                // snapshot the planner captured at probe time. If it
                // is null the op is intentionally non-rollbackable
                // (CreateSequence without prior state) — surface that
                // as a structured skip instead of emitting a
                // silently-wrong UPDATE.
                val restoreValue = op.restoreValue
                if (restoreValue == null) {
                    ctx.skip(
                        op,
                        "AlterSequenceCurrentValue DOWN for '${op.probeSequenceRef.name}' has no " +
                            "deterministic restoreValue — current-value rollback is not possible " +
                            "(${op.rollbackImpossibleReason ?: "rollbackImpossible = true"}).",
                        code = "SQLITE_SEQUENCE_CURRENT_VALUE_DOWN_ROLLBACK_IMPOSSIBLE",
                    )
                    return
                }
                ctx.emit(op, updateNextValueSql(op.probeSequenceRef.name, restoreValue))
            }
        }
    }

    private fun updateNextValueSql(sequenceName: String, value: Long): String =
        """UPDATE "${SqliteSequenceNaming.SUPPORT_TABLE}" SET "next_value" = $value """ +
            """WHERE "name" = '${escapeLiteral(sequenceName)}';"""

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

    /**
     * Walks the schema-side that holds the column→sequence bindings
     * for [sequenceName] and returns every `(table, column)` pair.
     * Used by [renderDropSequence] / [renderRenameSequence] to find
     * the `_bi`/`_ai`-trigger pairs that need to be dropped or
     * rebuilt alongside the `dmg_sequences`-row mutation.
     */
    private fun collectBoundColumns(
        sequenceName: String,
        ctx: SqliteDiffRenderContext,
        side: SqliteRenderDirection,
    ): List<Pair<String, String>> {
        val schema = when (side) {
            SqliteRenderDirection.UP -> ctx.currentSchema
            SqliteRenderDirection.DOWN -> ctx.desiredSchema
        } ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for ((tableName, table) in schema.tables) {
            for ((columnName, column) in table.columns) {
                val default = column.default
                if (default is DefaultValue.SequenceNextVal && default.sequenceName == sequenceName) {
                    out += tableName to columnName
                }
            }
        }
        return out
    }

    /**
     * Emits the canonical `_bi`/`_ai`-trigger pair for the given
     * `(table, column, sequence)` triple. Used by
     * [renderDropSequence] DOWN, [renderRenameSequence] (both
     * directions) and the AddColumn-with-SequenceNextVal pathway in
     * [SqliteDiffSimpleOps].
     */
    internal fun emitTriggerPair(
        op: DiffOperation,
        ctx: SqliteDiffRenderContext,
        tableName: String,
        columnName: String,
        sequenceName: String,
    ) {
        val spec = SqliteSequenceTriggerSpec(tableName, columnName, sequenceName)
        val biName = SqliteSequenceNaming.beforeInsertTriggerName(tableName, columnName, sequenceName)
        val aiName = SqliteSequenceNaming.afterInsertTriggerName(tableName, columnName, sequenceName)
        ctx.emit(op, SqliteSequenceEmulationTemplates.beforeInsertTriggerSql(spec, biName))
        ctx.emit(op, SqliteSequenceEmulationTemplates.afterInsertTriggerSql(spec, aiName))
    }

    /**
     * G5: convenience for [SqliteDiffSimpleOps.renderAddColumn] /
     * [SqliteDiffSimpleOps.renderDropColumn] — emit the trigger
     * pair only if the column's default is a `SequenceNextVal`.
     */
    internal fun emitTriggerPairIfBound(
        op: DiffOperation,
        ctx: SqliteDiffRenderContext,
        tableName: String,
        columnName: String,
        default: DefaultValue?,
    ) {
        val seqDefault = default as? DefaultValue.SequenceNextVal ?: return
        emitTriggerPair(op, ctx, tableName, columnName, seqDefault.sequenceName)
    }

    /**
     * G5 inverse: drop the trigger pair for the column if a
     * `SequenceNextVal` default is bound.
     */
    internal fun dropTriggerPairIfBound(
        op: DiffOperation,
        ctx: SqliteDiffRenderContext,
        tableName: String,
        columnName: String,
        default: DefaultValue?,
    ) {
        val seqDefault = default as? DefaultValue.SequenceNextVal ?: return
        val biName = SqliteSequenceNaming.beforeInsertTriggerName(tableName, columnName, seqDefault.sequenceName)
        val aiName = SqliteSequenceNaming.afterInsertTriggerName(tableName, columnName, seqDefault.sequenceName)
        ctx.emit(op, """DROP TRIGGER IF EXISTS "$biName";""")
        ctx.emit(op, """DROP TRIGGER IF EXISTS "$aiName";""")
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

    /**
     * Standard-SQL single-quote escape. Standard-SQLite kennt keine
     * Backslash-Escapes; deshalb darf hier **kein** `\\`-Doubling
     * stehen — sonst würde es bei einem Sequence-Namen mit Backslash
     * + Apostroph die Escape-Reihenfolge brechen (Backslash-Replace
     * vor Apostroph-Replace würde das nachfolgende `''` zerschneiden).
     */
    private fun escapeLiteral(s: String): String = s.replace("'", "''")
}
