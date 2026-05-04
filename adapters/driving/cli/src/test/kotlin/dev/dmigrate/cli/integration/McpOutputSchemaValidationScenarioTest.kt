package dev.dmigrate.cli.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import dev.dmigrate.mcp.protocol.ToolMetadata
import io.kotest.assertions.withClue
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.deleteRecursively

private val IntegrationTag = NamedTag("integration")

/**
 * AP 6.24 E8(A): output-schema validation per
 * `ImpPlan-0.9.6-C.md` §6.24 Akzeptanz Z. 2077-2081.
 *
 * For the four tools that publish a typed output schema —
 * `schema_validate`, `schema_generate`, `schema_compare`,
 * `job_status_get` — this spec runs a happy-path `tools/call` on
 * BOTH transports and validates the runtime response payload against
 * the OUTPUT SCHEMA the server itself publishes through `tools/list`.
 *
 * Why pull the schemas off the wire (not from `PhaseBToolSchemas`):
 * - `tools/list` is the contract surface clients see; validating
 *   against it covers a real "server announces X, delivers X" loop
 *   instead of just reaching into mcp-internal data
 * - if a future refactor diverges the published schema from the
 *   runtime payload, the test catches it without an mcp-internal
 *   import
 *
 * Acceptance specifics this catches:
 * - typed output shape (required keys, types) holds for the four
 *   tools on stdio AND http
 * - generic `ResponseLimitEnforcer` envelopes for these tools fail
 *   the test — they don't carry the schema's required keys (e.g.
 *   `findings` / `executionMeta`), so validation rejects them
 *
 * Negative-shape (truncation → typed `artifactRef`/`diffArtifactRef`)
 * is covered by E3's transport-equivalence test plus the schema's
 * own `allOf` clause, which the validator enforces here as a
 * by-product when truncation triggers.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class McpOutputSchemaValidationScenarioTest : FunSpec({

    tags(IntegrationTag)

    test("schema_validate runtime output validates against the wire-published output schema (stdio + http)") {
        withFreshTransports { s, h ->
            for (harness in listOf(s, h)) {
                val schemas = collectOutputSchemas(harness)
                val arguments = JsonParser.parseString(
                    """{"schema":{"name":"orders","version":"1.0","tables":{}}}""",
                ).asJsonObject
                val result = harness.toolsCall("schema_validate", arguments)
                result.isError shouldBe false
                val payload = parsePayload(result.text())
                assertValidAgainstOutputSchema(harness.name, "schema_validate", schemas, payload)
            }
        }
    }

    test("schema_generate runtime output validates against the wire-published output schema") {
        withFreshTransports { s, h ->
            for (harness in listOf(s, h)) {
                val schemas = collectOutputSchemas(harness)
                val arguments = JsonParser.parseString(
                    """{"schema":{"name":"orders","version":"1.0",""" +
                        """"tables":{"t1":{"columns":{"id":{"type":"identifier"}},"primary_key":["id"]}}},""" +
                        """"targetDialect":"POSTGRESQL"}""",
                ).asJsonObject
                val result = harness.toolsCall("schema_generate", arguments)
                result.isError shouldBe false
                val payload = parsePayload(result.text())
                assertValidAgainstOutputSchema(harness.name, "schema_generate", schemas, payload)
            }
        }
    }

    test("schema_compare runtime output validates against the wire-published output schema") {
        withFreshTransports { s, h ->
            for (harness in listOf(s, h)) {
                val schemas = collectOutputSchemas(harness)
                val sourceUri = IntegrationFixtures.stageSchema(
                    wiring = harness.wiring,
                    principal = harness.principal,
                    schemaId = "schema-e8-source",
                    json = """{"name":"a","version":"1.0","tables":{}}""",
                )
                val targetUri = IntegrationFixtures.stageSchema(
                    wiring = harness.wiring,
                    principal = harness.principal,
                    schemaId = "schema-e8-target",
                    json = """{"name":"a","version":"1.0","tables":{}}""",
                )
                val arguments = JsonParser.parseString(
                    """{"left":{"schemaRef":"$sourceUri"},"right":{"schemaRef":"$targetUri"}}""",
                ).asJsonObject
                val result = harness.toolsCall("schema_compare", arguments)
                result.isError shouldBe false
                val payload = parsePayload(result.text())
                assertValidAgainstOutputSchema(harness.name, "schema_compare", schemas, payload)
            }
        }
    }

    test("job_status_get runtime output validates against the wire-published output schema") {
        withFreshTransports { s, h ->
            for (harness in listOf(s, h)) {
                val schemas = collectOutputSchemas(harness)
                val tenant = harness.principal.effectiveTenantId.value
                IntegrationFixtures.stageJob(
                    wiring = harness.wiring,
                    principal = harness.principal,
                    jobId = "job-e8-fixture",
                    operation = "schema_generate",
                )
                val arguments = JsonParser.parseString(
                    """{"jobId":"job-e8-fixture"}""",
                ).asJsonObject
                val result = harness.toolsCall("job_status_get", arguments)
                result.isError shouldBe false
                val payload = parsePayload(result.text())
                withClue("$tenant/${harness.name} sanity: status field must be present before validation") {
                    payload.has("status") shouldBe true
                }
                assertValidAgainstOutputSchema(harness.name, "job_status_get", schemas, payload)
            }
        }
    }
})

// --- E8(A) helpers ----------------------------------------------------------

private val gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()
private val jackson = ObjectMapper()

/**
 * Pulls the per-tool output schema off the wire (`tools/list` →
 * `outputSchema`). Keyed by tool name. Tools without an
 * `outputSchema` field are skipped — the four E8(A) tools all
 * publish one.
 */
private fun collectOutputSchemas(harness: McpClientHarness): Map<String, JsonSchema> {
    val tools = harness.toolsList().tools
    val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    return tools.associateBy(ToolMetadata::name) { tool ->
        val schemaJson = gson.toJson(tool.outputSchema)
        val node: JsonNode = jackson.readTree(schemaJson)
        // 2020-12 dialect is enforced by PhaseBToolSchemasTest at the
        // unit level; this factory commits the test runner to that
        // dialect so a future schema that drops `$schema` still
        // validates with the same semantics.
        factory.getSchema(node)
    }
}

private fun assertValidAgainstOutputSchema(
    transportName: String,
    toolName: String,
    schemas: Map<String, JsonSchema>,
    payload: JsonObject,
) {
    val schema = schemas[toolName]
        ?: error("$transportName: tools/list did not advertise '$toolName' (or its outputSchema)")
    val payloadNode: JsonNode = jackson.readTree(gson.toJson(payload))
    val violations = schema.validate(payloadNode)
    withClue("$transportName: '$toolName' runtime output violates its wire-published schema; violations=$violations") {
        violations.size shouldBe 0
    }
}

private fun parsePayload(text: String): JsonObject =
    JsonParser.parseString(text).asJsonObject

private fun dev.dmigrate.mcp.protocol.ToolsCallResult.text(): String {
    val body = content.firstOrNull()?.text ?: error("tools/call response carried no text content")
    return body
}

/**
 * E8 reuses the E2-E7 transport-pair pattern. Block does its own
 * assertions; cleanup runs even on failure.
 */
private fun withFreshTransports(
    block: (StdioHarness, HttpHarness) -> Unit,
) {
    val stdioDir = IntegrationFixtures.freshStateDir("dmigrate-it-stdio-")
    val httpDir = IntegrationFixtures.freshStateDir("dmigrate-it-http-")
    val stdio = StdioHarness.start(stdioDir, IntegrationFixtures.INTEGRATION_PRINCIPAL)
    val http = HttpHarness.start(httpDir, IntegrationFixtures.INTEGRATION_PRINCIPAL)
    try {
        stdio.initialize()
        stdio.initializedNotification()
        http.initialize()
        http.initializedNotification()
        block(stdio, http)
    } finally {
        try { stdio.close() } catch (_: Throwable) {}
        try { http.close() } catch (_: Throwable) {}
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        try { stdioDir.deleteRecursively() } catch (_: Throwable) {}
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        try { httpDir.deleteRecursively() } catch (_: Throwable) {}
    }
}
