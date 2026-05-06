package dev.dmigrate.server.application.job

import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.server.application.approval.ApprovalAttempt
import dev.dmigrate.server.application.approval.ApprovalTokenFingerprint
import dev.dmigrate.server.application.fingerprint.BindContext
import dev.dmigrate.server.application.fingerprint.FingerprintScope
import dev.dmigrate.server.application.fingerprint.JsonValue
import dev.dmigrate.server.application.fingerprint.PayloadFingerprintService
import dev.dmigrate.server.application.policy.PolicyAttempt
import dev.dmigrate.server.application.policy.PolicyService
import dev.dmigrate.server.application.quota.OwnerAwareQuotaService
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import dev.dmigrate.server.ports.quota.QuotaOutcome
import java.time.Duration
import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import dev.dmigrate.server.core.idempotency.IdempotencyKey
import dev.dmigrate.server.core.idempotency.IdempotencyReserveOutcome
import dev.dmigrate.server.core.idempotency.IdempotencyScope
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.policy.PolicyDecision
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.ApprovalGrantStore
import dev.dmigrate.server.ports.IdempotencyStore
import dev.dmigrate.server.ports.JobStartTransaction
import dev.dmigrate.server.ports.JobStartTransactionOutcome
import dev.dmigrate.server.ports.WorkerHandleRegistry
import java.time.Instant

/**
 * Phase E §7.6 Orchestrator: kombiniert die Phase-E-Bausteine zu einem
 * ausfuehrbaren Start-Tool-Vertrag.
 *
 * Ablauf gemaess Plan §7.6 / §7.5:
 *
 * 1. Pre-Idempotency-Validation ueber [JobStartInputValidator]. Bei
 *    [JobStartInputValidation.Invalid] kein Store-Write; Caller mappt auf
 *    `INVALID_PARAMS` o.ae. (Plan §7.6 line 1118-1121).
 * 2. Payload-Fingerprint via [PayloadFingerprintService] mit
 *    [FingerprintScope.START_TOOL] und tenant-/caller-/tool-Bind.
 * 3. `IdempotencyStore.reserve(...)` — atomar.
 * 4. Switch auf Reserve-Outcome:
 *    - `Reserved` → [PolicyService.decide] dann
 *      - Allowed → atomares `JobStartTransaction.commit` + Worker-Handle
 *      - RequiresApproval → `markAwaitingApproval` + Challenge zurueck
 *      - Denied → `deny` + `POLICY_DENIED`
 *    - `AwaitingApproval` (existiert) →
 *      - mit Token: `ApprovedRetryService.retry` (Grant-Lookup +
 *        validate + claim + commit; siehe AP E.5)
 *      - ohne Token: re-decide Policy → neue Challenge (Plan §5.5
 *        "Retry liefert erneut POLICY_REQUIRED mit der aktuellen
 *        Challenge"). Der Approval-Request-ID-Anti-Replay-Check des
 *        Validators wird durch Lookup von `grant.approvalRequestId`
 *        umgangen, weil die Idempotency-Tabelle die aktuelle Challenge
 *        in dieser AP-Stufe noch nicht persistiert (TODO: store-
 *        extension fuer Plan §5.5 echte Anti-Replay-Bindung).
 *    - `Committed` → `AlreadyStarted(jobId)`
 *    - `ExistingPending` → `Pending(leaseExpiresAt)`
 *    - `Denied` → `PolicyDenied(reason, expiresAt)` (Replay)
 *    - `Failed` → `Failed(reason, expiresAt)` (Replay)
 *    - `Conflict` → `IdempotencyConflict(existingFingerprint)` —
 *      Plan §7.5 "Idempotency-Konflikt prueft keine Policy".
 *
 * Nicht in diesem Orchestrator:
 *
 * - Quota-Reservation (AP E.9 — Caller fuegt Quota.reserve VOR/NACH der
 *   Allowed-Branch ein, sobald die Quota-Felder produktiv sind).
 * - Audit-Threading (AP E.10).
 * - Runner-Dispatch (AP E.7 — der Job ist hier `QUEUED`, ein Worker
 *   uebernimmt spaeter).
 */
class JobStartOrchestrator(
    private val idempotencyStore: IdempotencyStore,
    private val jobStartTransaction: JobStartTransaction,
    private val workerHandleRegistry: WorkerHandleRegistry,
    private val approvalGrantStore: ApprovalGrantStore,
    private val approvedRetryService: ApprovedRetryService,
    private val policyService: PolicyService,
    private val payloadFingerprintService: PayloadFingerprintService,
    private val jobIdFactory: () -> String,
    private val cancellationSourceFactory: () -> CancellationTokenSource =
        { CancellationTokenSource.create() },
    /**
     * Phase E §7.9 owner-aware Quota-Service. Wenn `null`, ueberspringt
     * der Orchestrator die Quota-Reserve/Commit/Refund-Schritte
     * komplett — sinnvoll fuer Bestands-Tests, die keine Quota-Logik
     * unter sich haben. Phase-E-Production-Wiring (PhaseEWiring) setzt
     * eine echte Instanz.
     */
    private val quotaService: OwnerAwareQuotaService? = null,
    private val quotaLeaseDuration: Duration = DEFAULT_QUOTA_LEASE,
    /**
     * Phase E §7.7 Auto-Dispatch-Hook (Review-Fix Blocker #1). Wenn
     * beide gesetzt sind, ruft der Orchestrator unmittelbar nach
     * `JobStartTransaction.commit` die [jobWorkerFactory] und reicht
     * den entstehenden Worker an den [jobDispatcher] — fire-and-forget,
     * Resultat wird NICHT awaited. Fuer SyncExecutor laeuft der Worker
     * synchron (Tests, Single-Process-Bootstrap). Fuer
     * ExecutorService-Async kehrt dispatch sofort zurueck und der
     * Worker laeuft im Hintergrund.
     *
     * Beide null = Bestands-Verhalten: Job bleibt QUEUED, Caller (Test)
     * dispatcht manuell.
     */
    private val jobDispatcher: JobDispatcher? = null,
    private val jobWorkerFactory: JobWorkerFactory? = null,
) {

    fun start(request: JobStartRequest): JobStartHandlerOutcome {
        val validation = JobStartInputValidator.validate(
            JobStartInputAttempt(
                toolName = request.toolName,
                callerTenant = request.tenantId,
                idempotencyKey = request.idempotencyKey,
                refs = request.refs,
            ),
        )
        if (validation is JobStartInputValidation.Invalid) {
            return JobStartHandlerOutcome.ValidationError(validation)
        }

        val fingerprint = payloadFingerprintService.fingerprint(
            scope = FingerprintScope.START_TOOL,
            payload = request.payload,
            bind = BindContext(
                tenantId = request.tenantId,
                callerId = request.callerId,
                toolName = request.toolName,
            ),
        )

        // Phase E §7.10 (Review-Fix #8): AuditFields populieren, sobald
        // bekannt. payloadFingerprint deterministisch aus dem Payload,
        // resourceRefs aus den expliziten RefField-Eintraegen des
        // Requests. Schreibt nur wenn der Caller eine AuditFields-
        // Instanz mitgegeben hat.
        request.auditFields?.also { fields ->
            fields.payloadFingerprint = fingerprint
            fields.resourceRefs = request.refs.map { it.value }
        }

        val scope = IdempotencyScope(
            tenantId = request.tenantId,
            callerId = request.callerId,
            toolName = request.toolName,
            idempotencyKey = IdempotencyKey(request.idempotencyKey!!),
        )

        return when (val reserve = idempotencyStore.reserve(scope, fingerprint, request.now)) {
            is IdempotencyReserveOutcome.Reserved -> handleReserved(request, scope, fingerprint)
            is IdempotencyReserveOutcome.Committed -> JobStartHandlerOutcome.AlreadyStarted(reserve.resultRef)
            is IdempotencyReserveOutcome.ExistingPending -> JobStartHandlerOutcome.Pending(reserve.leaseExpiresAt)
            is IdempotencyReserveOutcome.AwaitingApproval ->
                handleExistingAwaitingApproval(request, scope, fingerprint, reserve.challenge)
            is IdempotencyReserveOutcome.Denied -> JobStartHandlerOutcome.PolicyDenied(reserve.reason, reserve.expiresAt)
            is IdempotencyReserveOutcome.Failed -> JobStartHandlerOutcome.Failed(reserve.reason, reserve.expiresAt)
            is IdempotencyReserveOutcome.Conflict -> JobStartHandlerOutcome.IdempotencyConflict(reserve.existingFingerprint)
        }
    }

    private fun handleReserved(
        request: JobStartRequest,
        scope: IdempotencyScope,
        fingerprint: String,
    ): JobStartHandlerOutcome {
        val attempt = PolicyAttempt(
            tenantId = request.tenantId,
            callerId = request.callerId,
            toolName = request.toolName,
            correlationKind = ApprovalCorrelationKind.IDEMPOTENCY_KEY,
            correlationKey = request.idempotencyKey!!,
            payloadFingerprint = fingerprint,
            resourceRefs = request.refs.map { it.value },
        )
        return when (val decision = policyService.decide(attempt)) {
            is PolicyDecision.Allowed -> commitJob(request, scope)
            is PolicyDecision.RequiresApproval -> markAwaitingAndChallenge(request, scope, decision)
            is PolicyDecision.Denied -> denyAndReturn(scope, decision.reasonCode, request.now)
        }
    }

    private fun commitJob(
        request: JobStartRequest,
        scope: IdempotencyScope,
    ): JobStartHandlerOutcome {
        // Phase E §7.9: Quota.reserve VOR jobBuilder + JobStartTransaction.commit.
        // RateLimited liefert sofort zurueck — keine Job-Erzeugung, keine
        // Secret-Store-Reads (Plan §7.9 line 1270-1273).
        val quotaReservation = reserveQuota(request, scope)
        if (quotaReservation is QuotaReserveResult.RateLimited) {
            return quotaReservation.outcome
        }
        val ownerId = (quotaReservation as? QuotaReserveResult.Granted)?.ownerId

        val jobId = jobIdFactory()
        val baseRecord = request.jobBuilder(jobId, request.now)
        val record = if (ownerId != null) baseRecord.copy(quotaReservationOwnerId = ownerId) else baseRecord

        return when (val outcome = jobStartTransaction.commit(record, scope, request.now)) {
            is JobStartTransactionOutcome.Committed -> {
                if (ownerId != null) quotaService?.commitForOwner(ownerId, request.now)
                val source = cancellationSourceFactory()
                workerHandleRegistry.register(jobId, source)

                // Phase E §7.7 Auto-Dispatch (Review-Fix Blocker #1):
                // Wenn Dispatcher + Factory gewired sind, kicke den Worker
                // hier an. fire-and-forget — die CompletableFuture wird
                // bewusst NICHT awaited, damit der Handler-Response sofort
                // mit `Started + jobId` zurueckgehen kann (Plan §7.7
                // async-Charakter).
                val factory = jobWorkerFactory
                val dispatcher = jobDispatcher
                if (factory != null && dispatcher != null) {
                    val worker = factory.create(outcome.record, request)
                    if (worker != null) {
                        dispatcher.dispatch(outcome.record, worker, source.token)
                    }
                }

                JobStartHandlerOutcome.Started(jobId, outcome.record, source)
            }
            is JobStartTransactionOutcome.IdempotencyNotEligible -> {
                // Race: parallel commit zwischen reserve und transaction-commit.
                // Plan §7.9 line 1282-1284: refund nur fuer pre-commit Fehler
                // dieses Pipeline-Owners. Sweeper kann auf Lease-Ablauf NICHT
                // refunden, weil der OwnerStore-Eintrag PENDING ist.
                if (ownerId != null) quotaService?.refundForOwner(ownerId, request.now)
                JobStartHandlerOutcome.Pending(request.now.plusSeconds(1))
            }
        }
    }

    private fun reserveQuota(request: JobStartRequest, scope: IdempotencyScope): QuotaReserveResult {
        val service = quotaService ?: return QuotaReserveResult.SkipNoService
        val key = QuotaKey(
            tenantId = request.tenantId,
            dimension = QuotaDimension.ACTIVE_JOBS,
            principalId = request.callerId,
            operation = request.toolName,
        )
        val ownerId = quotaOwnerIdFor(scope)
        val outcome = service.reserve(
            key = key,
            amount = QUOTA_AMOUNT_PER_JOB,
            ownerId = ownerId,
            leaseExpiresAt = request.now.plus(quotaLeaseDuration),
            now = request.now,
        )
        return when (outcome) {
            is QuotaOutcome.Granted -> QuotaReserveResult.Granted(ownerId)
            is QuotaOutcome.RateLimited -> QuotaReserveResult.RateLimited(
                JobStartHandlerOutcome.RateLimited(
                    retryAfter = outcome.retryAfter,
                    current = outcome.current,
                    limit = outcome.limit,
                ),
            )
        }
    }

    private fun quotaOwnerIdFor(scope: IdempotencyScope): String =
        "${scope.tenantId.value}:${scope.callerId.value}:${scope.toolName}:${scope.idempotencyKey.value}"

    private sealed interface QuotaReserveResult {
        data object SkipNoService : QuotaReserveResult
        data class Granted(val ownerId: String) : QuotaReserveResult
        data class RateLimited(val outcome: JobStartHandlerOutcome.RateLimited) : QuotaReserveResult
    }

    private fun markAwaitingAndChallenge(
        request: JobStartRequest,
        scope: IdempotencyScope,
        decision: PolicyDecision.RequiresApproval,
    ): JobStartHandlerOutcome {
        // Plan §5.5 (Review-Fix Blocker #3): die durable Challenge wird
        // beim Statuswechsel persistiert, damit der Approved-Retry den
        // approvalRequestId-Anti-Replay-Check echt durchfuehren kann.
        val challenge = dev.dmigrate.server.core.approval.ApprovalChallenge(
            approvalRequestId = decision.approvalRequestId,
            correlationKind = decision.correlationKind,
            correlationKey = decision.correlationKey,
            requiredScopes = decision.requiredScopes,
            reasons = decision.reasons,
        )
        idempotencyStore.markAwaitingApproval(scope, request.now, challenge)
        return JobStartHandlerOutcome.PolicyRequired(
            approvalRequestId = decision.approvalRequestId,
            correlationKind = decision.correlationKind,
            correlationKey = decision.correlationKey,
            requiredScopes = decision.requiredScopes,
            reasons = decision.reasons,
        )
    }

    private fun denyAndReturn(scope: IdempotencyScope, reason: String, now: Instant): JobStartHandlerOutcome {
        // Reserved → deny() darf nicht no-op sein; wenn doch (Race), syntheseize
        // einen Fallback-expiresAt.
        val expiresAt = idempotencyStore.deny(scope, reason, now)
            ?: now.plusSeconds(DENIAL_FALLBACK_SECONDS)
        return JobStartHandlerOutcome.PolicyDenied(reason, expiresAt)
    }

    private fun handleExistingAwaitingApproval(
        request: JobStartRequest,
        scope: IdempotencyScope,
        fingerprint: String,
        durableChallenge: dev.dmigrate.server.core.approval.ApprovalChallenge?,
    ): JobStartHandlerOutcome {
        val token = request.approvalToken
        return if (token != null) {
            handleApprovedRetry(request, scope, fingerprint, token, durableChallenge)
        } else if (durableChallenge != null) {
            // Plan §5.5 (Review-Fix Blocker #3): Replay-Pfad ohne Token
            // liefert die DURABLE-gespeicherte Challenge zurueck — gleicher
            // approvalRequestId, gleiche requiredScopes, kein
            // re-decide-Drift.
            JobStartHandlerOutcome.PolicyRequired(
                approvalRequestId = durableChallenge.approvalRequestId,
                correlationKind = durableChallenge.correlationKind,
                correlationKey = durableChallenge.correlationKey,
                requiredScopes = durableChallenge.requiredScopes,
                reasons = durableChallenge.reasons,
            )
        } else {
            // Bestands-Pfad ohne durable Challenge (z.B. wenn ein
            // Bestands-Caller markAwaitingApproval ohne challenge gerufen
            // hat): re-decide Policy. Defensive — die Phase-E-Production-
            // Wirebahn liefert immer eine durable Challenge.
            val attempt = PolicyAttempt(
                tenantId = request.tenantId,
                callerId = request.callerId,
                toolName = request.toolName,
                correlationKind = ApprovalCorrelationKind.IDEMPOTENCY_KEY,
                correlationKey = request.idempotencyKey!!,
                payloadFingerprint = fingerprint,
                resourceRefs = request.refs.map { it.value },
            )
            when (val decision = policyService.decide(attempt)) {
                is PolicyDecision.RequiresApproval ->
                    JobStartHandlerOutcome.PolicyRequired(
                        approvalRequestId = decision.approvalRequestId,
                        correlationKind = decision.correlationKind,
                        correlationKey = decision.correlationKey,
                        requiredScopes = decision.requiredScopes,
                        reasons = decision.reasons,
                    )
                is PolicyDecision.Allowed -> commitJob(request, scope)
                is PolicyDecision.Denied -> denyAndReturn(scope, decision.reasonCode, request.now)
            }
        }
    }

    private fun handleApprovedRetry(
        request: JobStartRequest,
        scope: IdempotencyScope,
        fingerprint: String,
        rawToken: String,
        durableChallenge: dev.dmigrate.server.core.approval.ApprovalChallenge?,
    ): JobStartHandlerOutcome {
        val tokenFingerprint = ApprovalTokenFingerprint.compute(rawToken)
        // Plan §5.5 (Review-Fix Blocker #3): die Challenge wurde beim
        // markAwaitingApproval durabel gespeichert. ApprovalAttempt
        // benutzt JETZT die durable approvalRequestId + requiredScopes —
        // damit greift der ApprovalGrantValidator-Anti-Replay-Check
        // (Plan §5.5 "Ein Grant fuer eine alte oder erneuerte
        // approvalRequestId ist ungueltig") echt: ein Grant fuer einen
        // anderen approvalRequestId wird via ApprovalRequestIdMismatch
        // abgelehnt. ScopeMismatch greift ebenfalls — die durable
        // requiredScopes sind nicht mehr empty.
        //
        // durableChallenge == null ist Bestands-Compat (Stores ohne
        // Challenge-Persistierung); dann faellt der Service auf das
        // alte E.6-(3a)-Verhalten zurueck (Approval-Request-Id-Lookup
        // im Grant — Anti-Replay-Bypass).
        val approvalRequestId = durableChallenge?.approvalRequestId
            ?: approvalGrantStore.findByTokenFingerprint(request.tenantId, tokenFingerprint)
                ?.approvalRequestId
                ?: ""
        val requiredScopes = durableChallenge?.requiredScopes ?: emptySet()
        val attempt = ApprovalAttempt(
            tokenFingerprint = tokenFingerprint,
            approvalRequestId = approvalRequestId,
            tenantId = request.tenantId,
            callerId = request.callerId,
            toolName = request.toolName,
            correlationKind = ApprovalCorrelationKind.IDEMPOTENCY_KEY,
            correlationKey = request.idempotencyKey!!,
            payloadFingerprint = fingerprint,
            requiredScopes = requiredScopes,
        )
        val outcome = approvedRetryService.retry(attempt, scope, request.now, request.jobBuilder)
        return outcome.toHandlerOutcome()
    }

    companion object {
        const val DENIAL_FALLBACK_SECONDS: Long = 600
        const val QUOTA_AMOUNT_PER_JOB: Long = 1L
        val DEFAULT_QUOTA_LEASE: Duration = Duration.ofSeconds(60)
    }
}

/**
 * Eingabe-Bundle fuer [JobStartOrchestrator.start]. Die MCP-Tool-Handler
 * aus AP E.6 (3b) konstruieren diese Struktur aus dem geparsten
 * `tools/call`-Argument plus dem aktuellen Principal.
 */
data class JobStartRequest(
    val toolName: String,
    val tenantId: TenantId,
    val callerId: PrincipalId,
    val idempotencyKey: String?,
    val approvalToken: String?,
    val payload: JsonValue.Obj,
    val refs: List<RefField>,
    val now: Instant,
    val jobBuilder: (jobId: String, createdAt: Instant) -> JobRecord,
    /**
     * Phase E §7.10 (Review-Fix #8): optionaler AuditFields-Sink. Wenn
     * gesetzt, schreibt der Orchestrator den berechneten
     * payloadFingerprint und die resourceRefs in dieses Objekt — der
     * `AuditScope.around`-finally-Pfad sieht die populated Werte beim
     * Emit des AuditEvent. `null` haelt Bestands-Caller unveraendert.
     */
    val auditFields: dev.dmigrate.server.application.audit.AuditFields? = null,
)

/**
 * Outcome eines vollstaendigen Start-Tool-Aufrufs. Wrapper um
 * [JobStartOutcome] plus zusaetzliche Branches fuer
 * pre-store-Validation und PolicyRequired-Challenge.
 */
sealed interface JobStartHandlerOutcome {

    /**
     * Phase E §7.7: Job ist commited und (bei wired Auto-Dispatch) an
     * den Worker uebergeben.
     *
     * **WICHTIG (Re-Review B2):** [record] ist der Snapshot UNMITTELBAR
     * NACH `JobStartTransaction.commit`, also typisch `status=QUEUED`.
     * Bei `SyncExecutor` hat der Auto-Dispatch den Worker bereits
     * synchron abgeschlossen, bevor diese Outcome zurueckkehrt — der
     * echte aktuelle Status liegt dann im
     * [dev.dmigrate.server.ports.JobStore] (z.B. SUCCEEDED). Caller,
     * die den Endstatus brauchen, muessen den Job-Store erneut
     * abfragen. [record] spiegelt nur den Commit-Zeitpunkt — Plan §7.7
     * async-Charakter, der Wire-Response gibt `jobId + QUEUED + poll`
     * zurueck.
     */
    data class Started(
        val jobId: String,
        val record: JobRecord,
        val cancellationSource: CancellationTokenSource,
    ) : JobStartHandlerOutcome

    data class AlreadyStarted(val jobId: String) : JobStartHandlerOutcome

    data class Pending(val leaseExpiresAt: Instant) : JobStartHandlerOutcome

    data class PolicyRequired(
        val approvalRequestId: String,
        val correlationKind: ApprovalCorrelationKind,
        val correlationKey: String,
        val requiredScopes: Set<String>,
        val reasons: List<String>,
    ) : JobStartHandlerOutcome

    data class PolicyDenied(val reason: String, val expiresAt: Instant) : JobStartHandlerOutcome

    data class Failed(val reason: String, val expiresAt: Instant) : JobStartHandlerOutcome

    data class IdempotencyConflict(val existingFingerprint: String) : JobStartHandlerOutcome

    data class ValidationError(val invalid: JobStartInputValidation.Invalid) : JobStartHandlerOutcome

    /**
     * Phase E §7.9: aktive Jobquote ueberschritten. Plan §7.9 line
     * 1294-1295: `retryAfter` ist Pflichtfeld; Tool-Handler projiziert
     * es in den `RATE_LIMITED`-Envelope und richtet die Idempotency-
     * Lease auf maximal `now + retryAfter` aus.
     */
    data class RateLimited(
        val retryAfter: Duration,
        val current: Long,
        val limit: Long,
        /**
         * Phase E3 § 3.5 / § 10 Q5: Diskriminator zwischen
         * Tenant-/Caller-Quota (`ACTIVE_JOBS_QUOTA`, Default — Phase-E
         * Quota-Pfad) und Executor-Pool-Saturation (`EXECUTOR_SATURATED`,
         * Phase-E3 Admission-Pfad). Wire-Caller sehen das Feld immer in
         * den `RATE_LIMITED`-Details.
         */
        val reason: String = JobStartReason.ACTIVE_JOBS_QUOTA,
    ) : JobStartHandlerOutcome
}

private fun JobStartOutcome.toHandlerOutcome(): JobStartHandlerOutcome = when (this) {
    is JobStartOutcome.Started -> JobStartHandlerOutcome.Started(jobId, record, cancellationSource)
    is JobStartOutcome.AlreadyStarted -> JobStartHandlerOutcome.AlreadyStarted(jobId)
    is JobStartOutcome.Pending -> JobStartHandlerOutcome.Pending(leaseExpiresAt)
    is JobStartOutcome.AwaitingApproval ->
        // Should not reach here from approvedRetryService — but defensive.
        JobStartHandlerOutcome.Pending(expiresAt)
    is JobStartOutcome.Denied -> JobStartHandlerOutcome.PolicyDenied(reason, expiresAt)
    is JobStartOutcome.Failed -> JobStartHandlerOutcome.Failed(reason, expiresAt)
    is JobStartOutcome.Conflict -> JobStartHandlerOutcome.IdempotencyConflict(existingFingerprint)
    is JobStartOutcome.RateLimited ->
        JobStartHandlerOutcome.RateLimited(retryAfter, current, limit, reason)
}
