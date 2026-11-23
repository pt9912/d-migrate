package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType

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
     * eine falsch stehende Datenbank, hier nur einen Fund zu wenig (ADR 0056).
     *
     * Was als Schreibweise gilt und wann die Faltung sich zurueckzieht, steht
     * bei [ExpressionSpelling]; das Index-Praedikat teilt dieselbe Regel.
     * Ein Cast faellt nur mit den Spaltentypen der jeweiligen Seite
     * ([leftColumns], [rightColumns]); ohne sie bleibt er ein Unterschied.
     */
    fun canonicallyEqual(
        left: String?,
        right: String?,
        leftColumns: ColumnTypes = ColumnTypes.NONE,
        rightColumns: ColumnTypes = ColumnTypes.NONE,
    ): Boolean {
        if (left == null || right == null) return left == right
        return ExpressionSpelling.equal(left, right, leftColumns, rightColumns)
    }

    /**
     * Ob zwei CHECK-Ausdruecke denselben **Wertevorrat** derselben Spalte aufzaehlen
     * (ADR 0067).
     *
     * Wie [canonicallyEqual] nur auf Anforderung (`schema compare`). Erkannt wird mit
     * [EnumCheckProjection.valuesOf], also dieselbe enge, formbasierte Erkennung wie im
     * zielbewussten Vergleich (ADR 0055): `IN`-Liste, `= ANY (ARRAY[...])`, die
     * einelementige `=`-Form und eine OR-Kette von Gleichheiten. Die Werte gelten als
     * Menge. Die Spalte muss auf **beiden** Seiten unter demselben Namen stehen.
     *
     * Die Erkennung uebergeht jeden Cast. Hier zaehlt er: `'ABC'::character` ist
     * `'A'`, und an einer `char(n)`-Spalte vergleicht PostgreSQL aufgefuellt
     * (ADR 0056). Deshalb nur, wenn die Spalte auf beiden Seiten eine Textspalte ist
     * und jeder Cast in beiden Ausdruecken auf `text` oder `character varying` ohne
     * Laenge geht — die Casts, die PostgreSQL an einer Textspalte selbst setzt.
     */
    fun sameValueList(left: String?, right: String?, columns: SideColumns): Boolean {
        if (left == null || right == null) return false
        if (!onlyTextCasts(left) || !onlyTextCasts(right)) return false
        val shared = columns.current.names intersect columns.desired.names
        return shared.any { column ->
            if (columns.current.typeOf(column) !is NeutralType.Text) return@any false
            if (columns.desired.typeOf(column) !is NeutralType.Text) return@any false
            val l = EnumCheckProjection.valuesOf(left, column) ?: return@any false
            val r = EnumCheckProjection.valuesOf(right, column) ?: return@any false
            l.toSet() == r.toSet()
        }
    }

    /** Laengste zuerst: `character varying` darf nicht als `character` gelesen werden. */
    private val TEXT_CAST_TYPES = listOf("character varying", "varchar", "text")

    /** Ob jeder Cast ausserhalb der String-Literale auf einen Texttyp ohne Laenge geht. */
    private fun onlyTextCasts(expression: String): Boolean {
        val code = withoutStringLiterals(expression).lowercase()
        var at = code.indexOf("::")
        while (at >= 0) {
            val rest = code.substring(at + 2).trimStart()
            val type = TEXT_CAST_TYPES.firstOrNull { rest.startsWith(it) } ?: return false
            val after = rest.substring(type.length)
            val next = after.firstOrNull()
            // Ein laengerer Typname (`textfoo`) ist ein anderer Typ, eine Klammer eine Laenge.
            if (next != null && (next.isLetterOrDigit() || next == '_')) return false
            if (after.trimStart().startsWith("(")) return false
            at = code.indexOf("::", at + 2)
        }
        return true
    }

    /** [expression] mit leeren String-Literalen; `''` ist ein escaptes Hochkomma. */
    private fun withoutStringLiterals(expression: String): String {
        val out = StringBuilder()
        var inLiteral = false
        var i = 0
        while (i < expression.length) {
            val ch = expression[i]
            when {
                !inLiteral && ch == '\'' -> { inLiteral = true; out.append("''") }
                inLiteral && ch == '\'' && expression.getOrNull(i + 1) == '\'' -> i++
                inLiteral && ch == '\'' -> inLiteral = false
                !inLiteral -> out.append(ch)
            }
            i++
        }
        return out.toString()
    }
}
