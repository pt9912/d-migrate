package dev.dmigrate.server.ports

import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import java.time.Instant

/**
 * Token-Registry fuer den stdio-Transport per
 * `docs/ImpPlan-0.9.6-B.md` §4.2 + §12.10.
 *
 * Der MCP-stdio-Transport leitet Principals NICHT aus OS-User,
 * Parent-Prozess oder ungeprueften ENV-Daten ab. Stattdessen wird
 * `DMIGRATE_MCP_STDIO_TOKEN` zu einem `sha256_hex`-Fingerprint gehasht
 * und gegen diese Registry nachgeschlagen. Eine fehlende, unbekannte
 * oder abgelaufene Eintragung muss am Tool-/Resource-Layer als
 * `AUTH_REQUIRED` zurueckkommen — die Registry liefert hier nur den
 * neutralen Lookup.
 *
 * Implementierungen MUESSEN den rohen Tokenwert weder loggen noch
 * audieren. Eine konkrete File-backed Default-Implementierung lebt
 * im `mcp`-Adapter-Modul.
 */
interface StdioTokenStore {

    /**
     * Liefert die Berechtigung zu einem Tokenfingerprint. `null`
     * bedeutet "Token unbekannt" — der Caller muss das in
     * `AUTH_REQUIRED` uebersetzen. Abgelaufene Eintraege duerfen ent-
     * weder weiterhin zurueckgegeben werden (Caller prueft `expiresAt`)
     * ODER intern gefiltert werden — Caller muss in beiden Faellen
     * den `expiresAt` selbst gegen `Instant.now()` pruefen.
     *
     * Erwartetes Format des Fingerprints: lowercase Hex der SHA-256 des
     * rohen Token-Werts, ohne Praefixe / Trennzeichen.
     */
    fun lookup(tokenFingerprint: String): StdioTokenGrant?
}

/**
 * Aufloesungs-DTO fuer einen stdio-Tokenfingerprint per §12.10. Die
 * Felder werden 1:1 in einen `PrincipalContext` projiziert; cross-
 * tenant impersonation ist in 0.9.6 nicht vorgesehen, deshalb gibt es
 * keine `allowedTenantIds`-Liste — der Caller setzt
 * `effectiveTenantId == homeTenantId` und
 * `allowedTenantIds == { tenantId }`.
 */
data class StdioTokenGrant(
    val principalId: PrincipalId,
    val tenantId: TenantId,
    val scopes: Set<String>,
    val isAdmin: Boolean,
    val auditSubject: String,
    val expiresAt: Instant,
)
