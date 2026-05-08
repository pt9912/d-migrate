package dev.dmigrate.server.core.upload

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AssembledUploadPayloadFactoryTest : FunSpec({

    test("inMemory factory writes through output and publishes a re-openable payload") {
        val factory = AssembledUploadPayloadFactory.inMemory()
        val spool = factory.allocate("session-1")
        val bytes = "static-payload".toByteArray()
        spool.output.write(bytes)
        val payload = spool.publish(sizeBytes = bytes.size.toLong(), sha256 = "deadbeef")

        payload.sizeBytes shouldBe bytes.size.toLong()
        payload.sha256 shouldBe "deadbeef"
        payload.openStream().use { it.readAllBytes() } shouldBe bytes
        payload.openStream().use { it.readAllBytes() } shouldBe bytes
    }

    test("inMemory publish twice throws IllegalStateException") {
        val factory = AssembledUploadPayloadFactory.inMemory()
        val spool = factory.allocate("session-2")
        spool.output.write(byteArrayOf(1, 2, 3))
        spool.publish(3L, "x")
        shouldThrow<IllegalStateException> { spool.publish(3L, "x") }
    }

    test("inMemory close is idempotent") {
        val factory = AssembledUploadPayloadFactory.inMemory()
        val spool = factory.allocate("session-3")
        spool.output.write(byteArrayOf(1))
        spool.close()
        spool.close() // must not throw
    }

    test("inMemory close without publish does not block a fresh allocation") {
        val factory = AssembledUploadPayloadFactory.inMemory()
        factory.allocate("session-4").close()
        val again = factory.allocate("session-4")
        again.output.write(byteArrayOf(7))
        val payload = again.publish(1L, "x")
        payload.openStream().use { it.readAllBytes() } shouldBe byteArrayOf(7)
    }
})
