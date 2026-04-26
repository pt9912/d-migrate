package dev.dmigrate.server.application.approval

import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ApprovalGrantValidatorTest : FunSpec({

    val validator = ApprovalGrantValidator()

    test("happy path returns Valid") {
        val grant = ApprovalFixtures.grant()
        val attempt = ApprovalFixtures.attempt()
        val result = validator.validate(grant, attempt, ApprovalFixtures.NOW)
        result.shouldBeInstanceOf<ApprovalGrantValidation.Valid>()
        result.grant shouldBe grant
    }

    test("expired grant returns Expired") {
        val grant = ApprovalFixtures.grant(expiresAt = ApprovalFixtures.PAST)
        val result = validator.validate(grant, ApprovalFixtures.attempt(), ApprovalFixtures.NOW)
        result shouldBe ApprovalGrantValidation.Invalid.Expired
    }

    test("grant expiring exactly at now is treated as expired") {
        val grant = ApprovalFixtures.grant(expiresAt = ApprovalFixtures.NOW)
        val result = validator.validate(grant, ApprovalFixtures.attempt(), ApprovalFixtures.NOW)
        result shouldBe ApprovalGrantValidation.Invalid.Expired
    }

    test("tenant mismatch is rejected") {
        val result = validator.validate(
            grant = ApprovalFixtures.grant(tenant = "acme"),
            attempt = ApprovalFixtures.attempt(tenant = "initech"),
            now = ApprovalFixtures.NOW,
        )
        result shouldBe ApprovalGrantValidation.Invalid.TenantMismatch
    }

    test("caller mismatch is rejected") {
        val result = validator.validate(
            grant = ApprovalFixtures.grant(caller = "alice"),
            attempt = ApprovalFixtures.attempt(caller = "bob"),
            now = ApprovalFixtures.NOW,
        )
        result shouldBe ApprovalGrantValidation.Invalid.CallerMismatch
    }

    test("tool mismatch is rejected") {
        val result = validator.validate(
            grant = ApprovalFixtures.grant(tool = "start.export.data"),
            attempt = ApprovalFixtures.attempt(tool = "start.import.data"),
            now = ApprovalFixtures.NOW,
        )
        result shouldBe ApprovalGrantValidation.Invalid.ToolMismatch
    }

    test("correlation kind mismatch is rejected") {
        val result = validator.validate(
            grant = ApprovalFixtures.grant(
                correlationKind = ApprovalCorrelationKind.IDEMPOTENCY_KEY,
            ),
            attempt = ApprovalFixtures.attempt(
                correlationKind = ApprovalCorrelationKind.APPROVAL_KEY,
            ),
            now = ApprovalFixtures.NOW,
        )
        result shouldBe ApprovalGrantValidation.Invalid.CorrelationMismatch
    }

    test("correlation key mismatch is rejected") {
        val result = validator.validate(
            grant = ApprovalFixtures.grant(correlationKey = "k1"),
            attempt = ApprovalFixtures.attempt(correlationKey = "k2"),
            now = ApprovalFixtures.NOW,
        )
        result shouldBe ApprovalGrantValidation.Invalid.CorrelationMismatch
    }

    test("payload fingerprint mismatch is rejected") {
        val result = validator.validate(
            grant = ApprovalFixtures.grant(payloadFingerprint = "fp-a"),
            attempt = ApprovalFixtures.attempt(payloadFingerprint = "fp-b"),
            now = ApprovalFixtures.NOW,
        )
        result shouldBe ApprovalGrantValidation.Invalid.PayloadMismatch
    }

    test("scope mismatch reports the missing scopes") {
        val result = validator.validate(
            grant = ApprovalFixtures.grant(scopes = setOf("data.export")),
            attempt = ApprovalFixtures.attempt(
                requiredScopes = setOf("data.export", "data.import", "schema.write"),
            ),
            now = ApprovalFixtures.NOW,
        )
        result.shouldBeInstanceOf<ApprovalGrantValidation.Invalid.ScopeMismatch>()
        result.missing shouldBe setOf("data.import", "schema.write")
    }

    test("empty required scopes always satisfies the scope check") {
        val result = validator.validate(
            grant = ApprovalFixtures.grant(scopes = emptySet()),
            attempt = ApprovalFixtures.attempt(requiredScopes = emptySet()),
            now = ApprovalFixtures.NOW,
        )
        result.shouldBeInstanceOf<ApprovalGrantValidation.Valid>()
    }

    test("grant with extra scopes still validates") {
        val result = validator.validate(
            grant = ApprovalFixtures.grant(scopes = setOf("a", "b", "c")),
            attempt = ApprovalFixtures.attempt(requiredScopes = setOf("a", "b")),
            now = ApprovalFixtures.NOW,
        )
        result.shouldBeInstanceOf<ApprovalGrantValidation.Valid>()
    }

    test("IssuerCheck.Off accepts any issuer") {
        val v = ApprovalGrantValidator(IssuerCheck.Off)
        val result = v.validate(
            grant = ApprovalFixtures.grant(issuerFingerprint = "anything"),
            attempt = ApprovalFixtures.attempt(),
            now = ApprovalFixtures.NOW,
        )
        result.shouldBeInstanceOf<ApprovalGrantValidation.Valid>()
    }

    test("IssuerCheck.AllowList rejects unknown issuer") {
        val v = ApprovalGrantValidator(IssuerCheck.AllowList(setOf("trusted-1")))
        val result = v.validate(
            grant = ApprovalFixtures.grant(issuerFingerprint = "trusted-2"),
            attempt = ApprovalFixtures.attempt(),
            now = ApprovalFixtures.NOW,
        )
        result shouldBe ApprovalGrantValidation.Invalid.IssuerMismatch
    }

    test("IssuerCheck.AllowList(emptySet) is deny-all") {
        val v = ApprovalGrantValidator(IssuerCheck.AllowList(emptySet()))
        val result = v.validate(
            grant = ApprovalFixtures.grant(issuerFingerprint = "anything"),
            attempt = ApprovalFixtures.attempt(),
            now = ApprovalFixtures.NOW,
        )
        result shouldBe ApprovalGrantValidation.Invalid.IssuerMismatch
    }

    test("approval-key correlation works for sync tools") {
        val grant = ApprovalFixtures.grant(
            correlationKind = ApprovalCorrelationKind.APPROVAL_KEY,
            correlationKey = "appr-1",
        )
        val attempt = ApprovalFixtures.attempt(
            correlationKind = ApprovalCorrelationKind.APPROVAL_KEY,
            correlationKey = "appr-1",
        )
        validator.validate(grant, attempt, ApprovalFixtures.NOW)
            .shouldBeInstanceOf<ApprovalGrantValidation.Valid>()
    }
})
