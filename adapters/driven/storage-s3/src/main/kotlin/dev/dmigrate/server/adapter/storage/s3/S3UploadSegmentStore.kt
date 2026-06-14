package dev.dmigrate.server.adapter.storage.s3

import dev.dmigrate.server.core.upload.UploadSegment
import dev.dmigrate.server.ports.UploadSegmentStore
import dev.dmigrate.server.ports.WriteSegmentOutcome
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock

/**
 * S3-kompatible [UploadSegmentStore]-Impl (ImpPlan-0.9.8-object-storage-s3
 * S3.3). **Jedes Segment = ein eigenstaendiges S3-Objekt** unter
 * `<keyPrefix>/segments/<uploadSessionId>/<segmentIndex>` — kein S3-Multipart
 * (object-storage-s3-eval.md: `openSegmentRangeRead` muss ein bereits geschriebenes Segment
 * range-lesen, Multipart-Parts sind das nicht).
 *
 * SHA-256, Groesse und `segmentOffset` werden als User-Metadata
 * (`x-amz-meta-sha256`/`-size-bytes`/`-segment-offset`) abgelegt, damit
 * [listSegments] die `UploadSegment`-Werte rekonstruieren kann. Geteilte
 * Primitive in [S3StorageSupport]. Concurrency wie [S3ArtifactContentStore]:
 * striped Lock pro `(session,index)`, JVM-lokal (object-storage-artifact-store.md).
 */
class S3UploadSegmentStore(
    private val s3: S3Client,
    private val bucket: String,
    private val keyPrefix: String = "",
) : UploadSegmentStore {

    private val locks = Array(LOCK_STRIPES) { ReentrantLock() }

    override fun writeSegment(segment: UploadSegment, source: InputStream): WriteSegmentOutcome {
        S3StorageSupport.requireSafeId(segment.uploadSessionId, "uploadSessionId")
        S3StorageSupport.requireNonNegativeIndex(segment.segmentIndex)
        val tmp = Files.createTempFile("dmg-s3-seg-", ".part")
        try {
            val hashed = S3StorageSupport.copyAndHash(source, tmp)
            return when {
                hashed.sizeBytes != segment.sizeBytes ->
                    WriteSegmentOutcome.SizeMismatch(segment.segmentIndex, segment.sizeBytes, hashed.sizeBytes)
                else -> storeOrResolve(segment, tmp, hashed)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun storeOrResolve(segment: UploadSegment, tmp: Path, hashed: S3StorageSupport.Hashed): WriteSegmentOutcome {
        val key = segmentKey(segment.uploadSessionId, segment.segmentIndex)
        val lock = locks[(key.hashCode() and Int.MAX_VALUE) % LOCK_STRIPES]
        lock.lock()
        try {
            // Existenz an `head != null` festmachen (nicht an der sha-Metadata),
            // sonst wuerde ein vorhandenes Objekt OHNE sha256-Metadata still
            // ueberschrieben statt als Conflict gemeldet — konsistent zu
            // S3ArtifactContentStore.
            val existing = S3StorageSupport.head(s3, bucket, key)
            val existingSha = existing?.metadata()?.get("sha256").orEmpty()
            return when {
                existing == null -> {
                    S3StorageSupport.putBody(s3, bucket, key, tmp, hashed.sizeBytes, metadataOf(segment, hashed.sha256))
                    WriteSegmentOutcome.Stored(segment.copy(segmentSha256 = hashed.sha256))
                }
                existingSha == hashed.sha256 ->
                    WriteSegmentOutcome.AlreadyStored(segment.copy(segmentSha256 = existingSha))
                else ->
                    WriteSegmentOutcome.Conflict(segment.segmentIndex, existingSha, hashed.sha256)
            }
        } finally {
            lock.unlock()
        }
    }

    override fun listSegments(uploadSessionId: String): List<UploadSegment> {
        S3StorageSupport.requireSafeId(uploadSessionId, "uploadSessionId")
        return listKeys(sessionPrefix(uploadSessionId)).mapNotNull { key ->
            val index = key.substringAfterLast('/').toIntOrNull() ?: return@mapNotNull null
            val head = S3StorageSupport.head(s3, bucket, key) ?: return@mapNotNull null
            UploadSegment(
                uploadSessionId = uploadSessionId,
                segmentIndex = index,
                segmentOffset = head.metadata()["segment-offset"]?.toLongOrNull()
                    ?: error("segment $index of $uploadSessionId has no persisted segment-offset"),
                sizeBytes = head.contentLength() ?: 0L,
                segmentSha256 = head.metadata()["sha256"].orEmpty(),
            )
        }.sortedBy { it.segmentIndex }
    }

    override fun openSegmentRangeRead(
        uploadSessionId: String,
        segmentIndex: Int,
        offset: Long,
        length: Long,
    ): InputStream {
        S3StorageSupport.requireSafeId(uploadSessionId, "uploadSessionId")
        S3StorageSupport.requireNonNegativeIndex(segmentIndex)
        S3StorageSupport.requireNonNegativeRange(offset, length)
        val key = segmentKey(uploadSessionId, segmentIndex)
        val size = S3StorageSupport.head(s3, bucket, key)?.contentLength()
            ?: error("segment $segmentIndex of $uploadSessionId not found")
        val header = S3StorageSupport.rangeHeader(offset, length, size)
            ?: return ByteArrayInputStream(ByteArray(0))
        return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).range(header).build())
    }

    override fun deleteAllForSession(uploadSessionId: String): Int {
        S3StorageSupport.requireSafeId(uploadSessionId, "uploadSessionId")
        val keys = listKeys(sessionPrefix(uploadSessionId))
        var deleted = 0
        keys.chunked(DELETE_BATCH).forEach { batch ->
            // Tatsaechlich geloeschte zaehlen: bei Teilfehlern listet S3 die
            // betroffenen Keys in errors() (leer bei Erfolg) — nicht blind
            // batch.size zurueckmelden.
            val response = s3.deleteObjects { req ->
                req.bucket(bucket).delete { del ->
                    del.objects(batch.map { ObjectIdentifier.builder().key(it).build() })
                }
            }
            deleted += batch.size - response.errors().size
        }
        return deleted
    }

    private fun listKeys(prefix: String): List<String> {
        val keys = mutableListOf<String>()
        var token: String? = null
        do {
            val continuation = token
            val response = s3.listObjectsV2 { req ->
                req.bucket(bucket).prefix(prefix)
                if (continuation != null) req.continuationToken(continuation)
            }
            response.contents().forEach { keys += it.key() }
            token = if (response.isTruncated == true) response.nextContinuationToken() else null
        } while (token != null)
        return keys
    }

    private fun metadataOf(segment: UploadSegment, sha256: String): Map<String, String> = mapOf(
        "sha256" to sha256,
        "size-bytes" to segment.sizeBytes.toString(),
        "segment-offset" to segment.segmentOffset.toString(),
    )

    private fun sessionPrefix(uploadSessionId: String): String = prefixed("segments/$uploadSessionId/")

    private fun segmentKey(uploadSessionId: String, segmentIndex: Int): String =
        prefixed("segments/$uploadSessionId/$segmentIndex")

    private fun prefixed(suffix: String): String =
        if (keyPrefix.isEmpty()) suffix else "${keyPrefix.trimEnd('/')}/$suffix"

    private companion object {
        const val LOCK_STRIPES = 64
        const val DELETE_BATCH = 1000
    }
}
