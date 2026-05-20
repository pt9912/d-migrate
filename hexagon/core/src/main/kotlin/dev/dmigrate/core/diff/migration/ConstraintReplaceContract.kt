package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType

/**
 * F.5 Sub-Slice F: pins the reversibility classification of CHECK and
 * EXCLUDE constraint Add/Drop/Replace operations.
 *
 * The mapper emits a constraint *Replace* as two ops:
 * `DropConstraint(before)` and `AddConstraint(after)`, both tagged
 * with a shared [DiffOperation.AddConstraint.replacePairId] /
 * [DiffOperation.DropConstraint.replacePairId]. Op ids stay unique
 * (dependency-sort, artefact binding, `renderedStatements.operationIds`
 * depend on that); the pair id is a separate group identity.
 *
 * Reversibility rules per Plan §5.7 (CHECK / EXCLUDE only — UNIQUE
 * and FOREIGN_KEY keep their existing Default `AUTOMATIC` since the
 * renderer can always reconstruct the inverse from the stored
 * `ConstraintDefinition` without an expression payload):
 *
 * | Op shape                                  | Reversibility               |
 * |-------------------------------------------|-----------------------------|
 * | `AddConstraint(CHECK/EXCLUDE)`            | `AUTOMATIC`                 |
 * | `DropConstraint` with known expression    | `AUTOMATIC_WITH_DATA_RISK`  |
 * | `DropConstraint` without expression       | `NOT_REVERSIBLE`            |
 *
 * "Known expression" means [ConstraintDefinition.expression] is
 * non-null and not blank after trimming. The renderer's Down-pass
 * surfaces `ROLLBACK_NOT_POSSIBLE` (instead of the generic
 * `DIALECT_UNSUPPORTED_OPERATION`) when it sees `NOT_REVERSIBLE` and
 * the expression is missing — that branch lives in the dialect
 * renderers; this contract only annotates the op metadata.
 *
 * The Drop-side rules above apply to BOTH standalone `DropConstraint`
 * ops and the Drop half of a Replace pair (identified by a non-null
 * `replacePairId`): in either case the Down-pass re-emits an
 * `ADD CONSTRAINT … (expression)`, so the renderer needs the
 * expression. Standalone Drops therefore see the same `AUTOMATIC →
 * AUTOMATIC_WITH_DATA_RISK` transition as paired Drops once their
 * expression is known — that is intentional, since rolling a Drop
 * back implies re-applying the constraint against the current data
 * snapshot, which the Down-side cannot guarantee data-safe.
 * Plan-2 §5.7 names the Replace shape explicitly; standalone Drops
 * follow from the same reasoning.
 *
 * The pass is idempotent and order-preserving: it never reorders ops,
 * only rewrites the `reversibility` field. If an op is already
 * classified correctly (e.g. a UNIQUE constraint that the contract
 * does not touch), it is returned identity-equal.
 */
internal object ConstraintReplaceContract {

    /**
     * Walks [ops] once and returns a new list where every
     * `AddConstraint` / `DropConstraint` on a CHECK or EXCLUDE
     * constraint carries the reversibility this contract pins. All
     * other ops pass through unchanged.
     */
    fun apply(ops: List<DiffOperation>): List<DiffOperation> {
        if (ops.isEmpty()) return ops
        var touched = false
        val rewritten = ops.map { op ->
            val next = classify(op)
            if (next !== op) touched = true
            next
        }
        return if (touched) rewritten else ops
    }

    private fun classify(op: DiffOperation): DiffOperation = when (op) {
        is DiffOperation.AddConstraint -> classifyAdd(op)
        is DiffOperation.DropConstraint -> classifyDrop(op)
        else -> op
    }

    private fun classifyAdd(op: DiffOperation.AddConstraint): DiffOperation {
        if (!isRawSql(op.constraint)) return op
        return if (op.reversibility == Reversibility.AUTOMATIC) op else op.copy(reversibility = Reversibility.AUTOMATIC)
    }

    private fun classifyDrop(op: DiffOperation.DropConstraint): DiffOperation {
        if (!isRawSql(op.constraint)) return op
        val target = if (hasUsableExpression(op.constraint)) {
            Reversibility.AUTOMATIC_WITH_DATA_RISK
        } else {
            Reversibility.NOT_REVERSIBLE
        }
        return if (op.reversibility == target) op else op.copy(reversibility = target)
    }

    private fun isRawSql(c: ConstraintDefinition): Boolean =
        c.type == ConstraintType.CHECK || c.type == ConstraintType.EXCLUDE

    private fun hasUsableExpression(c: ConstraintDefinition): Boolean =
        !c.expression.isNullOrBlank()
}
