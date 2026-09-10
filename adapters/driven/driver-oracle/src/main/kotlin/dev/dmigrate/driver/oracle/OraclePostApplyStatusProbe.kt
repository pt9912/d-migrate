package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.migration.DiffObjectType
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.driver.connection.DatabaseConnection
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.JdbcOperations
import dev.dmigrate.driver.migration.UncompiledObject

/**
 * Die Nachfrage nach dem Anwenden: welche der angefassten Objekte hat Oracle
 * angenommen, aber nicht uebersetzt?
 *
 * Oracle laesst ein `CREATE OR REPLACE` mit einem Fehler im Rumpf ueber JDBC
 * **gelingen** und stellt das Objekt auf `INVALID` (live gemessen). Der
 * Post-Compare faengt das nicht: `ALL_SOURCE` fuehrt den Text auch einer
 * ungueltigen Routine, der Vergleich ist also gruen.
 *
 * Gefragt wird `ALL_ERRORS`, nicht `ALL_OBJECTS.STATUS` — der Unterschied ist
 * der Kern der Sonde und ebenfalls gemessen:
 *
 * | Fall | `STATUS` | `ALL_ERRORS` |
 * |---|---|---|
 * | Uebersetzungsfehler im Rumpf | INVALID | Zeilen mit Fundstelle |
 * | eine benutzte Spalte faellt weg | INVALID | **leer** |
 * | Spalte hinzugefuegt | VALID | leer |
 *
 * `INVALID` allein ist also mehrdeutig: Oracle invalidiert Abhaengige auch
 * planmaessig und uebersetzt sie bei der naechsten Benutzung selbst neu. Ein
 * Eintrag in `ALL_ERRORS` ist es nicht — er heisst, dass der Rumpf nicht
 * uebersetzbar ist.
 */
object OraclePostApplyStatusProbe {

    fun probe(connection: DatabaseConnection, plan: DiffResult): List<UncompiledObject> =
        probe(JdbcMetadataSession(connection.asJdbc()), plan)

    internal fun probe(session: JdbcOperations, plan: DiffResult): List<UncompiledObject> {
        val names = touchedCompilableNames(plan)
        if (names.isEmpty()) return emptyList()
        val placeholders = names.joinToString(", ") { "?" }
        return session.queryList(
            """
            SELECT name, type, line, position, text
            FROM all_errors
            WHERE owner = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')
              AND attribute = 'ERROR'
              AND name IN ($placeholders)
            ORDER BY name, sequence
            """.trimIndent(),
            *names.toTypedArray(),
        ).map { row ->
            UncompiledObject(
                name = row["name"] as? String ?: "",
                objectType = row["type"] as? String ?: "",
                line = (row["line"] as? Number)?.toInt() ?: 0,
                position = (row["position"] as? Number)?.toInt() ?: 0,
                message = (row["text"] as? String)?.trim().orEmpty(),
            )
        }
    }

    /**
     * Die Namen der Objekte, die dieser Lauf anfasst und die Oracle uebersetzt.
     *
     * Nur diese: ein Schema kann `INVALID`-Objekte tragen, die niemand in
     * diesem Lauf beruehrt hat, und die als Fehler dieses Laufs zu melden waere
     * falsch.
     *
     * Verglichen wird **buchstabengetreu**. Der Oracle-Generator schreibt
     * Bezeichner in Anfuehrungszeichen, der Katalog fuehrt sie also so, wie sie
     * im Schema stehen; ein Hochstellen auf Grossbuchstaben faende die Objekte
     * nicht wieder. Weggeworfene Objekte brauchen keine Aussonderung — sie
     * stehen nicht in `ALL_ERRORS`.
     */
    private fun touchedCompilableNames(plan: DiffResult): List<String> =
        plan.operations
            .map { it.objectRef }
            .filter { it.type in COMPILABLE }
            .map { it.path.first() }
            .distinct()

    /** Die Objektarten, deren Rumpf Oracle uebersetzt. */
    private val COMPILABLE = setOf(
        DiffObjectType.FUNCTION,
        DiffObjectType.PROCEDURE,
        DiffObjectType.TRIGGER,
        DiffObjectType.VIEW,
        DiffObjectType.MATERIALIZED_VIEW,
    )
}
