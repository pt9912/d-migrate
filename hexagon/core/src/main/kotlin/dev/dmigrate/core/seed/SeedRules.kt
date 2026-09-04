package dev.dmigrate.core.seed

/**
 * Ein einzelnes Token in einer geparsten `template`-Regel (AE-4). Rein
 * schema-unabhängig -- welche konkreten Werte eingesetzt werden, entscheidet
 * erst [ColumnValueGenerator] beim Rendern; das Parsen hier prüft
 * ausschließlich Syntax, kein Zufallszugriff.
 */
sealed class TemplateSegment {
    data class Literal(val text: String) : TemplateSegment()
    data object Word : TemplateSegment()
    data class Digits(val count: Int) : TemplateSegment()
    data object Uuid : TemplateSegment()
}

/**
 * Parst ein `--rules`-`template`-Muster (`{word}`, `{digits:N}`, `{uuid}`,
 * beliebiger Literal-Text dazwischen) in [TemplateSegment]e. Wird sowohl
 * beim Laden der Regeldatei aufgerufen (Syntaxfehler = Exit 7, AE-4-Review-
 * Ergänzung: Template-Syntax ist schemaunabhängig prüfbar und gehört an
 * die Lade-Zeit) als auch -- über [ColumnRule.Template.segments] gecacht --
 * beim eigentlichen Rendern.
 *
 * @throws IllegalArgumentException bei nicht geschlossenem `{`, unmatched
 *   `}` oder unbekanntem Token-Namen.
 */
fun parseSeedTemplate(pattern: String): List<TemplateSegment> {
    val segments = mutableListOf<TemplateSegment>()
    val literal = StringBuilder()
    var i = 0
    while (i < pattern.length) {
        when (val c = pattern[i]) {
            '{' -> {
                val end = pattern.indexOf('}', i)
                require(end >= 0) { "seed template '$pattern' has an unclosed '{' at position $i" }
                if (literal.isNotEmpty()) {
                    segments += TemplateSegment.Literal(literal.toString())
                    literal.clear()
                }
                segments += parseTemplateToken(pattern.substring(i + 1, end), pattern)
                i = end + 1
            }
            '}' -> throw IllegalArgumentException("seed template '$pattern' has an unmatched '}' at position $i")
            else -> {
                literal.append(c)
                i++
            }
        }
    }
    if (literal.isNotEmpty()) segments += TemplateSegment.Literal(literal.toString())
    return segments
}

private fun parseTemplateToken(token: String, pattern: String): TemplateSegment = when {
    token == "word" -> TemplateSegment.Word
    token == "uuid" -> TemplateSegment.Uuid
    token.startsWith("digits:") -> TemplateSegment.Digits(parseDigitsCount(token, pattern))
    else -> throw IllegalArgumentException(
        "seed template '$pattern' has unknown token '{$token}' (expected: word, digits:N, uuid)",
    )
}

private fun parseDigitsCount(token: String, pattern: String): Int {
    val count = token.removePrefix("digits:").toIntOrNull()
    require(count != null && count >= 0) {
        "seed template '$pattern' has invalid '{$token}' -- N in 'digits:N' must be a non-negative integer"
    }
    return count
}

/**
 * Eine Spalten-Override-Strategie aus einer `--rules`-Datei (AE-4). Drei
 * Strategien, kein Regex-Generator (bewusst, s. ImpPlan-1.3.0-cli-data-seed-p2.md
 * AE-4). Strukturelle Gültigkeit (nicht-leere `values`, `weights`-Länge,
 * `min <= max`, Template-Syntax) wird beim Laden geprüft
 * (`SeedRulesFileLoader`); Typ-Kompatibilität mit der jeweiligen Spalte
 * erst bei Anwendung (`TableRowSeeder`, AE-5).
 */
sealed class ColumnRule {

    /** Feste Werteliste, optional gewichtet (Default: Gleichverteilung). */
    data class Values(val values: List<Any?>, val weights: List<Double>? = null) : ColumnRule()

    /** Numerischer Bereich `[min, max]` (inklusiv). */
    data class Range(val min: Double, val max: Double) : ColumnRule()

    /** Text-Vorlage aus `{word}`/`{digits:N}`/`{uuid}`-Tokens plus Literal-Text. */
    data class Template(val pattern: String) : ColumnRule() {
        /** Geparst bei erstem Zugriff und gecacht -- ein Syntaxfehler wirft hier, nicht erst beim Rendern. */
        val segments: List<TemplateSegment> by lazy { parseSeedTemplate(pattern) }
    }
}

/**
 * Eine einzelne Zeile aus einer `--rules`-Datei: `table` optional
 * (Wildcard über alle Tabellen), `column` verbindlich. Erste passende
 * Regel in Dateireihenfolge gewinnt (AE-2) -- tabellenspezifische Regeln
 * gehören deshalb VOR Wildcard-Regeln für denselben Spaltennamen (siehe
 * Anwenderhandbuch §3.22).
 */
data class SeedRuleEntry(val table: String?, val column: String, val rule: ColumnRule) {
    fun matches(tableName: String, columnName: String): Boolean =
        columnName == column && (table == null || table == tableName)
}

/**
 * Geordnete Menge von `--rules`-Regeln für `data seed` P2
 * (ImpPlan-1.3.0-cli-data-seed-p2.md AP1). [resolve] ist seiteneffektfrei;
 * [markUsed] ist ein separater, idempotenter Aufruf, den der Aufrufer bei
 * jeder tatsächlichen Anwendung einer gefundenen Regel tätigt
 * (Review-Korrektur zu AE-1/AE-7 -- löst den Widerspruch "pure resolve()"
 * vs. "mutable Tracking" auf). Da `TableRowSeeder` pro Zeile (nicht nur
 * einmal pro Spalte) aufruft, wird [markUsed] für dieselbe Regel
 * entsprechend oft aufgerufen -- durch die Set-Semantik unschädlich.
 */
class SeedRuleSet(private val entries: List<SeedRuleEntry>) {

    private val used = mutableSetOf<SeedRuleEntry>()

    /** Erste in Dateireihenfolge passende Regel für `tableName.columnName`, oder `null`. */
    fun resolve(tableName: String, columnName: String): SeedRuleEntry? =
        entries.firstOrNull { it.matches(tableName, columnName) }

    /** Idempotent: markiert [entry] als tatsächlich zur Wertegenerierung verwendet (AE-7). */
    fun markUsed(entry: SeedRuleEntry) {
        used += entry
    }

    /** Regeln, die nie [markUsed] wurden -- Tippfehler, FK-Spalte (AE-3) oder nicht existierende Tabelle.Spalte. */
    fun unused(): List<SeedRuleEntry> = entries.filterNot { it in used }

    companion object {
        val EMPTY = SeedRuleSet(emptyList())
    }
}
