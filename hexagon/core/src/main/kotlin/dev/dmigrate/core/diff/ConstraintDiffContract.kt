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
}
