package dev.dmigrate.driver

import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * E.3 MySQL Sequence Drift-Check Sub-Slice B (2026-05-20): per-
 * declaration decision logic for the renderer-side drift gate.
 *
 * Mirrors [CheckPreflightGate] structurally — sealed [Decision]
 * hierarchy, dialect-/renderer-context-free helper — but the
 * `MISSING` status routing depends on the operation INTENT
 * (`CREATE` / `ALTER` / `DROP`) because "the live object isn't
 * there" means different things per op:
 *
 * - `CreateSequence` wants the canonical objects to be MISSING
 *   (the bootstrap will create them); CANONICAL is also OK
 *   (subsequent CreateSequence in the same migration); DRIFT
 *   blocks.
 * - `AlterSequence` needs the sequence row to be CANONICAL —
 *   MISSING or DRIFT both block (cannot ALTER a sequence that
 *   isn't there or whose live state diverges from the plan's
 *   `before` snapshot).
 * - `DropSequence` accepts MISSING as idempotent ("already
 *   gone") and CANONICAL as the normal-case; DRIFT still blocks
 *   so the operator confirms the divergence before dropping a
 *   row whose live values aren't what the plan expects.
 *
 * The CLI stage (Sub-Slice C) collects all relevant declarations
 * per op and calls [decide] once per declaration; the first
 * non-Proceed result wins. Renderer integration (Sub-Slice D)
 * threads the chosen [Decision] into `ctx.skip` /
 * `ctx.addBlocker` / `ctx.info`.
 */
object MysqlSequenceCanonicityGate {

    /** Categorises the diff-op so the gate can route MISSING context-correctly. */
    enum class OpIntent {
        /**
         * `CreateSequence` UP: the bootstrap path will create
         * helper-table, routines, row, and column-bound trigger.
         * MISSING is the canonical pre-state; CANONICAL is the
         * "idempotent re-run" pre-state.
         */
        CREATE,

        /**
         * `AlterSequence` UP or DOWN: the renderer emits `UPDATE
         * dmg_sequences SET …`. The row MUST exist and its current
         * managed fields MUST match the plan's `before` snapshot.
         */
        ALTER,

        /**
         * `DropSequence` UP: the renderer emits `DELETE FROM
         * dmg_sequences …`. MISSING means the row is already gone
         * (idempotent); DRIFT means the live row diverges from the
         * plan's `sequence` snapshot — block so the operator
         * confirms.
         */
        DROP,
    }

    sealed interface Decision {
        /** Renderer proceeds with the SQL emission for this op. */
        data object Proceed : Decision

        /**
         * Renderer skips the op without surfacing a Migration-
         * Blocker. The report records the declaration so the
         * operator can see the probe outcome (e.g. file-to-file
         * mode: `NOT_RUN_FILE_TARGET`). The renderer should call
         * `ctx.info(op, message, code)` rather than
         * `ctx.skip(op, …, severity = INFO)` so the op stays in
         * the rendered set; pick the call site appropriate to
         * the kind.
         */
        data class Info(val code: String, val message: String) : Decision

        /**
         * Renderer blocks the op with [reason] + [code] +
         * [message]. The CLI stage routes the message through
         * `ctx.skip` + `ctx.addBlocker` exactly like the F.5
         * preflight gate.
         */
        data class Block(
            val code: String,
            val reason: MigrationBlockedReason,
            val message: String,
        ) : Decision
    }

    /**
     * Resolves a single [declaration] against the operation
     * [intent] and returns the per-status / per-kind decision.
     * The caller is responsible for collecting all relevant
     * declarations per op and applying the "first non-Proceed
     * wins" rule.
     */
    fun decide(
        declaration: MysqlSequenceCanonicityDeclaration,
        intent: OpIntent,
    ): Decision = when (declaration.status) {
        MysqlSequenceCanonicityStatus.CANONICAL -> Decision.Proceed
        MysqlSequenceCanonicityStatus.DRIFT -> Decision.Block(
            code = driftCodeFor(declaration.kind),
            reason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
            message = buildDriftMessage(declaration),
        )
        MysqlSequenceCanonicityStatus.MISSING -> decideMissing(declaration, intent)
        MysqlSequenceCanonicityStatus.PROBE_RUNTIME_ERROR -> Decision.Block(
            code = PROBE_RUNTIME_ERROR_CODE,
            reason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
            message = buildRuntimeErrorMessage(declaration),
        )
        MysqlSequenceCanonicityStatus.NOT_RUN_FILE_TARGET -> Decision.Info(
            code = NOT_RUN_FILE_TARGET_CODE,
            message = "Sequence drift-check skipped: file-to-file mode has no live DB to probe.",
        )
        MysqlSequenceCanonicityStatus.NOT_RUN_POLICY -> Decision.Info(
            code = NOT_RUN_POLICY_CODE,
            message = "Sequence drift-check skipped by operator policy.",
        )
    }

    private fun decideMissing(
        declaration: MysqlSequenceCanonicityDeclaration,
        intent: OpIntent,
    ): Decision = when (intent) {
        OpIntent.CREATE -> Decision.Proceed
        OpIntent.DROP -> when (declaration.kind) {
            // The Sequence-row absence is idempotent for DROP.
            MysqlSequenceCanonicityKind.SEQUENCE_ROW -> Decision.Proceed
            // Support-table / routine / trigger absence on a DROP
            // means the catalog is already torn down — let the
            // renderer's IF-EXISTS guards swallow it.
            MysqlSequenceCanonicityKind.SUPPORT_TABLE,
            MysqlSequenceCanonicityKind.NEXTVAL_ROUTINE,
            MysqlSequenceCanonicityKind.SETVAL_ROUTINE,
            MysqlSequenceCanonicityKind.SUPPORT_TRIGGER,
            -> Decision.Proceed
        }
        OpIntent.ALTER -> Decision.Block(
            code = MISSING_FOR_ALTER_CODE,
            reason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
            message = buildMissingForAlterMessage(declaration),
        )
    }

    private fun driftCodeFor(kind: MysqlSequenceCanonicityKind): String = when (kind) {
        MysqlSequenceCanonicityKind.SUPPORT_TABLE -> DRIFT_TABLE_CODE
        MysqlSequenceCanonicityKind.NEXTVAL_ROUTINE,
        MysqlSequenceCanonicityKind.SETVAL_ROUTINE,
        -> DRIFT_ROUTINE_CODE
        MysqlSequenceCanonicityKind.SEQUENCE_ROW -> DRIFT_ROW_CODE
        MysqlSequenceCanonicityKind.SUPPORT_TRIGGER -> DRIFT_TRIGGER_CODE
    }

    private fun buildDriftMessage(d: MysqlSequenceCanonicityDeclaration): String = buildString {
        append("MySQL helper-table drift detected on ").append(d.kind.name.lowercase())
        append(" `").append(d.objectName).append('`')
        d.driftField?.let { append(": field `").append(it).append('`') }
        d.expected?.let { append(" expected `").append(it).append('`') }
        d.actual?.let { append(", actual `").append(it).append('`') }
        append(". Reconcile the live state with the plan or re-emit with --plan-only to refresh.")
    }

    private fun buildMissingForAlterMessage(d: MysqlSequenceCanonicityDeclaration): String =
        "MySQL sequence `${d.objectName}` is missing in the live database; the plan " +
            "expected an `AlterSequence` against an existing row. The operator must either " +
            "switch the op to `CreateSequence` (the row is genuinely absent) or restore the " +
            "sequence outside this migration before re-running."

    private fun buildRuntimeErrorMessage(d: MysqlSequenceCanonicityDeclaration): String = buildString {
        append("MySQL sequence drift-check failed technically for ").append(d.kind.name.lowercase())
        append(" `").append(d.objectName).append('`')
        if (!d.problem.isNullOrBlank()) append(": ").append(d.problem)
        append('.')
    }

    /** Drift in `dmg_sequences` column signature. */
    const val DRIFT_TABLE_CODE: String = "E124_MYSQL_SEQUENCE_DRIFT_TABLE"

    /** Drift in `dmg_nextval` / `dmg_setval` body marker. */
    const val DRIFT_ROUTINE_CODE: String = "E124_MYSQL_SEQUENCE_DRIFT_ROUTINE"

    /** Drift in a `dmg_sequences` row's managed fields. */
    const val DRIFT_ROW_CODE: String = "E124_MYSQL_SEQUENCE_DRIFT_ROW"

    /** Drift in a `dmg_seq_…` trigger body marker / sequence reference. */
    const val DRIFT_TRIGGER_CODE: String = "E124_MYSQL_SEQUENCE_DRIFT_TRIGGER"

    /** `AlterSequence` against a non-existent row. */
    const val MISSING_FOR_ALTER_CODE: String = "E124_MYSQL_SEQUENCE_MISSING_FOR_ALTER"

    /** The probe itself failed (privileges, connection, …). */
    const val PROBE_RUNTIME_ERROR_CODE: String = "E124_MYSQL_SEQUENCE_DRIFT_PROBE_FAILED"

    /** File-to-file mode: no live DB available. */
    const val NOT_RUN_FILE_TARGET_CODE: String = "MYSQL_SEQUENCE_DRIFT_NOT_RUN_FILE_TARGET"

    /** Operator-suppressed (future `--skip-sequence-drift-check`). */
    const val NOT_RUN_POLICY_CODE: String = "MYSQL_SEQUENCE_DRIFT_NOT_RUN_POLICY"
}
