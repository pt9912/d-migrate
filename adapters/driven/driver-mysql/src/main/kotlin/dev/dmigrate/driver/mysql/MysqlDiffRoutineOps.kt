package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.ParameterDirection
import dev.dmigrate.core.model.RoutineSecurity
import dev.dmigrate.driver.DependencyGuard
import dev.dmigrate.driver.DependencyGuardEvaluator
import dev.dmigrate.driver.EffectiveRoutineCapability
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
 *    [EffectiveRoutineCapability] (per the
 *    `routineCapability`/`mysqlServerVersion` fields on
 *    `DdlGenerationOptions`). `Active` ⇒ `CREATE OR REPLACE`,
 *    `InvalidConfig` ⇒ `MANUAL_ACTION_REQUIRED` (sourced from the
 *    sealed [EffectiveRoutineCapability.Invalid] envelope —
 *    0.9.7-routine-capability-configurable-source Sub-Slice A made the
 *    invalid path representable in the type system). `Disabled` routes
 *    through the dependency guard: `SAFE` ⇒ `DROP + CREATE` (two
 *    statements; see the implicit-commit caveat below), any other
 *    guard state ⇒ `MANUAL_ACTION_REQUIRED`. Slice D.4 swapped the
 *    Slice-C.3 stub heuristic for a topology-driven evaluator that
 *    reads the edge graph populated by Slice D.1
 *    `RoutineDependencyAnalyzer` plus the engine-metadata readers
 *    from Slice D.2 (PostgreSQL) and Slice D.3 (MySQL). Every guard
 *    consultation annotates the report with
 *    `DEPENDENCY_GUARD_TOPOLOGY` so operators see the bewertung came
 *    from a real edge-graph check, not a stub. A SAFE-guard-driven
 *    `DROP + CREATE` additionally emits the
 *    `MYSQL_ROUTINE_DROP_CREATE_NON_ATOMIC` WARNING so the
 *    operational risk is visible alongside the routing decision.
 * 3. **Diagnostic code**: emits the canonical
 *    `ROUTINE_DOWN_BODY_UNKNOWN` (plan §1) — MySQL never used the
 *    older Replace-specific spelling.
 */
internal object MysqlDiffRoutineOps {

    fun renderCreateFunction(op: DiffOperation.CreateFunction, ctx: MysqlDiffRenderContext) {
        // E.1 Slice F.5: Plan §2/§3 requires that ALL routine
        // operations (Create*/Replace*/Drop*) block as
        // MANUAL_ACTION_REQUIRED when the capability mapping for the
        // routine kind is invalid, not just Replace.
        if (resolveCapability(ctx, RoutineKind.FUNCTION) == RoutineCapabilityResolution.InvalidConfig) {
            blockCapabilityInvalid(op, ctx, "Function")
            return
        }
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
            RoutineCapabilityResolution.Disabled -> handleDisabledReplaceFunction(op, target, ctx)
            RoutineCapabilityResolution.InvalidConfig -> blockCapabilityInvalid(op, ctx, "Function")
        }
    }

    fun renderDropFunction(op: DiffOperation.DropFunction, ctx: MysqlDiffRenderContext) {
        // E.1 Slice F.5: InvalidConfig blocks ALL routine ops, see comment in renderCreateFunction.
        if (resolveCapability(ctx, RoutineKind.FUNCTION) == RoutineCapabilityResolution.InvalidConfig) {
            blockCapabilityInvalid(op, ctx, "Function")
            return
        }
        when (ctx.direction) {
            MysqlRenderDirection.UP -> emitDropFunction(op, op.objectRef, ctx)
            MysqlRenderDirection.DOWN -> emitCreateOrReplaceFunction(
                op, op.objectRef, op.function, ctx, orReplace = false,
            )
        }
    }

    fun renderCreateProcedure(op: DiffOperation.CreateProcedure, ctx: MysqlDiffRenderContext) {
        // E.1 Slice F.5: InvalidConfig blocks ALL routine ops, see comment in renderCreateFunction.
        if (resolveCapability(ctx, RoutineKind.PROCEDURE) == RoutineCapabilityResolution.InvalidConfig) {
            blockCapabilityInvalid(op, ctx, "Procedure")
            return
        }
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
            RoutineCapabilityResolution.Disabled -> handleDisabledReplaceProcedure(op, target, ctx)
            RoutineCapabilityResolution.InvalidConfig -> blockCapabilityInvalid(op, ctx, "Procedure")
        }
    }

    fun renderDropProcedure(op: DiffOperation.DropProcedure, ctx: MysqlDiffRenderContext) {
        // E.1 Slice F.5: InvalidConfig blocks ALL routine ops, see comment in renderCreateFunction.
        if (resolveCapability(ctx, RoutineKind.PROCEDURE) == RoutineCapabilityResolution.InvalidConfig) {
            blockCapabilityInvalid(op, ctx, "Procedure")
            return
        }
        when (ctx.direction) {
            MysqlRenderDirection.UP -> emitDropProcedure(op, op.objectRef, ctx)
            MysqlRenderDirection.DOWN -> emitCreateOrReplaceProcedure(
                op, op.objectRef, op.procedure, ctx, orReplace = false,
            )
        }
    }

    // ── Capability helpers ────────────────────────────────────────

    private fun resolveCapability(ctx: MysqlDiffRenderContext, kind: RoutineKind): RoutineCapabilityResolution =
        when (val cap = ctx.options.routineCapability) {
            is EffectiveRoutineCapability.Invalid -> RoutineCapabilityResolution.InvalidConfig
            is EffectiveRoutineCapability.Valid -> cap.forKind(kind).resolve(ctx.options.mysqlServerVersion)
        }

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

    /**
     * E.1 Routine-Migration Slice C.3/D.4: when capability is
     * `Disabled` for the routine kind, fall back to `DROP + CREATE`
     * if the dependency guard reports `SAFE`. Otherwise block with
     * `MANUAL_ACTION_REQUIRED`. D.4 replaced the original stub with
     * topology-driven evaluation and annotates each consultation with
     * `DEPENDENCY_GUARD_TOPOLOGY`.
     */
    private fun handleDisabledReplaceFunction(
        op: DiffOperation.ReplaceFunction,
        target: dev.dmigrate.core.model.FunctionDefinition,
        ctx: MysqlDiffRenderContext,
    ) {
        val guard = evaluateGuard(op, ctx)
        annotateHeuristic(op, ctx, "Function", guard)
        if (guard == DependencyGuard.SAFE) {
            // MySQL DDL implicitly commits (see
            // MYSQL_IMPLICIT_COMMIT_DDL_HINTS in
            // MysqlDiffRenderContext): the DROP commits before the
            // CREATE runs, so a CREATE failure leaves the routine
            // gone. The dependency guard cannot detect that
            // operational risk — emit a WARNING so the operator
            // sees the trade-off alongside the heuristic
            // annotation.
            warnDropCreateNonAtomic(op, ctx, "Function")
            emitDropFunction(op, op.objectRef, ctx)
            emitCreateOrReplaceFunction(op, op.objectRef, target, ctx, orReplace = false)
        } else {
            blockCapabilityDisabled(op, ctx, "Function", guard)
        }
    }

    private fun handleDisabledReplaceProcedure(
        op: DiffOperation.ReplaceProcedure,
        target: dev.dmigrate.core.model.ProcedureDefinition,
        ctx: MysqlDiffRenderContext,
    ) {
        val guard = evaluateGuard(op, ctx)
        annotateHeuristic(op, ctx, "Procedure", guard)
        if (guard == DependencyGuard.SAFE) {
            warnDropCreateNonAtomic(op, ctx, "Procedure")
            emitDropProcedure(op, op.objectRef, ctx)
            emitCreateOrReplaceProcedure(op, op.objectRef, target, ctx, orReplace = false)
        } else {
            blockCapabilityDisabled(op, ctx, "Procedure", guard)
        }
    }

    private fun evaluateGuard(op: DiffOperation, ctx: MysqlDiffRenderContext): DependencyGuard {
        // No plan means the renderer was invoked without diff context
        // (only happens in tightly-scoped helper tests). Treat as
        // UNKNOWN so the guard cannot accidentally green-light a
        // DROP+CREATE that the caller didn't authorise.
        val plan = ctx.plan ?: return DependencyGuard.UNKNOWN
        return DependencyGuardEvaluator.evaluate(plan, op)
    }

    private fun annotateHeuristic(
        op: DiffOperation,
        ctx: MysqlDiffRenderContext,
        kind: String,
        guard: DependencyGuard,
    ) {
        ctx.info(
            op,
            "$kind '${op.objectRef.rootName}': dependency-guard evaluation is topology-driven " +
                "(Slice D.4 — uses the edge graph populated by RoutineDependencyAnalyzer plus the " +
                "PG / MySQL engine-metadata readers). Result for this op: $guard.",
            code = "DEPENDENCY_GUARD_TOPOLOGY",
        )
    }

    private fun warnDropCreateNonAtomic(op: DiffOperation, ctx: MysqlDiffRenderContext, kind: String) {
        // The Replace op itself carries risk.up = SAFE because the
        // operator's intent is a body swap. The DROP + CREATE
        // fallback turns that intent into two implicit-commit DDL
        // statements; the existing destructive-guard pipeline
        // therefore cannot flag the non-atomicity automatically.
        // This WARNING makes the risk explicit at report time.
        ctx.warning(
            op,
            "$kind '${op.objectRef.rootName}': capability is `Disabled` and the dependency guard is " +
                "`SAFE`, so the renderer falls back to `DROP` + `CREATE`. MySQL DDL implicitly commits " +
                "between the two statements — if the `CREATE` fails after the `DROP` has committed, " +
                "the routine is gone with no automatic rollback. Run in a controlled window or wait " +
                "for `CREATE OR REPLACE` capability (target server upgrade / capability config).",
            code = "MYSQL_ROUTINE_DROP_CREATE_NON_ATOMIC",
        )
    }

    private fun blockCapabilityDisabled(
        op: DiffOperation,
        ctx: MysqlDiffRenderContext,
        kind: String,
        guard: DependencyGuard,
    ) {
        ctx.skip(
            op,
            "$kind '${op.objectRef.rootName}': CREATE OR REPLACE is disabled by the routine capability " +
                "(either explicitly or because the target server version does not meet the declared " +
                "`minServerVersion` floor). The Dependency-Guard-aware DROP + CREATE fallback is blocked " +
                "because the guard reported $guard for this op — only SAFE permits the fallback.",
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
            // E.1 Slice F.6: MySQL syntax positions `DEFINER = user`
            // BEFORE the routine keyword. The literal is passed through
            // verbatim from the schema (e.g. `'alice'@'%'` or
            // `CURRENT_USER`); the operator is responsible for the
            // syntactic shape.
            fn.definer?.let { append("DEFINER = ").append(it).append(' ') }
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
            // E.1 Slice F.6: see emitCreateOrReplaceFunction for the
            // DEFINER positioning rationale.
            proc.definer?.let { append("DEFINER = ").append(it).append(' ') }
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
