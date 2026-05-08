package dev.dmigrate.server.core.upload

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AssembledUploadPayloadTest : FunSpec({

    test("fromBytes exposes sizeBytes / sha256 and re-opens on each openStream") {
        val bytes = "static-payload".toByteArray()
        val payload = AssembledUploadPayload.fromBytes(bytes, sha256 = "deadbeef")

        payload.sizeBytes shouldBe bytes.size.toLong()
        payload.sha256 shouldBe "deadbeef"

        val first = payload.openStream().use { it.readAllBytes() }
        val second = payload.openStream().use { it.readAllBytes() }
        first shouldBe bytes
        second shouldBe bytes
    }

    test("fromBytes close is a no-op and may be called repeatedly") {
        val payload = AssembledUploadPayload.fromBytes("x".toByteArray(), "x")
        payload.close()
        payload.close()
        // Still readable — the in-memory variant is idempotent and
        // does not invalidate on close.
        payload.openStream().use { it.readAllBytes() }.toString(Charsets.UTF_8) shouldBe "x"
    }
})
