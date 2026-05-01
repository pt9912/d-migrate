package dev.dmigrate.mcp.schema

import dev.dmigrate.mcp.server.McpServerConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

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

    test("every 0.9.6 tool from the default scope mapping has input + output schemas") {
        val registered = PhaseBToolSchemas.toolNames().toSet()
        registered shouldContainAll expectedToolNames()
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

    test("schema_validate has typed schemaUri as a required string") {
        val pair = PhaseBToolSchemas.forTool("schema_validate")!!
        @Suppress("UNCHECKED_CAST")
        val props = pair.inputSchema["properties"] as Map<String, Map<String, Any>>
        props["schemaUri"]?.get("type") shouldBe "string"
        @Suppress("UNCHECKED_CAST")
        val required = pair.inputSchema["required"] as List<String>
        required shouldContainAll listOf("schemaUri")
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
