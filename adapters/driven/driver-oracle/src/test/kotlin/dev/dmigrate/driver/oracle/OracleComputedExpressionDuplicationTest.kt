package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * Reine Erkennungslogik, live gemessen gegen Oracle 23 (siehe
 * [OracleComputedExpressionDuplication]-KDoc): identischer Ausdruckstext,
 * getrimmt, kollidiert -- egal ob VIRTUAL/MATERIALIZED gleich oder gemischt.
 */
class OracleComputedExpressionDuplicationTest : FunSpec({

    fun table(vararg columns: Pair<String, ColumnDefinition>, indices: List<IndexDefinition> = emptyList()) =
        TableDefinition(columns = linkedMapOf(*columns), indices = indices)

    fun computed(expression: String, stored: Boolean = false) =
        ColumnDefinition(NeutralType.Integer, generation = ColumnGeneration.Computed(expression, stored = stored))

    val plain = ColumnDefinition(NeutralType.Integer)

    test("duplicateGroups finds two VIRTUAL columns with identical (trimmed) text") {
        val t = table(
            "a" to plain, "b" to plain,
            "x" to computed("\"a\" * \"b\""),
            "y" to computed(" \"a\" * \"b\" "),
        )
        OracleComputedExpressionDuplication.duplicateGroups(t) shouldContainExactly listOf(listOf("x", "y"))
    }

    test("duplicateGroups finds a VIRTUAL/MATERIALIZED mix with identical text (measured: they DO collide)") {
        val t = table(
            "a" to plain, "b" to plain,
            "x" to computed("\"a\" * \"b\"", stored = false),
            "y" to computed("\"a\" * \"b\"", stored = true),
        )
        OracleComputedExpressionDuplication.duplicateGroups(t) shouldContainExactly listOf(listOf("x", "y"))
    }

    test("duplicateGroups is silent on different expressions") {
        val t = table(
            "a" to plain, "b" to plain,
            "x" to computed("\"a\" * \"b\""),
            "y" to computed("\"a\" + \"b\""),
        )
        OracleComputedExpressionDuplication.duplicateGroups(t).shouldBeEmpty()
    }

    test("duplicateGroups is textual only -- does not normalize operand order") {
        val t = table(
            "a" to plain, "b" to plain,
            "x" to computed("\"a\" * \"b\""),
            "y" to computed("\"b\" * \"a\""),
        )
        // Oracle selbst lehnt das live ab (kommutativ normalisiert) -- die
        // Vorabpruefung ist bewusst nur textuell und faengt diesen Fall NICHT;
        // der Server meldet ihn dann selbst beim Ausfuehren.
        OracleComputedExpressionDuplication.duplicateGroups(t).shouldBeEmpty()
    }

    test("duplicateSiblings finds the one sibling that matches, ignoring the column itself") {
        val t = table(
            "a" to plain,
            "x" to computed("\"a\" * 2"),
            "y" to computed("\"a\" * 3"),
        )
        OracleComputedExpressionDuplication.duplicateSiblings(t, "x", "\"a\" * 2").shouldBeEmpty()
        val t2 = table(
            "a" to plain,
            "x" to computed("\"a\" * 2"),
            "y" to computed("\"a\" * 2"),
        )
        OracleComputedExpressionDuplication.duplicateSiblings(t2, "x", "\"a\" * 2") shouldContainExactly listOf("y")
    }

    test("collidesWithExpressionIndex requires BOTH a plain column index and an expression index") {
        val exprOnly = table(
            "a" to plain,
            "x" to computed("\"a\" * 2"),
            indices = listOf(IndexDefinition(columns = listOf(IndexColumn.expression("\"a\" * 2")))),
        )
        OracleComputedExpressionDuplication.collidesWithExpressionIndex(exprOnly, "x", "\"a\" * 2") shouldBe false

        val both = table(
            "a" to plain,
            "x" to computed("\"a\" * 2"),
            indices = listOf(
                IndexDefinition(columns = listOf(IndexColumn.expression("\"a\" * 2"))),
                IndexDefinition(columns = listOf(IndexColumn(name = "x"))),
            ),
        )
        OracleComputedExpressionDuplication.collidesWithExpressionIndex(both, "x", "\"a\" * 2") shouldBe true
    }

    test("indexCollides: adding the plain column index second is caught") {
        val t = table(
            "a" to plain,
            "x" to computed("\"a\" * 2"),
            indices = listOf(IndexDefinition(columns = listOf(IndexColumn.expression("\"a\" * 2")))),
        )
        val newPlainIndex = IndexDefinition(columns = listOf(IndexColumn(name = "x")))
        OracleComputedExpressionDuplication.indexCollides(t, newPlainIndex) shouldBe true
    }

    test("indexCollides: adding the expression index second is caught (the reverse order)") {
        val t = table(
            "a" to plain,
            "x" to computed("\"a\" * 2"),
            indices = listOf(IndexDefinition(columns = listOf(IndexColumn(name = "x")))),
        )
        val newExprIndex = IndexDefinition(columns = listOf(IndexColumn.expression("\"a\" * 2")))
        OracleComputedExpressionDuplication.indexCollides(t, newExprIndex) shouldBe true
    }

    test("indexCollides: no collision when only one of the two index forms exists") {
        val t = table("a" to plain, "x" to computed("\"a\" * 2"))
        val newPlainIndex = IndexDefinition(columns = listOf(IndexColumn(name = "x")))
        OracleComputedExpressionDuplication.indexCollides(t, newPlainIndex) shouldBe false
    }

    test("indexCollides: an unrelated composite index does not trigger it") {
        val t = table(
            "a" to plain, "b" to plain,
            "x" to computed("\"a\" * 2"),
            indices = listOf(IndexDefinition(columns = listOf(IndexColumn(name = "a"), IndexColumn(name = "b")))),
        )
        val newExprIndex = IndexDefinition(columns = listOf(IndexColumn.expression("\"a\" * 2")))
        OracleComputedExpressionDuplication.indexCollides(t, newExprIndex) shouldBe false
    }
})
