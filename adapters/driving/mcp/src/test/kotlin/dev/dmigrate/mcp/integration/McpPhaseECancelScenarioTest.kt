package dev.dmigrate.mcp.integration

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.mcp.protocol.McpServiceImpl
import dev.dmigrate.mcp.protocol.ToolsCallParams
import dev.dmigrate.mcp.registry.PhaseCWiring
import dev.dmigrate.mcp.registry.PhaseERegistries
import dev.dmigrate.mcp.registry.PhaseEWiring
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.job.ManagedJob
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.JobWorker
import dev.dmigrate.server.ports.JobWorkerOutcome
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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * AP E.8 (3/3) Integration: dispatcht `job_cancel` durch
 * [McpServiceImpl] mit produktiver [PhaseERegistries] und prueft die
 * Plan-§7.8-Akzeptanz-Pins, die nur durch den vollen Wire-Pfad
 * sichtbar werden:
 *
 * - Wire-Shape `{jobId, status, terminal, executionMeta}` (Plan §7.6 +
 *   §5.6) fuer terminale und Pending-Cancel-Faelle.
 * - No-oracle RESOURCE_NOT_FOUND ohne resourceUri-Echo (Plan §5.6 line
 *   661-662).
 * - Dispatcher-Barriere: nach `queued -> cancelled`-CAS startet ein
 *   spaeterer dispatch keinen Worker (Plan §7.8 line 1213-1214 +
 *   AP E.7 (1/6) DISPATCH_RACE).
 * - Idempotenter Retry (Plan §7.8 line 1252-1254): zweiter
 *   `job_cancel` mit gleichem Reason aendert den persistierten Reason
 *   nicht.
 *
 * Bewusst nicht abgedeckt: Scope-Check (Phase-B upstream im Service-
 * Layer); Audit-Event-Korrelation (AP E.10).
 */
class McpPhaseECancelScenarioTest : FunSpec({

    val clock: Clock = Clock.fixed(Instant.parse("2026-05-05T12:00:00Z"), ZoneOffset.UTC)

    fun phaseEWiring(): PhaseEWiring {
        val jobStore = InMemoryJobStore()
        val idempotencyStore = InMemoryIdempotencyStore()
        val phaseC = PhaseCWiring(
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
        return PhaseEWiring(
            phaseCWiring = phaseC,
            idempotencyStore = idempotencyStore,
            jobStartTransaction = InMemoryJobStartTransaction(jobStore, idempotencyStore),
            workerHandleRegistry = InMemoryWorkerHandleRegistry(),
            approvalGrantStore = InMemoryApprovalGrantStore(),
            policyService = ConfiguredPolicyService(rules = emptyList(), defaultEffect = PolicyEffect.Allow),
        )
    }

    fun service(wiring: PhaseEWiring, principal: PrincipalContext): McpServiceImpl {
        val registry = PhaseERegistries.defaultToolRegistry(wiring)
        return McpServiceImpl(
            serverVersion = "test",
            toolRegistry = registry,
            initialPrincipal = principal,
        )
    }

    fun adminPrincipal(name: String = "alice", tenant: String = "acme"): PrincipalContext =
        Fixtures.principalContext(principalId = name, tenant = tenant)
            .copy(scopes = setOf("dmigrate:admin"), isAdmin = true)

    fun seedQueued(wiring: PhaseEWiring, jobId: String = "j1", owner: String = "alice"): JobRecord {
        val tenant = Fixtures.tenant("acme")
        val record = JobRecord(
            managedJob = ManagedJob(
                jobId = jobId,
                operation = "schema_reverse",
                status = JobStatus.QUEUED,
                createdAt = clock.instant(),
                updatedAt = clock.instant(),
                expiresAt = clock.instant().plusSeconds(86_400),
                createdBy = owner,
            ),
            tenantId = tenant,
            ownerPrincipalId = Fixtures.principal(owner),
            visibility = JobVisibility.OWNER,
            resourceUri = ServerResourceUri(tenant, ResourceKind.JOBS, jobId),
        )
        wiring.phaseCWiring.jobStore.save(record)
        return record
    }

    fun argsObj(jobId: String? = null, resourceUri: String? = null, reason: String? = null): JsonObject =
        JsonObject().apply {
            if (jobId != null) addProperty("jobId", jobId)
            if (resourceUri != null) addProperty("resourceUri", resourceUri)
            if (reason != null) addProperty("reason", reason)
        }

    test("QUEUED-Cancel via tools/call: status=CANCELLED, terminal=true, kein Ack-Pending") {
        val w = phaseEWiring()
        seedQueued(w)
        val principal = adminPrincipal()
            .copy(scopes = setOf("dmigrate:admin"), isAdmin = true)
        val svc = service(w, principal)

        val result = svc.toolsCall(ToolsCallParams("job_cancel", argsObj(jobId = "j1", reason = "user"))).get()
        result.isError shouldBe false
        val text = result.content.first().text!!
        val payload = JsonParser.parseString(text).asJsonObject
        payload.get("status").asString shouldBe "CANCELLED"
        payload.get("terminal").asBoolean shouldBe true
        val em = payload.getAsJsonObject("executionMeta")
        em.get("cancelRequested").asBoolean shouldBe true
        em.has("cancelAckPending") shouldBe false
        em.get("cancelRequestedReason").asString shouldBe "user"
    }

    test("RUNNING-Cancel via tools/call: status=RUNNING, terminal=false, cancelAckPending=true, retryAfter=2") {
        val w = phaseEWiring()
        // Job direkt RUNNING anlegen + Worker-Handle registrieren.
        val record = seedQueued(w)
        w.phaseCWiring.jobStore.transitionStatus(
            tenantId = record.tenantId,
            jobId = "j1",
            allowedFromStatuses = setOf(JobStatus.QUEUED),
        ) { it.copy(status = JobStatus.RUNNING) }
        w.workerHandleRegistry.register("j1", CancellationTokenSource.create())

        val principal = adminPrincipal()
        val svc = service(w, principal)

        val result = svc.toolsCall(ToolsCallParams("job_cancel", argsObj(jobId = "j1", reason = "user"))).get()
        result.isError shouldBe false
        val payload = JsonParser.parseString(result.content.first().text!!).asJsonObject
        payload.get("status").asString shouldBe "RUNNING"
        payload.get("terminal").asBoolean shouldBe false
        val em = payload.getAsJsonObject("executionMeta")
        em.get("cancelRequested").asBoolean shouldBe true
        em.get("cancelAckPending").asBoolean shouldBe true
        em.get("retryAfter").asLong shouldBe 2L
    }

    test("Unbekannter jobId via tools/call: Error-Envelope RESOURCE_NOT_FOUND ohne URI-Echo") {
        val w = phaseEWiring()
        val principal = adminPrincipal()
        val svc = service(w, principal)

        val result = svc.toolsCall(ToolsCallParams("job_cancel", argsObj(jobId = "j-missing"))).get()
        result.isError shouldBe true
        val text = result.content.first().text!!
        text shouldContain ToolErrorCode.RESOURCE_NOT_FOUND.name
        // Plan §5.6 line 661-662 no-oracle: keine ID-Echo im Envelope.
        text shouldNotContain "j-missing"
    }

    test("Cross-tenant resourceUri: TENANT_SCOPE_DENIED mit targetTenant-Detail") {
        val w = phaseEWiring()
        // Job in Fremd-Tenant.
        val record = JobRecord(
            managedJob = ManagedJob(
                jobId = "j-other", operation = "schema_reverse",
                status = JobStatus.QUEUED,
                createdAt = clock.instant(), updatedAt = clock.instant(),
                expiresAt = clock.instant().plusSeconds(86_400),
                createdBy = "bob",
            ),
            tenantId = Fixtures.tenant("initech"),
            ownerPrincipalId = Fixtures.principal("bob"),
            visibility = JobVisibility.OWNER,
            resourceUri = ServerResourceUri(Fixtures.tenant("initech"), ResourceKind.JOBS, "j-other"),
        )
        w.phaseCWiring.jobStore.save(record)

        val principal = adminPrincipal()
        val svc = service(w, principal)
        val result = svc.toolsCall(
            ToolsCallParams("job_cancel", argsObj(resourceUri = "dmigrate://tenants/initech/jobs/j-other")),
        ).get()
        result.isError shouldBe true
        val text = result.content.first().text!!
        text shouldContain ToolErrorCode.TENANT_SCOPE_DENIED.name
        text shouldContain "initech"
    }

    test("Validation: beide jobId UND resourceUri → VALIDATION_ERROR") {
        val w = phaseEWiring()
        seedQueued(w)
        val principal = adminPrincipal()
        val svc = service(w, principal)
        val result = svc.toolsCall(
            ToolsCallParams(
                "job_cancel",
                argsObj(jobId = "j1", resourceUri = "dmigrate://tenants/acme/jobs/j1"),
            ),
        ).get()
        result.isError shouldBe true
        result.content.first().text!! shouldContain "VALIDATION_ERROR"
    }

    test("Idempotenter Retry: zweiter cancel mit gleichem Reason aendert persisted Reason nicht (Plan §7.2)") {
        val w = phaseEWiring()
        seedQueued(w)
        // RUNNING setzen + Worker registrieren (durable Phase).
        w.phaseCWiring.jobStore.transitionStatus(
            tenantId = Fixtures.tenant("acme"),
            jobId = "j1",
            allowedFromStatuses = setOf(JobStatus.QUEUED),
        ) { it.copy(status = JobStatus.RUNNING) }
        w.workerHandleRegistry.register("j1", CancellationTokenSource.create())

        val principal = adminPrincipal()
        val svc = service(w, principal)

        svc.toolsCall(ToolsCallParams("job_cancel", argsObj(jobId = "j1", reason = "first"))).get()
        // Zweiter cancel mit anderem Reason — der zweite Reason landet NICHT im Store.
        svc.toolsCall(ToolsCallParams("job_cancel", argsObj(jobId = "j1", reason = "second"))).get()

        val final = w.phaseCWiring.jobStore.findById(Fixtures.tenant("acme"), "j1")!!
        final.managedJob.cancelRequest.requestedReason shouldBe "first"
    }

    test("Dispatcher-Barriere: nach QUEUED→CANCELLED via tools/call startet kein Worker (Plan §7.8 line 1213-1214)") {
        val w = phaseEWiring()
        val record = seedQueued(w)
        val principal = adminPrincipal()
        val svc = service(w, principal)

        // Cancel via tools/call.
        svc.toolsCall(ToolsCallParams("job_cancel", argsObj(jobId = "j1"))).get()
        w.phaseCWiring.jobStore.findById(Fixtures.tenant("acme"), "j1")!!.managedJob.status shouldBe JobStatus.CANCELLED

        // Versuche, den Worker explizit zu dispatchen — die transitionStatus-CAS
        // (QUEUED→RUNNING) muss fehlschlagen, der Worker darf NICHT laufen.
        var workerInvoked = false
        val worker = JobWorker { _, _ ->
            workerInvoked = true
            JobWorkerOutcome.Succeeded()
        }
        val dispatchOutcome = w.jobDispatcher.dispatch(record, worker, CancellationToken.none()).get()
        dispatchOutcome.shouldBeInstanceOf<JobWorkerOutcome.Failed>()
        dispatchOutcome.errorCode shouldBe dev.dmigrate.server.application.job.JobDispatcher.REASON_DISPATCH_RACE
        workerInvoked shouldBe false

        // Job-Record bleibt CANCELLED — kein FAILED-Overlay durch den fehlgeschlagenen Dispatch.
        val final = w.phaseCWiring.jobStore.findById(Fixtures.tenant("acme"), "j1")!!
        final.managedJob.status shouldBe JobStatus.CANCELLED
        final.managedJob.artifacts shouldBe emptyList()
    }

    test("Reason-Scrubbing: Bearer-Token im Reason wird redigiert (Plan §7.7 line 1182-1183)") {
        val w = phaseEWiring()
        seedQueued(w)
        val principal = adminPrincipal()
        val svc = service(w, principal)

        svc.toolsCall(
            ToolsCallParams("job_cancel", argsObj(jobId = "j1", reason = "leak Bearer abc.def.ghi token")),
        ).get()

        val final = w.phaseCWiring.jobStore.findById(Fixtures.tenant("acme"), "j1")!!
        final.managedJob.cancelRequest.requestedReason!! shouldContain "Bearer ***"
        final.managedJob.cancelRequest.requestedReason!! shouldNotContain "abc.def.ghi"
    }
})
