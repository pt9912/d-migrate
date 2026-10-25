package dev.dmigrate.format

import com.fasterxml.jackson.databind.ObjectMapper
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.SchemaDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** `default`, `not_null` und `collation` einer Domain in Datei-Parser und -Writer (Issue #59). */
class SchemaNodeDomainTraitsTest : FunSpec({

    val mapper = ObjectMapper()

    fun parse(typeJson: String) = SchemaNodeParser.parse(
        mapper.readTree(
            """
            { "schema_format": "1.0", "name": "d", "version": "1",
              "custom_types": { "code": $typeJson }, "tables": {} }
            """.trimIndent(),
        ),
    ).customTypes.getValue("code")

    test("der Parser liest default, not_null und collation einer Domain") {
        val type = parse(
            """{ "kind": "domain", "base_type": "text", "default": "x", "not_null": true, "collation": "C" }""",
        )
        type.default shouldBe DefaultValue.StringLiteral("x")
        type.notNull shouldBe true
        type.collation shouldBe "C"
    }

    test("ohne die Felder bleibt der Typ unveraendert (kein Default, nicht NOT NULL, keine Kollation)") {
        val type = parse("""{ "kind": "domain", "base_type": "integer", "check": "VALUE > 0" }""")
        type.default shouldBe null
        type.notNull shouldBe false
        type.collation shouldBe null
    }

    test("der Default hat die Formen des Spalten-Defaults") {
        parse("""{ "kind": "domain", "base_type": "integer", "default": 5 }""").default shouldBe
            DefaultValue.NumberLiteral(5)
        parse("""{ "kind": "domain", "base_type": "boolean", "default": false }""").default shouldBe
            DefaultValue.BooleanLiteral(false)
        parse("""{ "kind": "domain", "base_type": "text", "default": { "sequence_nextval": "s" } }""").default shouldBe
            DefaultValue.SequenceNextVal("s")
    }

    test("der Writer schreibt die Felder nur, wenn sie gesetzt sind, und der Parser liest sie zurueck") {
        val full = CustomTypeDefinition(
            kind = CustomTypeKind.DOMAIN, baseType = "text", check = "length(VALUE) > 0",
            default = DefaultValue.StringLiteral("x"), notNull = true, collation = "C",
        )
        val plain = CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = "text")
        val schema = SchemaDefinition(name = "d", version = "1", customTypes = mapOf("full" to full, "plain" to plain))

        val written = SchemaNodeBuilder.build(mapper, schema)["custom_types"]
        written["full"]["default"].asText() shouldBe "x"
        written["full"]["not_null"].asBoolean() shouldBe true
        written["full"]["collation"].asText() shouldBe "C"
        written["plain"].has("default") shouldBe false
        written["plain"].has("not_null") shouldBe false
        written["plain"].has("collation") shouldBe false

        val back = SchemaNodeParser.parse(SchemaNodeBuilder.build(mapper, schema))
        back.customTypes.getValue("full").default shouldBe full.default
        back.customTypes.getValue("full").notNull shouldBe true
        back.customTypes.getValue("full").collation shouldBe "C"
    }
})
