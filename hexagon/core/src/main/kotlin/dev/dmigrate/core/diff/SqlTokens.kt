package dev.dmigrate.core.diff

import dev.dmigrate.core.diff.RawSqlSkeleton.Companion.CLOSE
import dev.dmigrate.core.diff.RawSqlSkeleton.Companion.IDENTIFIER
import dev.dmigrate.core.diff.RawSqlSkeleton.Companion.LITERAL

/** Die Art eines Tokens im Geruest ([SqlTokens]). */
internal enum class SqlTokenKind {
    /** Ein Name oder Schluesselwort. */
    NAME,

    /** Eine Zahl ohne Vorzeichen (`0`, `2.5`, `.5`, `1e3`). */
    NUMBER,

    /** Der Platzhalter eines String-Literals. */
    LITERAL,

    /** Der Platzhalter eines geschuetzten Bezeichners. */
    IDENTIFIER,

    /** Eine zusammenhaengende Operatorfolge (`=`, `<>`, `~~`, `@>`). */
    OPERATOR,

    /** `::` */
    CAST,
    OPEN,
    CLOSE,
    OPEN_BRACKET,
    CLOSE_BRACKET,
    COMMA,
    DOT,

    /** Alles andere — auch eine Zahl mit angehaengtem Namen (`1abc`, `0x1F`). */
    OTHER,
}

/** Ein Token mit seiner Lage im Geruest; [end] ist exklusiv. */
internal data class SqlToken(val kind: SqlTokenKind, val text: String, val start: Int) {
    val end: Int get() = start + text.length

    /** Ob das Token das Wort [word] ist — ohne Ruecksicht auf die Schreibweise. */
    fun isWord(word: String): Boolean = kind == SqlTokenKind.NAME && text.equals(word, ignoreCase = true)
}

/**
 * Zerlegt ein Geruest ([RawSqlSkeleton]) in Tokens — **ohne** Leerraum.
 *
 * Kein Parser: die Tokens kennen keine Grammatik. Sie reichen, um in
 * [ColumnCasts] ein paar feste, kurze Muster zu erkennen; was keinem Muster
 * entspricht, bleibt unangetastet.
 */
internal object SqlTokens {

    fun of(skeleton: String): List<SqlToken> {
        val tokens = mutableListOf<SqlToken>()
        var position = 0
        while (position < skeleton.length) {
            val char = skeleton[position]
            val end = when {
                SqlLexis.WHITESPACE_CHARS.indexOf(char) >= 0 -> {
                    position++
                    continue
                }
                char == LITERAL || char == IDENTIFIER -> placeholderEnd(skeleton, position)
                char == '.' && isDecimalStart(skeleton, position, tokens.lastOrNull()) ->
                    numberEnd(skeleton, position)
                char in '0'..'9' -> numberEnd(skeleton, position)
                SqlLexis.isNameChar(char) -> nameEnd(skeleton, position)
                char == ':' && skeleton.startsWith("::", position) -> position + 2
                OPERATOR_CHARS.indexOf(char) >= 0 -> operatorEnd(skeleton, position)
                else -> position + 1
            }
            tokens += SqlToken(kindOf(skeleton, position, end), skeleton.substring(position, end), position)
            position = end
        }
        return tokens
    }

    /** Die Zeichen, aus denen PostgreSQL einen Operator bildet. */
    const val OPERATOR_CHARS = "+-*/<>=~!@#%^&|`?"

    private fun kindOf(skeleton: String, start: Int, end: Int): SqlTokenKind {
        val first = skeleton[start]
        return when {
            first == LITERAL -> SqlTokenKind.LITERAL
            first == IDENTIFIER -> SqlTokenKind.IDENTIFIER
            first in '0'..'9' || (first == '.' && end - start > 1) -> numberKind(skeleton, end)
            SqlLexis.isNameChar(first) -> SqlTokenKind.NAME
            end - start == 2 && first == ':' -> SqlTokenKind.CAST
            OPERATOR_CHARS.indexOf(first) >= 0 -> SqlTokenKind.OPERATOR
            else -> SINGLE[first] ?: SqlTokenKind.OTHER
        }
    }

    /** Eine Zahl, an der ein Name klebt, ist keine saubere Zahl (`1abc`, `0x1F`, `1_000`). */
    private fun numberKind(skeleton: String, end: Int): SqlTokenKind =
        if (end < skeleton.length && SqlLexis.isNameChar(skeleton[end])) SqlTokenKind.OTHER else SqlTokenKind.NUMBER

    private val SINGLE = mapOf(
        '(' to SqlTokenKind.OPEN,
        ')' to SqlTokenKind.CLOSE,
        '[' to SqlTokenKind.OPEN_BRACKET,
        ']' to SqlTokenKind.CLOSE_BRACKET,
        ',' to SqlTokenKind.COMMA,
        '.' to SqlTokenKind.DOT,
    )

    private fun placeholderEnd(skeleton: String, start: Int): Int = skeleton.indexOf(CLOSE, start) + 1

    /** `.5` ist eine Zahl — ausser hinter einem Namen oder einer Klammer (`t.5`, `(x).5`). */
    private fun isDecimalStart(skeleton: String, position: Int, previous: SqlToken?): Boolean {
        if (position + 1 >= skeleton.length || skeleton[position + 1] !in '0'..'9') return false
        val adjacent = previous != null && previous.end == position
        return !(adjacent && previous!!.kind in DOT_BASES)
    }

    private val DOT_BASES = setOf(
        SqlTokenKind.NAME, SqlTokenKind.IDENTIFIER, SqlTokenKind.CLOSE, SqlTokenKind.CLOSE_BRACKET,
    )

    private fun numberEnd(skeleton: String, start: Int): Int {
        var index = start
        while (index < skeleton.length && skeleton[index] in '0'..'9') index++
        if (index < skeleton.length && skeleton[index] == '.') {
            index++
            while (index < skeleton.length && skeleton[index] in '0'..'9') index++
        }
        return exponentEnd(skeleton, index)
    }

    private fun exponentEnd(skeleton: String, start: Int): Int {
        if (start >= skeleton.length || skeleton[start] !in "eE") return start
        var index = start + 1
        if (index < skeleton.length && skeleton[index] in "+-") index++
        if (index >= skeleton.length || skeleton[index] !in '0'..'9') return start
        while (index < skeleton.length && skeleton[index] in '0'..'9') index++
        return index
    }

    private fun nameEnd(skeleton: String, start: Int): Int {
        var index = start
        while (index < skeleton.length && SqlLexis.isNameChar(skeleton[index])) index++
        return index
    }

    private fun operatorEnd(skeleton: String, start: Int): Int {
        var index = start
        while (index < skeleton.length && OPERATOR_CHARS.indexOf(skeleton[index]) >= 0) index++
        return index
    }
}
