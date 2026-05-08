package dev.dmigrate.mcp.registry

import com.google.gson.JsonParser
import dev.dmigrate.mcp.protocol.McpServiceImpl
import dev.dmigrate.mcp.protocol.ToolsCallParams
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryIdempotencyStore
import dev.dmigrate.server.ports.memory.InMemoryJobStartTransaction
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryProfileStore
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSegmentStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import dev.dmigrate.server.ports.memory.InMemoryWorkerHandleRegistry
import dev.dmigrate.server.ports.SchemaIndexEntry
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Phase G § 6 G.6 (G.6.g) — End-to-end-Smoke für die produktive
 * KI-Tool-Registry: dispatcht die drei Phase-G-Tools durch den
 * realen `tools/call`-Pfad in [McpServiceImpl] und stellt sicher,
 * dass kein UnsupportedToolHandler mehr im Wege steht.
 */
class AiMcpRegistriesTest : FunSpec({

    val tenant = Fixtures.tenant("acme")
    val now: Instant = Instant.parse("2026-05-07T12:00:00Z")
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    fun operationalWiring(policyDefault: PolicyEffect = PolicyEffect.Allow): OperationalMcpWiring {
        val jobStore = InMemoryJobStore()
        val idempotencyStore = InMemoryIdempotencyStore()
        val phaseC = McpRuntimeWiring(
            uploadSessionStore = InMemoryUploadSessionStore(),
            uploadSegmentStore = InMemoryUploadSegmentStore(),
            artifactStore = InMemoryArtifactStore(),
            artifactContentStore = InMemoryArtifactContentStore(),
            schemaStore = InMemorySchemaStore(),
            jobStore = jobStore,
            quotaService = DefaultQuotaService(InMemoryQuotaStore()) { Long.MAX_VALUE },
            limits = McpLimitsConfig(),
            clock = clock,
            profileStore = InMemoryProfileStore(),
        )
        return OperationalMcpWiring(
            runtimeWiring = phaseC,
            idempotencyStore = idempotencyStore,
            jobStartTransaction = InMemoryJobStartTransaction(jobStore, idempotencyStore),
            workerHandleRegistry = InMemoryWorkerHandleRegistry(),
            approvalGrantStore = InMemoryApprovalGrantStore(),
            policyService = ConfiguredPolicyService(emptyList(), policyDefault),
        )
    }

    fun aiWiring(policyDefault: PolicyEffect = PolicyEffect.Allow): AiMcpWiring {
        val phaseE = operationalWiring(policyDefault)
        // Schema seeden, damit procedure_transform_plan und
        // testdata_plan einen gültigen schemaRef finden.
        phaseE.runtimeWiring.schemaStore.register(
            SchemaIndexEntry(
                schemaId = "schema-1",
                tenantId = tenant,
                resourceUri = ServerResourceUri(tenant, ResourceKind.SCHEMAS, "schema-1"),
                artifactRef = "art-schema-1",
                displayName = "schema-1",
                createdAt = now,
                expiresAt = now.plusSeconds(3600),
            ),
        )
        return AiMcpWiring(operationalWiring = phaseE)
    }

    fun service(gWiring: AiMcpWiring, principal: PrincipalContext): McpServiceImpl {
        val registry = AiMcpRegistries.defaultToolRegistry(gWiring)
        return McpServiceImpl(
            serverVersion = "test",
            toolRegistry = registry,
            initialPrincipal = principal,
        )
    }

    fun aiPrincipal(): PrincipalContext = Fixtures.principalContext(principalId = "alice", tenant = "acme")
        .copy(scopes = setOf("dmigrate:ai:execute"), isAdmin = false)

    test("Plan §7.6 Wiring-Akzeptanz: procedure_transform_plan ist NICHT mehr UnsupportedToolHandler") {
        val gWiring = aiWiring()
        val svc = service(gWiring, aiPrincipal())
        val args = JsonParser.parseString(
            """
            {
              "approvalKey":"k-plan-1",
              "schemaRef":"dmigrate://tenants/acme/schemas/schema-1",
              "procedureName":"process_orders",
              "targetDialect":"POSTGRESQL"
            }
            """.trimIndent(),
        )
        val result = svc.toolsCall(ToolsCallParams("procedure_transform_plan", args)).get()
        result.isError shouldBe false
        val text = result.content.first().text!!
        text shouldContain "\"planRef\""
        // Negativer Sanity-Check: KEIN UnsupportedToolHandler-Envelope.
        text shouldNotContain "UNSUPPORTED_TOOL_OPERATION"
    }

    test("Plan §7.6 Wiring-Akzeptanz: procedure_transform_execute ist NICHT mehr UnsupportedToolHandler") {
        val gWiring = aiWiring()
        val svc = service(gWiring, aiPrincipal())

        // Erst einen Plan erzeugen, dann Execute-Tool aufrufen.
        val planArgs = JsonParser.parseString(
            """
            {
              "approvalKey":"k-plan-base",
              "schemaRef":"dmigrate://tenants/acme/schemas/schema-1",
              "procedureName":"process_orders",
              "targetDialect":"POSTGRESQL"
            }
            """.trimIndent(),
        )
        val planResult = svc.toolsCall(ToolsCallParams("procedure_transform_plan", planArgs)).get()
        planResult.isError shouldBe false
        val planArtifactId = JsonParser.parseString(planResult.content.first().text!!)
            .asJsonObject.get("planArtifactId").asString

        val execArgs = JsonParser.parseString(
            """
            {
              "approvalKey":"k-exec-1",
              "planArtifactId":"$planArtifactId",
              "targetDialect":"POSTGRESQL"
            }
            """.trimIndent(),
        )
        val execResult = svc.toolsCall(ToolsCallParams("procedure_transform_execute", execArgs)).get()
        execResult.isError shouldBe false
        val execText = execResult.content.first().text!!
        execText shouldContain "\"targetArtifactId\""
        execText shouldContain "\"targetResourceUri\""
    }

    test("Plan §7.6 Wiring-Akzeptanz: testdata_plan ist NICHT mehr UnsupportedToolHandler") {
        val gWiring = aiWiring()
        val svc = service(gWiring, aiPrincipal())
        val args = JsonParser.parseString(
            """
            {
              "approvalKey":"k-testdata-1",
              "schemaRef":"dmigrate://tenants/acme/schemas/schema-1",
              "targetDialect":"POSTGRESQL"
            }
            """.trimIndent(),
        )
        val result = svc.toolsCall(ToolsCallParams("testdata_plan", args)).get()
        result.isError shouldBe false
        val text = result.content.first().text!!
        text shouldContain "\"testdataPlanArtifactId\""
        text shouldContain "\"testdataPlanResourceUri\""
    }

    test("Follow-up AP 3: testdata_execute ist produktiv (kein UnsupportedToolHandler mehr)") {
        // Plan §3.2 Carve-out wurde durch Follow-up-Plan AP 3 geschlossen.
        // Der Handler ist jetzt produktiv; ein Aufruf ohne approvalKey
        // muss ein VALIDATION_ERROR mit `approvalKey`-Feld liefern, nicht
        // mehr UNSUPPORTED_TOOL_OPERATION.
        val gWiring = aiWiring()
        val svc = service(gWiring, aiPrincipal())
        val args = JsonParser.parseString("""{"planRef":"dmigrate://tenants/acme/artifacts/x"}""")
        val result = svc.toolsCall(ToolsCallParams("testdata_execute", args)).get()
        result.isError shouldBe true
        result.content.first().text!! shouldNotContain "UNSUPPORTED_TOOL_OPERATION"
    }

    test("Plan §6 G.6: Idempotenz haengt am gemeinsamen Orchestrator (selber Outcome-Store)") {
        // Beweis-by-construction: zwei Aufrufe an dasselbe Tool mit
        // gleichem approvalKey+payload → selber resultRef. Das laeuft
        // nur, wenn alle Handler dieselbe AiToolOutcomeStore-Instanz
        // sehen.
        val gWiring = aiWiring()
        val svc = service(gWiring, aiPrincipal())
        val args = JsonParser.parseString(
            """
            {
              "approvalKey":"k-replay",
              "schemaRef":"dmigrate://tenants/acme/schemas/schema-1",
              "targetDialect":"POSTGRESQL"
            }
            """.trimIndent(),
        )
        val first = svc.toolsCall(ToolsCallParams("testdata_plan", args)).get()
        val second = svc.toolsCall(ToolsCallParams("testdata_plan", args)).get()
        first.isError shouldBe false
        second.isError shouldBe false
        val refOne = JsonParser.parseString(first.content.first().text!!)
            .asJsonObject.get("testdataPlanResourceUri").asString
        val refTwo = JsonParser.parseString(second.content.first().text!!)
            .asJsonObject.get("testdataPlanResourceUri").asString
        refTwo shouldBe refOne
    }

    test("Plan §4.1 Default: ohne explizite Provider-Konfig laeuft NoOp") {
        // Sanity: das Default-AiMcpWiring traegt
        // DefaultAiProviderRegistry.noOpOnly() — ein Aufruf darf
        // OHNE externe Secrets/Netz funktionieren.
        val gWiring = aiWiring()
        val svc = service(gWiring, aiPrincipal())
        val args = JsonParser.parseString(
            """
            {
              "approvalKey":"k-noop-default",
              "schemaRef":"dmigrate://tenants/acme/schemas/schema-1",
              "targetDialect":"POSTGRESQL"
            }
            """.trimIndent(),
        )
        val result = svc.toolsCall(ToolsCallParams("testdata_plan", args)).get()
        result.isError shouldBe false
        val text = result.content.first().text!!
        text shouldContain "\"providerName\":\"noop\""
    }

    test("Plan §6 G.6: PolicyDenied trifft alle drei Handler einheitlich") {
        val gWiring = aiWiring(policyDefault = PolicyEffect.Deny("policy:denied"))
        val svc = service(gWiring, aiPrincipal())
        val args = JsonParser.parseString(
            """
            {
              "approvalKey":"k-deny",
              "schemaRef":"dmigrate://tenants/acme/schemas/schema-1",
              "targetDialect":"POSTGRESQL"
            }
            """.trimIndent(),
        )
        val planResult = svc.toolsCall(ToolsCallParams("testdata_plan", args)).get()
        planResult.isError shouldBe true
        planResult.content.first().text!! shouldContain "POLICY_DENIED"
    }

    test("AiMcpWiring liefert sane Defaults (Plan §3.2 + §4.1): NoOp-Provider, In-Process-Stores") {
        val gWiring = AiMcpWiring(operationalWiring = operationalWiring())
        // Defaults sind die In-Process-Implementierungen; type-check
        // statt toString-prefix-Hoffen, weil internal data classes
        // ihren `simpleName` nicht garantieren.
        (gWiring.aiToolOutcomeStore is InProcessAiToolOutcomeStore) shouldBe true
        (gWiring.aiArtifactMetadataStore is InProcessAiArtifactMetadataStore) shouldBe true
    }

    test("AI defaultComponents erweitert capabilities_list um Provider- und Prompt-Discovery") {
        val gWiring = aiWiring()
        val components = AiMcpRegistries.defaultComponents(gWiring)
        val capabilities = components.capabilitiesProvider()

        val asJson = JsonParser.parseString(
            com.google.gson.GsonBuilder().disableHtmlEscaping().create().toJson(capabilities),
        ).asJsonObject
        val ai = asJson.getAsJsonObject("ai")
        ai.get("providerQuotaDimension").asString shouldBe "PROVIDER_CALLS"
        val providers = ai.getAsJsonArray("providers")
        providers.size() shouldBe 1
        providers[0].asJsonObject.get("providerId").asString shouldBe "noop"
        providers[0].asJsonObject.toString() shouldNotContain "secret"

        val prompts = asJson.getAsJsonArray("prompts").map {
            it.asJsonObject.get("name").asString
        }.toSet()
        prompts shouldBe setOf("procedure_analysis", "procedure_transformation", "testdata_planning")

        val registryHandler = components.toolRegistry.findHandler("capabilities_list")
        val outcome = registryHandler!!.handle(ToolCallContext("capabilities_list", null, aiPrincipal()))
        outcome as ToolCallOutcome.Success
        val wireJson = JsonParser.parseString(outcome.content.single().text!!).asJsonObject
        wireJson.getAsJsonObject("ai").getAsJsonArray("providers")[0]
            .asJsonObject.get("providerId").asString shouldBe "noop"
    }
})
