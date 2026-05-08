package dev.dmigrate.server.application.approval

import dev.dmigrate.server.core.approval.GrantRequest
import java.time.Instant

/**
 * Phase E §7.4 Grant-Aussteller-Port.
 *
 * Erzeugt aus einer [GrantRequest] (vorher von der Policy-Entscheidung
 * `RequiresApproval` materialisiert) entweder einen ausgestellten
 * [GrantIssuance.Issued] oder eine [GrantIssuance.NotIssuable]-Antwort
 * (fail-closed).
 *
 * Drei produktive Implementierungen:
 *
 * - [FailClosedGrantIssuer] — Default ohne Konfiguration; lehnt jede
 *   Anfrage ab.
 * - [ConfiguredAllowlistGrantIssuer] — explizit konfigurierte Allowlist;
 *   stellt nur fuer matchende Regeln aus.
 * - [DemoAutoApprovalGrantIssuer] — unsicher, nur fuer loopback/stdio;
 *   stellt fuer jede Anfrage aus, audit-pflichtig.
 *
 * Zusaetzliche Pfade aus Plan §3.1 (Admin-Subkommando, signierte
 * Grant-Datei) implementieren `GrantIssuer` separat und liegen ausserhalb
 * dieses Moduls.
 */
fun interface GrantIssuer {
    fun issue(request: GrantRequest, now: Instant): GrantIssuance
}
