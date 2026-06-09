package dev.dmigrate.server.adapter.storage.s3

import dev.dmigrate.core.util.sha256Hex
import dev.dmigrate.server.core.upload.UploadSegment
import dev.dmigrate.server.ports.WriteSegmentOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.S3Object
import java.io.ByteArrayInputStream
import java.util.function.Consumer

private fun notFound(): S3Exception = S3Exception.builder().statusCode(404).build() as S3Exception

private fun segHead(sha: String, size: Long, offset: Long = 0L): HeadObjectResponse =
    HeadObjectResponse.builder()
        .metadata(mapOf("sha256" to sha, "size-bytes" to size.toString(), "segment-offset" to offset.toString()))
        .contentLength(size)
        .build()

private fun seg(session: String, index: Int, size: Long, offset: Long = 0L): UploadSegment =
    UploadSegment(uploadSessionId = session, segmentIndex = index, segmentOffset = offset, sizeBytes = size, segmentSha256 = "")

/**
 * In-Modul-Unit-Tests fuer [S3UploadSegmentStore] gegen einen gemockten
 * `S3Client` — deckt die Logik-Branches ohne S3-Server ab. Echte
 * Server-Semantik validiert die SeaweedFS-Integration separat.
 */
class S3UploadSegmentStoreTest : FunSpec({

    test("writeSegment stores fresh segment and returns Stored with computed sha") {
        val s3 = mockk<S3Client>()
        every { s3.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } throws notFound()
        every { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns PutObjectResponse.builder().build()
        val payload = "seg".toByteArray()

        val outcome = S3UploadSegmentStore(s3, "b").writeSegment(seg("u1", 0, payload.size.toLong()), ByteArrayInputStream(payload))

        val stored = outcome.shouldBeInstanceOf<WriteSegmentOutcome.Stored>()
        stored.segment.segmentSha256 shouldBe sha256Hex(payload)
    }

    test("writeSegment with wrong declared size returns SizeMismatch, no put") {
        val s3 = mockk<S3Client>()
        val outcome = S3UploadSegmentStore(s3, "b").writeSegment(seg("u1", 1, 99), ByteArrayInputStream("tiny".toByteArray()))
        val mismatch = outcome.shouldBeInstanceOf<WriteSegmentOutcome.SizeMismatch>()
        mismatch.segmentIndex shouldBe 1
        mismatch.expected shouldBe 99
        mismatch.actual shouldBe 4
        verify(exactly = 0) { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) }
    }

    test("identical re-write returns AlreadyStored, no put") {
        val payload = "same".toByteArray()
        val sha = sha256Hex(payload)
        val s3 = mockk<S3Client>()
        every { s3.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } returns segHead(sha, payload.size.toLong())

        val outcome = S3UploadSegmentStore(s3, "b").writeSegment(seg("u1", 0, payload.size.toLong()), ByteArrayInputStream(payload))

        outcome.shouldBeInstanceOf<WriteSegmentOutcome.AlreadyStored>()
        verify(exactly = 0) { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) }
    }

    test("conflicting bytes at same index return Conflict") {
        val payload = "beta_".toByteArray()
        val s3 = mockk<S3Client>()
        every { s3.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } returns segHead("OTHERSHA", payload.size.toLong())

        val outcome = S3UploadSegmentStore(s3, "b").writeSegment(seg("u1", 0, payload.size.toLong()), ByteArrayInputStream(payload))

        val conflict = outcome.shouldBeInstanceOf<WriteSegmentOutcome.Conflict>()
        conflict.segmentIndex shouldBe 0
        conflict.existingSegmentSha256 shouldBe "OTHERSHA"
        conflict.attemptedSegmentSha256 shouldBe sha256Hex(payload)
    }

    test("listSegments reconstructs segments sorted by index with persisted offset") {
        val s3 = mockk<S3Client>()
        every { s3.listObjectsV2(any<Consumer<ListObjectsV2Request.Builder>>()) } returns
            ListObjectsV2Response.builder().isTruncated(false).contents(
                S3Object.builder().key("segments/u1/1").build(),
                S3Object.builder().key("segments/u1/0").build(),
            ).build()
        every { s3.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } returns segHead("sha", 3, offset = 7)

        val segments = S3UploadSegmentStore(s3, "b").listSegments("u1")

        segments.map { it.segmentIndex } shouldBe listOf(0, 1)
        segments.first().segmentOffset shouldBe 7
    }

    test("openSegmentRangeRead computes the Range header and returns the slice") {
        val s3 = mockk<S3Client>()
        every { s3.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } returns segHead("", 10)
        val getSlot = slot<GetObjectRequest>()
        every { s3.getObject(capture(getSlot)) } returns
            ResponseInputStream(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(ByteArrayInputStream("cdef".toByteArray())),
            )

        val slice = S3UploadSegmentStore(s3, "b").openSegmentRangeRead("u1", 0, offset = 2, length = 4).readAllBytes()

        String(slice) shouldBe "cdef"
        getSlot.captured.range() shouldBe "bytes=2-5"
    }

    test("openSegmentRangeRead length 0 returns empty without getObject") {
        val s3 = mockk<S3Client>()
        every { s3.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } returns segHead("", 10)
        S3UploadSegmentStore(s3, "b").openSegmentRangeRead("u1", 0, offset = 3, length = 0).readAllBytes().size shouldBe 0
        verify(exactly = 0) { s3.getObject(any<GetObjectRequest>()) }
    }

    test("openSegmentRangeRead rejects negative and out-of-bounds ranges") {
        val s3 = mockk<S3Client>()
        every { s3.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } returns segHead("", 10)
        val store = S3UploadSegmentStore(s3, "b")
        shouldThrow<IllegalArgumentException> { store.openSegmentRangeRead("u1", 0, -1, 1) }
        shouldThrow<IllegalArgumentException> { store.openSegmentRangeRead("u1", 0, 0, -1) }
        shouldThrow<IllegalArgumentException> { store.openSegmentRangeRead("u1", 0, 11, 0) }
        shouldThrow<IllegalArgumentException> { store.openSegmentRangeRead("u1", 0, 5, 6) }
    }

    test("deleteAllForSession deletes listed segments and returns the count") {
        val s3 = mockk<S3Client>()
        every { s3.listObjectsV2(any<Consumer<ListObjectsV2Request.Builder>>()) } returns
            ListObjectsV2Response.builder().isTruncated(false).contents(
                S3Object.builder().key("segments/u1/0").build(),
                S3Object.builder().key("segments/u1/1").build(),
            ).build()
        every { s3.deleteObjects(any<Consumer<DeleteObjectsRequest.Builder>>()) } returns DeleteObjectsResponse.builder().build()

        S3UploadSegmentStore(s3, "b").deleteAllForSession("u1") shouldBe 2
        verify(exactly = 1) { s3.deleteObjects(any<Consumer<DeleteObjectsRequest.Builder>>()) }
    }

    test("deleteAllForSession on an empty session returns 0 without deleteObjects") {
        val s3 = mockk<S3Client>()
        every { s3.listObjectsV2(any<Consumer<ListObjectsV2Request.Builder>>()) } returns
            ListObjectsV2Response.builder().isTruncated(false).build()
        S3UploadSegmentStore(s3, "b").deleteAllForSession("u1") shouldBe 0
        verify(exactly = 0) { s3.deleteObjects(any<Consumer<DeleteObjectsRequest.Builder>>()) }
    }

    test("rejects unsafe session id and negative segment index") {
        val store = S3UploadSegmentStore(mockk<S3Client>(), "b")
        shouldThrow<IllegalArgumentException> { store.listSegments("a/b") }
        shouldThrow<IllegalArgumentException> { store.writeSegment(seg("u1", -1, 1), ByteArrayInputStream(byteArrayOf(1))) }
    }
})
