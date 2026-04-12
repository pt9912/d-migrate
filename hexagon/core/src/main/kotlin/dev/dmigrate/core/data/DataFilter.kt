package dev.dmigrate.core.data

/**
 * Filter für [dev.dmigrate.core.data.DataChunk]-Streams.
 *
 * Wird vom CLI an den Reader durchgereicht, um nur eine Teilmenge der
 * Tabellendaten zu lesen.
 *
 * **Sicherheitsmodell**: [WhereClause] enthält rohes SQL und wird nicht
 * parametrisiert. Der CLI-Aufruf ist ein Trust-Boundary (lokale Shell),
 * siehe docs/archive/implementation-plan-0.3.0.md §6.7. Eine zukünftige REST-API muss
 * den Pfad re-validieren. [ParameterizedClause] zieht die Grenze enger:
 * der `sql`-String enthält ausschließlich `?`-Platzhalter, und die zu
 * bindenden Werte stehen in [ParameterizedClause.params]. Damit können
 * User-Werte (z.B. `--since`) sauber parametrisiert werden, ohne
 * String-Konkatenation.
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

    /**
     * Parametrisierte WHERE-Klausel für User-Werte, die per
     * `PreparedStatement.setObject(...)` gebunden werden müssen.
     * Eingeführt in 0.4.0 für LF-013 (`--since-column` / `--since`),
     * siehe implementation-plan-0.4.0.md §3.8 und §6.12.1.
     *
     * @property sql SQL-Fragment mit `?`-Platzhaltern, identisch zur
     *   `PreparedStatement`-Syntax. Beispiel: `"\"updated_at\" >= ?"`.
     * @property params Werte, die positional an die `?`-Platzhalter
     *   gebunden werden. Die Liste muss exakt so viele Elemente haben,
     *   wie `?`-Zeichen im `sql`-String stehen; der Reader bindet sie
     *   1:1 per `setObject(idx, value)`.
     *
     * **M-R5**: Wenn dieser Filter-Typ im selben [Compound] mit einem
     * rohen [WhereClause] kombiniert wird, darf der `WhereClause.sql`
     * KEIN literales `?`-Zeichen enthalten — sonst driften die
     * Bind-Positionen auseinander. Der CLI-Pre-Flight prüft das vor
     * dem Aufruf des Readers.
     */
    data class ParameterizedClause(val sql: String, val params: List<Any?>) : DataFilter()
}
