package dev.dmigrate.server.application.policy

import dev.dmigrate.server.core.policy.PolicyDecision
import java.util.UUID

/**
 * LF-012 / LN-011 / LN-017 / LN-027 *
 * Wertet [rules] in Reihenfolge aus; erste passende Regel gewinnt. Wenn
 * keine Regel matcht, faellt der Service auf [defaultEffect] zurueck —
 * Default ist `Deny("policy:no-rule")`, also fail-closed.
 *
 * Bei [PolicyEffect.Challenge] generiert der Service eine frische
 * [PolicyDecision.RequiresApproval.approvalRequestId] ueber
 * [approvalRequestIdFactory]. Dieser Wert bindet den spaeter ausgestellten
 * Grant: ein Grant fuer eine alte/erneuerte Challenge ist ungueltig
 * (LF-012 / LN-011 / LN-017 / LN-027). Die Challenge replayt deterministisch nur dann, wenn der
 * Caller (Tool-Handler aus LF-012 / LN-011 / LN-017 / LN-027) sie aus dem Idempotency-Store
 * `AWAITING_APPROVAL`-Outcome rekonstruiert; der Service selbst hat keinen
 * Cross-Request-State.
 *
 * Beispiel:
 *
 *     ConfiguredPolicyService(
 *         rules = listOf(
 *             PolicyRule(toolName = "schema_reverse_start", effect = PolicyEffect.Allow),
 *             PolicyRule(toolName = "data_profile_start",
 *                 effect = PolicyEffect.Challenge(setOf("data.read"))),
 *         ),
 *         defaultEffect = PolicyEffect.Deny("policy:no-rule"),
 *     )
 */
class ConfiguredPolicyService(
    private val rules: List<PolicyRule>,
    private val defaultEffect: PolicyEffect = PolicyEffect.Deny("policy:no-rule"),
    private val approvalRequestIdFactory: () -> String = { "appr_${UUID.randomUUID()}" },
) : PolicyService {

    override fun decide(attempt: PolicyAttempt): PolicyDecision {
        val effect = rules.firstOrNull { it.matches(attempt) }?.effect ?: defaultEffect
        return toDecision(effect, attempt)
    }

    private fun toDecision(effect: PolicyEffect, attempt: PolicyAttempt): PolicyDecision = when (effect) {
        is PolicyEffect.Allow -> PolicyDecision.Allowed
        is PolicyEffect.Challenge -> PolicyDecision.RequiresApproval(
            approvalRequestId = approvalRequestIdFactory(),
            correlationKind = attempt.correlationKind,
            correlationKey = attempt.correlationKey,
            requiredScopes = effect.requiredScopes,
            reasons = effect.reasons,
        )
        is PolicyEffect.Deny -> PolicyDecision.Denied(effect.reasonCode)
    }
}
