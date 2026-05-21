package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SequenceCurrentValueProbe
import dev.dmigrate.driver.SequenceCurrentValueProbeResult
import dev.dmigrate.driver.SqlIdentifiers
import java.sql.Connection
import java.sql.SQLException

/**
 * 0.9.7 preserve-current-value Sub-Slice B (2026-05-21): PostgreSQL
 * implementation of [SequenceCurrentValueProbe]. Reads the runtime
 * state of a single sequence via:
 *
 * ```sql
 * SELECT last_value, is_called FROM "<schema>"."<name>"
 * ```
 *
 * (or `SELECT last_value, is_called FROM "<name>"` when
 * [SequenceObjectRef.schema] is null — PG resolves through
 * `search_path`).
 *
 * `is_called` is **mandatory** for PG — the renderer's `setval(seq,
 * value, is_called)` form needs all three arguments to decide whether
 * the next `nextval` returns `value` or `value + 1`. The probe pins
 * the column read and surfaces a [SequenceCurrentValueProbeResult.Read]
 * with `isCalled` set to a non-null `Boolean`.
 *
 * SQLSTATE → [SequenceCurrentValueProbeResult] mapping:
 *
 * - `42P01` (*undefined_table*): the sequence does not exist (or the
 *   role can't see it). Returns
 *   [SequenceCurrentValueProbeResult.NotFound] — the Sub-Slice D
 *   planner-side gate maps this to `SEQUENCE_PRESERVE_NOT_FOUND`
 *   (info for `CreateSequence` parents, blocker otherwise).
 * - `42501` (*insufficient_privilege*): the role exists but lacks
 *   `SELECT` on the sequence. Returns
 *   [SequenceCurrentValueProbeResult.Failed] with code
 *   `PROBE_PERMISSION_DENIED`.
 * - All other [SQLException]s (driver crash, connection lost,
 *   syntax-error from a future PG version that breaks the query):
 *   returns [SequenceCurrentValueProbeResult.Failed] with code
 *   `PROBE_QUERY_FAILED`.
 *
 * The probe never throws — every exception path produces a typed
 * outcome the planner-side gate consumes uniformly.
 */
object PostgresSequenceCurrentValueProbe : SequenceCurrentValueProbe {

    /** SQLSTATE for an undefined relation (PG manual §52.5). */
    const val SQLSTATE_UNDEFINED_TABLE: String = "42P01"

    /** SQLSTATE for insufficient privilege (PG manual §52.5). */
    const val SQLSTATE_INSUFFICIENT_PRIVILEGE: String = "42501"

    /** Diagnostic code stamped on a `Failed` outcome when SQLSTATE = 42501. */
    const val CODE_PERMISSION_DENIED: String = "PROBE_PERMISSION_DENIED"

    /** Diagnostic code stamped on every other `Failed` outcome. */
    const val CODE_QUERY_FAILED: String = "PROBE_QUERY_FAILED"

    override fun probe(
        connection: Connection,
        sequenceRef: SequenceObjectRef,
    ): SequenceCurrentValueProbeResult {
        val qualified = buildQualifiedName(sequenceRef)
        val sql = "SELECT last_value, is_called FROM $qualified"
        return try {
            connection.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    if (!rs.next()) {
                        // PG sequences always yield exactly one row when
                        // accessed via `SELECT last_value FROM <seq>`; an
                        // empty ResultSet would be a JDBC-driver anomaly,
                        // not a planner-visible state. Surface as Failed
                        // rather than NotFound so the operator gets a
                        // clear "the driver didn't return what we expect".
                        return SequenceCurrentValueProbeResult.Failed(
                            code = CODE_QUERY_FAILED,
                            message = "PG sequence query returned 0 rows for $qualified",
                        )
                    }
                    SequenceCurrentValueProbeResult.Read(
                        value = rs.getLong("last_value"),
                        matchedRows = 1,
                        isCalled = rs.getBoolean("is_called"),
                    )
                }
            }
        } catch (e: SQLException) {
            when (e.sqlState) {
                SQLSTATE_UNDEFINED_TABLE -> SequenceCurrentValueProbeResult.NotFound
                SQLSTATE_INSUFFICIENT_PRIVILEGE -> SequenceCurrentValueProbeResult.Failed(
                    code = CODE_PERMISSION_DENIED,
                    message = e.message ?: "PG denied SELECT on $qualified",
                )
                else -> SequenceCurrentValueProbeResult.Failed(
                    code = CODE_QUERY_FAILED,
                    message = e.message ?: e::class.simpleName.orEmpty(),
                )
            }
        }
    }

    /**
     * Quotes [SequenceObjectRef.name] (and optionally
     * [SequenceObjectRef.schema]) for use as a relation reference in
     * the probe SQL. Uses the canonical [SqlIdentifiers] helpers so
     * a future renderer-side change to PG quoting flows through here
     * too.
     */
    private fun buildQualifiedName(ref: SequenceObjectRef): String {
        val schema = ref.schema
        return if (schema.isNullOrBlank()) {
            SqlIdentifiers.quoteIdentifier(ref.name, DatabaseDialect.POSTGRESQL)
        } else {
            SqlIdentifiers.quoteQualifiedIdentifier("$schema.${ref.name}", DatabaseDialect.POSTGRESQL)
        }
    }
}
