package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.SequenceCurrentValueProbeResult
import java.sql.Connection
import java.sql.SQLException

/**
 * Liest den Laufzeitwert einer Oracle-Sequenz aus `ALL_SEQUENCES.LAST_NUMBER`.
 *
 * **`LAST_NUMBER` ist nicht der zuletzt ausgegebene Wert.** Oracle
 * praeallokiert bei `CACHE n` und schreibt die Obergrenze des Vorrats ins Data
 * Dictionary; `last_number` liegt damit **vor** dem, was Anwendungen wirklich
 * bekommen haben. Fuer den Preserve ist genau das die sichere Richtung: wer
 * dort fortsetzt, gibt keinen Wert ein zweites Mal aus. Es entsteht
 * hoechstens eine Luecke — und die entsteht bei Oracle ohnehin bei jedem
 * Instanzneustart.
 *
 * `CURRVAL` waere der genauere Wert, ist aber sitzungsgebunden: er existiert
 * nur, wenn dieselbe Sitzung vorher `NEXTVAL` gezogen hat. Eine
 * Migrationsverbindung hat das nie getan, und ein `NEXTVAL` zum Messen
 * verbrauchte den Wert, den man erhalten will.
 */
internal object OracleSequenceCurrentValueProbe {

    /** SQLSTATE-Praefix fuer fehlende Rechte (`ORA-01031` u. a.). */
    private const val SQLSTATE_INSUFFICIENT_PRIVILEGE = "42000"

    const val CODE_QUERY_FAILED: String = "ORACLE_SEQUENCE_PROBE_QUERY_FAILED"
    const val CODE_PERMISSION_DENIED: String = "ORACLE_SEQUENCE_PROBE_PERMISSION_DENIED"

    fun probe(connection: Connection, sequenceRef: SequenceObjectRef): SequenceCurrentValueProbeResult {
        val sql = buildString {
            append("SELECT last_number FROM all_sequences WHERE sequence_name = ?")
            if (!sequenceRef.schema.isNullOrBlank()) append(" AND sequence_owner = ?")
        }
        return try {
            connection.prepareStatement(sql).use { ps ->
                ps.setString(1, sequenceRef.name.uppercase())
                if (!sequenceRef.schema.isNullOrBlank()) ps.setString(2, sequenceRef.schema!!.uppercase())
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return SequenceCurrentValueProbeResult.NotFound
                    val value = rs.getLong("last_number")
                    // Zwei Zeilen heisst: derselbe Name in mehreren Schemata
                    // und kein Schemafilter. Raten waere hier das Falsche.
                    if (rs.next()) {
                        return SequenceCurrentValueProbeResult.Failed(
                            code = CODE_QUERY_FAILED,
                            message = "all_sequences matched more than one sequence named " +
                                "'${sequenceRef.name}'; qualify it with its owner.",
                        )
                    }
                    SequenceCurrentValueProbeResult.Read(value = value, matchedRows = 1, isCalled = null)
                }
            }
        } catch (e: SQLException) {
            val code = if (e.sqlState == SQLSTATE_INSUFFICIENT_PRIVILEGE) {
                CODE_PERMISSION_DENIED
            } else {
                CODE_QUERY_FAILED
            }
            SequenceCurrentValueProbeResult.Failed(code = code, message = e.message ?: e::class.simpleName.orEmpty())
        }
    }
}
