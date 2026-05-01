package dev.dmigrate.mcp.auth

import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.ports.StdioTokenGrant
import dev.dmigrate.server.ports.StdioTokenStore
import java.time.Clock

/**
 * Resolved-or-not Result eines stdio-Principal-Lookups per
 * `ImpPlan-0.9.6-B.md` §4.2 + §6.7.
 *
 * `AuthRequired` ist der Sammeltyp fuer "kein gueltiger Principal
 * ableitbar" — fehlender Token, fehlende Registry, unbekannter
 * Fingerprint, abgelaufener Eintrag. Tool-/Resource-Handler MUESSEN
 * das in einen `AUTH_REQUIRED`-Fehler uebersetzen; OS-User,
 * Parent-Prozess oder ENV-Daten sind explizit kein Fallback (§4.2).
 *
 * `reason` ist ein kurzer, audit-/log-tauglicher Text — er enthaelt
 * NIE Tokenfragmente oder Fingerprints.
 */
sealed interface StdioPrincipalResolution {

    data class Resolved(val principal: PrincipalContext) : StdioPrincipalResolution

    data class AuthRequired(val reason: String) : StdioPrincipalResolution
}

/**
 * Loest den stdio-Principal aus `DMIGRATE_MCP_STDIO_TOKEN` plus einer
 * konfigurierten [StdioTokenStore] auf.
 *
 * Aufrufer geben:
 * - [tokenSupplier] — typischerweise `{ System.getenv("DMIGRATE_MCP_STDIO_TOKEN") }`,
 *   in Tests ein Lambda mit deterministischem Wert.
 * - [store] — `null` bedeutet "keine Token-Registry konfiguriert"
 *   (`McpServerConfig.stdioTokenFile == null`). In dem Fall ist jeder
 *   Aufruf `AuthRequired` — niemals ein impliziter Demo-Principal.
 * - [clock] — fuer Ablauf-Pruefung; Default `Clock.systemUTC()`.
 *
 * Der Mapping-Vertrag (§4.2 + §12.10):
 * - `effectiveTenantId == homeTenantId`,
 *   `allowedTenantIds == { tenantId }` (kein cross-tenant Switch).
 * - `authSource = AuthSource.SERVICE_ACCOUNT` — stdio mit Fingerprint
 *   ist semantisch ein lokal ausgehandeltes Service-Token, nicht OIDC,
 *   nicht ANONYMOUS und nicht LOCAL (LOCAL ist in 0.9.6 nicht belegt;
 *   wir bleiben fail-safe und benutzen den am besten passenden Eintrag).
 * - `auditSubject` aus dem Grant; rohe Tokenwerte erscheinen nie im
 *   Audit (§12.10 — `SecretScrubber` deckt `tok_*`-Praefixe ohnehin).
 */
internal class StdioPrincipalResolver(
    private val tokenSupplier: () -> String?,
    private val store: StdioTokenStore?,
    private val clock: Clock = Clock.systemUTC(),
) {

    fun resolve(): StdioPrincipalResolution {
        val raw = tokenSupplier()
        if (raw.isNullOrEmpty()) {
            return StdioPrincipalResolution.AuthRequired("DMIGRATE_MCP_STDIO_TOKEN not set")
        }
        if (store == null) {
            return StdioPrincipalResolution.AuthRequired("stdio token registry not configured")
        }
        val fingerprint = StdioTokenFingerprint.of(raw)
        val grant = store.lookup(fingerprint)
            ?: return StdioPrincipalResolution.AuthRequired("stdio token unknown")
        val now = clock.instant()
        if (!grant.expiresAt.isAfter(now)) {
            return StdioPrincipalResolution.AuthRequired("stdio token expired")
        }
        return StdioPrincipalResolution.Resolved(toPrincipal(grant))
    }

    private fun toPrincipal(grant: StdioTokenGrant): PrincipalContext = PrincipalContext(
        principalId = grant.principalId,
        homeTenantId = grant.tenantId,
        effectiveTenantId = grant.tenantId,
        allowedTenantIds = setOf(grant.tenantId),
        scopes = grant.scopes,
        isAdmin = grant.isAdmin,
        auditSubject = grant.auditSubject,
        authSource = AuthSource.SERVICE_ACCOUNT,
        expiresAt = grant.expiresAt,
    )
}
