package dev.dmigrate.core.model

import dev.dmigrate.core.validation.SchemaValidator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Was sich an einer berechneten Spalte ohne SQL-Parser entscheiden laesst.
 *
 * Bewusst wenig: dass ueberhaupt ein Ausdruck dasteht, dass er sich nicht auf
 * die Spalte bezieht, die er berechnet, dass die genannten Spalten existieren,
 * und dass die Spalte nicht zugleich einen Default fuehrt. Alles Weitere
 * entscheidet der Server beim Anwenden, mit seiner eigenen, genaueren Meldung.
 */
class ComputedColumnValidationTest : FunSpec({

    fun schemaWith(column: ColumnDefinition) = SchemaDefinition(
        name = "App", version = "1",
        tables = mapOf("order_line" to TableDefinition(
            columns = mapOf(
                "quantity" to ColumnDefinition(NeutralType.Integer),
                "unit_price" to ColumnDefinition(NeutralType.Decimal(12, 2)),
                "line_total" to column,
            ),
        )),
    )

    fun codes(column: ColumnDefinition) = SchemaValidator().validate(schemaWith(column)).errors.map { it.code }

    test("a well-formed computed column validates") {
        codes(
            ColumnDefinition(
                NeutralType.Decimal(14, 2),
                generation = ColumnGeneration.Computed("quantity * unit_price"),
            ),
        ).shouldBeEmpty()
    }

    test("a blank expression is refused") {
        codes(
            ColumnDefinition(NeutralType.Decimal(14, 2), generation = ColumnGeneration.Computed("   ")),
        ) shouldBe listOf("E134")
    }

    test("a computed column may not also carry a default") {
        codes(
            ColumnDefinition(
                NeutralType.Decimal(14, 2),
                default = DefaultValue.NumberLiteral(0),
                generation = ColumnGeneration.Computed("quantity * unit_price"),
            ),
        ) shouldBe listOf("E135")
    }

    test("an expression over a column that does not exist is refused") {
        codes(
            ColumnDefinition(
                NeutralType.Decimal(14, 2),
                generation = ColumnGeneration.Computed("quantity * unit_prise"),
            ),
        ) shouldBe listOf("E136")
    }

    test("an expression that computes a column from itself is refused") {
        codes(
            ColumnDefinition(
                NeutralType.Decimal(14, 2),
                generation = ColumnGeneration.Computed("line_total * 2"),
            ),
        ) shouldBe listOf("E136")
    }

    test("a function call is not mistaken for a column") {
        // Dieselbe zurueckhaltende Erkennung wie beim CHECK-Ausdruck: ein
        // Bezeichner vor `(` ist ein Funktionsname, kein Spaltenbezug.
        codes(
            ColumnDefinition(
                NeutralType.Decimal(14, 2),
                generation = ColumnGeneration.Computed("round(quantity * unit_price, 2)"),
            ),
        ).shouldBeEmpty()
    }
})
