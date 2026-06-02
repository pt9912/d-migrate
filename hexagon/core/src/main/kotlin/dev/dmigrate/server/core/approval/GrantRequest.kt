package dev.dmigrate.server.core.approval

import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import java.time.Instant

/**
 * LF-012 / LN-011 / LN-017 / LN-027 *
 * Die Felder spiegeln die Bindungen aus LF-012 / LN-011 / LN-017 / LN-027: jeder ausgestellte
 * [ApprovalGrant] erbt sie 1:1, damit der spaetere
 * [dev.dmigrate.server.application.approval.ApprovalGrantValidator]-Check
 * deterministisch ist. [approvalRequestId] kommt aus
 * [dev.dmigrate.server.core.policy.PolicyDecision.RequiresApproval] und
 * bindet den Grant an die aktuelle Challenge.
 */
data class GrantRequest(
    val tenantId: TenantId,
    val callerId: PrincipalId,
    val toolName: String,
    val approvalRequestId: String,
    val correlationKind: ApprovalCorrelationKind,
    val correlationKey: String,
    val payloadFingerprint: String,
    val requiredScopes: Set<String>,
    val expiresAt: Instant,
)
