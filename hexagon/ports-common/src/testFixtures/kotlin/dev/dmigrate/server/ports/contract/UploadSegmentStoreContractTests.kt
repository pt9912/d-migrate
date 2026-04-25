package dev.dmigrate.server.ports.contract

import dev.dmigrate.server.ports.UploadSegmentStore
import dev.dmigrate.server.ports.WriteSegmentOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream

abstract class UploadSegmentStoreContractTests(factory: () -> UploadSegmentStore) : FunSpec({

    test("writeSegment stores fresh segment and computes hash") {
        val store = factory()
        val payload = "first-segment".toByteArray()
        val segment = Fixtures.uploadSegment("u1", index = 0, sizeBytes = payload.size.toLong())
        val outcome = store.writeSegment(segment, ByteArrayInputStream(payload))
        outcome.shouldBeInstanceOf<WriteSegmentOutcome.Stored>()
        store.listSegments("u1").map { it.segmentIndex } shouldBe listOf(0)
    }

    test("identical re-write returns AlreadyStored (idempotent)") {
        val store = factory()
        val payload = "same".toByteArray()
        val segment = Fixtures.uploadSegment("u1", index = 0, sizeBytes = payload.size.toLong())
        store.writeSegment(segment, ByteArrayInputStream(payload))
        val second = store.writeSegment(segment, ByteArrayInputStream(payload))
        second.shouldBeInstanceOf<WriteSegmentOutcome.AlreadyStored>()
    }

    test("conflicting bytes at same index return Conflict") {
        val store = factory()
        val original = "alpha".toByteArray()
        val different = "beta_".toByteArray()
        val segment = Fixtures.uploadSegment("u1", index = 0, sizeBytes = original.size.toLong())
        store.writeSegment(segment, ByteArrayInputStream(original))
        val outcome = store.writeSegment(segment, ByteArrayInputStream(different))
        outcome.shouldBeInstanceOf<WriteSegmentOutcome.Conflict>()
        outcome.segmentIndex shouldBe 0
        outcome.existingSegmentSha256.shouldNotBeBlank()
        outcome.attemptedSegmentSha256.shouldNotBeBlank()
        (outcome.existingSegmentSha256 != outcome.attemptedSegmentSha256) shouldBe true
    }

    test("size mismatch is reported") {
        val store = factory()
        val payload = "tiny".toByteArray()
        val segment = Fixtures.uploadSegment("u1", index = 1, sizeBytes = payload.size + 5L)
        val outcome = store.writeSegment(segment, ByteArrayInputStream(payload))
        outcome.shouldBeInstanceOf<WriteSegmentOutcome.SizeMismatch>()
        outcome.expected shouldBe payload.size + 5L
        outcome.actual shouldBe payload.size.toLong()
    }

    test("openSegmentRangeRead returns slice of segment bytes") {
        val store = factory()
        val payload = "abcdefghij".toByteArray()
        val segment = Fixtures.uploadSegment("u1", index = 0, sizeBytes = payload.size.toLong())
        store.writeSegment(segment, ByteArrayInputStream(payload))
        val slice = store.openSegmentRangeRead("u1", 0, offset = 2, length = 4).readAllBytes()
        String(slice) shouldBe "cdef"
    }

    test("deleteAllForSession removes only that session's segments") {
        val store = factory()
        val payload = "x".toByteArray()
        store.writeSegment(
            Fixtures.uploadSegment("u1", index = 0, sizeBytes = payload.size.toLong()),
            ByteArrayInputStream(payload),
        )
        store.writeSegment(
            Fixtures.uploadSegment("u2", index = 0, sizeBytes = payload.size.toLong()),
            ByteArrayInputStream(payload),
        )
        store.deleteAllForSession("u1") shouldBe 1
        store.listSegments("u1") shouldBe emptyList()
        store.listSegments("u2").size shouldBe 1
    }
})
