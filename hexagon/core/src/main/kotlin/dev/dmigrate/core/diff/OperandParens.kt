package dev.dmigrate.core.diff

/**
 * Die **redundanten** Klammern um einen Operanden einer `AND`-/`OR`-Komposition
 * (ADR 0056).
 *
 * `((shipped_at IS NULL) OR (shipped_at >= placed_at))` und
 * `shipped_at IS NULL OR shipped_at>=placed_at` sind derselbe Ausdruck: jeder
 * Operand ist ein Vergleich, und ein Vergleich bindet in allen fuenf Dialekten
 * staerker als `AND` und `OR`. Die Klammern gliedern dort nichts.
 *
 * Eine Klammer faellt deshalb **nur**, wenn beides gilt:
 *
 * - sie grenzt links und rechts nur an den Ausdrucksrand, an `AND` oder an
 *   `OR` — und an mindestens einer Seite an `AND`/`OR`, sonst ist sie keine
 *   Operanden-Klammer;
 * - sie umschliesst ein einzelnes Vergleichspraedikat — einen
 *   Vergleichsoperator oder `IS [NOT] NULL` — ohne `AND`, `OR` oder `NOT` auf
 *   ihrer obersten Ebene.
 *
 * Stehen bleiben die **gliedernden** Klammern: um eine ganze Komposition, um
 * einen Rechenausdruck (`(a + b) * c`), hinter einem Funktionsnamen, hinter
 * `IN` oder `NOT`. Und auf einer Ebene mit `BETWEEN` faellt keine: dessen
 * `AND` ist keine Konjunktion, und `BETWEEN` bindet staerker als `=`.
 *
 * Laeuft auf dem Geruest ([RawSqlSkeleton]) nach der Leerraum-Faltung; Literale
 * und geschuetzte Bezeichner sind darin Platzhalter ohne Klammern.
 */
internal object OperandParens {

    fun fold(level: String): String {
        val groups = topLevelGroups(level) ?: return level
        if (groups.isEmpty()) return level
        val strippable = !BETWEEN.containsMatchIn(masked(level))
        val out = StringBuilder()
        var last = 0
        for (group in groups) {
            out.append(level, last, group.first)
            val inner = fold(level.substring(group.first + 1, group.last))
            val bare = stripEnclosing(inner)
            val redundant = strippable && isOperandSlot(level, group) && isSinglePredicate(bare)
            out.append(if (redundant) bare else "($inner)")
            last = group.last + 1
        }
        out.append(level, last, level.length)
        return out.toString()
    }

    /**
     * Die Klammergruppen der obersten Ebene als Bereich `(`…`)` — `null`, wenn
     * die Klammern nicht aufgehen. Eine Klammer in einem Index (`a[(i)]`)
     * gehoert nicht zur obersten Ebene.
     */
    private fun topLevelGroups(level: String): List<IntRange>? {
        val groups = mutableListOf<IntRange>()
        var parens = 0
        var brackets = 0
        var start = -1
        for ((index, char) in level.withIndex()) {
            when (char) {
                '[' -> brackets++
                ']' -> brackets--
                '(' -> {
                    if (parens == 0 && brackets == 0) start = index
                    parens++
                }
                ')' -> {
                    parens--
                    if (parens == 0 && start >= 0) {
                        groups += start..index
                        start = -1
                    }
                }
            }
            if (parens < 0 || brackets < 0) return null
        }
        return if (parens == 0 && brackets == 0) groups else null
    }

    /** Die oberste Ebene ohne den Inhalt von Klammern und Indizes. */
    private fun masked(level: String): String = buildString {
        var depth = 0
        for (char in level) {
            when (char) {
                '(', '[' -> depth++
                ')', ']' -> depth--
                else -> if (depth == 0) append(char)
            }
        }
    }

    /**
     * Die Klammern um den ganzen Text — so viele, wie ihn ganz umschliessen.
     * `(a > 0)` wird zu `a > 0`; `(a + b) * c` bleibt. Gehen die Klammern
     * nicht auf, bleibt der Text, wie er ist.
     */
    fun stripEnclosing(text: String): String {
        var current = text.trim()
        while (current.startsWith("(") && topLevelGroups(current)?.singleOrNull() == current.indices) {
            current = current.substring(1, current.length - 1).trim()
        }
        return current
    }

    private fun isOperandSlot(level: String, group: IntRange): Boolean {
        val before = level.substring(0, group.first).trimEnd()
        val after = level.substring(group.last + 1).trimStart()
        if (before.isEmpty() && after.isEmpty()) return false
        return (before.isEmpty() || JUNCTION_BEFORE.containsMatchIn(before)) &&
            (after.isEmpty() || JUNCTION_AFTER.containsMatchIn(after))
    }

    private fun isSinglePredicate(text: String): Boolean {
        val top = masked(text)
        if (NOT_A_SINGLE_PREDICATE.containsMatchIn(top.replace(IS_NOT, "IS"))) return false
        return IS_NULL.containsMatchIn(top) ||
            OPERATOR_RUN.findAll(top).any { it.value in SqlLexis.COMPARISON_OPERATORS }
    }

    private const val START = SqlLexis.WORD_START
    private const val END = SqlLexis.WORD_END

    private val BETWEEN = Regex("(?i)${START}BETWEEN$END")

    private val JUNCTION = SqlKeywords.CONJUNCTIONS.joinToString("|")

    private val JUNCTION_BEFORE = Regex("(?i)$START(?:$JUNCTION)$")

    private val JUNCTION_AFTER = Regex("(?i)^(?:$JUNCTION)$END")

    /**
     * Was auf der obersten Ebene eines Operanden mehr als ein Vergleich waere:
     * eine weitere Komposition, eine Negation, ein `BETWEEN` (dessen `AND`
     * keine Konjunktion ist), ein `CASE` oder eine Abfrage — und MySQLs
     * `||`/`&&`, die dort `OR`/`AND` bedeuten.
     */
    private val NOT_A_SINGLE_PREDICATE =
        Regex("(?i)$START(?:AND|OR|NOT|XOR|BETWEEN|CASE|SELECT|WITH|VALUES)$END|\\|\\||&&")

    private val IS_NOT = Regex("(?i)${START}IS\\s+NOT$END")

    private val IS_NULL = Regex("(?i)${START}IS\\s+(?:NOT\\s+)?NULL$END")

    /** Eine zusammenhaengende Operatorfolge — `@>` ist kein `>`. */
    private val OPERATOR_RUN = Regex("[=<>!@#~&|^%]+")
}
