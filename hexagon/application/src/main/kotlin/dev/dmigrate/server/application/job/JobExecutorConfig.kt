package dev.dmigrate.server.application.job

import java.time.Duration

/**
 * Adapter-neutrale Konfiguration fuer den Phase-E Job-Executor (Plan
 * §3.4 + §10 Q2 in `ImpPlan-0.9.6-E3.md`). YAML-/Env-Aufloesung lebt im
 * Host-/Adapter-Scope; `JobExecutorConfig` selbst kennt nur die
 * typisierten Felder.
 *
 * Validierung (z.B. `coreThreads > 0`, `maxThreads >= coreThreads`,
 * `queueCapacity > 0`) gehoert zu AP E3.2 — diese Datenklasse ist die
 * reine Struktur, die [BoundedAsyncJobExecutor] und
 * [BoundedAsyncJobDispatchAdmission] in E3.1 verbrauchen.
 */
sealed interface JobExecutorConfig {

    data object Sync : JobExecutorConfig

    /**
     * Bounded `ThreadPoolExecutor`-Konfiguration mit fester Pool-Groesse
     * (`coreThreads == maxThreads` als Default), bounded `ArrayBlockingQueue`
     * und konfigurierbarem Reject-Retry. Defaults gemaess Plan §3.2 + §10
     * Q1: fixed `4` Threads (kein CPU-skalierter Default), `1024`
     * Queue-Kapazitaet, `1s` Retry-After.
     */
    data class Async(
        val coreThreads: Int = DEFAULT_THREADS,
        val maxThreads: Int = DEFAULT_THREADS,
        val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
        val keepAliveSeconds: Long = DEFAULT_KEEP_ALIVE_SECONDS,
        val retryAfter: Duration = DEFAULT_RETRY_AFTER,
        val shutdownTimeout: Duration = DEFAULT_SHUTDOWN_TIMEOUT,
        val threadNamePrefix: String = DEFAULT_THREAD_NAME_PREFIX,
    ) : JobExecutorConfig {

        /** Gesamtkapazitaet (Threads + Queue) — Admission-Permits. */
        val admissionCapacity: Int get() = maxThreads + queueCapacity

        companion object {
            const val DEFAULT_THREADS: Int = 4
            const val DEFAULT_QUEUE_CAPACITY: Int = 1024
            const val DEFAULT_KEEP_ALIVE_SECONDS: Long = 60
            const val DEFAULT_THREAD_NAME_PREFIX: String = "d-migrate-worker"
            val DEFAULT_RETRY_AFTER: Duration = Duration.ofSeconds(1)
            val DEFAULT_SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(30)
            val DEFAULT: Async = Async()
        }
    }

    companion object {
        val SYNC_DEFAULT: JobExecutorConfig = Sync
    }
}
