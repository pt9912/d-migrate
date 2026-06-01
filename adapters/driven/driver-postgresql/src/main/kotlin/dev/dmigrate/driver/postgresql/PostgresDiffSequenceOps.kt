package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.driver.SqlIdentifiers

/**
 * PostgreSQL sequence DDL for Plan-2 E.3's first declarative slice.
 *
 * The renderer handles only schema attributes present in SequenceDefinition.
 * It deliberately does not preserve or reset the live current value.
 */
internal object PostgresDiffSequenceOps {

    fun renderCreateSequence(op: DiffOperation.CreateSequence, ctx: PostgresDiffRenderContext) {
        val name = op.objectRef.rootName
        if (ctx.direction == PostgresRenderDirection.DOWN) {
            ctx.emit(op, "DROP SEQUENCE ${ctx.sql.quote(name)};", PostgresDiffRenderContext.POSTGRES_METADATA_HINTS)
            return
        }
        ctx.emit(op, ctx.sql.createSequenceSql(name, op.sequence), PostgresDiffRenderContext.POSTGRES_METADATA_HINTS)
    }

    fun renderAlterSequence(op: DiffOperation.AlterSequence, ctx: PostgresDiffRenderContext) {
        val name = op.objectRef.rootName
        val target = if (ctx.direction == PostgresRenderDirection.UP) op.after else op.before
        ctx.emit(op, ctx.sql.alterSequenceSql(name, target), PostgresDiffRenderContext.POSTGRES_METADATA_HINTS)
    }

    fun renderDropSequence(op: DiffOperation.DropSequence, ctx: PostgresDiffRenderContext) {
        val name = op.objectRef.rootName
        if (ctx.direction == PostgresRenderDirection.DOWN) {
            ctx.emit(op, ctx.sql.createSequenceSql(name, op.sequence), PostgresDiffRenderContext.POSTGRES_METADATA_HINTS)
            return
        }
        ctx.emit(op, "DROP SEQUENCE ${ctx.sql.quote(name)};", PostgresDiffRenderContext.POSTGRES_METADATA_HINTS)
    }

    /**
     * F.4 Sub-Slice A.2 Teil 2: PostgreSQL native sequence rename.
     * `ALTER SEQUENCE <fromName> RENAME TO <toName>` — the left side
     * is the existing identity (`fromName`), the right side is the new
     * visible name (`toName`). `objectRef.path[0]` is the canonical
     * target key but must not appear in the SQL itself.
     */
    fun renderRenameSequence(op: DiffOperation.RenameSequence, ctx: PostgresDiffRenderContext) {
        val (oldName, newName) = if (ctx.direction == PostgresRenderDirection.UP) {
            op.fromName to op.toName
        } else {
            op.toName to op.fromName
        }
        ctx.emit(
            op,
            "ALTER SEQUENCE ${ctx.sql.quote(oldName)} RENAME TO ${ctx.sql.quote(newName)};",
            PostgresDiffRenderContext.POSTGRES_METADATA_HINTS,
        )
    }

    /**
     * 0.9.7 preserve-current-value Sub-Slice A: emit
     * `SELECT setval('<seq>', <value>, <isCalled>);` so a freshly-
     * created or altered sequence resumes at the live
     * `last_value` snapshot rather than jumping back to `start`.
     *
     * Up/Down split:
     * - **Up**: applies `currentValue` + `isCalled` against
     *   `applySequenceRef` (post-rename target for `RenameSequence`
     *   follow-ups, identity for `CreateSequence` / `AlterSequence`).
     * - **Down**: applies `restoreValue` + `restoreIsCalled` against
     *   `probeSequenceRef` (origin name on Rename, identity otherwise).
     *   When [DiffOperation.AlterSequenceCurrentValue.rollbackImpossible]
     *   is `true` the renderer emits a structured note instead of
     *   `setval(...)` — Down for new sequences without a deterministic
     *   pre-state cannot run safely.
     *
     * `isCalled` is propagated verbatim because `setval(seq, value, true)`
     * makes the next `nextval` return `value + 1`, while `setval(seq,
     * value, false)` makes it return `value`. The PG probe in Sub-Slice
     * B reads this flag from `pg_sequences.is_called`.
     */
    fun renderAlterSequenceCurrentValue(
        op: DiffOperation.AlterSequenceCurrentValue,
        ctx: PostgresDiffRenderContext,
    ) {
        if (ctx.direction == PostgresRenderDirection.UP) {
            // Atomic-Preserve follow-up (Finding #2, 2026-06-01): a
            // [currentValue] of [ATOMIC_PRESERVE_SENTINEL_CURRENT_VALUE]
            // (0L) is a marker indicating the value will be probed at
            // execute time by `PostgresAtomicSequencePreserveExecutor`,
            // not the real runtime `last_value`. Rendering a literal
            // `setval('seq', 0, true)` would mis-describe the operation
            // in plan-only / report artefacts and execute as a
            // destructive sequence-reset if anyone copy-pasted it. We
            // emit an audit comment instead — the live-execute path
            // already filters this op out via `internalFollowUpIds`.
            if (op.currentValue == DiffOperation.AlterSequenceCurrentValue.ATOMIC_PRESERVE_SENTINEL_CURRENT_VALUE) {
                ctx.emit(
                    op,
                    "-- atomic-preserve audit: setval for ${op.applySequenceRef.name} is " +
                        "probed + restored at execute time inside the lock " +
                        "(value not yet known at render time).",
                    PostgresDiffRenderContext.POSTGRES_METADATA_HINTS,
                )
                return
            }
            renderSetval(
                op = op,
                ctx = ctx,
                sequenceName = op.applySequenceRef.name,
                value = op.currentValue,
                isCalled = op.isCalled,
            )
            return
        }
        val restoreValue = op.restoreValue
        val restoreIsCalled = op.restoreIsCalled
        if (op.rollbackImpossible || restoreValue == null || restoreIsCalled == null) {
            // The PG `setval(seq, value, is_called)` form requires
            // a 3-arg call — Down without both `restoreValue` and
            // `restoreIsCalled` is not renderable. The planner-side
            // gate in Sub-Slice D guards this at emit time; the
            // renderer falls back to a structured note so the operator
            // still sees the op in the report without a half-built
            // `setval(seq, NULL, NULL)`.
            ctx.emit(
                op,
                "-- preserve-current-value down skipped for ${op.applySequenceRef.name}: " +
                    (op.rollbackImpossibleReason ?: "no deterministic restore snapshot"),
                PostgresDiffRenderContext.POSTGRES_METADATA_HINTS,
            )
            return
        }
        renderSetval(
            op = op,
            ctx = ctx,
            sequenceName = op.probeSequenceRef.name,
            value = restoreValue,
            isCalled = restoreIsCalled,
        )
    }

    private fun renderSetval(
        op: DiffOperation.AlterSequenceCurrentValue,
        ctx: PostgresDiffRenderContext,
        sequenceName: String,
        value: Long,
        isCalled: Boolean?,
    ) {
        // PG `setval` needs `is_called` to disambiguate the post-call
        // `nextval` behaviour. The DiffOp's `isCalled` is nullable
        // for cross-dialect symmetry; PG MUST have it set by the
        // planner — the gate in Sub-Slice D enforces this. Falling
        // back to `true` here would silently change the post-call
        // sequence position by +1, so we refuse instead.
        requireNotNull(isCalled) {
            "AlterSequenceCurrentValue on PG must carry isCalled (op-id=${op.id})"
        }
        val literal = SqlIdentifiers.quoteStringLiteral(sequenceName)
        ctx.emit(
            op,
            "SELECT setval($literal, $value, $isCalled);",
            PostgresDiffRenderContext.POSTGRES_METADATA_HINTS,
        )
    }
}
