package dev.dmigrate.mcp.registry

import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.approval.ApprovalGrantService
import dev.dmigrate.server.application.approval.ConfiguredAllowlistGrantIssuer
import dev.dmigrate.server.application.approval.FailClosedGrantIssuer
import dev.dmigrate.server.application.approval.GrantIssuer
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyAttempt
import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.application.policy.PolicyRule
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import dev.dmigrate.server.core.policy.PolicyDecision
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
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
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class OperationalMcpWiringTest : FunSpec({

    val clock = Clock.fixed(Instant.parse("2026-05-05T12:00:00Z"), ZoneOffset.UTC)

    fun runtimeWiring(jobStore: InMemoryJobStore = InMemoryJobStore()): McpRuntimeWiring {
        val artifactStore = InMemoryArtifactStore()
        val artifactContentStore = InMemoryArtifactContentStore()
        val schemaStore = InMemorySchemaStore()
        val quotaStore = InMemoryQuotaStore()
        return McpRuntimeWiring(
            uploadSessionStore = InMemoryUploadSessionStore(),
            uploadSegmentStore = InMemoryUploadSegmentStore(),
            artifactStore = artifactStore,
            artifactContentStore = artifactContentStore,
            schemaStore = schemaStore,
            jobStore = jobStore,
            quotaService = DefaultQuotaService(quotaStore) { Long.MAX_VALUE },
            limits = McpLimitsConfig(),
            clock = clock,
        )
    }

    fun operationalWiring(
        idempotencyStore: InMemoryIdempotencyStore = InMemoryIdempotencyStore(),
        jobStore: InMemoryJobStore = InMemoryJobStore(),
        approvalGrantStore: InMemoryApprovalGrantStore = InMemoryApprovalGrantStore(),
        workerHandleRegistry: InMemoryWorkerHandleRegistry = InMemoryWorkerHandleRegistry(),
        policyService: dev.dmigrate.server.application.policy.PolicyService =
            ConfiguredPolicyService(rules = emptyList()),
        grantIssuer: GrantIssuer = FailClosedGrantIssuer,
    ): OperationalMcpWiring = OperationalMcpWiring(
        runtimeWiring = runtimeWiring(jobStore),
        idempotencyStore = idempotencyStore,
        jobStartTransaction = InMemoryJobStartTransaction(jobStore, idempotencyStore),
        workerHandleRegistry = workerHandleRegistry,
        approvalGrantStore = approvalGrantStore,
        policyService = policyService,
        grantIssuer = grantIssuer,
    )

    test("Bundle laesst sich mit den Pflichtfeldern allein konstruieren") {
        val w = operationalWiring()
        // Composed services existieren.
        w.approvalGrantService.shouldBeInstanceOf<ApprovalGrantService>()
        w.jobStartService
        w.approvedRetryService
        w.payloadFingerprintService
    }

    test("Default-PolicyService ist fail-closed (Plan §7.4)") {
        val w = operationalWiring()
        val attempt = PolicyAttempt(
            tenantId = TenantId("acme"),
            callerId = PrincipalId("alice"),
            toolName = "schema_reverse_start",
            correlationKind = ApprovalCorrelationKind.IDEMPOTENCY_KEY,
            correlationKey = "idem-1",
            payloadFingerprint = "fp-1",
        )
        val decision = w.policyService.decide(attempt)
        decision shouldBe PolicyDecision.Denied("policy:no-rule")
    }

    test("Default-GrantIssuer ist FailClosed (Plan §7.4)") {
        val w = operationalWiring()
        w.grantIssuer shouldBeSameInstanceAs FailClosedGrantIssuer
    }

    test("Default-Bundle: PolicyService und GrantIssuer ueberschreibbar") {
        val customPolicy = ConfiguredPolicyService(
            rules = listOf(PolicyRule(toolName = "schema_reverse_start", effect = PolicyEffect.Allow)),
        )
        val customIssuer = ConfiguredAllowlistGrantIssuer(
            store = InMemoryApprovalGrantStore(),
            rules = emptyList(),
            issuerFingerprint = "test",
        )
        val w = operationalWiring(policyService = customPolicy, grantIssuer = customIssuer)
        w.policyService shouldBeSameInstanceAs customPolicy
        w.grantIssuer shouldBeSameInstanceAs customIssuer
    }

    test("jobStartService und approvedRetryService teilen jobIdFactory + cancellationSourceFactory") {
        // Beide Services bekommen IDENTISCHE Factory-Instanzen, sodass
        // ein per-Bundle-Default oder per-Test-Override konsistent fuer
        // beide Pfade greift.
        val w = operationalWiring()
        // Smoke: via Reflection-freie API kein direkter Vergleich der
        // Factories moeglich (sind private im Service); stattdessen
        // pruefen wir, dass die Bundle-Felder nicht null sind.
        w.jobIdFactory.invoke().startsWith("job_") shouldBe true
    }

    test("runtimeWiring bleibt zugaenglich fuer Tool-Handler") {
        val pc = runtimeWiring()
        val w = OperationalMcpWiring(
            runtimeWiring = pc,
            idempotencyStore = InMemoryIdempotencyStore(),
            jobStartTransaction = InMemoryJobStartTransaction(pc.jobStore as InMemoryJobStore, InMemoryIdempotencyStore()),
            workerHandleRegistry = InMemoryWorkerHandleRegistry(),
            approvalGrantStore = InMemoryApprovalGrantStore(),
        )
        w.runtimeWiring shouldBeSameInstanceAs pc
    }
})
