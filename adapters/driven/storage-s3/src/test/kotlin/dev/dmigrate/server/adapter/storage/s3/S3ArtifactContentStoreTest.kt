package dev.dmigrate.server.adapter.storage.s3

import dev.dmigrate.core.util.sha256Hex
import dev.dmigrate.server.ports.WriteArtifactOutcome
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
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadResponse
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import software.amazon.awssdk.services.s3.model.UploadPartResponse
import java.io.ByteArrayInputStream
import java.util.function.Consumer

private fun notFound(): S3Exception = S3Exception.builder().statusCode(404).build() as S3Exception

private fun headOf(sha: String, size: Long): HeadObjectResponse =
    HeadObjectResponse.builder().metadata(mapOf("sha256" to sha)).contentLength(size).build()

/**
 * In-Modul-Unit-Tests fuer [S3ArtifactContentStore] gegen einen gemockten
 * `S3Client` — decken die Logik-Branches ohne S3-Server ab (Coverage). Die
 * echte Server-Semantik validiert die SeaweedFS-Integration separat.
 */
class S3ArtifactContentStoreTest : FunSpec({

    test("write stores when absent and returns Stored with sha256") {
        val s3 = mockk<S3Client>()
        every { s3.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } throws notFound()
        every { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns
            PutObjectResponse.builder().build()
        val payload = "hello".toByteArray()

        val outcome = S3ArtifactContentStore(s3, "b").write("a1", ByteArrayInputStream(payload), payload.size.toLong())

        val stored = outcome.shouldBeInstanceOf<WriteArtifactOutcome.Stored>()
        stored.sha256 shouldBe sha256Hex(payload)
        stored.sizeBytes shouldBe payload.size.toLong()
        verify(exactly = 1) { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) }
    }

    test("write rejects on size mismatch without touching S3") {
        val s3 = mockk<S3Client>()
        val outcome = S3ArtifactContentStore(s3, "b").write("a", ByteArrayInputStream("abc".toByteArray()), 10)
        val mismatch = outcome.shouldBeInstanceOf<WriteArtifactOutcome.SizeMismatch>()
        mismatch.expected shouldBe 10
        mismatch.actual shouldBe 3
        verify(exactly = 0) { s3.headObject(any<Consumer<HeadObjectRequest.Builder>>()) }
        verify(exactly = 0) { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) }
    }

    test("write of existing same bytes returns AlreadyExists, no put") {
        val payload = "dup".toByteArray()
        val sha = sha256Hex(payload)
        val s3 = mockk<S3Client>()
        every { s3.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } returns headOf(sha, payload.size.toLong())

        val outcome = S3ArtifactContentStore(s3, "b").write("dup", ByteArrayInputStream(payload), payload.size.toLong())

        val already = outcome.shouldBeInstanceOf<WriteArtifactOutcome.AlreadyExists>()
        already.existingSha256 shouldBe sha
        already.existingSizeBytes shouldBe payload.size.toLong()
        verify(exactly = 0) { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) }
    }

    test("write of existing different bytes returns Conflict") {
        val payload = "beta".toByteArray()
        val s3 = mockk<S3Client>()
        every { s3.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } returns headOf("OTHERSHA", 99)

        val outcome = S3ArtifactContentStore(s3, "b").write("dup", ByteArrayInputStream(payload), payload.size.toLong())

        val conflict = outcome.shouldBeInstanceOf<WriteArtifactOutcome.Conflict>()
        conflict.existingSha256 shouldBe "OTHERSHA"
        conflict.attemptedSha256 shouldBe sha256Hex(payload)
    }

    test("write of large artifact takes the multipart path") {
        val s3 = mockk<S3Client>()
        every { s3.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } throws notFound()
        every { s3.createMultipartUpload(any<Consumer<CreateMultipartUploadRequest.Builder>>()) } returns
            CreateMultipartUploadResponse.builder().uploadId("u1").build()
        every { s3.uploadPart(any<UploadPartRequest>(), any<RequestBody>()) } returns
            UploadPartResponse.builder().eTag("e").build()
        every { s3.completeMultipartUpload(any<Consumer<CompleteMultipartUploadRequest.Builder>>()) } returns
            CompleteMultipartUploadResponse.builder().build()
        val size = 9 * 1024 * 1024 // > 8 MiB -> 2 Parts (8 + 1)
        val payload = ByteArray(size)

        val outcome = S3ArtifactContentStore(s3, "b").write("big", ByteArrayInputStream(payload), size.toLong())

        outcome.shouldBeInstanceOf<WriteArtifactOutcome.Stored>()
        verify(exactly = 0) { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) }
        verify(exactly = 2) { s3.uploadPart(any<UploadPartRequest>(), any<RequestBody>()) }
    }

    test("keyPrefix is applied and sha256 + size metadata are written") {
        val s3 = mockk<S3Client>()
        every { s3.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } throws notFound()
        val putSlot = slot<PutObjectRequest>()
        every { s3.putObject(capture(putSlot), any<RequestBody>()) } returns PutObjectResponse.builder().build()
        val payload = "x".toByteArray()

        S3ArtifactContentStore(s3, "b", keyPrefix = "pre/").write("a1", ByteArrayInputStream(payload), payload.size.toLong())

        putSlot.captured.key() shouldBe "pre/a1"
        putSlot.captured.metadata()["sha256"] shouldBe sha256Hex(payload)
        putSlot.captured.metadata()["size-bytes"] shouldBe "1"
    }

    test("openRangeRead computes the Range header and returns the slice") {
        val s3 = mockk<S3Client>()
        every { s3.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } returns headOf("", 10)
        val getSlot = slot<GetObjectRequest>()
        every { s3.getObject(capture(getSlot)) } returns
            ResponseInputStream(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(ByteArrayInputStream("cdef".toByteArray())),
            )

        val slice = S3ArtifactContentStore(s3, "b").openRangeRead("r", offset = 2, length = 4).readAllBytes()

        String(slice) shouldBe "cdef"
        getSlot.captured.range() shouldBe "bytes=2-5"
    }

    test("openRangeRead with length 0 returns empty, no getObject") {
        val s3 = mockk<S3Client>()
        every { s3.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } returns headOf("", 10)
        S3ArtifactContentStore(s3, "b").openRangeRead("r", offset = 3, length = 0).readAllBytes().size shouldBe 0
        verify(exactly = 0) { s3.getObject(any<GetObjectRequest>()) }
    }

    test("openRangeRead rejects negative and out-of-bounds ranges") {
        val s3 = mockk<S3Client>()
        every { s3.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } returns headOf("", 10)
        val store = S3ArtifactContentStore(s3, "b")
        shouldThrow<IllegalArgumentException> { store.openRangeRead("r", -1, 1) }
        shouldThrow<IllegalArgumentException> { store.openRangeRead("r", 0, -1) }
        shouldThrow<IllegalArgumentException> { store.openRangeRead("r", 11, 0) }
        shouldThrow<IllegalArgumentException> { store.openRangeRead("r", 5, 6) }
    }

    test("exists is true when HeadObject succeeds, false on 404") {
        val present = mockk<S3Client>()
        every { present.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } returns headOf("", 1)
        S3ArtifactContentStore(present, "b").exists("x") shouldBe true

        val absent = mockk<S3Client>()
        every { absent.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } throws notFound()
        S3ArtifactContentStore(absent, "b").exists("x") shouldBe false
    }

    test("delete returns true and calls deleteObject when present") {
        val s3 = mockk<S3Client>()
        every { s3.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } returns headOf("", 1)
        every { s3.deleteObject(any<Consumer<DeleteObjectRequest.Builder>>()) } returns
            DeleteObjectResponse.builder().build()
        S3ArtifactContentStore(s3, "b").delete("x") shouldBe true
        verify(exactly = 1) { s3.deleteObject(any<Consumer<DeleteObjectRequest.Builder>>()) }
    }

    test("delete returns false and skips deleteObject when absent") {
        val s3 = mockk<S3Client>()
        every { s3.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } throws notFound()
        S3ArtifactContentStore(s3, "b").delete("x") shouldBe false
        verify(exactly = 0) { s3.deleteObject(any<Consumer<DeleteObjectRequest.Builder>>()) }
    }

    test("HeadObject errors other than 404 propagate") {
        val s3 = mockk<S3Client>()
        every { s3.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } throws
            S3Exception.builder().statusCode(500).build()
        shouldThrow<S3Exception> { S3ArtifactContentStore(s3, "b").exists("x") }
    }

    test("rejects unsafe artifactIds before any S3 call") {
        val store = S3ArtifactContentStore(mockk<S3Client>(), "b")
        shouldThrow<IllegalArgumentException> { store.exists("") }
        shouldThrow<IllegalArgumentException> { store.exists("a/b") }
        shouldThrow<IllegalArgumentException> { store.exists("..") }
    }

    test("write against an existing object lacking sha256 metadata returns Conflict with empty existing sha") {
        val payload = "x".toByteArray()
        val s3 = mockk<S3Client>()
        every { s3.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } returns
            HeadObjectResponse.builder().metadata(emptyMap()).contentLength(payload.size.toLong()).build()

        val outcome = S3ArtifactContentStore(s3, "b").write("dup", ByteArrayInputStream(payload), payload.size.toLong())

        val conflict = outcome.shouldBeInstanceOf<WriteArtifactOutcome.Conflict>()
        conflict.existingSha256 shouldBe ""
        conflict.attemptedSha256 shouldBe sha256Hex(payload)
    }

    test("multipart aborts the upload and rethrows when a part upload fails") {
        val s3 = mockk<S3Client>()
        every { s3.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } throws notFound()
        every { s3.createMultipartUpload(any<Consumer<CreateMultipartUploadRequest.Builder>>()) } returns
            CreateMultipartUploadResponse.builder().uploadId("u1").build()
        every { s3.uploadPart(any<UploadPartRequest>(), any<RequestBody>()) } throws
            S3Exception.builder().statusCode(500).build()
        every { s3.abortMultipartUpload(any<Consumer<AbortMultipartUploadRequest.Builder>>()) } returns
            AbortMultipartUploadResponse.builder().build()
        val size = 9 * 1024 * 1024

        shouldThrow<S3Exception> {
            S3ArtifactContentStore(s3, "b").write("big", ByteArrayInputStream(ByteArray(size)), size.toLong())
        }
        verify(exactly = 1) { s3.abortMultipartUpload(any<Consumer<AbortMultipartUploadRequest.Builder>>()) }
    }
})
