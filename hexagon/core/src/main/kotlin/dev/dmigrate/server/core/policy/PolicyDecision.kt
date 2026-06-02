package dev.dmigrate.server.core.policy

import dev.dmigrate.server.core.approval.ApprovalCorrelationKind

/**
 * LF-012 / LN-011 / LN-017 / LN-027: Antwort des Policy-Service auf einen Start-Versuch.
 *
 * Drei Branches gemaess LF-012 / LN-011 / LN-017 / LN-027:
 *
 * - [Allowed] — direkter Start; Caller ueberspringt Approval-Flow und faehrt
 *   sofort mit der Idempotency-Reservierung fort. Erzeugt KEINEN Grant.
 * - [RequiresApproval] — Caller muss Approval-Token beibringen. Der Server
 *   prueft den Grant gegen die hier vergebene `approvalRequestId`
 *   (Validator-Check siehe LF-012 / LN-011 / LN-017 / LN-027 (1/3) `ApprovalAttempt.approvalRequestId`).
 *   Eingabe fuer das `POLICY_REQUIRED`-Tool-Outcome aus LF-012 / LN-011 / LN-017 / LN-027.
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
