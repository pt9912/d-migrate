package dev.dmigrate.server.application.policy

import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId

/**
 * Eine deklarative Regel der [ConfiguredPolicyService]. Match-Felder
 * (`null` = Wildcard) plus [effect]. Erste passende Regel gewinnt
 * (Plan §7.4 — deterministische Allowlist).
 */
data class PolicyRule(
    val tenantId: TenantId? = null,
    val toolName: String? = null,
    val callerId: PrincipalId? = null,
    val effect: PolicyEffect,
) {
    fun matches(attempt: PolicyAttempt): Boolean {
        if (tenantId != null && tenantId != attempt.tenantId) return false
        if (toolName != null && toolName != attempt.toolName) return false
        if (callerId != null && callerId != attempt.callerId) return false
        return true
    }
}

/**
 * Effekt einer [PolicyRule]. Drei Branches symmetrisch zu
 * [dev.dmigrate.server.core.policy.PolicyDecision], ohne `approvalRequestId`
 * — den vergibt der Service erst beim Match.
 */
sealed interface PolicyEffect {

    data object Allow : PolicyEffect

    data class Challenge(
        val requiredScopes: Set<String>,
        val reasons: List<String> = emptyList(),
    ) : PolicyEffect

    data class Deny(val reasonCode: String) : PolicyEffect
}
