package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.SequenceDefinition

/**
 * Wo eine SQL-Server-Sequenz nach einem Preserve **weiterlaeuft**.
 *
 * Zwei Begriffe, die hier nicht dasselbe sind: `sys.sequences.current_value`
 * ist der zuletzt **ausgegebene** Wert, `ALTER SEQUENCE … RESTART WITH n`
 * setzt den **naechsten**. Wer den probierten Wert unveraendert zurueckschreibt,
 * gibt ihn ein zweites Mal aus — bei einer Schluesselspalte ein Duplikat.
 *
 * Die Schrittweite gehoert deshalb dazu, und die Schranken auch: `RESTART
 * WITH` muss **innerhalb** von Minimum und Maximum liegen (SQL Server lehnt
 * alles andere ab, live belegt), und am Rand einer zyklischen Sequenz ist der
 * naechste Wert nicht Wert+Schrittweite, sondern die gegenueberliegende
 * Schranke — den Umbruch macht `RESTART WITH` naemlich nicht von selbst.
 *
 * **Hier statt im Adapter**, weil zwei Pfade dieselbe Rechnung brauchen: der
 * Renderer im Treiber (`MssqlDiffSequenceOps`) und der Atomic-Preserve-Pfad
 * der Anwendungsschicht (`AtomicPreserveRestoreSql`). Zwei Kopien derselben
 * Formel waeren zwei Gelegenheiten auseinanderzulaufen — und die
 * Abweichung faende niemand, weil beide Pfade fuer sich gruen blieben.
 */
object MssqlSequenceResume {

    /** Die wirksame Untergrenze — `null` heisst „keine". */
    fun boundedMinValue(sequence: SequenceDefinition): Long? =
        sequence.minValue ?: if (sequence.cycle && sequence.increment > 0) minOf(1L, sequence.start) else null

    /** Die wirksame Obergrenze — `null` heisst „keine". */
    fun boundedMaxValue(sequence: SequenceDefinition): Long? =
        sequence.maxValue ?: if (sequence.cycle && sequence.increment < 0) maxOf(-1L, sequence.start) else null

    /**
     * Der Wert, mit dem fortzusetzen ist — oder `null`, wenn es keinen gibt.
     *
     * `null` heisst: die Sequenz ist an ihrem Rand erschoepft und zyklt nicht.
     * Dann gibt es keinen gueltigen Fortsetzungspunkt, und zu raten waere das
     * Falsche.
     */
    fun resumePoint(lastIssued: Long, sequence: SequenceDefinition): Long? {
        val min = boundedMinValue(sequence) ?: Long.MIN_VALUE
        val max = boundedMaxValue(sequence) ?: Long.MAX_VALUE
        val next = runCatching { Math.addExact(lastIssued, sequence.increment) }.getOrNull()
        if (next != null && next in min..max) return next
        return if (sequence.cycle) {
            if (sequence.increment > 0) min else max
        } else {
            null
        }
    }
}
