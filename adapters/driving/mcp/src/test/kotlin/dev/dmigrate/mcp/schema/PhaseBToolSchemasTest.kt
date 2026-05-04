package dev.dmigrate.mcp.schema

import dev.dmigrate.mcp.server.McpServerConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * §12.16 verbindlich: MCP-protocol method names that must NOT be
 * registered as tools. Mirror of `PhaseBRegistries.PROTOCOL_METHODS`;
 * the test asserts that every one of these is absent from
 * `PhaseBToolSchemas.toolNames()`.
 */
private val PROTOCOL_METHODS: Set<String> = setOf(
    "tools/list",
    "tools/call",
    "resources/list",
    "resources/templates/list",
    "resources/read",
    "connections/list",
)

private fun expectedToolNames(): Set<String> =
    McpServerConfig.DEFAULT_SCOPE_MAPPING.keys.minus(PROTOCOL_METHODS)

class PhaseBToolSchemasTest : FunSpec({

    test("registered tools match the default scope mapping minus protocol methods exactly") {
        // §12.18 "Tool-Universum (verbindlich)": equality, not superset —
        // an accidentally-registered surplus tool would be a contract
        // breach and must fail this test.
        PhaseBToolSchemas.toolNames().toSet() shouldBe expectedToolNames()
    }

    test("PhaseBToolSchemas does not register protocol-method names") {
        val registered = PhaseBToolSchemas.toolNames()
        PROTOCOL_METHODS.forAll { method ->
            registered.contains(method) shouldBe false
        }
    }

    test("every schema sets \$schema to the 2020-12 dialect URI exactly") {
        for (name in PhaseBToolSchemas.toolNames()) {
            val pair = PhaseBToolSchemas.forTool(name)!!
            pair.inputSchema[JsonSchemaDialect.SCHEMA_KEYWORD] shouldBe JsonSchemaDialect.SCHEMA_URI
            pair.outputSchema[JsonSchemaDialect.SCHEMA_KEYWORD] shouldBe JsonSchemaDialect.SCHEMA_URI
        }
    }

    test("every schema's root type is 'object'") {
        for (name in PhaseBToolSchemas.toolNames()) {
            val pair = PhaseBToolSchemas.forTool(name)!!
            pair.inputSchema["type"] shouldBe "object"
            pair.outputSchema["type"] shouldBe "object"
        }
    }

    test("no schema contains a Draft-07-only forbidden keyword at any nesting level") {
        for (name in PhaseBToolSchemas.toolNames()) {
            val pair = PhaseBToolSchemas.forTool(name)!!
            assertNoForbiddenKeyword(pair.inputSchema, "$name.input")
            assertNoForbiddenKeyword(pair.outputSchema, "$name.output")
        }
    }

    test("no schema admits a secret-shaped property name") {
        for (name in PhaseBToolSchemas.toolNames()) {
            val pair = PhaseBToolSchemas.forTool(name)!!
            SchemaSecretGuard.findSecretLeaks(pair.inputSchema) shouldBe emptyList()
            SchemaSecretGuard.findSecretLeaks(pair.outputSchema) shouldBe emptyList()
        }
    }

    test("forTool(unknown) returns null") {
        PhaseBToolSchemas.forTool("definitely_not_a_tool") shouldBe null
    }

    test("toolNames is alphabetically sorted (deterministic for golden tests)") {
        val names = PhaseBToolSchemas.toolNames()
        names shouldBe names.sorted()
    }

    test("capabilities_list input is the empty-object marker (no arguments)") {
        val pair = PhaseBToolSchemas.forTool("capabilities_list")!!
        pair.inputSchema["properties"] shouldBe null
        pair.inputSchema["required"] shouldBe null
        pair.inputSchema["additionalProperties"] shouldBe false
    }

    test("schemas are stable across instances (no per-call mutation)") {
        // The schemas table is a single immutable map; calling forTool
        // twice MUST return the same content.
        val first = PhaseBToolSchemas.forTool("schema_validate")!!
        val second = PhaseBToolSchemas.forTool("schema_validate")!!
        first.inputSchema shouldBe second.inputSchema
        first.outputSchema shouldBe second.outputSchema
    }

    test("schema_validate accepts inline schema or schemaRef with optional format/strictness (AP 6.4)") {
        // The "exactly one of schema/schemaRef" rule is enforced at
        // runtime by SchemaSourceResolver — JSON Schema's oneOf would
        // duplicate that contract on the wire. Pin only the field set
        // and the optional enum constraints.
        val pair = PhaseBToolSchemas.forTool("schema_validate")!!
        val props = mapValue(pair.inputSchema["properties"])
        mapValue(props["schema"])["type"] shouldBe "object"
        mapValue(props["schemaRef"])["type"] shouldBe "string"
        mapValue(props["format"])["enum"] shouldBe listOf("json", "yaml")
        mapValue(props["strictness"])["enum"] shouldBe listOf("lenient", "strict")
        // No required keys: presence is checked by the resolver.
        pair.inputSchema.containsKey("required") shouldBe false
    }

    test("AP 6.23: schema_generate output uses generatorFindings + truncated→artifactRef") {
        val output = PhaseBToolSchemas.forTool("schema_generate")!!.outputSchema

        val props = mapValue(output["properties"])

        // Findings carry the generator-specific item (base + hint).
        props["findings"] shouldBe PhaseBToolSchemas.generatorFindingArray()
        props["artifactRef"] shouldBe artifactRefField()
        props["executionMeta"] shouldBe PhaseBToolSchemas.executionMetaField()

        output["allOf"] shouldBe listOf(PhaseBToolSchemas.truncatedRequiresField("artifactRef"))
    }

    test("AP 6.23: generatorFindingItem extends findingItem with optional hint") {
        val item = PhaseBToolSchemas.generatorFindingItem()
        item["additionalProperties"] shouldBe false
        val props = mapValue(item["properties"])
        props.keys shouldBe setOf("severity", "code", "path", "message", "hint")
        val required = stringListValue(item["required"])
        // hint is optional — same as the base findingItem.
        required shouldBe listOf("severity", "code", "path", "message")
    }

    test("AP 6.23: schema_compare output uses compareDetails findings + truncated→diffArtifactRef") {
        val output = PhaseBToolSchemas.forTool("schema_compare")!!.outputSchema

        val props = mapValue(output["properties"])

        // findings carry the compare-specific details (before/after).
        val findings = mapValue(props["findings"])
        findings shouldBe PhaseBToolSchemas.findingArray(
            detailsSchema = PhaseBToolSchemas.compareDetailsSchema(),
        )

        props["diffArtifactRef"] shouldBe artifactRefField()
        props["executionMeta"] shouldBe PhaseBToolSchemas.executionMetaField()

        output["allOf"] shouldBe listOf(PhaseBToolSchemas.truncatedRequiresField("diffArtifactRef"))
    }

    test("AP 6.23: compareDetailsSchema closes details and rejects empty / blank slots") {
        val schema = PhaseBToolSchemas.compareDetailsSchema()
        schema["type"] shouldBe "object"
        schema["additionalProperties"] shouldBe false
        // minProperties=1 means `details: {}` is structurally invalid.
        schema["minProperties"] shouldBe 1
        val props = mapValue(schema["properties"])
        props.keys shouldBe setOf("before", "after")
        // Both fields are scrubbed strings with a non-blank pattern.
        val before = mapValue(props["before"])
        before["type"] shouldBe "string"
        before["pattern"] shouldBe "\\S"
    }

    test("AP 6.23: schema_validate output is strict and pins truncated→artifactRef") {
        val output = PhaseBToolSchemas.forTool("schema_validate")!!.outputSchema

        val props = mapValue(output["properties"])

        // findings: array of findingItem
        val findings = mapValue(props["findings"])
        findings["type"] shouldBe "array"
        findings["items"] shouldBe PhaseBToolSchemas.findingItem()

        // artifactRef: URI-pattern string
        props["artifactRef"] shouldBe artifactRefField()

        // executionMeta: closed (additionalProperties=false), required requestId
        props["executionMeta"] shouldBe PhaseBToolSchemas.executionMetaField()

        // allOf carries the truncated → artifactRef coupling
        output["allOf"] shouldBe listOf(PhaseBToolSchemas.truncatedRequiresField("artifactRef"))
    }

    test("listing tools share a stable input shape") {
        for (name in listOf("schema_list", "profile_list", "diff_list", "job_list", "artifact_list")) {
            val pair = PhaseBToolSchemas.forTool(name)!!
            val props = mapValue(pair.inputSchema["properties"])
            props["pageSize"] shouldNotBe null
            props["cursor"] shouldNotBe null
        }
    }
})

private fun assertNoForbiddenKeyword(schema: Any?, location: String) {
    when (schema) {
        is Map<*, *> -> {
            for (key in schema.keys) {
                if (key is String && key in JsonSchemaDialect.DRAFT_07_FORBIDDEN_KEYWORDS) {
                    error("Schema at $location contains forbidden Draft-07 keyword '$key'")
                }
            }
            schema.values.forEach { assertNoForbiddenKeyword(it, location) }
        }
        is List<*> -> schema.forEach { assertNoForbiddenKeyword(it, location) }
        else -> Unit
    }
}

private fun mapValue(value: Any?): Map<*, *> =
    value as? Map<*, *> ?: error("expected map, got ${value?.let { it::class.simpleName } ?: "null"}")

private fun stringListValue(value: Any?): List<String> {
    val list = value as? List<*> ?: error("expected list, got ${value?.let { it::class.simpleName } ?: "null"}")
    return list.map { it as? String ?: error("expected string list item, got ${it?.let { v -> v::class.simpleName } ?: "null"}") }
}
