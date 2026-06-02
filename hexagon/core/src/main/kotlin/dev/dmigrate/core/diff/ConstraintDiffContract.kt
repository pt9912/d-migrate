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
}
