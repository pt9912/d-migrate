package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.ParameterDirection
import dev.dmigrate.core.model.RoutineSecurity
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * E.1 Routine-Migration Slice A — PostgreSQL routine rendering.
 *
 * The renderer covers `CREATE OR REPLACE FUNCTION` (Up + Down via
 * inverse), `DROP FUNCTION` (Up + Down via inverse), and the
 * Up-only `ReplaceFunction` path. Down-rendering for `ReplaceFunction`
 * requires the prior body to be known on the operation; otherwise the
 * renderer blocks with `ROLLBACK_NOT_POSSIBLE` and the canonical
 * diagnostic code `ROUTINE_DOWN_BODY_UNKNOWN` (plan §1) so the
 * operator gets a specific reason rather than the generic
 * `DIALECT_UNSUPPORTED_OPERATION`. The Slice C.1.b commit migrated
 * the older Replace-specific `ROUTINE_REPLACE_DOWN_BODY_UNKNOWN`
 * spelling onto the generic name.
 *
 * Body content is wrapped in dollar-quoted blocks. The renderer
 * intentionally uses a fixed `$body$` tag — operators who need a
 * different tag (e.g. because their body contains the literal
 * sequence `$body$`) need a future slice; this is a deliberate
 * carve-out per plan §6.3 (false-positive replace risk is the same
 * carve-out family).
 */
internal object PostgresDiffFunctionOps {

    fun renderCreateFunction(op: DiffOperation.CreateFunction, ctx: PostgresDiffRenderContext) {
        when (ctx.direction) {
            PostgresRenderDirection.UP -> emitCreateOrReplace(op, op.objectRef, op.function, ctx, orReplace = false)
            PostgresRenderDirection.DOWN -> emitDrop(op, op.objectRef, op.function, ctx)
        }
    }

    fun renderReplaceFunction(op: DiffOperation.ReplaceFunction, ctx: PostgresDiffRenderContext) {
        val target = if (ctx.direction == PostgresRenderDirection.UP) op.after else op.before
        if (target.body.isNullOrBlank()) {
            val isDown = ctx.direction == PostgresRenderDirection.DOWN
            val side = if (isDown) "Down" else "Up"
            val sideField = if (isDown) "before" else "after"
            // Plan §1 mandates `ROUTINE_DOWN_BODY_UNKNOWN` as the
            // canonical generic code for any routine Down path that
            // lacks a safe prior body — Slice C.1.b migrated this
            // emission from the older Replace-specific spelling
            // `ROUTINE_REPLACE_DOWN_BODY_UNKNOWN`. The Up path keeps
            // a Replace-specific `_UP_` code (a missing after-body on
            // Replace is structurally different from a missing
            // rollback target).
            val code = if (isDown) "ROUTINE_DOWN_BODY_UNKNOWN" else "ROUTINE_REPLACE_UP_BODY_UNKNOWN"
            val reason = if (isDown) {
                MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE
            } else {
                MigrationBlockedReason.MANUAL_ACTION_REQUIRED
            }
            ctx.skip(
                op,
                "ReplaceFunction $side for '${op.objectRef.rootName}': body is unknown. " +
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

    fun renderDropFunction(op: DiffOperation.DropFunction, ctx: PostgresDiffRenderContext) {
        when (ctx.direction) {
            PostgresRenderDirection.UP -> emitDrop(op, op.objectRef, op.function, ctx)
            PostgresRenderDirection.DOWN -> emitCreateOrReplace(op, op.objectRef, op.function, ctx, orReplace = false)
        }
    }

    private fun emitCreateOrReplace(
        op: DiffOperation,
        ref: DiffObjectRef,
        fn: FunctionDefinition,
        ctx: PostgresDiffRenderContext,
        orReplace: Boolean,
    ) {
        val body = fn.body
        if (body.isNullOrBlank()) {
            ctx.skip(
                op,
                "CreateFunction for '${ref.rootName}': body must be non-blank on the source/desired side.",
                code = "ROUTINE_BODY_UNKNOWN",
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
            return
        }
        if (body.contains(DOLLAR_TAG)) {
            // The renderer wraps the body in `$body$ … $body$`. If the
            // body itself contains that tag the generated SQL would be
            // malformed (silent injection). Block with an explicit
            // MANUAL_ACTION_REQUIRED so the operator picks an
            // alternative tag (or splits the body) instead of
            // silently producing invalid DDL. Future slice can
            // rotate the tag automatically.
            ctx.skip(
                op,
                "Function '${ref.rootName}' body contains the renderer's dollar-tag `$DOLLAR_TAG`. " +
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
            append("FUNCTION ")
            append(ctx.sql.quote(ref.rootName))
            append('(')
            append(renderParameters(fn.parameters))
            append(')')
            append('\n')
            append("  RETURNS ").append(renderReturnType(fn))
            fn.language?.let { append('\n').append("  LANGUAGE ").append(quoteLanguage(it)) }
            fn.security?.let { append('\n').append("  SECURITY ").append(it.toSqlKeyword()) }
            fn.searchPath?.takeIf { it.isNotEmpty() }?.let { paths ->
                append('\n').append("  SET search_path = ")
                append(paths.joinToString(", ") { ctx.sql.quote(it) })
            }
            append('\n').append("AS ").append(DOLLAR_TAG).append('\n')
            append(body)
            // Ensure exactly one newline before the closing dollar-quote so an operator-supplied
            // trailing newline doesn't double up.
            if (!body.endsWith("\n")) append('\n')
            append(DOLLAR_TAG).append(';')
        }
        ctx.emit(op, sql, PostgresDiffRenderContext.POSTGRES_METADATA_HINTS)
    }

    private fun emitDrop(
        op: DiffOperation,
        ref: DiffObjectRef,
        fn: FunctionDefinition,
        ctx: PostgresDiffRenderContext,
    ) {
        val sql = buildString {
            append("DROP FUNCTION ")
            append(ctx.sql.quote(ref.rootName))
            append('(')
            append(renderDropSignature(fn.parameters))
            append(");")
        }
        ctx.emit(op, sql, PostgresDiffRenderContext.POSTGRES_METADATA_HINTS)
    }

    /**
     * F.4 Sub-Slice A.2 Teil 2: PostgreSQL native function rename.
     * `ALTER FUNCTION <fromName>(<signature>) RENAME TO <toName>` —
     * the signature follows the same OUT-excluded, name-omitted
     * convention as DROP FUNCTION because PostgreSQL identifies a
     * function by `(name, IN/INOUT types)` only. The renderer never
     * derives the existing name from `objectRef.path[0]` (which holds
     * the canonical target key for plan/report ID stability).
     */
    fun renderRenameFunction(op: DiffOperation.RenameFunction, ctx: PostgresDiffRenderContext) {
        val (oldName, newName) = if (ctx.direction == PostgresRenderDirection.UP) {
            op.fromName to op.toName
        } else {
            op.toName to op.fromName
        }
        val sql = buildString {
            append("ALTER FUNCTION ")
            append(ctx.sql.quote(oldName))
            append('(')
            append(renderDropSignature(op.signature))
            append(") RENAME TO ")
            append(ctx.sql.quote(newName))
            append(';')
        }
        ctx.emit(op, sql, PostgresDiffRenderContext.POSTGRES_METADATA_HINTS)
    }

    /**
     * PostgreSQL identifies a routine by `(IN | INOUT)` parameter types
     * only — `OUT` parameters are NOT part of the
     * drop/alter-signature. INOUT parameters keep the keyword so the
     * signature matches the catalog. Names are intentionally omitted:
     * PostgreSQL ignores them in DROP / ALTER, only types and
     * direction modifiers count.
     */
    private fun renderDropSignature(parameters: List<ParameterDefinition>): String =
        parameters
            .filter { it.direction != ParameterDirection.OUT }
            .joinToString(", ") { p ->
                if (p.direction == ParameterDirection.INOUT) "INOUT ${p.type}" else p.type
            }

    private fun renderParameters(parameters: List<ParameterDefinition>): String =
        parameters.joinToString(", ") { p ->
            // The direction is part of PostgreSQL's parameter syntax:
            // `IN/OUT/INOUT name type`. Omit `IN` because it's the
            // default and clutters the output.
            val direction = p.direction.name
            val prefix = if (direction == "IN") "" else "$direction "
            "$prefix${p.name} ${p.type}"
        }

    private fun renderReturnType(fn: FunctionDefinition): String =
        fn.returns?.type ?: "void"

    private fun quoteLanguage(language: String): String =
        // Languages like `plpgsql`, `sql`, `plpython3u` are
        // identifiers; let PostgresIdentifiers handle quoting
        // semantics.
        quotePostgresIdentifier(language)

    private fun RoutineSecurity.toSqlKeyword(): String = when (this) {
        RoutineSecurity.INVOKER -> "INVOKER"
        RoutineSecurity.DEFINER -> "DEFINER"
    }

    private const val DOLLAR_TAG: String = "\$body\$"
}
