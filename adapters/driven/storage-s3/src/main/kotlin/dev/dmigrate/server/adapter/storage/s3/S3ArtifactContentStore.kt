package dev.dmigrate.server.adapter.storage.s3

import dev.dmigrate.core.util.toHex
import dev.dmigrate.server.ports.ArtifactContentStore
import dev.dmigrate.server.ports.WriteArtifactOutcome
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock

/**
 * S3-kompatible [ArtifactContentStore]-Impl (ImpPlan-0.9.8-object-storage-s3
 * S3.2). Jedes Artefakt = ein S3-Objekt unter `<keyPrefix>/<artifactId>`.
 *
 * SHA-256 wird beim Spool client-seitig gerechnet und als `x-amz-meta-sha256`
 * (+ `x-amz-meta-size-bytes`) abgelegt — der S3-ETag ist **nicht** der SHA-256,
 * deshalb liest der Idempotenzpfad die User-Metadata via HeadObject zurueck
 * (§2.1/§2.4; gegen SeaweedFS gate-validiert). Der `S3Client` muss aus
 * [S3ClientFactory] stammen (`url-connection-client`, WHEN_REQUIRED-Checksums).
 *
 * Concurrency: ein Per-Key-`ReentrantLock` serialisiert Schreibzugriffe
 * innerhalb der JVM. S3/SeaweedFS bieten kein atomares create-if-absent
 * (last-writer-wins) — die JVM-uebergreifende „exactly-one-Stored"-Garantie
 * des File-Adapters gibt es hier bewusst nicht (Plan §6, sequenziell getestet).
 */
class S3ArtifactContentStore(
    private val s3: S3Client,
    private val bucket: String,
    private val keyPrefix: String = "",
) : ArtifactContentStore {

    // Striped Locks (feste Groesse) statt einer Map<id, Lock>: serialisiert
    // konkurrierende Writes desselben artifactId JVM-lokal, ohne pro Id einen
    // Lock-Eintrag zu akkumulieren (sonst unbounded Heap-Wachstum bei vielen
    // distinkten Ids). Kollisionen zweier Ids auf denselben Stripe sind
    // harmlos (etwas mehr Kontention). Cross-JVM bleibt last-writer-wins (§6).
    private val locks = Array(LOCK_STRIPES) { ReentrantLock() }

    override fun write(
        artifactId: String,
        source: InputStream,
        expectedSizeBytes: Long,
    ): WriteArtifactOutcome {
        requireSafeId(artifactId)
        val tmp = Files.createTempFile("dmg-s3-", ".part")
        try {
            val hashed = copyAndHash(source, tmp)
            return when {
                hashed.sizeBytes != expectedSizeBytes ->
                    WriteArtifactOutcome.SizeMismatch(expectedSizeBytes, hashed.sizeBytes)
                else -> storeOrResolve(artifactId, tmp, hashed)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun storeOrResolve(artifactId: String, tmp: Path, hashed: Hashed): WriteArtifactOutcome {
        val lock = locks[(artifactId.hashCode() and Int.MAX_VALUE) % LOCK_STRIPES]
        lock.lock()
        try {
            val existing = headMeta(artifactId)
            return when {
                existing == null -> {
                    store(keyFor(artifactId), tmp, hashed)
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
        requireSafeId(artifactId)
        require(offset >= 0) { "offset must be >= 0, was $offset" }
        require(length >= 0) { "length must be >= 0, was $length" }
        val size = headMeta(artifactId)?.sizeBytes ?: error("artifact $artifactId not found")
        require(offset <= size) { "offset $offset out of bounds for size $size" }
        require(offset + length <= size) { "range [$offset, ${offset + length}) out of bounds for size $size" }
        if (length == 0L) return ByteArrayInputStream(ByteArray(0))
        return s3.getObject(
            GetObjectRequest.builder()
                .bucket(bucket).key(keyFor(artifactId))
                .range("bytes=$offset-${offset + length - 1}")
                .build(),
        )
    }

    override fun exists(artifactId: String): Boolean {
        requireSafeId(artifactId)
        return headMeta(artifactId) != null
    }

    override fun delete(artifactId: String): Boolean {
        requireSafeId(artifactId)
        if (headMeta(artifactId) == null) return false
        s3.deleteObject { it.bucket(bucket).key(keyFor(artifactId)) }
        return true
    }

    private fun store(key: String, tmp: Path, hashed: Hashed) {
        val metadata = mapOf("sha256" to hashed.sha256, "size-bytes" to hashed.sizeBytes.toString())
        if (hashed.sizeBytes <= MULTIPART_THRESHOLD_BYTES) {
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).metadata(metadata).build(),
                RequestBody.fromFile(tmp),
            )
        } else {
            storeMultipart(key, tmp, metadata)
        }
    }

    private fun storeMultipart(key: String, tmp: Path, metadata: Map<String, String>) {
        val uploadId = s3.createMultipartUpload { it.bucket(bucket).key(key).metadata(metadata) }.uploadId()
        val parts = mutableListOf<CompletedPart>()
        var completed = false
        try {
            Files.newInputStream(tmp).use { input ->
                var partNumber = 1
                while (true) {
                    val chunk = input.readNBytes(PART_SIZE_BYTES)
                    if (chunk.isEmpty()) break
                    val etag = s3.uploadPart(
                        UploadPartRequest.builder()
                            .bucket(bucket).key(key).uploadId(uploadId).partNumber(partNumber).build(),
                        RequestBody.fromBytes(chunk),
                    ).eTag()
                    parts += CompletedPart.builder().partNumber(partNumber).eTag(etag).build()
                    partNumber++
                }
            }
            s3.completeMultipartUpload {
                it.bucket(bucket).key(key).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
            }
            completed = true
        } finally {
            // Bei JEDEM Fehler (SdkException, IOException beim Datei-Lesen, …)
            // den angefangenen Upload abbrechen, sonst bleibt er server-seitig
            // als verwaister Multipart-Upload haengen (Speicher/Kosten).
            if (!completed) {
                runCatching { s3.abortMultipartUpload { it.bucket(bucket).key(key).uploadId(uploadId) } }
            }
        }
    }

    private fun headMeta(artifactId: String): Existing? = try {
        val head = s3.headObject { it.bucket(bucket).key(keyFor(artifactId)) }
        Existing(head.metadata()["sha256"].orEmpty(), head.contentLength() ?: 0L)
    } catch (failure: S3Exception) {
        if (failure.statusCode() == HTTP_NOT_FOUND) null else throw failure
    }

    private fun copyAndHash(source: InputStream, target: Path): Hashed {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        Files.newOutputStream(target).use { out ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                out.write(buffer, 0, read)
                total += read
            }
        }
        return Hashed(total, digest.digest().toHex())
    }

    private fun keyFor(artifactId: String): String =
        if (keyPrefix.isEmpty()) artifactId else "${keyPrefix.trimEnd('/')}/$artifactId"

    // Allowlist identisch zu storage-file PathSafety.ID_PATTERN — damit ein
    // artifactId auf beiden Byte-Store-Backends gleich akzeptiert/abgelehnt
    // wird und der S3-Key keine Sonder-/Control-Zeichen aufnehmen kann.
    // (Gemeinsame Util-Extraktion mit S3.3, wenn der UploadSegmentStore der
    // dritte Nutzer wird — ImpPlan §2.3.)
    private fun requireSafeId(artifactId: String) {
        require(ID_PATTERN.matches(artifactId)) {
            "artifactId '$artifactId' must match ${ID_PATTERN.pattern}"
        }
    }

    private data class Hashed(val sizeBytes: Long, val sha256: String)
    private data class Existing(val sha256: String, val sizeBytes: Long)

    private companion object {
        val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        const val LOCK_STRIPES = 64
        const val HTTP_NOT_FOUND = 404
        const val BUFFER_BYTES = 64 * 1024
        const val PART_SIZE_BYTES = 8 * 1024 * 1024
        const val MULTIPART_THRESHOLD_BYTES = 8L * 1024 * 1024
    }
}
