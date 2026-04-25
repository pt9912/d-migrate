package dev.dmigrate.server.ports

import dev.dmigrate.server.core.upload.UploadSegment
import java.io.InputStream

interface UploadSegmentStore {

    fun writeSegment(
        segment: UploadSegment,
        source: InputStream,
    ): WriteSegmentOutcome

    fun listSegments(uploadSessionId: String): List<UploadSegment>

    fun openSegmentRangeRead(
        uploadSessionId: String,
        segmentIndex: Int,
        offset: Long,
        length: Long,
    ): InputStream

    fun deleteAllForSession(uploadSessionId: String): Int
}

sealed interface WriteSegmentOutcome {
    data class Stored(val segment: UploadSegment) : WriteSegmentOutcome
    data class AlreadyStored(val segment: UploadSegment) : WriteSegmentOutcome
    data class Conflict(
        val segmentIndex: Int,
        val existingSegmentSha256: String,
        val attemptedSegmentSha256: String,
    ) : WriteSegmentOutcome
    data class SizeMismatch(val segmentIndex: Int, val expected: Long, val actual: Long) : WriteSegmentOutcome
}
