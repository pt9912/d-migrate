package dev.dmigrate.format

import com.fasterxml.jackson.databind.ObjectMapper
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * ADR 0021: die physische Spalten-Ordinalreihenfolge überlebt den
 * `build → parse`-Roundtrip. Hybrid: Spalten werden in Ordinalreihenfolge
 * serialisiert UND tragen ein explizites `ordinal`. Ohne diesen Test könnte
 * der alte alphabetische Serializer zurückkehren und Reverse-Fidelity brechen.
 */
class SchemaColumnOrdinalRoundtripTest : FunSpec({

    val mapper = ObjectMapper()

    fun roundtrip(schema: SchemaDefinition): SchemaDefinition =
        SchemaNodeParser.parse(SchemaNodeBuilder.build(mapper, schema))

    test("ordinal drives serialization order and survives the roundtrip") {
        // Einfügereihenfolge bewusst != Ordinal != alphabetisch.
        val table = TableDefinition(
            columns = linkedMapOf(
                "alpha" to ColumnDefinition(NeutralType.Text(), ordinal = 3),
                "zeta" to ColumnDefinition(NeutralType.Integer, ordinal = 1),
                "mid" to ColumnDefinition(NeutralType.BooleanType, ordinal = 2),
            ),
            primaryKey = listOf("zeta"),
        )
        val schema = SchemaDefinition(name = "App", version = "1", tables = mapOf("t" to table))

        val parsed = roundtrip(schema).tables.getValue("t")

        // Physische Reihenfolge nach ordinal, nicht alphabetisch, nicht Einfügereihenfolge.
        parsed.columns.keys.toList() shouldContainExactly listOf("zeta", "mid", "alpha")
        parsed.columns.getValue("zeta").ordinal shouldBe 1
        parsed.columns.getValue("mid").ordinal shouldBe 2
        parsed.columns.getValue("alpha").ordinal shouldBe 3
    }

    test("columns without ordinal keep insertion order and emit no ordinal") {
        val table = TableDefinition(
            columns = linkedMapOf(
                "b" to ColumnDefinition(NeutralType.Text()),
                "a" to ColumnDefinition(NeutralType.Integer),
            ),
            primaryKey = listOf("a"),
        )
        val schema = SchemaDefinition(name = "App", version = "1", tables = mapOf("t" to table))

        val parsed = roundtrip(schema).tables.getValue("t")

        parsed.columns.keys.toList() shouldContainExactly listOf("b", "a")
        parsed.columns.getValue("b").ordinal shouldBe null
        parsed.columns.getValue("a").ordinal shouldBe null
    }
})
