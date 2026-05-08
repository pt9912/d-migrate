package dev.dmigrate.server.adapter.storage.file

import dev.dmigrate.server.core.upload.UploadSegment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.nio.file.Files

class FileBackedRangeReadTest : FunSpec({

    fun newSegmentStore(): FileBackedUploadSegmentStore =
        FileBackedUploadSegmentStore(Files.createTempDirectory("d-migrate-range-seg-"))

    fun newArtifactStore(): FileBackedArtifactContentStore =
        FileBackedArtifactContentStore(Files.createTempDirectory("d-migrate-range-art-"))

    test("segment range read returns multiple non-overlapping slices") {
        val store = newSegmentStore()
        val payload = "abcdefghij".toByteArray()
        val seg = UploadSegment("u1", 0, 0, payload.size.toLong(), "")
        store.writeSegment(seg, ByteArrayInputStream(payload))
        String(store.openSegmentRangeRead("u1", 0, 0, 3).readAllBytes()) shouldBe "abc"
        String(store.openSegmentRangeRead("u1", 0, 3, 4).readAllBytes()) shouldBe "defg"
        String(store.openSegmentRangeRead("u1", 0, 7, 3).readAllBytes()) shouldBe "hij"
    }

    test("segment range read at the very end") {
        val store = newSegmentStore()
        val payload = ByteArray(16) { it.toByte() }
        store.writeSegment(
            UploadSegment("u1", 0, 0, payload.size.toLong(), ""),
            ByteArrayInputStream(payload),
        )
        store.openSegmentRangeRead("u1", 0, 16, 0).readAllBytes().size shouldBe 0
        store.openSegmentRangeRead("u1", 0, 15, 1).readAllBytes()[0] shouldBe 15.toByte()
    }

    test("segment range read enforces invariants") {
        val store = newSegmentStore()
        val payload = "abc".toByteArray()
        store.writeSegment(
            UploadSegment("u1", 0, 0, payload.size.toLong(), ""),
            ByteArrayInputStream(payload),
        )
        shouldThrow<IllegalArgumentException> {
            store.openSegmentRangeRead("u1", 0, offset = -1, length = 1)
        }
        shouldThrow<IllegalArgumentException> {
            store.openSegmentRangeRead("u1", 0, offset = 0, length = -1)
        }
        shouldThrow<IllegalArgumentException> {
            store.openSegmentRangeRead("u1", 0, offset = 4, length = 0)
        }
        shouldThrow<IllegalArgumentException> {
            store.openSegmentRangeRead("u1", 0, offset = 1, length = 5)
        }
    }

    test("artifact range read across multiple slices") {
        val store = newArtifactStore()
        val payload = "0123456789".toByteArray()
        store.write("art", ByteArrayInputStream(payload), payload.size.toLong())
        String(store.openRangeRead("art", 0, 5).readAllBytes()) shouldBe "01234"
        String(store.openRangeRead("art", 5, 5).readAllBytes()) shouldBe "56789"
        store.openRangeRead("art", 10, 0).readAllBytes().size shouldBe 0
    }

    test("artifact range read enforces invariants") {
        val store = newArtifactStore()
        val payload = "abc".toByteArray()
        store.write("art2", ByteArrayInputStream(payload), payload.size.toLong())
        shouldThrow<IllegalArgumentException> { store.openRangeRead("art2", -1, 1) }
        shouldThrow<IllegalArgumentException> { store.openRangeRead("art2", 0, -1) }
        shouldThrow<IllegalArgumentException> { store.openRangeRead("art2", 4, 0) }
        shouldThrow<IllegalArgumentException> { store.openRangeRead("art2", 1, 5) }
    }
})
