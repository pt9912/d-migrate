package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.driver.TriggerCapabilityResolution
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.resolve

/**
 * E.2 Trigger-Migration Sub-Slice A.2 — PostgreSQL trigger rendering.
 *
 * Render templates (verbindlich, see plan §3 Sub-Slice A.2):
 *
 *     CREATE TRIGGER <name>
 *         <timing> <event>
 *         ON <table>
 *         FOR EACH <forEach>
 *         [WHEN (<condition>)]
 *         EXECUTE FUNCTION <bodyFunctionRef>;
 *
 *     DROP TRIGGER <name> ON <table>;
 *
 *     CREATE OR REPLACE TRIGGER <name>
 *         <timing> <event>
 *         ON <table>
 *         FOR EACH <forEach>
 *         [WHEN (<condition>)]
 *         EXECUTE FUNCTION <bodyFunctionRef>;       -- PG 14+ only
 *
 * `<bodyFunctionRef>` is `TriggerDefinition.body` validated as a strict
 * `[schema.]identifier([arg, ...])` function reference. Inline PL/pgSQL
 * bodies are out of E.2 scope and block with
 * `TRIGGER_BODY_NOT_FUNCTION_REFERENCE`.
 *
 * `EXECUTE PROCEDURE` is intentionally never emitted — PostgreSQL has
 * accepted it as a deprecated alias since PG-11, but `EXECUTE FUNCTION`
 * is the canonical form on every server version that runs the
 * neutral-trigger contract.
 *
 * `ReplaceTrigger` consults [PostgresDiffRenderContext.options.triggerCapability]
 * via [TriggerCapabilityResolution]. `Active` renders the PG-14+
 * native `CREATE OR REPLACE TRIGGER`. `Disabled` falls back to two
 * statements (Drop then Create) and emits a `W_TRIGGER_REPLACE_GAP`
 * warning diagnostic.
 */
internal object PostgresTriggerDdlHelper {

    fun renderCreateTrigger(op: DiffOperation.CreateTrigger, ctx: PostgresDiffRenderContext) {
        when (ctx.direction) {
            PostgresRenderDirection.UP -> emitCreate(op, op.trigger, ctx, orReplace = false)
            PostgresRenderDirection.DOWN -> emitDrop(op, op.trigger, ctx)
        }
    }

    fun renderDropTrigger(op: DiffOperation.DropTrigger, ctx: PostgresDiffRenderContext) {
        when (ctx.direction) {
            PostgresRenderDirection.UP -> emitDrop(op, op.trigger, ctx)
            PostgresRenderDirection.DOWN -> emitCreate(op, op.trigger, ctx, orReplace = false)
        }
    }

    fun renderReplaceTrigger(op: DiffOperation.ReplaceTrigger, ctx: PostgresDiffRenderContext) {
        val target = if (ctx.direction == PostgresRenderDirection.UP) op.after else op.before
        if (target.body.isNullOrBlank()) {
            // Mirrors PostgresDiffFunctionOps: a missing body on the
            // active side blocks with ROUTINE_DOWN_BODY_UNKNOWN (down)
            // or MANUAL_ACTION_REQUIRED (up). Trigger bodies share the
            // same body-availability contract as routine bodies — once
            // F.2 body-embedding lands the conditions can relax.
            val isDown = ctx.direction == PostgresRenderDirection.DOWN
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
                    "PostgreSQL requires a function reference on the $sideField side; " +
                    "supply the trigger function name in the schema file or skip `--generate-rollback`.",
                code = code,
            )
            ctx.addBlocker(reason, operationIds = setOf(op.id))
            return
        }
        val resolution = ctx.options.triggerCapability.resolve(ctx.options.postgresMajorVersion)
        when (resolution) {
            TriggerCapabilityResolution.Active -> emitCreate(op, target, ctx, orReplace = true)
            TriggerCapabilityResolution.Disabled -> emitDropCreateReplaceFallback(op, target, ctx)
        }
    }

    private fun emitCreate(
        op: DiffOperation,
        trigger: TriggerDefinition,
        ctx: PostgresDiffRenderContext,
        orReplace: Boolean,
    ) {
        val ref = op.objectRef
        val validation = validateBodyAsFunctionReference(trigger.body)
        if (validation is FunctionReferenceValidation.Invalid) {
            ctx.skip(
                op,
                "Trigger '${ref.rootName}' body is not a PostgreSQL function reference. " +
                    "${validation.reason} PostgreSQL renders `EXECUTE FUNCTION <ref>` — inline " +
                    "PL/pgSQL bodies must be moved into a separate function and referenced by name.",
                code = "TRIGGER_BODY_NOT_FUNCTION_REFERENCE",
            )
            ctx.addBlocker(
                MigrationBlockedReason.TRIGGER_BODY_NOT_FUNCTION_REFERENCE,
                operationIds = setOf(op.id),
            )
            return
        }
        val sql = buildCreateSql(ref.rootName, trigger, ctx, orReplace = orReplace)
        ctx.emit(op, sql, PostgresDiffRenderContext.POSTGRES_METADATA_HINTS)
    }

    private fun emitDrop(
        op: DiffOperation,
        trigger: TriggerDefinition,
        ctx: PostgresDiffRenderContext,
    ) {
        val sql = "DROP TRIGGER ${ctx.sql.quote(op.objectRef.rootName)} " +
            "ON ${ctx.sql.quote(trigger.table)};"
        ctx.emit(op, sql, PostgresDiffRenderContext.POSTGRES_METADATA_HINTS)
    }

    private fun emitDropCreateReplaceFallback(
        op: DiffOperation.ReplaceTrigger,
        target: TriggerDefinition,
        ctx: PostgresDiffRenderContext,
    ) {
        val before = if (ctx.direction == PostgresRenderDirection.UP) op.before else op.after
        // Pre-validate the active-side body before either statement
        // hits the runner. A bad function reference must block before
        // we drop the existing trigger.
        val validation = validateBodyAsFunctionReference(target.body)
        if (validation is FunctionReferenceValidation.Invalid) {
            ctx.skip(
                op,
                "ReplaceTrigger '${op.objectRef.rootName}' body is not a PostgreSQL function reference. " +
                    "${validation.reason} Drop+Create fallback aborted before any DDL is emitted.",
                code = "TRIGGER_BODY_NOT_FUNCTION_REFERENCE",
            )
            ctx.addBlocker(
                MigrationBlockedReason.TRIGGER_BODY_NOT_FUNCTION_REFERENCE,
                operationIds = setOf(op.id),
            )
            return
        }
        val dropSql = "DROP TRIGGER ${ctx.sql.quote(op.objectRef.rootName)} " +
            "ON ${ctx.sql.quote(before.table)};"
        val createSql = buildCreateSql(op.objectRef.rootName, target, ctx, orReplace = false)
        ctx.emit(op, dropSql, PostgresDiffRenderContext.POSTGRES_METADATA_HINTS)
        ctx.emit(op, createSql, PostgresDiffRenderContext.POSTGRES_METADATA_HINTS)
        ctx.addDiagnostic(
            code = W_TRIGGER_REPLACE_GAP,
            operationId = op.id,
            message = "Trigger '${op.objectRef.rootName}' is replaced via DROP + CREATE because the " +
                "PostgreSQL target does not advertise PG-14+; while the two statements run there is " +
                "a short window in which the trigger does not fire. A strict execution mode should " +
                "treat this as MANUAL_ACTION_REQUIRED.",
            severity = DiffDiagnostic.Severity.WARNING,
        )
    }

    private fun buildCreateSql(
        triggerName: String,
        trigger: TriggerDefinition,
        ctx: PostgresDiffRenderContext,
        orReplace: Boolean,
    ): String = buildString {
        append("CREATE ")
        if (orReplace) append("OR REPLACE ")
        append("TRIGGER ").append(ctx.sql.quote(triggerName)).append('\n')
        append("    ").append(trigger.timing.toSqlKeyword())
            .append(' ').append(trigger.event.toSqlKeyword()).append('\n')
        append("    ON ").append(ctx.sql.quote(trigger.table)).append('\n')
        append("    FOR EACH ").append(trigger.forEach.toSqlKeyword()).append('\n')
        trigger.condition?.takeIf { it.isNotBlank() }?.let { cond ->
            append("    WHEN (").append(cond).append(")\n")
        }
        // body has been validated as a strict function reference;
        // emit it raw — function references never need quoting wrappers
        // beyond what the validator already enforces.
        append("    EXECUTE FUNCTION ").append(trigger.body!!.trim()).append(';')
    }

    /**
     * Validates `TriggerDefinition.body` against PostgreSQL's
     * `EXECUTE FUNCTION` grammar (E.2 plan §3 Sub-Slice A.2).
     *
     * Accepted shape: `[schema.]identifier(arg, arg, ...)` where each
     * argument is a literal token (string, numeric, identifier) or
     * empty. Multiline strings, `BEGIN`/`END` blocks and statement
     * separators are rejected — inline PL/pgSQL bodies are out of E.2
     * scope.
     */
    internal fun validateBodyAsFunctionReference(rawBody: String?): FunctionReferenceValidation {
        val body = rawBody?.trim().orEmpty()
        if (body.isEmpty()) {
            return FunctionReferenceValidation.Invalid("Body is empty.")
        }
        if ('\n' in body) {
            return FunctionReferenceValidation.Invalid("Body contains line breaks.")
        }
        val withoutTrailingSemi = if (body.endsWith(";")) body.dropLast(1).trim() else body
        if (';' in withoutTrailingSemi) {
            return FunctionReferenceValidation.Invalid("Body contains multiple statements.")
        }
        if (BEGIN_END_REGEX.containsMatchIn(withoutTrailingSemi)) {
            return FunctionReferenceValidation.Invalid("Body looks like a BEGIN/END block.")
        }
        val match = FUNCTION_REF_REGEX.matchEntire(withoutTrailingSemi)
            ?: return FunctionReferenceValidation.Invalid(
                "Body does not match `[schema.]identifier([arg, ...])`.",
            )
        // Re-walk the argument list to reject obviously inline-SQL-ish
        // tokens — nested function calls, set-returning subqueries and
        // similar shapes are out of scope here even though they would
        // syntactically be a function reference. groupValues[0] is the
        // full match, [1] is the args group from FUNCTION_REF_REGEX.
        val args = match.groupValues[1]
        if (args.isNotBlank() && DISALLOWED_ARG_TOKEN_REGEX.containsMatchIn(args)) {
            return FunctionReferenceValidation.Invalid(
                "Body contains argument tokens that are not literal/identifier/empty.",
            )
        }
        return FunctionReferenceValidation.Ok
    }

    internal sealed interface FunctionReferenceValidation {
        data object Ok : FunctionReferenceValidation
        data class Invalid(val reason: String) : FunctionReferenceValidation
    }

    private fun TriggerTiming.toSqlKeyword(): String = when (this) {
        TriggerTiming.BEFORE -> "BEFORE"
        TriggerTiming.AFTER -> "AFTER"
        TriggerTiming.INSTEAD_OF -> "INSTEAD OF"
    }

    private fun TriggerEvent.toSqlKeyword(): String = name

    private fun TriggerForEach.toSqlKeyword(): String = name

    internal const val W_TRIGGER_REPLACE_GAP: String = "W_TRIGGER_REPLACE_GAP"

    // [optional schema.]identifier ( ARGS )
    // - identifier: leading letter/underscore, then letter/digit/underscore
    // - argument-list: anything that is not an outer paren — fine-grained
    //   token check happens against [DISALLOWED_ARG_TOKEN_REGEX].
    private val FUNCTION_REF_REGEX = Regex(
        """^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)?\(([^()]*)\)$""",
    )
    private val BEGIN_END_REGEX = Regex("""\b(BEGIN|END)\b""", RegexOption.IGNORE_CASE)

    // Anything in the argument list that is not a single-quoted string
    // literal, numeric literal, identifier token, comma or whitespace
    // triggers a rejection. The narrow allow-list (rather than a broad
    // deny-list) makes future loosening intentional, not accidental.
    private val DISALLOWED_ARG_TOKEN_REGEX = Regex(
        """[^A-Za-z0-9_,\s'\.\-]""",
    )
}
