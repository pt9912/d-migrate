---
status: accepted
date: 2026-06-15
decision-makers: pt9912
consulted: MCP-Maintainers
informed: Integrator:innen des MCP-HTTP-Transports; künftige Author:innen einer Push-/Streaming-Erweiterung
---

# MCP-Transport ohne SSE-/Server→Client-Push

## Kontext und Problemstellung

Das MCP-Protokoll (Streamable HTTP, `2025-11-25`) erlaubt, dass ein Server
Nachrichten **server-initiiert** an den Client streamt — typischerweise über
Server-Sent Events (SSE, `text/event-stream`), etwa für `notifications/*`
(Fortschritt, `listChanged`) oder eine offene `GET /mcp`-Verbindung.

d-migrate hat lang laufende Operationen (Reverse, Compare, Profiling, Import,
Transfer) bereits als **asynchrone Jobs** modelliert (`*_start` → `jobId`,
`job_status_get`, `job_cancel`). Die Frage ist, ob der Server zusätzlich einen
Push-Kanal betreiben soll, um Fortschritt/Änderungen aktiv zu melden.

## Entscheidungstreiber

- **Zustandskosten:** SSE erfordert langlebige Verbindungen, serverseitiges
  Abo-/Session-Fanout und Reconnect-/Backpressure-Handling — zusätzlicher
  Betriebs- und Sicherheitsaufwand (Multi-Instanz, Lastverteilung).
- **Vorhandene Alternative:** Job-Status ist bereits pull-bar
  (`job_status_get` mit `executionMeta`/`retryAfter`); Discovery-Listen sind
  paginiert. Ein Push-Kanal ist für den heutigen Funktionsumfang nicht nötig.
- **Determinismus/Testbarkeit:** Request/Response ist einfacher gegen Drift zu
  pinnen als ein Streaming-Kanal.

## Betrachtete Optionen

1. **Request/Response ohne Push** (gewählt): `POST /mcp` für JSON-RPC;
   `GET /mcp` → `405`; `subscribe`/`listChanged` als Capability `false`;
   Fortschritt per Polling über `job_status_get`.
2. **SSE-Push** für `notifications/*` und Job-Fortschritt.
3. **Webhooks** (Server ruft eine Client-URL zurück).

## Entscheidung

Gewählt: **Option 1 — kein SSE-/Server→Client-Push.** Der HTTP-Transport ist
reines Request/Response: `GET /mcp` antwortet `405 Method Not Allowed`, der
Server kündigt `subscribe` und `listChanged` als `false` an, und es gibt keine
server-initiierten `notifications/*`. Client→Server-`notifications/initialized`
(Teil des MCP-Handshakes) wird normal verarbeitet — das ist **kein** Push.

## Konsequenzen

- **Positiv:** kein langlebiger Verbindungs-/Abo-Zustand; einfacher Multi-Instanz-
  Betrieb; deterministisch testbar.
- **Negativ:** Clients erfahren Job-Fortschritt und Ressourcen-Änderungen nur
  durch **Polling** (`job_status_get`), nicht in Echtzeit.
- Ein späterer Push-Kanal (SSE oder Webhooks) ist additiv möglich, ohne die
  bestehenden Pull-Verträge zu brechen, und würde diese ADR ablösen.

## Weitere Informationen

- [`spec/mcp-server.md`](../../spec/mcp-server.md) — Transports, Abgrenzung
- Asynchrone Jobs / Polling-Vertrag: [`spec/mcp-server.md`](../../spec/mcp-server.md)
  Abschnitt „Phase E"; [`spec/job-contract.md`](../../spec/job-contract.md)
