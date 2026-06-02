package dev.dmigrate.driver

import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.PlannerBlockerClassifier

/**
 * F.5 Sub-Slice E.3 (2026-05-19): shared decision logic for the
 * per-dialect renderer gates that consult
 * [DdlGenerationOptions.checkPreflights].
 *
 * The renderers (PG, MySQL, SQLite) all need to read the live-probe
 * status and translate it into skip/blocker emission. This helper
 * centralises:
 *
 * - the per-operation lookup (match by [operationId]);
 * - the operator-facing message format (table / constraint /
 *   failing-rows summary);
 * - the per-status routing
 *   (PASSED / NOT_RUN_* → proceed;
 *    FAILED → `CHECK_PREFLIGHT_VIOLATIONS` → MANUAL_ACTION_REQUIRED;
 *    PROBE_RUNTIME_ERROR → `CHECK_PREFLIGHT_RUNTIME_ERROR` →
 *    MANUAL_ACTION_REQUIRED).
 *
 * Adapter renderers consume this and apply [Decision.Block] to their
 * own `ctx.skip` / `ctx.addBlocker` API — the helper itself is
 * dependency-free (no render-context type).
 */
object CheckPreflightGate {

    sealed interface Decision {
        data object Proceed : Decision
        data class Block(
            val code: String,
            val reason: MigrationBlockedReason,
            val message: String,
        ) : Decision
    }

    /**
     * Resolves [operationId] against the supplied [declarations] and
     * returns the per-status decision.
     */
    fun decide(
        operationId: String,
        declarations: List<CheckPreflightDeclaration>,
    ): Decision {
        val declaration = declarations.firstOrNull { it.operationId == operationId } ?: return Decision.Proceed
        return when (declaration.status) {
            CheckPreflightStatus.FAILED -> Decision.Block(
                code = PlannerBlockerClassifier.CHECK_PREFLIGHT_VIOLATIONS_CODE,
                reason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
                message = buildViolationsMessage(declaration),
            )
            CheckPreflightStatus.PROBE_RUNTIME_ERROR -> Decision.Block(
                code = PlannerBlockerClassifier.CHECK_PREFLIGHT_RUNTIME_ERROR_CODE,
                reason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
                message = buildRuntimeErrorMessage(declaration),
            )
            CheckPreflightStatus.PASSED,
            CheckPreflightStatus.NOT_RUN_FILE_TARGET,
            CheckPreflightStatus.NOT_RUN_POLICY,
            -> Decision.Proceed
        }
    }

    private fun buildViolationsMessage(d: CheckPreflightDeclaration): String = buildString {
        append("CHECK preflight on `").append(d.table).append("` for constraint `")
        append(d.constraintName).append("` reports existing-row violations of the predicate (")
        append(d.expression).append(").")
        if (d.failingRows != null) append(" Failing rows: ").append(d.failingRows).append('.')
        if (d.totalRows != null) append(" Total rows: ").append(d.totalRows).append('.')
        if (d.sampleRowIds.isNotEmpty()) {
            append(" Sample row ids: ").append(d.sampleRowIds.joinToString(", ")).append('.')
        }
    }

    private fun buildRuntimeErrorMessage(d: CheckPreflightDeclaration): String = buildString {
        append("Live-data CHECK preflight failed technically for `").append(d.table).append('.')
        append(d.constraintName).append('`')
        if (!d.problem.isNullOrBlank()) append(": ").append(d.problem)
        append('.')
    }
}
