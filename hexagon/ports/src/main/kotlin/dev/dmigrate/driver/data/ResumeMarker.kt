package dev.dmigrate.driver.data

/**
 * 0.9.0 Phase C.2 (`docs/ImpPlan-0.9.0-C2.md` §4.1 / §5.1):
 * Composite-Marker-Oberflaeche fuer Mid-Table-Resume. Der Reader
 * schneidet eine bereits angefangene Tabelle ab dem zuletzt **chunk-
 * bestaetigten** Wertepaar `(markerColumn, tieBreakers...)` ab und
 * haelt die Sortierung invariant fuer Wiederholbarkeit.
 *
 * Der Typ modelliert zwei Rollen in einer einzigen Datenstruktur:
 *
 * - **Ordering** (`markerColumn`, `tieBreakerColumns`) — erzwingt
 *   `ORDER BY markerColumn ASC, tieBreaker1 ASC, ...`. Ein Export-Lauf
 *   mit `--since-column`-Vertrag nutzt diese Ordnung vom **ersten**
 *   Chunk an, damit nach einem Abbruch der Wieder-Lauf dieselbe
 *   Reihenfolge reproduzieren kann.
 * - **Position** ([position]) — optional. Wenn `null`, startet der
 *   Lauf am Anfang der Ordnung (Fresh-Track-Modus). Wenn gesetzt,
 *   schneidet der Lauf alle Zeilen unterhalb der Position weg
 *   (Resume-Modus).
 *
 * Vertrag beim Reader:
 *
 * ```
 * WHERE (<markerColumn>, <tieBreakers>) > (<markerValue>, <tieBreakerValues>)
 *       -- lexikografisch; nur bei position != null
 * ORDER BY <markerColumn> ASC, <tieBreakers> ASC...
 * ```
 *
 * `ResumeMarker` lebt bewusst im `hexagon:ports`-Modul, nahe am
 * [DataReader]; Treiber-Adapter brauchen so keinen neuen Modulpfad,
 * und der Checkpoint-Pfad (`streaming.checkpoint`) hat nur eine
 * Lesebeziehung darauf.
 */
data class ResumeMarker(
    /**
     * Nutzer-deklarierte `--since-column` (LF-013). Muss in der zu
     * exportierenden Tabelle existieren; der Reader validiert das
     * nicht, weil der Aufrufer (Runner) die Column bereits gegen das
     * Schema gecheckt hat, wenn er diesen Typ befuellt.
     */
    val markerColumn: String,
    /**
     * PK-Spalten in stabiler Reihenfolge; leere Liste heisst, dass
     * kein Tie-Breaker vorhanden ist. Der Runner sollte in diesem Fall
     * den Mid-Table-Resume-Pfad gar nicht anstossen (Phase C.2 §4.1
     * Fall 2: konservativer C.1-Fallback).
     */
    val tieBreakerColumns: List<String>,
    /**
     * Optionale Wiederaufnahmeposition. `null` heisst „Fresh-Track":
     * der Reader sortiert deterministisch, fuegt aber keinen
     * `>`-Filter hinzu — damit landet der neue Lauf vom ersten Chunk
     * an in derselben Ordnung, in der ein spaeterer Resume ihn
     * fortsetzen kann.
     */
    val position: Position? = null,
) {
    init {
        require(markerColumn.isNotBlank()) {
            "ResumeMarker.markerColumn must not be blank"
        }
        require(tieBreakerColumns.all { it.isNotBlank() }) {
            "ResumeMarker.tieBreakerColumns must not contain blank entries"
        }
        if (position != null) {
            require(tieBreakerColumns.size == position.lastTieBreakerValues.size) {
                "ResumeMarker.tieBreakerColumns (${tieBreakerColumns.size}) and " +
                    "position.lastTieBreakerValues (${position.lastTieBreakerValues.size}) " +
                    "must have the same size"
            }
        }
    }

    /**
     * Wiederaufnahmeposition fuer einen Composite-Marker. Bildet den
     * zuletzt chunk-bestaetigten Wert der Marker-Spalte und der
     * Tie-Breaker-Spalten ab. Reader vergleichen in
     * lexikografischer Reihenfolge:
     *
     * ```
     * WHERE markerColumn > ?
     *    OR (markerColumn = ? AND tieBreakerCascade(tieBreakers, lastValues))
     * ```
     */
    data class Position(
        /**
         * Zuletzt chunk-bestaetigter Marker-Wert. `null`-Werte sind
         * nominal erlaubt, produzieren aber in `> ?`-Vergleichen
         * dreiwertiges UNKNOWN — nullable Marker-Spalten sind eine
         * dokumentierte Nutzereinschraenkung (Phase C.2 §8.2).
         */
        val lastMarkerValue: Any?,
        /**
         * Parallel zu [ResumeMarker.tieBreakerColumns] — gleiche
         * Laenge, gleiche Reihenfolge. Leere Liste, wenn auch
         * [ResumeMarker.tieBreakerColumns] leer ist.
         */
        val lastTieBreakerValues: List<Any?>,
    )
}
