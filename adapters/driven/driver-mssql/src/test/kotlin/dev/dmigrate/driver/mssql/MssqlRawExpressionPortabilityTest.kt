package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Roher Ausdruckstext aus einem **fremden** Dialekt darf nicht ungeprueft in
 * T-SQL landen.
 *
 * Die Vorlage ist ein Konsumentenbefund: ein PostgreSQL-Reverse liefert
 * `(email ~~ '%@%'::text)` fuer einen CHECK und `((quantity)::numeric *
 * unit_price)` fuer eine berechnete Spalte — beides mit Operatoren, die es in
 * T-SQL nicht gibt. Der Reverse **uebersetzt** solchen Text nicht
 * ([ADR 0053](../../../../../../../../../docs/adr/0053-vergleich-rohen-sql-texts.md)):
 * der Text ist die Aussage. Das Rendern gegen ein anderes Ziel muss ihn
 * deshalb benannt verwerfen, statt ihn weiterzureichen.
 */
class MssqlRawExpressionPortabilityTest : FunSpec({

    val pgCheck = "(email ~~ '%@%'::text)"
    val pgComputed = "((quantity)::numeric * unit_price)"

    fun generate(table: TableDefinition) = MssqlDdlGenerator().generate(
        SchemaDefinition(name = "App", version = "1", tables = mapOf("order_items" to table)),
        DdlGenerationOptions(),
    )

    test("a PostgreSQL CHECK expression is refused, not rendered") {
        val table = TableDefinition(
            columns = linkedMapOf("email" to ColumnDefinition(NeutralType.Text())),
            constraints = listOf(
                ConstraintDefinition(
                    name = "customers_email_check",
                    type = ConstraintType.CHECK,
                    expression = pgCheck,
                ),
            ),
        )

        val result = generate(table)
        val sql = result.statements.joinToString("\n") { it.sql }

        withClue(sql) {
            sql.contains("~~") shouldBe false
            sql.contains("::") shouldBe false
        }
        result.notes.any { it.code == "E053" } shouldBe true
    }

    test("a PostgreSQL computed expression is refused the same way") {
        val table = TableDefinition(
            columns = linkedMapOf(
                "quantity" to ColumnDefinition(NeutralType.Integer),
                "unit_price" to ColumnDefinition(NeutralType.Decimal(12, 2)),
                "line_total" to ColumnDefinition(
                    NeutralType.Decimal(14, 2),
                    generation = ColumnGeneration.Computed(pgComputed, stored = true),
                ),
            ),
        )

        val result = generate(table)
        val sql = result.statements.joinToString("\n") { it.sql }

        withClue(sql) {
            sql.contains("~~") shouldBe false
            sql.contains("::") shouldBe false
        }
        result.notes.any { it.code == "E053" } shouldBe true
    }

    test("an expression that T-SQL can parse is rendered unchanged") {
        val table = TableDefinition(
            columns = linkedMapOf(
                "quantity" to ColumnDefinition(NeutralType.Integer),
                "unit_price" to ColumnDefinition(NeutralType.Decimal(12, 2)),
                "line_total" to ColumnDefinition(
                    NeutralType.Decimal(14, 2),
                    generation = ColumnGeneration.Computed("quantity * unit_price", stored = true),
                ),
            ),
            constraints = listOf(
                ConstraintDefinition(name = "ck_qty", type = ConstraintType.CHECK, expression = "quantity > 0"),
            ),
        )

        val result = generate(table)
        val sql = result.statements.joinToString("\n") { it.sql }

        withClue(sql) {
            sql.contains("AS (quantity * unit_price) PERSISTED") shouldBe true
            sql.contains("CHECK (quantity > 0)") shouldBe true
        }
        result.notes.none { it.code == "E053" } shouldBe true
    }
})
