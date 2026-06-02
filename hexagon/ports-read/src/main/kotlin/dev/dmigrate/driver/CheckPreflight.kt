package dev.dmigrate.driver

/**
 * F.5 Sub-Slice E (2026-05-19): cross-dialect live-data preflight for
 * newly-added CHECK constraints.
 *
 * A new restrictive CHECK clause silently widens the table contract:
 * the migration may render and apply on an empty DB without warning,
 * but adding the same constraint to a production schema where
 * existing rows violate the predicate produces a runtime constraint-
 * violation error mid-rebuild. The preflight runs a read-only
 * `SELECT count(*) FROM <table> WHERE NOT (<expression>)` against the
 * live target before render/execute; the renderer consults the
 * declaration via its [bindingKey] and refuses to emit the ADD when
 * the status is [CheckPreflightStatus.FAILED].
 *
 * The shape mirrors [SqliteCastPreflightDeclaration] so the
 * migration-pipeline plumbing (planner → runner → renderer → report)
 * is symmetric for the two preflight kinds.
 *
 * @property operationId the [DiffOperation.id] this declaration
 *           backs; the renderer matches by id (NOT by name) so two
 *           constraints with the same name in different tables don't
 *           collide.
 * @property dialect informational; the runner uses it to pick the
 *           per-dialect probe implementation.
 * @property table the table whose rows the probe scans.
 * @property constraintName the constraint identifier; surfaced in
 *           the report for operator-readable diagnostics.
 * @property expression the CHECK predicate as authored in the schema
 *           file. The probe wraps it with `NOT (...)`.
 * @property status the live-probe outcome; see [CheckPreflightStatus].
 * @property sqlHash a deterministic hash over the probe SQL the
 *           runner executed. Two declarations with the same
 *           [bindingKey] but different sqlHash indicate the
 *           expression changed between plan and execute — the
 *           renderer treats that as a re-plan obligation.
 * @property totalRows total row count the probe observed; informational.
 * @property failingRows number of violating rows when status is
 *           FAILED. Surfaced verbatim in the operator-facing report.
 * @property sampleRowIds best-effort representative offending rows
 *           (typically primary-key tuples) for operator triage.
 *           Empty when the probe cannot project a stable identifier.
 * @property problem free-text error context when a technical failure
 *           occurred during probing (status PROBE_RUNTIME_ERROR).
 */
data class CheckPreflightDeclaration(
    val operationId: String,
    val dialect: String,
    val table: String,
    val constraintName: String,
    val expression: String,
    val status: CheckPreflightStatus,
    val sqlHash: String,
    val totalRows: Long? = null,
    val failingRows: Long? = null,
    val sampleRowIds: List<String> = emptyList(),
    val problem: String? = null,
) {
    /**
     * Stable identity key for matching a declaration against a
     * rendered [DiffOperation.AddConstraint]. Uses ASCII Unit
     * Separator (``) between fields so identifier characters
     * (dots, dashes, table names with whitespace) cannot collide.
     *
     * The renderer computes the same key during emission and looks
     * the declaration up by exact-match — no fuzzy comparison, no
     * fallback.
     */
    val bindingKey: String
        get() = bindingKey(operationId, dialect, table, constraintName, sqlHash)

    companion object {
        fun bindingKey(
            operationId: String,
            dialect: String,
            table: String,
            constraintName: String,
            sqlHash: String,
        ): String =
            listOf(operationId, dialect, table, constraintName, sqlHash).joinToString("")
    }
}

/**
 * Live-probe outcome for [CheckPreflightDeclaration].
 *
 * The four values cover both the "data verification" axis (PASSED /
 * FAILED) and the "why didn't we probe" axis (NOT_RUN_FILE_TARGET /
 * NOT_RUN_POLICY / PROBE_RUNTIME_ERROR). The renderer's blocking
 * decision differs per-status:
 *
 * - [PASSED]: render the ADD natively.
 * - [FAILED]: block with `CHECK_PREFLIGHT_VIOLATIONS` →
 *   `MANUAL_ACTION_REQUIRED`.
 * - [NOT_RUN_FILE_TARGET]: render natively. The report surfaces the
 *   declaration so the operator knows no live verification happened.
 *   File-to-file is the dominant case for `schema generate`; running
 *   the resulting migration against a real DB will require a separate
 *   `schema migrate --execute` pass that *can* probe.
 * - [NOT_RUN_POLICY]: probe is reachable but was suppressed (e.g.
 *   `--skip-data-preflight`). Same render behaviour as
 *   `NOT_RUN_FILE_TARGET` but the rationale is operator-explicit.
 * - [PROBE_RUNTIME_ERROR]: a probe attempt threw — connection lost,
 *   permissions, malformed expression. Block with
 *   `CHECK_PREFLIGHT_RUNTIME_ERROR` → `MANUAL_ACTION_REQUIRED`. The
 *   [CheckPreflightDeclaration.problem] field carries the underlying
 *   error text.
 */
enum class CheckPreflightStatus {
    PASSED,
    FAILED,
    NOT_RUN_FILE_TARGET,
    NOT_RUN_POLICY,
    PROBE_RUNTIME_ERROR,
}
