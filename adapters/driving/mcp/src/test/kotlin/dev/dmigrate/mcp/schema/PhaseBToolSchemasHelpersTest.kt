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
        val field = artifactRefField()
        field shouldContainExactly mapOf(
            "type" to "string",
            "pattern" to ARTIFACT_REF_PATTERN,
        )
        // Pattern MUST match a tenant-scoped artefact URI.
        val pattern = Regex(ARTIFACT_REF_PATTERN)
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
        val properties = mapValue(field["properties"])
        properties.keys shouldBe setOf("requestId")
    }

    test("findingItem without details slot has the four required keys + closed shape") {
        val item = PhaseBToolSchemas.findingItem()
        item["type"] shouldBe "object"
        item["additionalProperties"] shouldBe false
        val required = stringListValue(item["required"])
        required shouldContainAll listOf("severity", "code", "path", "message")
        val properties = mapValue(item["properties"])
        properties.keys shouldBe setOf("severity", "code", "path", "message")
    }

    test("findingItem severity enum uses the wire constants from SchemaFindingSeverity") {
        val severity = mapValue(mapValue(PhaseBToolSchemas.findingItem()["properties"])["severity"])
        val values = stringListValue(severity["enum"])
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
        val properties = mapValue(item["properties"])
        properties["details"] shouldBe customDetails
    }

    test("findingArray wraps the findingItem under array.items") {
        val arr = PhaseBToolSchemas.findingArray()
        arr["type"] shouldBe "array"
        arr["items"] shouldBe PhaseBToolSchemas.findingItem()
    }
})

private fun mapValue(value: Any?): Map<*, *> =
    value as? Map<*, *> ?: error("expected map, got ${value?.let { it::class.simpleName } ?: "null"}")

private fun stringListValue(value: Any?): List<String> {
    val list = value as? List<*> ?: error("expected list, got ${value?.let { it::class.simpleName } ?: "null"}")
    return list.map { it as? String ?: error("expected string list item, got ${it?.let { v -> v::class.simpleName } ?: "null"}") }
}
