package dev.dmigrate.server.application.job

import java.time.Duration
import java.time.Instant
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LF-012 / LN-011 / LN-017: Admission-Gate fuer den Job-Dispatch.
 * Der Orchestrator fragt das Gate **vor**
 * `JobStartTransaction.commit`; bei [JobDispatchAdmissionOutcome.Saturated]
 * wird ohne Job-Eintrag mit `RATE_LIMITED` (`reason = EXECUTOR_SATURATED`)
 * geantwortet. Bei [JobDispatchAdmissionOutcome.Closed] (Shutdown-Race)
 * markiert der Orchestrator die Idempotency-Reservation als `FAILED`.
 */
interface JobDispatchAdmission {
    fun tryAcquire(now: Instant): JobDispatchAdmissionOutcome
}

sealed interface JobDispatchAdmissionOutcome {
    data class Granted(val permit: JobDispatchPermit) : JobDispatchAdmissionOutcome

    data class Saturated(
        val retryAfter: Duration,
        val current: Long,
        val limit: Long,
    ) : JobDispatchAdmissionOutcome

    data object Closed : JobDispatchAdmissionOutcome
}

/**
 * LF-012 / LN-011: Permit-Vertrag. [close] MUSS idempotent UND no-throw
 * sein — Implementierungen suppressen Release-Fehler, damit ein
 * defekter Permit-Release den primaeren Fehlerpfad im Orchestrator
 * nicht blockiert.
 */
fun interface JobDispatchPermit : AutoCloseable {
    override fun close()
}

/**
 * Sync-Default: immer [JobDispatchAdmissionOutcome.Granted], no-op
 * Permit. Verwendet, wenn `JobExecutorConfig` = [JobExecutorConfig.Sync]
 * (kein Pool, kein Backpressure noetig).
 */
object SyncJobDispatchAdmission : JobDispatchAdmission {
    private val NoOpPermit = JobDispatchPermit { /* no-op */ }
    override fun tryAcquire(now: Instant): JobDispatchAdmissionOutcome =
        JobDispatchAdmissionOutcome.Granted(NoOpPermit)
}

/**
 * Bounded-Async-Admission: Semaphore mit
 * `cfg.maxThreads + cfg.queueCapacity` Permits (LF-012 / LN-011 / LN-017).
 * Bei Shutdown wird [close] gerufen; ab dann liefert
 * [tryAcquire] [JobDispatchAdmissionOutcome.Closed]. Die Lifecycle-
 * Komposition (close-vor-Pool-Drain) lebt in
 * [BoundedAsyncJobExecutorLifecycle].
 */
class BoundedAsyncJobDispatchAdmission(
    private val cfg: JobExecutorConfig.Async,
) : JobDispatchAdmission {

    private val capacity: Int = cfg.admissionCapacity
    private val permits: Semaphore = Semaphore(capacity)
    private val accepting: AtomicBoolean = AtomicBoolean(true)

    val capacityValue: Int get() = capacity
    fun availablePermits(): Int = permits.availablePermits()

    override fun tryAcquire(now: Instant): JobDispatchAdmissionOutcome {
        if (!accepting.get()) return JobDispatchAdmissionOutcome.Closed
        return if (permits.tryAcquire()) {
            JobDispatchAdmissionOutcome.Granted(SinglePermit(permits))
        } else {
            JobDispatchAdmissionOutcome.Saturated(
                retryAfter = cfg.retryAfter,
                current = capacity.toLong(),
                limit = capacity.toLong(),
            )
        }
    }

    /**
     * Schliesst die Admission fuer neue Acquires. Bereits ausgegebene
     * Permits bleiben gueltig und koennen weiter `close()`-en (sonst
     * waere kein graceful Drain moeglich).
     */
    fun close() {
        accepting.set(false)
    }

    private class SinglePermit(private val sem: Semaphore) : JobDispatchPermit {
        private val released: AtomicBoolean = AtomicBoolean(false)
        override fun close() {
            if (released.compareAndSet(false, true)) {
                @Suppress("TooGenericExceptionCaught", "SwallowedException")
                try {
                    sem.release()
                } catch (_: Throwable) {
                    // Permit-Vertrag: no-throw. Semaphore.release() wirft
                    // in der Praxis nicht; defensives Suppress verhindert,
                    // dass ein theoretischer Fehler den Caller-finally-
                    // Pfad bricht.
                }
            }
        }
    }
}
