package dev.dmigrate.mcp.protocol

import dev.dmigrate.mcp.prompts.DefaultPromptRegistry
import dev.dmigrate.server.application.audit.prompt.DefaultPromptHygieneService
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.time.Instant

/**
 * LF-017 / LF-024 / LN-030 / LN-031 § 6 G.7 — End-to-end Akzeptanz auf der McpServiceImpl-
 * Schicht: capabilities.prompts wird im initialize ausgewiesen,
 * prompts/list und prompts/get sind durch dmigrate:read gated und
 * mappen Fehler auf JSON-RPC + dmigrateCode.
 */
class McpServiceImplPromptsTest : FunSpec({

    val tenant = TenantId("acme")
    val alice = PrincipalId("alice")
    val readPrincipal = PrincipalContext(
        principalId = alice,
        homeTenantId = tenant,
        effectiveTenantId = tenant,
        allowedTenantIds = setOf(tenant),
        scopes = setOf("dmigrate:read"),
        isAdmin = false,
        auditSubject = "alice",
        authSource = AuthSource.SERVICE_ACCOUNT,
        expiresAt = Instant.MAX,
    )

    fun service(withPromptRegistry: Boolean): McpServiceImpl =
        if (withPromptRegistry) {
            McpServiceImpl(
                serverVersion = "test",
                initialPrincipal = readPrincipal,
                promptRegistry = DefaultPromptRegistry.mandatory(),
                promptHygieneService = DefaultPromptHygieneService(),
            )
        } else {
            McpServiceImpl(
                serverVersion = "test",
                initialPrincipal = readPrincipal,
            )
        }

    test("LF-017 / LF-024 / LN-030 / LN-031 Akzeptanz: initialize enthaelt capabilities.prompts wenn Registry gewired") {
        val svc = service(withPromptRegistry = true)
        val params = InitializeParams(
            protocolVersion = McpProtocol.MCP_PROTOCOL_VERSION,
        )
        val result = svc.initialize(params).get()
        result.capabilities.prompts shouldBe mapOf("listChanged" to false)
    }

    test("Ohne Registry-Wiring: initialize laesst capabilities.prompts null") {
        val svc = service(withPromptRegistry = false)
        val params = InitializeParams(
            protocolVersion = McpProtocol.MCP_PROTOCOL_VERSION,
        )
        val result = svc.initialize(params).get()
        result.capabilities.prompts shouldBe null
    }

    test("prompts/list ueber Service-Layer liefert die drei Pflichtprompts") {
        val svc = service(withPromptRegistry = true)
        val result = svc.promptsList(PromptsListParams()).get()
        result.prompts.map { it.name } shouldBe listOf(
            "procedure_analysis", "procedure_transformation", "testdata_planning",
        )
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: prompts/list ohne Registry-Wiring -> MethodNotFound") {
        val svc = service(withPromptRegistry = false)
        val ex = shouldThrow<Exception> {
            svc.promptsList(PromptsListParams()).get()
        }
        // CompletableFuture.get() wraps in ExecutionException;
        // ResponseErrorException ist der cause.
        val rex = (ex.cause ?: ex) as ResponseErrorException
        rex.responseError.code shouldBe ResponseErrorCode.MethodNotFound.value
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: unbekannter Prompt -> JSON-RPC mit dmigrateCode=RESOURCE_NOT_FOUND") {
        val svc = service(withPromptRegistry = true)
        val ex = shouldThrow<Exception> {
            svc.promptsGet(PromptsGetParams(name = "no_such_prompt", arguments = null)).get()
        }
        val rex = (ex.cause ?: ex) as ResponseErrorException
        rex.responseError.code shouldBe ResponseErrorCode.InvalidParams.value
        @Suppress("UNCHECKED_CAST")
        val data = rex.responseError.data as Map<String, Any?>
        data["dmigrateCode"] shouldBe "RESOURCE_NOT_FOUND"
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: ungueltige Argumente -> JSON-RPC mit dmigrateCode=VALIDATION_ERROR") {
        val svc = service(withPromptRegistry = true)
        val ex = shouldThrow<Exception> {
            svc.promptsGet(
                PromptsGetParams(
                    name = "testdata_planning",
                    // schemaRef fehlt — ist required.
                    arguments = mapOf("targetDialect" to "POSTGRESQL"),
                ),
            ).get()
        }
        val rex = (ex.cause ?: ex) as ResponseErrorException
        @Suppress("UNCHECKED_CAST")
        val data = rex.responseError.data as Map<String, Any?>
        data["dmigrateCode"] shouldBe "VALIDATION_ERROR"
        rex.responseError.message shouldContain "schemaRef"
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: Hygiene-Verletzung -> JSON-RPC mit dmigrateCode=PROMPT_HYGIENE_BLOCKED") {
        val svc = service(withPromptRegistry = true)
        val ex = shouldThrow<Exception> {
            svc.promptsGet(
                PromptsGetParams(
                    name = "testdata_planning",
                    arguments = mapOf(
                        "schemaRef" to "dmigrate://tenants/acme/schemas/x",
                        "targetDialect" to "POSTGRESQL",
                        "rulesSummary" to "use api_key=sk_AKIA1234567890ABCDEF",
                    ),
                ),
            ).get()
        }
        val rex = (ex.cause ?: ex) as ResponseErrorException
        @Suppress("UNCHECKED_CAST")
        val data = rex.responseError.data as Map<String, Any?>
        data["dmigrateCode"] shouldBe "PROMPT_HYGIENE_BLOCKED"
        // LF-017 / LF-024 / LN-030 / LN-031 Akzeptanz: kein Secret im public message.
        rex.responseError.message.contains("AKIA") shouldBe false
    }

    test("LF-017 / LF-024 / LN-030 / LN-031: prompts/list/get verlangen dmigrate:read") {
        val noScopePrincipal = readPrincipal.copy(scopes = emptySet())
        val svc = McpServiceImpl(
            serverVersion = "test",
            initialPrincipal = noScopePrincipal,
            promptRegistry = DefaultPromptRegistry.mandatory(),
            promptHygieneService = DefaultPromptHygieneService(),
        )
        val ex = shouldThrow<Exception> {
            svc.promptsList(PromptsListParams()).get()
        }
        val rex = (ex.cause ?: ex) as ResponseErrorException
        // Scope-Verletzung -> -32600 InvalidRequest mit dmigrateCode=
        // FORBIDDEN_PRINCIPAL (vgl. resources/list-Pfad).
        rex.responseError.code shouldBe ResponseErrorCode.InvalidRequest.value
    }
})
