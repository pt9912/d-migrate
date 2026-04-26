package dev.dmigrate.server.application.approval

import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId

/**
 * Snapshot of everything an approval-grant validation must compare
 * against a stored [dev.dmigrate.server.core.approval.ApprovalGrant].
 * Bundles the parameters into one DTO so the
 * [ApprovalGrantService.validate] surface stays small.
 */
data class ApprovalAttempt(
    val tokenFingerprint: String,
    val tenantId: TenantId,
    val callerId: PrincipalId,
    val toolName: String,
    val correlationKind: ApprovalCorrelationKind,
    val correlationKey: String,
    val payloadFingerprint: String,
    val requiredScopes: Set<String>,
)
