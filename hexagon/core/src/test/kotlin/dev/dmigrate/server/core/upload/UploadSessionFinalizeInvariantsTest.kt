package dev.dmigrate.server.core.upload

import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

class UploadSessionFinalizeInvariantsTest : FunSpec({

    val now = Instant.parse("2026-04-25T10:00:00Z")
    val later = Instant.parse("2026-04-25T11:00:00Z")
    val expectedTotalChecksum = "deadbeef"

    fun session(
        state: UploadSessionState = UploadSessionState.ACTIVE,
        segmentTotal: Int = 3,
        checksum: String = expectedTotalChecksum,
    ) = UploadSession(
        uploadSessionId = "u1",
        tenantId = TenantId("acme"),
        ownerPrincipalId = PrincipalId("alice"),
        resourceUri = ServerResourceUri(TenantId("acme"), ResourceKind.UPLOAD_SESSIONS, "u1"),
        artifactKind = ArtifactKind.SCHEMA,
        mimeType = "application/octet-stream",
        sizeBytes = 3000L,
        segmentTotal = segmentTotal,
        checksumSha256 = checksum,
        uploadIntent = "schema_staging",
        state = state,
        createdAt = now,
        updatedAt = now,
        idleTimeoutAt = later,
        absoluteLeaseExpiresAt = later,
    )

    fun segment(index: Int, hash: String = "h$index") = UploadSegment(
        uploadSessionId = "u1",
        segmentIndex = index,
        segmentOffset = index * 1000L,
        sizeBytes = 1000L,
        segmentSha256 = hash,
    )

    test("Ok when all segments present and checksum matches") {
        val result = UploadSessionTransitions.validateFinalize(
            session = session(),
            segments = listOf(segment(0), segment(1), segment(2)),
            actualTotalChecksum = expectedTotalChecksum,
        )
        result shouldBe UploadSessionTransitions.FinalizeValidation.Ok
    }

    test("rejects finalize when not in ACTIVE state") {
        val result = UploadSessionTransitions.validateFinalize(
            session = session(state = UploadSessionState.COMPLETED),
            segments = listOf(segment(0), segment(1), segment(2)),
            actualTotalChecksum = expectedTotalChecksum,
        )
        result.shouldBeInstanceOf<UploadSessionTransitions.FinalizeValidation.WrongState>()
        result.state shouldBe UploadSessionState.COMPLETED
    }

    test("reports gaps when segments are missing") {
        val result = UploadSessionTransitions.validateFinalize(
            session = session(),
            segments = listOf(segment(0), segment(2)),
            actualTotalChecksum = expectedTotalChecksum,
        )
        result.shouldBeInstanceOf<UploadSessionTransitions.FinalizeValidation.GapsInSegments>()
        result.missingIndices shouldBe listOf(1)
    }

    test("reports multiple gaps when several segments are missing") {
        val result = UploadSessionTransitions.validateFinalize(
            session = session(segmentTotal = 4),
            segments = listOf(segment(0)),
            actualTotalChecksum = expectedTotalChecksum,
        )
        result.shouldBeInstanceOf<UploadSessionTransitions.FinalizeValidation.GapsInSegments>()
        result.missingIndices shouldBe listOf(1, 2, 3)
    }

    test("reports checksum mismatch when totals diverge") {
        val result = UploadSessionTransitions.validateFinalize(
            session = session(),
            segments = listOf(segment(0), segment(1), segment(2)),
            actualTotalChecksum = "different",
        )
        result.shouldBeInstanceOf<UploadSessionTransitions.FinalizeValidation.TotalChecksumMismatch>()
        result.expected shouldBe expectedTotalChecksum
        result.actual shouldBe "different"
    }
})
