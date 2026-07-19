# MCP-HTTP: unbegrenzter Pre-Auth-Body + ungebundene Sessions (P2)

> **Status:** VOLLSTÄNDIG BEHOBEN 2026-07-18 — Befund 4 (P2) + 13+14 (P3).
> **Trigger:** Security-Vollaudit
> ([`security-audit-2026-07-17.md`](security-audit-2026-07-17.md), Befunde 4 = P2,
> 13 = P3, 14 = P3). Gemeinsamer Fix-Ort: die HTTP-Transport-Schicht.
>
> **Umsetzung (Befund 4):** Zwei Teile in `McpHttpRoute.handleMcpPost`. (1)
> `checkBodySize` weist einen `POST /mcp` mit `Content-Length` über
> `maxRequestBodyBytes` (neu in `McpLimitsConfig`, Default 8 MiB, über dem
> 6-MiB-Upload-Cap) mit `413` ab — vor dem Body-Read, billigster Check zuerst.
> (2) `validateBearer` wandert **vor** `parseBody`/`receiveText`, sodass ein
> unauthentifizierter Request den Body nie puffert oder parst. `McpLimitsConfig`
> ist in `installMcpHttpRoute` gefädelt (optionaler Param, Default für Tests). TDD
> in `McpHttpAuthTest` (413 vor Auth; 401 statt 400 bei Token-los + Malformed-
> Body). Spec-`Request-Härtung`-Regel ergänzt. Docker `:mcp:check` grün.
>
> **Restrisiko (dokumentiert, jenseits des P2):** Ein **authentifizierter** Client
> mit `Transfer-Encoding: chunked` ohne `Content-Length` umgeht den
> Content-Length-Cap. Der unauthentifizierte Pre-Auth-Vektor — der eigentliche
> P2 — ist geschlossen, weil der Body-Read jetzt hinter der Bearer-Validierung
> liegt. Ein harter gedeckelter Read (Chunked) wäre die nächste Tiefenstufe.
>
> **Umsetzung (Befund 13+14):** `DELETE /mcp` läuft jetzt dieselbe Origin+Bearer-
> Kette wie POST und lässt nur den Session-Owner-Principal löschen (13). Ein
> Owner-Mismatch bei DELETE ist `405` — ununterscheidbar von „unbekannte Session".
> `resolveContext` bindet die Session an ihren Erzeuger-Principal: ein Request,
> dessen validierter `principalId` ≠ `SessionState.principalContext.principalId`
> ist, wird wie eine unbekannte Session behandelt (`404`, kein Existenz-Leak) —
> damit sieht das per-Session `bindPrincipal`/`currentPrincipal` nur je diesen
> einen Principal (14). TDD in `McpHttpAuthTest` (DELETE ohne Token → 401;
> Cross-Principal POST → 404 / DELETE → 405; Owner-DELETE → 200). Docker
> `:mcp:check` + `:cli:check` grün.
>
> **Restrisiko (dokumentiert):** `currentPrincipal` bleibt eine per-Session-
> `AtomicReference`; nebenläufige Requests **desselben** Principals auf derselben
> Session teilen sich den Scope-Snapshot (gleiche Identität/Tenant → benign, die
> Scope-Prüfung selbst läuft per-Request auf dem eigenen Principal). Ein
> per-Dispatch durchgereichter Principal (statt geteilter Zustand) wäre der tiefere
> Umbau — durch die Owner-Bindung aber nicht mehr sicherheitskritisch.

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
