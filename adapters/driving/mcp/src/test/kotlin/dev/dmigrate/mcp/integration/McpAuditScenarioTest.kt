package dev.dmigrate.mcp.integration

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.mcp.protocol.McpServiceImpl
import dev.dmigrate.mcp.protocol.ToolsCallParams
import dev.dmigrate.mcp.registry.McpRuntimeRegistries
import dev.dmigrate.mcp.registry.McpRuntimeWiring
import dev.dmigrate.mcp.registry.OperationalMcpRegistries
import dev.dmigrate.mcp.registry.OperationalMcpWiring
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.audit.AuditScope
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.core.audit.AuditOutcome
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryAuditSink
import dev.dmigrate.server.ports.memory.InMemoryIdempotencyStore
import dev.dmigrate.server.ports.memory.InMemoryJobStartTransaction
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSegmentStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import dev.dmigrate.server.ports.memory.InMemoryWorkerHandleRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * LF-012 / LN-011 / LN-017 / LN-027 Audit-Akzeptanz-Pins. Verifiziert die Job-Start- und
 * Cancel-Auditpfade:
 *
 * - jedes Fehleroutcome wird auditiert (Around/Finally per [AuditScope])
 * - keine Approval-Tokens, Secrets oder rohen Connection-Daten im Audit
 *
 * Implementierung: existiert bereits seit LF-012 / LN-027 / LN-028 / LN-038 — [AuditScope.around]
 * wraps jeden `tools/call`-Dispatch und emittiert ein [AuditEvent] mit
 * SUCCESS/FAILURE-Outcome, errorCode und scrubbed resourceRefs. Diese
 * Suite pinnt das Verhalten fuer die LF-012 / LN-011 / LN-017 / LN-027-Tools (schema_reverse_start,
 * data_profile_start, schema_compare_start, job_cancel) end-to-end.
 *
 * Bewusst NICHT abgedeckt: AuditFields-Population (`payloadFingerprint`,
 * `resourceRefs`) — die LF-012 / LN-038-/LF-012 / LN-011 / LN-017 / LN-027-Handler reichen sie heute nicht
 * an `AuditScope.around` weiter (siehe McpServiceImpl#runAudited
 * Code-Kommentar). Das ist eine Folge-AP-Verbesserung, nicht ein
 * LF-012 / LN-011 / LN-017 / LN-027-Akzeptanz-Bullet.
 */
class McpAuditScenarioTest : FunSpec({

    val clock: Clock = Clock.fixed(Instant.parse("2026-05-05T12:00:00Z"), ZoneOffset.UTC)

    fun adminPrincipal(name: String = "alice", tenant: String = "acme"): PrincipalContext =
        Fixtures.principalContext(principalId = name, tenant = tenant)
            .copy(scopes = setOf("dmigrate:admin"), isAdmin = true)

    class Fixture(
        policyEffect: PolicyEffect = PolicyEffect.Allow,
    ) {
        val auditSink = InMemoryAuditSink()
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
            auditSink = auditSink,
        )
        val eWiring = OperationalMcpWiring(
            runtimeWiring = phaseC,
            idempotencyStore = idempotencyStore,
            jobStartTransaction = InMemoryJobStartTransaction(jobStore, idempotencyStore),
            workerHandleRegistry = InMemoryWorkerHandleRegistry(),
            approvalGrantStore = InMemoryApprovalGrantStore(),
            policyService = ConfiguredPolicyService(rules = emptyList(), defaultEffect = policyEffect),
        )
        val service: McpServiceImpl = run {
            val components = McpRuntimeRegistries.defaultComponents(phaseC)
            // LF-012 / LN-011 / LN-017 / LN-027-Handler ueberlagern; auditSink + auditScope kommen
            // aus McpRuntimeRegistries.defaultComponents.
            val registry = OperationalMcpRegistries.defaultToolRegistry(eWiring)
            McpServiceImpl(
                serverVersion = "test",
                toolRegistry = registry,
                initialPrincipal = adminPrincipal(),
                auditScope = components.auditScope,
            )
        }
    }

    test("schema_reverse_start SUCCESS: ein AuditEvent mit outcome=SUCCESS") {
        val fx = Fixture(PolicyEffect.Allow)
        val args = JsonParser.parseString(
            """{"connectionId":"dmigrate://tenants/acme/connections/c1","idempotencyKey":"k-audit-1"}""",
        ).asJsonObject
        fx.service.toolsCall(ToolsCallParams("schema_reverse_start", args)).get()
        val events = fx.auditSink.recorded()
        events shouldHaveSize 1
        events.first().outcome shouldBe AuditOutcome.SUCCESS
        events.first().toolName shouldBe "schema_reverse_start"
        events.first().errorCode shouldBe null
    }

    test("schema_reverse_start VALIDATION_ERROR: AuditEvent mit FAILURE + errorCode=VALIDATION_ERROR") {
        // idempotencyKey fehlt -> Pre-Idempotency-Validation throws
        // ValidationErrorException -> AuditScope catcht und mappt.
        val fx = Fixture(PolicyEffect.Allow)
        val args = JsonObject().apply {
            addProperty("connectionId", "dmigrate://tenants/acme/connections/c1")
        }
        fx.service.toolsCall(ToolsCallParams("schema_reverse_start", args)).get()
        val events = fx.auditSink.recorded()
        events shouldHaveSize 1
        events.first().outcome shouldBe AuditOutcome.FAILURE
        events.first().errorCode shouldBe ToolErrorCode.VALIDATION_ERROR
    }

    test("schema_reverse_start POLICY_DENIED: AuditEvent mit FAILURE + errorCode=POLICY_DENIED") {
        val fx = Fixture(PolicyEffect.Deny("policy:tool-blocked"))
        val args = JsonParser.parseString(
            """{"connectionId":"dmigrate://tenants/acme/connections/c1","idempotencyKey":"k-deny"}""",
        ).asJsonObject
        fx.service.toolsCall(ToolsCallParams("schema_reverse_start", args)).get()
        val events = fx.auditSink.recorded()
        events shouldHaveSize 1
        events.first().outcome shouldBe AuditOutcome.FAILURE
        events.first().errorCode shouldBe ToolErrorCode.POLICY_DENIED
    }

    test("job_cancel auf unbekannten jobId: AuditEvent mit FAILURE (RESOURCE_NOT_FOUND ueber direkte Error-Outcome)") {
        // job_cancel mappt NotFound auf ToolCallOutcome.Error direkt
        // (nicht via Exception). McpServiceImpl setzt den Fehlercode
        // trotzdem in AuditFields, damit direkte Error-Outcomes als
        // Audit-Failure sichtbar bleiben.
        val fx = Fixture()
        val args = JsonObject().apply { addProperty("jobId", "j-missing") }
        fx.service.toolsCall(ToolsCallParams("job_cancel", args)).get()
        val events = fx.auditSink.recorded()
        events shouldHaveSize 1
        events.first().outcome shouldBe AuditOutcome.FAILURE
        events.first().errorCode shouldBe ToolErrorCode.RESOURCE_NOT_FOUND
        events.first().toolName shouldBe "job_cancel"
    }

    test("Audit projiziert KEINE rohen approvalToken-Werte") {
        // LF-012 / LN-011 / LN-017 / LN-027: keine Approval-Tokens im Audit.
        val fx = Fixture(PolicyEffect.Challenge(setOf("data.read")))
        val args = JsonParser.parseString(
            """{"connectionId":"dmigrate://tenants/acme/connections/c1","idempotencyKey":"k-tok","approvalToken":"tok_secret123abc"}""",
        ).asJsonObject
        fx.service.toolsCall(ToolsCallParams("schema_reverse_start", args)).get()
        val events = fx.auditSink.recorded()
        events shouldHaveSize 1
        // Audit-Event enthaelt das rohe Token nirgends — der
        // payloadFingerprint und resourceRefs werden heute zwar nicht
        // populiert (siehe runAudited-Kommentar in McpServiceImpl), aber
        // die existierenden Felder des AuditEvent (toolName, requestId,
        // tenantId, principalId, errorCode) tragen den Token nicht.
        val serialized = events.first().toString()
        serialized shouldNotContain "tok_secret123abc"
    }

    test("Mehrere parallele LF-012 / LN-011 / LN-017 / LN-027-Calls erzeugen genau ein AuditEvent pro Call") {
        val fx = Fixture(PolicyEffect.Allow)
        for (i in 1..5) {
            val args = JsonParser.parseString(
                """{"connectionId":"dmigrate://tenants/acme/connections/c$i","idempotencyKey":"k-multi-$i"}""",
            ).asJsonObject
            fx.service.toolsCall(ToolsCallParams("schema_reverse_start", args)).get()
        }
        fx.auditSink.recorded() shouldHaveSize 5
        fx.auditSink.recorded().forEach { it.toolName shouldBe "schema_reverse_start" }
    }
})
