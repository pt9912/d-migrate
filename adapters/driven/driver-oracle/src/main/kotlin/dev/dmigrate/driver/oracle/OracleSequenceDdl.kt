package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers

/**
 * Sequenz-DDL fuer Oracle -- einzige Quelle fuer den Generate-Pfad
 * (`OracleDdlGenerator.generateSequences`) und den Diff-Pfad
 * (`OracleDiffSequenceOps`), nach demselben Muster wie
 * [OracleIndexDdlBuilder]. Eine zweite Fassung wuerde frueher oder spaeter
 * an einer Klausel auseinanderlaufen.
 *
 * Alle Klauseln sind live gemessen (2026-09-06,
 * `gvenzl/oracle-free:23-slim-faststart`):
 * - **`ALTER SEQUENCE` kann jede Klausel aendern** ausser dem Startwert:
 *   `INCREMENT BY`, `MINVALUE`, `MAXVALUE`, `CYCLE`, `CACHE` gehen alle
 *   durch. `ALTER SEQUENCE s START WITH n` dagegen scheitert mit
 *   `ORA-02283: cannot alter starting sequence number`.
 * - **Der Laufzeitstand wird mit `RESTART START WITH n` gesetzt** (nicht
 *   `RESTART WITH` wie in T-SQL). Ein blankes `RESTART` setzt auf den
 *   urspruenglichen Startwert zurueck.
 * - Oracle schreibt die Verneinungen zusammen (`NOMINVALUE`, `NOMAXVALUE`,
 *   `NOCYCLE`, `NOCACHE`) -- anders als T-SQLs `NO MINVALUE`.
 */
internal object OracleSequenceDdl {

    private fun quote(name: String): String = SqlIdentifiers.quoteIdentifier(name, DatabaseDialect.ORACLE)

    fun createSql(name: String, seq: SequenceDefinition): String = buildString {
        append("CREATE SEQUENCE ${quote(name)}")
        append(" START WITH ${seq.start}")
        append(attributeClauses(seq))
        append(";")
    }

    fun dropSql(name: String): String = "DROP SEQUENCE ${quote(name)};"

    /**
     * `ALTER SEQUENCE` **ohne** `START WITH`: den Startwert kann Oracle nicht
     * aendern (`ORA-02283`). Jede uebrige Klausel wird ausgeschrieben, auch
     * wenn sie unveraendert bleibt -- eine weggelassene liesse die alte
     * Schranke stehen, und Massstab ist, was ein `CREATE SEQUENCE` fuer
     * dasselbe Modell erzeugt haette.
     */
    fun alterSql(name: String, seq: SequenceDefinition): String =
        "ALTER SEQUENCE ${quote(name)}${attributeClauses(seq)};"

    /**
     * Setzt den Laufzeitstand. [next] ist der **naechste auszugebende** Wert
     * -- genau die Groesse, die Oracle in `ALL_SEQUENCES.LAST_NUMBER` fuehrt,
     * weshalb der Fortsetzungspunkt dort unveraendert uebernommen wird und
     * NICHT (wie in T-SQL) um die Schrittweite erhoeht: `sys.sequences
     * .current_value` meint dort den zuletzt ausgegebenen Wert, `LAST_NUMBER`
     * hier den naechsten.
     */
    fun restartSql(name: String, next: Long): String =
        "ALTER SEQUENCE ${quote(name)} RESTART START WITH $next;"

    fun renameSql(from: String, to: String): String = "RENAME ${quote(from)} TO ${quote(to)};"

    private fun attributeClauses(seq: SequenceDefinition): String = buildString {
        append(" INCREMENT BY ${seq.increment}")
        if (seq.minValue != null) append(" MINVALUE ${seq.minValue}") else append(" NOMINVALUE")
        if (seq.maxValue != null) append(" MAXVALUE ${seq.maxValue}") else append(" NOMAXVALUE")
        if (seq.cycle) append(" CYCLE") else append(" NOCYCLE")
        if (seq.cache != null) append(" CACHE ${seq.cache}") else append(" NOCACHE")
    }
}
