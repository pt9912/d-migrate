package dev.dmigrate.server.application.approval

import dev.dmigrate.server.core.approval.ApprovalGrant
import dev.dmigrate.server.core.approval.GrantRequest
import dev.dmigrate.server.ports.ApprovalGrantStore
import java.time.Instant
import java.util.UUID

/**
 * Phase E §7.4 produktiver Grant-Aussteller mit deklarativer Allowlist.
 *
 * Wertet [rules] in Reihenfolge aus; erste passende Regel gewinnt. Wenn
 * keine Regel matcht, gibt [NotIssuable("policy:not-on-allowlist")][GrantIssuance.NotIssuable]
 * zurueck — fail-closed gemaess Plan §7.4 ("Reine lokale Policy-Allowlist
 * ohne GrantIssuer-Modus darf nur direkte ALLOW-Entscheidungen liefern").
 *
 * Beim Match werden gleichermassen [tokenFactory] (raw token, nur in
 * memory) und der [ApprovalGrant] mit `approvalTokenFingerprint` erzeugt;
 * der Fingerprint und der Grant landen ueber [store] persistent, das
 * rohe Token gibt der Caller in der Tool-Antwort an den MCP-Client zurueck
 * und wirft es danach weg.
 *
 * @property issuerFingerprint stabile Identitaet des Ausstellers — wird im
 *   Grant gespeichert und kann von der Validator-Konfiguration
 *   ([dev.dmigrate.server.application.approval.IssuerCheck.AllowList])
 *   eingrenzt werden.
 */
class ConfiguredAllowlistGrantIssuer(
    private val store: ApprovalGrantStore,
    private val rules: List<GrantIssuanceRule>,
    private val issuerFingerprint: String,
    private val tokenFactory: () -> String = { "tok_${UUID.randomUUID()}" },
    private val grantSource: String = "configured-allowlist",
) : GrantIssuer {

    override fun issue(request: GrantRequest, now: Instant): GrantIssuance {
        val rule = rules.firstOrNull { it.matches(request) }
            ?: return GrantIssuance.NotIssuable(REASON_NOT_ON_ALLOWLIST)
        val issuedScopes = rule.grantedScopes ?: request.requiredScopes
        if (!issuedScopes.containsAll(request.requiredScopes)) {
            return GrantIssuance.NotIssuable(REASON_SCOPES_INSUFFICIENT)
        }
        val token = tokenFactory()
        val grant = ApprovalGrant(
            approvalRequestId = request.approvalRequestId,
            correlationKind = request.correlationKind,
            correlationKey = request.correlationKey,
            approvalTokenFingerprint = ApprovalTokenFingerprint.compute(token),
            toolName = request.toolName,
            tenantId = request.tenantId,
            callerId = request.callerId,
            payloadFingerprint = request.payloadFingerprint,
            issuerFingerprint = issuerFingerprint,
            issuedScopes = issuedScopes,
            grantSource = grantSource,
            expiresAt = request.expiresAt,
        )
        store.save(grant)
        return GrantIssuance.Issued(approvalToken = token, grant = grant)
    }

    companion object {
        const val REASON_NOT_ON_ALLOWLIST: String = "policy:not-on-allowlist"
        const val REASON_SCOPES_INSUFFICIENT: String = "policy:scopes-insufficient"
    }
}
