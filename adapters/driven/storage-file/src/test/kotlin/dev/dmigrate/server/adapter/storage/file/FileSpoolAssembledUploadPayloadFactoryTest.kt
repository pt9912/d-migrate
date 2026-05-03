package dev.dmigrate.server.adapter.storage.file

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.deleteRecursively

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class FileSpoolAssembledUploadPayloadFactoryTest : FunSpec({

    test("allocate + write + publish yields a re-openable payload backed by the spool file") {
        val stateDir = Files.createTempDirectory("dmigrate-spool-factory-publish-")
        try {
            val factory = FileSpoolAssembledUploadPayloadFactory(stateDir)
            val spool = factory.allocate("session-1")
            val bytes = "hello-stream".toByteArray()
            spool.output.write(bytes)
            val payload = spool.publish(sizeBytes = bytes.size.toLong(), sha256 = "deadbeef")

            payload.sizeBytes shouldBe bytes.size.toLong()
            payload.sha256 shouldBe "deadbeef"
            payload.openStream().use { it.readAllBytes() } shouldBe bytes
            payload.openStream().use { it.readAllBytes() } shouldBe bytes
            payload.close()
        } finally {
            stateDir.deleteRecursively()
        }
    }

    test("close without publish discards the spool file") {
        val stateDir = Files.createTempDirectory("dmigrate-spool-factory-discard-")
        try {
            val factory = FileSpoolAssembledUploadPayloadFactory(stateDir)
            val spool = factory.allocate("session-2")
            spool.output.write(byteArrayOf(1, 2, 3))

            // Capture the path indirectly by listing the assembly dir,
            // since FileSpoolHandle does not expose it. The factory
            // promises exactly one .bin under <stateDir>/assembly/<id>/
            // before close.
            val sessionDir = stateDir.resolve("assembly").resolve("session-2")
            val before = Files.list(sessionDir).use { it.toList() }
            before.size shouldBe 1

            spool.close()

            val after = Files.list(sessionDir).use { it.toList() }
            after.shouldBeEmpty()
        } finally {
            stateDir.deleteRecursively()
        }
    }

    test("publish twice throws IllegalStateException") {
        val stateDir = Files.createTempDirectory("dmigrate-spool-factory-republish-")
        try {
            val factory = FileSpoolAssembledUploadPayloadFactory(stateDir)
            val spool = factory.allocate("session-3")
            spool.output.write(byteArrayOf(9))
            spool.publish(1L, "x")
            shouldThrow<IllegalStateException> { spool.publish(1L, "x") }
        } finally {
            stateDir.deleteRecursively()
        }
    }

    test("close after publish does NOT delete the file (payload owns it now)") {
        val stateDir = Files.createTempDirectory("dmigrate-spool-factory-handoff-")
        try {
            val factory = FileSpoolAssembledUploadPayloadFactory(stateDir)
            val spool = factory.allocate("session-4")
            spool.output.write(byteArrayOf(7))
            val payload = spool.publish(1L, "x")
            spool.close()

            // Payload's file is still there until the payload itself
            // closes — pin that the spool's close is now a no-op.
            val sessionDir = stateDir.resolve("assembly").resolve("session-4")
            Files.list(sessionDir).use { it.count() } shouldBe 1L

            payload.close()
            Files.list(sessionDir).use { it.toList() }.shouldBeEmpty()
        } finally {
            stateDir.deleteRecursively()
        }
    }

    test("close is idempotent") {
        val stateDir = Files.createTempDirectory("dmigrate-spool-factory-idemp-")
        try {
            val factory = FileSpoolAssembledUploadPayloadFactory(stateDir)
            val spool = factory.allocate("session-5")
            spool.output.write(byteArrayOf(1))
            spool.close()
            spool.close() // must not throw

            val sessionDir = stateDir.resolve("assembly").resolve("session-5")
            Files.list(sessionDir).use { it.toList() }.shouldBeEmpty()
        } finally {
            stateDir.deleteRecursively()
        }
    }
})

private fun List<*>.shouldBeEmpty() {
    if (isNotEmpty()) error("expected empty list, got $this")
}
