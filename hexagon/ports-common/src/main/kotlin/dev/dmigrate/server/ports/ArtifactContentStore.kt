package dev.dmigrate.server.ports

import java.io.InputStream

/**
 * Byte-store for finalized, immutable artifact content. Phase A defines only
 * the contract; in-memory and file-backed implementations follow in
 * AP 6.3 (`adapters:driven:storage-file`).
 */
interface ArtifactContentStore {

    fun write(artifactId: String, source: InputStream, expectedSizeBytes: Long): WriteArtifactOutcome

    fun openRangeRead(artifactId: String, offset: Long, length: Long): InputStream

    fun exists(artifactId: String): Boolean

    fun delete(artifactId: String): Boolean
}

sealed interface WriteArtifactOutcome {
    data class Stored(val artifactId: String, val sha256: String, val sizeBytes: Long) : WriteArtifactOutcome
    data class SizeMismatch(val expected: Long, val actual: Long) : WriteArtifactOutcome
    data class AlreadyExists(val artifactId: String, val existingSha256: String) : WriteArtifactOutcome
}
