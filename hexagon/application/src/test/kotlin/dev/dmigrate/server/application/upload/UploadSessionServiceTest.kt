package dev.dmigrate.server.application.upload

import dev.dmigrate.server.core.upload.UploadSegment
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.TransitionOutcome
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryUploadSegmentStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream

class UploadSessionServiceTest : FunSpec({

    fun harness(): Triple<UploadSessionService, InMemoryUploadSessionStore, InMemoryUploadSegmentStore> {
        val sessions = InMemoryUploadSessionStore()
        val segments = InMemoryUploadSegmentStore()
        return Triple(UploadSessionService(sessions, segments), sessions, segments)
    }

    fun seedSegment(segments: InMemoryUploadSegmentStore, sessionId: String, payload: ByteArray) {
        segments.writeSegment(
            UploadSegment(sessionId, 0, 0, payload.size.toLong(), ""),
            ByteArrayInputStream(payload),
        )
    }

    test("expireDue transitions sessions and clears their spool") {
        val (service, sessions, segments) = harness()
        sessions.save(
            Fixtures.uploadSession(
                "stale",
                idleTimeoutAt = Fixtures.NOW.minusSeconds(10),
                absoluteLeaseExpiresAt = Fixtures.NOW.plusSeconds(10_000),
            ),
        )
        sessions.save(Fixtures.uploadSession("fresh"))
        seedSegment(segments, "stale", "abc".toByteArray())
        seedSegment(segments, "fresh", "def".toByteArray())

        val expired = service.expireDue(Fixtures.NOW)

        expired.map { it.uploadSessionId } shouldBe listOf("stale")
        expired.single().state shouldBe UploadSessionState.EXPIRED
        segments.listSegments("stale") shouldBe emptyList()
        segments.listSegments("fresh").size shouldBe 1
    }

    test("abort transitions ACTIVE -> ABORTED and clears the spool") {
        val (service, sessions, segments) = harness()
        sessions.save(Fixtures.uploadSession("u1"))
        seedSegment(segments, "u1", "abc".toByteArray())

        val outcome = service.abort(Fixtures.tenant("acme"), "u1", Fixtures.NOW)

        outcome.shouldBeInstanceOf<TransitionOutcome.Applied>()
        outcome.session.state shouldBe UploadSessionState.ABORTED
        segments.listSegments("u1") shouldBe emptyList()
    }

    test("abort on unknown session returns NotFound and leaves spool untouched") {
        val (service, _, segments) = harness()
        seedSegment(segments, "ghost", "abc".toByteArray())

        val outcome = service.abort(Fixtures.tenant("acme"), "ghost", Fixtures.NOW)

        outcome shouldBe TransitionOutcome.NotFound
        segments.listSegments("ghost").size shouldBe 1
    }

    test("abort on already-terminal session is IllegalTransition and keeps spool") {
        val (service, sessions, segments) = harness()
        sessions.save(Fixtures.uploadSession("u1", state = UploadSessionState.COMPLETED))
        seedSegment(segments, "u1", "abc".toByteArray())

        val outcome = service.abort(Fixtures.tenant("acme"), "u1", Fixtures.NOW)

        outcome.shouldBeInstanceOf<TransitionOutcome.IllegalTransition>()
        segments.listSegments("u1").size shouldBe 1
    }

    test("finalize transitions ACTIVE -> COMPLETED and clears the spool") {
        val (service, sessions, segments) = harness()
        sessions.save(Fixtures.uploadSession("u1"))
        seedSegment(segments, "u1", "abc".toByteArray())

        val outcome = service.finalize(Fixtures.tenant("acme"), "u1", Fixtures.NOW)

        outcome.shouldBeInstanceOf<TransitionOutcome.Applied>()
        outcome.session.state shouldBe UploadSessionState.COMPLETED
        segments.listSegments("u1") shouldBe emptyList()
    }

    test("finalize on already-terminal session leaves spool untouched") {
        val (service, sessions, segments) = harness()
        sessions.save(Fixtures.uploadSession("u1", state = UploadSessionState.ABORTED))
        seedSegment(segments, "u1", "abc".toByteArray())

        val outcome = service.finalize(Fixtures.tenant("acme"), "u1", Fixtures.NOW)

        outcome.shouldBeInstanceOf<TransitionOutcome.IllegalTransition>()
        segments.listSegments("u1").size shouldBe 1
    }
})
