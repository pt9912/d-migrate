package dev.dmigrate.server.core.upload

object UploadSessionTransitions {

    private val allowed: Map<UploadSessionState, Set<UploadSessionState>> = mapOf(
        UploadSessionState.ACTIVE to setOf(
            UploadSessionState.COMPLETED,
            UploadSessionState.ABORTED,
            UploadSessionState.EXPIRED,
        ),
        UploadSessionState.COMPLETED to emptySet(),
        UploadSessionState.ABORTED to emptySet(),
        UploadSessionState.EXPIRED to emptySet(),
    )

    fun isAllowed(from: UploadSessionState, to: UploadSessionState): Boolean =
        to in (allowed[from] ?: emptySet())

    fun acceptsSegments(state: UploadSessionState): Boolean =
        state == UploadSessionState.ACTIVE

    fun canResume(state: UploadSessionState): Boolean =
        state == UploadSessionState.ACTIVE

    /**
     * §6.3-Abnahme: Finalize prueft State, lueckenlose Segmente, jedes
     * Segment haelt einen non-blank `segmentSha256`, kontinuierliche
     * Offsets (`segment[i].segmentOffset == sum(segment[0..i-1].sizeBytes)`),
     * `Σ segment.sizeBytes == session.sizeBytes`, und der Gesamt-Hash
     * passt zur Session.
     */
    @Suppress("ReturnCount")
    fun validateFinalize(
        session: UploadSession,
        segments: List<UploadSegment>,
        actualTotalChecksum: String,
    ): FinalizeValidation {
        if (session.state != UploadSessionState.ACTIVE) {
            return FinalizeValidation.WrongState(session.state)
        }
        val gaps = findSegmentGaps(session.segmentTotal, segments)
        if (gaps.isNotEmpty()) {
            return FinalizeValidation.GapsInSegments(gaps)
        }
        val sorted = segments.sortedBy { it.segmentIndex }
        var runningOffset = 0L
        for (segment in sorted) {
            if (segment.segmentSha256.isBlank()) {
                return FinalizeValidation.EmptySegmentHash(segment.segmentIndex)
            }
            if (segment.segmentOffset != runningOffset) {
                return FinalizeValidation.SegmentOffsetMismatch(
                    segmentIndex = segment.segmentIndex,
                    expected = runningOffset,
                    actual = segment.segmentOffset,
                )
            }
            runningOffset += segment.sizeBytes
        }
        if (runningOffset != session.sizeBytes) {
            return FinalizeValidation.SegmentSizeMismatch(
                expected = session.sizeBytes,
                actual = runningOffset,
            )
        }
        if (session.checksumSha256 != actualTotalChecksum) {
            return FinalizeValidation.TotalChecksumMismatch(
                expected = session.checksumSha256,
                actual = actualTotalChecksum,
            )
        }
        return FinalizeValidation.Ok
    }

    private fun findSegmentGaps(expectedTotal: Int, segments: List<UploadSegment>): List<Int> {
        val present = segments.map { it.segmentIndex }.toSet()
        return (0 until expectedTotal).filter { it !in present }
    }

    sealed interface FinalizeValidation {
        data object Ok : FinalizeValidation
        data class WrongState(val state: UploadSessionState) : FinalizeValidation
        data class GapsInSegments(val missingIndices: List<Int>) : FinalizeValidation
        data class EmptySegmentHash(val segmentIndex: Int) : FinalizeValidation
        data class SegmentOffsetMismatch(
            val segmentIndex: Int,
            val expected: Long,
            val actual: Long,
        ) : FinalizeValidation
        data class SegmentSizeMismatch(val expected: Long, val actual: Long) : FinalizeValidation
        data class TotalChecksumMismatch(val expected: String, val actual: String) : FinalizeValidation
    }
}
