package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * F.5 Sub-Slice F: pins the reversibility classification this
 * post-pass attaches to CHECK / EXCLUDE constraint Add and Drop
 * operations. The renderer reads `reversibility` to decide whether
 * the Down-pass produces an inverse statement or surfaces
 * `ROLLBACK_NOT_POSSIBLE`.
 */
class ConstraintReplaceContractTest : FunSpec({

    fun ref(table: String, name: String) = DiffObjectRef(DiffObjectType.CONSTRAINT, listOf(table, name))

    fun checkOp(name: String, expression: String?): ConstraintDefinition =
        ConstraintDefinition(name = name, type = ConstraintType.CHECK, expression = expression)

    fun excludeOp(name: String, expression: String?): ConstraintDefinition =
        ConstraintDefinition(name = name, type = ConstraintType.EXCLUDE, expression = expression)

    test("AddConstraint(CHECK) keeps AUTOMATIC reversibility") {
        val op = DiffOperation.AddConstraint(
            id = "AddConstraint:chk",
            objectRef = ref("users", "chk_age"),
            constraint = checkOp("chk_age", "age >= 0"),
        )
        val rewritten = ConstraintReplaceContract.apply(listOf(op))
        rewritten.single().reversibility shouldBe Reversibility.AUTOMATIC
    }

    test("DropConstraint(CHECK) with known expression becomes AUTOMATIC_WITH_DATA_RISK") {
        val op = DiffOperation.DropConstraint(
            id = "DropConstraint:chk",
            objectRef = ref("users", "chk_age"),
            constraint = checkOp("chk_age", "age >= 0"),
        )
        val rewritten = ConstraintReplaceContract.apply(listOf(op))
        rewritten.single().reversibility shouldBe Reversibility.AUTOMATIC_WITH_DATA_RISK
    }

    test("DropConstraint(CHECK) with missing expression becomes NOT_REVERSIBLE") {
        val op = DiffOperation.DropConstraint(
            id = "DropConstraint:chk",
            objectRef = ref("users", "chk_age"),
            constraint = checkOp("chk_age", null),
        )
        val rewritten = ConstraintReplaceContract.apply(listOf(op))
        rewritten.single().reversibility shouldBe Reversibility.NOT_REVERSIBLE
    }

    test("DropConstraint(CHECK) with blank expression becomes NOT_REVERSIBLE") {
        val op = DiffOperation.DropConstraint(
            id = "DropConstraint:chk",
            objectRef = ref("users", "chk_age"),
            constraint = checkOp("chk_age", "   "),
        )
        val rewritten = ConstraintReplaceContract.apply(listOf(op))
        rewritten.single().reversibility shouldBe Reversibility.NOT_REVERSIBLE
    }

    test("DropConstraint(EXCLUDE) follows the same expression-presence rule as CHECK") {
        val withExpr = DiffOperation.DropConstraint(
            id = "DropConstraint:ex_with",
            objectRef = ref("res", "ex_room"),
            constraint = excludeOp("ex_room", "room WITH ="),
        )
        val withoutExpr = DiffOperation.DropConstraint(
            id = "DropConstraint:ex_blank",
            objectRef = ref("res", "ex_blank"),
            constraint = excludeOp("ex_blank", null),
        )
        val rewritten = ConstraintReplaceContract.apply(listOf(withExpr, withoutExpr))
        rewritten[0].reversibility shouldBe Reversibility.AUTOMATIC_WITH_DATA_RISK
        rewritten[1].reversibility shouldBe Reversibility.NOT_REVERSIBLE
    }

    test("AddConstraint(EXCLUDE) keeps AUTOMATIC reversibility") {
        val op = DiffOperation.AddConstraint(
            id = "AddConstraint:ex",
            objectRef = ref("res", "ex_room"),
            constraint = excludeOp("ex_room", "room WITH ="),
        )
        val rewritten = ConstraintReplaceContract.apply(listOf(op))
        rewritten.single().reversibility shouldBe Reversibility.AUTOMATIC
    }

    test("UNIQUE constraint ops pass through with their default reversibility") {
        val uniqueAdd = DiffOperation.AddConstraint(
            id = "AddConstraint:u",
            objectRef = ref("users", "u_name"),
            constraint = ConstraintDefinition(
                name = "u_name",
                type = ConstraintType.UNIQUE,
                columns = listOf("name"),
            ),
        )
        val uniqueDrop = DiffOperation.DropConstraint(
            id = "DropConstraint:u",
            objectRef = ref("users", "u_name"),
            constraint = ConstraintDefinition(
                name = "u_name",
                type = ConstraintType.UNIQUE,
                columns = listOf("name"),
            ),
        )
        val rewritten = ConstraintReplaceContract.apply(listOf(uniqueAdd, uniqueDrop))
        rewritten shouldHaveSize 2
        rewritten[0].reversibility shouldBe Reversibility.AUTOMATIC
        rewritten[1].reversibility shouldBe Reversibility.AUTOMATIC
    }

    test("Replace pair (Drop+Add with shared replacePairId) classifies both ops independently") {
        val pairId = "replace:abc123"
        val drop = DiffOperation.DropConstraint(
            id = "DropConstraint:before",
            objectRef = ref("users", "chk_age"),
            constraint = checkOp("chk_age", "age >= 0"),
            replacePairId = pairId,
        )
        val add = DiffOperation.AddConstraint(
            id = "AddConstraint:after",
            objectRef = ref("users", "chk_age"),
            constraint = checkOp("chk_age", "age >= 18"),
            replacePairId = pairId,
        )
        val rewritten = ConstraintReplaceContract.apply(listOf(drop, add))
        rewritten shouldHaveSize 2
        // Drop side: known old expression → reversible-with-data-risk.
        rewritten[0].reversibility shouldBe Reversibility.AUTOMATIC_WITH_DATA_RISK
        (rewritten[0] as DiffOperation.DropConstraint).replacePairId shouldBe pairId
        // Add side: new constraint, AUTOMATIC.
        rewritten[1].reversibility shouldBe Reversibility.AUTOMATIC
        (rewritten[1] as DiffOperation.AddConstraint).replacePairId shouldBe pairId
    }

    test("Replace pair with unknown old expression flips Drop side to NOT_REVERSIBLE") {
        val pairId = "replace:xyz"
        val drop = DiffOperation.DropConstraint(
            id = "DropConstraint:before",
            objectRef = ref("users", "chk_age"),
            constraint = checkOp("chk_age", expression = null),
            replacePairId = pairId,
        )
        val add = DiffOperation.AddConstraint(
            id = "AddConstraint:after",
            objectRef = ref("users", "chk_age"),
            constraint = checkOp("chk_age", "age >= 18"),
            replacePairId = pairId,
        )
        val rewritten = ConstraintReplaceContract.apply(listOf(drop, add))
        rewritten[0].reversibility shouldBe Reversibility.NOT_REVERSIBLE
        rewritten[1].reversibility shouldBe Reversibility.AUTOMATIC
    }

    test("contract is idempotent — second pass produces the identical list") {
        val ops = listOf(
            DiffOperation.AddConstraint(
                id = "AddConstraint:a",
                objectRef = ref("users", "chk_a"),
                constraint = checkOp("chk_a", "x > 0"),
            ),
            DiffOperation.DropConstraint(
                id = "DropConstraint:b",
                objectRef = ref("users", "chk_b"),
                constraint = checkOp("chk_b", null),
            ),
        )
        val once = ConstraintReplaceContract.apply(ops)
        val twice = ConstraintReplaceContract.apply(once)
        twice shouldBe once
    }

    test("empty input returns the identical empty list") {
        ConstraintReplaceContract.apply(emptyList()) shouldBe emptyList()
    }

    test("a list with no CHECK/EXCLUDE ops is returned identity-equal") {
        val ops = listOf(
            DiffOperation.AddConstraint(
                id = "AddConstraint:u",
                objectRef = ref("users", "u_name"),
                constraint = ConstraintDefinition(
                    name = "u_name",
                    type = ConstraintType.UNIQUE,
                    columns = listOf("name"),
                ),
            ),
        )
        // Parenthesise the `===` — infix calls bind tighter than the
        // reference-equality operator, so without the parens this
        // would parse as `apply(ops) === (ops shouldBe true)`.
        (ConstraintReplaceContract.apply(ops) === ops) shouldBe true
    }
})
