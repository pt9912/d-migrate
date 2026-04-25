package dev.dmigrate.server.core.approval

import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import java.time.Instant

enum class ApprovalCorrelationKind { IDEMPOTENCY_KEY, APPROVAL_KEY }

data class ApprovalGrant(
    val approvalRequestId: String,
    val correlationKind: ApprovalCorrelationKind,
    val correlationKey: String,
    val approvalTokenFingerprint: String,
    val toolName: String,
    val tenantId: TenantId,
    val callerId: PrincipalId,
    val payloadFingerprint: String,
    val issuerFingerprint: String,
    val issuedScopes: Set<String>,
    val grantSource: String,
    val expiresAt: Instant,
)
