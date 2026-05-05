package dev.dmigrate.server.core.policy

import dev.dmigrate.server.core.approval.ApprovalCorrelationKind

/**
 * Phase E §5.4: Antwort des Policy-Service auf einen Start-Versuch.
 *
 * Drei Branches gemaess Plan §5.4 / §7.4:
 *
 * - [Allowed] — direkter Start; Caller ueberspringt Approval-Flow und faehrt
 *   sofort mit der Idempotency-Reservierung fort. Erzeugt KEINEN Grant.
 * - [RequiresApproval] — Caller muss Approval-Token beibringen. Der Server
 *   prueft den Grant gegen die hier vergebene `approvalRequestId`
 *   (Validator-Check siehe AP E.4 (1/3) `ApprovalAttempt.approvalRequestId`).
 *   Eingabe fuer das `POLICY_REQUIRED`-Tool-Outcome aus AP E.6.
 * - [Denied] — endgueltige, nicht-retrybare Ablehnung; Tool antwortet mit
 *   `POLICY_DENIED` und uebernimmt [reasonCode] in den Audit-Trail.
 */
sealed interface PolicyDecision {

    data object Allowed : PolicyDecision

    data class RequiresApproval(
        val approvalRequestId: String,
        val correlationKind: ApprovalCorrelationKind,
        val correlationKey: String,
        val requiredScopes: Set<String>,
        val reasons: List<String>,
    ) : PolicyDecision

    data class Denied(val reasonCode: String) : PolicyDecision
}
