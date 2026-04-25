package dev.dmigrate.server.ports.memory

import dev.dmigrate.server.ports.ArtifactContentStore
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
        val existing = hashes[artifactId]
        if (existing != null) {
            return WriteArtifactOutcome.AlreadyExists(artifactId, existing)
        }
        val bytes = source.readAllBytes()
        if (bytes.size.toLong() != expectedSizeBytes) {
            return WriteArtifactOutcome.SizeMismatch(expectedSizeBytes, bytes.size.toLong())
        }
        val digest = sha256Hex(bytes)
        contents[artifactId] = bytes
        hashes[artifactId] = digest
        return WriteArtifactOutcome.Stored(artifactId, digest, bytes.size.toLong())
    }

    override fun openRangeRead(artifactId: String, offset: Long, length: Long): InputStream {
        val bytes = contents[artifactId] ?: error("artifact $artifactId not found")
        val from = offset.coerceAtLeast(0).toInt().coerceAtMost(bytes.size)
        val to = (from + length.toInt()).coerceAtMost(bytes.size)
        return ByteArrayInputStream(bytes, from, to - from)
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
