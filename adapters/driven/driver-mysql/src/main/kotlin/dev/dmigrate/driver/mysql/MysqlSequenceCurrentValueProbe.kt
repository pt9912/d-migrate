package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.MysqlSequenceSupportNaming
import dev.dmigrate.driver.SequenceCurrentValueProbe
import dev.dmigrate.driver.SequenceCurrentValueProbeResult
import java.sql.Connection
import java.sql.SQLException

/**
 * 0.9.7 preserve-current-value Sub-Slice C (2026-05-21): MySQL
 * implementation of [SequenceCurrentValueProbe] against the helper-
 * table emulation introduced in 0.9.4
 * (`docs/planning/done/mysql-sequence-emulation-plan.md`). Reads
 * the runtime state of a named sequence via:
 *
 * ```sql
 * SELECT `next_value`, `managed_by`, `format_version`
 * FROM `dmg_sequences`
 * WHERE `name` = '<lookupKey>'
 * ```
 *
 * Unlike the PG probe (which `SELECT`s directly from a sequence
 * relation), MySQL has no first-class sequences — the emulation
 * stores one row per managed sequence in `dmg_sequences`. The
 * `managed_by` / `format_version` columns let this probe distinguish
 * a d-migrate-managed row from an operator-inserted one: a mismatch
 * surfaces as a structured [SequenceCurrentValueProbeResult.Failed]
 * rather than a false-positive `Read`.
 *
 * Outcome routing:
 *
 * - **`Read(value=next_value, isCalled=null, managedBy=…, formatVersion=…)`**
 *   when exactly one row matches and `managed_by = 'd-migrate'` and
 *   `format_version` is in
 *   [MysqlSequenceSupportNaming.SUPPORTED_FORMAT_VERSIONS]. MySQL
 *   has no `is_called` analogue; the helper-table `next_value` column
 *   already encodes the "next-to-be-returned" semantics, so
 *   `isCalled` stays `null`.
 * - **`NotFound`** when:
 *   - The `dmg_sequences` helper table does not exist (MySQL error
 *     1146 *ER_NO_SUCH_TABLE*). The emulation has not been
 *     bootstrapped — Sub-Slice D's `CreateSequence` parents pick this
 *     up as the canonical pre-state.
 *   - The query returns 0 rows. The helper table exists but does not
 *     yet carry an entry for [SequenceObjectRef.name].
 * - **`Failed(PROBE_PERMISSION_DENIED, …)`** when the role lacks
 *   `SELECT` privileges (MySQL error 1142 *ER_TABLEACCESS_DENIED_ERROR*).
 * - **`Failed(PROBE_UNMANAGED_ROW, …)`** when a row matches `name`
 *   but `managed_by` is not `'d-migrate'`. Operator-inserted rows
 *   must not be silently overwritten by the preserve renderer's
 *   `UPDATE`.
 * - **`Failed(PROBE_UNKNOWN_FORMAT_VERSION, …)`** when `managed_by =
 *   'd-migrate'` but `format_version` is outside the
 *   `SUPPORTED_FORMAT_VERSIONS` set. The emulation has been
 *   upgraded to a layout this build doesn't understand — explicit
 *   blocker prevents accidental cross-version writes.
 * - **`Failed(PROBE_AMBIGUOUS_ROW, …)`** when more than one row
 *   matches (defensive — `dmg_sequences` has `PRIMARY KEY (name)`
 *   so this can only fire on a structurally broken table).
 * - **`Failed(PROBE_QUERY_FAILED, …)`** for any other `SQLException`.
 *
 * The probe never throws — every failure path produces a typed
 * outcome the Sub-Slice D planner-side gate consumes uniformly.
 */
object MysqlSequenceCurrentValueProbe : SequenceCurrentValueProbe {

    /** MySQL error code for "Base table or view not found". */
    const val MYSQL_ERR_NO_SUCH_TABLE: Int = 1146

    /** MySQL error code for "SELECT command denied to user …". */
    const val MYSQL_ERR_TABLEACCESS_DENIED: Int = 1142

    /** Diagnostic code stamped on a `Failed` outcome when error = 1142. */
    const val CODE_PERMISSION_DENIED: String = "PROBE_PERMISSION_DENIED"

    /** Diagnostic code for rows whose `managed_by` is not `'d-migrate'`. */
    const val CODE_UNMANAGED_ROW: String = "PROBE_UNMANAGED_ROW"

    /** Diagnostic code for `managed_by = 'd-migrate'` but `format_version` not supported. */
    const val CODE_UNKNOWN_FORMAT_VERSION: String = "PROBE_UNKNOWN_FORMAT_VERSION"

    /** Diagnostic code for the (defensive) multi-row case. */
    const val CODE_AMBIGUOUS_ROW: String = "PROBE_AMBIGUOUS_ROW"

    /** Diagnostic code for every other [SQLException]. */
    const val CODE_QUERY_FAILED: String = "PROBE_QUERY_FAILED"

    override fun probe(
        connection: Connection,
        sequenceRef: SequenceObjectRef,
    ): SequenceCurrentValueProbeResult {
        val lookupKey = MysqlSequenceSupportNaming.lookupKey(sequenceRef)
        val sql = buildProbeSql(lookupKey)
        return try {
            connection.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    readSingleRow(rs, lookupKey)
                }
            }
        } catch (e: SQLException) {
            when (e.errorCode) {
                MYSQL_ERR_NO_SUCH_TABLE -> SequenceCurrentValueProbeResult.NotFound
                MYSQL_ERR_TABLEACCESS_DENIED -> SequenceCurrentValueProbeResult.Failed(
                    code = CODE_PERMISSION_DENIED,
                    message = e.message ?: "MySQL denied SELECT on dmg_sequences",
                )
                else -> SequenceCurrentValueProbeResult.Failed(
                    code = CODE_QUERY_FAILED,
                    message = e.message ?: e::class.simpleName.orEmpty(),
                )
            }
        }
    }

    /**
     * Iterates the ResultSet and classifies the outcome. Pulled out
     * so the SQLException-catching block in [probe] stays focused on
     * driver-level failures while the row-validation branches stay
     * here. ResultSet.next() may not advance past the first row in
     * the happy path — the second [java.sql.ResultSet.next] call
     * tells us whether the PK invariant held.
     */
    private fun readSingleRow(
        rs: java.sql.ResultSet,
        lookupKey: String,
    ): SequenceCurrentValueProbeResult {
        if (!rs.next()) return SequenceCurrentValueProbeResult.NotFound

        val nextValue = rs.getLong("next_value")
        val managedBy = rs.getString("managed_by")
        val formatVersion = rs.getString("format_version")

        if (rs.next()) {
            // PK violation, structural corruption of dmg_sequences.
            return SequenceCurrentValueProbeResult.Failed(
                code = CODE_AMBIGUOUS_ROW,
                message = "dmg_sequences carries multiple rows with name='$lookupKey'",
            )
        }

        if (managedBy != MysqlSequenceSupportNaming.MANAGED_BY) {
            return SequenceCurrentValueProbeResult.Failed(
                code = CODE_UNMANAGED_ROW,
                message = "dmg_sequences row name='$lookupKey' is owned by " +
                    "managed_by='$managedBy' (expected '${MysqlSequenceSupportNaming.MANAGED_BY}')",
            )
        }

        if (formatVersion !in MysqlSequenceSupportNaming.SUPPORTED_FORMAT_VERSIONS) {
            return SequenceCurrentValueProbeResult.Failed(
                code = CODE_UNKNOWN_FORMAT_VERSION,
                message = "dmg_sequences row name='$lookupKey' uses format_version='$formatVersion' " +
                    "not in supported set ${MysqlSequenceSupportNaming.SUPPORTED_FORMAT_VERSIONS}",
            )
        }

        return SequenceCurrentValueProbeResult.Read(
            value = nextValue,
            matchedRows = 1,
            isCalled = null,
            managedBy = managedBy,
            // format_version is varchar in dmg_sequences but the
            // probe result's Int field is the canonical numeric
            // representation. Strip the `mysql-sequence-v` prefix
            // ("mysql-sequence-v1" → 1). If the prefix is absent we
            // keep null — the planner-side gate downstreams that as
            // unknown rather than asserting a misparse.
            formatVersion = formatVersion.removePrefix("mysql-sequence-v").toIntOrNull(),
        )
    }

    private fun buildProbeSql(lookupKey: String): String {
        val table = MysqlSequenceSqlCodec.quoteIdentifier(MysqlSequenceSupportNaming.SUPPORT_TABLE)
        val nextValueCol = MysqlSequenceSqlCodec.quoteIdentifier("next_value")
        val managedByCol = MysqlSequenceSqlCodec.quoteIdentifier("managed_by")
        val formatVersionCol = MysqlSequenceSqlCodec.quoteIdentifier("format_version")
        val nameCol = MysqlSequenceSqlCodec.quoteIdentifier("name")
        val keyLiteral = MysqlSequenceSqlCodec.quoteStringLiteral(lookupKey)
        return "SELECT $nextValueCol, $managedByCol, $formatVersionCol " +
            "FROM $table " +
            "WHERE $nameCol = $keyLiteral"
    }
}
