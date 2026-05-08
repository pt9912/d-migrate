package dev.dmigrate.server.ports.memory

import dev.dmigrate.server.ports.ArtifactContentStore
import dev.dmigrate.server.ports.RangeBounds
import dev.dmigrate.server.ports.WriteArtifactOutcome
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class InMemoryArtifactContentStore : ArtifactContentStore {

    private val contents = ConcurrentHashMap<String, ByteArray>()
    private val hashes = ConcurrentHashMap<String, String>()

    override fun write(
        artifactId: String,
        source: InputStream,
        expectedSizeBytes: Long,
    ): WriteArtifactOutcome {
        val bytes = source.readAllBytes()
        if (bytes.size.toLong() != expectedSizeBytes) {
            return WriteArtifactOutcome.SizeMismatch(expectedSizeBytes, bytes.size.toLong())
        }
        val attemptedSha = sha256Hex(bytes)
        val existing = hashes[artifactId]
        if (existing != null) {
            return if (existing == attemptedSha) {
                WriteArtifactOutcome.AlreadyExists(
                    artifactId = artifactId,
                    existingSha256 = existing,
                    existingSizeBytes = (contents[artifactId]?.size ?: 0).toLong(),
                )
            } else {
                WriteArtifactOutcome.Conflict(artifactId, existing, attemptedSha)
            }
        }
        contents[artifactId] = bytes
        hashes[artifactId] = attemptedSha
        return WriteArtifactOutcome.Stored(artifactId, attemptedSha, bytes.size.toLong())
    }

    override fun openRangeRead(artifactId: String, offset: Long, length: Long): InputStream {
        val bytes = contents[artifactId] ?: error("artifact $artifactId not found")
        RangeBounds.check(offset, length, bytes.size.toLong())
        if (length == 0L) return ByteArrayInputStream(ByteArray(0))
        return ByteArrayInputStream(bytes, offset.toInt(), length.toInt())
    }

    override fun exists(artifactId: String): Boolean = contents.containsKey(artifactId)

    override fun delete(artifactId: String): Boolean {
        val removed = contents.remove(artifactId) != null
        hashes.remove(artifactId)
        return removed
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
