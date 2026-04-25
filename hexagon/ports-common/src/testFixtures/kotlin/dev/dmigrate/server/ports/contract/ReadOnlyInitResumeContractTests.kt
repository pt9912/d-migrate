package dev.dmigrate.server.ports.contract

import dev.dmigrate.server.core.idempotency.InitResumeOutcome
import dev.dmigrate.server.core.idempotency.InitResumeScope
import dev.dmigrate.server.ports.IdempotencyStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

abstract class ReadOnlyInitResumeContractTests(factory: () -> IdempotencyStore) : FunSpec({

    fun scope(clientRequestId: String = "req-1") = InitResumeScope(
        tenantId = Fixtures.tenant("acme"),
        callerId = Fixtures.principal("alice"),
        toolName = "artifact_upload_init",
        clientRequestId = clientRequestId,
    )

    test("first init reservation returns Reserved with sessionId") {
        val store = factory()
        val outcome = store.reserveInitResume(scope(), "fp", "session_1", Fixtures.NOW)
        outcome.shouldBeInstanceOf<InitResumeOutcome.Reserved>()
        outcome.sessionId shouldBe "session_1"
        outcome.expiresAt.isAfter(Fixtures.NOW) shouldBe true
    }

    test("identical re-init returns Existing with same sessionId") {
        val store = factory()
        val s = scope()
        store.reserveInitResume(s, "fp", "session_1", Fixtures.NOW)
        val second = store.reserveInitResume(s, "fp", "session_2", Fixtures.NOW.plusSeconds(1))
        second.shouldBeInstanceOf<InitResumeOutcome.Existing>()
        second.sessionId shouldBe "session_1"
    }

    test("divergent fingerprint at same scope returns Conflict") {
        val store = factory()
        val s = scope()
        store.reserveInitResume(s, "fp", "session_1", Fixtures.NOW)
        val conflict = store.reserveInitResume(s, "other", "session_2", Fixtures.NOW.plusSeconds(1))
        conflict.shouldBeInstanceOf<InitResumeOutcome.Conflict>()
        conflict.existingFingerprint shouldBe "fp"
    }

    test("different clientRequestId scopes are independent") {
        val store = factory()
        val a = scope("req-a")
        val b = scope("req-b")
        store.reserveInitResume(a, "fp", "session_a", Fixtures.NOW)
        val outcome = store.reserveInitResume(b, "fp", "session_b", Fixtures.NOW)
        outcome.shouldBeInstanceOf<InitResumeOutcome.Reserved>()
        outcome.sessionId shouldBe "session_b"
    }
})
