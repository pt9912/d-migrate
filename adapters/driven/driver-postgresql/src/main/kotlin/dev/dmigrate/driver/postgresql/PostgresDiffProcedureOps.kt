package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.ParameterDirection
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.RoutineSecurity
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * E.1 Routine-Migration Slice B — PostgreSQL procedure rendering.
 *
 * Mirrors [PostgresDiffFunctionOps] but for `PROCEDURE` operations.
 * The renderer covers `CREATE OR REPLACE PROCEDURE` (Up + Down via
 * inverse), `DROP PROCEDURE` (Up + Down via inverse), and the
 * Up-only `ReplaceProcedure` path. Down-rendering for `ReplaceProcedure`
 * requires the prior body to be known on the operation; otherwise the
 * renderer blocks with `ROLLBACK_NOT_POSSIBLE` and the canonical
 * diagnostic code `ROUTINE_DOWN_BODY_UNKNOWN` (Slice C.1.b migrated
 * this from the older `ROUTINE_REPLACE_DOWN_BODY_UNKNOWN`).
 *
 * Procedures differ from functions in two ways: (1) no `RETURNS`
 * clause and (2) PostgreSQL only supports procedures from 11+. The
 * server-version capability gate is deliberately out of scope for
 * Slice B — Plan §3 routes that decision through `create_or_replace_routine`,
 * which Slice C wires in for MySQL. Until that lands, the renderer
 * always emits the `OR REPLACE` form on the Up path, which matches
 * the function-side behaviour today.
 */
internal object PostgresDiffProcedureOps {

    fun renderCreateProcedure(op: DiffOperation.CreateProcedure, ctx: PostgresDiffRenderContext) {
        when (ctx.direction) {
            PostgresRenderDirection.UP -> emitCreateOrReplace(op, op.objectRef, op.procedure, ctx, orReplace = false)
            PostgresRenderDirection.DOWN -> emitDrop(op, op.objectRef, op.procedure, ctx)
        }
    }

    fun renderReplaceProcedure(op: DiffOperation.ReplaceProcedure, ctx: PostgresDiffRenderContext) {
        val target = if (ctx.direction == PostgresRenderDirection.UP) op.after else op.before
        if (target.body.isNullOrBlank()) {
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
                "ReplaceProcedure $side for '${op.objectRef.rootName}': body is unknown. " +
                    "Render path requires a complete body on the $sideField side; " +
                    "until the F.2 body-embedding gate lands the operator must supply a full body " +
                    "in the schema file or run without `--generate-rollback`.",
                code = code,
            )
            ctx.addBlocker(reason, operationIds = setOf(op.id))
            return
        }
        emitCreateOrReplace(op, op.objectRef, target, ctx, orReplace = true)
    }

    fun renderDropProcedure(op: DiffOperation.DropProcedure, ctx: PostgresDiffRenderContext) {
        when (ctx.direction) {
            PostgresRenderDirection.UP -> emitDrop(op, op.objectRef, op.procedure, ctx)
            PostgresRenderDirection.DOWN -> emitCreateOrReplace(op, op.objectRef, op.procedure, ctx, orReplace = false)
        }
    }

    private fun emitCreateOrReplace(
        op: DiffOperation,
        ref: DiffObjectRef,
        proc: ProcedureDefinition,
        ctx: PostgresDiffRenderContext,
        orReplace: Boolean,
    ) {
        val body = proc.body
        if (body.isNullOrBlank()) {
            ctx.skip(
                op,
                "CreateProcedure for '${ref.rootName}': body must be non-blank on the source/desired side.",
                code = "ROUTINE_BODY_UNKNOWN",
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
            return
        }
        if (body.contains(DOLLAR_TAG)) {
            ctx.skip(
                op,
                "Procedure '${ref.rootName}' body contains the renderer's dollar-tag `$DOLLAR_TAG`. " +
                    "Tag rotation is not implemented yet — rename the inner tag or supply an " +
                    "alternative via a future overlay-driven slice.",
                code = "ROUTINE_BODY_DOLLAR_TAG_COLLISION",
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
            return
        }
        val sql = buildString {
            append("CREATE ")
            if (orReplace) append("OR REPLACE ")
            append("PROCEDURE ")
            append(ctx.sql.quote(ref.rootName))
            append('(')
            append(renderParameters(proc.parameters))
            append(')')
            proc.language?.let { append('\n').append("  LANGUAGE ").append(quoteLanguage(it)) }
            proc.security?.let { append('\n').append("  SECURITY ").append(it.toSqlKeyword()) }
            proc.searchPath?.takeIf { it.isNotEmpty() }?.let { paths ->
                append('\n').append("  SET search_path = ")
                append(paths.joinToString(", ") { ctx.sql.quote(it) })
            }
            append('\n').append("AS ").append(DOLLAR_TAG).append('\n')
            append(body)
            if (!body.endsWith("\n")) append('\n')
            append(DOLLAR_TAG).append(';')
        }
        ctx.emit(op, sql, PostgresDiffRenderContext.POSTGRES_METADATA_HINTS)
    }

    private fun emitDrop(
        op: DiffOperation,
        ref: DiffObjectRef,
        proc: ProcedureDefinition,
        ctx: PostgresDiffRenderContext,
    ) {
        // PostgreSQL identifies a procedure by `(IN | INOUT)` parameter
        // types only — `OUT` parameters are NOT part of the
        // drop-signature. Same contract as DROP FUNCTION.
        val signatureParams = proc.parameters
            .filter { it.direction != ParameterDirection.OUT }
            .joinToString(", ") { p ->
                if (p.direction == ParameterDirection.INOUT) "INOUT ${p.type}" else p.type
            }
        val sql = buildString {
            append("DROP PROCEDURE ")
            append(ctx.sql.quote(ref.rootName))
            append('(')
            append(signatureParams)
            append(");")
        }
        ctx.emit(op, sql, PostgresDiffRenderContext.POSTGRES_METADATA_HINTS)
    }

    private fun renderParameters(parameters: List<ParameterDefinition>): String =
        parameters.joinToString(", ") { p ->
            val direction = p.direction.name
            val prefix = if (direction == "IN") "" else "$direction "
            "$prefix${p.name} ${p.type}"
        }

    private fun quoteLanguage(language: String): String =
        quotePostgresIdentifier(language)

    private fun RoutineSecurity.toSqlKeyword(): String = when (this) {
        RoutineSecurity.INVOKER -> "INVOKER"
        RoutineSecurity.DEFINER -> "DEFINER"
    }

    private const val DOLLAR_TAG: String = "\$body\$"
}
