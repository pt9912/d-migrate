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
> **Review-Nachzug (2026-09-04):** unabhängiger Codebase-Review vor
> Implementierungsstart fand drei blockierende Lücken (Exit-Code-Konvention
> von `mcp serve` ist 2, nicht 7; das genannte Grant-Store-"Vorbild" hat
> tatsächlich keine startup-taugliche Fehlerbehandlung; `connections/list`
> hätte ohne Korrektur nie mehr als die eigene Tenant-Sicht des Callers
> gezeigt, obwohl das der Sinn des Admin-Scopes ist) sowie zwei wichtige
> (Cursor muss HMAC-versiegelt sein; Pool-Dimensionierung für den
> Live-Check). Alle fünf unten in AE-A4 sowie AE-B7 bis AE-B10 aufgelöst.

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

**AE-A4 — Exit-Code und Fehlerbehandlung (Review-Korrektur).** `mcp
serve` mappt **alle** Config-Parse-Fehler auf `McpServeExit(2)`
(`adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/McpServeRunner.kt`,
14 Fundstellen, z. B. Zeile 160/175/185/207/216/224/233/238/272/281/290/428
— Retention-, Artifacts-, Cursor-Keyring-, State-Dir-, Lock-Parsing),
**nicht** Exit 7 (das ist die Konvention anderer CLI-Befehle wie
`NamedConnectionResolver`/`SchemaCompareWiring`, nicht von `mcp serve`
selbst). Ein Policy-Datei-Parse-Fehler mappt deshalb ebenfalls auf
`McpServeExit(2)`, analog `parseArtifactsConfigOrExit()`/
`parseCursorKeyringOrExit()`.

Zusätzlich: `FileBackedApprovalGrantStore` ist **kein** brauchbares
Fehlerbehandlungs-Vorbild — es parst lazy pro Lookup (nicht einmalig
beim Start) und wirft bei Fehlern nur ungefangene `error(...)`/
`require(...)`; `McpServeWiring.approvalGrantStore()` (Zeile 282-283)
konstruiert nur, liest nie. AE-A1 (einmaliges Laden, fail loud) braucht
deshalb eine neue Fehlerbehandlung.

**Umsetzungs-Ort (Design-Delta gegenüber der ersten Fassung dieser
AE):** nicht in `McpServeRunner.kt` — die anderen `parse*OrExit()`-
Methoden dort laufen zwar in `doExecute()`, aber der `launcher`-
Konstruktorparameter von `McpServeRunner` baut `McpServeWiring(...)`
bereits als **Default-Parameter-Ausdruck**, der bei der
Objekt-Konstruktion ausgewertet wird — **vor** `execute()`/`doExecute()`.
Eine dort geworfene `McpServeExit` würde `execute()`s
`try { doExecute() } catch (e: McpServeExit)` gar nicht erreichen. Die
Policy-Datei wird deshalb in `McpServeWiring.build()` selbst geladen
(private `loadPolicyRulesOrExit()`, ganz am Anfang von `build()` — läuft
also weiterhin vor jedem tatsächlichen Request, nur eine Ebene tiefer
als ursprünglich geplant) und dort in `try/catch` auf `McpServeExit(2)`
gemappt; `McpServeExit` ist `internal` im selben Package
(`dev.dmigrate.cli.commands`), direkt referenzierbar.

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
  — neuer Konstruktorparameter `policyFile: Path?`; neue private
  `loadPolicyRulesOrExit()` am Anfang von `build()` (AE-A4); an
  **beiden** `OperationalMcpWiring(...)`-Konstruktionsstellen (Zeile 197
  und 261, letztere über `buildInMemory(..., policyRules)`)
  `policyService = ConfiguredPolicyService(rules = policyRules)`
  übergeben.
- Tests: `PolicyRuleFileLoaderTest.kt` (gültige YAML/JSON, fehlende
  optionale Felder = Wildcard, ungültiger `effect`-Wert wirft), ein
  Wiring-Test, der belegt, dass geladene Regeln tatsächlich bei
  `OperationalMcpWiring` ankommen.
- Doku: `docs/user/administrationshandbuch.md` §6.7 "Policy-gesteuerte
  Datenoperationen" (bereits vorhanden, Zeile 365 — passenderer Anker
  als §6.4/Grant-Issuer) — neuer Unterabschnitt "Policy-Regeln
  konfigurieren" (Format, Beispiel, Sicherheitshinweis AE-A3).
  `spec/mcp-server.md` — normativer Abschnitt für das Dateiformat.

### Phasen

- **AP-A1** — `PolicyRuleFileLoader` + Unit-Tests (Parsing, Validierung).
- **AP-A2** — CLI-Wiring (`McpCommands.kt` → `McpServeRunner.kt` →
  `McpServeWiring.kt`, beide Konstruktionsstellen).
- **AP-A3** — Doku (`administrationshandbuch.md`, `spec/mcp-server.md`),
  `make docs-check`.

### Akzeptanzkriterien

- [x] `mcp serve --policy-file rules.yaml` mit einer `Allow`-Regel für
  ein Tool lässt den zugehörigen `*_start`-Job ohne Challenge durch;
  ohne passende Regel bleibt der Default `Deny` (Test: „--policy-file
  rules flow into OperationalMcpWiring.policyService", verifiziert über
  einen echten `policyService.decide(...)`-Aufruf).
- [x] Ungültige Policy-Datei (kaputtes YAML, unbekannter `effect`-Wert,
  fehlendes `reasonCode`/`requiredScopes`) lässt `mcp serve` mit klarer
  Fehlermeldung gar nicht erst starten (`McpServeExit(2)`, AE-A4; Test:
  „invalid --policy-file exits 2 with stderr message").
- [x] Kein `--policy-file` → Verhalten unverändert (leere Regelliste,
  fail-closed) — rein additive Erweiterung, bestehende Tests bleiben
  grün.
- [x] `make docker-check` grün für `:adapters:driving:mcp` und
  `:adapters:driving:cli` **und** einmal ohne `MODULES`.
- [x] `make docs-check` grün.

**Geliefert** (Code): `PolicyRuleFileLoader.kt` + Test (`adapters/driving/mcp`);
`--policy-file`-Flag (`McpCommands.kt`), `McpServeOptions.policyFile`
(`McpServeRunner.kt`), `loadPolicyRulesOrExit()` + zwei neue Wiring-Tests
(`McpServeWiring.kt`/`McpServeWiringTest.kt`, `adapters/driving/cli`).
Doku: `administrationshandbuch.md` §6.7, `anwenderhandbuch.md` Anhang
A.13, `api-referenz.md` §4.12, `spec/mcp-server.md`
„Policy-Regeln konfigurieren" (neuer normativer Abschnitt).

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

**AE-B7 — Multi-Tenancy (Review-Korrektur, war im Entwurf übersehen).**
`LoaderBackedConnectionReferenceStore.list(principal, page)` filtert
hart auf `principal.effectiveTenantId` — ohne Weiteres sähe ein Admin
nur die eigene Tenant-Sicht. `spec/ki-mcp.md:880` sagt aber explizit,
`dmigrate:admin` sei „nur für Cross-Tenant- oder fremde administrative
Aktionen erforderlich" — der ganze Sinn des Admin-Scopes für diese
Methode wäre sonst hinfällig. Auflösung: `ConnectionsListHandler`
übernimmt exakt das bestehende Muster der anderen `*_list`-Handler
(`ListToolHelpers.resolveTenant(args, principal)`,
`adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/ListToolHelpers.kt`) —
optionaler `tenantId`-Parameter, validiert gegen
`principal.allowedTenantIds`, sonst `TenantScopeDeniedException`. Kein
Cross-Tenant-Fanout in einem Aufruf (wie bei den anderen List-Tools
auch) — aber ein Admin kann gezielt jede erlaubte Tenant-Sicht
adressieren, nicht nur die eigene.

**AE-B8 — Cursor muss HMAC-versiegelt sein (Review-Korrektur).** Der
Entwurf definierte `nextCursor: String?` als rohen String. Es gibt aber
ein etabliertes, zwingend zu übernehmendes Muster:
`McpCursorCodec`/`CursorKeyring`
(`adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/cursor/McpCursorCodec.kt`)
plus ein pro-Familie-Wrapper wie
`adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/resources/SealedResourcesListCursor.kt`
oder
`adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/SealedListToolCursor.kt`.
Ein ungesiegelter Cursor wäre eine Inkonsistenz zu beiden Präzedenzfällen
und clientseitig manipulierbar. `ConnectionsListHandler` bekommt einen
neuen `SealedConnectionsListCursor`-Wrapper nach demselben Muster.

**AE-B9 — Pool-Dimensionierung für den Live-Check.** `PoolSettings`
(`hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/connection/PoolSettings.kt`)
defaultet auf
`maximumPoolSize=10, minimumIdle=2` — für einen reinen
Reachability-Check baut das unnötig einen vollen Pool inkl.
Housekeeper-Thread und zwei eifrig aufgebauten Idle-Connections auf.
Der Live-Check überschreibt **die gesamte** `PoolSettings`, nicht nur
den Timeout: `PoolSettings(maximumPoolSize = 1, minimumIdle = 0,
connectionTimeoutMs = 2000)`.

**AE-B10 — Testbare Pool-Erzeugung (Review-Korrektur).**
`HikariConnectionPoolFactory` ist ein Kotlin-`object` (Singleton), direkt
aufgerufen in `McpCoreJobWorkerFactory.kt` — keine
`ConnectionPoolFactory`-Interface-Abstraktion existiert. `Connections
ListHandler` nimmt die Pool-Erzeugung deshalb als injizierbare Funktion
entgegen (`poolFactory: (ConnectionConfig) -> ConnectionPool =
HikariConnectionPoolFactory::create`), exakt der Funktions-Injektions-
Stil, den die CLI-Wiring-Schicht bereits durchgängig für Runner nutzt
(z. B. `DataSeedRunner`) — kein neues Interface, aber explizit als
neue Testinjektionsstelle in „Neue/geänderte Dateien" zu führen, nicht
stillschweigend vorausgesetzt.

**AE-B11 — `isReadableBy` ist bereits abgedeckt.**
`LoaderBackedConnectionReferenceStore.list()` filtert schon auf
`allowedPrincipalIds`/Sichtbarkeit — eine nicht lesbare Connection
erscheint gar nicht erst in der Liste. Kein zusätzlicher Check nötig,
aber explizit als Begründung festgehalten (nicht implizit vorausgesetzt).

### Neue/geänderte Dateien

- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/protocol/McpService.kt`
  — `@JsonRequest("connections/list")`-Methode ergänzen (fehlt
  komplett).
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/ConnectionsListHandler.kt` <!-- d-check:ignore (Zielbild: entsteht in AP-B1; ADR 0011) -->
  (neu) — nutzt
  `adapters/driven/connection-config/src/main/kotlin/dev/dmigrate/connection/LoaderBackedConnectionReferenceStore.kt`
  (`.list(principal, page)`, bereits von `ResourcesListHandler`
  genutzt) für die Metadaten-Projektion, plus
  `ListToolHelpers.resolveTenant` für den optionalen `tenantId`-Parameter
  (AE-B7); `checkLive=true` löst pro Connection den Live-Check aus
  (Resolver → injizierbarer `poolFactory` mit AE-B9-`PoolSettings` →
  `borrow()`/`close()`, Exception → redigierte Statuskategorie gemäß
  AE-B3). Konstruktor nimmt `poolFactory: (ConnectionConfig) ->
  ConnectionPool` entgegen (AE-B10).
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/SealedConnectionsListCursor.kt` <!-- d-check:ignore (Zielbild: entsteht in AP-B1; ADR 0011) -->
  (neu) — HMAC-Sealing des `nextCursor` über `McpCursorCodec`, Muster wie
  `SealedListToolCursor`/`SealedResourcesListCursor` (AE-B8).
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/protocol/McpServiceImpl.kt`
  — Dispatch-Methode `connectionsList`, Muster wie `resourcesList`
  (`enforceScope(...)`, Principal, Handler, JSON-RPC-Result).
- Neuer Response-DTO (kein Spec-Vorbild):
  `ConnectionsListResult(connections: List<ConnectionSummary>,
  nextCursor: String?)` (versiegelt, AE-B8), `ConnectionSummary
  (connectionId, displayName, dialectId, sensitivity, status: String?)`
  — `status = null` wenn `checkLive=false` (AE-B6).
- Tests: `ConnectionsListHandlerTest.kt` (Listing ohne/mit Live-Check via
  injiziertem Fake-`poolFactory` für Erfolg/Fehlschlag, Redaction
  verifiziert — keine Exception-Message im Response, Tenant-Scope-
  Verletzung → `TenantScopeDeniedException`), Ergänzung in
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
  `ConnectionsListHandler` (Metadaten, `tenantId`-Parameter per
  `ListToolHelpers.resolveTenant`, `SealedConnectionsListCursor`;
  `checkLive` noch nicht).
- **AP-B2** — Live-Check-Pfad: injizierbarer `poolFactory`, AE-B9-
  `PoolSettings`, Resolver → Pool → `borrow()`/`close()` → redigierte
  Statuskategorie.
- **AP-B3** — Dispatch in `McpServiceImpl` + Scope-Durchsetzung + Tests
  (inkl. Tenant-Scope- und Cursor-Fälschungs-Fälle).
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
- [ ] Ein Admin kann per `tenantId`-Parameter gezielt jede in
  `allowedTenantIds` erlaubte Tenant-Sicht abfragen (nicht nur die
  eigene); ein nicht erlaubter `tenantId`-Wert → `TenantScopeDeniedException`
  (AE-B7).
- [ ] `nextCursor` ist HMAC-versiegelt (`McpCursorCodec`), nicht roh
  manipulierbar (AE-B8).
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
- `docs/user/administrationshandbuch.md` §6.7 "Policy-gesteuerte
  Datenoperationen" — bestehender Abschnitt, wird um die
  Datei-Konfiguration ergänzt.
- `docs/planning/done/security-audit-2026-07-17.md` — bewertet den
  Policy-Mechanismus grundsätzlich als fail-safe, adressiert aber nicht
  das Fehlen eines Konfigurationswegs.
