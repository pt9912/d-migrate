package dev.dmigrate.driver.data

import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool

/**
 * Port: row-streaming aus einer Tabelle.
 *
 * Connection-Ownership (siehe docs/archive/implementation-plan-0.3.0.md §6.18):
 * - Der Reader bekommt einen [ConnectionPool], NICHT eine fertige Connection.
 * - Pro [streamTable]-Aufruf borgt der Reader sich eine eigene Connection
 *   aus dem Pool, hält sie für die Lifetime der zurückgegebenen
 *   [ChunkSequence] und gibt sie via `close()` wieder zurück.
 * - Der Caller besitzt nie eine JDBC-Connection.
 *
 * Konkrete Treiber (PostgreSQL, MySQL, SQLite) leiten typischerweise von
 * [AbstractJdbcDataReader] ab, das den Großteil der JDBC-Mechanik kapselt.
 */
interface DataReader {
    val dialect: DatabaseDialect

    /**
     * Liefert einen Pull-basierten Stream über die Daten einer Tabelle.
     *
     * Vertrag:
     * - Die zurückgegebene [ChunkSequence] ist **single-use** (siehe §6.1).
     * - Die Sequence MUSS auch bei einer **leeren Tabelle** mindestens einen
     *   Chunk mit `columns` korrekt gefüllt und `rows = emptyList()` liefern,
     *   damit Format-Writer ihren Header schreiben können (siehe §6.17).
     * - Der Caller MUSS die Sequence konsumieren oder via `use {}` schließen.
     *
     * @param pool Connection-Pool — der Reader borgt sich pro Aufruf eine
     *   eigene Connection daraus
     * @param table Tabellenname (kann schema-qualifiziert sein, z.B. `"public.orders"`)
     * @param filter Optionaler Filter (WhereClause / ColumnSubset / Compound)
     * @param chunkSize Anzahl Rows pro Chunk (Default: 10 000)
     */
    fun streamTable(
        pool: ConnectionPool,
        table: String,
        filter: DataFilter? = null,
        chunkSize: Int = 10_000,
    ): ChunkSequence
}
