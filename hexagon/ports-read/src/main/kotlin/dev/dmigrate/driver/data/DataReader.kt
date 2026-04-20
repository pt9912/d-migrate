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
     * @param filter Optionaler Filter (ParameterizedClause / ColumnSubset / Compound)
     * @param chunkSize Anzahl Rows pro Chunk (Default: 10 000)
     */
    fun streamTable(
        pool: ConnectionPool,
        table: String,
        filter: DataFilter? = null,
        chunkSize: Int = 10_000,
    ): ChunkSequence

    /**
     * 0.9.0 Phase C.2 (`docs/ImpPlan-0.9.0-C2.md` §4.1 / §5.1):
     * Mid-Table-Resume-Variante. Startet den Stream ab dem letzten
     * chunk-bestaetigten Composite-Marker und sortiert deterministisch
     * nach `(markerColumn, tieBreakers)`.
     *
     * Standard-Delegation: ohne [resumeMarker] verhaelt sich die
     * Methode identisch zur Vier-Parameter-Variante. Treiber, die den
     * Mid-Table-Pfad nicht umsetzen, koennen diese Default-Impl
     * unveraendert erben — dann greift fuer jeden Caller mit gesetztem
     * [resumeMarker] der `UnsupportedOperationException`-Pfad; der
     * Runner faengt das ab und faellt auf den C.1-Kontrakt zurueck.
     *
     * Vertragliche Ergaenzungen gegenueber der Basis-Variante:
     *
     * - Der Reader garantiert eine Sortierung nach
     *   `(markerColumn, tieBreakers)` in ASC-Reihenfolge. Ohne diese
     *   Sortierung waere ein Resume-Lauf nicht reproduzierbar.
     * - Der Reader garantiert nach dem Abbruch + Resume keine
     *   Duplikate und keine verlorenen Zeilen fuer alle Zeilen, deren
     *   `(markerColumn, tieBreakers)`-Wertepaar
     *   **strikt groesser** als `(lastMarkerValue, lastTieBreakerValues)`
     *   ist. Zeilen mit **gleichem** Composite-Wert werden
     *   ausgeschlossen.
     * - Der Caller muss sicherstellen, dass [ResumeMarker.markerColumn]
     *   und [ResumeMarker.tieBreakerColumns] in der Tabelle existieren;
     *   der Reader validiert das nicht.
     */
    fun streamTable(
        pool: ConnectionPool,
        table: String,
        filter: DataFilter?,
        chunkSize: Int,
        resumeMarker: ResumeMarker?,
    ): ChunkSequence {
        if (resumeMarker == null) {
            return streamTable(pool, table, filter, chunkSize)
        }
        throw UnsupportedOperationException(
            "${this::class.simpleName} does not support mid-table resume (ResumeMarker)"
        )
    }
}
