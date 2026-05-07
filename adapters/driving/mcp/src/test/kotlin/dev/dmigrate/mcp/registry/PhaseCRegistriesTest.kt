package dev.dmigrate.mcp.registry

import com.google.gson.JsonParser
import dev.dmigrate.mcp.schema.JsonSchemaDialect
import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.error.UnsupportedToolOperationException
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSegmentStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

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
 * Phase-C read-only tools per `ImpPlan-0.9.6-C.md` §3.1. AP 6.6.5
 * aligned the registry with the spec by collapsing the legacy
 * `artifact_upload_chunk`/`artifact_upload_complete` pair into a
 * single `artifact_upload` segment tool with implicit finalisation.
 */
private val PHASE_C_READ_ONLY_TOOLS: List<String> = listOf(
    "capabilities_list",
    "schema_validate",
    "schema_generate",
    "schema_compare",
    "job_status_get",
    "artifact_chunk_get",
    "artifact_upload_init",
    "artifact_upload",
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

    test("defaultToolRegistry wires policy upload init orchestrator in Phase-C path") {
        val sessionStore = InMemoryUploadSessionStore()
        val quotaStore = InMemoryQuotaStore()
        val wiring = PhaseCWiring(
            uploadSessionStore = sessionStore,
            uploadSegmentStore = InMemoryUploadSegmentStore(),
            artifactStore = InMemoryArtifactStore(),
            artifactContentStore = InMemoryArtifactContentStore(),
            schemaStore = InMemorySchemaStore(),
            jobStore = InMemoryJobStore(),
            quotaService = DefaultQuotaService(quotaStore) { Long.MAX_VALUE },
            limits = McpLimitsConfig(),
            clock = Clock.fixed(Instant.parse("2026-05-07T12:00:00Z"), ZoneOffset.UTC),
            policyService = ConfiguredPolicyService(rules = emptyList(), defaultEffect = PolicyEffect.Allow),
        )
        val registry = PhaseCRegistries.defaultToolRegistry(wiring)
        val args = JsonParser.parseString(
            """
            {
              "uploadIntent": "job_input",
              "artifactKind": "upload_input",
              "mimeType": "text/csv",
              "sizeBytes": 42,
              "checksumSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "segmentTotal": 1,
              "approvalKey": "appr-phase-c-init",
              "targetTable": "public.events"
            }
            """.trimIndent(),
        ).asJsonObject

        val outcome = registry.findHandler("artifact_upload_init")!!.handle(
            ToolCallContext("artifact_upload_init", args, PRINCIPAL),
        )
        outcome.shouldBeInstanceOf<ToolCallOutcome.Success>()
        val payload = JsonParser.parseString(outcome.content.single().text!!).asJsonObject
        val session = sessionStore.findById(TenantId("acme"), payload.get("uploadSessionId").asString)!!
        session.uploadIntent shouldBe ArtifactUploadInitHandler.INTENT_JOB_INPUT
        session.artifactKind shouldBe ArtifactKind.UPLOAD_INPUT
        session.approvalKey shouldBe "appr-phase-c-init"
        session.targetTable shouldBe "public.events"
    }
})
