package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.FuncReplaceRule
import dev.dmigrate.driver.ViewPortabilityContext
import dev.dmigrate.driver.ViewPortabilityRules
import dev.dmigrate.driver.ViewQueryFunctionSets
import dev.dmigrate.driver.ViewQueryRule
import dev.dmigrate.driver.ViewQueryTokenSupport
import dev.dmigrate.driver.ViewQueryTokenType

/**
 * PostgreSQLs Sicht auf einen fremden View-Rumpf.
 *
 * **Keine eigenen Marker:** `::` und `||` sind PostgreSQLs eigene
 * Schreibweisen, `LIMIT` kennt es. Was fremd bleibt, faellt ueber die
 * unbekannten Funktionen auf.
 */
object PostgresViewPortabilityRules : ViewPortabilityRules {

    override val dialect: DatabaseDialect = DatabaseDialect.POSTGRESQL

    override fun markers(context: ViewPortabilityContext): List<String> = emptyList()

    override fun rules(): List<ViewQueryRule> = rewriteRules

    /** Dieselbe portable Menge wie MySQL — sie ist beiden gemeinsam. */
    override fun knownFunctions(): Set<String> =
        ViewQueryFunctionSets.BASELINE + ViewQueryFunctionSets.PORTABLE_MYSQL_POSTGRES

    private val rewriteRules: List<ViewQueryRule> = listOf(
        FuncReplaceRule("NOW") { _, _ -> listOf(ViewQueryTokenSupport.word("CURRENT_TIMESTAMP")) },
    )
}
