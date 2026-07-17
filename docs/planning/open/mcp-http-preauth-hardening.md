# MCP-HTTP: unbegrenzter Pre-Auth-Body + ungebundene Sessions (P2)

> **Status:** Vorabklärung (2026-07-17)
> **Trigger:** Security-Vollaudit
> ([`security-audit-2026-07-17.md`](security-audit-2026-07-17.md), Befunde 4 = P2,
> 13 = P3, 14 = P3). Gemeinsamer Fix-Ort: die HTTP-Transport-Schicht.
> **Aktivierungsbedingung:** P2 — RC-Kandidat → `next/`-Plan.

## Befunde

**4 (P2, CWE-770) — Pre-Auth-Heap-Exhaustion.** `POST /mcp` liest den
Request-Body per `call.receiveText()` **unbegrenzt und vor** der
Bearer-Validierung vollständig in den Heap und parst ihn. Der `embeddedServer`
setzt kein Body-Limit; `ResponseLimitEnforcer` misst die *Antwort*, nicht die
Anfrage. Ein unauthentifizierter Angreifer mit Netzzugang zum Port kippt den
Prozess mit einem großen POST. Von zwei Auditoren unabhängig gefunden
(mcp-surface und deserialization).

**13 (P3, CWE-306) — `DELETE /mcp` terminiert Sessions ohne jede
Authentifizierung.**

**14 (P3, CWE-488) — Session ist an keinen Principal gebunden;
`currentPrincipal` ist geteilter mutabler Zustand.** Zusammen mit 13 ergibt
das eine Cross-Principal-Störfläche.

## Arbeitspakete (Skizze)

1. Body-Limit **vor** dem Lesen erzwingen (Ktor-seitig am `embeddedServer` bzw.
   per `Content-Length`-Prüfung), gedeckelt aus `McpLimitsConfig` — die
   Konfigurationsfläche existiert bereits, sie greift hier nur nicht.
2. Reihenfolge umdrehen: Bearer-Validierung **vor** Body-Read/Parse.
3. `DELETE /mcp` derselben Auth unterwerfen wie die übrigen Routen.
4. Session an den Principal binden, `currentPrincipal` als geteilten mutablen
   Zustand auflösen.
5. Regression: Test mit übergroßem unauthentifiziertem POST (Heap-Cap-Muster aus
   `test/perf-data-path` ist vorhanden und übertragbar), Test für `DELETE` ohne
   Token, Test für Session-Übernahme über Principal-Grenze.

## Fundstellen

- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/transport/http/McpHttpRoute.kt:226` (`call.receiveText()`)
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/transport/http/McpHttpRoute.kt:142` (Reihenfolge Auth/Body)
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/server/McpServerBootstrap.kt:124` (`embeddedServer` ohne Limit)
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/ResponseLimitEnforcer.kt:48` (Cap misst die Antwort)
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/transport/http/McpHttpRoute.kt:461` (`DELETE /mcp`)
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/transport/http/McpHttpRoute.kt:283` (`currentPrincipal`)
