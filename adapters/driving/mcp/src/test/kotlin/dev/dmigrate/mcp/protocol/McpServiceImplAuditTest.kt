package dev.dmigrate.mcp.protocol

import dev.dmigrate.mcp.registry.PhaseBRegistries
import dev.dmigrate.mcp.registry.ToolCallContext
import dev.dmigrate.mcp.registry.ToolCallOutcome
import dev.dmigrate.mcp.registry.ToolContent
import dev.dmigrate.mcp.registry.ToolDescriptor
import dev.dmigrate.mcp.registry.ToolHandler
import dev.dmigrate.mcp.registry.ToolRegistry
import dev.dmigrate.server.application.audit.AuditScope
import dev.dmigrate.server.application.error.PolicyDeniedException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.error.ValidationViolation
import dev.dmigrate.server.core.audit.AuditOutcome
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.memory.InMemoryAuditSink
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ExecutionException

private val ACME = TenantId("acme")
private val PRINCIPAL = PrincipalContext(
    principalId = PrincipalId("alice"),
    homeTenantId = ACME,
    effectiveTenantId = ACME,
    allowedTenantIds = setOf(ACME),
    scopes = setOf("dmigrate:read"),
    isAdmin = false,
    auditSubject = "alice",
    authSource = AuthSource.SERVICE_ACCOUNT,
    expiresAt = Instant.MAX,
)

private class AuditTestFixture(
    val sut: McpServiceImpl,
    val sink: InMemoryAuditSink,
)

private fun fixture(
    handler: ToolHandler = ToolHandler {
        ToolCallOutcome.Success(
            content = listOf(ToolContent(type = "text", text = """{"ok":true}""", mimeType = "application/json")),
        )
    },
    toolName: String = "test_tool",
    requiredScopes: Set<String> = setOf("dmigrate:read"),
    principal: PrincipalContext? = PRINCIPAL,
    requestId: String = "req-fixed",
): AuditTestFixture {
    val sink = InMemoryAuditSink()
    val descriptor = ToolDescriptor(
        name = toolName,
        title = toolName,
        description = "test tool",
        requiredScopes = requiredScopes,
        inputSchema = mapOf("type" to "object"),
        outputSchema = mapOf("type" to "object"),
    )
    val registry = ToolRegistry.builder().register(descriptor, handler).build()
    val sut = McpServiceImpl(
        serverVersion = "0.0.0",
        toolRegistry = registry,
        initialPrincipal = principal,
        scopeMapping = mapOf(toolName to requiredScopes),
        auditScope = AuditScope(sink, Clock.systemUTC()),
        requestIdProvider = { requestId },
    )
    return AuditTestFixture(sut, sink)
}

class McpServiceImplAuditTest : FunSpec({

    test("AP 6.20: a successful tools/call emits one SUCCESS audit event with the dispatch requestId") {
        val f = fixture()
        f.sut.toolsCall(ToolsCallParams(name = "test_tool")).get()
        val events = f.sink.recorded()
        events.size shouldBe 1
        val event = events.single()
        event.outcome shouldBe AuditOutcome.SUCCESS
        event.toolName shouldBe "test_tool"
        event.tenantId shouldBe ACME
        event.principalId shouldBe PrincipalId("alice")
        event.requestId shouldBe "req-fixed"
        event.errorCode shouldBe null
    }

    test("AP 6.20: a handler-thrown ValidationError emits FAILURE with VALIDATION_ERROR outcome") {
        val handler = ToolHandler {
            throw ValidationErrorException(listOf(ValidationViolation("schema", "missing required field")))
        }
        val f = fixture(handler = handler)
        f.sut.toolsCall(ToolsCallParams(name = "test_tool")).get()
        val event = f.sink.recorded().single()
        event.outcome shouldBe AuditOutcome.FAILURE
        event.errorCode shouldBe ToolErrorCode.VALIDATION_ERROR
        event.toolName shouldBe "test_tool"
        event.requestId shouldBe "req-fixed"
    }

    test("AP 6.20: a missing principal emits FAILURE with AUTH_REQUIRED outcome") {
        val f = fixture(principal = null)
        f.sut.toolsCall(ToolsCallParams(name = "test_tool")).get()
        val event = f.sink.recorded().single()
        event.outcome shouldBe AuditOutcome.FAILURE
        event.errorCode shouldBe ToolErrorCode.AUTH_REQUIRED
        // No principal => no tenant/principal on the event.
        event.principalId shouldBe null
        event.tenantId shouldBe null
    }

    test("AP 6.20: a scope violation emits FAILURE with FORBIDDEN_PRINCIPAL outcome") {
        // Tool requires admin scope; principal only has read.
        val f = fixture(requiredScopes = setOf("dmigrate:admin"))
        f.sut.toolsCall(ToolsCallParams(name = "test_tool")).get()
        val event = f.sink.recorded().single()
        event.outcome shouldBe AuditOutcome.FAILURE
        event.errorCode shouldBe ToolErrorCode.FORBIDDEN_PRINCIPAL
        event.toolName shouldBe "test_tool"
        event.principalId shouldBe PrincipalId("alice")
    }

    test("AP 6.20: a typed PolicyDenied propagates through audit as FAILURE with POLICY_DENIED") {
        val handler = ToolHandler {
            throw PolicyDeniedException(policyName = "p1", reason = "test")
        }
        val f = fixture(handler = handler)
        f.sut.toolsCall(ToolsCallParams(name = "test_tool")).get()
        val event = f.sink.recorded().single()
        event.outcome shouldBe AuditOutcome.FAILURE
        event.errorCode shouldBe ToolErrorCode.POLICY_DENIED
    }

    test("AP 6.20: a non-ApplicationException Throwable emits FAILURE with INTERNAL_AGENT_ERROR") {
        val handler = ToolHandler {
            throw IllegalStateException("boom")
        }
        val f = fixture(handler = handler)
        f.sut.toolsCall(ToolsCallParams(name = "test_tool")).get()
        val event = f.sink.recorded().single()
        event.outcome shouldBe AuditOutcome.FAILURE
        event.errorCode shouldBe ToolErrorCode.INTERNAL_AGENT_ERROR
    }

    test("AP 6.20: unknown tool stays JSON-RPC -32601 — no audit event") {
        // §6.8: protocol-method typos must NOT pollute the audit log.
        // Audit semantics record tool invocations, not name lookups.
        val f = fixture()
        io.kotest.assertions.throwables.shouldThrow<ExecutionException> {
            f.sut.toolsCall(ToolsCallParams(name = "definitely_not_a_tool")).get()
        }
        f.sink.recorded().size shouldBe 0
    }

    test("AP 6.20: without auditScope wired, dispatch behaves exactly as Phase B (no audit, no requestId leak)") {
        val descriptor = ToolDescriptor(
            name = "test_tool",
            title = "test_tool",
            description = "test tool",
            requiredScopes = setOf("dmigrate:read"),
            inputSchema = mapOf("type" to "object"),
            outputSchema = mapOf("type" to "object"),
        )
        val handler = ToolHandler {
            ToolCallOutcome.Success(
                content = listOf(ToolContent(type = "text", text = """{"ok":true}""", mimeType = "application/json")),
            )
        }
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            toolRegistry = ToolRegistry.builder().register(descriptor, handler).build(),
            initialPrincipal = PRINCIPAL,
            scopeMapping = mapOf("test_tool" to setOf("dmigrate:read")),
            // No auditScope!
        )
        val result = sut.toolsCall(ToolsCallParams(name = "test_tool")).get()
        result.isError shouldBe false
        result.content.single().text!! shouldContain "ok"
    }
})
