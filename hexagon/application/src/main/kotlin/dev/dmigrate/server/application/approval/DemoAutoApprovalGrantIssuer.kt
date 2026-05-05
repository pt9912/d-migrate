package dev.dmigrate.server.application.approval

import dev.dmigrate.server.core.approval.ApprovalGrant
import dev.dmigrate.server.core.approval.GrantRequest
import dev.dmigrate.server.ports.ApprovalGrantStore
import java.time.Instant
import java.util.UUID

/**
 * Phase E §7.4 / §3.1 **Demo-Auto-Approval-Modus**.
 *
 * Stellt JEDEN [GrantRequest] ohne weitere Pruefung aus. Gedacht
 * ausschliesslich fuer lokale Entwickler-Demos ueber Loopback/stdio.
 *
 * **Sicherheitswarnung**: Dieser Issuer darf in produktiven Konfigurationen
 * nicht gewired werden. Die Transport-Bindung (loopback/stdio) wird vom
 * Bootstrap-Wiring durchgesetzt — der Issuer selbst kennt den Transport
 * nicht. Plan §7.4 verlangt:
 * - explizit aktivierte Konfiguration (Property-Gate)
 * - klare Audit-Markierung jedes Issue-Vorgangs ueber [grantSource]
 * - eindeutiger [issuerFingerprint], damit Audit-Trace und
 *   [IssuerCheck.AllowList] den Demo-Mode jederzeit aussortieren koennen.
 */
class DemoAutoApprovalGrantIssuer(
    private val store: ApprovalGrantStore,
    private val tokenFactory: () -> String = { "tok_${UUID.randomUUID()}" },
    private val issuerFingerprint: String = DEMO_ISSUER_FINGERPRINT,
    private val grantSource: String = DEMO_GRANT_SOURCE,
) : GrantIssuer {

    override fun issue(request: GrantRequest, now: Instant): GrantIssuance {
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
            issuedScopes = request.requiredScopes,
            grantSource = grantSource,
            expiresAt = request.expiresAt,
        )
        store.save(grant)
        return GrantIssuance.Issued(approvalToken = token, grant = grant)
    }

    companion object {
        const val DEMO_ISSUER_FINGERPRINT: String = "demo-auto-approval"
        const val DEMO_GRANT_SOURCE: String = "demo-auto-approval"
    }
}
