package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.ParameterDirection
import dev.dmigrate.core.model.RoutineSecurity
import dev.dmigrate.driver.RoutineCapabilityResolution
import dev.dmigrate.driver.RoutineKind
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.resolve

/**
 * E.1 Routine-Migration Slice C.2 — MySQL routine rendering.
 *
 * Mirrors [PostgresDiffFunctionOps] / [PostgresDiffProcedureOps] in
 * intent but differs in three ways:
 *
 * 1. **Body wrapping**: MySQL has no dollar-quoting. The body is
 *    emitted verbatim as part of the `CREATE FUNCTION ... BEGIN ...
 *    END` statement. The canonical plan artefact is **delimiterfrei**
 *    — no `DELIMITER //` wrapper. The runner submits the statement
 *    directly via JDBC, which respects the multi-statement body
 *    natively. Display-/copy-out variants may add a `DELIMITER`
 *    wrapper in a later slice; the kanonische Output here stays
 *    single-statement.
 * 2. **Capability gating**: the renderer consults
 *    [dev.dmigrate.driver.RoutineCapability] (per the
 *    `routineCapability`/`mysqlServerVersion` fields on
 *    `DdlGenerationOptions`). `Active` ⇒ `CREATE OR REPLACE`,
 *    `Disabled`/`InvalidConfig` ⇒ `MANUAL_ACTION_REQUIRED`. The
 *    `DROP+CREATE` fallback is C.3 territory; in this slice the
 *    Dependency-Guard is fixed to UNKNOWN, so the renderer never
 *    reaches that branch.
 * 3. **Diagnostic code**: emits the canonical
 *    `ROUTINE_DOWN_BODY_UNKNOWN` (plan §1) — MySQL never used the
 *    older Replace-specific spelling.
 */
internal object MysqlDiffRoutineOps {

    fun renderCreateFunction(op: DiffOperation.CreateFunction, ctx: MysqlDiffRenderContext) {
        when (ctx.direction) {
            MysqlRenderDirection.UP -> emitCreateOrReplaceFunction(op, op.objectRef, op.function, ctx, orReplace = false)
            MysqlRenderDirection.DOWN -> emitDropFunction(op, op.objectRef, ctx)
        }
    }

    fun renderReplaceFunction(op: DiffOperation.ReplaceFunction, ctx: MysqlDiffRenderContext) {
        val target = if (ctx.direction == MysqlRenderDirection.UP) op.after else op.before
        if (target.body.isNullOrBlank()) {
            blockMissingBody(op, ctx, "ReplaceFunction")
            return
        }
        when (resolveCapability(ctx, RoutineKind.FUNCTION)) {
            RoutineCapabilityResolution.Active -> emitCreateOrReplaceFunction(
                op, op.objectRef, target, ctx, orReplace = true,
            )
            RoutineCapabilityResolution.Disabled -> blockCapabilityDisabled(op, ctx, "Function")
            RoutineCapabilityResolution.InvalidConfig -> blockCapabilityInvalid(op, ctx, "Function")
        }
    }

    fun renderDropFunction(op: DiffOperation.DropFunction, ctx: MysqlDiffRenderContext) {
        when (ctx.direction) {
            MysqlRenderDirection.UP -> emitDropFunction(op, op.objectRef, ctx)
            MysqlRenderDirection.DOWN -> emitCreateOrReplaceFunction(
                op, op.objectRef, op.function, ctx, orReplace = false,
            )
        }
    }

    fun renderCreateProcedure(op: DiffOperation.CreateProcedure, ctx: MysqlDiffRenderContext) {
        when (ctx.direction) {
            MysqlRenderDirection.UP -> emitCreateOrReplaceProcedure(
                op, op.objectRef, op.procedure, ctx, orReplace = false,
            )
            MysqlRenderDirection.DOWN -> emitDropProcedure(op, op.objectRef, ctx)
        }
    }

    fun renderReplaceProcedure(op: DiffOperation.ReplaceProcedure, ctx: MysqlDiffRenderContext) {
        val target = if (ctx.direction == MysqlRenderDirection.UP) op.after else op.before
        if (target.body.isNullOrBlank()) {
            blockMissingBody(op, ctx, "ReplaceProcedure")
            return
        }
        when (resolveCapability(ctx, RoutineKind.PROCEDURE)) {
            RoutineCapabilityResolution.Active -> emitCreateOrReplaceProcedure(
                op, op.objectRef, target, ctx, orReplace = true,
            )
            RoutineCapabilityResolution.Disabled -> blockCapabilityDisabled(op, ctx, "Procedure")
            RoutineCapabilityResolution.InvalidConfig -> blockCapabilityInvalid(op, ctx, "Procedure")
        }
    }

    fun renderDropProcedure(op: DiffOperation.DropProcedure, ctx: MysqlDiffRenderContext) {
        when (ctx.direction) {
            MysqlRenderDirection.UP -> emitDropProcedure(op, op.objectRef, ctx)
            MysqlRenderDirection.DOWN -> emitCreateOrReplaceProcedure(
                op, op.objectRef, op.procedure, ctx, orReplace = false,
            )
        }
    }

    // ── Capability helpers ────────────────────────────────────────

    private fun resolveCapability(ctx: MysqlDiffRenderContext, kind: RoutineKind): RoutineCapabilityResolution =
        ctx.options.routineCapability.forKind(kind).resolve(ctx.options.mysqlServerVersion)

    private fun blockMissingBody(op: DiffOperation, ctx: MysqlDiffRenderContext, opName: String) {
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
            "$opName $side for '${op.objectRef.rootName}': body is unknown. " +
                "Render path requires a complete body on the $sideField side; " +
                "until the F.2 body-embedding gate lands the operator must supply a full body " +
                "in the schema file or run without `--generate-rollback`.",
            code = code,
        )
        ctx.addBlocker(reason, operationIds = setOf(op.id))
    }

    private fun blockCapabilityDisabled(op: DiffOperation, ctx: MysqlDiffRenderContext, kind: String) {
        // Plan §3 step 3: `Disabled` on MySQL means CREATE OR REPLACE is
        // off for this routine kind. `DROP + CREATE` is conditional on
        // Dependency-Guard=SAFE, which Slice C.3 ships; until then,
        // MANUAL_ACTION_REQUIRED with the explicit reason.
        ctx.skip(
            op,
            "$kind '${op.objectRef.rootName}': CREATE OR REPLACE is disabled by the routine capability " +
                "(either explicitly or because the target server version does not meet the declared " +
                "`minServerVersion` floor). The Dependency-Guard-aware DROP + CREATE fallback ships in " +
                "Slice C.3; until then this operation requires manual handling.",
            code = "ROUTINE_CAPABILITY_DISABLED",
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
    }

    private fun blockCapabilityInvalid(op: DiffOperation, ctx: MysqlDiffRenderContext, kind: String) {
        ctx.skip(
            op,
            "$kind '${op.objectRef.rootName}': routine capability configuration is invalid " +
                "(unparsable or inconsistent). All affected Create/Replace/Drop operations require " +
                "manual handling until the configuration is corrected.",
            code = "ROUTINE_CAPABILITY_CONFIG_INVALID",
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
    }

    // ── Function emission ─────────────────────────────────────────

    private fun emitCreateOrReplaceFunction(
        op: DiffOperation,
        ref: DiffObjectRef,
        fn: dev.dmigrate.core.model.FunctionDefinition,
        ctx: MysqlDiffRenderContext,
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
        val returnType = fn.returns?.type
        if (returnType == null) {
            // MySQL requires every function to declare a return type. A
            // schema-file Function without `returns` (or a reverse-read
            // path that lost the return type) cannot render — block
            // explicitly instead of crashing the renderer.
            ctx.skip(
                op,
                "Function '${ref.rootName}': MySQL function rendering requires a return type. " +
                    "The source/desired definition has none — set `returns:` in the schema file or " +
                    "drop the function to remove it.",
                code = "ROUTINE_RETURN_TYPE_UNKNOWN",
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
            append("\n  RETURNS ").append(returnType)
            renderFunctionCharacteristics(this, fn)
            append('\n')
            append(body)
        }
        ctx.emit(op, sql)
    }

    private fun renderFunctionCharacteristics(
        sb: StringBuilder,
        fn: dev.dmigrate.core.model.FunctionDefinition,
    ) {
        fn.language?.let { sb.append("\n  LANGUAGE ").append(it.uppercase()) }
        if (fn.deterministic == true) sb.append("\n  DETERMINISTIC")
        fn.security?.let { sb.append("\n  SQL SECURITY ").append(it.toMysqlKeyword()) }
    }

    private fun emitDropFunction(op: DiffOperation, ref: DiffObjectRef, ctx: MysqlDiffRenderContext) {
        // MySQL `DROP FUNCTION` does NOT take a parameter signature
        // (unlike PostgreSQL). Function overloading is not a thing in
        // MySQL — names are unique per schema.
        ctx.emit(op, "DROP FUNCTION ${ctx.sql.quote(ref.rootName)};")
    }

    // ── Procedure emission ────────────────────────────────────────

    private fun emitCreateOrReplaceProcedure(
        op: DiffOperation,
        ref: DiffObjectRef,
        proc: dev.dmigrate.core.model.ProcedureDefinition,
        ctx: MysqlDiffRenderContext,
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
        val sql = buildString {
            append("CREATE ")
            if (orReplace) append("OR REPLACE ")
            append("PROCEDURE ")
            append(ctx.sql.quote(ref.rootName))
            append('(')
            append(renderParameters(proc.parameters))
            append(')')
            renderProcedureCharacteristics(this, proc)
            append('\n')
            append(body)
        }
        ctx.emit(op, sql)
    }

    private fun renderProcedureCharacteristics(
        sb: StringBuilder,
        proc: dev.dmigrate.core.model.ProcedureDefinition,
    ) {
        proc.language?.let { sb.append("\n  LANGUAGE ").append(it.uppercase()) }
        proc.security?.let { sb.append("\n  SQL SECURITY ").append(it.toMysqlKeyword()) }
    }

    private fun emitDropProcedure(op: DiffOperation, ref: DiffObjectRef, ctx: MysqlDiffRenderContext) {
        ctx.emit(op, "DROP PROCEDURE ${ctx.sql.quote(ref.rootName)};")
    }

    // ── Shared helpers ────────────────────────────────────────────

    private fun renderParameters(parameters: List<ParameterDefinition>): String =
        parameters.joinToString(", ") { p ->
            // MySQL parameter syntax is `[IN|OUT|INOUT] name type`. The
            // direction precedes the name; the `IN` keyword is the
            // default and is dropped to keep the output terse, matching
            // the canonical-artefact contract.
            val prefix = when (p.direction) {
                ParameterDirection.IN -> ""
                ParameterDirection.OUT -> "OUT "
                ParameterDirection.INOUT -> "INOUT "
            }
            "$prefix${p.name} ${p.type}"
        }

    private fun RoutineSecurity.toMysqlKeyword(): String = when (this) {
        RoutineSecurity.INVOKER -> "INVOKER"
        RoutineSecurity.DEFINER -> "DEFINER"
    }
}
