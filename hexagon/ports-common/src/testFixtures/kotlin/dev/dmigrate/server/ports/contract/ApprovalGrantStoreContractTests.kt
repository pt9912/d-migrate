package dev.dmigrate.server.ports.contract

import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import dev.dmigrate.server.core.approval.ApprovalGrant
import dev.dmigrate.server.ports.ApprovalGrantStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

abstract class ApprovalGrantStoreContractTests(factory: () -> ApprovalGrantStore) : FunSpec({

    fun grant(
        token: String = "tok",
        tenant: String = "acme",
        expiresAt: Instant = Fixtures.EXPIRY,
    ) = ApprovalGrant(
        approvalRequestId = "req_$token",
        correlationKind = ApprovalCorrelationKind.IDEMPOTENCY_KEY,
        correlationKey = "ck_$token",
        approvalTokenFingerprint = token,
        toolName = "data_export_start",
        tenantId = Fixtures.tenant(tenant),
        callerId = Fixtures.principal("alice"),
        payloadFingerprint = "pf",
        issuerFingerprint = "if",
        issuedScopes = setOf("data.read"),
        grantSource = "local",
        expiresAt = expiresAt,
    )

    test("save and findByTokenFingerprint round-trip") {
        val store = factory()
        val g = grant()
        store.save(g)
        store.findByTokenFingerprint(Fixtures.tenant("acme"), "tok") shouldBe g
    }

    test("findByTokenFingerprint is tenant-scoped") {
        val store = factory()
        store.save(grant())
        store.findByTokenFingerprint(Fixtures.tenant("umbrella"), "tok") shouldBe null
    }

    test("deleteExpired removes grants past their expiresAt") {
        val store = factory()
        store.save(grant("keep", expiresAt = Fixtures.NOW.plusSeconds(10_000)))
        store.save(grant("drop", expiresAt = Fixtures.NOW.minusSeconds(10)))
        store.deleteExpired(Fixtures.NOW) shouldBe 1
        store.findByTokenFingerprint(Fixtures.tenant("acme"), "drop") shouldBe null
    }
})
