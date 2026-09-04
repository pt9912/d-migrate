# ImpPlan 1.2.0 — MCP: Policy-Datei-Konfiguration + `connections/list` mit Live-Status

> **Status:** Draft, bereit zur Umsetzung (2026-09-04). Zwei unabhängig
> voneinander gefundene, bislang unbekannte Lücken im MCP-Server-Adapter
> (`adapters/driving/mcp`), beide vom Eigner zur Behebung freigegeben.
> Zwei Slices in einem Dokument, weil beide dieselbe Herkunft (Faktencheck
> gegen `capabilities_list`/Admin-Scope-Aussagen) und denselben Adapter
> betreffen — aber unabhängig lieferbar: **Slice A zuerst** (klein, fast
> 1:1 präzediert), **Slice B danach** (neues Sicherheits-Feature, eigene
> offene Entwurfsfragen).
> **Vorbedingung:** Keine harte Blockade.

## Kontext / Ist-Stand (verifiziert)

- **Lücke 1 — Policy-Regeln nicht konfigurierbar.**
  `OperationalMcpWiring` (`adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/OperationalMcpWiring.kt:76`)
  defaultet auf `ConfiguredPolicyService(rules = emptyList())`. Bei
  leerer Regelliste greift immer `defaultEffect = Deny("policy:no-rule")`
  (`hexagon/application/src/main/kotlin/dev/dmigrate/server/application/policy/ConfiguredPolicyService.kt`).
  Beide Produktions-Wiring-Pfade in
  `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/McpServeWiring.kt`
  (Zeile 197 und 261) übergeben keinen eigenen `policyService`. Kein
  `--policy-*`-Flag in
  `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/McpCommands.kt`,
  kein `policy:`-Config-Block. Fail-closed ist als Sicherheitsdefault
  plausibel gewollt — dass es aber gar keinen Weg gibt, ihn bewusst zu
  lockern (Sandbox-/Demo-Betrieb), ist die eigentliche Lücke. Kein
  Planning-/ADR-Dokument nennt das als bekannte MVP-Lücke.
- **Lücke 2 — `connections/list` nur Scope-Reservierung, kein Dispatch.**
  Als Scope-Eintrag registriert
  (`adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/server/McpServerConfig.kt:298`,
  Scope `dmigrate:admin`) und explizit aus `tools/list` ausgeschlossen
  (`McpContractRegistries.PROTOCOL_METHODS`). Aber
  `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/protocol/McpService.kt`
  kennt die Methode gar nicht — kein `@JsonRequest("connections/list")`.
  Konfigurierte Verbindungen sind nur über MCP-Resources lesbar
  (`dmigrate://tenants/{tenantId}/connections/{connectionId}`), ohne
  Live-Status: `ConnectionReference`
  (`hexagon/core/src/main/kotlin/dev/dmigrate/server/core/connection/ConnectionReference.kt`)
  hat keine Status-/Health-Felder, und es existiert im ganzen Repo kein
  Ping-/Health-Check-/Test-Connection-Mechanismus.
- **Bestehende Kette für einen echten DB-Connect** (wiederverwendbar,
  bereits produktiv für `schema_reverse_start`/`data_profile_start`/
  `schema_compare_start`):
  `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/McpCoreJobWorkerFactory.kt` (Zeile 149-174) —
  `connectionStore.findById` → `ref.isReadableBy(principal, tenant)` →
  `connectionSecretResolver.resolve(ref, principal)` (Port
  `hexagon/ports-common/src/main/kotlin/dev/dmigrate/server/ports/ConnectionSecretResolver.kt`,
  Implementierung dispatcht über `CredentialProviderRegistry`) →
  `ConnectionUrlParser.parse` → `HikariConnectionPoolFactory.create`.
- **`PolicyRule`-Modell** (bereits vorhanden, wird nicht geändert):
  `hexagon/application/src/main/kotlin/dev/dmigrate/server/application/policy/PolicyRule.kt` —
  `PolicyRule(tenantId: TenantId?, toolName: String?, callerId:
  PrincipalId?, effect: PolicyEffect)`. `PolicyEffect` = `Allow` |
  `Challenge(requiredScopes, reasons)` | `Deny(reasonCode)`. Matching:
  erste passende Regel gewinnt (`null`-Feld = Wildcard), exakte
  Gleichheit, kein Pattern/Glob. `TenantId`/`PrincipalId` sind triviale
  `value class`-Wrapper um `String`
  (`hexagon/core/src/main/kotlin/dev/dmigrate/server/core/principal/PrincipalContext.kt`).
- **Vorbild für dateibasierte Konfiguration**:
  `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/FileBackedApprovalGrantStore.kt` —
  Extension-basierter Jackson-Mapper (`.yaml`/`.yml` → YAMLFactory, sonst
  JSON), Root-Objekt mit Array-Feld, `requiredText`-Helper für
  Pflichtfelder, atomarer Schreibpfad (hier nicht relevant, da
  Policy-Regeln nur gelesen werden).
- **Dispatch-Vorbild für einen neuen Protokoll-Slot**:
  `resourcesList` in `McpServiceImpl.kt` — `enforceScope(methodName)` →
  Principal erneut holen → Handler aufrufen → reguläres JSON-RPC-Result
  (kein Tool-Envelope, das ist nur für `tools/call`).
- **Keine Spec-Autorität** für ein Policy-Dateiformat oder für die
  `connections/list`-Response-Form — `spec/mcp-server.md` erwähnt Policy
  nur konzeptionell ("läuft durch `PolicyService.decide`"), kein
  YAML/JSON-Schema; `connections/list` hat nur die Scope-Tabellenzeile,
  keine Payload-Definition. Beide Formate sind in diesem ImpPlan neu zu
  entwerfen.
- **Redaction-Lücke** (nur für Slice B relevant): sensible Zugangsdaten
  werden zwar maskiert (`ConnectionSecretMasker`), aber Host/Port/
  Netzwerktopologie in rohen Exception-Messages nicht — ein Live-Ping-
  Fehlschlag würde unredigiert bis zum Client durchgereicht, wenn der
  neue Handler das nicht selbst abfängt.

## Slice A — `--policy-file`

### Scope

Ein neues, optionales `--policy-file`-Flag für `mcp serve`, das
`PolicyRule`-Einträge aus einer YAML-/JSON-Datei lädt und in
`OperationalMcpWiring.policyService` einspeist. Additiv: ohne Flag
bleibt das Verhalten exakt wie heute (leere Regelliste, fail-closed).

### Architektur-Entscheidungen

**AE-A1 — Einmaliges Laden beim Start, kein Hot-Reload.** Grants
(`FileBackedApprovalGrantStore`) hot-reloaden bewusst, weil sie live via
`mcp approval-grant issue` entstehen, während der Server läuft.
Policy-Regeln sind reine Betreiber-Konfiguration — ein
Reload-auf-jeden-Aufruf würde erlauben, dass sich das
Sicherheitsverhalten unbemerkt mitten im Betrieb ändert. Die Datei wird
einmal beim Start geparst; eine ungültige Datei lässt `mcp serve` gar
nicht erst starten (fail loud, nicht fail open).

**AE-A2 — Dateiformat** (neu entworfen, kein Spec-Vorbild):
```yaml
rules:
  - tenantId: acme            # optional, weggelassen = Wildcard
    toolName: schema_reverse_start
    effect: allow
  - toolName: data_import_start
    effect: challenge
    requiredScopes: [dmigrate:writer]
    reasons: ["writes require approval"]
  - effect: deny
    reasonCode: policy:blocked-by-operator
```
Regeln werden in Dateireihenfolge geprüft (erste passende gewinnt, wie
`ConfiguredPolicyService` es bereits intern tut).

**AE-A3 — Sicherheitshinweis statt technischer Schranke.** Eine zu
freizügige Policy-Datei (z. B. `effect: allow` ohne Filter) hebt den
fail-closed-Default vollständig auf. Das ist eine legitime, gewollte
Betriebsart (Sandbox/Demo) — keine Code-Schranke dagegen, aber ein
expliziter, unübersehbarer Doku-Hinweis (analog zur Projektkonvention
"keine stille Degradation").

### Neue/geänderte Dateien

- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/PolicyRuleFileLoader.kt` <!-- d-check:ignore (Zielbild: entsteht in AP-A1; ADR 0011) -->
  (neu) — `fun loadPolicyRules(path: Path): List<PolicyRule>`.
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/McpCommands.kt`
  — neues `--policy-file`-Flag (Pfad, optional), analog
  `--approval-grants-file` (Zeile 166-169).
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/McpServeRunner.kt`
  — `McpServeOptions.policyFile: Path?` durchreichen (analog
  `approvalGrantsFile`, Zeile 48/75).
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/McpServeWiring.kt`
  — an **beiden** `OperationalMcpWiring(...)`-Konstruktionsstellen
  (Zeile 197 und 261) `policyService = ConfiguredPolicyService(rules =
  policyFile?.let(::loadPolicyRules) ?: emptyList())` übergeben. Fehler
  beim Laden → Exit 7 (Konfigurationsfehler), analog anderen
  Config-Parse-Fehlern in diesem Pfad.
- Tests: `PolicyRuleFileLoaderTest.kt` (gültige YAML/JSON, fehlende
  optionale Felder = Wildcard, ungültiger `effect`-Wert wirft), ein
  Wiring-Test, der belegt, dass geladene Regeln tatsächlich bei
  `OperationalMcpWiring` ankommen.
- Doku: `docs/user/administrationshandbuch.md` §6.4 — neuer Abschnitt
  "Policy-Regeln konfigurieren" (Format, Beispiel, Sicherheitshinweis
  AE-A3). `spec/mcp-server.md` — normativer Abschnitt für das
  Dateiformat.

### Phasen

- **AP-A1** — `PolicyRuleFileLoader` + Unit-Tests (Parsing, Validierung).
- **AP-A2** — CLI-Wiring (`McpCommands.kt` → `McpServeRunner.kt` →
  `McpServeWiring.kt`, beide Konstruktionsstellen).
- **AP-A3** — Doku (`administrationshandbuch.md`, `spec/mcp-server.md`),
  `make docs-check`.

### Akzeptanzkriterien

- [ ] `mcp serve --policy-file rules.yaml` mit einer `Allow`-Regel für
  ein Tool lässt den zugehörigen `*_start`-Job ohne Challenge durch;
  ohne passende Regel bleibt der Default `Deny`.
- [ ] Ungültige Policy-Datei (kaputtes YAML, unbekannter `effect`-Wert,
  fehlendes `reasonCode`/`requiredScopes`) lässt `mcp serve` mit klarer
  Fehlermeldung gar nicht erst starten (Exit 7).
- [ ] Kein `--policy-file` → Verhalten unverändert (leere Regelliste,
  fail-closed) — rein additive Erweiterung, bestehende Tests bleiben
  grün.
- [ ] `make docker-check` grün für `:adapters:driving:mcp` und
  `:adapters:driving:cli`.
- [ ] `make docs-check` grün.

## Slice B — `connections/list` mit optionalem Live-Status

### Scope

Neuer MCP-Protokoll-Slot `connections/list` (Scope `dmigrate:admin`):
listet konfigurierte Verbindungen; optionaler Parameter `checkLive`
löst pro Verbindung einen echten, redigierten Verbindungstest aus.

### Architektur-Entscheidungen

**AE-B1 — Live-Check ist opt-in (`checkLive: Boolean = false`), nicht
Default.** `list` soll billig bleiben; ein Live-Connect pro Verbindung
kostet bis zu `connectionTimeoutMs`. Ein Admin, der den Live-Status
will, fragt explizit danach.

**AE-B2 — Kurzer, dedizierter Timeout für den Live-Check** (z. B.
2000 ms statt `PoolSettings`-Default 10000 ms) — ein hängender Ping darf
die Response nicht minutenlang blockieren. Sequenziell je Connection,
keine Parallelisierung in dieser Phase (Nicht-Scope).

**AE-B3 — Redaction schließt die gefundene Lücke.** Der Live-Check
liefert nur eine grobe Statuskategorie (`REACHABLE` / `UNREACHABLE` /
`CREDENTIAL_ERROR`), **keine** rohe Exception-Message — kein Host/Port/
Netzwerkdetail an den MCP-Client. Volle Fehlerdetails nur ins
Server-Log (DEBUG), analog zum bestehenden Logging-Verbot in
`ConnectionSecretResolver.kt` (Zeile 23-26 laut Faktencheck).

**AE-B4 — Kein neuer `ToolErrorCode`.** Der Status ist ein Datenfeld der
Erfolgsantwort, kein JSON-RPC-Fehler — eine nicht erreichbare Connection
ist kein Protokollfehler.

**AE-B5 — Kein Quota-Hook in dieser Phase** (Nicht-Scope). Die
`dmigrate:admin`-Scope-Prüfung ist die einzige Zugriffsschranke.
Protokoll-Slots haben aktuell generell keinen Quota-Hook in
`McpServiceImpl`; einen neu einzuführen wäre ein eigener Entwurf mit
eigenem Bedarfsnachweis.

**AE-B6 — Minimale Projektion.** Response enthält `connectionId`,
`displayName`, `dialectId`, `sensitivity`, `status` — **keine**
`credentialRef`/`providerRef`/`allowedPrincipalIds`/`allowedScopes`
(interne Config-Details, nicht für eine Admin-Liste nötig).

### Neue/geänderte Dateien

- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/protocol/McpService.kt`
  — `@JsonRequest("connections/list")`-Methode ergänzen (fehlt
  komplett).
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/ConnectionsListHandler.kt` <!-- d-check:ignore (Zielbild: entsteht in AP-B1; ADR 0011) -->
  (neu) — nutzt
  `adapters/driven/connection-config/src/main/kotlin/dev/dmigrate/connection/LoaderBackedConnectionReferenceStore.kt`
  (`.list(principal, page)`, bereits von `ResourcesListHandler`
  genutzt) für die Metadaten-Projektion; `checkLive=true` löst pro
  Connection den Live-Check aus (Resolver → Pool mit kurzem Timeout →
  `borrow()`/`close()`, Exception → redigierte Statuskategorie gemäß
  AE-B3).
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/protocol/McpServiceImpl.kt`
  — Dispatch-Methode `connectionsList`, Muster wie `resourcesList`
  (`enforceScope(...)`, Principal, Handler, JSON-RPC-Result).
- Neuer Response-DTO (kein Spec-Vorbild):
  `ConnectionsListResult(connections: List<ConnectionSummary>,
  nextCursor: String?)`, `ConnectionSummary(connectionId, displayName,
  dialectId, sensitivity, status: String?)` — `status = null` wenn
  `checkLive=false` (AE-B6).
- Tests: `ConnectionsListHandlerTest.kt` (Listing ohne/mit Live-Check,
  Fake-Pool-Factory für Erfolg/Fehlschlag, Redaction verifiziert — keine
  Exception-Message im Response), Ergänzung in
  `McpServiceImplToolsTest.kt` (Scope-Verletzung ohne `dmigrate:admin` →
  JSON-RPC-Error; `tools/list` zeigt `connections/list` weiterhin
  **nicht** an, bestehender Test in `McpContractRegistriesTest.kt:52`
  bleibt grün).
- Doku: `spec/mcp-server.md` — normativer Abschnitt für
  `connections/list` (Request/Response-Form, `checkLive`, Scope,
  Redaction-Garantie). `docs/user/anwenderhandbuch.md` §3.15 — kurzer
  Verweis.

### Nicht-Scope (dokumentiert, kein Blocker)

- Quota/Rate-Limiting für `checkLive` (Folge-Ticket bei Bedarf).
- Parallele Live-Checks (sequenziell reicht für diese Phase).
- Ein CLI-Subcommand-Spiegel (`d-migrate connections list`) — nur der
  MCP-Weg, wie angefragt.

### Phasen

- **AP-B1** — `@JsonRequest("connections/list")` im Interface +
  `ConnectionsListHandler` (nur Metadaten, `checkLive` noch nicht).
- **AP-B2** — Live-Check-Pfad (Resolver → kurzer Timeout → Pool →
  redigierte Statuskategorie).
- **AP-B3** — Dispatch in `McpServiceImpl` + Scope-Durchsetzung + Tests.
- **AP-B4** — Doku (`spec/mcp-server.md`, `anwenderhandbuch.md`), `make
  docs-check`.

### Akzeptanzkriterien

- [ ] `connections/list` (ohne `checkLive`) liefert die konfigurierten
  Verbindungen ohne Live-Connect, schnell.
- [ ] `connections/list` mit `checkLive: true` liefert pro Connection
  einen Status; eine absichtlich falsch konfigurierte Verbindung liefert
  `UNREACHABLE`/`CREDENTIAL_ERROR`, nie eine rohe Exception-Message.
- [ ] Aufruf ohne `dmigrate:admin`-Scope → JSON-RPC-Scope-Fehler, keine
  Daten.
- [ ] `tools/list` projiziert `connections/list` weiterhin nicht
  (bestehender Test bleibt grün).
- [ ] `make docker-check` grün für `:adapters:driving:mcp` **und**
  einmal ohne `MODULES` (geteilte `McpService`-Interface-Änderung).
- [ ] `make docs-check` grün.

## Verifikation (beide Slices)

1. `make docker-check MODULES=":adapters:driving:mcp"` und
   `MODULES=":adapters:driving:cli"` je nach Slice.
2. Einmal `make docker-check` ohne `MODULES` (geteilte
   `McpService`-Interface-Änderung in Slice B).
3. `make docs-check` nach den Doku-Änderungen.
4. `make solid-suppression-gate` vor jedem Commit.
5. Manueller Smoke: `mcp serve --policy-file <datei>` lokal starten,
   einen Tool-Call gegen eine per Regel erlaubte Kombination fahren
   (kein `POLICY_REQUIRED`); für Slice B einen `connections/list
   checkLive:true`-Aufruf gegen eine echte (SQLite-)Verbindung und eine
   absichtlich kaputte URL, Redaction der Fehlermeldung prüfen.

## Referenzen

- `spec/mcp-server.md` — MCP-Contract (Zielbild, wird um beide neuen
  Abschnitte ergänzt).
- `docs/user/administrationshandbuch.md` §6.4 — bestehender
  Grant-Issuer-Abschnitt, wird um Policy-Regel-Konfiguration ergänzt.
- `docs/planning/done/security-audit-2026-07-17.md` — bewertet den
  Policy-Mechanismus grundsätzlich als fail-safe, adressiert aber nicht
  das Fehlen eines Konfigurationswegs.
