# MCP-Auth: kein https-Zwang auf jwksUrl/introspectionUrl (P2)

> **Status:** BEHOBEN 2026-07-18
> **Trigger:** Security-Vollaudit
> ([`security-audit-2026-07-17.md`](security-audit-2026-07-17.md), Befunde 5 = P2,
> 7 = P3, 12 = P3). Eine systemische Scheme-Validierungs-Omission, ein Fix.
>
> **Umsetzung:** Ein gemeinsamer Guard `authUrlSchemeError(label, url)` in
> `McpServerConfig`, aufgerufen aus `jwksAuthErrors()` **und**
> `introspectionAuthErrors()` — erzwingt `https`, außer der URL-**Host** ist
> Loopback (kein pauschaler Zwang; `http://localhost/certs` als Dev-Keycloak-Form
> bleibt gültig). Loopback-Klassifikation über das aus `bindIsLoopback()`
> extrahierte, geteilte `isLoopbackHost(host)` (InetAddress, IPv6-Brackets
> entpackt, unauflösbar → fail-closed = nicht-Loopback → https-Pflicht). Der
> Fehler fließt in `validate()` und löst den bestehenden **Exit 2 vor dem ersten
> Client-Request** aus (`McpServeRunner.doExecute` + Defense-in-depth in
> `McpServerBootstrap.startHttp`) — kein neuer Exit-Pfad nötig. TDD in
> `McpServerConfigValidationTest` (routbar-http → reject je URL; 127.0.0.1 +
> localhost + https → ok). Docker `:mcp:check` + `:cli:check` grün.
>
> **Deckt zusätzlich ab:** Befund 12 (introspectionUrl-Schema) ist derselbe Guard;
> Befund 7 (Bearer-/Secret-Klartext im Introspection-POST) entfällt als Folge, weil
> `https` auf `introspectionUrl` den Klartext-Transport beseitigt.
>
> **Spec:** Regel in [`spec/mcp-server.md`](../../../spec/mcp-server.md)
> „Validierungsregeln" ergänzt (https außer Loopback-Host für `jwks-url`/
> `introspection-url`).
>
> **ADR 0009: keine Änderung nötig.** Die Entscheidung (reiner Resource-Server,
> validiert extern ausgestellte JWTs) bleibt unberührt; der Transportsicherheits-
> Zwang für den Vertrauensanker ist eine Validierungsregel *unter* dieser
> Entscheidung und gehört normativ in die Spec, nicht als neue/geänderte
> Architektur-Entscheidung.

## Befund

`jwksUrl` und `introspectionUrl` werden nirgends auf ihr Schema validiert;
`http://` wird akzeptiert. `McpServeRunner` nutzt `URI::create` ohne Prüfung,
die Clikt-Option hat keine Validierung, `McpServerConfig.jwksAuthErrors()`
prüft nur auf Vorhandensein.

Die `jwksUrl` ist der **Vertrauensanker der gesamten Token-Prüfung**. Über
`http://` kann ein Netzwerk-Angreifer (MITM) das JWKS ersetzen und damit Tokens
fälschen, die die ansonsten vorbildliche Validierungskette (alg-Allowlist ohne
`none`/`HS*`, `requiredClaims`, Clock-Skew-Deckel) sauber passieren — die Kette
ist nur so stark wie der Schlüssel, gegen den sie prüft. Beim
`introspectionUrl` kommt hinzu: das `client_secret` geht im Klartext raus, und
die Introspection-Antwort (`active: true`) ist fälschbar.

**Der Kontrast ist im selben File sichtbar:** `publicBaseUrl` wird bei
`McpServerConfig.kt:172` sehr wohl schema-geprüft. Die Regel existiert, sie
wurde nur nicht auf die Auth-URLs angewandt.

## Wichtige Präzisierung aus der Gegenprüfung

Ein pauschaler https-Zwang ist **falsch**: `introspectionAuthErrors()` waivet
die Client-Credential-Pflicht bewusst für Loopback, und
`http://localhost:8080/realms/x/protocol/openid-connect/certs` ist die gängige
Dev-Keycloak-Form. Der richtige Guard ist **„https außer Loopback-Host"**,
gekoppelt an das bereits existierende `bindIsLoopback()`-Muster — sonst bricht
das Dev-Setup.

## Arbeitspakete (Skizze)

1. Guard „https erzwingen, außer der Host ist Loopback" als eine Stelle, für
   `jwksUrl` **und** `introspectionUrl` gemeinsam (Befund 12 ist derselbe Defekt).
   An `bindIsLoopback()` anlehnen, nicht neu erfinden.
2. Fail-closed beim Start (`ConfigError` → Exit 2), analog zu
   `rejectDevKeyringInProductionOrExit`.
3. Regel in `spec/mcp-server.md` ergänzen — dort steht derzeit nur
   „MÜSSEN gesetzt sein", nichts zum Schema.
4. Prüfen, ob [ADR 0009](../../adr/0009-mcp-resource-server-no-auth-server.md)
   eine Ergänzung braucht (Transportsicherheit des Vertrauensankers ist dort
   nicht adressiert).

## Fundstellen

- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/server/McpServerConfig.kt:98` (`jwksAuthErrors()`)
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/server/McpServerConfig.kt:104` (`introspectionAuthErrors()`)
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/server/McpServerConfig.kt:172` (Kontrast: `publicBaseUrl` wird geprüft)
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/auth/JwksAuthValidator.kt:44` (`JWKSourceBuilder.create`)
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/McpServeRunner.kt:145` (`URI::create` ohne Prüfung)
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/McpCommands.kt:99` (Clikt-Option ohne `check`)
