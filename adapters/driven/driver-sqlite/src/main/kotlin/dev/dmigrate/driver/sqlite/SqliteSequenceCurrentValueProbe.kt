package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.SequenceCurrentValueProbe
import dev.dmigrate.driver.SequenceCurrentValueProbeResult
import java.sql.Connection
import java.sql.SQLException

/**
 * 0.9.7 SQLite preserve-current-value Folge-Slice
 * (2026-05-29): SQLite implementation of [SequenceCurrentValueProbe]
 * against the helper-table emulation introduced in 0.9.7
 * (`docs/planning/done/sqlite-sequence-emulation-plan.md` Phase B.3).
 *
 * Reads the runtime state of a named sequence via:
 *
 * ```sql
 * SELECT "next_value", "managed_by", "format_version"
 * FROM "dmg_sequences"
 * WHERE "name" = '<seq>'
 * ```
 *
 * Analog zu [dev.dmigrate.driver.mysql.MysqlSequenceCurrentValueProbe]
 * — SQLite hat ebenfalls keine first-class Sequences, sondern speichert
 * pro verwalteter Sequenz eine Zeile in `dmg_sequences`. Die
 * `managed_by`/`format_version`-Spalten erlauben, operator-eingefügte
 * Zeilen von d-migrate-managed-Zeilen abzugrenzen.
 *
 * Outcome routing (Plan §7.1):
 *
 * - **`Read(value=next_value, isCalled=null, managedBy=…, formatVersion=…)`**
 *   bei genau einer Zeile mit `managed_by = "d-migrate"` und
 *   `format_version = "sqlite-sequence-v1"`. `isCalled` bleibt `null` —
 *   SQLite hat keine PG-`is_called`-Semantik.
 * - **`NotFound`** wenn:
 *   - `dmg_sequences` fehlt — SQLite-Fehler mit Nachricht
 *     `no such table`. Die Emulation ist noch nicht gebootstrapped;
 *     `CreateSequence`-Eltern routen das zum `SEQUENCE_PRESERVE_NOT_FOUND`-
 *     INFO-Pfad.
 *   - Die Query liefert 0 Zeilen.
 * - **`Failed(PROBE_PERMISSION_DENIED, …)`** bei `SQLITE_PERM (3)`
 *   oder `SQLITE_AUTH (23)`.
 * - **`Failed(PROBE_UNMANAGED_ROW, …)`** wenn `managed_by` nicht
 *   `"d-migrate"` ist.
 * - **`Failed(PROBE_UNKNOWN_FORMAT_VERSION, …)`** wenn `managed_by` ok
 *   ist, aber `format_version` nicht `"sqlite-sequence-v1"`.
 * - **`Failed(PROBE_AMBIGUOUS_ROW, …)`** bei >1 Zeile (defensiv; der
 *   PK auf `name` macht das real unmöglich).
 * - **`Failed(PROBE_QUERY_FAILED, …)`** für sonstige `SQLException`.
 *
 * Der Probe wirft nie — jeder Fehlerpfad produziert ein typisiertes
 * Outcome, das die [SequencePreserveStage]-Routing-Tabelle uniform
 * konsumiert.
 */
object SqliteSequenceCurrentValueProbe : SequenceCurrentValueProbe {

    /** SQLite-Fehlercode `SQLITE_PERM` — Zugriff verweigert. */
    const val SQLITE_ERR_PERM: Int = 3

    /** SQLite-Fehlercode `SQLITE_AUTH` — Authorizer hat den Zugriff abgelehnt. */
    const val SQLITE_ERR_AUTH: Int = 23

    /** Diagnostic code stamped on a `Failed` outcome bei Permission-Fehlern. */
    const val CODE_PERMISSION_DENIED: String = "PROBE_PERMISSION_DENIED"

    /** Diagnostic code wenn `managed_by` nicht `d-migrate` ist. */
    const val CODE_UNMANAGED_ROW: String = "PROBE_UNMANAGED_ROW"

    /** Diagnostic code wenn `format_version` außerhalb des unterstützten Sets liegt. */
    const val CODE_UNKNOWN_FORMAT_VERSION: String = "PROBE_UNKNOWN_FORMAT_VERSION"

    /** Diagnostic code für den (defensiven) Multi-Row-Fall. */
    const val CODE_AMBIGUOUS_ROW: String = "PROBE_AMBIGUOUS_ROW"

    /** Diagnostic code für sonstige [SQLException]. */
    const val CODE_QUERY_FAILED: String = "PROBE_QUERY_FAILED"

    /** Format-Version-Wert, der für `format_version = sqlite-sequence-v1` akzeptiert wird. */
    private const val SUPPORTED_FORMAT_VERSION: String = SqliteSequenceNaming.FORMAT_VERSION

    /** Managed-by-Wert, der eine d-migrate-managed Zeile signalisiert. */
    private const val SUPPORTED_MANAGED_BY: String = SqliteSequenceNaming.MANAGED_BY

    override fun probe(
        connection: Connection,
        sequenceRef: SequenceObjectRef,
    ): SequenceCurrentValueProbeResult {
        val sql = buildProbeSql(sequenceRef.name)
        return try {
            connection.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    readSingleRow(rs, sequenceRef.name)
                }
            }
        } catch (e: SQLException) {
            mapSqlException(e)
        }
    }

    private fun mapSqlException(e: SQLException): SequenceCurrentValueProbeResult {
        val message = e.message.orEmpty()
        // xerial-sqlite-jdbc mapped Fehlertexte sind die einzige
        // verlässliche Quelle für "no such table" — error code 1 ist
        // generisches SQLITE_ERROR, das deckt zu viel ab. Daher
        // Substring-Match auf den kanonischen SQLite-Fehlertext.
        if (message.contains("no such table", ignoreCase = true)) {
            return SequenceCurrentValueProbeResult.NotFound
        }
        return when (e.errorCode) {
            SQLITE_ERR_PERM, SQLITE_ERR_AUTH -> SequenceCurrentValueProbeResult.Failed(
                code = CODE_PERMISSION_DENIED,
                message = message.ifEmpty { "SQLite denied access to dmg_sequences (errorCode=${e.errorCode})" },
            )
            else -> SequenceCurrentValueProbeResult.Failed(
                code = CODE_QUERY_FAILED,
                message = message.ifEmpty { e::class.simpleName.orEmpty() },
            )
        }
    }

    private fun readSingleRow(
        rs: java.sql.ResultSet,
        sequenceName: String,
    ): SequenceCurrentValueProbeResult {
        if (!rs.next()) return SequenceCurrentValueProbeResult.NotFound

        val nextValue = rs.getLong("next_value")
        val managedBy = rs.getString("managed_by")
        val formatVersion = rs.getString("format_version")

        if (rs.next()) {
            return SequenceCurrentValueProbeResult.Failed(
                code = CODE_AMBIGUOUS_ROW,
                message = "dmg_sequences carries multiple rows with name='$sequenceName'",
            )
        }

        if (managedBy != SUPPORTED_MANAGED_BY) {
            return SequenceCurrentValueProbeResult.Failed(
                code = CODE_UNMANAGED_ROW,
                message = "dmg_sequences row name='$sequenceName' is owned by " +
                    "managed_by='$managedBy' (expected '$SUPPORTED_MANAGED_BY')",
            )
        }

        if (formatVersion != SUPPORTED_FORMAT_VERSION) {
            return SequenceCurrentValueProbeResult.Failed(
                code = CODE_UNKNOWN_FORMAT_VERSION,
                message = "dmg_sequences row name='$sequenceName' uses format_version='$formatVersion' " +
                    "(expected '$SUPPORTED_FORMAT_VERSION')",
            )
        }

        return SequenceCurrentValueProbeResult.Read(
            value = nextValue,
            matchedRows = 1,
            isCalled = null,
            managedBy = managedBy,
            // format_version is varchar; the probe result's Int field is
            // the canonical numeric representation. Strip the
            // `sqlite-sequence-v` prefix ("sqlite-sequence-v1" → 1).
            formatVersion = formatVersion.removePrefix("sqlite-sequence-v").toIntOrNull(),
        )
    }

    private fun buildProbeSql(sequenceName: String): String {
        val table = quoteIdentifier(SqliteSequenceNaming.SUPPORT_TABLE)
        val nextValueCol = quoteIdentifier("next_value")
        val managedByCol = quoteIdentifier("managed_by")
        val formatVersionCol = quoteIdentifier("format_version")
        val nameCol = quoteIdentifier("name")
        val keyLiteral = quoteStringLiteral(sequenceName)
        return "SELECT $nextValueCol, $managedByCol, $formatVersionCol " +
            "FROM $table " +
            "WHERE $nameCol = $keyLiteral"
    }

    private fun quoteIdentifier(name: String): String = "\"${name.replace("\"", "\"\"")}\""

    private fun quoteStringLiteral(value: String): String = "'${value.replace("'", "''")}'"
}
