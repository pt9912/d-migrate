package dev.dmigrate.server.ports

import dev.dmigrate.core.cancel.CancellationTokenSource

/**
 * LF-012 / LN-011 / LN-017 / LN-027 §7.2 / §6.4 runtime registry that maps a `jobId` to the
 * [CancellationTokenSource] of the worker thread (or coroutine) that
 * is currently executing the job.
 *
 * This registry is **runtime-only** — entries do NOT survive a process
 * restart. The job's `cancelRequest` durable metadata in
 * [JobStore.markCancelRequested] is the persistent counterpart that
 * survives restart and lets a recovering worker observe a pending
 * cancel via `cancelRequest.requested == true`.
 *
 * LF-012 / LN-011 / LN-017 / LN-027 zweistufiges Cancel-Pattern:
 *
 * 1. `JobStore.markCancelRequested(...)` setzt durabel
 *    `cancelRequest.requested = true` mit Reason und Metadaten.
 * 2. Hier `signal(jobId, reason)` ruft `source.cancel(reason)` —
 *    Worker beobachtet das Token an seinem nächsten Cancel-Checkpoint
 *    und beendet kontrolliert.
 *
 * Wenn `signal` keine aktive Source findet (z.B. weil der Worker noch
 * nicht gestartet ist oder schon beendet ist), liefert er
 * [SignalOutcome.NotFound]. Der Job-Cancel-Request bleibt durabel
 * persistiert; der Worker beobachtet `cancelRequest.requested = true`
 * beim Start oder nach einem Process-Restart.
 */
interface WorkerHandleRegistry {

    /**
     * Registers the [source] for the given [jobId]. The caller (typically
     * the Job-Worker that just started executing) MUST call
     * [unregister] when the job terminates (succeed/fail/cancelled).
     */
    fun register(jobId: String, source: CancellationTokenSource)

    /**
     * Looks up the registered [CancellationTokenSource] for [jobId] and
     * fires `cancel(reason)` if present. Idempotent — multiple calls
     * with different reasons are accepted but the first reason wins
     * (per [CancellationTokenSource.cancel] contract).
     */
    fun signal(jobId: String, reason: String?): SignalOutcome

    /**
     * Removes the registration for [jobId]. Idempotent — calling for
     * an unknown jobId is a no-op.
     */
    fun unregister(jobId: String)
}

sealed interface SignalOutcome {
    /** [WorkerHandleRegistry.signal] called `cancel(...)` on the registered source. */
    data object Signaled : SignalOutcome

    /**
     * No active source registered for this jobId. The LF-012 / LN-011 / LN-017 / LN-027
     * `job_cancel`-Tool returns this from the in-process registry; the
     * durable cancel-request via [JobStore.markCancelRequested] still
     * applies and a recovering worker observes it.
     */
    data object NotFound : SignalOutcome
}
