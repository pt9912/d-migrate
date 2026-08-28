package dev.dmigrate.format

import com.fasterxml.jackson.databind.ObjectMapper
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * ADR 0049: INCLUDE-Spalten und die clustered-Steuerung überleben den
 * `build → parse`-Roundtrip. Beide sind semantisch — ginge eines der Felder
 * beim Serialisieren verloren, wäre der Verlust im Vergleich unsichtbar und
 * ein Schema aus der Datei nicht dasselbe wie das gelesene.
 */
class SchemaCoveringIndexRoundtripTest : FunSpec({

    val mapper = ObjectMapper()

    fun roundtrip(schema: SchemaDefinition): SchemaDefinition =
        SchemaNodeParser.parse(SchemaNodeBuilder.build(mapper, schema))

    fun schemaWith(index: IndexDefinition) = SchemaDefinition(
        name = "App", version = "1",
        tables = mapOf("docs" to TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Integer),
                "title" to ColumnDefinition(NeutralType.Text()),
                "body" to ColumnDefinition(NeutralType.Text()),
            ),
            indices = listOf(index),
        )),
    )

    test("include columns survive the roundtrip in order") {
        val parsed = roundtrip(schemaWith(IndexDefinition(
            name = "idx_id",
            columns = listOf(IndexColumn("id")),
            includeColumns = listOf("title", "body"),
        )))
        val index = parsed.tables.getValue("docs").indices.single()
        index.includeColumns shouldContainExactly listOf("title", "body")
        // Sie bleiben ausserhalb der Schluesselspalten — sonst waere aus einem
        // abdeckenden Index ein zusammengesetzter geworden.
        index.columns.map { it.name } shouldContainExactly listOf("id")
    }

    test("the clustered flag survives the roundtrip") {
        val parsed = roundtrip(schemaWith(IndexDefinition(
            name = "idx_id", columns = listOf(IndexColumn("id")), clustered = true,
        )))
        parsed.tables.getValue("docs").indices.single().clustered shouldBe true
    }

    test("an index without either field serialises neither key") {
        val node = SchemaNodeBuilder.build(mapper, schemaWith(IndexDefinition(
            name = "idx_id", columns = listOf(IndexColumn("id")),
        )))
        val indexNode = node.get("tables").get("docs").get("indices").get(0)
        indexNode.has("include_columns") shouldBe false
        indexNode.has("clustered") shouldBe false
    }

    test("a non-string entry in include_columns is rejected, not silently dropped") {
        val node = SchemaNodeBuilder.build(mapper, schemaWith(IndexDefinition(
            name = "idx_id", columns = listOf(IndexColumn("id")), includeColumns = listOf("title"),
        )))
        val indexNode = node.get("tables").get("docs").get("indices").get(0) as com.fasterxml.jackson.databind.node.ObjectNode
        val broken = mapper.createArrayNode()
        broken.add(mapper.createObjectNode().put("name", "title"))
        indexNode.set<com.fasterxml.jackson.databind.JsonNode>("include_columns", broken)

        shouldThrow<IllegalArgumentException> { SchemaNodeParser.parse(node) }
    }
})
