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
)
