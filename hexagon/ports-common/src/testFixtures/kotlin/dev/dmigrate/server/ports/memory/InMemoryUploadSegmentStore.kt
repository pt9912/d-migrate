package dev.dmigrate.server.ports.memory

import dev.dmigrate.server.core.upload.UploadSegment
import dev.dmigrate.server.ports.RangeBounds
import dev.dmigrate.server.ports.UploadSegmentStore
import dev.dmigrate.server.ports.WriteSegmentOutcome
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class InMemoryUploadSegmentStore : UploadSegmentStore {

    private data class Key(val sessionId: String, val segmentIndex: Int)

    private data class StoredSegment(val segment: UploadSegment, val bytes: ByteArray)

    private val segments = ConcurrentHashMap<Key, StoredSegment>()

    override fun writeSegment(segment: UploadSegment, source: InputStream): WriteSegmentOutcome {
        val key = Key(segment.uploadSessionId, segment.segmentIndex)
        val bytes = source.readAllBytes()
        if (bytes.size.toLong() != segment.sizeBytes) {
            return WriteSegmentOutcome.SizeMismatch(
                segmentIndex = segment.segmentIndex,
                expected = segment.sizeBytes,
                actual = bytes.size.toLong(),
            )
        }
        val computedHash = sha256Hex(bytes)
        val attempted = segment.copy(segmentSha256 = computedHash)
        var outcome: WriteSegmentOutcome? = null
        segments.compute(key) { _, existing ->
            when {
                existing == null -> {
                    outcome = WriteSegmentOutcome.Stored(attempted)
                    StoredSegment(attempted, bytes)
                }
                existing.segment.segmentSha256 == computedHash -> {
                    outcome = WriteSegmentOutcome.AlreadyStored(existing.segment)
                    existing
                }
                else -> {
                    outcome = WriteSegmentOutcome.Conflict(
                        segmentIndex = segment.segmentIndex,
                        existingSegmentSha256 = existing.segment.segmentSha256,
                        attemptedSegmentSha256 = computedHash,
                    )
                    existing
                }
            }
        }
        return outcome!!
    }

    override fun listSegments(uploadSessionId: String): List<UploadSegment> =
        segments.values
            .filter { it.segment.uploadSessionId == uploadSessionId }
            .map { it.segment }
            .sortedBy { it.segmentIndex }

    override fun openSegmentRangeRead(
        uploadSessionId: String,
        segmentIndex: Int,
        offset: Long,
        length: Long,
    ): InputStream {
        val stored = segments[Key(uploadSessionId, segmentIndex)]
            ?: error("segment $segmentIndex of $uploadSessionId not found")
        RangeBounds.check(offset, length, stored.bytes.size.toLong())
        if (length == 0L) return ByteArrayInputStream(ByteArray(0))
        return ByteArrayInputStream(stored.bytes, offset.toInt(), length.toInt())
    }

    override fun deleteAllForSession(uploadSessionId: String): Int {
        val toRemove = segments.entries
            .filter { it.value.segment.uploadSessionId == uploadSessionId }
            .map { it.key }
        toRemove.forEach { segments.remove(it) }
        return toRemove.size
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
