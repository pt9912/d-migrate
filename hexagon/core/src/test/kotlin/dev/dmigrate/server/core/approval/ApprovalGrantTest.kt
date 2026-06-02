package dev.dmigrate.server.core.approval

import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

private val FIXED_EXPIRY: Instant = Instant.parse("2026-05-10T12:00:00Z")

private fun grant(
    approvalRequestId: String = "ar-1",
    correlationKind: ApprovalCorrelationKind = ApprovalCorrelationKind.IDEMPOTENCY_KEY,
    correlationKey: String = "ck-1",
    approvalTokenFingerprint: String = "atfp-1",
    toolName: String = "ai.test",
    tenantId: TenantId = TenantId("t-1"),
    callerId: PrincipalId = PrincipalId("p-1"),
    payloadFingerprint: String = "pfp-1",
    issuerFingerprint: String = "ifp-1",
    issuedScopes: Set<String> = setOf("scope.a", "scope.b"),
    grantSource: String = "operator",
    expiresAt: Instant = FIXED_EXPIRY,
) = ApprovalGrant(
    approvalRequestId, correlationKind, correlationKey, approvalTokenFingerprint,
    toolName, tenantId, callerId, payloadFingerprint, issuerFingerprint, issuedScopes,
    grantSource, expiresAt,
)

/**
 * Construction-coverage for [ApprovalGrant] and the
 * [ApprovalCorrelationKind] enum. Both are pure data carriers (no
 * `init`-block validation) so the test exercises the auto-generated
 * `equals`/`hashCode`/`copy`/`toString` methods that previously sat
 * at 0% line coverage when the kover excludes silenced them.
 */
class ApprovalGrantTest : FunSpec({

    test("constructs with both correlation kinds") {
        grant(correlationKind = ApprovalCorrelationKind.IDEMPOTENCY_KEY)
        grant(correlationKind = ApprovalCorrelationKind.APPROVAL_KEY)
    }

    test("equals + hashCode follow data-class semantics") {
        grant() shouldBe grant()
        grant().hashCode() shouldBe grant().hashCode()
    }

    test("copy preserves untouched fields and updates the named one") {
        val original = grant()
        val copied = original.copy(toolName = "ai.other")
        copied.toolName shouldBe "ai.other"
        copied.tenantId shouldBe original.tenantId
        copied.expiresAt shouldBe original.expiresAt
    }

    test("ApprovalCorrelationKind enum exposes both values") {
        ApprovalCorrelationKind.entries.toSet() shouldBe setOf(
            ApprovalCorrelationKind.IDEMPOTENCY_KEY,
            ApprovalCorrelationKind.APPROVAL_KEY,
        )
    }
})
