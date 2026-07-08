package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.toSqlEventClause
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * E.2 Trigger-Migration Sub-Slice B — MySQL trigger rendering.
 *
 * Render templates (verbindlich, per plan §3 Sub-Slice B):
 *
 *     CREATE TRIGGER <name>
 *         <timing> <event>
 *         ON <table>
 *         FOR EACH ROW
 *         <body>;
 *
 *     DROP TRIGGER <name>;
 *
 * `<body>` is an inline SQL block, either a single statement or a
 * `BEGIN ... END` block. MySQL clients use `DELIMITER //` around such
 * bodies, but the renderer writes the body as a single
 * `Statement.execute(...)` payload without the delimiter wrapper —
 * mirrors `MysqlDiffRoutineOps`' delimiter-free contract for stored
 * routines.
 *
 * MySQL does **not** support `CREATE OR REPLACE TRIGGER`. Replace is
 * always rendered as Drop+Create with a `W_TRIGGER_REPLACE_GAP`
 * warning so consumers see the visibility gap between the two
 * statements. `--strict-gap-operations` lifts the gap into a hard
 * `MANUAL_ACTION_REQUIRED` via the central
 * `MysqlDiffRenderContext.emit()` guard (A.3 foundation).
 *
 * The renderer rejects MySQL-incompatible modelling at render time:
 * - `condition != null` (MySQL has no `WHEN` clause on triggers).
 * - `forEach = STATEMENT` (MySQL knows only `FOR EACH ROW`).
 * - `timing = INSTEAD_OF` (PostgreSQL-only; MySQL has no INSTEAD OF
 *   triggers).
 *
 * **`DROP TRIGGER` carries a bare trigger name** — MySQL does **not**
 * accept a `<table>.<name>` qualifier (that is a SQL syntax error
 * unique to MySQL; the `<schema>.<name>` form is the only allowed
 * qualifier and is reserved for a future schema-aware slice). DROP
 * is rendered without `IF EXISTS`; diff-driven DROP only fires when
 * the planner saw the trigger in `current`, so a missing trigger at
 * apply-time is a real drift signal, not noise. An
 * `--idempotent-drop`-style toggle is a future-slice carve-out.
 *
 * **Body sanitisation is out of scope.** The renderer treats the
 * inline body as opaque SQL and does not validate or scrub it —
 * SQL/control-construct rejection is the database engine's job. The
 * reader pipeline (`MysqlRoutineReader` for live targets, the YAML
 * schema codec for file targets) is the sole input-trust boundary;
 * untrusted body content reaching the renderer is a pipeline bug.
 * Operator-facing display reports go through `RoutineBodyScrubber`
 * (E.1) on the way out — see `SchemaMigrateReportBuilder`.
 *
 * DEFINER rendering is out of scope: the neutral `TriggerDefinition`
 * model has no definer field. A future slice can thread definer
 * metadata once readers populate it (analogous to E.1 F.6 MySQL
 * routines).
 */
internal object MysqlTriggerDdlHelper {

    fun renderCreateTrigger(op: DiffOperation.CreateTrigger, ctx: MysqlDiffRenderContext) {
        when (ctx.direction) {
            MysqlRenderDirection.UP -> emitCreate(op, op.trigger, ctx)
            MysqlRenderDirection.DOWN -> emitDrop(op, ctx)
        }
    }

    fun renderDropTrigger(op: DiffOperation.DropTrigger, ctx: MysqlDiffRenderContext) {
        when (ctx.direction) {
            MysqlRenderDirection.UP -> emitDrop(op, ctx)
            MysqlRenderDirection.DOWN -> emitCreate(op, op.trigger, ctx)
        }
    }

    fun renderReplaceTrigger(op: DiffOperation.ReplaceTrigger, ctx: MysqlDiffRenderContext) {
        val target = if (ctx.direction == MysqlRenderDirection.UP) op.after else op.before
        if (target.body.isNullOrBlank()) {
            // Body unknown on the active side; mirror the routine
            // renderer's classification — Up = MANUAL_ACTION_REQUIRED,
            // Down = ROLLBACK_NOT_POSSIBLE.
            val isDown = ctx.direction == MysqlRenderDirection.DOWN
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
                    "MySQL needs a complete trigger body on the $sideField side to render the " +
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
        ctx: MysqlDiffRenderContext,
    ) {
        if (!validateMysqlTrigger(op, trigger, ctx)) return
        ctx.emit(op, buildCreateSql(op.objectRef.rootName, trigger, ctx))
    }

    private fun emitDrop(op: DiffOperation, ctx: MysqlDiffRenderContext) {
        // MySQL DROP TRIGGER takes a bare trigger name. `<table>.<name>`
        // is a SQL syntax error in MySQL; the only valid qualifier is
        // `<schema>.<name>` and the neutral trigger model has no
        // schema field today.
        ctx.emit(op, "DROP TRIGGER ${ctx.sql.quote(op.objectRef.rootName)};")
    }

    private fun emitDropCreateReplaceFallback(
        op: DiffOperation.ReplaceTrigger,
        target: TriggerDefinition,
        ctx: MysqlDiffRenderContext,
    ) {
        if (!validateMysqlTrigger(op, target, ctx)) return
        val dropSql = "DROP TRIGGER ${ctx.sql.quote(op.objectRef.rootName)};"
        val createSql = buildCreateSql(op.objectRef.rootName, target, ctx)
        ctx.emit(op, dropSql)
        ctx.emit(op, createSql)
        // A.3 strict-mode guard: emit() short-circuits in strict mode
        // and routes the op into `skipped`. The trailing gap warning
        // only makes sense for the lenient path.
        if (!ctx.isSkipped(op)) {
            ctx.warning(
                op,
                "Trigger '${op.objectRef.rootName}' is replaced via DROP + CREATE because MySQL has no " +
                    "native `CREATE OR REPLACE TRIGGER`. While the two statements run there is a short " +
                    "window in which the trigger does not fire. `--strict-gap-operations` would lift " +
                    "this to MANUAL_ACTION_REQUIRED.",
                code = W_TRIGGER_REPLACE_GAP,
            )
        }
    }

    /**
     * Reject MySQL-incompatible trigger modelling at render time:
     * INSTEAD-OF timing, `WHEN`-conditions and statement-level
     * triggers do not exist on MySQL. All three produce
     * `DIALECT_UNSUPPORTED_OPERATION` so the report tells the
     * operator which field is at fault.
     *
     * Check order is fixed and deterministic:
     * 1. `INSTEAD_OF` → `MYSQL_TRIGGER_INSTEAD_OF_UNSUPPORTED`
     * 2. `condition != null` → `MYSQL_TRIGGER_CONDITION_UNSUPPORTED`
     * 3. `forEach = STATEMENT` → `MYSQL_TRIGGER_STATEMENT_LEVEL_UNSUPPORTED`
     * 4. empty body → `ROUTINE_BODY_UNKNOWN` + `MANUAL_ACTION_REQUIRED`
     *
     * A trigger that violates several invariants surfaces the
     * earlier blocker in the chain (e.g. `INSTEAD OF` + `WHEN` fires
     * `MYSQL_TRIGGER_INSTEAD_OF_UNSUPPORTED`, not the WHEN code).
     * Order is intentional — INSTEAD-OF is the structural mismatch;
     * once removed, the operator can address WHEN/STATEMENT/body
     * issues in subsequent rounds.
     */
    private fun validateMysqlTrigger(
        op: DiffOperation,
        trigger: TriggerDefinition,
        ctx: MysqlDiffRenderContext,
    ): Boolean {
        if (trigger.timing == TriggerTiming.INSTEAD_OF) {
            ctx.skip(
                op,
                "Trigger '${op.objectRef.rootName}' is declared `INSTEAD OF`. MySQL only supports " +
                    "`BEFORE`/`AFTER` triggers; INSTEAD OF is PostgreSQL-only.",
                code = "MYSQL_TRIGGER_INSTEAD_OF_UNSUPPORTED",
            )
            ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
            return false
        }
        if (!trigger.condition.isNullOrBlank()) {
            ctx.skip(
                op,
                "Trigger '${op.objectRef.rootName}' carries a WHEN condition. MySQL triggers do not " +
                    "support `WHEN`; move the predicate into the trigger body or drop the condition.",
                code = "MYSQL_TRIGGER_CONDITION_UNSUPPORTED",
            )
            ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
            return false
        }
        if (trigger.forEach == TriggerForEach.STATEMENT) {
            ctx.skip(
                op,
                "Trigger '${op.objectRef.rootName}' is declared `FOR EACH STATEMENT`. MySQL only " +
                    "supports `FOR EACH ROW` triggers.",
                code = "MYSQL_TRIGGER_STATEMENT_LEVEL_UNSUPPORTED",
            )
            ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
            return false
        }
        if (trigger.body.isNullOrBlank()) {
            ctx.skip(
                op,
                "Trigger '${op.objectRef.rootName}' has no body. MySQL requires a non-empty inline " +
                    "trigger body.",
                code = "ROUTINE_BODY_UNKNOWN",
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
            return false
        }
        return true
    }

    private fun buildCreateSql(
        triggerName: String,
        trigger: TriggerDefinition,
        ctx: MysqlDiffRenderContext,
    ): String {
        val trimmedBody = trigger.body!!.trim()
        val needsTrailingSemicolon = !trimmedBody.endsWith(';')
        return buildString {
            append("CREATE TRIGGER ").append(ctx.sql.quote(triggerName)).append('\n')
            append("    ").append(trigger.timing.toSqlKeyword()).append(' ')
                .append(trigger.events.toSqlEventClause()).append('\n')
            append("    ON ").append(ctx.sql.quote(trigger.table)).append('\n')
            append("    FOR EACH ROW\n")
            append("    ").append(trimmedBody)
            if (needsTrailingSemicolon) append(';')
        }
    }

    private fun TriggerTiming.toSqlKeyword(): String = when (this) {
        TriggerTiming.BEFORE -> "BEFORE"
        TriggerTiming.AFTER -> "AFTER"
        // Defence-in-depth fallback only — `validateMysqlTrigger`
        // rejects `INSTEAD_OF` upstream with
        // `MYSQL_TRIGGER_INSTEAD_OF_UNSUPPORTED` before any
        // `buildCreateSql` call, so this branch should be unreachable.
        TriggerTiming.INSTEAD_OF -> "INSTEAD OF"
    }

    internal const val W_TRIGGER_REPLACE_GAP: String = "W_TRIGGER_REPLACE_GAP"
}
