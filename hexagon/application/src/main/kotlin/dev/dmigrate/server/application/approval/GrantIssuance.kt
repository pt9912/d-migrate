package dev.dmigrate.server.application.approval

import dev.dmigrate.server.core.approval.ApprovalGrant

/**
 * Phase E §7.4 Ergebnis von [GrantIssuer.issue].
 *
 * - [Issued] enthaelt den ROHEN [approvalToken], der nur ueber den
 *   In-Memory-Rueckweg an den Caller (Tool-Handler -> MCP-Client) geht
 *   und niemals in den `ApprovalGrantStore` oder Audit-Log gelangt
 *   (Plan §7.4: "rohe Tokens erscheinen nicht in Store oder Audit"). Der
 *   gespeicherte [grant] traegt nur den Fingerprint ueber
 *   `approvalTokenFingerprint`.
 * - [NotIssuable] signalisiert fail-closed: kein Grant wird ausgestellt,
 *   der Tool-Handler antwortet mit `POLICY_REQUIRED` und der [reason]
 *   landet im Audit (Plan §7.4 Tests "fehlender Grant-Aussteller ist
 *   fuer RequiresApproval dokumentiert fail-closed").
 */
sealed interface GrantIssuance {

    data class Issued(
        val approvalToken: String,
        val grant: ApprovalGrant,
    ) : GrantIssuance

    data class NotIssuable(val reason: String) : GrantIssuance
}
