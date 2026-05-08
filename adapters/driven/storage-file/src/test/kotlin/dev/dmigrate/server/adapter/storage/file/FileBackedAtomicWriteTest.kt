package dev.dmigrate.server.adapter.storage.file

import dev.dmigrate.server.core.upload.UploadSegment
import dev.dmigrate.server.ports.WriteSegmentOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

class FileBackedAtomicWriteTest : FunSpec({

    test("partial stream failure leaves no visible final segment file") {
        val root: Path = Files.createTempDirectory("d-migrate-atomic-")
        val store = FileBackedUploadSegmentStore(root)
        val segment = UploadSegment(
            uploadSessionId = "u1",
            segmentIndex = 0,
            segmentOffset = 0,
            sizeBytes = 8,
            segmentSha256 = "ignored",
        )
        val crashing = object : InputStream() {
            private var emitted = 0
            override fun read(): Int {
                if (emitted >= 4) throw IOException("simulated crash")
                emitted++
                return 'a'.code
            }
        }
        shouldThrow<IOException> { store.writeSegment(segment, crashing) }
        val sessionDir = root.resolve("segments").resolve("u1")
        if (Files.exists(sessionDir)) {
            Files.list(sessionDir).use { stream ->
                val finals = stream
                    .filter { it.fileName.toString() == "0.bin" }
                    .count()
                finals shouldBe 0L
            }
        }
    }

    test("size mismatch leaves no visible final segment file") {
        val root: Path = Files.createTempDirectory("d-migrate-mismatch-")
        val store = FileBackedUploadSegmentStore(root)
        val segment = UploadSegment("u2", 0, 0, sizeBytes = 100, segmentSha256 = "")
        val outcome = store.writeSegment(segment, ByteArrayInputStream("short".toByteArray()))
        outcome.shouldBeInstanceOf<WriteSegmentOutcome.SizeMismatch>()
        Files.exists(root.resolve("segments").resolve("u2").resolve("0.bin")) shouldBe false
    }

    test("artifact size mismatch leaves no committed file") {
        val root: Path = Files.createTempDirectory("d-migrate-art-mismatch-")
        val store = FileBackedArtifactContentStore(root)
        store.write("art1", ByteArrayInputStream("ab".toByteArray()), expectedSizeBytes = 10)
        store.exists("art1") shouldBe false
        Files.list(root.resolve("artifacts")).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .flatMap { Files.list(it) }
                .filter { it.fileName.toString() == "art1.bin" }
                .count() shouldBe 0L
        }
    }

    test("committed segment is durable across new store instance") {
        val root: Path = Files.createTempDirectory("d-migrate-durable-")
        val first = FileBackedUploadSegmentStore(root)
        val payload = "abcd".toByteArray()
        val seg = UploadSegment("ses", 0, 0, payload.size.toLong(), "")
        first.writeSegment(seg, ByteArrayInputStream(payload)).shouldBeInstanceOf<WriteSegmentOutcome.Stored>()
        val second = FileBackedUploadSegmentStore(root)
        second.listSegments("ses").map { it.segmentIndex } shouldBe listOf(0)
    }
})
