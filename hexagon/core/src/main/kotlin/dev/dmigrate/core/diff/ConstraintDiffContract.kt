package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType

/**
 * Conservative Plan-2 §F.5 comparison contract for raw-SQL constraints.
 *
 * CHECK and EXCLUDE expressions are still not semantically parsed. The first
 * F.5 slice only makes unchanged constraints comparable by stable text:
 * line endings are normalized and surrounding whitespace is ignored; any
 * other text change remains a migration blocker.
 */
internal object ConstraintDiffContract {

    fun isRawSqlConstraint(constraint: ConstraintDefinition): Boolean =
        constraint.type == ConstraintType.CHECK || constraint.type == ConstraintType.EXCLUDE

    fun comparable(constraint: ConstraintDefinition): ConstraintDefinition =
        if (isRawSqlConstraint(constraint)) {
            constraint.copy(expression = constraint.expression?.canonicalRawSqlExpression())
        } else {
            constraint
        }

    private fun String.canonicalRawSqlExpression(): String =
        replace("\r\n", "\n").replace('\r', '\n').trim()

    /**
     * Ob zwei Ausdruecke sich nur in der **Dialekt-Schreibweise** unterscheiden.
     *
     * Wird **nicht** vom Vergleich selbst gerufen, sondern nur, wenn der
     * Aufrufer die Kanonisierung angefordert hat (siehe
     * `SchemaComparator.canonicalizeRawExpressions`). `schema compare` tut das,
     * `schema migrate` bewusst nicht: dort kostet eine uebersehene Aenderung
     * eine falsch stehende Datenbank, hier nur einen Fund zu wenig.
     *
     * Kanonisiert wird **Schreibweise, nicht Bedeutung** — Whitespace,
     * redundante Klammern, der PostgreSQL-Operator `~~` (der `LIKE` ist) und
     * Casts auf den eigenen Typ. Was ein Parser braeuchte, bleibt Unterschied.
     * Siehe `docs/planning/in-progress/compare-falsch-positive-cross-dialekt.md`.
     */
    fun canonicallyEqual(left: String?, right: String?): Boolean {
        if (left == null || right == null) return left == right
        if (left == right) return true
        return left.canonicalForm() == right.canonicalForm()
    }

    private fun String.canonicalForm(): String {
        if (isEmpty()) return this
        // Literale herausnehmen, damit die Ersetzungen sie nicht anfassen.
        // Der Platzhalter kommt aus der Private Use Area: er darf **kein**
        // Leerzeichen enthalten, sonst zerstoert ihn die
        // Whitespace-Normalisierung und die Literale gehen verloren — genau der
        // Fehler, der `'a~~b'` und `'a like b'` gleichgesetzt haette.
        val literals = mutableListOf<String>()
        val skeleton = STRING_LITERAL.replace(this) { match ->
            literals += match.value
            "\uE000${literals.size - 1}"
        }
        val canonical = skeleton
            // Identifier-Quoting vereinheitlichen \u2014 dieselbe Regel wie fuer
            // View-Bodies, und aus demselben Grund: d-migrate erzeugt die
            // Differenz selbst. `OracleIdentifierRequoter` quotet die
            // Bezeichner eines CHECK-Ausdrucks auf dem Generate-Pfad bewusst,
            // weil Oracle unquotiert auf GROSSSCHREIBUNG faltet und d-migrate
            // wortgetreu quotet anlegt; der Reverse liest nur zurueck, was der
            // Generator geschrieben hat. Ohne diese drei Zeilen meldet der
            // Vergleich `TABLE_CONSTRAINT_CHANGED` fuer denselben Ausdruck.
            //
            // Nur ein **einfacher** Bezeichner wird entpackt (`"my col"` und
            // `[0]` bleiben stehen), und die Schreibweise bleibt: `"Quantity"`
            // und `quantity` sind danach weiterhin verschieden \u2014 in
            // PostgreSQL sind sie das auch.
            .replace(ANSI_IDENTIFIER, "$1")
            .replace(BACKTICK_IDENTIFIER, "$1")
            .replace(BRACKET_IDENTIFIER, "$1")
            .replace("~~", "like")
            .replace(CAST_SUFFIX, "")
            .replace(REDUNDANT_PARENS, "$1")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s*([=<>!]+|\\*|\\+|-)\\s*"), "$1")
            .trim()
            .stripOuterParens()
            .trim()
        return Regex("\uE000(\\d+)").replace(canonical) { literals[it.groupValues[1].toInt()] }
    }

    /** Ein einfaches Anfuehrungszeichen, ein verdoppeltes als Escape, Inhalt dazwischen. */
    private val STRING_LITERAL = Regex("'(?:[^']|'')*'")

    /** `"id"` \u2014 ANSI-Quoting (PostgreSQL, SQLite, Oracle). */
    private val ANSI_IDENTIFIER = Regex("\"([A-Za-z_][A-Za-z0-9_]*)\"")

    /** `` `id` `` \u2014 MySQL. */
    private val BACKTICK_IDENTIFIER = Regex("`([A-Za-z_][A-Za-z0-9_]*)`")

    /**
     * `[id]` \u2014 T-SQL. Enger gefasst als die beiden anderen, weil `[` auch in
     * JSON-Pfaden und Array-Ausdruecken steht; `[0]` faellt damit nicht unter
     * die Regel.
     */
    private val BRACKET_IDENTIFIER = Regex("\\[([A-Za-z_][A-Za-z0-9_]*)]")

    /** `::text`, `::numeric(10,2)`, `::"MyType"` — der Cast auf den eigenen Typ. */
    private val CAST_SUFFIX = Regex("::\\s*\"?[A-Za-z_][A-Za-z0-9_]*\"?(\\s*\\([^)]*\\))?")

    /** Klammern, die **nur** ein Literal oder einen Bezeichner umschliessen. */
    private val REDUNDANT_PARENS = Regex("\\(\\s*([A-Za-z0-9_.$]+)\\s*\\)")

    /**
     * Entfernt Klammern, die den **ganzen** Ausdruck umschliessen — und nur
     * solche. `(a > 0)` wird zu `a>0`; `(a + b) * c` bleibt. Eine naive
     * `removePrefix("(")` haette dort gegliedert und den Ausdruck verfaelscht.
     */
    private fun String.stripOuterParens(): String {
        var current = this
        while (current.startsWith("(") && current.endsWith(")") && closesAtEnd(current)) {
            current = current.substring(1, current.length - 1).trim()
        }
        return current
    }

    /** `true`, wenn die Klammer an Position 0 erst am letzten Zeichen schliesst. */
    private fun closesAtEnd(expression: String): Boolean {
        var depth = 0
        for (index in expression.indices) {
            when (expression[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0 && index != expression.lastIndex) return false
                }
            }
        }
        return depth == 0
    }
}
