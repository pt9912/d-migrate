package dev.dmigrate.server.application.job

import dev.dmigrate.server.application.approval.ApprovalGrantValidator
import dev.dmigrate.server.application.approval.ApprovalTokenFingerprint
import dev.dmigrate.server.application.approval.DefaultApprovalGrantService
import dev.dmigrate.server.application.fingerprint.DefaultPayloadFingerprintService
import dev.dmigrate.server.application.fingerprint.JsonValue
import dev.dmigrate.text.FakeUnicodeTextService
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.application.policy.PolicyRule
import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import dev.dmigrate.server.core.approval.ApprovalGrant
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import dev.dmigrate.server.ports.memory.InMemoryIdempotencyStore
import dev.dmigrate.server.ports.memory.InMemoryJobStartTransaction
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryWorkerHandleRegistry
import dev.dmigrate.server.ports.JobWorker
import dev.dmigrate.server.ports.JobWorkerOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class JobStartOrchestratorTest : FunSpec({

    val tenant = Fixtures.tenant("acme")
    val principal = Fixtures.principal("alice")
    val tool = "schema_reverse_start"
    val now: Instant = Fixtures.NOW

    fun connRef(value: String = "dmigrate://tenants/acme/connections/c1") =
        RefField(name = "connectionId", value = value, expectedKind = ResourceKind.CONNECTIONS)

    class Fixture(
        val jobIdSeq: AtomicInteger = AtomicInteger(0),
        val policyRules: List<PolicyRule> = emptyList(),
        val policyDefault: PolicyEffect = PolicyEffect.Allow,
        autoDispatch: Boolean = false,
    ) {
        val jobStore = InMemoryJobStore()
        val idempotencyStore = InMemoryIdempotencyStore()
        val approvalGrantStore = InMemoryApprovalGrantStore()
        val workerHandleRegistry = InMemoryWorkerHandleRegistry()
        val transaction = InMemoryJobStartTransaction(jobStore, idempotencyStore)
        val grantService = DefaultApprovalGrantService(approvalGrantStore, ApprovalGrantValidator())
        val approvedRetryService = ApprovedRetryService(
            approvalGrantService = grantService,
            idempotencyStore = idempotencyStore,
            jobStartTransaction = transaction,
            workerHandleRegistry = workerHandleRegistry,
            jobIdFactory = { "job_${jobIdSeq.incrementAndGet()}" },
        )
        val policyService = ConfiguredPolicyService(
            rules = policyRules,
            defaultEffect = policyDefault,
        )

        // Fix approved-retry-no-dispatch: optional dispatch-fähige Verdrahtung.
        // SyncExecutor führt den Worker synchron aus; [workerInvoked] belegt, dass
        // der Job wirklich dispatcht wurde (vor dem Fix blieb ein genehmigter Retry
        // QUEUED, der Worker lief nie).
        val workerInvoked = AtomicBoolean(false)
        val orchestrator = JobStartOrchestrator(
            idempotencyStore = idempotencyStore,
            jobStartTransaction = transaction,
            workerHandleRegistry = workerHandleRegistry,
            approvedRetryService = approvedRetryService,
            policyService = policyService,
            payloadFingerprintService = DefaultPayloadFingerprintService(FakeUnicodeTextService()),
            jobIdFactory = { "job_${jobIdSeq.incrementAndGet()}" },
            jobDispatcher = if (autoDispatch) JobDispatcher(jobStore = jobStore) else null,
            jobWorkerFactory = if (autoDispatch) {
                JobWorkerFactory { _, _ ->
                    JobWorker { _, _ -> workerInvoked.set(true); JobWorkerOutcome.Succeeded() }
                }
            } else {
                null
            },
            jobStore = if (autoDispatch) jobStore else null,
        )

        /** Legt einen Grant ab, der zur [approvalRequestId]-Challenge passt (Token/Scopes/Binds). */
        fun saveGrant(
            approvalRequestId: String,
            rawToken: String,
            scopes: Set<String> = setOf("data.read"),
        ) {
            val payloadFp = DefaultPayloadFingerprintService(FakeUnicodeTextService()).fingerprint(
                scope = dev.dmigrate.server.application.fingerprint.FingerprintScope.START_TOOL,
                payload = JsonValue.obj("connectionId" to JsonValue.str("c1")),
                bind = dev.dmigrate.server.application.fingerprint.BindContext(
                    tenantId = tenant, callerId = principal, toolName = tool,
                ),
            )
            approvalGrantStore.save(
                ApprovalGrant(
                    approvalRequestId = approvalRequestId,
                    correlationKind = ApprovalCorrelationKind.IDEMPOTENCY_KEY,
                    correlationKey = "k1",
                    approvalTokenFingerprint = ApprovalTokenFingerprint.compute(rawToken),
                    toolName = tool,
                    tenantId = tenant,
                    callerId = principal,
                    payloadFingerprint = payloadFp,
                    issuerFingerprint = "test-issuer",
                    issuedScopes = scopes,
                    grantSource = "test",
                    expiresAt = now.plusSeconds(3600),
                ),
            )
        }

        fun request(
            idempotencyKey: String? = "k1",
            approvalToken: String? = null,
            payload: JsonValue.Obj = JsonValue.obj("connectionId" to JsonValue.str("c1")),
            refs: List<RefField> = listOf(connRef()),
        ) = JobStartRequest(
            toolName = tool,
            tenantId = tenant,
            callerId = principal,
            idempotencyKey = idempotencyKey,
            approvalToken = approvalToken,
            payload = payload,
            refs = refs,
            now = now,
            jobBuilder = { jobId, createdAt ->
                Fixtures.jobRecord(jobId).copy(
                    managedJob = Fixtures.jobRecord(jobId).managedJob.copy(
                        status = JobStatus.QUEUED,
                        createdAt = createdAt,
                        updatedAt = createdAt,
                    ),
                )
            },
        )
    }

    test("Allowed-Policy + Reserved → Started, Job committed, Worker-Handle registriert") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val outcome = fx.orchestrator.start(fx.request())
        outcome.shouldBeInstanceOf<JobStartHandlerOutcome.Started>()
        outcome.jobId shouldBe "job_1"
        fx.workerHandleRegistry.signal("job_1", "user-test")
        outcome.cancellationSource.token.isCancellationRequested shouldBe true
    }

    test("Idempotenter Retry nach Started → AlreadyStarted, kein neuer Job") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val first = fx.orchestrator.start(fx.request())
        first.shouldBeInstanceOf<JobStartHandlerOutcome.Started>()

        val second = fx.orchestrator.start(fx.request())
        second.shouldBeInstanceOf<JobStartHandlerOutcome.AlreadyStarted>()
        second.jobId shouldBe first.jobId
        fx.jobIdSeq.get() shouldBe 1
    }

    test("ValidationError: idempotencyKey fehlt → kein Store-Write") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val outcome = fx.orchestrator.start(fx.request(idempotencyKey = null))
        outcome.shouldBeInstanceOf<JobStartHandlerOutcome.ValidationError>()
        outcome.invalid shouldBe JobStartInputValidation.Invalid.IdempotencyKeyMissing
        // Kein Job, kein Idempotency-Eintrag.
        fx.jobIdSeq.get() shouldBe 0
    }

    test("ValidationError: freier JDBC-URL → kein Store-Write") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val outcome = fx.orchestrator.start(
            fx.request(refs = listOf(connRef(value = "jdbc:postgresql://oops"))),
        )
        outcome.shouldBeInstanceOf<JobStartHandlerOutcome.ValidationError>()
        outcome.invalid.shouldBeInstanceOf<JobStartInputValidation.Invalid.FreeJdbcUrl>()
        fx.jobIdSeq.get() shouldBe 0
    }

    test("Denied-Policy + Reserved → PolicyDenied, Idempotency = DENIED") {
        val fx = Fixture(policyDefault = PolicyEffect.Deny("policy:tool-blocked"))
        val outcome = fx.orchestrator.start(fx.request())
        outcome.shouldBeInstanceOf<JobStartHandlerOutcome.PolicyDenied>()
        outcome.reason shouldBe "policy:tool-blocked"
        // Replay liefert dieselbe Antwort.
        val replay = fx.orchestrator.start(fx.request())
        replay.shouldBeInstanceOf<JobStartHandlerOutcome.PolicyDenied>()
        replay.reason shouldBe "policy:tool-blocked"
        replay.expiresAt shouldBe outcome.expiresAt
    }

    test("RequiresApproval ohne Token → PolicyRequired-Challenge mit approvalRequestId") {
        val fx = Fixture(policyDefault = PolicyEffect.Challenge(setOf("data.read")))
        val outcome = fx.orchestrator.start(fx.request())
        outcome.shouldBeInstanceOf<JobStartHandlerOutcome.PolicyRequired>()
        outcome.requiredScopes shouldBe setOf("data.read")
        outcome.approvalRequestId.startsWith("appr_") shouldBe true
        outcome.correlationKind shouldBe ApprovalCorrelationKind.IDEMPOTENCY_KEY
        outcome.correlationKey shouldBe "k1"
        // Idempotency-Eintrag jetzt AWAITING_APPROVAL — kein Job, kein
        // Worker-Handle.
        fx.jobIdSeq.get() shouldBe 0
    }

    test("AwaitingApproval-Replay ohne Token → erneut PolicyRequired (keine Job-Erzeugung)") {
        val fx = Fixture(policyDefault = PolicyEffect.Challenge(setOf("data.read")))
        fx.orchestrator.start(fx.request())
        val replay = fx.orchestrator.start(fx.request())
        replay.shouldBeInstanceOf<JobStartHandlerOutcome.PolicyRequired>()
        fx.jobIdSeq.get() shouldBe 0
    }

    test("RequiresApproval mit gueltigem Grant → Started via ApprovedRetry") {
        val fx = Fixture(policyDefault = PolicyEffect.Challenge(setOf("data.read")))
        // Erster Aufruf erzeugt Challenge.
        val challenge = fx.orchestrator.start(fx.request())
            as JobStartHandlerOutcome.PolicyRequired

        // Grant mit passenden Bindungen (Token-Fingerprint, Scopes, etc.)
        // ablegen.
        val rawToken = "tok-fixture"
        val payloadFp = DefaultPayloadFingerprintService(FakeUnicodeTextService()).fingerprint(
            scope = dev.dmigrate.server.application.fingerprint.FingerprintScope.START_TOOL,
            payload = JsonValue.obj("connectionId" to JsonValue.str("c1")),
            bind = dev.dmigrate.server.application.fingerprint.BindContext(
                tenantId = tenant,
                callerId = principal,
                toolName = tool,
            ),
        )
        fx.approvalGrantStore.save(
            ApprovalGrant(
                approvalRequestId = challenge.approvalRequestId,
                correlationKind = ApprovalCorrelationKind.IDEMPOTENCY_KEY,
                correlationKey = "k1",
                approvalTokenFingerprint = ApprovalTokenFingerprint.compute(rawToken),
                toolName = tool,
                tenantId = tenant,
                callerId = principal,
                payloadFingerprint = payloadFp,
                issuerFingerprint = "test-issuer",
                issuedScopes = setOf("data.read"),
                grantSource = "test",
                expiresAt = now.plusSeconds(3600),
            ),
        )

        // Retry mit Token.
        val outcome = fx.orchestrator.start(fx.request(approvalToken = rawToken))
        outcome.shouldBeInstanceOf<JobStartHandlerOutcome.Started>()
    }

    test("Approved-Retry dispatcht den Job (Fix approved-retry-no-dispatch): Worker läuft, Job SUCCEEDED") {
        val fx = Fixture(policyDefault = PolicyEffect.Challenge(setOf("data.read")), autoDispatch = true)
        val challenge = fx.orchestrator.start(fx.request()) as JobStartHandlerOutcome.PolicyRequired
        val rawToken = "tok-fixture"
        fx.saveGrant(challenge.approvalRequestId, rawToken)

        val outcome = fx.orchestrator.start(fx.request(approvalToken = rawToken))
        outcome.shouldBeInstanceOf<JobStartHandlerOutcome.Started>()
        // Regression: vor dem Fix lief der Worker NIE und der Job blieb QUEUED.
        fx.workerInvoked.get() shouldBe true
        fx.jobStore.findById(tenant, outcome.jobId)!!.managedJob.status shouldBe JobStatus.SUCCEEDED
    }

    test("RequiresApproval mit fehlendem Grant → PolicyDenied (policy:grant-unknown)") {
        val fx = Fixture(policyDefault = PolicyEffect.Challenge(setOf("data.read")))
        fx.orchestrator.start(fx.request()) // Challenge erzeugt
        // Token, der keinem gespeicherten Grant entspricht.
        val outcome = fx.orchestrator.start(fx.request(approvalToken = "bogus-token"))
        outcome.shouldBeInstanceOf<JobStartHandlerOutcome.PolicyDenied>()
        outcome.reason shouldBe "policy:grant-unknown"
    }

    test("Fail-closed (Security-Audit #2): AwaitingApproval OHNE durable Challenge + Token → keine Ausfuehrung") {
        // Regression fuer den entfernten Anti-Replay-Bypass: fehlte die durable
        // Challenge, zog der Retry die approvalRequestId frueher AUS dem Grant
        // selbst — ApprovalRequestIdMismatch konnte nie feuern, ein angreifer-
        // gewaehlter Grant lief durch (→ Started). Jetzt fail-closed: re-decide
        // Policy → erneut Challenge, kein Job.
        val fx = Fixture(policyDefault = PolicyEffect.Challenge(setOf("data.read")))
        val fingerprintService = DefaultPayloadFingerprintService(FakeUnicodeTextService())
        val payload = JsonValue.obj("connectionId" to JsonValue.str("c1"))
        val fingerprint = fingerprintService.fingerprint(
            scope = dev.dmigrate.server.application.fingerprint.FingerprintScope.START_TOOL,
            payload = payload,
            bind = dev.dmigrate.server.application.fingerprint.BindContext(
                tenantId = tenant, callerId = principal, toolName = tool,
            ),
        )
        val scope = dev.dmigrate.server.core.idempotency.IdempotencyScope(
            tenantId = tenant,
            callerId = principal,
            toolName = tool,
            idempotencyKey = dev.dmigrate.server.core.idempotency.IdempotencyKey("k1"),
        )
        // AWAITING_APPROVAL OHNE durable Challenge (Record ohne Challenge-Persistierung).
        fx.idempotencyStore.reserve(scope, fingerprint, now)
        fx.idempotencyStore.markAwaitingApproval(scope, now)

        // Grant, dessen approvalRequestId der alte Bypass 1:1 uebernommen haette
        // (garantierter Match); alle uebrigen Bindungen passen ebenfalls.
        val rawToken = "tok-forged"
        fx.approvalGrantStore.save(
            ApprovalGrant(
                approvalRequestId = "appr-attacker-chosen",
                correlationKind = ApprovalCorrelationKind.IDEMPOTENCY_KEY,
                correlationKey = "k1",
                approvalTokenFingerprint = ApprovalTokenFingerprint.compute(rawToken),
                toolName = tool,
                tenantId = tenant,
                callerId = principal,
                payloadFingerprint = fingerprint,
                issuerFingerprint = "test-issuer",
                issuedScopes = setOf("data.read"),
                grantSource = "test",
                expiresAt = now.plusSeconds(3600),
            ),
        )

        val outcome = fx.orchestrator.start(fx.request(approvalToken = rawToken))
        // Fail-closed: KEINE Ausfuehrung; der stale Token treibt keinen Job.
        outcome.shouldBeInstanceOf<JobStartHandlerOutcome.PolicyRequired>()
        fx.jobIdSeq.get() shouldBe 0
    }

    test("IdempotencyConflict: gleicher Scope, anderer Fingerprint → keine Policy") {
        // LF-012 / LN-011 / LN-017 / LN-027: "Idempotency-Konflikt prueft keine Policy".
        val fx = Fixture(policyDefault = PolicyEffect.Deny("policy:tool-blocked"))
        // Erster Call: scope k1 mit fp "c1".
        fx.orchestrator.start(fx.request(payload = JsonValue.obj("connectionId" to JsonValue.str("c1"))))
        // Zweiter Call: gleicher scope k1, anderer fingerprint via "c2".
        val outcome = fx.orchestrator.start(
            fx.request(payload = JsonValue.obj("connectionId" to JsonValue.str("c2"))),
        )
        outcome.shouldBeInstanceOf<JobStartHandlerOutcome.IdempotencyConflict>()
    }

    test("Stored Denied-Eintrag wird auf Retry deterministisch wiedergegeben") {
        val fx = Fixture()
        // Erst manuell einen Denied-Eintrag aus PENDING erzeugen.
        val scope = dev.dmigrate.server.core.idempotency.IdempotencyScope(
            tenantId = tenant,
            callerId = principal,
            toolName = tool,
            idempotencyKey = dev.dmigrate.server.core.idempotency.IdempotencyKey("k1"),
        )
        // Reserve mit demselben fingerprint, dann markieren als denied.
        val fp = DefaultPayloadFingerprintService(FakeUnicodeTextService()).fingerprint(
            scope = dev.dmigrate.server.application.fingerprint.FingerprintScope.START_TOOL,
            payload = JsonValue.obj("connectionId" to JsonValue.str("c1")),
            bind = dev.dmigrate.server.application.fingerprint.BindContext(
                tenantId = tenant, callerId = principal, toolName = tool,
            ),
        )
        fx.idempotencyStore.reserve(scope, fp, now)
        val deniedExpiry = fx.idempotencyStore.deny(scope, "policy:earlier", now)!!

        // Orchestrator-Aufruf liefert die gespeicherte Antwort.
        val outcome = fx.orchestrator.start(fx.request())
        outcome.shouldBeInstanceOf<JobStartHandlerOutcome.PolicyDenied>()
        outcome.reason shouldBe "policy:earlier"
        outcome.expiresAt shouldBe deniedExpiry
    }
})
