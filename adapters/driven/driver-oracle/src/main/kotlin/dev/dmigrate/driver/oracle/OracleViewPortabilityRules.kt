package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.ViewPortabilityContext
import dev.dmigrate.driver.ViewPortabilityRules
import dev.dmigrate.driver.ViewQueryClauseDetection

/**
 * Was Oracle an einem fremden View-Rumpf nicht versteht.
 *
 * **Kein Umschreibregelwerk** (wie SQL Server): Rumpf-Texte passieren
 * unveraendert, Nicht-Portabilitaet meldet [markers] als E053. Die bekannte
 * Funktionsmenge bleibt konservativ bei der Grundmenge, bis ein
 * Oracle-Verdikt die dortigen Eigenheiten belegt.
 */
object OracleViewPortabilityRules : ViewPortabilityRules {

    override val dialect: DatabaseDialect = DatabaseDialect.ORACLE

    override fun markers(context: ViewPortabilityContext): List<String> {
        val markers = mutableListOf<String>()
        // Oracle kennt weder `::` noch eine `LIMIT`-Klausel (`FETCH FIRST n
        // ROWS ONLY` / `ROWNUM`) — harte Syntaxfehler, unabhaengig von der
        // Quelle.
        if (context.codeOnly.contains("::")) markers += "PostgreSQL-style cast (::)"
        if (ViewQueryClauseDetection.hasLimitClause(context.tokens)) {
            markers += "LIMIT clause (Oracle uses FETCH FIRST n ROWS ONLY / ROWNUM)"
        }
        // `||` bleibt fuer die meisten Quellen unmarkiert: Oracle traegt es
        // nativ als Stringverkettung wie PostgreSQL/SQLite. Nur aus MySQL ist
        // es per Default logisches OR — unveraendert uebernommen kippte die
        // Bedeutung still von OR auf Verkettung.
        if (context.sourceIs(DatabaseDialect.MYSQL) && context.codeOnly.contains("||")) {
            markers += "MySQL-style logical OR (||), reinterpreted as concatenation in Oracle"
        }
        return markers
    }
}
