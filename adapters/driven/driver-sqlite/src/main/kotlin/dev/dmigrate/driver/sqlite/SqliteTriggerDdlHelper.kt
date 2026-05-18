package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * E.2 Trigger-Migration Sub-Slice C — SQLite trigger rendering.
 *
 * Render templates (verbindlich, per plan §3 Sub-Slice C):
 *
 *     CREATE TRIGGER <name>
 *         <timing> <event> ON <table>
 *         FOR EACH ROW
 *         [WHEN <condition>]
 *     BEGIN
 *         <body>
 *     END;
 *
 *     DROP TRIGGER <name>;           -- triggers are global in SQLite,
 *                                    -- no qualifier is allowed
 *
 * SQLite has no `CREATE OR REPLACE TRIGGER` grammar; Replace always
 * renders as Drop+Create. The Mapper sets `hasGap = true` on every
 * SQLite `ReplaceTrigger` via
 * `TriggerCapabilityDefaults.forDialect(SQLITE) = disabled` →
 * `TriggerReplaceMode.DROP_CREATE_FALLBACK`, and the renderer reads
 * `op.risks.<direction>.hasGap` for the strict-mode lift instead of
 * inspecting the capability itself (A.3 contract).
 *
 * The renderer rejects SQLite-incompatible modelling at render time:
 *
 * - `forEach = STATEMENT` → `DIALECT_UNSUPPORTED_OPERATION`. SQLite
 *   only supports `FOR EACH ROW`.
 * - `INSTEAD OF` triggers are rendered as-is. SQLite accepts them
 *   only on views; if the underlying object is a table, the engine
 *   will reject the statement. The renderer does not pre-check that
 *   here because the neutral model does not yet expose
 *   view-vs-table cleanly for the trigger's target.
 *
 * **SQLite-Rebuild interaction:** `SqliteRebuildPlanner.classify`
 * absorbs trigger ops whose table is in a rebuild bucket into the
 * rebuild's `dependentTriggersToDrop` / `dependentTriggersToRecreate`
 * lists, so they never reach this helper. The renderer only ever
 * sees stand-alone trigger ops — ones whose target table is not in
 * any rebuild bucket. Tests pin that no separate `CREATE TRIGGER`
 * leaks outside the rebuild block.
 *
 * SQLite-trigger reverse-read (`sqlite_master` lookups) is out of
 * E.2 scope — see the plan doc §7.3. File-to-file is the primary
 * SQLite migrate path; live-DB→live-DB trigger diffing is a
 * follow-up slice.
 */
internal object SqliteTriggerDdlHelper {

    fun renderCreateTrigger(op: DiffOperation.CreateTrigger, ctx: SqliteDiffRenderContext) {
        when (ctx.direction) {
            SqliteRenderDirection.UP -> emitCreate(op, op.trigger, ctx)
            SqliteRenderDirection.DOWN -> emitDrop(op, ctx)
        }
    }

    fun renderDropTrigger(op: DiffOperation.DropTrigger, ctx: SqliteDiffRenderContext) {
        when (ctx.direction) {
            SqliteRenderDirection.UP -> emitDrop(op, ctx)
            SqliteRenderDirection.DOWN -> emitCreate(op, op.trigger, ctx)
        }
    }

    fun renderReplaceTrigger(op: DiffOperation.ReplaceTrigger, ctx: SqliteDiffRenderContext) {
        val target = if (ctx.direction == SqliteRenderDirection.UP) op.after else op.before
        if (target.body.isNullOrBlank()) {
            // Mirrors PG/MySQL classification — Up = MANUAL_ACTION_REQUIRED,
            // Down = ROLLBACK_NOT_POSSIBLE.
            val isDown = ctx.direction == SqliteRenderDirection.DOWN
            val side = if (isDown) "Down" else "Up"
            val sideField = if (isDown) "before" else "after"
            val code = if (isDown) "ROUTINE_DOWN_BODY_UNKNOWN" else "ROUTINE_REPLACE_UP_BODY_UNKNOWN"
            val reason = if (isDown) {
                MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE
            } else {
                MigrationBlockedReason.MANUAL_ACTION_REQUIRED
            }
            ctx.skip(
                op,
                "ReplaceTrigger $side for '${op.objectRef.rootName}': body is unknown. " +
                    "SQLite needs a complete trigger body on the $sideField side to render the " +
                    "Drop+Create fallback.",
                code = code,
            )
            ctx.addBlocker(reason, operationIds = setOf(op.id))
            return
        }
        emitDropCreateReplaceFallback(op, target, ctx)
    }

    private fun emitCreate(
        op: DiffOperation,
        trigger: TriggerDefinition,
        ctx: SqliteDiffRenderContext,
    ) {
        if (!validateSqliteTrigger(op, trigger, ctx)) return
        val sqlText = ctx.sql.createTriggerSql(op.objectRef.rootName, trigger)
        if (sqlText == null) {
            // createTriggerSql returns null when the body is missing or
            // sourceDialect rules out SQLite emission. We've already
            // validated body presence above, so a null here means
            // sourceDialect mismatch.
            ctx.skip(
                op,
                "Trigger '${op.objectRef.rootName}' cannot be rendered for SQLite. The neutral " +
                    "trigger payload either has no body or its `sourceDialect` does not match " +
                    "SQLite — the renderer refuses to translate body grammar across dialects.",
                code = "SQLITE_TRIGGER_NOT_RENDERABLE",
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
            return
        }
        ctx.emit(op, sqlText)
    }

    private fun emitDrop(op: DiffOperation, ctx: SqliteDiffRenderContext) {
        ctx.emit(op, "DROP TRIGGER ${ctx.sql.quote(op.objectRef.rootName)};")
    }

    private fun emitDropCreateReplaceFallback(
        op: DiffOperation.ReplaceTrigger,
        target: TriggerDefinition,
        ctx: SqliteDiffRenderContext,
    ) {
        if (!validateSqliteTrigger(op, target, ctx)) return
        val createSql = ctx.sql.createTriggerSql(op.objectRef.rootName, target)
        if (createSql == null) {
            ctx.skip(
                op,
                "ReplaceTrigger '${op.objectRef.rootName}': SQLite cannot render the target body " +
                    "(missing or non-SQLite sourceDialect). Drop+Create fallback aborted before any " +
                    "DDL is emitted.",
                code = "SQLITE_TRIGGER_NOT_RENDERABLE",
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
            return
        }
        val dropSql = "DROP TRIGGER ${ctx.sql.quote(op.objectRef.rootName)};"
        ctx.emit(op, dropSql)
        ctx.emit(op, createSql)
        // A.3 strict-mode guard: emit() short-circuits in strict mode
        // and routes the op into `skipped`. The trailing gap warning
        // only makes sense for the lenient path.
        if (!ctx.isSkipped(op)) {
            ctx.addDiagnostic(
                DiffDiagnostic(
                    code = W_TRIGGER_REPLACE_GAP,
                    message = "Trigger '${op.objectRef.rootName}' is replaced via DROP + CREATE because " +
                        "SQLite has no native `CREATE OR REPLACE TRIGGER`. While the two statements run " +
                        "there is a short window in which the trigger does not fire. " +
                        "`--strict-gap-operations` would lift this to MANUAL_ACTION_REQUIRED.",
                    severity = DiffDiagnostic.Severity.WARNING,
                    operationId = op.id,
                ),
            )
        }
    }

    private fun validateSqliteTrigger(
        op: DiffOperation,
        trigger: TriggerDefinition,
        ctx: SqliteDiffRenderContext,
    ): Boolean {
        if (trigger.forEach == TriggerForEach.STATEMENT) {
            ctx.skip(
                op,
                "Trigger '${op.objectRef.rootName}' is declared `FOR EACH STATEMENT`. SQLite only " +
                    "supports row-level triggers.",
                code = "SQLITE_TRIGGER_STATEMENT_LEVEL_UNSUPPORTED",
            )
            ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
            return false
        }
        if (trigger.body.isNullOrBlank()) {
            ctx.skip(
                op,
                "Trigger '${op.objectRef.rootName}' has no body. SQLite requires a non-empty " +
                    "BEGIN..END body.",
                code = "ROUTINE_BODY_UNKNOWN",
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
            return false
        }
        return true
    }

    internal const val W_TRIGGER_REPLACE_GAP: String = "W_TRIGGER_REPLACE_GAP"
}
