package dev.dmigrate.mcp.protocol

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.mcp.registry.PhaseBRegistries
import dev.dmigrate.mcp.registry.ToolCallContext
import dev.dmigrate.mcp.registry.ToolCallOutcome
import dev.dmigrate.mcp.registry.ToolDescriptor
import dev.dmigrate.mcp.registry.ToolHandler
import dev.dmigrate.mcp.registry.ToolRegistry
import dev.dmigrate.server.application.error.PolicyDeniedException
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.time.Instant
import java.util.concurrent.ExecutionException

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

class McpServiceImplToolsTest : FunSpec({

    test("tools/list returns all registered tools with stub schemas") {
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            toolRegistry = PhaseBRegistries.toolRegistry(),
            initialPrincipal = PRINCIPAL,
        )
        val result = sut.toolsList(null).get()
        result.tools.any { it.name == "capabilities_list" } shouldBe true
        result.tools.any { it.name == "schema_validate" } shouldBe true
        result.tools.first { it.name == "capabilities_list" }
            .inputSchema["\$schema"] shouldBe "https://json-schema.org/draft/2020-12/schema"
        result.nextCursor shouldBe null
    }

    test("tools/call capabilities_list returns Phase-B contract snapshot") {
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            toolRegistry = PhaseBRegistries.toolRegistry(),
            initialPrincipal = PRINCIPAL,
        )
        val result = sut.toolsCall(ToolsCallParams(name = "capabilities_list")).get()
        result.isError shouldBe false
        val text = result.content.single().text!!
        val json = JsonParser.parseString(text).asJsonObject
        json.get("serverName").asString shouldBe McpProtocol.SERVER_NAME
        json.get("dmigrateContractVersion").asString shouldBe McpProtocol.DMIGRATE_CONTRACT_VERSION
    }

    test("tools/call on unknown name fails with JSON-RPC -32601 (Method not found)") {
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            toolRegistry = PhaseBRegistries.toolRegistry(),
            initialPrincipal = PRINCIPAL,
        )
        val ex = shouldThrow<ExecutionException> {
            sut.toolsCall(ToolsCallParams(name = "no_such_tool")).get()
        }
        val cause = ex.cause as ResponseErrorException
        cause.responseError.code shouldBe ResponseErrorCode.MethodNotFound.value
    }

    test("tools/call on registered-but-unimplemented tool returns isError envelope") {
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            toolRegistry = PhaseBRegistries.toolRegistry(),
            initialPrincipal = PRINCIPAL,
        )
        val result = sut.toolsCall(ToolsCallParams(name = "schema_validate")).get()
        result.isError shouldBe true
        val envelope = JsonParser.parseString(result.content.single().text!!).asJsonObject
        envelope.get("code").asString shouldBe ToolErrorCode.UNSUPPORTED_TOOL_OPERATION.name
    }

    test("tools/call surfaces ApplicationException details into the envelope") {
        val descriptor = ToolDescriptor(
            name = "policy_denied_test_tool",
            title = "Policy denied",
            description = "fixture",
            requiredScopes = setOf("dmigrate:read"),
            inputSchema = mapOf("type" to "object"),
            outputSchema = mapOf("type" to "object"),
        )
        val handler = ToolHandler {
            throw PolicyDeniedException(policyName = "test-policy", reason = "test-reason")
        }
        val registry = ToolRegistry.builder().register(descriptor, handler).build()
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            toolRegistry = registry,
            initialPrincipal = PRINCIPAL,
        )
        val result = sut.toolsCall(ToolsCallParams(name = "policy_denied_test_tool")).get()
        result.isError shouldBe true
        val envelope = JsonParser.parseString(result.content.single().text!!).asJsonObject
        envelope.get("code").asString shouldBe ToolErrorCode.POLICY_DENIED.name
        val details = envelope.getAsJsonArray("details")
        val pairs = details.map {
            val obj = it.asJsonObject
            obj.get("key").asString to obj.get("value").asString
        }
        pairs shouldBe listOf("policyName" to "test-policy", "reason" to "test-reason")
    }

    test("missing principal -> AUTH_REQUIRED envelope on tools/call") {
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            toolRegistry = PhaseBRegistries.toolRegistry(),
            initialPrincipal = null,
        )
        val result = sut.toolsCall(ToolsCallParams(name = "capabilities_list")).get()
        result.isError shouldBe true
        val envelope = JsonParser.parseString(result.content.single().text!!).asJsonObject
        envelope.get("code").asString shouldBe ToolErrorCode.AUTH_REQUIRED.name
    }

    test("tools/call ignores unknown tool when principal absent (still -32601)") {
        // The route MUST report unknown tools as JSON-RPC -32601 BEFORE
        // any auth check, otherwise misspelled tool names look like
        // auth failures. We assert that ordering.
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            toolRegistry = PhaseBRegistries.toolRegistry(),
            initialPrincipal = null,
        )
        val ex = shouldThrow<ExecutionException> {
            sut.toolsCall(ToolsCallParams(name = "definitely_not_a_tool")).get()
        }
        val cause = ex.cause as ResponseErrorException
        cause.responseError.code shouldBe ResponseErrorCode.MethodNotFound.value
    }

    test("tools/list on an empty registry returns an empty list with no cursor") {
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            toolRegistry = ToolRegistry.builder().build(),
            initialPrincipal = PRINCIPAL,
        )
        val result = sut.toolsList(null).get()
        result.tools shouldBe emptyList()
        result.nextCursor shouldBe null
    }

    test("tools/list never advertises MCP-protocol method names as tools") {
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            toolRegistry = PhaseBRegistries.toolRegistry(),
            initialPrincipal = PRINCIPAL,
        )
        val result = sut.toolsList(null).get()
        val advertised = result.tools.map { it.name }.toSet()
        advertised shouldNotContain "tools/list"
        advertised shouldNotContain "tools/call"
        advertised shouldNotContain "resources/list"
        advertised shouldNotContain "resources/templates/list"
        advertised shouldNotContain "resources/read"
        advertised shouldNotContain "connections/list"
    }

    test("tools/call honours ToolCallOutcome.Error from a custom handler") {
        // Distinct from the throw-an-ApplicationException path: handlers
        // may return a ToolCallOutcome.Error directly when they want to
        // inject custom envelope details that don't fit any
        // ApplicationException subtype. Verifies that branch round-trips.
        val descriptor = ToolDescriptor(
            name = "custom_envelope_tool",
            title = "Custom envelope",
            description = "fixture",
            requiredScopes = setOf("dmigrate:read"),
            inputSchema = mapOf("type" to "object"),
            outputSchema = mapOf("type" to "object"),
        )
        val customEnvelope = dev.dmigrate.server.core.error.ToolErrorEnvelope(
            code = ToolErrorCode.POLICY_DENIED,
            message = "custom denial",
            details = listOf(
                dev.dmigrate.server.core.error.ToolErrorDetail("policyName", "x"),
                dev.dmigrate.server.core.error.ToolErrorDetail("custom", "y"),
            ),
            requestId = "req-42",
        )
        val handler = ToolHandler { ToolCallOutcome.Error(customEnvelope) }
        val registry = ToolRegistry.builder().register(descriptor, handler).build()
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            toolRegistry = registry,
            initialPrincipal = PRINCIPAL,
        )
        val result = sut.toolsCall(ToolsCallParams(name = "custom_envelope_tool")).get()
        result.isError shouldBe true
        val envelope = JsonParser.parseString(result.content.single().text!!).asJsonObject
        envelope.get("code").asString shouldBe ToolErrorCode.POLICY_DENIED.name
        envelope.get("message").asString shouldBe "custom denial"
        envelope.get("requestId").asString shouldBe "req-42"
        val pairs = envelope.getAsJsonArray("details").map {
            val obj = it.asJsonObject
            obj.get("key").asString to obj.get("value").asString
        }
        pairs shouldBe listOf("policyName" to "x", "custom" to "y")
    }

    test("envelope details survive duplicate keys (ValidationError-style)") {
        // §12.16: details is a JSON array of {key,value}, NOT an object.
        // ValidationErrorException can emit two violations on the same
        // field — the wire shape must preserve both.
        val descriptor = ToolDescriptor(
            name = "validation_test_tool",
            title = "Validation test",
            description = "fixture",
            requiredScopes = setOf("dmigrate:read"),
            inputSchema = mapOf("type" to "object"),
            outputSchema = mapOf("type" to "object"),
        )
        val handler = ToolHandler {
            throw dev.dmigrate.server.application.error.ValidationErrorException(
                listOf(
                    dev.dmigrate.server.application.error.ValidationViolation("name", "too short"),
                    dev.dmigrate.server.application.error.ValidationViolation("name", "forbidden"),
                ),
            )
        }
        val registry = ToolRegistry.builder().register(descriptor, handler).build()
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            toolRegistry = registry,
            initialPrincipal = PRINCIPAL,
        )
        val result = sut.toolsCall(ToolsCallParams(name = "validation_test_tool")).get()
        val envelope = JsonParser.parseString(result.content.single().text!!).asJsonObject
        val pairs = envelope.getAsJsonArray("details").map {
            val obj = it.asJsonObject
            obj.get("key").asString to obj.get("value").asString
        }
        pairs shouldBe listOf("name" to "too short", "name" to "forbidden")
    }

    test("AUTH_REQUIRED envelope carries toolName in structured details") {
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            toolRegistry = PhaseBRegistries.toolRegistry(),
            initialPrincipal = null,
        )
        val result = sut.toolsCall(ToolsCallParams(name = "capabilities_list")).get()
        val envelope = JsonParser.parseString(result.content.single().text!!).asJsonObject
        envelope.get("code").asString shouldBe ToolErrorCode.AUTH_REQUIRED.name
        val pairs = envelope.getAsJsonArray("details").map {
            val obj = it.asJsonObject
            obj.get("key").asString to obj.get("value").asString
        }
        pairs shouldBe listOf("toolName" to "capabilities_list")
    }

    test("bindPrincipal updates the principal used by subsequent dispatches") {
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            toolRegistry = PhaseBRegistries.toolRegistry(),
            initialPrincipal = null,
        )
        // Without a bound principal, AUTH_REQUIRED.
        sut.toolsCall(ToolsCallParams(name = "capabilities_list")).get().isError shouldBe true
        // After binding, success.
        sut.bindPrincipal(PRINCIPAL)
        sut.toolsCall(ToolsCallParams(name = "capabilities_list")).get().isError shouldBe false
        // Re-binding to null reverts to AUTH_REQUIRED.
        sut.bindPrincipal(null)
        sut.toolsCall(ToolsCallParams(name = "capabilities_list")).get().isError shouldBe true
    }

    test("ServerCapabilities.tools is set with listChanged=false post-AP6.8") {
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            toolRegistry = PhaseBRegistries.toolRegistry(),
            initialPrincipal = PRINCIPAL,
        )
        val initOut = sut.initialize(InitializeParams(McpProtocol.MCP_PROTOCOL_VERSION)).get()
        initOut.capabilities.tools shouldBe mapOf("listChanged" to false)
        initOut.capabilities.resources shouldBe null
    }

    test("ToolsCallContent for a successful call propagates type/text/mimeType") {
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            toolRegistry = PhaseBRegistries.toolRegistry(),
            initialPrincipal = PRINCIPAL,
        )
        val result = sut.toolsCall(ToolsCallParams(name = "capabilities_list")).get()
        val first = result.content.single()
        first.type shouldBe "text"
        first.mimeType shouldBe "application/json"
        first.text shouldNotBe null
    }

    test("arguments are forwarded verbatim to the handler") {
        val captured = mutableListOf<JsonObject?>()
        val descriptor = ToolDescriptor(
            name = "arg_capture_tool",
            title = "Arg capture",
            description = "fixture",
            requiredScopes = setOf("dmigrate:read"),
            inputSchema = mapOf("type" to "object"),
            outputSchema = mapOf("type" to "object"),
        )
        val handler = ToolHandler { ctx: ToolCallContext ->
            captured += ctx.arguments?.asJsonObject
            ToolCallOutcome.Success(emptyList())
        }
        val registry = ToolRegistry.builder().register(descriptor, handler).build()
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            toolRegistry = registry,
            initialPrincipal = PRINCIPAL,
        )
        val args = JsonObject().apply { addProperty("foo", "bar") }
        sut.toolsCall(ToolsCallParams(name = "arg_capture_tool", arguments = args)).get()
        captured.single()!!.get("foo").asString shouldBe "bar"
    }
})
