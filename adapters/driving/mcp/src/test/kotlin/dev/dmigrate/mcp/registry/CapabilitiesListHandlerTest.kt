package dev.dmigrate.mcp.registry

import com.google.gson.JsonParser
import dev.dmigrate.mcp.protocol.McpProtocol
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
})
