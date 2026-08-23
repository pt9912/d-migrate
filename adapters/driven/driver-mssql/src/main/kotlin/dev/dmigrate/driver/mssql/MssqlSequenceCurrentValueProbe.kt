package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SequenceCurrentValueProbeResult
import dev.dmigrate.driver.SqlIdentifiers
import java.sql.Connection
import java.sql.SQLException

/**
 * Liest den Laufzeitzustand einer SQL-Server-Sequenz aus `sys.sequences`
 * (Sub-Slice 5d).
 *
 * Anders als bei PostgreSQL wird die Sequenz nicht selbst abgefragt, sondern
 * die Katalogsicht — T-SQL kennt kein `SELECT … FROM <sequence>`. Damit gibt
 * es auch **kein** `is_called`: die Ergebniszeile traegt es als `null`.
 *
 * **Was `current_value` bedeutet (live gemessen, nicht dem Handbuch
 * entnommen):** es ist der zuletzt ausgegebene Wert — bei einer nie benutzten
 * Sequenz aber der Startwert, und der erste `NEXT VALUE FOR` gibt genau
 * diesen zurueck, ohne `current_value` zu bewegen. Ein frischer und ein
 * einmal benutzter Zustand sind daran also nicht zu unterscheiden. Wer daraus
 * fortsetzt, muss die sichere Richtung waehlen und einen Wert ueberspringen
 * statt einen zu wiederholen; die Umrechnung macht
 * [MssqlDiffSequenceOps.renderAlterSequenceCurrentValue].
 *
 * Die Probe wirft nie — jeder Fehlerpfad wird zu einem typisierten Ergebnis,
 * das die Planner-Seite einheitlich auswertet.
 */
object MssqlSequenceCurrentValueProbe {

    /** Diagnose-Code, wenn die Rolle die Katalogsicht nicht lesen darf. */
    const val CODE_PERMISSION_DENIED: String = "PROBE_PERMISSION_DENIED"

    /** Diagnose-Code fuer jeden anderen Fehlschlag. */
    const val CODE_QUERY_FAILED: String = "PROBE_QUERY_FAILED"

    /** SQL Server meldet fehlende Berechtigung mit SQLSTATE-Klasse 42. */
    private const val SQLSTATE_INSUFFICIENT_PRIVILEGE = "42000"

    fun probe(connection: Connection, sequenceRef: SequenceObjectRef): SequenceCurrentValueProbeResult {
        val name = sequenceRef.name
        val schema = sequenceRef.schema
        // `sys.sequences` ist datenbankweit; der Schemafilter kommt ueber
        // SCHEMA_NAME(schema_id), nicht ueber eine qualifizierte Referenz.
        val sql = buildString {
            append("SELECT CAST(current_value AS BIGINT) AS cv FROM sys.sequences ")
            append("WHERE name = ${literal(name)}")
            if (!schema.isNullOrBlank()) append(" AND SCHEMA_NAME(schema_id) = ${literal(schema)}")
        }
        return try {
            connection.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    if (!rs.next()) return SequenceCurrentValueProbeResult.NotFound
                    val value = rs.getLong("cv")
                    // Zwei Zeilen heisst: derselbe Name in mehreren Schemata und
                    // kein Schemafilter. Raten waere hier das Falsche.
                    if (rs.next()) {
                        return SequenceCurrentValueProbeResult.Failed(
                            code = CODE_QUERY_FAILED,
                            message = "sys.sequences matched more than one sequence named '$name'; " +
                                "qualify it with its schema.",
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

    private fun literal(value: String) = SqlIdentifiers.quoteStringLiteral(value, DatabaseDialect.MSSQL)
}
