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
            principalProvider = { PRINCIPAL },
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
            principalProvider = { PRINCIPAL },
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
            principalProvider = { PRINCIPAL },
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
            principalProvider = { PRINCIPAL },
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
            principalProvider = { PRINCIPAL },
        )
        val result = sut.toolsCall(ToolsCallParams(name = "policy_denied_test_tool")).get()
        result.isError shouldBe true
        val envelope = JsonParser.parseString(result.content.single().text!!).asJsonObject
        envelope.get("code").asString shouldBe ToolErrorCode.POLICY_DENIED.name
        val details = envelope.getAsJsonObject("details")
        details.get("policyName").asString shouldBe "test-policy"
        details.get("reason").asString shouldBe "test-reason"
    }

    test("missing principal -> AUTH_REQUIRED envelope on tools/call") {
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            toolRegistry = PhaseBRegistries.toolRegistry(),
            principalProvider = { null },
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
            principalProvider = { null },
        )
        val ex = shouldThrow<ExecutionException> {
            sut.toolsCall(ToolsCallParams(name = "definitely_not_a_tool")).get()
        }
        val cause = ex.cause as ResponseErrorException
        cause.responseError.code shouldBe ResponseErrorCode.MethodNotFound.value
    }

    test("ServerCapabilities.tools is set with listChanged=false post-AP6.8") {
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            toolRegistry = PhaseBRegistries.toolRegistry(),
            principalProvider = { PRINCIPAL },
        )
        val initOut = sut.initialize(InitializeParams(McpProtocol.MCP_PROTOCOL_VERSION)).get()
        initOut.capabilities.tools shouldBe mapOf("listChanged" to false)
        initOut.capabilities.resources shouldBe null
    }

    test("ToolsCallContent for a successful call propagates type/text/mimeType") {
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            toolRegistry = PhaseBRegistries.toolRegistry(),
            principalProvider = { PRINCIPAL },
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
            principalProvider = { PRINCIPAL },
        )
        val args = JsonObject().apply { addProperty("foo", "bar") }
        sut.toolsCall(ToolsCallParams(name = "arg_capture_tool", arguments = args)).get()
        captured.single()!!.get("foo").asString shouldBe "bar"
    }
})
