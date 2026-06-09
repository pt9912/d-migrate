package dev.dmigrate.server.adapter.storage.s3

import dev.dmigrate.server.ports.ArtifactContentStore
import dev.dmigrate.server.ports.WriteArtifactOutcome
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock

/**
 * S3-kompatible [ArtifactContentStore]-Impl (ImpPlan-0.9.8-object-storage-s3
 * S3.2). Jedes Artefakt = ein S3-Objekt unter `<keyPrefix>/<artifactId>`.
 *
 * SHA-256 wird beim Spool client-seitig gerechnet und als `x-amz-meta-sha256`
 * (+ `x-amz-meta-size-bytes`) abgelegt — der S3-ETag ist **nicht** der
 * SHA-256, deshalb liest der Idempotenzpfad die User-Metadata via HeadObject
 * zurueck. Geteilte Primitive (Hash, Validierung, Put) in [S3StorageSupport];
 * der `S3Client` muss aus [S3ClientFactory] stammen.
 *
 * Concurrency: ein striped `ReentrantLock` serialisiert Schreibzugriffe
 * desselben artifactId JVM-lokal. S3/SeaweedFS bieten kein atomares
 * create-if-absent (last-writer-wins) — die JVM-uebergreifende
 * „exactly-one-Stored"-Garantie des File-Adapters gibt es hier bewusst nicht
 * (object-storage-artifact-store.md, sequenziell getestet).
 */
class S3ArtifactContentStore(
    private val s3: S3Client,
    private val bucket: String,
    private val keyPrefix: String = "",
) : ArtifactContentStore {

    private val locks = Array(LOCK_STRIPES) { ReentrantLock() }

    override fun write(
        artifactId: String,
        source: InputStream,
        expectedSizeBytes: Long,
    ): WriteArtifactOutcome {
        S3StorageSupport.requireSafeId(artifactId, "artifactId")
        val tmp = Files.createTempFile("dmg-s3-", ".part")
        try {
            val hashed = S3StorageSupport.copyAndHash(source, tmp)
            return when {
                hashed.sizeBytes != expectedSizeBytes ->
                    WriteArtifactOutcome.SizeMismatch(expectedSizeBytes, hashed.sizeBytes)
                else -> storeOrResolve(artifactId, tmp, hashed)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun storeOrResolve(artifactId: String, tmp: Path, hashed: S3StorageSupport.Hashed): WriteArtifactOutcome {
        val lock = locks[(artifactId.hashCode() and Int.MAX_VALUE) % LOCK_STRIPES]
        lock.lock()
        try {
            val existing = existingMeta(artifactId)
            return when {
                existing == null -> {
                    S3StorageSupport.putBody(
                        s3, bucket, keyFor(artifactId), tmp, hashed.sizeBytes,
                        mapOf("sha256" to hashed.sha256, "size-bytes" to hashed.sizeBytes.toString()),
                    )
                    WriteArtifactOutcome.Stored(artifactId, hashed.sha256, hashed.sizeBytes)
                }
                existing.sha256 == hashed.sha256 ->
                    WriteArtifactOutcome.AlreadyExists(artifactId, existing.sha256, existing.sizeBytes)
                else ->
                    WriteArtifactOutcome.Conflict(artifactId, existing.sha256, hashed.sha256)
            }
        } finally {
            lock.unlock()
        }
    }

    override fun openRangeRead(artifactId: String, offset: Long, length: Long): InputStream {
        S3StorageSupport.requireSafeId(artifactId, "artifactId")
        S3StorageSupport.requireNonNegativeRange(offset, length)
        val size = existingMeta(artifactId)?.sizeBytes ?: error("artifact $artifactId not found")
        val header = S3StorageSupport.rangeHeader(offset, length, size)
            ?: return ByteArrayInputStream(ByteArray(0))
        return s3.getObject(
            GetObjectRequest.builder().bucket(bucket).key(keyFor(artifactId)).range(header).build(),
        )
    }

    override fun exists(artifactId: String): Boolean {
        S3StorageSupport.requireSafeId(artifactId, "artifactId")
        return existingMeta(artifactId) != null
    }

    override fun delete(artifactId: String): Boolean {
        S3StorageSupport.requireSafeId(artifactId, "artifactId")
        if (existingMeta(artifactId) == null) return false
        s3.deleteObject { it.bucket(bucket).key(keyFor(artifactId)) }
        return true
    }

    private fun existingMeta(artifactId: String): Existing? =
        S3StorageSupport.head(s3, bucket, keyFor(artifactId))?.let {
            Existing(it.metadata()["sha256"].orEmpty(), it.contentLength() ?: 0L)
        }

    private fun keyFor(artifactId: String): String =
        if (keyPrefix.isEmpty()) artifactId else "${keyPrefix.trimEnd('/')}/$artifactId"

    private data class Existing(val sha256: String, val sizeBytes: Long)

    private companion object {
        const val LOCK_STRIPES = 64
    }
}
