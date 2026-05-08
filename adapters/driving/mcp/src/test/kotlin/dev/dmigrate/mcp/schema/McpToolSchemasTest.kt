package dev.dmigrate.mcp.schema

import dev.dmigrate.mcp.server.McpServerConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * §12.16 verbindlich: MCP-protocol method names that must NOT be
 * registered as tools. Mirror of `McpContractRegistries.PROTOCOL_METHODS`;
 * the test asserts that every one of these is absent from
 * `McpToolSchemas.toolNames()`.
 */
private val PROTOCOL_METHODS: Set<String> = setOf(
    "tools/list",
    "tools/call",
    "resources/list",
    "resources/templates/list",
    "resources/read",
    "connections/list",
    // LF-017 / LF-024 / LN-030 / LN-031: MCP-Prompt-Methoden sind ebenfalls
    // Protokoll-Slots (im DEFAULT_SCOPE_MAPPING gelistet), nicht
    // Tools.
    "prompts/list",
    "prompts/get",
)

private fun expectedToolNames(): Set<String> =
    McpServerConfig.DEFAULT_SCOPE_MAPPING.keys.minus(PROTOCOL_METHODS)

class McpToolSchemasTest : FunSpec({

    test("registered tools match the default scope mapping minus protocol methods exactly") {
        // §12.18 "Tool-Universum (verbindlich)": equality, not superset —
        // an accidentally-registered surplus tool would be a contract
        // breach and must fail this test.
        McpToolSchemas.toolNames().toSet() shouldBe expectedToolNames()
    }

    test("McpToolSchemas does not register protocol-method names") {
        val registered = McpToolSchemas.toolNames()
        PROTOCOL_METHODS.forAll { method ->
            registered.contains(method) shouldBe false
        }
    }

    test("every schema sets \$schema to the 2020-12 dialect URI exactly") {
        for (name in McpToolSchemas.toolNames()) {
            val pair = McpToolSchemas.forTool(name)!!
            pair.inputSchema[JsonSchemaDialect.SCHEMA_KEYWORD] shouldBe JsonSchemaDialect.SCHEMA_URI
            pair.outputSchema[JsonSchemaDialect.SCHEMA_KEYWORD] shouldBe JsonSchemaDialect.SCHEMA_URI
        }
    }

    test("every schema's root type is 'object'") {
        for (name in McpToolSchemas.toolNames()) {
            val pair = McpToolSchemas.forTool(name)!!
            pair.inputSchema["type"] shouldBe "object"
            pair.outputSchema["type"] shouldBe "object"
        }
    }

    test("no schema contains a Draft-07-only forbidden keyword at any nesting level") {
        for (name in McpToolSchemas.toolNames()) {
            val pair = McpToolSchemas.forTool(name)!!
            assertNoForbiddenKeyword(pair.inputSchema, "$name.input")
            assertNoForbiddenKeyword(pair.outputSchema, "$name.output")
        }
    }

    test("no schema admits a secret-shaped property name") {
        for (name in McpToolSchemas.toolNames()) {
            val pair = McpToolSchemas.forTool(name)!!
            SchemaSecretGuard.findSecretLeaks(pair.inputSchema) shouldBe emptyList()
            SchemaSecretGuard.findSecretLeaks(pair.outputSchema) shouldBe emptyList()
        }
    }

    test("forTool(unknown) returns null") {
        McpToolSchemas.forTool("definitely_not_a_tool") shouldBe null
    }

    test("toolNames is alphabetically sorted (deterministic for golden tests)") {
        val names = McpToolSchemas.toolNames()
        names shouldBe names.sorted()
    }

    test("capabilities_list input is the empty-object marker (no arguments)") {
        val pair = McpToolSchemas.forTool("capabilities_list")!!
        pair.inputSchema["properties"] shouldBe null
        pair.inputSchema["required"] shouldBe null
        pair.inputSchema["additionalProperties"] shouldBe false
    }

    test("schemas are stable across instances (no per-call mutation)") {
        // The schemas table is a single immutable map; calling forTool
        // twice MUST return the same content.
        val first = McpToolSchemas.forTool("schema_validate")!!
        val second = McpToolSchemas.forTool("schema_validate")!!
        first.inputSchema shouldBe second.inputSchema
        first.outputSchema shouldBe second.outputSchema
    }

    test("schema_validate accepts inline schema or schemaRef with optional format/strictness (LF-012 / LN-027 / LN-028 / LN-038)") {
        // The "exactly one of schema/schemaRef" rule is enforced at
        // runtime by SchemaSourceResolver — JSON Schema's oneOf would
        // duplicate that contract on the wire. Pin only the field set
        // and the optional enum constraints.
        val pair = McpToolSchemas.forTool("schema_validate")!!
        val props = mapValue(pair.inputSchema["properties"])
        mapValue(props["schema"])["type"] shouldBe "object"
        mapValue(props["schemaRef"])["type"] shouldBe "string"
        mapValue(props["format"])["enum"] shouldBe listOf("json", "yaml")
        mapValue(props["strictness"])["enum"] shouldBe listOf("lenient", "strict")
        // No required keys: presence is checked by the resolver.
        pair.inputSchema.containsKey("required") shouldBe false
    }

    test("LF-012 / LN-027 / LN-028 / LN-038: schema_generate output uses generatorFindings + truncated→artifactRef") {
        val output = McpToolSchemas.forTool("schema_generate")!!.outputSchema

        val props = mapValue(output["properties"])

        // Findings carry the generator-specific item (base + hint).
        props["findings"] shouldBe McpToolSchemas.generatorFindingArray()
        props["artifactRef"] shouldBe artifactRefField()
        props["executionMeta"] shouldBe McpToolSchemas.executionMetaField()

        output["allOf"] shouldBe listOf(McpToolSchemas.truncatedRequiresField("artifactRef"))
    }

    test("LF-012 / LN-027 / LN-028 / LN-038: generatorFindingItem extends findingItem with optional hint") {
        val item = McpToolSchemas.generatorFindingItem()
        item["additionalProperties"] shouldBe false
        val props = mapValue(item["properties"])
        props.keys shouldBe setOf("severity", "code", "path", "message", "hint")
        val required = stringListValue(item["required"])
        // hint is optional — same as the base findingItem.
        required shouldBe listOf("severity", "code", "path", "message")
    }

    test("LF-012 / LN-027 / LN-028 / LN-038: schema_compare output uses compareDetails findings + truncated→diffArtifactRef") {
        val output = McpToolSchemas.forTool("schema_compare")!!.outputSchema

        val props = mapValue(output["properties"])

        // findings carry the compare-specific details (before/after).
        val findings = mapValue(props["findings"])
        findings shouldBe McpToolSchemas.findingArray(
            detailsSchema = McpToolSchemas.compareDetailsSchema(),
        )

        props["diffArtifactRef"] shouldBe artifactRefField()
        props["executionMeta"] shouldBe McpToolSchemas.executionMetaField()

        output["allOf"] shouldBe listOf(McpToolSchemas.truncatedRequiresField("diffArtifactRef"))
    }

    test("LF-012 / LN-027 / LN-028 / LN-038: compareDetailsSchema closes details and rejects empty / blank slots") {
        val schema = McpToolSchemas.compareDetailsSchema()
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

    test("LF-012 / LN-027 / LN-028 / LN-038: schema_validate output is strict and pins truncated→artifactRef") {
        val output = McpToolSchemas.forTool("schema_validate")!!.outputSchema

        val props = mapValue(output["properties"])

        // findings: array of findingItem
        val findings = mapValue(props["findings"])
        findings["type"] shouldBe "array"
        findings["items"] shouldBe McpToolSchemas.findingItem()

        // artifactRef: URI-pattern string
        props["artifactRef"] shouldBe artifactRefField()

        // executionMeta: closed (additionalProperties=false), required requestId
        props["executionMeta"] shouldBe McpToolSchemas.executionMetaField()

        // allOf carries the truncated → artifactRef coupling
        output["allOf"] shouldBe listOf(McpToolSchemas.truncatedRequiresField("artifactRef"))
    }

    test("listing tools share a stable input shape") {
        for (name in listOf("schema_list", "profile_list", "diff_list", "job_list", "artifact_list")) {
            val pair = McpToolSchemas.forTool(name)!!
            val props = mapValue(pair.inputSchema["properties"])
            props["pageSize"] shouldNotBe null
            props["cursor"] shouldNotBe null
        }
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: KI-nahe Tools verlangen approvalKey + targetDialect als Pflichtfelder") {
        // LF-017 / LF-024 / LN-030 / LN-031: alle drei produktiven KI-Tools haben
        // approvalKey als sync-Idempotency-Key und targetDialect als
        // Pflichtfeld. Schema-Goldenness ist in der Golden-JSON
        // gepinnt; dieser Test pin't zusaetzlich die required-Liste,
        // damit ein Refactor, der ein Pflichtfeld vergisst, hier
        // sofort sichtbar wird.
        for (toolName in listOf(
            "procedure_transform_plan",
            "procedure_transform_execute",
            "testdata_plan",
        )) {
            val input = McpToolSchemas.forTool(toolName)!!.inputSchema
            stringListValue(input["required"]) shouldContainAll listOf("approvalKey", "targetDialect")
            input["additionalProperties"] shouldBe false
        }
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: KI-nahe Tool-Outputs tragen Pflicht-providerMeta + executionMeta") {
        // LF-017 / LF-024 / LN-030 / LN-031 Output: providerMeta + executionMeta sind
        // bei jedem Erfolgsfall Pflicht — sie tragen die Provenance
        // ins Audit (LF-012 / LN-011 / LN-017 / LN-027). Ohne diese Pflicht-Bindung waere
        // ein Output-Goldenness-Drift moeglich, der die Provenance
        // unterschlaegt.
        for (toolName in listOf(
            "procedure_transform_plan",
            "procedure_transform_execute",
            "testdata_plan",
        )) {
            val output = McpToolSchemas.forTool(toolName)!!.outputSchema
            stringListValue(output["required"]) shouldContainAll listOf(
                "summary", "findings", "providerMeta", "executionMeta",
            )
        }
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: providerMetaField ist closed-shape mit modelVersion + requestId nullable") {
        // LF-017 / LF-024 / LN-030 / LN-031: providerMeta darf weder Endpoint noch secretRef
        // enthalten. Pin den shape strukturell, damit ein zukuenftiger
        // Helper-Refactor nicht versehentlich einen Endpoint/Secret-Slot
        // einfuehrt.
        val meta = McpToolSchemas.providerMetaField()
        meta["type"] shouldBe "object"
        meta["additionalProperties"] shouldBe false
        val props = mapValue(meta["properties"])
        props.keys shouldBe setOf("providerName", "model", "modelVersion", "requestId")
        stringListValue(meta["required"]) shouldBe listOf("providerName", "model")
        // modelVersion und requestId sind nullable (LF-017 / LF-024 / LN-030 / LN-031: lokale
        // Provider liefern keine modelVersion/requestId).
        mapValue(props["modelVersion"])["type"] shouldBe listOf("string", "null")
        mapValue(props["requestId"])["type"] shouldBe listOf("string", "null")
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
