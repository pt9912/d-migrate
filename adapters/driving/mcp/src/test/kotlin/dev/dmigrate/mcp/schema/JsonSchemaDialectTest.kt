package dev.dmigrate.mcp.schema

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class JsonSchemaDialectTest : FunSpec({

    test("SCHEMA_URI matches the JSON-Schema 2020-12 spec URL") {
        JsonSchemaDialect.SCHEMA_URI shouldBe "https://json-schema.org/draft/2020-12/schema"
    }

    test("SCHEMA_KEYWORD is the JSON-Schema dialect keyword") {
        JsonSchemaDialect.SCHEMA_KEYWORD shouldBe "\$schema"
    }

    test("DRAFT_07_FORBIDDEN_KEYWORDS includes the contract-relevant Draft-07 names") {
        JsonSchemaDialect.DRAFT_07_FORBIDDEN_KEYWORDS shouldContain "definitions"
        JsonSchemaDialect.DRAFT_07_FORBIDDEN_KEYWORDS shouldContain "id"
        JsonSchemaDialect.DRAFT_07_FORBIDDEN_KEYWORDS shouldContain "dependencies"
    }
})
