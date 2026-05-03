package dev.dmigrate.mcp.schema

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * AP 6.23 D1: pin the shared output schema building blocks before
 * D3-D6 wire them into the tool schemas. The helpers must produce
 * closed shapes (`additionalProperties=false`) so a later tool-schema
 * change cannot regress strictness without an explicit Goldenfile
 * diff.
 */
class PhaseBToolSchemasHelpersTest : FunSpec({

    test("artifactRefField is a string field constrained by the resource-URI pattern") {
        val field = PhaseBToolSchemas.artifactRefField()
        field shouldContainExactly mapOf(
            "type" to "string",
            "pattern" to PhaseBToolSchemas.ARTIFACT_REF_PATTERN,
        )
        // Pattern MUST match a tenant-scoped artefact URI.
        val pattern = Regex(PhaseBToolSchemas.ARTIFACT_REF_PATTERN)
        pattern.matches("dmigrate://tenants/acme/artifacts/art-1") shouldBe true
        pattern.matches("dmigrate://tenants/acme/artifacts/") shouldBe false
        pattern.matches("dmigrate://tenants//artifacts/art-1") shouldBe false
        pattern.matches("https://acme/artifacts/art-1") shouldBe false
    }

    test("executionMetaField requires requestId and forbids additional properties") {
        val field = PhaseBToolSchemas.executionMetaField()
        field["type"] shouldBe "object"
        field["additionalProperties"] shouldBe false
        field["required"] shouldBe listOf("requestId")
        @Suppress("UNCHECKED_CAST")
        val properties = field["properties"] as Map<String, Any>
        properties.keys shouldBe setOf("requestId")
    }

    test("findingItem without details slot has the four required keys + closed shape") {
        val item = PhaseBToolSchemas.findingItem()
        item["type"] shouldBe "object"
        item["additionalProperties"] shouldBe false
        @Suppress("UNCHECKED_CAST")
        val required = item["required"] as List<String>
        required shouldContainAll listOf("severity", "code", "path", "message")
        @Suppress("UNCHECKED_CAST")
        val properties = item["properties"] as Map<String, Any>
        properties.keys shouldBe setOf("severity", "code", "path", "message")
    }

    test("findingItem severity enum uses the wire constants from SchemaFindingSeverity") {
        @Suppress("UNCHECKED_CAST")
        val severity = (PhaseBToolSchemas.findingItem()["properties"] as Map<String, Any>)["severity"]
            as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val values = severity["enum"] as List<String>
        values shouldBe listOf(
            SchemaFindingSeverity.ERROR,
            SchemaFindingSeverity.WARNING,
            SchemaFindingSeverity.INFO,
        )
    }

    test("findingItem with details slot adds the supplied schema verbatim") {
        val customDetails = mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "properties" to mapOf("foo" to mapOf("type" to "string")),
        )
        val item = PhaseBToolSchemas.findingItem(detailsSchema = customDetails)
        @Suppress("UNCHECKED_CAST")
        val properties = item["properties"] as Map<String, Any>
        properties["details"] shouldBe customDetails
    }

    test("findingArray wraps the findingItem under array.items") {
        val arr = PhaseBToolSchemas.findingArray()
        arr["type"] shouldBe "array"
        arr["items"] shouldBe PhaseBToolSchemas.findingItem()
    }
})
