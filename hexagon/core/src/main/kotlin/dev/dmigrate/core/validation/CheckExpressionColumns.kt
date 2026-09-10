package dev.dmigrate.core.validation

/**
 * Die Spaltenbezuege in einem CHECK-Ausdruck.
 *
 * Ohne SQL-Parser laesst sich das nicht entscheiden, sondern nur schaetzen. Die
 * Schaetzung ist deshalb bewusst **zurueckhaltend**: gemeldet wird nur, was
 * unzweifelhaft an einer Spaltenstelle steht. Die Kosten sind ungleich — ein
 * Fehlalarm haelt ein gueltiges Schema an (E012 ist ein Fehler, kein Hinweis),
 * ein uebersehener Tippfehler wird spaetestens von der Datenbank beim Anwenden
 * abgelehnt, mit ihrer eigenen, genaueren Meldung.
 *
 * Erkannt wird an der **Stellung im Ausdruck**, nicht an Namenslisten:
 *
 * - ein Bezeichner vor `(` ist ein Funktionsname
 * - ein Bezeichner vor `[` ist ein Feldkonstruktor (`ARRAY[...]`)
 * - ein Bezeichner vor `.` ist eine Qualifizierung, die Spalte folgt dahinter
 * - was auf `::` oder `AS` folgt, ist ein Typname — auch mehrwortig
 *   (`character varying`, `double precision`, `timestamp with time zone`)
 *
 * Eine Namensliste bleibt fuer die Woerter, die an einer Spaltenstelle stehen
 * duerfen, ohne eine zu sein.
 */
internal object CheckExpressionColumns {

    fun referencedIn(expression: String): List<String> {
        val tokens = tokenize(expression.replace(STRING_LITERAL, " "))
        val columns = mutableListOf<String>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            when {
                token == "::" || token.equals("AS", ignoreCase = true) ->
                    index = skipTypeName(tokens, index + 1)
                !token.isIdentifier() -> index++
                token.uppercase() in NOT_A_COLUMN -> index++
                tokens.getOrNull(index + 1) in QUALIFYING -> index++
                else -> {
                    if (token !in columns) columns += token
                    index++
                }
            }
        }
        return columns
    }

    /**
     * Ueberspringt einen Typnamen. Er kann aus mehreren Woertern bestehen und
     * eine Feld-Klammer tragen (`::text[]`), aber keine runde Klammer: eine
     * Laengenangabe (`::varchar(10)`) waere zwar gueltiges SQL, ihre Zahl ist
     * aber kein Bezeichner und stoert deshalb nicht.
     */
    private fun skipTypeName(tokens: List<String>, from: Int): Int {
        var index = from
        while (index < tokens.size && tokens[index].isIdentifier()) index++
        while (index < tokens.size && (tokens[index] == "[" || tokens[index] == "]")) index++
        return index
    }

    private fun tokenize(expression: String): List<String> =
        TOKEN.findAll(expression).map { it.value }.toList()

    private fun String.isIdentifier(): Boolean = IDENTIFIER.matches(this)

    private val STRING_LITERAL = Regex("'[^']*'")

    private val TOKEN = Regex("""[A-Za-z_][A-Za-z0-9_]*|::|[(\[\].]|\S""")

    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** Zeichen, nach denen ein Bezeichner kein Spaltenname ist. */
    private val QUALIFYING = setOf("(", "[", ".")

    /**
     * Woerter, die an einer Spaltenstelle stehen duerfen, ohne eine Spalte zu
     * sein: Operatoren und Literale der Sprache, die parameterlosen
     * Standardfunktionen (sie tragen keine Klammern und faellen sonst durch),
     * und die Feldnamen von `EXTRACT`.
     */
    private val NOT_A_COLUMN = setOf(
        "AND", "OR", "NOT", "IN", "IS", "NULL", "TRUE", "FALSE", "UNKNOWN",
        "BETWEEN", "LIKE", "ILIKE", "SIMILAR", "TO", "ESCAPE", "ANY", "ALL", "SOME",
        "EXISTS", "CASE", "WHEN", "THEN", "ELSE", "END",
        "CHECK", "VALUE", "FROM", "FOR", "ARRAY", "INTERVAL", "COLLATE", "DISTINCT",
        "ASC", "DESC", "HAVING", "OLD", "NEW",
        "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER",
        "SESSION_USER", "SYSTEM_USER", "USER", "LOCALTIME", "LOCALTIMESTAMP",
        "SYSDATE", "SYSTIMESTAMP",
        "YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "SECOND", "TIMEZONE_HOUR",
        "TIMEZONE_MINUTE", "EPOCH", "QUARTER", "WEEK", "DOW", "DOY",
    )
}
