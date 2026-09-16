package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Die redundanten Klammern um die Operanden einer `AND`-/`OR`-Komposition
 * (ADR 0056). Die drei Formen von `ck_order_ship_after_place` sind
 * **woertlich** aus der Konsumentenmessung gegen 1.7.1.
 *
 * „Gliedernd" heisst hier, was **stehen bleibt** — die Gegenproben unten sind
 * der eigentliche Punkt: eine Klammer-Regel, die zu viel wegnimmt, macht aus
 * `(a + b) * c` still `a + b * c`.
 */
class OperandParenthesesCanonicalisationTest : FunSpec({

    val postgres = "((shipped_at IS NULL) OR (shipped_at >= placed_at))"
    val mssql = "shipped_at IS NULL OR shipped_at>=placed_at"
    val mysql = "((`shipped_at` is null) or (`shipped_at` >= `placed_at`))"

    fun schemaWith(expression: String) = SchemaDefinition(
        name = "app", version = "1.0",
        tables = mapOf(
            "orders" to TableDefinition(
                columns = mapOf("id" to ColumnDefinition(NeutralType.Integer)),
                primaryKey = listOf("id"),
                constraints = listOf(
                    ConstraintDefinition(
                        name = "ck_order_ship_after_place", type = ConstraintType.CHECK, expression = expression,
                    ),
                ),
            ),
        ),
    )

    fun equal(left: String, right: String) = ConstraintDiffContract.canonicallyEqual(left, right)

    context("Die gemessenen drei Beine") {

        test("PostgreSQL against SQL Server is no longer a finding") {
            SchemaComparator(canonicalizeRawExpressions = true)
                .compare(schemaWith(postgres), schemaWith(mssql)).tablesChanged.shouldBeEmpty()
        }

        test("both MySQL legs stay findings: keyword case is not folded (open owner question)") {
            SchemaComparator(canonicalizeRawExpressions = true)
                .compare(schemaWith(postgres), schemaWith(mysql)).tablesChanged.shouldNotBeEmpty()
            SchemaComparator(canonicalizeRawExpressions = true)
                .compare(schemaWith(mssql), schemaWith(mysql)).tablesChanged.shouldNotBeEmpty()
        }

        test("the MySQL leg differs only in keyword case once the parentheses fold") {
            // Der Beleg, dass der verbleibende Fund die offene Frage ist und
            // nicht die Klammern: mit grossgeschriebenen Schluesselwoertern
            // faellt er weg.
            equal(postgres, "((`shipped_at` IS NULL) OR (`shipped_at` >= `placed_at`))") shouldBe true
        }

        test("migrate still reports the PostgreSQL / SQL Server pair") {
            SchemaComparator().compare(schemaWith(postgres), schemaWith(mssql)).tablesChanged.shouldNotBeEmpty()
        }
    }

    context("Redundante Operanden-Klammern fallen") {

        test("an AND composition with IS NOT NULL") {
            equal("(a = 1) AND (b IS NOT NULL)", "a = 1 AND b IS NOT NULL") shouldBe true
        }

        test("inside a parenthesised composition") {
            equal("(x > 0 AND ((a IS NULL) OR (a >= b)))", "x > 0 AND (a IS NULL OR a >= b)") shouldBe true
        }

        test("doubled parentheses around an operand") {
            equal("((a = 1)) OR b = 2", "a = 1 OR b = 2") shouldBe true
        }

        test("an operand whose comparison involves a function call or an ANY list") {
            equal(
                "(length(name) > 0) AND (status = ANY (ARRAY['A','B']))",
                "length(name) > 0 AND status = ANY (ARRAY['A','B'])",
            ) shouldBe true
        }
    }

    context("Gliedernde Klammern bleiben") {

        test("around a whole composition") {
            equal("(a = 1 OR b = 2) AND c = 3", "a = 1 OR b = 2 AND c = 3") shouldBe false
        }

        test("around an arithmetic expression") {
            equal("(a + b) * c > 0 OR d = 1", "a + b * c > 0 OR d = 1") shouldBe false
        }

        test("after NOT") {
            equal("NOT (a = 1) OR b = 2", "NOT a = 1 OR b = 2") shouldBe false
        }

        test("around an operand with NOT at its top level") {
            equal("(NOT a = 1) OR b = 2", "NOT a = 1 OR b = 2") shouldBe false
            equal("(a NOT LIKE 'x') OR b = 2", "a NOT LIKE 'x' OR b = 2") shouldBe false
        }

        test("an operand that is no comparison") {
            equal("(a IN (1, 2)) OR b = 2", "a IN (1, 2) OR b = 2") shouldBe false
            equal("(a @> b) OR c = 1", "a @> b OR c = 1") shouldBe false
            equal("(a ~ 'x') OR c = 1", "a ~ 'x' OR c = 1") shouldBe false
        }

        test("inside a function argument list") {
            equal("coalesce((a = 1), false) OR b = 2", "coalesce(a = 1, false) OR b = 2") shouldBe false
        }

        test("next to a comparison or a keyword that is no junction") {
            equal("(a = 1) = true OR b = 2", "a = 1 = true OR b = 2") shouldBe false
            equal("CASE WHEN (a = 1) THEN 1 END = 1", "CASE WHEN a = 1 THEN 1 END = 1") shouldBe false
        }

        test("on a level with BETWEEN, whose AND is no conjunction") {
            equal("x BETWEEN 1 AND (y = 2) OR z = 3", "x BETWEEN 1 AND y = 2 OR z = 3") shouldBe false
            equal("(x BETWEEN 1 AND 5) OR z = 3", "x BETWEEN 1 AND 5 OR z = 3") shouldBe false
        }

        test("around an operand with MySQL's || or &&") {
            equal("(a || b = c) AND d = 1", "a || b = c AND d = 1") shouldBe false
        }

        test("a reordered composition stays different") {
            equal("(a = 1) OR (b = 2)", "b = 2 OR a = 1") shouldBe false
        }

        test("unbalanced parentheses are left alone") {
            equal("(a = 1) OR (b = 2", "a = 1 OR b = 2") shouldBe false
        }
    }
})
