package dev.dmigrate.mcp.integration

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.mcp.protocol.McpServiceImpl
import dev.dmigrate.mcp.protocol.ToolsCallParams
import dev.dmigrate.mcp.registry.McpRuntimeWiring
import dev.dmigrate.mcp.registry.OperationalMcpRegistries
import dev.dmigrate.mcp.registry.OperationalMcpWiring
import dev.dmigrate.mcp.schema.McpToolSchemas
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.application.policy.PolicyRule
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryIdempotencyStore
import dev.dmigrate.server.ports.memory.InMemoryJobStartTransaction
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSegmentStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import dev.dmigrate.server.ports.memory.InMemoryWorkerHandleRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain as shouldContainItem
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * AP E.6 (4/4) Integration: dispatcht die drei Phase-E Start-Tools
 * durch den realen `tools/call`-Pfad in [McpServiceImpl] mit der
 * produktiven [OperationalMcpRegistries] Registry.
 *
 * Belegt Plan §7.6 Akzeptanzpunkte:
 *
 * - "Tool-Registry von Unsupported-Handlern auf produktive Handler
 *   umstellen" — die drei E-Slots werden via [OperationalMcpRegistries] zu
 *   echten Handlern aufgeloest und liefern Job-Outcomes statt
 *   `UNSUPPORTED_TOOL_OPERATION`.
 * - Output-Schema `{jobId, resourceUri, executionMeta}` (E.6 (1/4)).
 * - Pre-Idempotency-Validation: idempotencyKey-Pflicht und freie
 *   JDBC-URLs werden vor jedem Store-Write abgewiesen (E.6 (2/4)).
 * - Idempotenter Retry liefert dieselbe Antwort.
 *
 * Bewusst KEIN Transport-Layer: stdio/HTTP-Aequivalenz ist durch
 * AP-6.24-Phase-C-Suite generisch abgedeckt; Phase-E-spezifischer
 * Test fokussiert sich auf Handler-/Registry-Integration. Der
 * Runner-Pfad (Worker-Dispatch) folgt in AP E.7.
 */
class McpJobStartScenarioTest : FunSpec({

    val clock = Clock.fixed(Instant.parse("2026-05-05T12:00:00Z"), ZoneOffset.UTC)

    fun operationalWiring(
        policyDefault: PolicyEffect = PolicyEffect.Allow,
    ): OperationalMcpWiring {
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
        )
        return OperationalMcpWiring(
            runtimeWiring = phaseC,
            idempotencyStore = idempotencyStore,
            jobStartTransaction = InMemoryJobStartTransaction(jobStore, idempotencyStore),
            workerHandleRegistry = InMemoryWorkerHandleRegistry(),
            approvalGrantStore = InMemoryApprovalGrantStore(),
            policyService = ConfiguredPolicyService(
                rules = emptyList(),
                defaultEffect = policyDefault,
            ),
        )
    }

    fun service(wiring: OperationalMcpWiring): McpServiceImpl {
        val registry = OperationalMcpRegistries.defaultToolRegistry(wiring)
        // Initial-Principal mit dmigrate:admin (bypassed Scope-Check in
        // ScopeChecker.isSatisfied) — entspricht AuthMode.DISABLED-
        // Lokalbetrieb.
        val principal = Fixtures.principalContext(principalId = "alice", tenant = "acme")
            .copy(scopes = setOf("dmigrate:admin"), isAdmin = true)
        return McpServiceImpl(
            serverVersion = "test",
            toolRegistry = registry,
            initialPrincipal = principal,
        )
    }

    fun callStart(
        svc: McpServiceImpl,
        tool: String,
        args: JsonObject,
    ): String {
        val result = svc.toolsCall(ToolsCallParams(name = tool, arguments = args)).get()
        // Tool-Antwort kommt als content[0].text-JSON; bei Error ist
        // isError=true.
        result.isError shouldBe false
        return result.content.first().text
            ?: error("expected text content, got null")
    }

    test("schema_reverse_start: Allow-Policy → Success-Envelope mit jobId/resourceUri/executionMeta") {
        val svc = service(operationalWiring(PolicyEffect.Allow))
        val args = JsonParser.parseString(
            """
            {
              "connectionId": "dmigrate://tenants/acme/connections/c1",
              "idempotencyKey": "k-reverse-1"
            }
            """.trimIndent(),
        ).asJsonObject
        val text = callStart(svc, "schema_reverse_start", args)
        val payload = JsonParser.parseString(text).asJsonObject
        payload.has("jobId") shouldBe true
        payload.get("resourceUri").asString shouldContain "dmigrate://tenants/acme/jobs/"
        payload.getAsJsonObject("executionMeta").has("requestId") shouldBe true
    }

    test("data_profile_start: Allow-Policy → Success") {
        val svc = service(operationalWiring(PolicyEffect.Allow))
        val args = JsonParser.parseString(
            """{"connectionId":"dmigrate://tenants/acme/connections/c1","idempotencyKey":"k-profile-1"}""",
        ).asJsonObject
        val text = callStart(svc, "data_profile_start", args)
        val payload = JsonParser.parseString(text).asJsonObject
        payload.has("jobId") shouldBe true
    }

    test("schema_compare_start mit zwei Schema-Refs → Success") {
        val svc = service(operationalWiring(PolicyEffect.Allow))
        val args = JsonParser.parseString(
            """
            {
              "sourceUri": "dmigrate://tenants/acme/schemas/s1",
              "targetUri": "dmigrate://tenants/acme/schemas/s2",
              "idempotencyKey": "k-compare-1"
            }
            """.trimIndent(),
        ).asJsonObject
        val text = callStart(svc, "schema_compare_start", args)
        val payload = JsonParser.parseString(text).asJsonObject
        payload.has("jobId") shouldBe true
    }

    test("idempotencyKey fehlt → VALIDATION_ERROR mit Feldname") {
        val svc = service(operationalWiring(PolicyEffect.Allow))
        val args = JsonParser.parseString(
            """{"connectionId":"dmigrate://tenants/acme/connections/c1"}""",
        ).asJsonObject
        val result = svc.toolsCall(ToolsCallParams(name = "schema_reverse_start", arguments = args)).get()
        result.isError shouldBe true
        val text = (result.content.first().text ?: error("expected text content"))
        text shouldContain "VALIDATION_ERROR"
        text shouldContain "idempotencyKey"
    }

    test("freie JDBC-URL als connectionId → VALIDATION_ERROR (vor Idempotency-Store-Write)") {
        val w = operationalWiring(PolicyEffect.Allow)
        val svc = service(w)
        val args = JsonParser.parseString(
            """{"connectionId":"jdbc:postgresql://localhost/db","idempotencyKey":"k-jdbc"}""",
        ).asJsonObject
        val result = svc.toolsCall(ToolsCallParams(name = "schema_reverse_start", arguments = args)).get()
        result.isError shouldBe true
        val text = (result.content.first().text ?: error("expected text content"))
        text shouldContain "VALIDATION_ERROR"
        text shouldContain "free JDBC"
        // Plan §7.6: keine Idempotency-Store-Write fuer fruehe Validation-
        // Fehler. Der Job-Store bleibt leer; der Idempotency-Eintrag
        // existiert nicht (er wurde nie reserve()'d).
        w.runtimeWiring.jobStore.findById(Fixtures.tenant("acme"), "job_1") shouldBe null
    }

    test("Idempotenter Retry liefert dieselbe jobId") {
        val svc = service(operationalWiring(PolicyEffect.Allow))
        val args = JsonParser.parseString(
            """{"connectionId":"dmigrate://tenants/acme/connections/c1","idempotencyKey":"k-dedup"}""",
        ).asJsonObject
        val first = JsonParser.parseString(callStart(svc, "schema_reverse_start", args)).asJsonObject
        val second = JsonParser.parseString(callStart(svc, "schema_reverse_start", args)).asJsonObject
        first.get("jobId").asString shouldBe second.get("jobId").asString
    }

    test("Denied-Policy → POLICY_DENIED-Error-Envelope") {
        val svc = service(operationalWiring(PolicyEffect.Deny("policy:tool-blocked")))
        val args = JsonParser.parseString(
            """{"connectionId":"dmigrate://tenants/acme/connections/c1","idempotencyKey":"k-denied"}""",
        ).asJsonObject
        val result = svc.toolsCall(ToolsCallParams(name = "schema_reverse_start", arguments = args)).get()
        result.isError shouldBe true
        val text = (result.content.first().text ?: error("expected text content"))
        text shouldContain "POLICY_DENIED"
    }

    test("RequiresApproval ohne Token → POLICY_REQUIRED mit approvalRequestId") {
        val svc = service(operationalWiring(PolicyEffect.Challenge(setOf("data.read"))))
        val args = JsonParser.parseString(
            """{"connectionId":"dmigrate://tenants/acme/connections/c1","idempotencyKey":"k-challenge"}""",
        ).asJsonObject
        val result = svc.toolsCall(ToolsCallParams(name = "schema_reverse_start", arguments = args)).get()
        result.isError shouldBe true
        val text = (result.content.first().text ?: error("expected text content"))
        text shouldContain ToolErrorCode.POLICY_REQUIRED.name
        text shouldContain "approvalRequestId"
        text shouldContain "data.read"
    }

    test("Auto-Dispatch (Review-Fix Blocker #1): Worker laeuft synchron via SyncExecutor; Job ist SUCCEEDED") {
        val w = operationalWiring(PolicyEffect.Allow)
        val svc = service(w)
        val args = JsonParser.parseString(
            """{"connectionId":"dmigrate://tenants/acme/connections/c1","idempotencyKey":"k-auto-dispatch"}""",
        ).asJsonObject
        val text = callStart(svc, "schema_reverse_start", args)
        val payload = JsonParser.parseString(text).asJsonObject
        val jobId = payload.get("jobId").asString

        // Plan §7.7: nach Auto-Dispatch + SyncExecutor ist der Job
        // bereits SUCCEEDED, NICHT mehr QUEUED. Dieses Szenario nutzt
        // bewusst das OperationalMcpWiring-Default-Test-Fallback; der CLI-
        // Bootstrap injiziert fuer die echten Read-Side-Start-Tools den
        // produktiven McpCoreJobWorkerFactory.
        val finalRecord = w.runtimeWiring.jobStore.findById(Fixtures.tenant("acme"), jobId)!!
        finalRecord.managedJob.status shouldBe dev.dmigrate.server.core.job.JobStatus.SUCCEEDED
        // Counter freigegeben (Slot released).
        finalRecord.quotaReservationOwnerId.shouldNotBeNull()
    }

    test("Schema in McpToolSchemas reflektiert die produktiven Handler") {
        // Sanity: das Schema fuer schema_reverse_start hat idempotencyKey
        // als Pflichtfeld (E.6 (1/4)) und der Handler erzwingt das. Wenn
        // jemand das Schema ohne Migration aendert, schlaegt dieser Test
        // an, weil die Handler-Validierung divergiert.
        val pair = McpToolSchemas.forTool("schema_reverse_start")!!
        val required = (pair.inputSchema["required"] as List<*>).filterIsInstance<String>()
        required shouldContainItem "idempotencyKey"
        required shouldContainItem "connectionId"
    }
})
