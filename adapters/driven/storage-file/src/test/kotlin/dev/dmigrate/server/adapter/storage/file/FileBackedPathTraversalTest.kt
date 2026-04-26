package dev.dmigrate.server.adapter.storage.file

import dev.dmigrate.server.core.upload.UploadSegment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import java.io.ByteArrayInputStream
import java.nio.file.Files

class FileBackedPathTraversalTest : FunSpec({

    test("segment store rejects traversal-laden session ids") {
        val store = FileBackedUploadSegmentStore(Files.createTempDirectory("d-migrate-trav-seg-"))
        val payload = "x".toByteArray()
        forbidden().forEach { unsafe ->
            shouldThrow<IllegalArgumentException> {
                store.writeSegment(
                    UploadSegment(unsafe, 0, 0, payload.size.toLong(), ""),
                    ByteArrayInputStream(payload),
                )
            }
            shouldThrow<IllegalArgumentException> { store.listSegments(unsafe) }
            shouldThrow<IllegalArgumentException> { store.deleteAllForSession(unsafe) }
            shouldThrow<IllegalArgumentException> {
                store.openSegmentRangeRead(unsafe, 0, 0, 0)
            }
        }
    }

    test("segment store rejects negative segment index") {
        val store = FileBackedUploadSegmentStore(Files.createTempDirectory("d-migrate-trav-idx-"))
        shouldThrow<IllegalArgumentException> {
            store.writeSegment(
                UploadSegment("u1", -1, 0, 0, ""),
                ByteArrayInputStream(byteArrayOf()),
            )
        }
        shouldThrow<IllegalArgumentException> {
            store.openSegmentRangeRead("u1", -1, 0, 0)
        }
    }

    test("artifact store rejects traversal-laden ids") {
        val store = FileBackedArtifactContentStore(Files.createTempDirectory("d-migrate-trav-art-"))
        val payload = "x".toByteArray()
        forbidden().forEach { unsafe ->
            shouldThrow<IllegalArgumentException> {
                store.write(unsafe, ByteArrayInputStream(payload), payload.size.toLong())
            }
            shouldThrow<IllegalArgumentException> { store.exists(unsafe) }
            shouldThrow<IllegalArgumentException> { store.delete(unsafe) }
            shouldThrow<IllegalArgumentException> { store.openRangeRead(unsafe, 0, 0) }
        }
    }
})

private fun forbidden(): List<String> = listOf(
    "../escape",
    "..",
    "with/slash",
    "with\\back",
    "",
    "with space",
    "x".repeat(129),
    "with.dot",
    "abs/../../etc/passwd",
)
