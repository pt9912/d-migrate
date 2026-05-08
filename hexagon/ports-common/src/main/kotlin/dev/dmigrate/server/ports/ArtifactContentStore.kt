package dev.dmigrate.server.ports

import java.io.InputStream

/**
 * Byte-store for finalized, immutable artifact content. base artifact scope defines only
 * the contract; in-memory and file-backed implementations follow in
 * LF-012 / LN-027 / LN-028 / LN-038 (`adapters:driven:storage-file`).
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

    /**
     * LF-010 / LF-013 / LN-009 / LN-011: an artefact under [artifactId] is already present
     * with the same [existingSha256] and [existingSizeBytes].
     * Returned for the deterministic-id idempotent path; callers
     * may treat it as a successful no-op when it matches the
     * payload they intended to write. Differing SHA / size under
     * the same id remains a hard inconsistency reported via
     * [Conflict].
     */
    data class AlreadyExists(
        val artifactId: String,
        val existingSha256: String,
        val existingSizeBytes: Long,
    ) : WriteArtifactOutcome
    data class Conflict(
        val artifactId: String,
        val existingSha256: String,
        val attemptedSha256: String,
    ) : WriteArtifactOutcome
}
