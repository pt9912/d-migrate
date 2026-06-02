package dev.dmigrate.driver

/**
 * Sealed outcome of a per-dialect sequence probe call.
 *
 * The probe itself was originally exposed as a top-level
 * `SequenceCurrentValueProbe` port (0.9.7 preserve-current-value
 * Sub-Slice A). Atomic-Preserve Phase C.1 (2026-06-01) folded the
 * call site into the per-dialect
 * [dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveExecutor]:
 * the executor invokes the adapter singleton
 * ([dev.dmigrate.driver.postgresql.PostgresSequenceCurrentValueProbe],
 * [dev.dmigrate.driver.mysql.MysqlSequenceCurrentValueProbe],
 * [dev.dmigrate.driver.sqlite.SqliteSequenceCurrentValueProbe])
 * directly inside the lock window, so the inversion-of-control
 * dispatch via a port had no remaining consumer and was deleted in
 * the §4.2 Dead-Code-Cleanup follow-up. The result type stays
 * because the `Read` variant continues to live on
 * [dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveRequest.renderRestore]
 * and on each adapter's `probe(...)` signature.
 *
 * The four subtypes route to distinct diagnostic codes downstream:
 *
 * - [Read] — probe succeeded; the executor restores via
 *   `renderRestore(read)`.
 * - [NotFound] — sequence does not exist in the target; the executor
 *   rolls back with `AtomicSequencePreserveResult.NotFound`.
 * - [Failed] — technical failure (privileges, connection,
 *   unexpected row count, helper-row mismatch); the executor rolls
 *   back with `AtomicSequencePreserveResult.Failed`.
 * - [NotApplicable] — the dialect has no probe implementation; the
 *   executor rolls back with `AtomicSequencePreserveResult.Failed`.
 *   Reserved for future dialects without a sequence emulation; PG,
 *   MySQL and SQLite all return one of the other three.
 */
sealed class SequenceCurrentValueProbeResult {

    /**
     * The probe succeeded and read a deterministic snapshot.
     *
     * @property value the runtime value. PG: `last_value`. MySQL helper-
     *           table: `next_value` (the next-to-be-returned-by-`nextval`
     *           value, not +1).
     * @property matchedRows how many rows the probe query matched. MySQL
     *           probes set this from the JDBC update count; PG probes
     *           always set it to `1` (single-row `SELECT FROM <seq>`).
     *           The planner rejects `matchedRows != 1` for MySQL probes
     *           because `dmg_sequences` has a `PRIMARY KEY (name)` and
     *           anything else points to schema-level drift.
     * @property isCalled PG-specific: whether the sequence's
     *           `last_value` was returned by `nextval` (vs. the
     *           initial `start` value). MySQL / SQLite probes leave
     *           this `null` — the helper-table semantics make the
     *           distinction unnecessary.
     * @property managedBy MySQL-specific: the helper-table row's
     *           `managed_by` column. The planner checks this matches
     *           `"d-migrate"` so operator-inserted rows can't be
     *           accidentally preserved.
     * @property formatVersion MySQL-specific: the helper-table row's
     *           `format_version` column. Checked against the
     *           emulation's `mysqlExpectedFormatVersions` set so a
     *           future emulation-format-bump doesn't silently preserve
     *           against an old row layout.
     */
    data class Read(
        val value: Long,
        val matchedRows: Int = 1,
        val isCalled: Boolean? = null,
        val managedBy: String? = null,
        val formatVersion: Int? = null,
    ) : SequenceCurrentValueProbeResult()

    /**
     * The probe failed with a technical error: connection lost,
     * permissions, unexpected row count, malformed
     * `dmg_sequences` row. The executor surfaces this as
     * `AtomicSequencePreserveResult.Failed` with the structured
     * code/message attached.
     *
     * @property code stable diagnostic-shaped identifier the adapter
     *           emits (e.g. `"PROBE_QUERY_FAILED"`,
     *           `"DMG_SEQUENCES_ROW_COUNT"`). Joined into the
     *           downstream error message.
     * @property message human-readable detail for the operator.
     */
    data class Failed(val code: String, val message: String) : SequenceCurrentValueProbeResult()

    /**
     * The sequence does not exist in the target database. For
     * `CreateSequence` parents this is the canonical pre-state and
     * the planner emits a `SEQUENCE_PRESERVE_NOT_FOUND` info; for
     * `AlterSequence` / `RenameSequence` parents the planner blocks
     * with `SEQUENCE_PRESERVE_PROBE_FAILED`.
     */
    data object NotFound : SequenceCurrentValueProbeResult()

    /**
     * The dialect has no probe implementation — reserved for future
     * dialects that lack a sequence emulation entirely. PG, MySQL,
     * and SQLite all return [Read] / [NotFound] / [Failed] instead.
     * The planner maps this to
     * `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT` →
     * `DIALECT_UNSUPPORTED_OPERATION`.
     */
    data object NotApplicable : SequenceCurrentValueProbeResult()
}
