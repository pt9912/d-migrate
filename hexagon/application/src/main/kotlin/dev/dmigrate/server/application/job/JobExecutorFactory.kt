package dev.dmigrate.server.application.job

import java.util.concurrent.Executor

/**
 * Bauplatz fuer das vom Host (MCP-Bootstrap) konsumierte
 * Job-Executor-Tripel — gemaess LF-012 / LN-011 / LN-017 / LN-027.
 *
 * Der Caller uebergibt eine [JobExecutorConfig]; die Factory liefert
 * ein [JobExecutorBundle] mit `executor` (an [JobDispatcher]),
 * `admission` (an [JobStartOrchestrator]) und `lifecycle` (an den
 * Shutdown-Hook). Validierung der Async-Felder lebt in
 * [JobExecutorConfig.Async]'s `init {}`-Block.
 *
 * Synchrone Tripel teilen Singletons; jeder Async-Aufruf erzeugt eine
 * frische [BoundedAsyncJobExecutor]/[BoundedAsyncJobDispatchAdmission]-
 * Paarung — die beiden teilen Lifecycle-State (Admission schliesst vor
 * Pool-Drain) und duerfen NIE einzeln getauscht werden.
 */
object JobExecutorFactory {

    fun create(config: JobExecutorConfig): JobExecutorBundle = when (config) {
        JobExecutorConfig.Sync -> SYNC_BUNDLE
        is JobExecutorConfig.Async -> {
            val admission = BoundedAsyncJobDispatchAdmission(config)
            val executor = BoundedAsyncJobExecutor(config)
            JobExecutorBundle(
                executor = executor,
                admission = admission,
                lifecycle = BoundedAsyncJobExecutorLifecycle(executor, admission),
            )
        }
    }

    private val SYNC_BUNDLE: JobExecutorBundle = JobExecutorBundle(
        executor = SyncExecutor,
        admission = SyncJobDispatchAdmission,
        lifecycle = SyncExecutorLifecycle,
    )
}

data class JobExecutorBundle(
    val executor: Executor,
    val admission: JobDispatchAdmission,
    val lifecycle: JobExecutorLifecycle,
)
