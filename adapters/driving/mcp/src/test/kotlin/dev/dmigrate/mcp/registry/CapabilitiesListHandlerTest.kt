package dev.dmigrate.mcp.registry

import com.google.gson.JsonParser
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.mcp.protocol.McpProtocol
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

private val PRINCIPAL = PrincipalContext(
    principalId = PrincipalId("alice"),
    homeTenantId = TenantId("acme"),
    effectiveTenantId = TenantId("acme"),
    allowedTenantIds = setOf(TenantId("acme")),
    scopes = setOf("dmigrate:read"),
    isAdmin = false,
    auditSubject = "alice",
    authSource = AuthSource.SERVICE_ACCOUNT,
    expiresAt = Instant.MAX,
)

private val SAMPLE_TOOL = ToolDescriptor(
    name = "schema_validate",
    title = "Validate schema",
    description = "Validates a schema document.",
    requiredScopes = setOf("dmigrate:read"),
    inputSchema = mapOf("type" to "object"),
    outputSchema = mapOf("type" to "object"),
    inlineLimits = "max 1 MiB",
    resourceFallbackHint = "use template X",
    errorCodes = setOf(ToolErrorCode.VALIDATION_ERROR),
)

class CapabilitiesListHandlerTest : FunSpec({

    test("payload includes contract versions, tools and scope table") {
        val sut = CapabilitiesListReadOnlyHandler(
            tools = listOf(SAMPLE_TOOL),
            scopeMapping = mapOf(
                "schema_validate" to setOf("dmigrate:read"),
                "schema_reverse_start" to setOf("dmigrate:job:start"),
            ),
        )
        val outcome = sut.handle(ToolCallContext("capabilities_list", null, PRINCIPAL))
        outcome.shouldBeInstanceOf<ToolCallOutcome.Success>()
        val text = outcome.content.single().text!!
        val json = JsonParser.parseString(text).asJsonObject
        json.get("mcpProtocolVersion").asString shouldBe McpProtocol.MCP_PROTOCOL_VERSION
        json.get("dmigrateContractVersion").asString shouldBe McpProtocol.DMIGRATE_CONTRACT_VERSION
        json.get("serverName").asString shouldBe McpProtocol.SERVER_NAME
        json.getAsJsonArray("tools").size() shouldBe 1
        val toolJson = json.getAsJsonArray("tools").get(0).asJsonObject
        toolJson.get("name").asString shouldBe "schema_validate"
        toolJson.get("inlineLimits").asString shouldBe "max 1 MiB"
        toolJson.get("resourceFallbackHint").asString shouldBe "use template X"
        toolJson.getAsJsonArray("errorCodes").get(0).asString shouldBe "VALIDATION_ERROR"
        // Scope table groups by scope; "dmigrate:job:start" is a key.
        val scopeTable = json.getAsJsonObject("scopeTable")
        scopeTable.getAsJsonArray("dmigrate:job:start").get(0).asString shouldBe "schema_reverse_start"
    }

    test("optional descriptor fields are omitted when null/empty") {
        val tool = SAMPLE_TOOL.copy(
            inlineLimits = null,
            resourceFallbackHint = null,
            errorCodes = emptySet(),
        )
        val sut = CapabilitiesListReadOnlyHandler(
            tools = listOf(tool),
            scopeMapping = mapOf("schema_validate" to setOf("dmigrate:read")),
        )
        val outcome = sut.handle(ToolCallContext("capabilities_list", null, PRINCIPAL))
        val text = (outcome as ToolCallOutcome.Success).content.single().text!!
        val toolJson = JsonParser.parseString(text).asJsonObject
            .getAsJsonArray("tools").get(0).asJsonObject
        toolJson.has("inlineLimits") shouldBe false
        toolJson.has("resourceFallbackHint") shouldBe false
        toolJson.has("errorCodes") shouldBe false
    }

    test("content type and mime type are MCP-conformant text/application-json") {
        val sut = CapabilitiesListReadOnlyHandler(emptyList(), emptyMap())
        val outcome = sut.handle(ToolCallContext("capabilities_list", null, PRINCIPAL))
        val content = (outcome as ToolCallOutcome.Success).content.single()
        content.type shouldBe "text"
        content.mimeType shouldBe "application/json"
    }

    test("payload omits inputSchema and outputSchema (capabilities_list is a contract overview)") {
        // KDoc on CapabilitiesListReadOnlyHandler explicitly says the
        // schemas are reachable via tools/list and would bloat this
        // payload — pin that.
        val sut = CapabilitiesListReadOnlyHandler(
            tools = listOf(SAMPLE_TOOL),
            scopeMapping = mapOf("schema_validate" to setOf("dmigrate:read")),
        )
        val outcome = sut.handle(ToolCallContext("capabilities_list", null, PRINCIPAL))
        val text = (outcome as ToolCallOutcome.Success).content.single().text!!
        val toolJson = JsonParser.parseString(text).asJsonObject
            .getAsJsonArray("tools").get(0).asJsonObject
        toolJson.has("inputSchema") shouldBe false
        toolJson.has("outputSchema") shouldBe false
    }

    test("scopeTable surfaces multi-scope tools under every required scope") {
        // §4.4 Protected Resource Metadata depends on this — dropping
        // a scope would silently fail authorization advertisement.
        val sut = CapabilitiesListReadOnlyHandler(
            tools = emptyList(),
            scopeMapping = mapOf(
                "data_export_start" to setOf("dmigrate:job:start", "dmigrate:export:approved"),
                "schema_validate" to setOf("dmigrate:read"),
            ),
        )
        val outcome = sut.handle(ToolCallContext("capabilities_list", null, PRINCIPAL))
        val text = (outcome as ToolCallOutcome.Success).content.single().text!!
        val scopeTable = JsonParser.parseString(text).asJsonObject.getAsJsonObject("scopeTable")
        // data_export_start appears under BOTH scopes
        scopeTable.getAsJsonArray("dmigrate:job:start").map { it.asString } shouldBe listOf("data_export_start")
        scopeTable.getAsJsonArray("dmigrate:export:approved").map { it.asString } shouldBe listOf("data_export_start")
        scopeTable.getAsJsonArray("dmigrate:read").map { it.asString } shouldBe listOf("schema_validate")
    }

    test("scopeTable skips methods with empty scope sets") {
        val sut = CapabilitiesListReadOnlyHandler(
            tools = emptyList(),
            scopeMapping = mapOf(
                "initialize" to emptySet(),
                "schema_validate" to setOf("dmigrate:read"),
            ),
        )
        val outcome = sut.handle(ToolCallContext("capabilities_list", null, PRINCIPAL))
        val text = (outcome as ToolCallOutcome.Success).content.single().text!!
        val scopeTable = JsonParser.parseString(text).asJsonObject.getAsJsonObject("scopeTable")
        scopeTable.has("dmigrate:read") shouldBe true
        // No bucket for empty-scope methods
        scopeTable.entrySet().size shouldBe 1
    }

    test("payload includes every supported dialect (AP 6.2)") {
        val sut = CapabilitiesListReadOnlyHandler(emptyList(), emptyMap())
        val text = invoke(sut)
        val dialects = JsonParser.parseString(text).asJsonObject
            .getAsJsonArray("dialects").map { it.asString }.toSet()
        dialects shouldBe DatabaseDialect.values().map { it.name }.toSet()
    }

    test("payload includes every supported neutral-schema format (AP 6.2)") {
        val sut = CapabilitiesListReadOnlyHandler(emptyList(), emptyMap())
        val text = invoke(sut)
        val formats = JsonParser.parseString(text).asJsonObject
            .getAsJsonArray("formats").map { it.asString }.toSet()
        formats shouldBe setOf("json", "yaml")
    }

    test("payload exposes every numeric limit from McpLimitsConfig (§10 DoD)") {
        val limits = McpLimitsConfig()
        val sut = CapabilitiesListReadOnlyHandler(emptyList(), emptyMap(), limits = limits)
        val text = invoke(sut)
        val limitsJson = JsonParser.parseString(text).asJsonObject.getAsJsonObject("limits")
        limitsJson.get("maxToolResponseBytes").asInt shouldBe limits.maxToolResponseBytes
        limitsJson.get("maxNonUploadToolRequestBytes").asInt shouldBe limits.maxNonUploadToolRequestBytes
        limitsJson.get("maxInlineSchemaBytes").asInt shouldBe limits.maxInlineSchemaBytes
        limitsJson.get("maxUploadToolRequestBytes").asInt shouldBe limits.maxUploadToolRequestBytes
        limitsJson.get("maxUploadSegmentBytes").asInt shouldBe limits.maxUploadSegmentBytes
        limitsJson.get("maxArtifactChunkBytes").asInt shouldBe limits.maxArtifactChunkBytes
        limitsJson.get("maxInlineFindings").asInt shouldBe limits.maxInlineFindings
        limitsJson.get("maxArtifactUploadBytes").asLong shouldBe limits.maxArtifactUploadBytes
    }

    test("maxArtifactChunkBytes equals 32768 per §10 DoD") {
        val sut = CapabilitiesListReadOnlyHandler(emptyList(), emptyMap())
        val text = invoke(sut)
        JsonParser.parseString(text).asJsonObject
            .getAsJsonObject("limits")
            .get("maxArtifactChunkBytes").asInt shouldBe 32_768
    }

    test("custom McpLimitsConfig overrides surface in the payload") {
        val custom = McpLimitsConfig(maxToolResponseBytes = 4_096, maxInlineFindings = 10)
        val sut = CapabilitiesListReadOnlyHandler(emptyList(), emptyMap(), limits = custom)
        val limitsJson = JsonParser.parseString(invoke(sut)).asJsonObject.getAsJsonObject("limits")
        limitsJson.get("maxToolResponseBytes").asInt shouldBe 4_096
        limitsJson.get("maxInlineFindings").asInt shouldBe 10
    }

    test("executionMeta carries the requestId from the supplied provider") {
        val sut = CapabilitiesListReadOnlyHandler(
            tools = emptyList(),
            scopeMapping = emptyMap(),
            requestIdProvider = { "req-deadbeef" },
        )
        val text = invoke(sut)
        val meta = JsonParser.parseString(text).asJsonObject.getAsJsonObject("executionMeta")
        meta.get("requestId").asString shouldBe "req-deadbeef"
    }

    test("each call gets a fresh requestId from the default generator") {
        val sut = CapabilitiesListReadOnlyHandler(emptyList(), emptyMap())
        val first = JsonParser.parseString(invoke(sut)).asJsonObject
            .getAsJsonObject("executionMeta").get("requestId").asString
        val second = JsonParser.parseString(invoke(sut)).asJsonObject
            .getAsJsonObject("executionMeta").get("requestId").asString
        (first != second) shouldBe true
        first.startsWith("req-") shouldBe true
    }
})

private fun invoke(handler: CapabilitiesListReadOnlyHandler): String {
    val outcome = handler.handle(ToolCallContext("capabilities_list", null, PRINCIPAL))
    return (outcome as ToolCallOutcome.Success).content.single().text!!
}
