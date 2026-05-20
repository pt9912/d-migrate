package dev.dmigrate.driver

/**
 * E.3 MySQL Sequence Drift-Check Sub-Slice A (2026-05-20):
 * cross-stage declaration carrying the result of a live-DB probe
 * against the MySQL helper-table emulation's canonical objects.
 *
 * The structure mirrors [CheckPreflightDeclaration] / F.5 E.3 so the
 * migration-pipeline plumbing stays uniform: planner → runner →
 * renderer → report. The CLI stage builds one declaration per
 * (sequence-op, probe-kind) tuple and threads them through
 * `DdlGenerationOptions`; the renderer consults the
 * [MysqlSequenceCanonicityGate] before emitting any DML against
 * `dmg_sequences` or the support routines / triggers.
 *
 * Why a separate declaration kind per object: the same `--execute`
 * run may need to validate the helper table, both routines, the
 * affected row, and the column-bound trigger. Encoding the kind on
 * the declaration keeps the renderer's gate decision local — no
 * need to second-guess which probe fed which gate call.
 *
 * @property operationId the [DiffOperation.id] this declaration
 *           backs. Stage / renderer match by op-id so two CreateSequence
 *           ops for different sequences cannot collide.
 * @property dialect informational; the runner uses it to pick the
 *           per-dialect probe implementation. Always `"mysql"` in
 *           this slice — kept for symmetry with [CheckPreflightDeclaration].
 * @property kind which canonical object the declaration concerns.
 * @property objectName the live identifier the probe inspected:
 *           `"dmg_sequences"` for SUPPORT_TABLE, `"dmg_nextval"` /
 *           `"dmg_setval"` for ROUTINE_*, the sequence name for
 *           SEQUENCE_ROW, the canonical trigger name for
 *           SUPPORT_TRIGGER.
 * @property status the probe outcome; see [MysqlSequenceCanonicityStatus].
 * @property sqlHash deterministic hash over the probe SQL the runner
 *           executed. Two declarations with the same [bindingKey]
 *           but different `sqlHash` indicate the probe's input
 *           changed between plan and execute — the renderer treats
 *           that as a re-plan obligation.
 * @property driftField the canonical field name that drifted (e.g.
 *           `"increment_by"`, `"cycle_enabled"`, `"body_marker"`,
 *           `"column_signature"`). Null unless [status] is `DRIFT`.
 * @property expected stringified expected value for [driftField].
 * @property actual stringified actual value for [driftField].
 * @property problem free-text error context when a technical failure
 *           occurred during probing (status `PROBE_RUNTIME_ERROR`).
 */
data class MysqlSequenceCanonicityDeclaration(
    val operationId: String,
    val dialect: String,
    val kind: MysqlSequenceCanonicityKind,
    val objectName: String,
    val status: MysqlSequenceCanonicityStatus,
    val sqlHash: String,
    val driftField: String? = null,
    val expected: String? = null,
    val actual: String? = null,
    val problem: String? = null,
) {
    /**
     * Stable identity key for matching a declaration against a
     * rendered sequence op. Uses ASCII Unit Separator (``)
     * between fields so identifier characters (dots, dashes,
     * routine names with parentheses) cannot collide.
     *
     * The renderer computes the same key during emission and looks
     * the declaration up by exact-match — no fuzzy comparison, no
     * fallback. Plus the [kind] discriminator so a single op
     * (e.g. `CreateSequence`) can carry up to five declarations
     * (table + 2 routines + row + trigger) without ambiguity.
     */
    val bindingKey: String
        get() = bindingKey(operationId, dialect, kind, objectName, sqlHash)

    companion object {
        fun bindingKey(
            operationId: String,
            dialect: String,
            kind: MysqlSequenceCanonicityKind,
            objectName: String,
            sqlHash: String,
        ): String =
            listOf(operationId, dialect, kind.name, objectName, sqlHash)
                .joinToString("")
    }
}

/**
 * Discriminator for [MysqlSequenceCanonicityDeclaration]. Five
 * canonical objects need separate probes because they live in
 * different `INFORMATION_SCHEMA` views / `SHOW CREATE` outputs
 * and drift in different ways.
 */
enum class MysqlSequenceCanonicityKind {
    /** `dmg_sequences` helper table column signature. */
    SUPPORT_TABLE,

    /** `dmg_nextval` function body marker + signature. */
    NEXTVAL_ROUTINE,

    /** `dmg_setval` function body marker + signature. */
    SETVAL_ROUTINE,

    /**
     * A single `dmg_sequences` row's managed fields (`increment_by`,
     * `min_value`, `max_value`, `cycle_enabled`, `cache_size`) vs.
     * what the Diff plan expects.
     */
    SEQUENCE_ROW,

    /**
     * A `dmg_seq_<table16>_<column16>_<hash10>_bi` trigger's body
     * marker + the referenced sequence name in `dmg_nextval('…')`.
     */
    SUPPORT_TRIGGER,
}

/**
 * Live-probe outcome for [MysqlSequenceCanonicityDeclaration].
 *
 * Mirrors [CheckPreflightStatus] structurally so the report
 * builder + renderer-gate logic can share helpers across the two
 * preflight kinds:
 *
 * - [CANONICAL]: the live object matches the canonical contract —
 *   render the sequence op natively.
 * - [DRIFT]: the live object exists but differs (column signature,
 *   row field, body marker, …). Block with `E124_MYSQL_SEQUENCE_DRIFT`
 *   → `MANUAL_ACTION_REQUIRED`. The declaration's
 *   [MysqlSequenceCanonicityDeclaration.driftField] /
 *   [MysqlSequenceCanonicityDeclaration.expected] /
 *   [MysqlSequenceCanonicityDeclaration.actual] fields carry the
 *   detail.
 * - [MISSING]: the live object does not exist. For
 *   `CreateSequence`-style INSERT paths this is the canonical case
 *   ("nothing to drift against, normal bootstrap applies"); for
 *   `AlterSequence` / `DropSequence` it blocks because the target
 *   sequence cannot be modified / dropped if it isn't there.
 * - [NOT_RUN_FILE_TARGET]: file-to-file mode (`--source` is a YAML
 *   file, `--target` is a file). No live DB to probe. The renderer
 *   proceeds natively; the report surfaces the declaration so the
 *   operator knows no live verification happened.
 * - [NOT_RUN_POLICY]: probe is reachable but was suppressed (e.g.
 *   a future `--skip-sequence-drift-check` flag). Same render
 *   behaviour as `NOT_RUN_FILE_TARGET`; rationale is operator-
 *   explicit.
 * - [PROBE_RUNTIME_ERROR]: a probe attempt threw — connection
 *   lost, permissions, missing privileges to read
 *   `INFORMATION_SCHEMA`. Block with `E124_MYSQL_SEQUENCE_DRIFT_PROBE_FAILED`
 *   → `MANUAL_ACTION_REQUIRED`. The
 *   [MysqlSequenceCanonicityDeclaration.problem] field carries the
 *   underlying error text.
 */
enum class MysqlSequenceCanonicityStatus {
    CANONICAL,
    DRIFT,
    MISSING,
    NOT_RUN_FILE_TARGET,
    NOT_RUN_POLICY,
    PROBE_RUNTIME_ERROR,
}

/**
 * E.3 MySQL Sequence Drift-Check Sub-Slice A: live-DB probe port
 * for the helper-table emulation's canonical objects.
 *
 * Implementations live in the driver adapter (
 * `MysqlSequenceCanonicityProbeAdapter` in `driver-mysql`); the
 * CLI stage instantiates the adapter per `--execute` run.
 * File-to-file runs skip the probe entirely; the runner produces
 * declarations with status `NOT_RUN_FILE_TARGET` so the
 * report-builder still has a paper trail.
 *
 * Each method returns a [MysqlSequenceCanonicityDeclaration]
 * carrying the probe outcome for the corresponding
 * [MysqlSequenceCanonicityKind]. The probe is responsible for
 * filling `sqlHash` deterministically so the renderer can match
 * declarations against re-planned operations.
 *
 * Out-of-scope of this port (separate follow-up slices):
 *
 * - **Auto-fix / drift-repair**: the probe reports drift, never
 *   resolves it.
 * - **Cross-dialect drift** (PG ↔ MySQL): handled by the
 *   cross-dialect-sequencing plan.
 * - **Sample-rate / partial scans**: probes are O(1) catalog
 *   lookups + one row read; sampling would be a meta-helper
 *   if `dmg_sequences` ever grew to millions of rows.
 */
interface MysqlSequenceCanonicityProbe {

    /**
     * Probes the `dmg_sequences` helper table's column signature
     * against the canonical 9-column shape:
     *
     * `managed_by VARCHAR(32) NOT NULL`,
     * `format_version VARCHAR(32) NOT NULL`,
     * `name VARCHAR(255) NOT NULL`,
     * `next_value BIGINT NOT NULL`,
     * `increment_by BIGINT NOT NULL`,
     * `min_value BIGINT NULL`,
     * `max_value BIGINT NULL`,
     * `cycle_enabled TINYINT(1) NOT NULL`,
     * `cache_size INT NULL`,
     * `PRIMARY KEY (name)`.
     *
     * Returns `MISSING` when the table does not exist (canonical
     * for first-time-bootstrap), `CANONICAL` when the signature
     * matches, `DRIFT` with a specific column name when any
     * column type / nullability differs.
     */
    fun probeSupportTable(operationId: String): MysqlSequenceCanonicityDeclaration

    /**
     * Probes `dmg_nextval` / `dmg_setval` routine body via
     * `SHOW CREATE FUNCTION` + marker comment validation (the
     * canonical body contains a `d-migrate:mysql-sequence-v1
     * object=nextval` / `object=setval` marker inside a SQL block
     * comment). Returns `MISSING` when the routine doesn't exist,
     * `CANONICAL` when the marker matches, `DRIFT` with
     * `driftField = "body_marker"` otherwise.
     */
    fun probeRoutine(
        operationId: String,
        kind: MysqlSequenceCanonicityKind,
    ): MysqlSequenceCanonicityDeclaration

    /**
     * Probes a single `dmg_sequences` row's managed fields
     * (`increment_by`, `min_value`, `max_value`, `cycle_enabled`,
     * `cache_size`) against [expected]. `start` / `next_value`
     * (runtime state) are NOT compared — that's the
     * `preserveCurrentValue` follow-up's contract.
     *
     * Returns `MISSING` when the row doesn't exist (canonical for
     * `CreateSequence`, drift for `AlterSequence` / `DropSequence`
     * — the caller's `MysqlSequenceCanonicityGate` does the
     * context-aware routing). Returns `CANONICAL` when every
     * managed field matches. Returns `DRIFT` with the first
     * mismatched field on failure.
     */
    fun probeSequenceRow(
        operationId: String,
        sequenceName: String,
        expectedIncrement: Long,
        expectedMinValue: Long?,
        expectedMaxValue: Long?,
        expectedCycle: Boolean,
        expectedCache: Int?,
    ): MysqlSequenceCanonicityDeclaration

    /**
     * Probes a `dmg_seq_<table16>_<column16>_<hash10>_bi` trigger
     * via `SHOW CREATE TRIGGER` and validates the body marker
     * (the canonical body contains a `d-migrate:mysql-sequence-v1
     * object=sequence-trigger sequence=…` substring inside a SQL
     * block comment) plus the referenced sequence name. Returns
     * `MISSING` when the trigger doesn't exist, `CANONICAL` when
     * marker + sequence name match the expectation, `DRIFT`
     * otherwise.
     */
    fun probeSupportTrigger(
        operationId: String,
        triggerName: String,
        expectedSequenceName: String,
    ): MysqlSequenceCanonicityDeclaration
}
