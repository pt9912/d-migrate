package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.RawTextAuthorship
import dev.dmigrate.core.diff.RawTextServerForm
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Der Berechnungsausdruck einer Spalte wird nur verglichen, wo eine Quelle ihn
 * entscheiden kann. Wo nicht, wird nichts geplant — und **gesagt**, dass die
 * Frage offenblieb.
 *
 * Ohne diese Meldung aenderte jemand den Ausdruck, bekaeme Exit 0, und die
 * Datenbank rechnete weiter nach der alten Formel.
 */
class ComputedExpressionDecidabilityTest : FunSpec({

    fun schemaWith(expression: String?) = SchemaDefinition(
        name = "App", version = "1",
        tables = mapOf("order_line" to TableDefinition(
            columns = mapOf(
                "quantity" to ColumnDefinition(NeutralType.Integer),
                "line_total" to ColumnDefinition(
                    NeutralType.Decimal(14, 2),
                    generation = expression?.let { ColumnGeneration.Computed(it, stored = true) },
                ),
            ),
        )),
    )

    /** Die Autorenform, die Katalogform — so, wie PostgreSQL sie zurueckgibt. */
    val authored = schemaWith("quantity * unit_price")
    val catalog = schemaWith("((quantity)::numeric * unit_price)")

    fun diagnose(
        authorship: RawTextAuthorship? = null,
        serverForm: RawTextServerForm? = null,
    ) = ComputedExpressionDecidability.diagnostics(catalog, authored, authorship, serverForm)

    test("without any source the difference is reported as undecided") {
        val diagnostics = diagnose()

        diagnostics.single().code shouldBe ComputedExpressionDecidability.UNDECIDED
        diagnostics.single().message shouldContain "order_line.line_total"
        diagnostics.single().message shouldContain "would NOT have been migrated"
    }

    test("with provenance the question is answerable, so nothing is reported") {
        val authorship = RawTextAuthorship { _, _, _, _, _ -> false }

        diagnose(authorship = authorship).shouldBeEmpty()
    }

    test("provenance that reports a real change says nothing — the plan carries it instead") {
        val authorship = RawTextAuthorship { _, _, _, _, _ -> true }

        // Frueher stand hier ein Blocker (E137, zurueckgezogen). Eine belegte
        // Aenderung wird heute zu einer `AlterColumnGeneration`; ob der
        // Zielserver sie ausfuehren kann, entscheidet der Renderer, der Dialekt
        // und Version kennt. Hier zu blocken hiesse, dieselbe Frage ein zweites
        // Mal und schlechter informiert zu beantworten.
        diagnose(authorship = authorship).shouldBeEmpty()
    }

    test("the sandbox answers where provenance is silent") {
        val serverForm = RawTextServerForm { _, _, _, _ -> "((quantity)::numeric * unit_price)" }

        diagnose(serverForm = serverForm).shouldBeEmpty()
    }

    test("a provenance that has no entry for this field leaves the question open") {
        val authorship = RawTextAuthorship { _, _, _, _, _ -> null }

        diagnose(authorship = authorship).single().code shouldBe ComputedExpressionDecidability.UNDECIDED
    }

    test("expressions that match literally need no source") {
        ComputedExpressionDecidability.diagnostics(authored, authored, null, null).shouldBeEmpty()
    }

    test("a column that is computed on only one side is not this question") {
        // Die Berechnung kommt hinzu oder faellt weg — das ist eine Aenderung
        // an der Spalte, keine Textfrage, und der Vergleich sieht sie ohnehin.
        ComputedExpressionDecidability.diagnostics(schemaWith(null), authored, null, null).shouldBeEmpty()
        ComputedExpressionDecidability.diagnostics(catalog, schemaWith(null), null, null).shouldBeEmpty()
    }

    test("a table that only one side has is not this question either") {
        val other = SchemaDefinition(name = "App", version = "1")

        ComputedExpressionDecidability.diagnostics(other, authored, null, null).shouldBeEmpty()
    }
})
