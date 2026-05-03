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
        @Suppress("UNCHECKED_CAST")
        val props = pair.inputSchema["properties"] as Map<String, Map<String, Any>>
        props["schema"]?.get("type") shouldBe "object"
        props["schemaRef"]?.get("type") shouldBe "string"
        props["format"]?.get("enum") shouldBe listOf("json", "yaml")
        props["strictness"]?.get("enum") shouldBe listOf("lenient", "strict")
        // No required keys: presence is checked by the resolver.
        pair.inputSchema.containsKey("required") shouldBe false
    }

    test("AP 6.23: schema_generate output uses generatorFindings + truncated→artifactRef") {
        val output = PhaseBToolSchemas.forTool("schema_generate")!!.outputSchema

        @Suppress("UNCHECKED_CAST")
        val props = output["properties"] as Map<String, Any>

        // Findings carry the generator-specific item (base + hint).
        props["findings"] shouldBe PhaseBToolSchemas.generatorFindingArray()
        props["artifactRef"] shouldBe PhaseBToolSchemas.artifactRefField()
        props["executionMeta"] shouldBe PhaseBToolSchemas.executionMetaField()

        @Suppress("UNCHECKED_CAST")
        val allOf = output["allOf"] as List<Map<String, Any>>
        allOf shouldBe listOf(PhaseBToolSchemas.truncatedRequiresField("artifactRef"))
    }

    test("AP 6.23: generatorFindingItem extends findingItem with optional hint") {
        val item = PhaseBToolSchemas.generatorFindingItem()
        item["additionalProperties"] shouldBe false
        @Suppress("UNCHECKED_CAST")
        val props = item["properties"] as Map<String, Any>
        props.keys shouldBe setOf("severity", "code", "path", "message", "hint")
        @Suppress("UNCHECKED_CAST")
        val required = item["required"] as List<String>
        // hint is optional — same as the base findingItem.
        required shouldBe listOf("severity", "code", "path", "message")
    }

    test("AP 6.23: schema_compare output uses compareDetails findings + truncated→diffArtifactRef") {
        val output = PhaseBToolSchemas.forTool("schema_compare")!!.outputSchema

        @Suppress("UNCHECKED_CAST")
        val props = output["properties"] as Map<String, Any>

        // findings carry the compare-specific details (before/after).
        @Suppress("UNCHECKED_CAST")
        val findings = props["findings"] as Map<String, Any>
        findings shouldBe PhaseBToolSchemas.findingArray(
            detailsSchema = PhaseBToolSchemas.compareDetailsSchema(),
        )

        props["diffArtifactRef"] shouldBe PhaseBToolSchemas.artifactRefField()
        props["executionMeta"] shouldBe PhaseBToolSchemas.executionMetaField()

        @Suppress("UNCHECKED_CAST")
        val allOf = output["allOf"] as List<Map<String, Any>>
        allOf shouldBe listOf(PhaseBToolSchemas.truncatedRequiresField("diffArtifactRef"))
    }

    test("AP 6.23: compareDetailsSchema closes details and rejects empty / blank slots") {
        val schema = PhaseBToolSchemas.compareDetailsSchema()
        schema["type"] shouldBe "object"
        schema["additionalProperties"] shouldBe false
        // minProperties=1 means `details: {}` is structurally invalid.
        schema["minProperties"] shouldBe 1
        @Suppress("UNCHECKED_CAST")
        val props = schema["properties"] as Map<String, Any>
        props.keys shouldBe setOf("before", "after")
        // Both fields are scrubbed strings with a non-blank pattern.
        @Suppress("UNCHECKED_CAST")
        val before = props["before"] as Map<String, Any>
        before["type"] shouldBe "string"
        before["pattern"] shouldBe "\\S"
    }

    test("AP 6.23: schema_validate output is strict and pins truncated→artifactRef") {
        val output = PhaseBToolSchemas.forTool("schema_validate")!!.outputSchema

        @Suppress("UNCHECKED_CAST")
        val props = output["properties"] as Map<String, Any>

        // findings: array of findingItem
        @Suppress("UNCHECKED_CAST")
        val findings = props["findings"] as Map<String, Any>
        findings["type"] shouldBe "array"
        findings["items"] shouldBe PhaseBToolSchemas.findingItem()

        // artifactRef: URI-pattern string
        props["artifactRef"] shouldBe PhaseBToolSchemas.artifactRefField()

        // executionMeta: closed (additionalProperties=false), required requestId
        props["executionMeta"] shouldBe PhaseBToolSchemas.executionMetaField()

        // allOf carries the truncated → artifactRef coupling
        @Suppress("UNCHECKED_CAST")
        val allOf = output["allOf"] as List<Map<String, Any>>
        allOf shouldBe listOf(PhaseBToolSchemas.truncatedRequiresField("artifactRef"))
    }

    test("listing tools share a stable input shape") {
        for (name in listOf("schema_list", "profile_list", "diff_list", "job_list", "artifact_list")) {
            val pair = PhaseBToolSchemas.forTool(name)!!
            @Suppress("UNCHECKED_CAST")
            val props = pair.inputSchema["properties"] as Map<String, Map<String, Any>>
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
