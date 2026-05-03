package dev.dmigrate.server.ports.contract

import dev.dmigrate.server.ports.ArtifactContentStore
import dev.dmigrate.server.ports.WriteArtifactOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream

abstract class ArtifactContentStoreContractTests(factory: () -> ArtifactContentStore) : FunSpec({

    test("write stores bytes and returns sha256") {
        val store = factory()
        val payload = "hello world".toByteArray()
        val outcome = store.write("art_1", ByteArrayInputStream(payload), payload.size.toLong())
        outcome.shouldBeInstanceOf<WriteArtifactOutcome.Stored>()
        outcome.artifactId shouldBe "art_1"
        outcome.sizeBytes shouldBe payload.size.toLong()
        outcome.sha256.length shouldBe 64
        store.exists("art_1") shouldBe true
    }

    test("write rejects when actual size differs from expected") {
        val store = factory()
        val payload = "abc".toByteArray()
        val outcome = store.write("mismatch", ByteArrayInputStream(payload), expectedSizeBytes = 10)
        outcome.shouldBeInstanceOf<WriteArtifactOutcome.SizeMismatch>()
        outcome.expected shouldBe 10
        outcome.actual shouldBe 3
        store.exists("mismatch") shouldBe false
    }

    test("write of existing artifact with same bytes returns AlreadyExists with size") {
        val store = factory()
        val payload = "static".toByteArray()
        val first = store.write("dup", ByteArrayInputStream(payload), payload.size.toLong())
            as WriteArtifactOutcome.Stored
        val second = store.write("dup", ByteArrayInputStream(payload), payload.size.toLong())
        val already = second.shouldBeInstanceOf<WriteArtifactOutcome.AlreadyExists>()
        already.existingSha256 shouldBe first.sha256
        // AP 6.22: callers (the deterministic-id replay path) compare
        // both SHA AND size — the contract surfaces both.
        already.existingSizeBytes shouldBe payload.size.toLong()
    }

    test("write of existing artifact with different bytes returns Conflict") {
        val store = factory()
        val original = "alpha-content".toByteArray()
        val different = "beta-content_".toByteArray()
        val first = store.write("dup", ByteArrayInputStream(original), original.size.toLong())
            as WriteArtifactOutcome.Stored
        val outcome = store.write("dup", ByteArrayInputStream(different), different.size.toLong())
        outcome.shouldBeInstanceOf<WriteArtifactOutcome.Conflict>()
        outcome.existingSha256 shouldBe first.sha256
        (outcome.existingSha256 == outcome.attemptedSha256) shouldBe false
    }

    test("openRangeRead returns the requested slice") {
        val store = factory()
        val payload = "abcdefghij".toByteArray()
        store.write("range", ByteArrayInputStream(payload), payload.size.toLong())
        val slice = store.openRangeRead("range", offset = 2, length = 4).readAllBytes()
        String(slice) shouldBe "cdef"
    }

    test("openRangeRead with length=0 returns empty stream") {
        val store = factory()
        val payload = "abcdefghij".toByteArray()
        store.write("range0", ByteArrayInputStream(payload), payload.size.toLong())
        val slice = store.openRangeRead("range0", offset = 3, length = 0).readAllBytes()
        slice.size shouldBe 0
    }

    test("openRangeRead rejects out-of-bounds and negative ranges") {
        val store = factory()
        val payload = "abcdefghij".toByteArray()
        store.write("rangeoob", ByteArrayInputStream(payload), payload.size.toLong())
        shouldThrow<IllegalArgumentException> {
            store.openRangeRead("rangeoob", offset = -1, length = 1)
        }
        shouldThrow<IllegalArgumentException> {
            store.openRangeRead("rangeoob", offset = 0, length = -1)
        }
        shouldThrow<IllegalArgumentException> {
            store.openRangeRead("rangeoob", offset = 11, length = 0)
        }
        shouldThrow<IllegalArgumentException> {
            store.openRangeRead("rangeoob", offset = 5, length = 6)
        }
    }

    test("delete removes content") {
        val store = factory()
        val payload = "to-delete".toByteArray()
        store.write("gone", ByteArrayInputStream(payload), payload.size.toLong())
        store.delete("gone") shouldBe true
        store.exists("gone") shouldBe false
        store.delete("gone") shouldBe false
    }
})
