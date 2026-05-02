package dev.dmigrate.mcp.registry

import dev.dmigrate.mcp.schema.JsonSchemaDialect
import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.server.application.error.UnsupportedToolOperationException
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
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

private val NOOP_HANDLER: ToolHandler = ToolHandler { _ -> ToolCallOutcome.Success(emptyList()) }

/**
 * Phase-C read-only tools per `ImpPlan-0.9.6-C.md` §3.1 that are
 * already part of the Phase-B scope mapping. `artifact_upload`
 * (singular per §3.1) is intentionally absent — the Phase-B registry
 * still uses `artifact_upload_chunk` / `artifact_upload_complete`;
 * AP 6.6.5 aligns the names before AP 6.7-6.10 ship the handlers.
 */
private val PHASE_C_READ_ONLY_TOOLS: List<String> = listOf(
    "capabilities_list",
    "schema_validate",
    "schema_generate",
    "schema_compare",
    "job_status_get",
    "artifact_chunk_get",
    "artifact_upload_init",
    "artifact_upload_abort",
)

class PhaseCRegistriesTest : FunSpec({

    test("with no overrides the registry equals the Phase-B baseline") {
        val phaseC = PhaseCRegistries.toolRegistry()
        val phaseB = PhaseBRegistries.toolRegistry()
        phaseC.names() shouldBe phaseB.names()
        for (name in phaseB.names()) {
            phaseC.find(name) shouldBe phaseB.find(name)
        }
    }

    test("every Phase-C read-only tool is visible on the default registry (§6.1 acceptance)") {
        val registry = PhaseCRegistries.toolRegistry()
        val names = registry.names().toSet()
        for (tool in PHASE_C_READ_ONLY_TOOLS) {
            withClue(tool) { (tool in names) shouldBe true }
        }
    }

    test("handler overrides replace UnsupportedToolHandler and preserve descriptor metadata") {
        val custom: ToolHandler = ToolHandler { _ ->
            ToolCallOutcome.Success(listOf(ToolContent(type = "text", text = "stub")))
        }
        val registry = PhaseCRegistries.toolRegistry(
            handlerOverrides = mapOf("schema_validate" to custom),
        )

        val handler = registry.findHandler("schema_validate")!!
        handler shouldBeSameInstanceAs custom
        handler.handle(ToolCallContext("schema_validate", null, PRINCIPAL))
            .shouldBeInstanceOf<ToolCallOutcome.Success>()

        val descriptor = registry.find("schema_validate")!!
        descriptor.requiredScopes shouldBe setOf("dmigrate:read")
        descriptor.inputSchema["\$schema"] shouldBe JsonSchemaDialect.SCHEMA_URI
    }

    test("non-overridden tools keep dispatching to UnsupportedToolHandler") {
        val registry = PhaseCRegistries.toolRegistry(
            handlerOverrides = mapOf("schema_validate" to NOOP_HANDLER),
        )
        val handler = registry.findHandler("schema_generate")!!
        shouldThrow<UnsupportedToolOperationException> {
            handler.handle(ToolCallContext("schema_generate", null, PRINCIPAL))
        }
    }

    test("overrides for unregistered tool names are rejected at build time") {
        shouldThrow<IllegalStateException> {
            PhaseCRegistries.toolRegistry(
                handlerOverrides = mapOf("schema_validate_v999" to NOOP_HANDLER),
            )
        }
    }

    test("overrides for MCP-protocol method names are rejected at build time") {
        // Protocol methods (`tools/list`, `resources/read`, ...) are
        // dispatched by the protocol layer, not the tool registry —
        // an override would silently no-op (§12.16).
        val ex = shouldThrow<IllegalStateException> {
            PhaseCRegistries.toolRegistry(
                handlerOverrides = mapOf("tools/list" to NOOP_HANDLER),
            )
        }
        ex.message!! shouldContain "MCP-protocol methods"
    }

    test("custom scopeMapping is forwarded to the underlying Phase-B builder") {
        val custom = mapOf(
            "capabilities_list" to setOf("dmigrate:read"),
            "schema_validate" to setOf("dmigrate:admin"),
        )
        val registry = PhaseCRegistries.toolRegistry(scopeMapping = custom)
        registry.find("schema_validate")!!.requiredScopes shouldBe setOf("dmigrate:admin")
    }

    test("the default scope mapping declares every Phase-C read-only tool") {
        // Defense-in-depth: if the default map ever drops a Phase-C
        // tool, AP 6.1 visibility breaks — fail here so the cause is
        // obvious instead of surfacing as a missing `tools/list` entry.
        for (tool in PHASE_C_READ_ONLY_TOOLS) {
            withClue(tool) {
                (tool in McpServerConfig.DEFAULT_SCOPE_MAPPING.keys) shouldBe true
            }
        }
    }
})
