package dev.dmigrate.core.data

/**
 * Filter für [dev.dmigrate.core.data.DataChunk]-Streams.
 *
 * Wird vom CLI an den Reader durchgereicht, um nur eine Teilmenge der
 * Tabellendaten zu lesen.
 *
 * **Sicherheitsmodell**: [WhereClause] enthält rohes SQL und wird nicht
 * parametrisiert. Der CLI-Aufruf ist ein Trust-Boundary (lokale Shell),
 * siehe implementation-plan-0.3.0.md §6.7. Eine zukünftige REST-API muss
 * den Pfad re-validieren.
 */
sealed class DataFilter {
    /**
     * Roh-WHERE-Klausel, die unverändert in `SELECT * FROM <table> WHERE <sql>`
     * eingebaut wird. Beispiel: `"created_at >= '2024-01-01'"`.
     *
     * NICHT für untrusted Input verwenden.
     */
    data class WhereClause(val sql: String) : DataFilter()

    /**
     * Beschränkt den Export auf eine Teilmenge der Spalten.
     * Wird zu `SELECT <col1>, <col2>, ... FROM <table>` übersetzt.
     */
    data class ColumnSubset(val columns: List<String>) : DataFilter()

    /**
     * Kombiniert mehrere Filter mit AND-Semantik (für WhereClauses) bzw.
     * Schnittmenge (für ColumnSubsets).
     */
    data class Compound(val parts: List<DataFilter>) : DataFilter()
}
