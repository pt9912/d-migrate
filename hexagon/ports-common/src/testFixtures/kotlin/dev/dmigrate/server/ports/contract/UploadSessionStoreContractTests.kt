package dev.dmigrate.server.ports.contract

import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.TransitionOutcome
import dev.dmigrate.server.ports.UploadSessionStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

abstract class UploadSessionStoreContractTests(factory: () -> UploadSessionStore) : FunSpec({

    test("save and findById round-trip within tenant") {
        val store = factory()
        val session = Fixtures.uploadSession("u1")
        store.save(session)
        store.findById(Fixtures.tenant("acme"), "u1") shouldBe session
    }

    test("list filters by state") {
        val store = factory()
        store.save(Fixtures.uploadSession("active1", state = UploadSessionState.ACTIVE))
        store.save(Fixtures.uploadSession("done1", state = UploadSessionState.COMPLETED))
        val page = store.list(
            tenantId = Fixtures.tenant("acme"),
            page = PageRequest(pageSize = 10),
            stateFilter = UploadSessionState.ACTIVE,
        )
        page.items.map { it.uploadSessionId } shouldBe listOf("active1")
    }

    test("transition ACTIVE to COMPLETED applies and updates state") {
        val store = factory()
        store.save(Fixtures.uploadSession("u1"))
        val result = store.transition(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "u1",
            newState = UploadSessionState.COMPLETED,
            now = Fixtures.NOW.plusSeconds(60),
        )
        result.shouldBeInstanceOf<TransitionOutcome.Applied>()
        result.session.state shouldBe UploadSessionState.COMPLETED
        store.findById(Fixtures.tenant("acme"), "u1")?.state shouldBe UploadSessionState.COMPLETED
    }

    test("transition rejects illegal target") {
        val store = factory()
        store.save(Fixtures.uploadSession("u1", state = UploadSessionState.COMPLETED))
        val result = store.transition(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "u1",
            newState = UploadSessionState.ACTIVE,
            now = Fixtures.NOW,
        )
        result.shouldBeInstanceOf<TransitionOutcome.IllegalTransition>()
        result.from shouldBe UploadSessionState.COMPLETED
        result.to shouldBe UploadSessionState.ACTIVE
    }

    test("transition returns NotFound for unknown session") {
        val store = factory()
        store.transition(
            tenantId = Fixtures.tenant("acme"),
            uploadSessionId = "missing",
            newState = UploadSessionState.COMPLETED,
            now = Fixtures.NOW,
        ) shouldBe TransitionOutcome.NotFound
    }

    test("expireDue marks idle/lease-expired ACTIVE sessions as EXPIRED") {
        val store = factory()
        store.save(
            Fixtures.uploadSession(
                "stale",
                idleTimeoutAt = Fixtures.NOW.minusSeconds(10),
                absoluteLeaseExpiresAt = Fixtures.NOW.plusSeconds(10_000),
            ),
        )
        store.save(Fixtures.uploadSession("fresh"))
        val expired = store.expireDue(Fixtures.NOW)
        expired.map { it.uploadSessionId } shouldBe listOf("stale")
        expired.single().state shouldBe UploadSessionState.EXPIRED
        store.findById(Fixtures.tenant("acme"), "stale")?.state shouldBe UploadSessionState.EXPIRED
        store.findById(Fixtures.tenant("acme"), "fresh")?.state shouldBe UploadSessionState.ACTIVE
    }
})
