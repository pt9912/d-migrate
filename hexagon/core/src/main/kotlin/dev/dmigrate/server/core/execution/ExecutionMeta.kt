package dev.dmigrate.server.core.execution

import java.time.Instant

data class ExecutionMeta(
    val requestId: String,
    val startedAt: Instant,
    val completedAt: Instant? = null,
    val durationMs: Long? = null,
    val toolName: String? = null,
)
