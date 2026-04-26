package dev.dmigrate.server.application.upload

import dev.dmigrate.server.application.upload.UploadSessionService.FinalizeOutcome
import dev.dmigrate.server.core.upload.UploadSegment
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.core.upload.UploadSessionTransitions
import dev.dmigrate.server.ports.TransitionOutcome
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryUploadSegmentStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream

class UploadSessionServiceTest : FunSpec({

    data class Harness(
        val service: UploadSessionService,
        val sessions: InMemoryUploadSessionStore,
        val segments: InMemoryUploadSegmentStore,
    )

    fun harness(): Harness {
        val sessions = InMemoryUploadSessionStore()
        val segments = InMemoryUploadSegmentStore()
        return Harness(UploadSessionService(sessions, segments), sessions, segments)
    }

    fun seedSegment(segments: InMemoryUploadSegmentStore, sessionId: String, payload: ByteArray) {
        segments.writeSegment(
            UploadSegment(sessionId, 0, 0, payload.size.toLong(), "ignored"),
            ByteArrayInputStream(payload),
        )
    }

    test("expireDue transitions sessions and clears their spool") {
        val h = harness()
        h.sessions.save(
            Fixtures.uploadSession(
                "stale",
                idleTimeoutAt = Fixtures.NOW.minusSeconds(10),
                absoluteLeaseExpiresAt = Fixtures.NOW.plusSeconds(10_000),
            ),
        )
        h.sessions.save(Fixtures.uploadSession("fresh"))
        seedSegment(h.segments, "stale", "abc".toByteArray())
        seedSegment(h.segments, "fresh", "def".toByteArray())

        val expired = h.service.expireDue(Fixtures.NOW)

        expired.map { it.uploadSessionId } shouldBe listOf("stale")
        expired.single().state shouldBe UploadSessionState.EXPIRED
        h.segments.listSegments("stale") shouldBe emptyList()
        h.segments.listSegments("fresh").size shouldBe 1
    }

    test("abort transitions ACTIVE -> ABORTED and clears the spool") {
        val h = harness()
        h.sessions.save(Fixtures.uploadSession("u1"))
        seedSegment(h.segments, "u1", "abc".toByteArray())

        val outcome = h.service.abort(Fixtures.tenant("acme"), "u1", Fixtures.NOW)

        outcome.shouldBeInstanceOf<TransitionOutcome.Applied>()
        outcome.session.state shouldBe UploadSessionState.ABORTED
        h.segments.listSegments("u1") shouldBe emptyList()
    }

    test("abort on unknown session returns NotFound and leaves spool untouched") {
        val h = harness()
        seedSegment(h.segments, "ghost", "abc".toByteArray())

        val outcome = h.service.abort(Fixtures.tenant("acme"), "ghost", Fixtures.NOW)

        outcome shouldBe TransitionOutcome.NotFound
        h.segments.listSegments("ghost").size shouldBe 1
    }

    test("abort on already-terminal session is IllegalTransition and keeps spool") {
        val h = harness()
        h.sessions.save(Fixtures.uploadSession("u1", state = UploadSessionState.COMPLETED))
        seedSegment(h.segments, "u1", "abc".toByteArray())

        val outcome = h.service.abort(Fixtures.tenant("acme"), "u1", Fixtures.NOW)

        outcome.shouldBeInstanceOf<TransitionOutcome.IllegalTransition>()
        h.segments.listSegments("u1").size shouldBe 1
    }

    test("finalize Applied: validation passes, state COMPLETED, spool cleared") {
        val h = harness()
        val payload = "hello".toByteArray()
        h.sessions.save(
            Fixtures.uploadSession(
                "u1",
                sizeBytes = payload.size.toLong(),
                segmentTotal = 1,
            ),
        )
        h.segments.writeSegment(
            UploadSegment("u1", 0, 0, payload.size.toLong(), "ignored"),
            ByteArrayInputStream(payload),
        )

        val outcome = h.service.finalize(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "u1",
            actualTotalChecksum = "totalhash",
            now = Fixtures.NOW,
        )

        outcome.shouldBeInstanceOf<FinalizeOutcome.Applied>()
        outcome.session.state shouldBe UploadSessionState.COMPLETED
        h.segments.listSegments("u1") shouldBe emptyList()
    }

    test("finalize ValidationFailed: gaps in segments — no state change, spool kept") {
        val h = harness()
        h.sessions.save(Fixtures.uploadSession("u1", segmentTotal = 3))
        seedSegment(h.segments, "u1", "abc".toByteArray())

        val outcome = h.service.finalize(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "u1",
            actualTotalChecksum = "totalhash",
            now = Fixtures.NOW,
        )

        outcome.shouldBeInstanceOf<FinalizeOutcome.ValidationFailed>()
        outcome.reason.shouldBeInstanceOf<UploadSessionTransitions.FinalizeValidation.GapsInSegments>()
        h.sessions.findById(Fixtures.tenant("acme"), "u1")?.state shouldBe UploadSessionState.ACTIVE
        h.segments.listSegments("u1").size shouldBe 1
    }

    test("finalize ValidationFailed: total checksum mismatch") {
        val h = harness()
        val payload = "hello".toByteArray()
        h.sessions.save(
            Fixtures.uploadSession(
                "u1",
                sizeBytes = payload.size.toLong(),
                segmentTotal = 1,
            ),
        )
        h.segments.writeSegment(
            UploadSegment("u1", 0, 0, payload.size.toLong(), "ignored"),
            ByteArrayInputStream(payload),
        )

        val outcome = h.service.finalize(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "u1",
            actualTotalChecksum = "wrong",
            now = Fixtures.NOW,
        )

        outcome.shouldBeInstanceOf<FinalizeOutcome.ValidationFailed>()
        outcome.reason
            .shouldBeInstanceOf<UploadSessionTransitions.FinalizeValidation.TotalChecksumMismatch>()
        h.sessions.findById(Fixtures.tenant("acme"), "u1")?.state shouldBe UploadSessionState.ACTIVE
    }

    test("finalize NotFound when session does not exist") {
        val h = harness()

        val outcome = h.service.finalize(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "ghost",
            actualTotalChecksum = "totalhash",
            now = Fixtures.NOW,
        )

        outcome shouldBe FinalizeOutcome.NotFound
    }

    test("finalize on already-terminal session is ValidationFailed (WrongState)") {
        val h = harness()
        h.sessions.save(Fixtures.uploadSession("u1", state = UploadSessionState.ABORTED))
        seedSegment(h.segments, "u1", "abc".toByteArray())

        val outcome = h.service.finalize(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "u1",
            actualTotalChecksum = "totalhash",
            now = Fixtures.NOW,
        )

        outcome.shouldBeInstanceOf<FinalizeOutcome.ValidationFailed>()
        outcome.reason.shouldBeInstanceOf<UploadSessionTransitions.FinalizeValidation.WrongState>()
        h.segments.listSegments("u1").size shouldBe 1
    }
})
