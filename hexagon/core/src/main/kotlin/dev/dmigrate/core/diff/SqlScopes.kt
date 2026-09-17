package dev.dmigrate.core.diff

/**
 * Die Klammer-Ebenen einer Token-Folge ([SqlTokens]) — und fuer jede Ebene,
 * ob ein Vergleich darauf **unmittelbar** steht.
 *
 * Eine Klammer **gliedert**, wenn vor ihr ein Operator, eine andere Klammer,
 * ein Komma, `AND`, `OR`, `NOT` oder gar nichts steht. Jede andere Klammer
 * gehoert zu einer Syntax: einem Funktionsaufruf, `IN (…)`, `ANY (…)`. Ein
 * Vergleich ist nur dann **eigenstaendig**, wenn ihn ausschliesslich
 * gliedernde Klammern umgeben, keine eckige Klammer und auf seiner Ebene kein
 * `BETWEEN` steht — dessen `AND` ist keine Konjunktion, und `BETWEEN` bindet
 * in PostgreSQL staerker als `=`.
 */
internal class SqlScopes private constructor(
    private val frameOf: Array<Frame>,
    private val groupingOpen: BooleanArray,
    private val partner: IntArray,
) {

    /** Ob der Vergleich am Token [index] eigenstaendig ist. */
    fun standalone(index: Int): Boolean {
        val frame = frameOf[index]
        if (frame.between) return false
        var current: Frame? = frame
        while (current != null) {
            if (!current.grouping) return false
            current = current.parent
        }
        return true
    }

    /** Ob die Klammer am Token [index] gliedert. */
    fun grouping(index: Int): Boolean = groupingOpen[index]

    /** Die zugehoerige Klammer eines `(`/`)`/`[`/`]` — sonst `-1`. */
    fun partnerOf(index: Int): Int = partner[index]

    private class Frame(val parent: Frame?, val grouping: Boolean) {
        var between = false
    }

    companion object {

        /** Die Ebenen von [tokens] — `null`, wenn die Klammern nicht aufgehen. */
        fun of(tokens: List<SqlToken>): SqlScopes? {
            val root = Frame(parent = null, grouping = true)
            val frameOf = Array(tokens.size) { root }
            val groupingOpen = BooleanArray(tokens.size)
            val partner = IntArray(tokens.size) { -1 }
            val opens = ArrayDeque<Int>()
            var frame = root
            for ((index, token) in tokens.withIndex()) {
                frameOf[index] = frame
                when (token.kind) {
                    SqlTokenKind.OPEN, SqlTokenKind.OPEN_BRACKET -> {
                        val grouping = token.kind == SqlTokenKind.OPEN && opensGroup(tokens.getOrNull(index - 1))
                        groupingOpen[index] = grouping
                        opens.addLast(index)
                        frame = Frame(frame, grouping)
                    }
                    SqlTokenKind.CLOSE, SqlTokenKind.CLOSE_BRACKET -> {
                        val open = opens.removeLastOrNull() ?: return null
                        if (!matches(tokens[open].kind, token.kind)) return null
                        partner[open] = index
                        partner[index] = open
                        frame = frame.parent ?: return null
                    }
                    else -> if (token.isWord("between")) frame.between = true
                }
            }
            return if (opens.isEmpty()) SqlScopes(frameOf, groupingOpen, partner) else null
        }

        private fun matches(open: SqlTokenKind, close: SqlTokenKind): Boolean =
            (open == SqlTokenKind.OPEN) == (close == SqlTokenKind.CLOSE)

        private fun opensGroup(previous: SqlToken?): Boolean = when {
            previous == null -> true
            previous.kind in GROUP_PREFIXES -> true
            else -> SqlKeywords.OPERAND_OPENERS.any { previous.isWord(it) }
        }

        private val GROUP_PREFIXES = setOf(SqlTokenKind.OPERATOR, SqlTokenKind.OPEN, SqlTokenKind.COMMA)
    }
}
