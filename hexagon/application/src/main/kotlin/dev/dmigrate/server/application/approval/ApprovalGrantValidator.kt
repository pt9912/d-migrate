package dev.dmigrate.server.application.approval

import dev.dmigrate.server.core.approval.ApprovalGrant
import java.time.Instant

/**
 * Pure validator. Takes a stored [ApprovalGrant] (already located by
 * the service via tenant + tokenFingerprint), the [ApprovalAttempt]
 * that the caller wants to validate against, and a `now` instant.
 * Returns [ApprovalGrantValidation] in deterministic order — see
 * `ImpPlan-0.9.6-A.md` §6.5.
 */
class ApprovalGrantValidator(
    private val issuerCheck: IssuerCheck = IssuerCheck.Off,
) {

    fun validate(
        grant: ApprovalGrant,
        attempt: ApprovalAttempt,
        now: Instant,
    ): ApprovalGrantValidation {
        if (!grant.expiresAt.isAfter(now)) return ApprovalGrantValidation.Invalid.Expired
        if (grant.tenantId != attempt.tenantId) return ApprovalGrantValidation.Invalid.TenantMismatch
        if (grant.callerId != attempt.callerId) return ApprovalGrantValidation.Invalid.CallerMismatch
        if (grant.toolName != attempt.toolName) return ApprovalGrantValidation.Invalid.ToolMismatch
        if (grant.correlationKind != attempt.correlationKind ||
            grant.correlationKey != attempt.correlationKey
        ) {
            return ApprovalGrantValidation.Invalid.CorrelationMismatch
        }
        if (grant.payloadFingerprint != attempt.payloadFingerprint) {
            return ApprovalGrantValidation.Invalid.PayloadMismatch
        }
        if (!grant.issuedScopes.containsAll(attempt.requiredScopes)) {
            val missing = attempt.requiredScopes - grant.issuedScopes
            return ApprovalGrantValidation.Invalid.ScopeMismatch(missing)
        }
        if (issuerCheck is IssuerCheck.AllowList &&
            grant.issuerFingerprint !in issuerCheck.trusted
        ) {
            return ApprovalGrantValidation.Invalid.IssuerMismatch
        }
        return ApprovalGrantValidation.Valid(grant)
    }
}
