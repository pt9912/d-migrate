package dev.dmigrate.driver.metadata

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration

/**
 * Die Klausel, mit der eine berechnete Spalte deklariert wird.
 *
 * Der Rumpf ist ueber alle fuenf Dialekte derselbe
 * (`GENERATED ALWAYS AS (<ausdruck>)`); was dahinter steht, ist es nicht — und
 * die Unterschiede sind gemessen, nicht der Doku entnommen:
 *
 * | Server | virtuell | gespeichert |
 * | --- | --- | --- |
 * | PostgreSQL | gibt es nicht | `STORED` (Pflicht) |
 * | MySQL, SQLite | `VIRTUAL` (Vorgabe) | `STORED` |
 * | Oracle | `VIRTUAL` (Vorgabe) | `MATERIALIZED` |
 * | SQL Server | Vorgabe, **ohne** `GENERATED ALWAYS` und **ohne Typ** | `PERSISTED` |
 *
 * SQL Server faellt aus dem Muster: dort ist `b AS (a*2)` gueltig und
 * `b INT AS (a*2)` ein Syntaxfehler — die Spalte bekommt ihren Typ aus dem
 * Ausdruck. Deshalb rendert dieser Helfer die Klausel, nicht die ganze Spalte:
 * ob ein Typ davorsteht, entscheidet der Dialekt.
 */
object ComputedColumnClause {

    /** Die Berechnung, wenn die Spalte eine traegt — sonst `null`. */
    fun of(column: ColumnDefinition): ColumnGeneration.Computed? =
        column.generation as? ColumnGeneration.Computed

    /**
     * `GENERATED ALWAYS AS (<ausdruck>) <zusatz>`.
     *
     * [suffix] ist das Wort, das der Dialekt fuer die gewaehlte Speicherform
     * verlangt — leer, wo er keines kennt.
     */
    fun clause(computed: ColumnGeneration.Computed, suffix: String): String =
        listOf("GENERATED ALWAYS AS (${computed.expression})", suffix)
            .filter { it.isNotBlank() }
            .joinToString(" ")
}
