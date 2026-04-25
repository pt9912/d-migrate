package dev.dmigrate.server.ports.contract

import dev.dmigrate.server.core.idempotency.SyncEffectReserveOutcome
import dev.dmigrate.server.core.idempotency.SyncEffectScope
import dev.dmigrate.server.ports.SyncEffectIdempotencyStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

abstract class SyncEffectIdempotencyStoreContractTests(
    factory: () -> SyncEffectIdempotencyStore,
) : FunSpec({

    fun scope(approvalKey: String = "ak1") = SyncEffectScope(
        tenantId = Fixtures.tenant("acme"),
        callerId = Fixtures.principal("alice"),
        toolName = "data_import_run",
        approvalKey = approvalKey,
    )

    test("first reserve returns Reserved") {
        val store = factory()
        val outcome = store.reserve(scope(), "fp", Fixtures.NOW)
        outcome.shouldBeInstanceOf<SyncEffectReserveOutcome.Reserved>()
    }

    test("commit makes subsequent identical reserve return Existing with resultRef") {
        val store = factory()
        val s = scope()
        store.reserve(s, "fp", Fixtures.NOW)
        store.commit(s, "result_ref", Fixtures.NOW.plusSeconds(1)) shouldBe true
        val second = store.reserve(s, "fp", Fixtures.NOW.plusSeconds(2))
        second.shouldBeInstanceOf<SyncEffectReserveOutcome.Existing>()
        second.resultRef shouldBe "result_ref"
    }

    test("different fingerprint at same approvalKey scope yields Conflict") {
        val store = factory()
        val s = scope()
        store.reserve(s, "fp", Fixtures.NOW)
        val conflict = store.reserve(s, "other", Fixtures.NOW.plusSeconds(1))
        conflict.shouldBeInstanceOf<SyncEffectReserveOutcome.Conflict>()
    }
})
