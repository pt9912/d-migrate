package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.ExtractReplaceRule
import dev.dmigrate.driver.FuncReplaceRule
import dev.dmigrate.driver.SubstringReplaceRule
import dev.dmigrate.driver.ViewPortabilityContext
import dev.dmigrate.driver.ViewPortabilityRules
import dev.dmigrate.driver.ViewQueryFunctionSets
import dev.dmigrate.driver.ViewQueryRule
import dev.dmigrate.driver.ViewQueryTokenSupport
import dev.dmigrate.driver.WordReplaceRule
import dev.dmigrate.driver.ViewQueryTokenType
import dev.dmigrate.driver.ViewQueryRuleSupport

/**
 * Was MySQL an einem fremden View-Rumpf nicht versteht — und was es
 * umschreiben kann.
 *
 * MySQL ist der einzige der fuenf mit einem echten Uebersetzungsregelwerk:
 * `DATE_TRUNC`, `EXTRACT` und `SUBSTRING` haben dort andere Schreibweisen mit
 * derselben Bedeutung.
 */
object MysqlViewPortabilityRules : ViewPortabilityRules {

    override val dialect: DatabaseDialect = DatabaseDialect.MYSQL

    override fun markers(context: ViewPortabilityContext): List<String> {
        val markers = mutableListOf<String>()
        // `::` ist in MySQL immer ein Syntaxfehler. `||` dagegen ist dort
        // gueltig — als logisches OR —, also nur bei fremder Herkunft ein
        // Marker: aus PostgreSQL/SQLite gemeint ist Verkettung, und die
        // Bedeutung kippte still.
        if (context.codeOnly.contains("::")) markers += "PostgreSQL-style cast (::)"
        if (context.crossDialect && context.codeOnly.contains("||")) {
            markers += "PostgreSQL/SQLite-style concatenation (||)"
        }
        return markers
    }

    override fun rules(): List<ViewQueryRule> = rewriteRules

    /**
     * Skalar- und Aggregatfunktionen, die MySQL und PostgreSQL gleich
     * schreiben und gleich meinen. Als bekannt gefuehrt, damit eine portable
     * Sicht (`SELECT FLOOR(x)`) nicht faelschlich als unportabel gilt.
     */
    override fun knownFunctions(): Set<String> =
        ViewQueryFunctionSets.BASELINE + ViewQueryFunctionSets.PORTABLE_MYSQL_POSTGRES

    private val rewriteRules: List<ViewQueryRule> = listOf(
        FuncReplaceRule("DATE_TRUNC") { _, args ->
            if (args.size == 2) {
                val unit = args[0].firstOrNull { it.type == ViewQueryTokenType.STRING }?.text?.removeSurrounding("'")
                val column = args[1].dropWhile { it.type == ViewQueryTokenType.WS }
                when (unit) {
                    "month" -> ViewQueryRuleSupport.functionCall("DATE_FORMAT", column, "'%Y-%m-01'")
                    "year" -> ViewQueryRuleSupport.functionCall("DATE_FORMAT", column, "'%Y-01-01'")
                    "day" -> ViewQueryRuleSupport.wrapCall("DATE", column)
                    else -> ViewQueryRuleSupport.originalDateTrunc(args)
                }
            } else {
                ViewQueryRuleSupport.emptyFunctionCall("DATE_TRUNC")
            }
        },
        ExtractReplaceRule("YEAR") { expr -> ViewQueryRuleSupport.wrapCall("YEAR", expr) },
        ExtractReplaceRule("MONTH") { expr -> ViewQueryRuleSupport.wrapCall("MONTH", expr) },
        SubstringReplaceRule { expr, from, length ->
            ViewQueryRuleSupport.substringCall("SUBSTRING", expr, from, length)
        },
        FuncReplaceRule("LENGTH") { _, args ->
            ViewQueryRuleSupport.callWithArgs("CHAR_LENGTH", args)
        },
        WordReplaceRule("CURRENT_DATE", "CURDATE()"),
        WordReplaceRule("CURRENT_TIME", "CURTIME()"),
        WordReplaceRule("TRUE", "1"),
        WordReplaceRule("FALSE", "0"),
    )
}
