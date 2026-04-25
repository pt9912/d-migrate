package dev.dmigrate.server.core.artifact

import java.time.Instant

data class ManagedArtifact(
    val artifactId: String,
    val filename: String,
    val contentType: String,
    val sizeBytes: Long,
    val sha256: String,
    val createdAt: Instant,
    val expiresAt: Instant,
)
