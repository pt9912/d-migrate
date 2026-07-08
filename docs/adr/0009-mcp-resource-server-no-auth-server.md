---
status: accepted
date: 2026-06-15
decision-makers: pt9912
consulted: MCP-Maintainers
informed: Betreiber:innen einer MCP-HTTP-Deployment; Integrator:innen mit eigenem IdP
---

# MCP-Server als OAuth-Resource-Server (kein eigener Authorization Server, keine DCR)

## Kontext und Problemstellung

Der MCP-HTTP-Transport authentifiziert Requests über `Authorization: Bearer
<jwt>` (`--auth-mode jwt-jwks` / `jwt-introspection`). Die MCP-Spezifikation
skizziert darüber hinaus, dass ein Server selbst OAuth-Funktionen anbieten
könnte: einen **Authorization Server** (Token-Ausgabe, `authorization_endpoint`/
`token_endpoint`) und **Dynamic Client Registration** (DCR, `/register`).

Zu entscheiden ist, ob d-migrate diese Rollen selbst implementiert oder sich auf
einen externen Identity-Provider (IdP) stützt.

## Entscheidungstreiber

- **Sicherheit/Verantwortung:** Ein Authorization Server ist ein
  sicherheitskritisches, eigenständiges Stück Software (Token-Signatur,
  Client-Secrets, Consent, Rotation). Das selbst zu betreiben vergrößert die
  Angriffsfläche erheblich.
- **Enterprise-Realität:** Zielumgebungen haben in der Regel bereits einen IdP
  (Keycloak, Entra ID, Okta, …); Tokens dort auszustellen ist der etablierte Weg.
- **Trennung der Belange:** d-migrate ist ein Migrationswerkzeug, kein
  Identity-Provider.

## Betrachtete Optionen

1. **Resource-Server** (gewählt): d-migrate **validiert** extern ausgestellte
   JWTs gegen einen konfigurierten OIDC-Issuer (`--issuer` + `--jwks-url` bzw.
   `--introspection-url`, `--audience`) und liefert RFC-9728 Protected-Resource-
   Metadata unter `/.well-known/oauth-protected-resource`.
2. **Eigener Authorization Server** mit Token-Ausgabe.
3. **Authorization Server + Dynamic Client Registration** (`/register`).

## Entscheidung

Gewählt: **Option 1 — reiner OAuth-Resource-Server.** d-migrate stellt **keine**
Tokens aus und implementiert **keine** Dynamic Client Registration. Tokens
kommen von einem externen IdP; der Server prüft sie pro Request (Issuer,
Audience, Signatur via JWKS oder Introspection, Algorithmus-Allowlist ohne
`none`/`HS*`). Für lokale Nutzung gibt es zusätzlich den stdio-Token-Pfad
(`--stdio-token-file`) und `--auth-mode disabled` (strikt Loopback-only).

## Konsequenzen

- **Positiv:** kleine, klar abgegrenzte Sicherheitsfläche; Integration in
  vorhandene IdPs; keine Pflege von Client-Secrets/Consent im Tool.
- **Negativ:** Für netzwerkweiten Betrieb ist ein **externer IdP Voraussetzung**;
  d-migrate kann nicht „standalone" Tokens vergeben. Clients müssen ihre Tokens
  anderweitig beziehen.
- Eine spätere AS-/DCR-Unterstützung wäre additiv und würde diese ADR ablösen.

## Weitere Informationen

- [`spec/mcp-server.md`](../../spec/mcp-server.md) — Authorisierung (HTTP),
  Validierungsregeln, Abgrenzung
- Verwandt: Token-/Scope-Modell in [`spec/mcp-server.md`](../../spec/mcp-server.md)
  „Authorisierung"
