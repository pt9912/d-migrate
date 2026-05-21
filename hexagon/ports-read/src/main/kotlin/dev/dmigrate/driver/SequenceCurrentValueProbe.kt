package dev.dmigrate.driver

import dev.dmigrate.core.diff.migration.SequenceObjectRef
import java.sql.Connection

/**
 * 0.9.7 preserve-current-value Sub-Slice A: live-DB probe port for the
 * runtime value of a named sequence. Implementations live in the
 * dialect adapters:
 *
 * - **PG** (`adapters/driven/driver-postgresql`): `SELECT last_value,
 *   is_called FROM <schema>.<sequence>`. `is_called` is mandatory —
 *   the PG `setval(seq, value, is_called)` renderer in Sub-Slice B
 *   needs it to decide whether the next `nextval` returns `value` or
 *   `value + 1`.
 * - **MySQL** (`adapters/driven/driver-mysql`): `SELECT next_value,
 *   managed_by, format_version FROM dmg_sequences WHERE name = <key>
 *   AND managed_by = 'd-migrate' AND format_version IN (...)`. The
 *   `managed_by` / `format_version` filter guards against operator-
 *   modified or non-d-migrate-managed rows accidentally surfacing as
 *   a preserve target.
 * - **SQLite**: no implementation in 0.9.7. Adapters return
 *   [SequenceCurrentValueProbeResult.NotApplicable] so the upstream
 *   `MigrationPreflightPlanner` (Sub-Slice D) can emit a
 *   `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT` block.
 *
 * The port is the inversion of `SequenceCurrentValueRenderer` (Sub-
 * Slices B / C): probe reads the live value before render; the
 * renderer emits the corresponding `setval` / `UPDATE` statement
 * carrying that value into the [DiffOperation.AlterSequenceCurrentValue]
 * the planner emitted in Sub-Slice D.
 *
 * Out-of-scope for this port (separate follow-up slices):
 *
 * - **Atomic probe + setval under table lock**: the probe reads
 *   `last_value` / `next_value` at time T; an `INSERT` from the app
 *   between T and the renderer's `setval` would race. The
 *   `preserveCurrentValue` slice documents this as an operator-managed
 *   freeze-window contract.
 * - **Sequence-ownership inference** (`OWNED BY`): out-of-scope; the
 *   probe takes the sequence name as given.
 */
interface SequenceCurrentValueProbe {

    /**
     * Probes the runtime value of [sequenceRef]. Returns one of four
     * outcomes:
     *
     * - [SequenceCurrentValueProbeResult.Read]: probe succeeded;
     *   `value` (plus dialect-specific [SequenceCurrentValueProbeResult.Read.isCalled]
     *   / [SequenceCurrentValueProbeResult.Read.managedBy] /
     *   [SequenceCurrentValueProbeResult.Read.formatVersion]) is
     *   deterministic.
     * - [SequenceCurrentValueProbeResult.NotFound]: the sequence
     *   doesn't exist in the target. For `CreateSequence`-style
     *   parent ops the planner treats this as an info hint
     *   (`SEQUENCE_PRESERVE_NOT_FOUND`); for `AlterSequence` /
     *   `RenameSequence` parents it becomes a blocker.
     * - [SequenceCurrentValueProbeResult.NotApplicable]: the dialect
     *   doesn't support preserve probing (SQLite today). The
     *   planner-side gate converts this to
     *   `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT`.
     * - [SequenceCurrentValueProbeResult.Failed]: technical failure
     *   (privileges, connection, unexpected row count for MySQL's
     *   `dmg_sequences` query). The planner-side gate converts this
     *   to `SEQUENCE_PRESERVE_PROBE_FAILED`.
     */
    fun probe(
        connection: Connection,
        sequenceRef: SequenceObjectRef,
    ): SequenceCurrentValueProbeResult
}

/**
 * 0.9.7 preserve-current-value Sub-Slice A: sealed outcome of a
 * [SequenceCurrentValueProbe.probe] call. The four subtypes route
 * to distinct diagnostic codes in the planner — see
 * [SequenceCurrentValueProbe] KDoc.
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
     * `dmg_sequences` row. The planner maps this to
     * `SEQUENCE_PRESERVE_PROBE_FAILED` and blocks with
     * `MANUAL_ACTION_REQUIRED`.
     *
     * @property code stable diagnostic-shaped identifier the adapter
     *           emits (e.g. `"PROBE_QUERY_FAILED"`,
     *           `"DMG_SEQUENCES_ROW_COUNT"`). Joined into the
     *           planner-side diagnostic message.
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
     * The dialect has no probe implementation. Today this is SQLite —
     * the SQLite sequence emulation plan (in
     * `docs/planning/open/sqlite-sequence-emulation-plan.md`) will
     * fill in the probe later. The planner maps this to
     * `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT` → `DIALECT_UNSUPPORTED_OPERATION`.
     */
    data object NotApplicable : SequenceCurrentValueProbeResult()
}
