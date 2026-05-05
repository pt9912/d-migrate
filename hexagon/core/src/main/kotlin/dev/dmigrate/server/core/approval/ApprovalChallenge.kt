package dev.dmigrate.server.core.approval

/**
 * Phase E §5.5 durable Approval-Challenge.
 *
 * Wird von der Policy beim ersten `RequiresApproval`-Outcome erzeugt
 * und im `IdempotencyStore`-Eintrag persistiert (zusammen mit dem
 * Statuswechsel `PENDING -> AWAITING_APPROVAL`). Bei einem Approved-
 * Retry liest der `JobStartOrchestrator` die durable Challenge zurueck
 * und vergleicht sie gegen die `ApprovalGrant`-Bindungen — damit greift
 * der Anti-Replay-Check im `ApprovalGrantValidator` echt (Plan §5.5
 * "Ein Grant fuer eine alte oder erneuerte approvalRequestId ist
 * ungueltig").
 *
 * Felder spiegeln `dev.dmigrate.server.core.policy.PolicyDecision.RequiresApproval`
 * eins-zu-eins: dieselben Werte, die die Policy zurueckgegeben hat,
 * werden hier persistent gehalten, damit Replays deterministisch
 * dieselbe Challenge zeigen.
 */
data class ApprovalChallenge(
    val approvalRequestId: String,
    val correlationKind: ApprovalCorrelationKind,
    val correlationKey: String,
    val requiredScopes: Set<String>,
    val reasons: List<String> = emptyList(),
)
