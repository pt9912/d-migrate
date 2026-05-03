package dev.dmigrate.server.adapter.storage.file

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.deleteRecursively

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class FileSpoolAssembledUploadPayloadTest : FunSpec({

    test("allocate places the spool under <stateDir>/assembly/<uploadSessionId>/<uuid>.bin") {
        val stateDir = Files.createTempDirectory("dmigrate-spool-allocate-")
        try {
            val path = FileSpoolAssembledUploadPayload.allocate(stateDir, "session-1")
            path.toString() shouldContain "/assembly/session-1/"
            path.toString() shouldEndWith ".bin"
            // Parent dirs exist; the file itself is empty until the
            // streaming assembly writes the segment bytes.
            Files.exists(path.parent).shouldBeTrue()
            Files.exists(path).shouldBeFalse()
        } finally {
            stateDir.deleteRecursively()
        }
    }

    test("openStream returns the on-disk bytes; multiple opens read independently") {
        val stateDir = Files.createTempDirectory("dmigrate-spool-read-")
        try {
            val path = FileSpoolAssembledUploadPayload.allocate(stateDir, "session-2")
            val payloadBytes = "spool-content".toByteArray()
            Files.write(path, payloadBytes)

            val payload = FileSpoolAssembledUploadPayload(
                path = path,
                sizeBytes = payloadBytes.size.toLong(),
                sha256 = "abc",
            )
            payload.openStream().use { it.readAllBytes() } shouldBe payloadBytes
            payload.openStream().use { it.readAllBytes() } shouldBe payloadBytes
        } finally {
            stateDir.deleteRecursively()
        }
    }

    test("close removes the spool file and is idempotent") {
        val stateDir = Files.createTempDirectory("dmigrate-spool-close-")
        try {
            val path = FileSpoolAssembledUploadPayload.allocate(stateDir, "session-3")
            Files.write(path, byteArrayOf(1, 2, 3))

            val payload = FileSpoolAssembledUploadPayload(path, 3L, "x")
            payload.close()
            Files.exists(path).shouldBeFalse()

            // Idempotent — second close does not throw.
            payload.close()
        } finally {
            stateDir.deleteRecursively()
        }
    }

    test("openStream after close throws IOException") {
        val stateDir = Files.createTempDirectory("dmigrate-spool-after-close-")
        try {
            val path = FileSpoolAssembledUploadPayload.allocate(stateDir, "session-4")
            Files.write(path, byteArrayOf(1))

            val payload = FileSpoolAssembledUploadPayload(path, 1L, "x")
            payload.close()
            shouldThrow<IOException> { payload.openStream() }
        } finally {
            stateDir.deleteRecursively()
        }
    }
})

private infix fun String.shouldContain(substring: String) {
    if (substring !in this) error("expected \"$this\" to contain \"$substring\"")
}

private infix fun String.shouldEndWith(suffix: String) {
    if (!endsWith(suffix)) error("expected \"$this\" to end with \"$suffix\"")
}
