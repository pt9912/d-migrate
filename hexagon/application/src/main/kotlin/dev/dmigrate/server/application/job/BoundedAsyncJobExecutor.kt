package dev.dmigrate.server.application.job

import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded-Async-Pool fuer LF-012 / LN-011 / LN-017 / LN-027 Job-Worker. Wrappt einen [ThreadPoolExecutor] mit fixer
 * Pool-Groesse, bounded [ArrayBlockingQueue] und benannten
 * Daemon-Threads. Backpressure laeuft ueber das vorgelagerte
 * [BoundedAsyncJobDispatchAdmission]-Gate VOR dem Job-Commit; ein
 * [ExecutorClosedException] aus dem Reject-Handler tritt nur im
 * Shutdown-Race auf und wird vom Orchestrator separat behandelt.
 *
 * Uncaught Exceptions im Worker werden vom Thread-Uncaught-Handler
 * geloggt; der Pool startet Worker-Threads automatisch nach (Standard-
 * Verhalten von [ThreadPoolExecutor]), keine Pool-Death.
 */
class BoundedAsyncJobExecutor(
    private val cfg: JobExecutorConfig.Async,
) : Executor {

    private val rejectedCounter: AtomicLong = AtomicLong(0)

    private val pool: ThreadPoolExecutor = ThreadPoolExecutor(
        cfg.coreThreads,
        cfg.maxThreads,
        cfg.keepAliveSeconds,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(cfg.queueCapacity),
        NamedDaemonThreadFactory(cfg.threadNamePrefix),
    ).apply {
        rejectedExecutionHandler = RejectedExecutionHandler { _, _ ->
            rejectedCounter.incrementAndGet()
            throw ExecutorClosedException("Job executor rejected task (closed or saturated)")
        }
    }

    override fun execute(command: Runnable) {
        pool.execute(command)
    }

    /**
     * Graceful shutdown: blockiert neue Submissions und wartet bis zu
     * [timeout] auf den Drain. Liefert `true` wenn alle in-flight
     * Tasks vor Ablauf beendet sind, `false` sonst.
     */
    fun shutdown(timeout: Duration): Boolean {
        pool.shutdown()
        return pool.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    /** Eskalation: bricht in-flight Tasks per Interrupt ab. */
    fun shutdownNow(): List<Runnable> = pool.shutdownNow()

    fun status(): JobExecutorStatus = JobExecutorStatus(
        active = pool.activeCount.toLong(),
        queued = pool.queue.size.toLong(),
        completed = pool.completedTaskCount,
        rejected = rejectedCounter.get(),
        capacity = cfg.admissionCapacity.toLong(),
    )

    private class NamedDaemonThreadFactory(private val prefix: String) : ThreadFactory {
        private val counter: AtomicInteger = AtomicInteger(0)
        override fun newThread(r: Runnable): Thread {
            val t = Thread(r, "$prefix-${counter.incrementAndGet()}")
            t.isDaemon = true
            t.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, e ->
                LOG.error("Uncaught exception on worker thread {}", thread.name, e)
            }
            return t
        }

        companion object {
            private val LOG = LoggerFactory.getLogger(NamedDaemonThreadFactory::class.java)
        }
    }
}

/**
 * Spezialisierte [RejectedExecutionException] fuer Saturation-/Shutdown-
 * Rejects. Der Orchestrator nutzt den Typ als Diskriminator gegenueber
 * generischen RejectedExecutionExceptions, die von einem fremden
 * [Executor] kommen koennten (LF-012 / LN-011 / LN-017 / LN-027).
 */
class ExecutorClosedException(message: String) : RejectedExecutionException(message)

/**
 * Lifecycle-Wrapper, der Admission und Pool zusammen schliesst (Plan
 * §3.3): admission-close vor pool-shutdown verhindert neue
 * Permit-Acquires waehrend des Drain. Bei Timeout eskaliert der
 * Lifecycle gemaess LF-012 / LN-011 / LN-017 / LN-027 per [BoundedAsyncJobExecutor.shutdownNow],
 * damit lang laufende Worker ein Interrupt-Signal bekommen.
 */
class BoundedAsyncJobExecutorLifecycle(
    private val executor: BoundedAsyncJobExecutor,
    private val admission: BoundedAsyncJobDispatchAdmission,
) : JobExecutorLifecycle {

    override fun status(): JobExecutorStatus = executor.status()

    override fun shutdown(timeout: Duration): Boolean {
        admission.close()
        val drained = executor.shutdown(timeout)
        if (!drained) {
            executor.shutdownNow()
        }
        return drained
    }
}
