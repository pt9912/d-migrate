package dev.dmigrate.server.adapter.storage.file

import dev.dmigrate.server.core.upload.UploadSegment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.nio.file.Files

class FileBackedTtlCleanupTest : FunSpec({

    test("deleteAllForSession removes session directory and bin counter") {
        val root = Files.createTempDirectory("d-migrate-ttl-")
        val store = FileBackedUploadSegmentStore(root)
        val payload = "abc".toByteArray()
        store.writeSegment(
            UploadSegment("u1", 0, 0, payload.size.toLong(), ""),
            ByteArrayInputStream(payload),
        )
        store.writeSegment(
            UploadSegment("u1", 1, payload.size.toLong(), payload.size.toLong(), ""),
            ByteArrayInputStream(payload),
        )
        store.deleteAllForSession("u1") shouldBe 2
        store.listSegments("u1") shouldBe emptyList()
        Files.exists(root.resolve("segments").resolve("u1")) shouldBe false
    }

    test("deleteAllForSession is idempotent for unknown sessions") {
        val root = Files.createTempDirectory("d-migrate-ttl-empty-")
        val store = FileBackedUploadSegmentStore(root)
        store.deleteAllForSession("ghost") shouldBe 0
    }

    test("deleteAllForSession does not affect siblings") {
        val root = Files.createTempDirectory("d-migrate-ttl-sib-")
        val store = FileBackedUploadSegmentStore(root)
        val payload = "abc".toByteArray()
        store.writeSegment(
            UploadSegment("alive", 0, 0, payload.size.toLong(), ""),
            ByteArrayInputStream(payload),
        )
        store.writeSegment(
            UploadSegment("expired", 0, 0, payload.size.toLong(), ""),
            ByteArrayInputStream(payload),
        )
        store.deleteAllForSession("expired")
        store.listSegments("alive").map { it.segmentIndex } shouldBe listOf(0)
    }
})
