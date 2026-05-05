package dev.dmigrate.server.application.job

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.cancel.OperationCancelledException
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
) {

    fun dispatch(
        record: JobRecord,
        worker: JobWorker,
        token: CancellationToken,
    ): CompletableFuture<JobWorkerOutcome> {
        val future = CompletableFuture<JobWorkerOutcome>()
        executor.execute {
            try {
                future.complete(runOnce(record, worker, token))
            } catch (t: Throwable) {
                // Defensive: applyTerminal-Folgefehler oder
                // unerwartete jobStore-Exceptions erreichen den Caller.
                future.completeExceptionally(t)
            }
        }
        return future
    }

    private fun runOnce(
        record: JobRecord,
        worker: JobWorker,
        token: CancellationToken,
    ): JobWorkerOutcome {
        val startedAt = clock.instant()
        val running = jobStore.transitionStatus(
            tenantId = record.tenantId,
            jobId = record.managedJob.jobId,
            allowedFromStatuses = setOf(JobStatus.QUEUED),
        ) { mj -> mj.copy(status = JobStatus.RUNNING, updatedAt = startedAt) }

        val runningRecord = when (running) {
            is JobTransitionOutcome.Applied -> running.record
            is JobTransitionOutcome.IllegalTransition ->
                return JobWorkerOutcome.Failed(
                    errorCode = REASON_DISPATCH_RACE,
                    errorMessage = "Job not in QUEUED (current=${running.currentStatus})",
                )
            is JobTransitionOutcome.NotFound ->
                return JobWorkerOutcome.Failed(
                    errorCode = REASON_DISPATCH_NOT_FOUND,
                    errorMessage = "Job not found: ${record.managedJob.jobId}",
                )
        }

        val outcome = try {
            worker.execute(runningRecord, token)
        } catch (e: OperationCancelledException) {
            JobWorkerOutcome.Cancelled(reason = e.reason ?: REASON_GENERIC_CANCEL)
        } catch (e: Exception) {
            JobWorkerOutcome.Failed(
                errorCode = REASON_RUNNER_ERROR,
                errorMessage = e.message ?: e::class.simpleName.orEmpty(),
            )
        }
        applyTerminal(record, outcome, clock.instant())
        return outcome
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
                mj.copy(
                    status = JobStatus.CANCELLED,
                    updatedAt = terminalAt,
                    cancelRequest = mj.cancelRequest.copy(
                        signalAcked = true,
                        ackedAt = terminalAt,
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
    }

    companion object {
        const val REASON_DISPATCH_RACE: String = "DISPATCH_RACE"
        const val REASON_DISPATCH_NOT_FOUND: String = "DISPATCH_NOT_FOUND"
        const val REASON_RUNNER_ERROR: String = "RUNNER_ERROR"
        const val REASON_GENERIC_CANCEL: String = "operation cancelled"
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
