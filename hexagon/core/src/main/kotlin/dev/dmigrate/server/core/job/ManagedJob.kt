package dev.dmigrate.server.core.job

import java.time.Instant

enum class JobStatus(val terminal: Boolean) {
    QUEUED(terminal = false),
    RUNNING(terminal = false),
    SUCCEEDED(terminal = true),
    FAILED(terminal = true),
    CANCELLED(terminal = true),
}

data class JobError(
    val code: String,
    val message: String,
    val exitCode: Int? = null,
)

data class JobProgress(
    val phase: String,
    val numericValues: Map<String, Long> = emptyMap(),
)

/**
 * Cancel-request- und Cancel-Ack-Metadaten für einen [ManagedJob].
 *
 * Der zweistufige Pattern aus LF-012 / LN-011 / LN-017 / LN-027:
 *
 * - **Request-Phase**: `requested = true` wird durabel via CAS gesetzt
 *   (siehe `JobStore.markCancelRequested`). `signalAcked = false` heißt
 *   "Cancel-Request liegt vor, Worker hat noch nicht bestätigt". LF-012 / LN-011 / LN-017 / LN-027
 *   `job_cancel` retried in diesem Zustand idempotent ohne Reason oder
 *   Request-Metadaten zu überschreiben.
 *
 * - **Ack-Phase**: `signalAcked = true` mit `ackedAt`-Zeitstempel wird
 *   gesetzt, sobald der Worker den Cancel-Punkt verarbeitet hat. Das
 *   ist orthogonal zum Job-Status-Übergang nach `CANCELLED` —
 *   LF-012 / LN-011 / LN-017 / LN-027 *   `job_status_get`-Response.
 *
 * Alle Felder sind nullable bzw. `false` im Default, damit
 * Bestands-Code, der `ManagedJob` ohne Cancel-Metadaten konstruiert,
 * unverändert bleibt.
 */
data class JobCancelRequest(
    val requested: Boolean = false,
    val signalAcked: Boolean = false,
    val requestedAt: Instant? = null,
    val requestedBy: String? = null,
    val requestedReason: String? = null,
    val signalSource: String? = null,
    val ackedAt: Instant? = null,
)

data class ManagedJob(
    val jobId: String,
    val operation: String,
    val status: JobStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val expiresAt: Instant,
    val createdBy: String,
    val artifacts: List<String> = emptyList(),
    val error: JobError? = null,
    val progress: JobProgress? = null,
    val cancelRequest: JobCancelRequest = JobCancelRequest(),
)
