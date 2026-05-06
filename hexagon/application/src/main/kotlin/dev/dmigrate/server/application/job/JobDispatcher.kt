package dev.dmigrate.server.application.job

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.cancel.OperationCancelSource
import dev.dmigrate.core.cancel.OperationCancelledException
import dev.dmigrate.server.application.audit.SecretScrubber
import dev.dmigrate.server.application.quota.OwnerAwareQuotaService
import dev.dmigrate.server.core.job.JobError
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.job.ManagedJob
import dev.dmigrate.server.ports.JobStore
import dev.dmigrate.server.ports.JobTransitionOutcome
import dev.dmigrate.server.ports.JobWorker
import dev.dmigrate.server.ports.JobWorkerOutcome
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Phase E §7.7 Job-Dispatcher.
 *
 * Faedet das produktive Worker-Lifecycle durch den
 * [dev.dmigrate.server.ports.JobStore] und nimmt einem konkreten
 * Tool-Adapter die Status-Pflege ab. Verwendet die in AP E.2
 * etablierten CAS-Methoden ([JobStore.transitionStatus]):
 *
 * 1. `QUEUED -> RUNNING` — atomar; Race-Konflikte (z.B. paralleler
 *    Cancel) liefern eine [JobWorkerOutcome.Failed]-Diagnose mit
 *    Fehler-Code `DISPATCH_RACE`, ohne den Worker auszufuehren.
 * 2. `worker.execute(record, token)` — der Worker ist verantwortlich
 *    fuer Cancel-Propagation, Artefakt-Publish und Connection-
 *    Materialisierung (Plan §7.7).
 * 3. `RUNNING -> {SUCCEEDED | FAILED | CANCELLED}` — Outcome wird
 *    deterministisch auf Job-Felder gemapped (`artifacts`, `error`,
 *    `cancelRequest.signalAcked` + `ackedAt`).
 *
 * [executor] ist der Auspraegungs-Punkt fuer sync-vs-async:
 *
 * - [SyncExecutor] (Default) laeuft auf dem Caller-Thread — ideal
 *   fuer Unit-Tests und kleine Single-Job-Bootstraps.
 * - Eine `ExecutorService.asExecutor()` (Java) waere die Production-
 *   Variante; der Dispatcher selbst owned ihre Lifecycle nicht.
 *
 * Ungefangene Worker-Exceptions (alles ausser [OperationCancelledException])
 * werden als [JobWorkerOutcome.Failed] mit `errorCode = "RUNNER_ERROR"`
 * gemapped — der Dispatcher reicht NIE eine Exception ueber das
 * `CompletableFuture` an den Caller weiter, damit der Job-Lifecycle
 * stets vollstaendig geschlossen wird.
 *
 * Cancel-Source-Klassifikation (JOB_CANCEL vs. RUNNER_TIMEOUT) lebt in
 * AP E.7 (2/6); diese AP-Stufe behandelt jede [OperationCancelledException]
 * als generischen Cancel-Branch.
 */
class JobDispatcher(
    private val jobStore: JobStore,
    private val executor: Executor = SyncExecutor,
    private val clock: Clock = Clock.systemUTC(),
    /**
     * Plan §7.7 line 1182-1183: Cancel-Reason wandert nur SCRUBBED in
     * Job-/Audit-Metadaten. Default ist [SecretScrubber.scrub], die
     * Bearer-/Approval-Token-/JDBC-URL-Marker entfernt. Tests koennen
     * die Identitaet `{ it }` setzen, wenn sie den rohen Reason
     * inspizieren wollen.
     */
    private val cancelReasonScrubber: (String) -> String = SecretScrubber::scrub,
    /**
     * Phase E §7.9: optionaler owner-aware Quota-Service. Wenn gesetzt,
     * gibt der Dispatcher beim Terminal-Pfad (succeeded/failed/cancelled
     * + Runner-Timeout-Cleanup) den Slot via
     * `releaseForOwner(record.quotaReservationOwnerId, now)` frei.
     * Plan §7.9 line 1291-1292.
     */
    private val quotaService: OwnerAwareQuotaService? = null,
    /**
     * Phase E3 § 3.7 (E3.6): optionaler Snapshot-Provider fuer
     * Pool-Telemetrie im `job.dispatch.scheduled`-Log-Event
     * (`queueDepth`-Feld). Default `null` -> queueDepth = 0
     * (Sync-Pfad hat keine Queue). Production-Wiring uebergibt
     * typischerweise `executorBundle.lifecycle::status` — der
     * Dispatcher kennt den [JobExecutorLifecycle]-Typ NICHT, sondern
     * akzeptiert nur die Funktionssignatur (Plan §3.3 dispatcher-
     * agnostic).
     */
    private val executorStatusSnapshot: () -> JobExecutorStatus? = { null },
) {

    private val log = org.slf4j.LoggerFactory.getLogger(JobDispatcher::class.java)

    fun dispatch(
        record: JobRecord,
        worker: JobWorker,
        token: CancellationToken,
        /**
         * Phase E3 § 3.5 + § 6.2: optionaler [JobDispatchPermit] aus dem
         * Admission-Gate. Der Dispatcher schliesst ihn im `finally` der
         * Worker-Runnable — also exakt nach `applyTerminal` oder einem
         * Defensive-Catch. Default `null` haelt Bestands-Tests/Wiring
         * unveraendert (Sync-Pfad ohne Admission, kein Permit-Acquire).
         *
         * Plan-Akzeptanz: `JobDispatchPermit.close()` ist idempotent und
         * no-throw (siehe [SyncJobDispatchAdmission.NoOpPermit] und
         * [BoundedAsyncJobDispatchAdmission.SinglePermit]) — deshalb
         * muss der Dispatcher den Release nicht in einen weiteren
         * try-catch verpacken.
         */
        permit: JobDispatchPermit? = null,
    ): CompletableFuture<JobWorkerOutcome> {
        val scheduledAt = clock.instant()
        // Plan E3 § 3.7 Log-Event #1: am Boundary, BEVOR der Runnable
        // im Pool landet. queueDepth ist die aktuelle Queue-Tiefe vor
        // dem Submit (nicht nach), aus dem Snapshot-Provider.
        log.info(
            EVENT_DISPATCH_SCHEDULED +
                " jobId={} tenant={} tool={} queueDepth={}",
            record.managedJob.jobId,
            record.tenantId.value,
            record.managedJob.operation,
            executorStatusSnapshot()?.queued ?: 0L,
        )
        val future = CompletableFuture<JobWorkerOutcome>()
        executor.execute {
            try {
                future.complete(runOnce(record, worker, token, scheduledAt))
            } catch (t: Throwable) {
                // Defensive: applyTerminal-Folgefehler oder
                // unerwartete jobStore-Exceptions erreichen den Caller.
                future.completeExceptionally(t)
            } finally {
                permit?.close()
            }
        }
        return future
    }

    private fun runOnce(
        record: JobRecord,
        worker: JobWorker,
        token: CancellationToken,
        scheduledAt: Instant,
    ): JobWorkerOutcome {
        val startedAt = clock.instant()
        val running = jobStore.transitionStatus(
            tenantId = record.tenantId,
            jobId = record.managedJob.jobId,
            allowedFromStatuses = setOf(JobStatus.QUEUED),
        ) { mj -> mj.copy(status = JobStatus.RUNNING, updatedAt = startedAt) }

        val runningRecord = when (running) {
            is JobTransitionOutcome.Applied -> running.record
            is JobTransitionOutcome.IllegalTransition -> {
                // Plan E3 § 3.6 + § 6.3: cancel-while-queued. Wenn der
                // Job zwischen Submit und Worker-Start durch
                // JobCancelService.cancelQueuedJob auf CANCELLED gesetzt
                // wurde, fuehren wir den Worker NICHT aus. Der finale
                // Record (signalAcked = true, ackedAt, requestedReason)
                // wurde bereits beim QUEUED -> CANCELLED CAS persistiert;
                // applyTerminal wird hier NICHT gerufen, weshalb auch
                // kein zweiter Quota-Release stattfindet (JobCancelService
                // hat den Slot bereits freigegeben — Plan §7.9 line
                // 1291-1292). Nur fuer andere IllegalTransition-Quellen
                // (z.B. RUNNING, SUCCEEDED) bleibt das DISPATCH_RACE-
                // Failed-Mapping aktiv.
                val skipOutcome = if (running.currentStatus == JobStatus.CANCELLED) {
                    JobWorkerOutcome.Cancelled(reason = REASON_GENERIC_CANCEL)
                } else {
                    JobWorkerOutcome.Failed(
                        errorCode = REASON_DISPATCH_RACE,
                        errorMessage = "Job not in QUEUED (current=${running.currentStatus})",
                    )
                }
                logFinished(record, skipOutcome, scheduledAt, clock.instant())
                return skipOutcome
            }
            is JobTransitionOutcome.NotFound -> {
                val notFoundOutcome = JobWorkerOutcome.Failed(
                    errorCode = REASON_DISPATCH_NOT_FOUND,
                    errorMessage = "Job not found: ${record.managedJob.jobId}",
                )
                logFinished(record, notFoundOutcome, scheduledAt, clock.instant())
                return notFoundOutcome
            }
        }

        // Plan E3 § 3.7 Log-Event #2: nach erfolgreichem QUEUED -> RUNNING.
        // waitMs ist die Zeit zwischen dispatch()-Aufruf und dem realen
        // Worker-Start (queue-Wait fuer Async-Pool, ~0 fuer Sync).
        log.info(
            EVENT_DISPATCH_STARTED +
                " jobId={} tenant={} tool={} waitMs={}",
            record.managedJob.jobId,
            record.tenantId.value,
            record.managedJob.operation,
            java.time.Duration.between(scheduledAt, startedAt).toMillis(),
        )

        val outcome = try {
            worker.execute(runningRecord, token)
        } catch (e: OperationCancelledException) {
            // Plan §7.7: Source-Klassifikation. JOB_CANCEL ist eine
            // echte Cancel-Operation -> job-status CANCELLED.
            // RUNNER_TIMEOUT ist eine Budget-Grenze -> job-status
            // FAILED(error.code=OPERATION_TIMEOUT).
            when (e.source) {
                OperationCancelSource.JOB_CANCEL ->
                    JobWorkerOutcome.Cancelled(reason = e.reason ?: REASON_GENERIC_CANCEL)
                OperationCancelSource.RUNNER_TIMEOUT ->
                    JobWorkerOutcome.Failed(
                        errorCode = ERROR_CODE_OPERATION_TIMEOUT,
                        errorMessage = e.reason ?: REASON_GENERIC_TIMEOUT,
                    )
            }
        } catch (e: Exception) {
            JobWorkerOutcome.Failed(
                errorCode = REASON_RUNNER_ERROR,
                errorMessage = e.message ?: e::class.simpleName.orEmpty(),
            )
        }
        val terminalAt = clock.instant()
        applyTerminal(record, outcome, terminalAt)
        logFinished(record, outcome, startedAt, terminalAt)
        return outcome
    }

    /**
     * Plan E3 § 3.7 Log-Event #3: terminaler Outcome aus Worker-Sicht.
     * `status` reflektiert die Job-Lifecycle-Variante, `errorCode` nur
     * fuer [JobWorkerOutcome.Failed] (ansonsten leer fuer
     * slf4j-Kompatibilitaet). Wird VON ALLEN runOnce-Pfaden gerufen
     * (worker-run UND skip-Branches), sodass jeder `scheduled`-Event
     * genau ein `finished`-Event hat.
     */
    private fun logFinished(
        record: JobRecord,
        outcome: JobWorkerOutcome,
        startedAt: Instant,
        terminalAt: Instant,
    ) {
        log.info(
            EVENT_DISPATCH_FINISHED +
                " jobId={} tenant={} tool={} status={} durationMs={} errorCode={}",
            record.managedJob.jobId,
            record.tenantId.value,
            record.managedJob.operation,
            statusOf(outcome),
            java.time.Duration.between(startedAt, terminalAt).toMillis(),
            (outcome as? JobWorkerOutcome.Failed)?.errorCode ?: "",
        )
    }

    private fun statusOf(outcome: JobWorkerOutcome): String = when (outcome) {
        is JobWorkerOutcome.Succeeded -> "SUCCEEDED"
        is JobWorkerOutcome.Cancelled -> "CANCELLED"
        is JobWorkerOutcome.Failed -> "FAILED"
    }

    private fun applyTerminal(
        record: JobRecord,
        outcome: JobWorkerOutcome,
        terminalAt: Instant,
    ) {
        val transformer: (ManagedJob) -> ManagedJob = when (outcome) {
            is JobWorkerOutcome.Succeeded -> { mj ->
                mj.copy(
                    status = JobStatus.SUCCEEDED,
                    updatedAt = terminalAt,
                    artifacts = outcome.artifactRefs,
                )
            }
            is JobWorkerOutcome.Cancelled -> { mj ->
                // Plan §7.2 Idempotenz: ein bereits durabel gespeicherter
                // requestedReason (via JobStore.markCancelRequested aus
                // job_cancel) wird NICHT ueberschrieben. Der Worker-
                // Reason ist nur ein Fallback fuer Worker-internen Cancel.
                // Plan §7.7 line 1182-1183: Reason wandert nur SCRUBBED in
                // die Job-Metadaten.
                val scrubbed = cancelReasonScrubber(outcome.reason)
                mj.copy(
                    status = JobStatus.CANCELLED,
                    updatedAt = terminalAt,
                    cancelRequest = mj.cancelRequest.copy(
                        signalAcked = true,
                        ackedAt = terminalAt,
                        requestedReason = mj.cancelRequest.requestedReason ?: scrubbed,
                    ),
                )
            }
            is JobWorkerOutcome.Failed -> { mj ->
                mj.copy(
                    status = JobStatus.FAILED,
                    updatedAt = terminalAt,
                    error = JobError(
                        code = outcome.errorCode,
                        message = outcome.errorMessage,
                        exitCode = outcome.exitCode,
                    ),
                )
            }
        }
        jobStore.transitionStatus(
            tenantId = record.tenantId,
            jobId = record.managedJob.jobId,
            allowedFromStatuses = setOf(JobStatus.RUNNING),
            transformer = transformer,
        )
        // Plan §7.9 line 1291-1292: release nach succeeded/failed/cancelled.
        // Wenn KEIN OwnerId auf dem Record (Bestands-Pfad ohne Quota) -> no-op.
        record.quotaReservationOwnerId?.let { ownerId ->
            quotaService?.releaseForOwner(ownerId, terminalAt)
        }
    }

    companion object {
        const val REASON_DISPATCH_RACE: String = "DISPATCH_RACE"
        const val REASON_DISPATCH_NOT_FOUND: String = "DISPATCH_NOT_FOUND"
        const val REASON_RUNNER_ERROR: String = "RUNNER_ERROR"
        const val REASON_GENERIC_CANCEL: String = "operation cancelled"
        const val REASON_GENERIC_TIMEOUT: String = "runner timeout"

        /** Plan §7.7: error.code-Wert fuer RUNNER_TIMEOUT-induzierten FAILED. */
        const val ERROR_CODE_OPERATION_TIMEOUT: String = "OPERATION_TIMEOUT"

        /** Plan E3 § 3.7 Log-Event-Namen (E3.6). */
        const val EVENT_DISPATCH_SCHEDULED: String = "job.dispatch.scheduled"
        const val EVENT_DISPATCH_STARTED: String = "job.dispatch.started"
        const val EVENT_DISPATCH_FINISHED: String = "job.dispatch.finished"
    }
}

/**
 * Synchroner [Executor]-Default fuer Tests und Single-Job-Bootstraps:
 * laeuft den uebergebenen [Runnable] auf dem aufrufenden Thread, ohne
 * eine Hintergrund-Thread-Pool-Lifecycle zu verlangen. Production-
 * Wiring uebergibt einen `ExecutorService` (z.B.
 * `Executors.newCachedThreadPool()`), dessen Lifecycle NICHT vom
 * Dispatcher gemanaged wird.
 */
object SyncExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
}
