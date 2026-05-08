package dev.dmigrate.server.application.approval

import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ApprovalGrantServiceTest : FunSpec({

    fun newService(
        store: InMemoryApprovalGrantStore = InMemoryApprovalGrantStore(),
        issuerCheck: IssuerCheck = IssuerCheck.Off,
    ) = DefaultApprovalGrantService(store, ApprovalGrantValidator(issuerCheck)) to store

    test("Unknown when no grant exists for the token fingerprint") {
        val (service, _) = newService()
        val result = service.validate(ApprovalFixtures.attempt(), ApprovalFixtures.NOW)
        result shouldBe ApprovalGrantValidation.Invalid.Unknown
    }

    test("Unknown when grant exists for a different tenant") {
        val (service, store) = newService()
        store.save(ApprovalFixtures.grant(tenant = "acme"))
        val result = service.validate(
            ApprovalFixtures.attempt(tenant = "initech"),
            ApprovalFixtures.NOW,
        )
        result shouldBe ApprovalGrantValidation.Invalid.Unknown
    }

    test("happy path returns Valid with the stored grant") {
        val (service, store) = newService()
        val grant = store.save(ApprovalFixtures.grant())
        val result = service.validate(ApprovalFixtures.attempt(), ApprovalFixtures.NOW)
        result.shouldBeInstanceOf<ApprovalGrantValidation.Valid>()
        result.grant shouldBe grant
    }

    test("identical reuse stays Valid (idempotent)") {
        val (service, store) = newService()
        store.save(ApprovalFixtures.grant())
        repeat(3) {
            service.validate(ApprovalFixtures.attempt(), ApprovalFixtures.NOW)
                .shouldBeInstanceOf<ApprovalGrantValidation.Valid>()
        }
    }

    test("payload mismatch is propagated from the validator") {
        val (service, store) = newService()
        store.save(ApprovalFixtures.grant(payloadFingerprint = "fp-a"))
        val result = service.validate(
            ApprovalFixtures.attempt(payloadFingerprint = "fp-b"),
            ApprovalFixtures.NOW,
        )
        result shouldBe ApprovalGrantValidation.Invalid.PayloadMismatch
    }

    test("IssuerCheck.Off accepts any issuer") {
        val (service, store) = newService(issuerCheck = IssuerCheck.Off)
        store.save(ApprovalFixtures.grant(issuerFingerprint = "anything"))
        service.validate(ApprovalFixtures.attempt(), ApprovalFixtures.NOW)
            .shouldBeInstanceOf<ApprovalGrantValidation.Valid>()
    }

    test("IssuerCheck.AllowList rejects unknown issuer") {
        val (service, store) = newService(
            issuerCheck = IssuerCheck.AllowList(setOf("trusted-1")),
        )
        store.save(ApprovalFixtures.grant(issuerFingerprint = "untrusted"))
        service.validate(ApprovalFixtures.attempt(), ApprovalFixtures.NOW) shouldBe
            ApprovalGrantValidation.Invalid.IssuerMismatch
    }

    test("expired grant is rejected") {
        val (service, store) = newService()
        store.save(ApprovalFixtures.grant(expiresAt = ApprovalFixtures.PAST))
        service.validate(ApprovalFixtures.attempt(), ApprovalFixtures.NOW) shouldBe
            ApprovalGrantValidation.Invalid.Expired
    }
})
