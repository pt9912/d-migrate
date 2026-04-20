package dev.dmigrate.core.data

/**
 * Filter für [dev.dmigrate.core.data.DataChunk]-Streams.
 *
 * Wird vom CLI an den Reader durchgereicht, um nur eine Teilmenge der
 * Tabellendaten zu lesen.
 *
 * Seit 0.9.3 wird `--filter` als geschlossene DSL geparst und erzeugt
 * ausschliesslich [ParameterizedClause] mit Bind-Parametern. Die vormals
 * vorhandene [WhereClause]-Variante (rohes SQL) wurde entfernt.
 */
sealed class DataFilter {

    /**
     * Beschränkt den Export auf eine Teilmenge der Spalten.
     * Wird zu `SELECT <col1>, <col2>, ... FROM <table>` übersetzt.
     */
    data class ColumnSubset(val columns: List<String>) : DataFilter()

    /**
     * Kombiniert mehrere Filter mit AND-Semantik (für ParameterizedClauses)
     * bzw. Schnittmenge (für ColumnSubsets).
     */
    data class Compound(val parts: List<DataFilter>) : DataFilter()

    /**
     * Parametrisierte WHERE-Klausel. Der `sql`-String enthält ausschließlich
     * `?`-Platzhalter, und die zu bindenden Werte stehen in [params].
     *
     * @property sql SQL-Fragment mit `?`-Platzhaltern, identisch zur
     *   `PreparedStatement`-Syntax. Beispiel: `"\"updated_at\" >= ?"`.
     * @property params Werte, die positional an die `?`-Platzhalter
     *   gebunden werden. Die Liste muss exakt so viele Elemente haben,
     *   wie `?`-Zeichen im `sql`-String stehen; der Reader bindet sie
     *   1:1 per `setObject(idx, value)`.
     */
    data class ParameterizedClause(val sql: String, val params: List<Any?>) : DataFilter()
}
