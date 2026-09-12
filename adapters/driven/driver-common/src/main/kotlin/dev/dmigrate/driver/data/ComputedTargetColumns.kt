package dev.dmigrate.driver.data

/**
 * Die berechneten Spalten der Zieltabelle (`GENERATED ALWAYS AS (…)`) -- und
 * der Name, unter dem eine Meldung das Zielsystem nennt.
 *
 * **Warum das jeder Dialekt braucht.** Gemessen an allen fuenf Zielen: keines
 * nimmt einen Wert fuer eine berechnete Spalte an, gespeichert wie virtuell.
 * Unterschiedlich ist nur der Wortlaut, in dem der Treiber es sagt -- und
 * *wann*: der Fehler faellt mitten im ersten Chunk, nennt die Ursache nicht
 * immer und laesst offen, welche Spalte gemeint ist. Deshalb benennt
 * [AbstractTableImportSession] die Spalte **vor** dem ersten Schreiben, an
 * einer Stelle fuer alle Dialekte.
 *
 * Der Dialekt liefert nur die beiden Angaben, die er allein kennt: welche
 * Spalten es sind (sein Katalog) und wie das Ziel heisst.
 */
data class ComputedTargetColumns(
    val names: Set<String>,
    val target: String,
) {
    companion object {
        /** Keine berechnete Spalte in der Zieltabelle -- der Import prueft nichts. */
        val NONE = ComputedTargetColumns(emptySet(), "")
    }
}
