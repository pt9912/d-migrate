package dev.dmigrate.server.adapter.storage.s3

import dev.dmigrate.core.util.toHex
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Geteilte Byte-Store-Primitive der S3-Adapter ([S3ArtifactContentStore] +
 * [S3UploadSegmentStore], ImpPlan §7 „3. Nutzer"): Streaming-Hash, ID-/Range-
 * Validierung, HeadObject-404-Handling und der Single-/Multipart-Put inkl.
 * Abort-on-Failure. Modul-lokal (`internal`); die moduluebergreifende
 * Promotion nach `hexagon:core` (gegen `storage-file` PathSafety/
 * StreamingHashWriter) bleibt ein eigener Folge-Refactor.
 */
internal object S3StorageSupport {

    const val HTTP_NOT_FOUND = 404

    private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
    private const val BUFFER_BYTES = 64 * 1024
    private const val PART_SIZE_BYTES = 8 * 1024 * 1024
    private const val MULTIPART_THRESHOLD_BYTES = 8L * 1024 * 1024

    data class Hashed(val sizeBytes: Long, val sha256: String)

    fun requireSafeId(id: String, label: String) {
        require(ID_PATTERN.matches(id)) { "$label '$id' must match ${ID_PATTERN.pattern}" }
    }

    fun requireNonNegativeIndex(index: Int) {
        require(index >= 0) { "segmentIndex must be >= 0, was $index" }
    }

    /** Streamt [source] nach [target] und rechnet dabei Groesse + SHA-256. */
    fun copyAndHash(source: InputStream, target: Path): Hashed {
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

    /**
     * Validiert die Range gegen [size] (IllegalArgumentException bei negativ
     * oder out-of-bounds) und liefert den inklusiven S3-`Range`-Header — oder
     * `null`, wenn [length] == 0 (leerer Read, kein GET noetig).
     */
    fun rangeHeader(offset: Long, length: Long, size: Long): String? {
        require(offset >= 0) { "offset must be >= 0, was $offset" }
        require(length >= 0) { "length must be >= 0, was $length" }
        require(offset <= size) { "offset $offset out of bounds for size $size" }
        require(offset + length <= size) { "range [$offset, ${offset + length}) out of bounds for size $size" }
        return if (length == 0L) null else "bytes=$offset-${offset + length - 1}"
    }

    /** HeadObject; `null` bei 404, sonst die Response (andere Fehler propagieren). */
    fun head(s3: S3Client, bucket: String, key: String): HeadObjectResponse? = try {
        s3.headObject { it.bucket(bucket).key(key) }
    } catch (failure: S3Exception) {
        if (failure.statusCode() == HTTP_NOT_FOUND) null else throw failure
    }

    /** Single-PutObject (<= 8 MiB) bzw. Multipart fuer grosse Bodies. */
    fun putBody(
        s3: S3Client,
        bucket: String,
        key: String,
        body: Path,
        sizeBytes: Long,
        metadata: Map<String, String>,
    ) {
        if (sizeBytes <= MULTIPART_THRESHOLD_BYTES) {
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).metadata(metadata).build(),
                RequestBody.fromFile(body),
            )
        } else {
            putMultipart(s3, bucket, key, body, metadata)
        }
    }

    private fun putMultipart(s3: S3Client, bucket: String, key: String, body: Path, metadata: Map<String, String>) {
        val uploadId = s3.createMultipartUpload { it.bucket(bucket).key(key).metadata(metadata) }.uploadId()
        val parts = mutableListOf<CompletedPart>()
        var completed = false
        try {
            Files.newInputStream(body).use { input ->
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
            // als verwaister Multipart-Upload haengen.
            if (!completed) {
                runCatching { s3.abortMultipartUpload { it.bucket(bucket).key(key).uploadId(uploadId) } }
            }
        }
    }
}
