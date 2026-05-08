package dev.dmigrate.server.application.approval

import dev.dmigrate.server.core.approval.GrantRequest
import java.time.Instant

/**
 * LF-012 / LN-011 / LN-017 / LN-027 *
 * Lehnt jede [issue]-Anfrage mit `policy:no-issuer-configured` ab. Das
 * Bootstrap-Wiring MUSS diesen Issuer als Default einsetzen, sodass eine
 * laufende Instanz ohne explizite Konfiguration nie Grants ausstellt.
 * Direkte `Allow`-Policies (LF-012 / LN-011 / LN-017 / LN-027) bleiben unberuehrt — sie laufen
 * gar nicht erst durch den Issuer.
 */
object FailClosedGrantIssuer : GrantIssuer {

    const val REASON: String = "policy:no-issuer-configured"

    override fun issue(request: GrantRequest, now: Instant): GrantIssuance =
        GrantIssuance.NotIssuable(REASON)
}
