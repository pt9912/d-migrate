package dev.dmigrate.format

import com.fasterxml.jackson.databind.ObjectMapper
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Eine berechnete Spalte ueberlebt den `build → parse`-Roundtrip — Ausdruck
 * und Speicherform.
 *
 * Ginge eines der beiden verloren, waere der Verlust im Vergleich unsichtbar:
 * ein Schema aus der Datei waere nicht dasselbe wie das gelesene, und die
 * Zusicherung „diese Spalte ist immer <ausdruck>" faellt still weg.
 */
class SchemaComputedColumnRoundtripTest : FunSpec({

    val mapper = ObjectMapper()

    fun schemaWith(generation: ColumnGeneration) = SchemaDefinition(
        name = "App", version = "1",
        tables = mapOf("order_line" to TableDefinition(
            columns = mapOf(
                "quantity" to ColumnDefinition(NeutralType.Integer),
                "unit_price" to ColumnDefinition(NeutralType.Decimal(12, 2)),
                "line_total" to ColumnDefinition(NeutralType.Decimal(14, 2), generation = generation),
            ),
        )),
    )

    fun roundtrip(schema: SchemaDefinition): SchemaDefinition =
        SchemaNodeParser.parse(SchemaNodeBuilder.build(mapper, schema))

    fun generationOf(schema: SchemaDefinition) =
        schema.tables.getValue("order_line").columns.getValue("line_total").generation

    test("a stored computed column survives with its expression") {
        val generation = ColumnGeneration.Computed("quantity * unit_price", stored = true)

        generationOf(roundtrip(schemaWith(generation))) shouldBe generation
    }

    test("a virtual computed column survives too — the default is not a stored one") {
        val generation = ColumnGeneration.Computed("quantity * unit_price")

        generationOf(roundtrip(schemaWith(generation))) shouldBe generation
        (generationOf(roundtrip(schemaWith(generation))) as ColumnGeneration.Computed).stored shouldBe false
    }

    test("the written node names the type, so an identity is never mistaken for a computation") {
        val node = SchemaNodeBuilder.build(mapper, schemaWith(ColumnGeneration.Computed("quantity * unit_price")))
        val generation = node["tables"]["order_line"]["columns"]["line_total"]["generation"]

        generation["type"].asText() shouldBe "computed"
        generation["expression"].asText() shouldBe "quantity * unit_price"
    }

    test("a computed generation without an expression is refused, not silently emptied") {
        val node = SchemaNodeBuilder.build(mapper, schemaWith(ColumnGeneration.Computed("x")))
        (node["tables"]["order_line"]["columns"]["line_total"]["generation"] as com.fasterxml.jackson.databind.node.ObjectNode)
            .remove("expression")

        shouldThrow<IllegalArgumentException> { SchemaNodeParser.parse(node) }
    }
})
