package dev.dmigrate.server.application.approval

import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Pins the deterministic Reject-Reason order from `ImpPlan-0.9.6-A.md`
 * §6.5: every test feeds a grant that has *several* mismatches at once
 * and asserts the earlier-listed reason wins.
 */
class ApprovalGrantValidationOrderTest : FunSpec({

    val validator = ApprovalGrantValidator(IssuerCheck.AllowList(setOf("trusted")))

    test("Expired beats Tenant when both are wrong") {
        val grant = ApprovalFixtures.grant(
            tenant = "acme",
            expiresAt = ApprovalFixtures.PAST,
        )
        val attempt = ApprovalFixtures.attempt(tenant = "initech")
        validator.validate(grant, attempt, ApprovalFixtures.NOW) shouldBe
            ApprovalGrantValidation.Invalid.Expired
    }

    test("Tenant beats Caller") {
        val grant = ApprovalFixtures.grant(tenant = "acme", caller = "alice")
        val attempt = ApprovalFixtures.attempt(tenant = "initech", caller = "bob")
        validator.validate(grant, attempt, ApprovalFixtures.NOW) shouldBe
            ApprovalGrantValidation.Invalid.TenantMismatch
    }

    test("Caller beats Tool") {
        val grant = ApprovalFixtures.grant(caller = "alice", tool = "x")
        val attempt = ApprovalFixtures.attempt(caller = "bob", tool = "y")
        validator.validate(grant, attempt, ApprovalFixtures.NOW) shouldBe
            ApprovalGrantValidation.Invalid.CallerMismatch
    }

    test("Tool beats Correlation") {
        val grant = ApprovalFixtures.grant(tool = "x", correlationKey = "k1")
        val attempt = ApprovalFixtures.attempt(tool = "y", correlationKey = "k2")
        validator.validate(grant, attempt, ApprovalFixtures.NOW) shouldBe
            ApprovalGrantValidation.Invalid.ToolMismatch
    }

    test("Correlation beats Payload") {
        val grant = ApprovalFixtures.grant(
            correlationKind = ApprovalCorrelationKind.IDEMPOTENCY_KEY,
            payloadFingerprint = "fp-a",
        )
        val attempt = ApprovalFixtures.attempt(
            correlationKind = ApprovalCorrelationKind.APPROVAL_KEY,
            payloadFingerprint = "fp-b",
        )
        validator.validate(grant, attempt, ApprovalFixtures.NOW) shouldBe
            ApprovalGrantValidation.Invalid.CorrelationMismatch
    }

    test("Payload beats Scope") {
        val grant = ApprovalFixtures.grant(
            payloadFingerprint = "fp-a",
            scopes = setOf("a"),
        )
        val attempt = ApprovalFixtures.attempt(
            payloadFingerprint = "fp-b",
            requiredScopes = setOf("a", "b"),
        )
        validator.validate(grant, attempt, ApprovalFixtures.NOW) shouldBe
            ApprovalGrantValidation.Invalid.PayloadMismatch
    }

    test("Scope beats Issuer") {
        val grant = ApprovalFixtures.grant(
            scopes = setOf("a"),
            issuerFingerprint = "untrusted",
        )
        val attempt = ApprovalFixtures.attempt(requiredScopes = setOf("a", "b"))
        val result = validator.validate(grant, attempt, ApprovalFixtures.NOW)
        result.shouldBeInstanceOf<ApprovalGrantValidation.Invalid.ScopeMismatch>()
        result.missing shouldBe setOf("b")
    }
})
