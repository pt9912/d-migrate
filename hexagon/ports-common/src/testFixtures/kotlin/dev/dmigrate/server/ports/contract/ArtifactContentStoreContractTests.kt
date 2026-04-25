package dev.dmigrate.server.ports.contract

import dev.dmigrate.server.ports.ArtifactContentStore
import dev.dmigrate.server.ports.WriteArtifactOutcome
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

    test("write of existing artifact returns AlreadyExists with same hash") {
        val store = factory()
        val payload = "static".toByteArray()
        val first = store.write("dup", ByteArrayInputStream(payload), payload.size.toLong())
            as WriteArtifactOutcome.Stored
        val second = store.write("dup", ByteArrayInputStream(payload), payload.size.toLong())
        second.shouldBeInstanceOf<WriteArtifactOutcome.AlreadyExists>()
        second.existingSha256 shouldBe first.sha256
    }

    test("openRangeRead returns the requested slice") {
        val store = factory()
        val payload = "abcdefghij".toByteArray()
        store.write("range", ByteArrayInputStream(payload), payload.size.toLong())
        val slice = store.openRangeRead("range", offset = 2, length = 4).readAllBytes()
        String(slice) shouldBe "cdef"
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
