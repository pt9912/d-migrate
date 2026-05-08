package dev.dmigrate.server.application.job

import java.time.Duration

/**
 * LF-012 / LN-011 / LN-017: Lifecycle-Vertrag fuer den Job-Executor.
 * Der Host (MCP-Server-Bootstrap) registriert
 * die Lifecycle-Instanz fuer JVM-Shutdown-Hooks — der
 * [JobDispatcher] selbst kennt das Interface nicht und sieht nur
 * [java.util.concurrent.Executor].
 */
interface JobExecutorLifecycle {
    /** Aktueller Pool-Snapshot (active/queued/completed/rejected/capacity). */
    fun status(): JobExecutorStatus

    /**
     * Graceful shutdown: schliesst die Admission (keine neuen Permits)
     * und drainiert in-flight Tasks bis [timeout]. Liefert `true` wenn
     * vor Ablauf alle Tasks beendet sind, `false` sonst.
     */
    fun shutdown(timeout: Duration): Boolean
}

data class JobExecutorStatus(
    val active: Long,
    val queued: Long,
    val completed: Long,
    val rejected: Long,
    val capacity: Long,
)

/**
 * Sync-Default-Lifecycle: kein Pool, kein Drain. `status()` liefert
 * Nullen (sync hat kein Aussagekraefte-Telemetry — Tasks laufen auf
 * dem Caller-Thread). `shutdown()` ist sofort `true`.
 */
object SyncExecutorLifecycle : JobExecutorLifecycle {
    private val EMPTY: JobExecutorStatus = JobExecutorStatus(
        active = 0L,
        queued = 0L,
        completed = 0L,
        rejected = 0L,
        capacity = 0L,
    )

    override fun status(): JobExecutorStatus = EMPTY
    override fun shutdown(timeout: Duration): Boolean = true
}
