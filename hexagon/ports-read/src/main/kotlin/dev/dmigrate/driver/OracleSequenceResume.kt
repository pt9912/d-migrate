package dev.dmigrate.driver

import dev.dmigrate.core.model.SequenceDefinition

/**
 * Wo eine Oracle-Sequenz nach einem Preserve weiterlaeuft.
 *
 * **`ALL_SEQUENCES.LAST_NUMBER` ist der naechste auszugebende Wert, nicht der
 * zuletzt ausgegebene** — anders als SQL Servers `current_value`. Live
 * gemessen gegen Oracle 23:
 *
 * | Zustand | `LAST_NUMBER` |
 * | --- | --- |
 * | `NOCACHE`, frisch angelegt (`START WITH 1`) | `1` |
 * | nach `NEXTVAL` → 1 | `2` |
 * | nach `NEXTVAL` → 2 | `3` |
 * | `CACHE 20`, nach `NEXTVAL` → 1 | `21` |
 * | `INCREMENT BY 5`, nach `NEXTVAL` → 100 | `105` |
 *
 * Der Fortsetzungspunkt ist damit der Wert selbst: `ALTER SEQUENCE … RESTART
 * START WITH <last_number>` gibt als naechstes genau ihn aus — keine
 * Wiederholung und keine Luecke. Die Schrittweite zu addieren waere ein
 * verschenkter Wert bei **jedem** Preserve.
 *
 * Mit `CACHE n` liegt `LAST_NUMBER` hinter dem, was Anwendungen wirklich
 * bekommen haben (Oracle praeallokiert den Block). Die dabei uebersprungenen
 * Werte sind Oracles Preis fuer den Vorrat, nicht unserer: sie gehen bei jedem
 * Instanzneustart genauso verloren.
 */
object OracleSequenceResume {

    /**
     * Der Wert, mit dem fortzusetzen ist — oder `null`, wenn er ausserhalb
     * der Schranken laege und die Sequenz nicht zyklt.
     *
     * `null` im Modell heisst „nicht deklariert", was bei Oracle nicht
     * unbegrenzt bedeutet, sondern den richtungsabhaengigen Default:
     * `NOMINVALUE` ist bei aufsteigenden Sequenzen **1**, `NOMAXVALUE` bei
     * absteigenden **-1**.
     */
    fun resumePoint(lastNumber: Long, sequence: SequenceDefinition): Long? {
        val min = sequence.minValue ?: if (sequence.increment > 0) 1L else Long.MIN_VALUE
        val max = sequence.maxValue ?: if (sequence.increment < 0) -1L else Long.MAX_VALUE
        if (lastNumber in min..max) return lastNumber
        return if (sequence.cycle) {
            if (sequence.increment > 0) min else max
        } else {
            null
        }
    }
}
