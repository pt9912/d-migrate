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
        data class TotalChecksumMismatch(val expected: String, val actual: String) : FinalizeValidation
    }
}
