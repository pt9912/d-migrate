package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.IndexDefinition

/**
 * Klauseln, die der Generate- und der Diff-Pfad gleich schreiben muessen.
 *
 * PostgreSQL baut seinen `CREATE INDEX` an zwei Stellen: im `DdlGenerator` und
 * im Diff-Builder. Das ist historisch gewachsen und laesst sich nicht in einem
 * Zug zusammenlegen -- der Generate-Pfad kennt Operatorklassen-Pruefungen und
 * eine reichere Volltext-Expansion, die der Diff-Pfad nicht hat.
 *
 * Was beide gleich schreiben muessen, steht deshalb hier. `INCLUDE` war der
 * erste Fall, an dem die beiden auseinanderliefen: der Generator lernte es, der
 * Diff-Builder nicht, und damit schrieb `schema migrate` einen anderen Index als
 * `schema generate` -- ohne Meldung, mit Drift im Post-Compare als einzigem
 * Symptom. Wer hier etwas ergaenzt, ergaenzt es fuer beide.
 */
internal object PostgresIndexClauses {

    /**
     * `INCLUDE (…)` -- die Nicht-Schluesselspalten eines abdeckenden Index
     * (PostgreSQL ab 11). Leer, wenn der Index keine traegt.
     *
     * Steht zwischen der Spaltenliste und einem `WHERE`; die Reihenfolge ist
     * nicht frei, PostgreSQL erwartet sie so.
     */
    fun include(index: IndexDefinition, quote: (String) -> String): String =
        if (index.includeColumns.isEmpty()) {
            ""
        } else {
            " INCLUDE (${index.includeColumns.joinToString(", ") { quote(it) }})"
        }
}
