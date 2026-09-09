package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.ExtractReplaceRule
import dev.dmigrate.driver.FuncReplaceRule
import dev.dmigrate.driver.SubstringReplaceRule
import dev.dmigrate.driver.ViewPortabilityContext
import dev.dmigrate.driver.ViewPortabilityRules
import dev.dmigrate.driver.ViewQueryRule
import dev.dmigrate.driver.ViewQueryTokenSupport
import dev.dmigrate.driver.WordReplaceRule
import dev.dmigrate.driver.ViewQueryTokenType
import dev.dmigrate.driver.ViewQueryRuleSupport

/**
 * SQLites Sicht auf einen fremden View-Rumpf.
 *
 * **Keine eigenen Marker:** SQLite kennt sowohl `::`-artige Ausdruecke nicht
 * als harten Fehler noch `LIMIT` als fremd — was es nicht versteht, faellt
 * ueber die unbekannten Funktionen auf. Die bekannte Funktionsmenge bleibt
 * bewusst die Grundmenge: mehrere der in MySQL/PostgreSQL portablen
 * Mathematik-Funktionen brauchen in SQLite die optionale Erweiterung.
 */
object SqliteViewPortabilityRules : ViewPortabilityRules {

    override val dialect: DatabaseDialect = DatabaseDialect.SQLITE

    override fun markers(context: ViewPortabilityContext): List<String> = emptyList()

    override fun rules(): List<ViewQueryRule> = rewriteRules

    private val rewriteRules: List<ViewQueryRule> = listOf(
        FuncReplaceRule("NOW") { _, _ -> ViewQueryRuleSupport.literalCall("datetime", "'now'") },
        WordReplaceRule("CURRENT_TIMESTAMP", "datetime('now')"),
        WordReplaceRule("CURRENT_DATE", "date('now')"),
        WordReplaceRule("CURRENT_TIME", "time('now')"),
        FuncReplaceRule("DATE_TRUNC") { _, args ->
            if (args.size == 2) {
                val unit = args[0].firstOrNull { it.type == ViewQueryTokenType.STRING }?.text?.removeSurrounding("'")
                val column = args[1].dropWhile { it.type == ViewQueryTokenType.WS }
                when (unit) {
                    "month" -> ViewQueryRuleSupport.functionCall("strftime", column, "'%Y-%m-01'")
                    "year" -> ViewQueryRuleSupport.functionCall("strftime", column, "'%Y-01-01'")
                    "day" -> ViewQueryRuleSupport.wrapCall("date", column)
                    else -> ViewQueryRuleSupport.originalDateTrunc(args)
                }
            } else {
                ViewQueryRuleSupport.emptyFunctionCall("DATE_TRUNC")
            }
        },
        ExtractReplaceRule("YEAR") { expr ->
            ViewQueryRuleSupport.castStrftimeInt("'%Y'", expr)
        },
        ExtractReplaceRule("MONTH") { expr ->
            ViewQueryRuleSupport.castStrftimeInt("'%m'", expr)
        },
        FuncReplaceRule("CONCAT") { _, args ->
            if (args.size >= 2) {
                args.flatMapIndexed { index, arg ->
                    if (index > 0) {
                        listOf(
                            ViewQueryTokenSupport.ws(),
                            ViewQueryTokenSupport.other("||"),
                            ViewQueryTokenSupport.ws(),
                        ) + arg.dropWhile { it.type == ViewQueryTokenType.WS }
                    } else {
                        arg.dropWhile { it.type == ViewQueryTokenType.WS }
                    }
                }
            } else {
                ViewQueryRuleSupport.emptyFunctionCall("CONCAT")
            }
        },
        SubstringReplaceRule { expr, from, length ->
            ViewQueryRuleSupport.substringCall("SUBSTR", expr, from, length)
        },
        WordReplaceRule("TRUE", "1"),
        WordReplaceRule("FALSE", "0"),
    )
}
