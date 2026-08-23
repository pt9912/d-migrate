package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers

/**
 * Sequenz-DDL fuer T-SQL — geteilt zwischen `schema generate` und dem
 * Diff-Pfad. SQL Server hat native Sequenzen, es gibt also nichts zu
 * emulieren; die Feinheiten stecken in den Grenzen von `ALTER SEQUENCE`.
 *
 * Geteilt und nicht zweimal geschrieben, weil beide Pfade fuer dasselbe
 * Schema dieselbe Sequenz bauen muessen — dieselbe Regel, an der im
 * Spalten-Rendering schon mehrere Fehler haengen geblieben sind.
 */
internal object MssqlSequenceDdl {

    private fun quote(name: String) = SqlIdentifiers.quoteIdentifier(name, DatabaseDialect.MSSQL)

    /**
     * SQL Server nimmt ohne MINVALUE/MAXVALUE die BIGINT-Typgrenzen; bei
     * CYCLE wuerde eine aufsteigende Sequenz dann auf -2^63 statt (wie in
     * PostgreSQL) auf 1 umbrechen — die Standard-Schranke wird dann explizit.
     */
    fun boundedMinValue(seq: SequenceDefinition): Long? =
        seq.minValue ?: if (seq.cycle && seq.increment > 0) minOf(1L, seq.start) else null

    fun boundedMaxValue(seq: SequenceDefinition): Long? =
        seq.maxValue ?: if (seq.cycle && seq.increment < 0) maxOf(-1L, seq.start) else null

    fun createSql(name: String, seq: SequenceDefinition): String = buildString {
        // BIGINT traegt jeden neutralen Wertebereich; der Reverse blendet die
        // Typgrenzen wieder zu "kein MINVALUE/MAXVALUE" aus.
        append("CREATE SEQUENCE ${quote(name)} AS BIGINT")
        append(" START WITH ${seq.start}")
        append(" INCREMENT BY ${seq.increment}")
        boundedMinValue(seq)?.let { append(" MINVALUE $it") }
        boundedMaxValue(seq)?.let { append(" MAXVALUE $it") }
        if (seq.cycle) append(" CYCLE") else append(" NO CYCLE")
        seq.cache?.let { append(" CACHE $it") }
        append(";")
    }

    fun dropSql(name: String): String = "DROP SEQUENCE ${quote(name)};"

    /**
     * `ALTER SEQUENCE` kann in T-SQL **nicht alles**, was `CREATE SEQUENCE`
     * kann: Datentyp und `START WITH` sind unveraenderlich. `RESTART WITH`
     * setzt den naechsten auszugebenden Wert und ist damit etwas anderes als
     * der deklarierte Startwert — es taugt nicht als Ersatz.
     *
     * Der geaenderte Startwert bleibt deshalb aussen vor; wer ihn wirklich
     * aendern will, muss die Sequenz neu anlegen. Der Renderer meldet das
     * ([MssqlDiffSequenceOps]), statt es stillschweigend fallen zu lassen.
     *
     * Jede uebrige Klausel steht ausdruecklich da: ein weggelassenes
     * `MINVALUE` liesse die alte Schranke stehen, und der Zielzustand haenge
     * dann davon ab, was vorher galt. Massstab ist immer, welche Sequenz
     * `CREATE SEQUENCE` fuer dasselbe Modell erzeugt haette — auch beim Cache,
     * wo „nicht angegeben" NICHT „aus" bedeutet.
     */
    fun alterSql(name: String, seq: SequenceDefinition): String = buildString {
        append("ALTER SEQUENCE ${quote(name)}")
        append(" INCREMENT BY ${seq.increment}")
        // Ohne explizite Angabe blieben die alten Schranken stehen — bei einer
        // Aenderung, die sie entfernt, waere das eine stille Abweichung.
        append(boundedMinValue(seq)?.let { " MINVALUE $it" } ?: " NO MINVALUE")
        append(boundedMaxValue(seq)?.let { " MAXVALUE $it" } ?: " NO MAXVALUE")
        if (seq.cycle) append(" CYCLE") else append(" NO CYCLE")
        // `cache = null` heisst „nicht angegeben", nicht „aus". Live gemessen:
        // ein `CREATE SEQUENCE` ohne CACHE-Klausel legt die Sequenz mit
        // `is_cached = true` an (servergewaehlte Groesse), und ein blankes
        // `ALTER SEQUENCE … CACHE` stellt genau diesen Zustand her. `NO CACHE`
        // schaltet das Caching dagegen ab — damit haette dieselbe Eingabe ueber
        // `migrate` eine andere Sequenz ergeben als ueber `generate`.
        append(seq.cache?.let { " CACHE $it" } ?: " CACHE")
        append(";")
    }

    /** `RESTART WITH` setzt den naechsten auszugebenden Wert. */
    fun restartSql(name: String, nextValue: Long): String =
        "ALTER SEQUENCE ${quote(name)} RESTART WITH $nextValue;"
}
