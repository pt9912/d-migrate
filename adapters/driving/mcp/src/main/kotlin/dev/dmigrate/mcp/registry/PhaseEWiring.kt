package dev.dmigrate.mcp.registry

import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.server.application.approval.ApprovalGrantService
import dev.dmigrate.server.application.approval.ApprovalGrantValidator
import dev.dmigrate.server.application.approval.DefaultApprovalGrantService
import dev.dmigrate.server.application.approval.FailClosedGrantIssuer
import dev.dmigrate.server.application.approval.GrantIssuer
import dev.dmigrate.server.application.fingerprint.DefaultPayloadFingerprintService
import dev.dmigrate.server.application.fingerprint.PayloadFingerprintService
import dev.dmigrate.server.application.job.ApprovedRetryService
import dev.dmigrate.server.application.job.JobCancelService
import dev.dmigrate.server.application.job.JobDispatcher
import dev.dmigrate.server.application.job.JobExecutorBundle
import dev.dmigrate.server.application.job.JobExecutorConfig
import dev.dmigrate.server.application.job.JobExecutorFactory
import dev.dmigrate.server.application.job.JobStartService
import dev.dmigrate.server.application.job.JobWorkerFactory
import dev.dmigrate.server.application.job.PassthroughJobWorkerFactory
import dev.dmigrate.server.application.quota.InMemoryQuotaReservationOwnerStore
import dev.dmigrate.server.application.quota.OwnerAwareQuotaService
import dev.dmigrate.server.application.quota.QuotaReservationOwnerStore
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyService
import dev.dmigrate.server.ports.ApprovalGrantStore
import dev.dmigrate.server.ports.IdempotencyStore
import dev.dmigrate.server.ports.JobStartTransaction
import dev.dmigrate.server.ports.WorkerHandleRegistry
import java.util.UUID

/**
 * Phase-E Wiring-Bundle parallel zu [PhaseCWiring]. Komponiert die
 * Services, die die Phase-E-Tool-Handler aus AP E.6 (3/4) brauchen,
 * sodass das Bootstrap nur die Ports und (optional) Konfigurationen
 * uebergibt — Service-Konstruktion erfolgt mit defaults-rueckwaerts.
 *
 * Zusammenspiel mit [PhaseCWiring]:
 *
 * - [PhaseEWiring] traegt das gemeinsame [PhaseCWiring] (`jobStore`,
 *   `quotaService`, `auditSink`, `clock`, `connectionStore`, …) als
 *   Pflichtfeld; Tool-Handler greifen via `phaseCWiring.<x>` darauf zu.
 * - Phase-E-eigene Ports (Idempotency, JobStart-Transaction, Worker-
 *   Handle-Registry, ApprovalGrantStore) sind separate Pflichtfelder,
 *   weil sie keine Phase-C-Vorgaenger haben.
 *
 * Sicherheits-Defaults (Plan §7.4):
 *
 * - [policyService] defaultet auf eine leere Allowlist mit
 *   `defaultEffect = Deny("policy:no-rule")` — fail-closed bis explizit
 *   konfiguriert.
 * - [grantIssuer] defaultet auf [FailClosedGrantIssuer] — keine Grants
 *   ohne explizite Konfiguration.
 *
 * Die Konstruktion folgt dem [PhaseCWiring]-Muster: spaetere Default-
 * Werte verwenden frueher deklarierte Parameter, sodass die ganze
 * Service-Kette aus den Pflichtfeldern allein eine voll funktionsfaehige
 * Bundle liefert.
 */
data class PhaseEWiring(
    /** Phase-C-Bundle (jobStore, quotaService, auditSink, clock, connectionStore, …). */
    val phaseCWiring: PhaseCWiring,
    val idempotencyStore: IdempotencyStore,
    val jobStartTransaction: JobStartTransaction,
    val workerHandleRegistry: WorkerHandleRegistry,
    val approvalGrantStore: ApprovalGrantStore,
    val policyService: PolicyService = ConfiguredPolicyService(rules = emptyList()),
    val grantIssuer: GrantIssuer = FailClosedGrantIssuer,
    val approvalGrantValidator: ApprovalGrantValidator = ApprovalGrantValidator(),
    val approvalGrantService: ApprovalGrantService = DefaultApprovalGrantService(
        store = approvalGrantStore,
        validator = approvalGrantValidator,
    ),
    val payloadFingerprintService: PayloadFingerprintService = DefaultPayloadFingerprintService(),
    val jobIdFactory: () -> String = { "job_${UUID.randomUUID()}" },
    val cancellationSourceFactory: () -> CancellationTokenSource = { CancellationTokenSource.create() },
    val jobStartService: JobStartService = JobStartService(
        idempotencyStore = idempotencyStore,
        jobStartTransaction = jobStartTransaction,
        workerHandleRegistry = workerHandleRegistry,
        jobIdFactory = jobIdFactory,
        cancellationSourceFactory = cancellationSourceFactory,
    ),
    /**
     * Phase E §7.9 owner-aware Quota-Service. Default-Komposition:
     * delegate auf [PhaseCWiring.quotaService], owner-Store als
     * In-Memory. Production-Wiring kann eine persistente OwnerStore-
     * Implementierung injizieren.
     *
     * Muss VOR `approvedRetryService` deklariert sein, damit dessen
     * Default auf den Service referenzieren kann (Review-Fix Blocker #2).
     */
    val quotaReservationOwnerStore: QuotaReservationOwnerStore = InMemoryQuotaReservationOwnerStore(),
    val ownerAwareQuotaService: OwnerAwareQuotaService = OwnerAwareQuotaService(
        delegate = phaseCWiring.quotaService,
        ownerStore = quotaReservationOwnerStore,
    ),
    val approvedRetryService: ApprovedRetryService = ApprovedRetryService(
        approvalGrantService = approvalGrantService,
        idempotencyStore = idempotencyStore,
        jobStartTransaction = jobStartTransaction,
        workerHandleRegistry = workerHandleRegistry,
        jobIdFactory = jobIdFactory,
        cancellationSourceFactory = cancellationSourceFactory,
        quotaService = ownerAwareQuotaService,
    ),
    /**
     * Phase E3 § 4 + § 5 (E3.5): Executor + Admission + Lifecycle als
     * konsistent verkabeltes Tripel. Default ist `Sync` — gleicher
     * Bestands-Effekt wie `SyncExecutor` plus no-op Admission. Production-
     * Wiring ueberschreibt mit `JobExecutorFactory.create(Async(...))`;
     * der CLI-Bootstrap registriert dann zusaetzlich
     * [JobExecutorBundle.lifecycle].`shutdown(...)` auf den Shutdown-Hook.
     */
    val executorBundle: JobExecutorBundle = JobExecutorFactory.create(JobExecutorConfig.SYNC_DEFAULT),
    /**
     * Backward-compat-Shortcut: zeigte vor E3.5 auf `SyncExecutor`. Heute
     * abgeleitet aus [executorBundle], damit Bestands-Caller
     * (PhaseEWiring(workerExecutor = ...)) weiter funktionieren — ein
     * expliziter Override hier gewinnt gegenueber `executorBundle.executor`.
     */
    val workerExecutor: java.util.concurrent.Executor = executorBundle.executor,
    val jobDispatcher: JobDispatcher = JobDispatcher(
        jobStore = phaseCWiring.jobStore,
        executor = workerExecutor,
        clock = phaseCWiring.clock,
        quotaService = ownerAwareQuotaService,
    ),
    val jobCancelService: JobCancelService = JobCancelService(
        jobStore = phaseCWiring.jobStore,
        workerHandleRegistry = workerHandleRegistry,
        quotaService = ownerAwareQuotaService,
    ),
    /**
     * Phase E §7.7 Worker-Factory fuer Auto-Dispatch (Review-Fix
     * Blocker #1). Default ist [PassthroughJobWorkerFactory] — ein
     * no-op-Worker, der sofort succeeded und damit den Job-Lifecycle
     * QUEUED -> SUCCEEDED schliesst, OHNE echte Reader/Profiler/
     * Compare-Adapter. Production-Wiring uebergibt eine Operation-
     * basierte Factory mit echten SchemaReverse-/DataProfile-/
     * SchemaCompare-Workern.
     */
    val jobWorkerFactory: JobWorkerFactory = PassthroughJobWorkerFactory,
)
