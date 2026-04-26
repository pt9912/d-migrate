package dev.dmigrate.server.application.approval

import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import dev.dmigrate.server.core.approval.ApprovalGrant
import dev.dmigrate.server.ports.contract.Fixtures
import java.time.Instant

internal object ApprovalFixtures {

    val NOW: Instant = Fixtures.NOW
    val FUTURE: Instant = NOW.plusSeconds(300)
    val PAST: Instant = NOW.minusSeconds(1)

    val TOKEN_FP: String = ApprovalTokenFingerprint.compute("token-fixture-1")
    val PAYLOAD_FP: String = ApprovalTokenFingerprint.compute("payload-fixture-1")
    val ISSUER_FP: String = ApprovalTokenFingerprint.compute("issuer-fixture-trusted")

    fun grant(
        tokenFingerprint: String = TOKEN_FP,
        tenant: String = "acme",
        caller: String = "alice",
        tool: String = "start.export.data",
        correlationKind: ApprovalCorrelationKind = ApprovalCorrelationKind.IDEMPOTENCY_KEY,
        correlationKey: String = "idem-1",
        payloadFingerprint: String = PAYLOAD_FP,
        issuerFingerprint: String = ISSUER_FP,
        scopes: Set<String> = setOf("data.export"),
        expiresAt: Instant = FUTURE,
    ) = ApprovalGrant(
        approvalRequestId = "req-1",
        correlationKind = correlationKind,
        correlationKey = correlationKey,
        approvalTokenFingerprint = tokenFingerprint,
        toolName = tool,
        tenantId = Fixtures.tenant(tenant),
        callerId = Fixtures.principal(caller),
        payloadFingerprint = payloadFingerprint,
        issuerFingerprint = issuerFingerprint,
        issuedScopes = scopes,
        grantSource = "local",
        expiresAt = expiresAt,
    )

    fun attempt(
        tokenFingerprint: String = TOKEN_FP,
        tenant: String = "acme",
        caller: String = "alice",
        tool: String = "start.export.data",
        correlationKind: ApprovalCorrelationKind = ApprovalCorrelationKind.IDEMPOTENCY_KEY,
        correlationKey: String = "idem-1",
        payloadFingerprint: String = PAYLOAD_FP,
        requiredScopes: Set<String> = setOf("data.export"),
    ) = ApprovalAttempt(
        tokenFingerprint = tokenFingerprint,
        tenantId = Fixtures.tenant(tenant),
        callerId = Fixtures.principal(caller),
        toolName = tool,
        correlationKind = correlationKind,
        correlationKey = correlationKey,
        payloadFingerprint = payloadFingerprint,
        requiredScopes = requiredScopes,
    )
}
