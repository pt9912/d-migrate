package dev.dmigrate.driver.migration

/**
 * F.4 Renderer-Blocker-Bridge (2026-05-19): maps a planner-emitted
 * BLOCKER-severity `DiffDiagnostic.code` to the
 * [MigrationBlockedReason] enum value that should surface as the
 * report-level `primaryBlockedReason`.
 *
 * Background. The PG, MySQL and SQLite render contexts used to wrap
 * EVERY planner-emitted BLOCKER diagnostic into a single
 * `MigrationBlocker(reason = DIALECT_UNSUPPORTED_OPERATION)`. That
 * was correct for the original F.5 carve-out (`CONSTRAINT_NOT_DIFFABLE`
 * — the dialect genuinely cannot render the operation) but became
 * wrong once F.4 introduced
 * [MigrationBlockedReason.OBJECT_RENAME_UNSUPPORTED] as the
 * Mapper-/Planner-phase rename-policy outcome. F.4 plan-doc §5.2
 * reserves `OBJECT_RENAME_UNSUPPORTED` for those Mapper-/Planner
 * cases and forbids conflating it with `DIALECT_UNSUPPORTED_OPERATION`
 * (which stays reserved for renderer-side dialect-unsupported
 * operations).
 *
 * Renderers now run each planner-emitted diagnostic code through
 * [classify] and group the resulting `MigrationBlocker`s by reason,
 * so the report's `primaryBlockedReason` accurately reflects the
 * Mapper/Planner intent.
 *
 * The classifier is intentionally conservative: only diagnostic
 * codes that map to a more specific
 * [MigrationBlockedReason] are listed here; everything else falls
 * back to [MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION] so
 * the pre-F.4 behaviour stays unchanged for unrelated codes
 * (`CONSTRAINT_NOT_DIFFABLE`, `MATERIALIZED_VIEW_DIFF_UNSUPPORTED`,
 * etc.). Additional mappings land via dedicated follow-up slices,
 * one diagnostic code at a time, after the contract has been
 * validated against the F.5 / D.3b carve-outs.
 *
 * Out of scope (separate concerns):
 *
 * - The pre-plan overlay-validator classifier at
 *   `dev.dmigrate.cli.commands.MigrationOverlayPreflight.classifyDiagnostic`
 *   maps the OVERLAY-side string code `OBJECT_RENAME_UNSUPPORTED`
 *   onto `RENAME_MAPPING_INVALID` (legacy renderer-emitted-code
 *   shortcut). That schedule is independent from this classifier
 *   — both can coexist because they run on different pipeline
 *   stages.
 */
object PlannerBlockerClassifier {

    /**
     * F.4 plan-doc §5.2: `OBJECT_RENAME_UNSUPPORTED` is the Mapper-/
     * Planner-phase diagnostic code emitted by `RenameObjectMapper`
     * when [ObjectRenamePolicy.classify][] returns
     * `RenameSupport.Blocked` (materialized-view rename, body-drift,
     * missing prior body in Drop+Create-fallback, SQLite-routines-
     * carve-out, MySQL/SQLite-sequence-carve-out). The renderer must
     * preserve this reason through the wrap to keep the contract
     * consistent.
     */
    const val OBJECT_RENAME_UNSUPPORTED_CODE: String = "OBJECT_RENAME_UNSUPPORTED"

    /**
     * F.5 Sub-Slice A: planner-level cross-table-CHECK heuristic
     * fires when an operator-supplied CHECK expression looks like
     * it references another table (subquery markers, EXISTS / IN
     * with SELECT). The slice deliberately does NOT parse the SQL
     * semantically; the heuristic is conservative — false positives
     * (operator writes `selection_count` as a column name) are
     * acceptable, false negatives (operator writes a real
     * cross-table CHECK that bypasses the heuristic) are not.
     */
    const val CHECK_EXPRESSION_CROSS_TABLE_UNSUPPORTED_CODE: String =
        "CHECK_EXPRESSION_CROSS_TABLE_UNSUPPORTED"

    /**
     * F.5 Sub-Slice C: MySQL CHECK constraints require server
     * version ≥ 8.0.16 (or MariaDB ≥ 10.2.1) for actual enforcement.
     * Earlier MySQL accepts the syntax but ignores semantics —
     * a silent contract violation. The renderer blocks instead of
     * emitting NOT-ENFORCED DDL.
     */
    const val MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16_CODE: String =
        "MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16"

    /**
     * F.5 Sub-Slice C: MySQL CHECK rendering needs an enforcement
     * decision based on `mysqlServerVersion`. When the version is
     * not detectable (e.g. the runner couldn't probe), the renderer
     * blocks rather than silently picking a default.
     */
    const val MYSQL_CHECK_ENFORCEMENT_UNKNOWN_CODE: String =
        "MYSQL_CHECK_ENFORCEMENT_UNKNOWN"

    /**
     * F.5 Sub-Slice B/D: EXCLUDE is a PostgreSQL-only constraint
     * concept. MySQL and SQLite block any EXCLUDE op with this
     * code; cross-dialect transfer surfaces it directly.
     */
    const val EXCLUDE_NOT_SUPPORTED_BY_DIALECT_CODE: String =
        "EXCLUDE_NOT_SUPPORTED_BY_DIALECT"

    /**
     * F.5 Sub-Slice B: PostgreSQL EXCLUDE constraints carry an
     * operator class (`USING gist (col WITH &&)`). The first F.5
     * tranche whitelists the standard range operators; custom
     * operator classes block with this code until a dedicated slice
     * adds the operator-class whitelist contract.
     */
    const val EXCLUDE_OPERATOR_CLASS_NOT_SUPPORTED_CODE: String =
        "EXCLUDE_OPERATOR_CLASS_NOT_SUPPORTED"

    /**
     * F.5 Sub-Slice E: a new restrictive CHECK constraint may
     * violate existing rows. The execute-mode preflight runs
     * `SELECT count(*) FROM t WHERE NOT (expr)`; a non-zero count
     * surfaces this code so the operator decides whether to clean
     * up data first or accept the manual action.
     */
    const val CHECK_PREFLIGHT_VIOLATIONS_CODE: String =
        "CHECK_PREFLIGHT_VIOLATIONS"

    /**
     * F.5 Sub-Slice E: the preflight probe itself can fail
     * (network, privilege, malformed expression). The slice surfaces
     * the technical failure separately from a data-violation result
     * so the operator can distinguish "data violates" from "probe
     * could not be executed".
     */
    const val CHECK_PREFLIGHT_RUNTIME_ERROR_CODE: String =
        "CHECK_PREFLIGHT_RUNTIME_ERROR"

    /**
     * E.3 MySQL Sequence Drift-Check Sub-Slice B (2026-05-20):
     * the six drift-related diagnostic codes the
     * `MysqlSequenceCanonicityGate` emits. All map to
     * `MANUAL_ACTION_REQUIRED` — the operator must either
     * reconcile the live state with the plan, switch op intent
     * (CreateSequence vs. AlterSequence), or re-plan after
     * external repair. The codes are intentionally distinct from
     * the F.5 CHECK preflight codes so reports surface drift
     * source (table / routine / row / trigger) without parsing
     * the message text.
     */
    const val MYSQL_SEQUENCE_DRIFT_TABLE_CODE: String = "E124_MYSQL_SEQUENCE_DRIFT_TABLE"
    const val MYSQL_SEQUENCE_DRIFT_ROUTINE_CODE: String = "E124_MYSQL_SEQUENCE_DRIFT_ROUTINE"
    const val MYSQL_SEQUENCE_DRIFT_ROW_CODE: String = "E124_MYSQL_SEQUENCE_DRIFT_ROW"
    const val MYSQL_SEQUENCE_DRIFT_TRIGGER_CODE: String = "E124_MYSQL_SEQUENCE_DRIFT_TRIGGER"
    const val MYSQL_SEQUENCE_MISSING_FOR_ALTER_CODE: String = "E124_MYSQL_SEQUENCE_MISSING_FOR_ALTER"
    const val MYSQL_SEQUENCE_MISSING_FOR_DROP_CODE: String = "E124_MYSQL_SEQUENCE_MISSING_FOR_DROP"
    const val MYSQL_SEQUENCE_DRIFT_PROBE_FAILED_CODE: String = "E124_MYSQL_SEQUENCE_DRIFT_PROBE_FAILED"

    /**
     * 0.9.7 preserve-current-value Sub-Slice D (2026-05-21): four
     * BLOCKER codes the `SequencePreserveStage` emits. Three map to
     * `MANUAL_ACTION_REQUIRED` — operator decides whether to reconcile
     * state, switch op intent, or supply a working probe — and one
     * (`NOT_SUPPORTED_BY_DIALECT`) keeps the dialect-unsupported
     * routing because SQLite has no sequence emulation yet.
     *
     * Two INFO codes (`SEQUENCE_PRESERVE_NOT_FOUND` for the canonical
     * "CreateSequence without prior state" path, and
     * `SEQUENCE_PRESERVE_NOT_RUN_POLICY` for the no-probe-configured
     * test path) intentionally have NO mapping here — they are not
     * blockers, so [classify] should never see them. If they ever
     * reach this function, the `else` branch's
     * DIALECT_UNSUPPORTED_OPERATION fallback would be wrong; the
     * stage is responsible for routing INFO codes through
     * `recordDiagnostic`-style emit paths that bypass classification.
     */
    const val SEQUENCE_PRESERVE_PROBE_FAILED_CODE: String = "SEQUENCE_PRESERVE_PROBE_FAILED"
    const val SEQUENCE_PRESERVE_CONFIG_INVALID_CODE: String = "SEQUENCE_PRESERVE_CONFIG_INVALID"
    const val SEQUENCE_PRESERVE_REQUIRES_DB_TARGET_CODE: String = "SEQUENCE_PRESERVE_REQUIRES_DB_TARGET"
    const val SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT_CODE: String =
        "SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT"

    /**
     * 0.9.7 SQLite preserve-current-value Folge-Slice (2026-05-29):
     * the dialect has the capability (SQLite helper_table emulation
     * is implemented since 0.9.7) but the operator has not opted in
     * via `--sqlite-named-sequences helper_table`. Distinct from
     * `NOT_SUPPORTED_BY_DIALECT` so the report tells the operator
     * the actual remedy — flip the CLI flag, do not give up on the
     * dialect.
     */
    const val SEQUENCE_PRESERVE_OPT_IN_REQUIRED_CODE: String =
        "SEQUENCE_PRESERVE_OPT_IN_REQUIRED"

    /**
     * Atomic-Preserve Phase A (2026-05-31): two BLOCKER codes
     * reserved for the upcoming execute-time atomic Probe + Restore
     * pipeline (Plan-Doc
     * `docs/planning/done-archive/sequence-preserve-atomic-lock-plan.md`
     * §3.1). Both classify as `MANUAL_ACTION_REQUIRED` because the
     * operator has a concrete remedy:
     *
     * - `SEQUENCE_PRESERVE_LOCK_TIMEOUT` — the per-dialect lock
     *   (`pg_advisory_xact_lock(hashtext(...))` on PG since Phase B.2,
     *   `SELECT … FOR UPDATE` on MySQL, `BEGIN IMMEDIATE` on SQLite)
     *   did not acquire within the configured `lockTimeoutMillis`
     *   window. Operator can raise the timeout, schedule the run in
     *   a quieter window, or pin the cause via the test-side
     *   concurrency reproducer.
     * - `SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED` — the dialect or
     *   protected operation type lacks the
     *   `supportsAtomicPreserve` / `supportsAtomicPreserveAllInPlan`
     *   / `transactionalProtectedSequenceOperations` capability the
     *   atomic-runner requires. Operator's remedy is either to swap
     *   target dialect / operation form, or to wait for the dialect
     *   to ship the capability.
     *
     * Phase A only registers the codes + classifier mapping.
     * Phases B/C/D land the executor + stage refactor that actually
     * emits them.
     */
    const val SEQUENCE_PRESERVE_LOCK_TIMEOUT_CODE: String =
        "SEQUENCE_PRESERVE_LOCK_TIMEOUT"
    const val SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED_CODE: String =
        "SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED"

    /**
     * 0.9.7 Cross-Dialect-Sequencing Sub-Slice B: two BLOCKER codes
     * reserved for per-attribute and ownership-related sequence
     * mismatches across dialects. Both map to
     * `MANUAL_ACTION_REQUIRED` — the operator can resolve them via
     * overlay, target-dialect swap, or by enabling a helper-table
     * emulation once available.
     *
     * Forward-compatibility: today no renderer emits either code.
     * The OP-level block ("dialect has no sequence concept at all" —
     * SQLite with `supportsNamedSequences=false`) keeps the existing
     * `DIALECT_UNSUPPORTED_OPERATION` routing because no operator
     * action enables the dialect's sequence renderer; the
     * attribute-level code only fires once a renderer supports named
     * sequences but a specific attribute (e.g., `cycle`) is not
     * representable. Plan-doc §3.1 D2 + §4 pins the OP-/attribute-
     * split.
     *
     * `SEQUENCE_OWNED_BY_NOT_REPRESENTABLE_IN_DIALECT` is reserved
     * for a future neutral-model extension that introduces a
     * sequence-ownership field; today the PG reverse-reader filters
     * owned sequences via `pg_depend.deptype IN ('a','i')`, so no
     * Cross-Dialect-Transfer surfaces ownership yet.
     */
    const val SEQUENCE_ATTRIBUTE_NOT_SUPPORTED_BY_DIALECT_CODE: String =
        "SEQUENCE_ATTRIBUTE_NOT_SUPPORTED_BY_DIALECT"
    const val SEQUENCE_OWNED_BY_NOT_REPRESENTABLE_IN_DIALECT_CODE: String =
        "SEQUENCE_OWNED_BY_NOT_REPRESENTABLE_IN_DIALECT"

    fun classify(code: String): MigrationBlockedReason = when (code) {
        OBJECT_RENAME_UNSUPPORTED_CODE ->
            MigrationBlockedReason.OBJECT_RENAME_UNSUPPORTED
        CHECK_EXPRESSION_CROSS_TABLE_UNSUPPORTED_CODE,
        MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16_CODE,
        MYSQL_CHECK_ENFORCEMENT_UNKNOWN_CODE,
        EXCLUDE_OPERATOR_CLASS_NOT_SUPPORTED_CODE,
        CHECK_PREFLIGHT_VIOLATIONS_CODE,
        CHECK_PREFLIGHT_RUNTIME_ERROR_CODE,
        MYSQL_SEQUENCE_DRIFT_TABLE_CODE,
        MYSQL_SEQUENCE_DRIFT_ROUTINE_CODE,
        MYSQL_SEQUENCE_DRIFT_ROW_CODE,
        MYSQL_SEQUENCE_DRIFT_TRIGGER_CODE,
        MYSQL_SEQUENCE_MISSING_FOR_ALTER_CODE,
        MYSQL_SEQUENCE_MISSING_FOR_DROP_CODE,
        MYSQL_SEQUENCE_DRIFT_PROBE_FAILED_CODE,
        SEQUENCE_PRESERVE_PROBE_FAILED_CODE,
        SEQUENCE_PRESERVE_CONFIG_INVALID_CODE,
        SEQUENCE_PRESERVE_REQUIRES_DB_TARGET_CODE,
        SEQUENCE_PRESERVE_OPT_IN_REQUIRED_CODE,
        SEQUENCE_PRESERVE_LOCK_TIMEOUT_CODE,
        SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED_CODE,
        SEQUENCE_ATTRIBUTE_NOT_SUPPORTED_BY_DIALECT_CODE,
        SEQUENCE_OWNED_BY_NOT_REPRESENTABLE_IN_DIALECT_CODE ->
            MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        EXCLUDE_NOT_SUPPORTED_BY_DIALECT_CODE,
        SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT_CODE ->
            MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
        else -> MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
    }
}
