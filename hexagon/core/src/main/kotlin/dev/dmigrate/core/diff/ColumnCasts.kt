package dev.dmigrate.core.diff

/**
 * Faltet die Casts eines rohen Ausdrucks, die **nur Schreibweise** sind —
 * entschieden mit den Spaltentypen der Tabelle dieser Seite (ADR 0056: „ein
 * Cast auf den Typ, den der Operand ohnehin hat").
 *
 * Ein Cast aendert den Wert eines Literals nicht, kann aber die **umgebende
 * Operation** umtypen: `qty / 2::numeric > 1` ist bei `qty = 3` wahr,
 * `qty / 2 > 1` nicht. Ein Cast faellt deshalb nur, wenn beides gilt:
 *
 * 1. Der gecastete Operand ist **unmittelbarer Operand eines Vergleichs**
 *    (`=`, `<>`, `!=`, `<`, `<=`, `>`, `>=`, `LIKE`/`~~`; links auch vor
 *    `= ANY (…)`) — nie neben einem Rechenoperator, nie in einer
 *    Argumentliste, nie auf einer Ebene mit `BETWEEN` ([SqlScopes]).
 * 2. Die Spalte, um die es geht, belegt den Typ ([CastRules]): ein
 *    Spalten-Cast (`(status)::text`) haelt den Wert der Spalte und vergleicht
 *    gegen einen Partner, dessen Typ dabei derselbe bleibt; ein Literal-Cast
 *    (`'x'::text`) steht einer Spalte gegenueber, deren Typfamilie er traegt.
 *
 * Ohne Tabellenkontext, an einem Namen, der keine Spalte ist, oder bei einem
 * unbekannten Typ faellt nichts. Erkannt werden nur wenige, feste Formen —
 * kein Parser; was keiner Form entspricht, bleibt stehen.
 */
internal object ColumnCasts {

    /** [skeleton] ohne die Casts, die fuer [columns] Schreibweise sind. */
    fun fold(skeleton: String, columns: ColumnTypes): String {
        if (columns.isEmpty || !skeleton.contains("::")) return skeleton
        val tokens = SqlTokens.of(skeleton)
        val scopes = SqlScopes.of(tokens) ?: return skeleton
        val operands = CastOperands(tokens, scopes)
        val ranges = mutableSetOf<IntRange>()
        for (index in tokens.indices) {
            val kind = comparisonKind(tokens[index]) ?: continue
            if (!scopes.standalone(index)) continue
            val comparison = Comparison(operands.left(index), operands.right(index), kind == LIKE, columns)
            ranges += comparison.castsToDrop()
        }
        return drop(skeleton, ranges.toList())
    }

    private const val PLAIN = 0
    private const val LIKE = 1

    /** `LIKE`, ein anderer Vergleich — oder `null`, wenn [token] keiner ist. */
    private fun comparisonKind(token: SqlToken): Int? = when {
        token.kind == SqlTokenKind.OPERATOR && token.text == "~~" -> LIKE
        token.kind == SqlTokenKind.OPERATOR && token.text in COMPARISONS -> PLAIN
        token.isWord("like") -> LIKE
        else -> null
    }

    private val COMPARISONS = setOf("=", "<>", "!=", "<", "<=", ">", ">=")

    private fun drop(skeleton: String, ranges: List<IntRange>): String {
        if (ranges.isEmpty()) return skeleton
        val out = StringBuilder()
        var from = 0
        for (range in ranges.sortedBy { it.first }) {
            out.append(skeleton, from, range.first)
            from = range.last + 1
        }
        return out.append(skeleton, from, skeleton.length).toString()
    }
}

/**
 * Ein Vergleich mit seinen zwei Operanden — und welche seiner Casts fallen.
 *
 * Ein **Spalten-Cast** faellt, wenn er den Wert der Spalte haelt und der
 * Partner einen Typ hat, gegen den die Spalte ohne Cast genauso verglichen
 * wird: bei Text ein Text-Partner, bei Zahlen ein exakter Zahl-Partner. Ein
 * unmarkiertes String-Literal ist bei Zahlen **kein** solcher Partner — es
 * nimmt den Typ seines Gegenuebers an, und `(qty)::bigint = '3000000000'`
 * gelingt, wo `qty = '3000000000'` scheitert.
 *
 * Ein **Literal-Cast** faellt, wenn ihm eine Spalte gegenuebersteht — bloss
 * oder hinter einem Spalten-Cast, der selbst faellt — und die Regel fuer
 * deren Typ ihn als Schreibweise ausweist. Unter `LIKE` nur rechts, nur bei
 * Text.
 */
private class Comparison(
    private val left: CastOperand,
    private val right: CastOperand,
    private val like: Boolean,
    private val columns: ColumnTypes,
) {

    fun castsToDrop(): List<IntRange> = buildList {
        if (left is CastOperand.ColumnCast && columnCastFalls(left, right)) add(left.cast)
        if (!like && right is CastOperand.ColumnCast && columnCastFalls(right, left)) add(right.cast)
        if (right is CastOperand.LiteralCast && literalCastFalls(right, left)) add(right.cast)
        if (!like && left is CastOperand.LiteralCast && literalCastFalls(left, right)) add(left.cast)
        if (right is CastOperand.AnyList && textColumn(left)) addAll(right.textElementCasts)
    }

    private fun keeps(cast: CastOperand.ColumnCast): Boolean {
        val type = columns.typeOf(cast.column) ?: return false
        return CastRules.columnCastKeeps(type, cast.type, like)
    }

    private fun columnCastFalls(cast: CastOperand.ColumnCast, partner: CastOperand): Boolean {
        if (!keeps(cast)) return false
        val family = CastFamily.of(columns.typeOf(cast.column)!!)
        return if (family == CastFamily.TEXT) textPartner(partner) else exactNumericPartner(partner)
    }

    private fun literalCastFalls(cast: CastOperand.LiteralCast, partner: CastOperand): Boolean {
        val column = when (partner) {
            is CastOperand.Column -> partner.column
            is CastOperand.ColumnCast -> partner.column.takeIf { keeps(partner) }
            else -> null
        } ?: return false
        val type = columns.typeOf(column) ?: return false
        return CastRules.literalCastKeeps(type, cast.literal, cast.type, like)
    }

    private fun textColumn(operand: CastOperand): Boolean {
        val column = when (operand) {
            is CastOperand.Column -> operand.column
            is CastOperand.ColumnCast -> operand.column.takeIf { keeps(operand) }
            else -> null
        } ?: return false
        return columns.typeOf(column)?.let(CastFamily::of) == CastFamily.TEXT
    }

    private fun textPartner(partner: CastOperand): Boolean = when (partner) {
        is CastOperand.Literal -> partner.literal.string
        is CastOperand.LiteralCast -> partner.literal.string && CastRules.isTextType(partner.type)
        is CastOperand.Column -> columns.typeOf(partner.column)?.let(CastFamily::of) == CastFamily.TEXT
        is CastOperand.ColumnCast -> textColumn(partner) && CastRules.isTextType(partner.type)
        is CastOperand.AnyList -> partner.textTyped
        CastOperand.Other -> false
    }

    private fun exactNumericPartner(partner: CastOperand): Boolean = when (partner) {
        is CastOperand.Literal -> !partner.literal.string
        is CastOperand.LiteralCast -> CastRules.isExactNumericType(partner.type)
        is CastOperand.Column -> columns.typeOf(partner.column)?.let(CastFamily::of) in EXACT_NUMERIC
        is CastOperand.ColumnCast -> keeps(partner) && CastRules.isExactNumericType(partner.type) &&
            columns.typeOf(partner.column)?.let(CastFamily::of) in EXACT_NUMERIC
        is CastOperand.AnyList, CastOperand.Other -> false
    }

    private companion object {
        val EXACT_NUMERIC = setOf(CastFamily.INTEGER, CastFamily.DECIMAL)
    }
}
