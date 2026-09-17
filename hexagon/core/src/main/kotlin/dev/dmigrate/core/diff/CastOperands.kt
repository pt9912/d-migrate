package dev.dmigrate.core.diff

/** Ein Operand eines Vergleichs, soweit [ColumnCasts] ihn erkennt. */
internal sealed interface CastOperand {

    /** Ein bloßer Name — ob er eine Spalte ist, entscheidet [ColumnTypes]. */
    data class Column(val column: String) : CastOperand

    /** `spalte::typ` oder `(spalte)::typ`; [cast] ist der Zeichenbereich von `::typ`. */
    data class ColumnCast(val column: String, val type: String, val cast: IntRange) : CastOperand

    /** Ein unmarkiertes Literal, auch in Klammern (`(0)`). */
    data class Literal(val literal: CastLiteral) : CastOperand

    /** `literal::typ` oder `(literal)::typ`. */
    data class LiteralCast(val literal: CastLiteral, val type: String, val cast: IntRange) : CastOperand

    /**
     * `ANY (…)`, `SOME (…)` oder `ALL (…)` rechts von einem Vergleich.
     *
     * [textTyped]: das Argument ist ein Text-Array — ein unmarkiertes
     * Array-Literal, ein `(…)::text[]` oder ein `ARRAY[…]` aus lauter
     * String-Literalen mit Text-Cast oder ohne. [textElementCasts]: die
     * `::text` der Elemente eines `ARRAY[…]` aus lauter String-Literalen — ein
     * solches Array ist auch ohne sie `text[]`.
     */
    data class AnyList(val textTyped: Boolean, val textElementCasts: List<IntRange>) : CastOperand

    /** Alles andere. */
    data object Other : CastOperand
}

/**
 * Erkennt die Operanden links und rechts eines Vergleichs in einer
 * Token-Folge — nur die festen Formen aus [CastOperand], und nur, wenn sie
 * den **ganzen** Operanden bilden: links steht davor der Ausdrucksrand, eine
 * gliedernde Klammer, `AND`, `OR` oder `NOT`; rechts folgt der Rand, eine
 * schliessende Klammer, `AND` oder `OR`.
 */
internal class CastOperands(private val tokens: List<SqlToken>, private val scopes: SqlScopes) {

    private data class Core(val kind: SqlTokenKind, val text: String, val first: Int, val last: Int)

    private data class TypeSpan(val cast: Int, val phrase: String, val last: Int)

    /** Der Operand, der unmittelbar vor dem Vergleich am Token [operator] endet. */
    fun left(operator: Int): CastOperand {
        val end = operator - 1
        val typed = typeBefore(end)
        val coreEnd = typed?.let { it.cast - 1 } ?: end
        val core = coreEndingAt(coreEnd) ?: return CastOperand.Other
        if (!leftBoundary(core.first - 1)) return CastOperand.Other
        return operand(core, typed)
    }

    /** Der Operand, der unmittelbar nach dem Vergleich am Token [operator] beginnt. */
    fun right(operator: Int): CastOperand {
        val start = operator + 1
        quantified(start)?.let { return it }
        val core = coreStartingAt(start) ?: return CastOperand.Other
        val typed = typeAfter(core.last + 1)
        if (!rightBoundary((typed?.last ?: core.last) + 1)) return CastOperand.Other
        return operand(core, typed)
    }

    private fun operand(core: Core, typed: TypeSpan?): CastOperand {
        val literal = CastLiteral(string = core.kind == SqlTokenKind.LITERAL, text = core.text)
        if (typed == null) {
            return if (core.kind == SqlTokenKind.NAME) CastOperand.Column(core.text) else CastOperand.Literal(literal)
        }
        val cast = tokens[core.last].end until tokens[typed.last].end
        return if (core.kind == SqlTokenKind.NAME) {
            CastOperand.ColumnCast(core.text, typed.phrase, cast)
        } else {
            CastOperand.LiteralCast(literal, typed.phrase, cast)
        }
    }

    private fun coreEndingAt(end: Int): Core? {
        val token = tokens.getOrNull(end) ?: return null
        if (token.kind in CORE_KINDS) return Core(token.kind, token.text, end, end)
        if (token.kind != SqlTokenKind.CLOSE || end < 2) return null
        return parenthesized(end - 2)
    }

    private fun coreStartingAt(start: Int): Core? {
        val token = tokens.getOrNull(start) ?: return null
        if (token.kind in CORE_KINDS) return Core(token.kind, token.text, start, start)
        return if (token.kind == SqlTokenKind.OPEN) parenthesized(start) else null
    }

    /** `(name)`, `(zahl)` oder `('literal')` in einer gliedernden Klammer ab [open]. */
    private fun parenthesized(open: Int): Core? {
        if (tokens[open].kind != SqlTokenKind.OPEN || !scopes.grouping(open)) return null
        if (scopes.partnerOf(open) != open + 2) return null
        val inner = tokens[open + 1]
        return if (inner.kind in CORE_KINDS) Core(inner.kind, inner.text, open, open + 2) else null
    }

    /** `::typ`, das genau an [end] endet — der ganze Wortlauf dazwischen ist der Typ. */
    private fun typeBefore(end: Int): TypeSpan? {
        var index = end
        while (index >= 0 && tokens[index].kind == SqlTokenKind.NAME && end - index < CastRules.MAX_TYPE_WORDS) index--
        if (index < 0 || index == end || tokens[index].kind != SqlTokenKind.CAST) return null
        val phrase = (index + 1..end).joinToString(" ") { tokens[it].text }
        return if (phrase in CastRules.KNOWN_TYPES) TypeSpan(index, phrase, end) else null
    }

    /** `::typ` ab [cast] — der laengste bekannte Typname. */
    private fun typeAfter(cast: Int): TypeSpan? {
        if (tokens.getOrNull(cast)?.kind != SqlTokenKind.CAST) return null
        var best: TypeSpan? = null
        val words = mutableListOf<String>()
        var index = cast + 1
        while (index < tokens.size && tokens[index].kind == SqlTokenKind.NAME && words.size < CastRules.MAX_TYPE_WORDS) {
            words += tokens[index].text
            val phrase = words.joinToString(" ")
            if (phrase in CastRules.KNOWN_TYPES) best = TypeSpan(cast, phrase, index)
            index++
        }
        return best
    }

    private fun leftBoundary(index: Int): Boolean {
        val token = tokens.getOrNull(index) ?: return index < 0
        return when (token.kind) {
            SqlTokenKind.OPEN -> scopes.grouping(index)
            SqlTokenKind.NAME -> SqlKeywords.OPERAND_OPENERS.any { token.isWord(it) }
            else -> false
        }
    }

    private fun rightBoundary(index: Int): Boolean {
        val token = tokens.getOrNull(index) ?: return index >= tokens.size
        return when (token.kind) {
            SqlTokenKind.CLOSE -> true
            SqlTokenKind.NAME -> SqlKeywords.CONJUNCTIONS.any { token.isWord(it) }
            else -> false
        }
    }

    /** `ANY (…)`/`SOME (…)`/`ALL (…)` ab [start] — `null`, wenn dort keines steht. */
    private fun quantified(start: Int): CastOperand? {
        val word = tokens.getOrNull(start) ?: return null
        if (QUANTIFIERS.none { word.isWord(it) }) return null
        val open = start + 1
        if (tokens.getOrNull(open)?.kind != SqlTokenKind.OPEN) return CastOperand.Other
        val close = scopes.partnerOf(open)
        if (!rightBoundary(close + 1)) return CastOperand.Other
        val elements = arrayElements(open + 1, close - 1)
        val textElements = elements != null && elements.all { it.type == null || it.type == "text" }
        return CastOperand.AnyList(
            textTyped = untypedLiteral(open + 1, close - 1) || textArrayCast(open + 1, close - 1) ||
                (elements != null && elements.all { it.type == null || CastRules.isTextType(it.type) }),
            textElementCasts = if (textElements) elements.orEmpty().mapNotNull { it.cast } else emptyList(),
        )
    }

    private data class Element(val type: String?, val cast: IntRange?)

    /** Die Elemente von `ARRAY[…]` zwischen [first] und [last] — nur String-Literale mit oder ohne Cast. */
    private fun arrayElements(first: Int, last: Int): List<Element>? {
        if (last - first < 2 || !tokens[first].isWord("array")) return null
        val bracket = first + 1
        if (tokens[bracket].kind != SqlTokenKind.OPEN_BRACKET || scopes.partnerOf(bracket) != last) return null
        val elements = mutableListOf<Element>()
        var index = bracket + 1
        while (index < last) {
            if (tokens[index].kind != SqlTokenKind.LITERAL) return null
            val type = typeAfter(index + 1)
            val end = type?.last ?: index
            elements += Element(type?.phrase, type?.let { tokens[index].end until tokens[it.last].end })
            val next = end + 1
            if (next < last && tokens[next].kind != SqlTokenKind.COMMA) return null
            if (next < last && next + 1 == last) return null
            index = next + 1
        }
        return elements.takeIf { it.isNotEmpty() }
    }

    private fun untypedLiteral(first: Int, last: Int): Boolean =
        first == last && tokens[first].kind == SqlTokenKind.LITERAL

    /** `(…)::text[]` — das ganze Argument, auf ein Text-Array gecastet. */
    private fun textArrayCast(first: Int, last: Int): Boolean {
        if (last - first < 5 || tokens[first].kind != SqlTokenKind.OPEN) return false
        val close = scopes.partnerOf(first)
        if (close < 0 || close + 1 > last) return false
        val type = typeAfter(close + 1) ?: return false
        return CastRules.isTextType(type.phrase) && type.last + 2 == last &&
            tokens[last - 1].kind == SqlTokenKind.OPEN_BRACKET && scopes.partnerOf(last - 1) == last
    }

    private companion object {
        val CORE_KINDS = setOf(SqlTokenKind.NAME, SqlTokenKind.NUMBER, SqlTokenKind.LITERAL)
        val QUANTIFIERS = listOf("any", "some", "all")
    }
}
