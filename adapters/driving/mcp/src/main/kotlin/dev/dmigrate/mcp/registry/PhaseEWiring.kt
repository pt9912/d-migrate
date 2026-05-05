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
import dev.dmigrate.server.application.job.JobStartService
import dev.dmigrate.server.application.job.SyncExecutor
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
    val approvedRetryService: ApprovedRetryService = ApprovedRetryService(
        approvalGrantService = approvalGrantService,
        idempotencyStore = idempotencyStore,
        jobStartTransaction = jobStartTransaction,
        workerHandleRegistry = workerHandleRegistry,
        jobIdFactory = jobIdFactory,
        cancellationSourceFactory = cancellationSourceFactory,
    ),
    val workerExecutor: java.util.concurrent.Executor = SyncExecutor,
    val jobDispatcher: JobDispatcher = JobDispatcher(
        jobStore = phaseCWiring.jobStore,
        executor = workerExecutor,
        clock = phaseCWiring.clock,
    ),
    val jobCancelService: JobCancelService = JobCancelService(
        jobStore = phaseCWiring.jobStore,
        workerHandleRegistry = workerHandleRegistry,
    ),
)
