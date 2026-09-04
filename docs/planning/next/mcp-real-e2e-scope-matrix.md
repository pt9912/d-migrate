# MCP: Echte E2E-Absicherung der Scope-Matrix (`connections/list` + Rest)

> **Status:** Draft, bereit zur Umsetzung (2026-09-04). Ausgelöst durch eine
> manuelle Live-Prüfung: `connections/list` (Slice B von
> [`ImpPlan-1.2.0-mcp-policy-file-and-connections-list.md`](../done/ImpPlan-1.2.0-mcp-policy-file-and-connections-list.md))
> wurde als "nicht erreichbar" gemeldet — tatsächlich war der Code korrekt
> verdrahtet (per Live-JSON-RPC-Aufruf gegen das echte `d-migrate:dev`-Image
> verifiziert), das benutzte Sandbox-Token hatte nur nicht den
> `dmigrate:admin`-Scope. Die eigentliche Lücke: **kein Test — weder
> In-Process noch Prozess-/Image-Ebene — beweist heute, dass Scope-Denial
> für irgendein Tool tatsächlich funktioniert.** Alle bestehenden
> Testprinzipien sind hart auf `isAdmin: true`/volle Scopes verdrahtet
> (`IntegrationFixtures.INTEGRATION_PRINCIPAL`).
> **Review-Korrektur (2026-09-04):** die ursprüngliche Risikobegründung war
> für den größeren Teil der Fläche falsch gerahmt — für die 19 als
> `tools/call`-Namen dispatchten Einträge gibt es sehr wohl ein zentrales
> Gate (`McpServiceImpl.kt:261`), das ein neuer Tool-Handler nicht vergessen
> kann. Der reale Wert dieses Slices ist nicht "eine Architekturlücke
> schließen", sondern **beide bestehenden, korrekten Gates (das zentrale
> `tools/call`-Gate und die sieben individuellen
> `enforceScope(...)`-Aufrufe der JSON-RPC-Methoden) erstmals durch einen
> Test beweisen** — unabhängig richtig verdrahtet heißt nicht ungeprüft
> richtig. Details in Teil A.
> **Vorbedingung:** Keine harte Blockade.

## Kontext / Ist-Stand (verifiziert)

- **Scope-Mapping-Autorität**:
  `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/server/McpServerConfig.kt`
  (`buildDefaultScopeMapping()`, `DEFAULT_SCOPE_MAPPING`, Zeile 56) — **sieben**
  Scopes über 26 Einträge: `dmigrate:read` (19 Einträge, u. a.
  `tools/list`/`resources/list`/`resources/read`/`resources/templates/list`/
  `prompts/list`/`prompts/get` sowie 13 Tool-Namen wie `schema_validate`,
  `job_list`, `artifact_list`), `dmigrate:job:start`, `dmigrate:job:cancel`,
  `dmigrate:data:write`, `dmigrate:artifact:upload`, `dmigrate:ai:execute`,
  `dmigrate:admin` (nur `connections/list`).
- **Zwei verschiedene Enforcement-Mechanismen — nicht einer** (Review-Korrektur;
  wichtig für Teil As Assertion-Form, siehe AE-A3):
  1. **JSON-RPC-Protokollmethoden** (7 Einträge: `tools/list`, `resources/list`,
     `resources/read`, `resources/templates/list`, `prompts/list`,
     `prompts/get`, `connections/list`) — jeweils ein eigener
     `enforceScope(method)`-Aufruf im jeweiligen Dispatch-Zweig
     (`McpServiceImpl.kt:719`, exakt an dieser Zeile verifiziert). Wirft bei
     fehlendem Scope einen `ResponseErrorException` mit
     `ResponseErrorCode.InvalidRequest` und der Nachricht `"principal lacks
     required scope(s) for '<method>': [<scopes>]"` — ein echter
     JSON-RPC-Protokollfehler. Hier gibt es tatsächlich **keine zentrale
     Vor-Dispatch-Prüfung**; ein neuer Protokollmethoden-Handler könnte den
     Aufruf theoretisch vergessen.
  2. **Tool-Namen** (die übrigen 19 Einträge, dispatcht über den einzigen
     `tools/call`-Handler, `McpServiceImpl.kt:233-277`) — **ein zentrales
     Gate** (`scopeViolation(params.name, resolvedPrincipal)` an Zeile 261,
     läuft vor jedem Tool-Dispatch). Bei fehlendem Scope wirft es eine
     `ForbiddenPrincipalException`
     (`hexagon/application/src/main/kotlin/dev/dmigrate/server/application/error/ApplicationException.kt:32`),
     die auf ein
     `ToolsCallResult(isError = true)` mit `ToolErrorCode.FORBIDDEN_PRINCIPAL`
     und Detail-`reason` `"missing scope(s): [...]"` gemappt wird — eine
     JSON-RPC-**Erfolgs**-Hülle, die einen Tool-Fehler trägt, nicht ein
     Protokollfehler. Ein neues Tool kann dieses Gate nicht umgehen, sobald es
     in `DEFAULT_SCOPE_MAPPING` registriert ist.
- **Bestehende Testebenen** (beide bereits vorhanden, siehe
  [`ImpPlan-1.2.0-...`](../done/ImpPlan-1.2.0-mcp-policy-file-and-connections-list.md)-Recherche
  von heute):
  1. **In-Process-Szenario-Tests**
     (`test/e2e-cli/src/test/kotlin/dev/dmigrate/cli/integration/Mcp*ScenarioTest.kt`)
     — laufen über `StdioHarness`/`HttpHarness`
     (`test/e2e-cli/src/test/kotlin/dev/dmigrate/cli/integration/StdioHarness.kt`,
     `HttpHarness.kt`): echter `McpServerBootstrap.startStdio`/`startHttp` in
     einem Daemon-Thread **derselben JVM**, verbunden über
     `PipedInputStream`/`PipedOutputStream`. Testet die echte
     JSON-RPC-Dispatch-/Serialisierungsschicht, aber **keinen separaten
     Prozess**.
  2. **Echter Subprozess**: `McpRealCliSubprocessTest.kt` (gleiches
     Verzeichnis) spawnt `java -cp <Testklassenpfad> dev.dmigrate.cli.MainKt`
     als echten Kind-Prozess über die `CliSubprocess`-Klasse
     (`RealCliSubprocess.kt`, Launcher `startRealCliSubprocess(...)`) —
     durchläuft `McpCommand.run()` → `StateDirOwner` → `McpStateDirLock` →
     `McpServerLifecycle` mit der **echten, dateibasierten Produktions-
     verdrahtung** (`McpCliRuntimeWiring.runtimeWiring(stateDir)`, nicht die
     In-Memory-Fixtures von Ebene 1). Unterstützt bereits einen
     `DMIGRATE_CLI_BIN`-Hebel, um statt der Kind-JVM das echte
     GraalVM-Native-Binary zu testen (siehe
     [`native-e2e-regression-gate.md`](native-e2e-regression-gate.md), Status
     Draft, noch nicht CI-verdrahtet — dieser Slice tastet das Dokument
     nicht an). **Wichtig: der bestehende Test authentifiziert nie** — er
     sendet nur `initialize` (auth-exempt, `ScopeChecker.kt:23-26`) und
     `notifications/initialized`, nie einen Scope-geprüften Aufruf.
  3. **Beide Ebenen sind gated**: `test/e2e-cli` läuft nur mit
     `-PintegrationTests` (Kotest `integration`-Tag,
     `test/e2e-cli/build.gradle.kts`) — nicht Teil von
     `make docker-check` ohne diese Property.
- **Zwei inkompatible Token-Mechanismen — Review-Korrektur, betrifft AE-A4
  direkt**: `IntegrationFixtures.kt` (`INTEGRATION_PRINCIPAL`,
  `freshTransportPrincipal(transport)`) + `StubStdioTokenStore.kt`
  (`forPrincipal(principal, rawToken)`, SHA-256-Fingerprint **in-memory**)
  sind **ausschließlich an `StdioHarness.kt:253` verdrahtet** (Ebene 1,
  In-Process) — `RealCliSubprocess.kt` (Klasse `CliSubprocess`, Launcher
  `startRealCliSubprocess`) und `McpRealCliSubprocessTest.kt` referenzieren
  sie nirgends. Der echte Subprozess authentifiziert stattdessen über die
  **Produktionsverdrahtung**: eine `--stdio-token-file <pfad>`-Option
  (`McpCommands.kt:126-128`, `McpServeRunner.kt:43,153`), die eine
  YAML-Datei lädt (`FileStdioTokenStore.kt:24-34`, Form: `tokens: [{fingerprint
  (SHA-256-Hex), principalId, tenantId, scopes, isAdmin, auditSubject,
  expiresAt}]` — exakt das Format, das die manuelle Live-Prüfung heute von
  Hand gebaut hat), plus einen rohen Token **pro Subprozess-Umgebung** über
  `DMIGRATE_MCP_STDIO_TOKEN` (`StdioPrincipalResolver.kt:30-34,57-60`) — der
  Prinzipal wird **einmal beim Prozessstart** aus diesem Env-Var aufgelöst,
  nicht pro Request neu verhandelbar. Teil A braucht also einen **neuen**
  Baustein, nicht nur eine zusätzliche Fabrikfunktion auf den bestehenden
  Fixtures (Details AE-A4).
- **Fehlt komplett**: ein Test gegen das gebaute **Docker-Runtime-Image**
  (`d-migrate:dev`). Weder Ebene 1 noch Ebene 2 fasst das Image an — beide
  laufen gegen den JVM-Testklassenpfad. Das im Projekt etablierte Muster
  dafür ist **nicht** ein Gradle-Testmodul/Testcontainers, sondern
  `examples/sample-db/` und `examples/bi-demo/`: docker-compose + Bash-Skripte
  gegen das lokal gebaute `d-migrate:dev`-Image, "kein Testcontainers, kein
  Gradle-Testmodul. Läuft lokal und in CI" (`examples/sample-db/README.md`).
  Eigener `make/*.mk`-Satz, eigener (meist `continue-on-error: true`,
  Best-Effort) CI-Workflow pro Smoke (`.github/workflows/sample-db-smoke.yml`
  als Vorbild).
- **Warum nicht Testcontainers `GenericContainer`**: Testcontainers ist bereits
  Dependency in `test:e2e-cli` (`testcontainers-postgresql`/`-mysql`/
  `-mssqlserver`), aber `GenericContainer` ist HTTP/TCP-orientiert
  (`Wait.forHttp`, `withExposedPorts`) — kein First-Class-Stdin/Stdout-Pumping
  für einen PID-1-Vordergrundprozess. Für `--transport stdio` bräuchte man
  rohe `docker run -i`-Prozesssteuerung, die mit der Library kämpft statt sie
  zu nutzen. Passt daher nicht zum Docker-Image-Slice unten (der bewusst
  Bash+NDJSON statt Testcontainers nutzt, analog `examples/sample-db/`).

## Scope

Zwei unabhängig lieferbare, sich ergänzende Teile:

- **Teil A** — Gradle-/JVM-Ebene: schließt die Test-Lücke "kein Beweis, dass
  Scope-Denial funktioniert" für **alle** Einträge in
  `DEFAULT_SCOPE_MAPPING`, nicht nur `connections/list`. Läuft mit
  `-PintegrationTests`, Teil des normalen `test/e2e-cli`-Moduls, kein neues
  Modul, keine neue Infrastruktur.
- **Teil B** — Docker-Image-Ebene: neuer `examples/mcp-e2e/`-Harness <!-- d-check:ignore (Zielbild: entsteht in Teil B; ADR 0011) --> nach dem
  `sample-db`/`bi-demo`-Muster, der `connections/list` (inkl. `checkLive`
  gegen eine echte PG-Verbindung) und mindestens einen Vertreter pro Scope
  gegen das **echte gebaute Runtime-Image** durchspielt — genau die Ebene,
  die die ursprüngliche "Info"-Meldung eigentlich hätte automatisiert prüfen
  sollen.

## Teil A — Scope-Enforcement-Matrix in `test/e2e-cli`

### Architektur-Entscheidungen

**AE-A1 — Matrix wird aus `DEFAULT_SCOPE_MAPPING` generiert, nicht
handkopiert.** Eine von Hand gepflegte Tool-Liste im Test würde exakt das
Drift-Risiko reproduzieren, das
`McpToolMatrix.kt` (`test/e2e-cli/src/test/kotlin/dev/dmigrate/cli/integration/McpToolMatrix.kt`)
für `tools/list` bereits einmal gelöst hat. Der neue Test iteriert
`McpServerConfig.DEFAULT_SCOPE_MAPPING.entries` direkt — ein neuer Tool-/
Scope-Eintrag ist automatisch erfasst, ohne den Test anzufassen. Die Matrix
klassifiziert jeden Eintrag beim Iterieren selbst in einen von zwei Zweigen
(AE-A3) — anhand einer festen Menge der sieben Protokollmethodennamen, alles
andere ist ein Tool-Name.

**AE-A2 — Positiv-Fall prüft NUR "keine Scope-Ablehnung", nicht vollen
Geschäftserfolg.** Für `dmigrate:data:write`/`dmigrate:ai:execute`-Tools
einen vollen erfolgreichen Lauf zu erzwingen bräuchte echte DB-/AI-Backends
für jeden Bucket — das ist nicht das Ziel dieses Slices (der ist Scope-
Gating, keine Funktionsabdeckung). Positiv-Fall: Aufruf mit einem Prinzipal,
der den nötigen Scope trägt, plus bewusst unvollständigen/synthetischen
Argumenten. **Review-Ergänzung:** für die fünf `*_start`-Tools
(`schema_reverse_start`, `schema_compare_start`, `data_profile_start`,
`data_import_start`, `data_transfer_start`) greift zusätzlich
`PolicyService` mit fail-closed-Default (`ConfiguredPolicyService.kt:10`,
`Deny("policy:no-rule")` ohne `--policy-file`) — der Positiv-Fall-Lauf ohne
Policy-Datei bekommt dort einen `POLICY_DENIED`-Fehler statt echten
Erfolgs. Das ist für dieses Slice **unschädlich** (Scope-Check läuft vor der
Policy-Prüfung, siehe `McpServiceImpl.kt:261` vs. Policy-Dispatch danach —
ein `POLICY_DENIED` beweist also weiterhin, dass die Scope-Prüfung
durchgelassen hat), muss aber in der Assertion sauber unterschieden werden
von einer Scope-Ablehnung, nicht als "beliebiger Erfolg" missverstanden
werden.

**AE-A3 — Negativ-Fall ist der eigentliche Wert, mit ZWEI Assertion-Formen
(Review-Korrektur).** Aufruf mit einem Prinzipal, der **keinen** passenden
Scope trägt (leeres `scopes`-Set, `isAdmin = false`) — Assertion abhängig
vom Klassifizierungszweig aus AE-A1:
- **Protokollmethoden** (`tools/list`, `resources/list`, `resources/read`,
  `resources/templates/list`, `prompts/list`, `prompts/get`,
  `connections/list`): JSON-RPC-Fehlerobjekt, `error.code == -32600`
  (`InvalidRequest`), `error.message` enthält `"lacks required scope(s) for
  '<method>'"` und den erwarteten Scope-Namen.
- **Tool-Namen** (die übrigen 19 Einträge, via `tools/call`): JSON-RPC-
  **Erfolgs**-Antwort mit `result.isError == true`, Envelope-`code ==
  "FORBIDDEN_PRINCIPAL"`, Detail-`reason` enthält `"missing scope(s)"` und
  den erwarteten Scope-Namen.

Das ist der Test, der heute komplett fehlt — für **beide** Formen.

**AE-A4 — Neue Datei-Token-Fixture statt Wiederverwendung von
`StubStdioTokenStore` (Review-Korrektur).** Da der Real-Subprozess über
`--stdio-token-file` + `DMIGRATE_MCP_STDIO_TOKEN` authentifiziert (siehe
Kontext oben), braucht Teil A einen neuen Helfer, der eine temporäre
`stdio-tokens.yaml`-Datei mit **zwei** Einträgen schreibt: einem Prinzipal
mit der **Vereinigung aller sieben Scopes** (nicht `isAdmin = true` — der
Admin-Bypass in `ScopeChecker` würde jede Scope-spezifische Verdrahtung
umgehen, ohne sie zu beweisen) für den Positiv-Fall, und einem Prinzipal mit
`scopes = emptySet()`/`isAdmin = false` für den Negativ-Fall. Weil beide
Prinzipale in **derselben** Datei stehen und `DMIGRATE_MCP_STDIO_TOKEN` pro
Subprozess-Umgebungsvariable exakt einen Token wählt, braucht die Matrix nur
**zwei** Subprozess-Starts insgesamt (einer je Prinzipal), nicht einen pro
Scope/Tool-Kombination — der Positiv-Subprozess durchläuft alle 26 Einträge
in einer Session (ein Aufruf pro Eintrag, `notifications/initialized`
dazwischen nicht nötig), ebenso der Negativ-Subprozess.

### Neue/geänderte Dateien

- `test/e2e-cli/src/test/kotlin/dev/dmigrate/cli/integration/McpScopeEnforcementMatrixTest.kt` <!-- d-check:ignore (Zielbild: entsteht in Teil A; ADR 0011) -->
  (neu) — die Matrix aus AE-A1/AE-A2/AE-A3, gegen `startRealCliSubprocess`
  (analog `McpRealCliSubprocessTest.kt`), Stdio-Transport (HTTP als Stretch,
  falls Zeit reicht — kein Blocker für diesen Slice).
- `test/e2e-cli/src/test/kotlin/dev/dmigrate/cli/integration/ScopeMatrixTokenFile.kt` <!-- d-check:ignore (Zielbild: entsteht in Teil A; ADR 0011) -->
  (neu) — AE-A4s Datei-Fixture: schreibt eine temporäre `stdio-tokens.yaml`
  mit Voll-Scope- und Null-Scope-Prinzipal, liefert beide Rohtoken zurück.
  **Kein** `IntegrationFixtures`-/`StubStdioTokenStore`-Bezug (der ist für
  Ebene 1 reserviert und für den Subprozess nicht nutzbar).

### Akzeptanzkriterien

- [ ] Für jeden der sieben Scopes: ein Prinzipal **mit** diesem Scope (im
      Voll-Scope-Token aus AE-A4) löst bei mindestens einem zugeordneten
      Eintrag keine Scope-Ablehnung aus — für `*_start`-Tools zählt ein
      `POLICY_DENIED`-Ergebnis explizit als "keine Scope-Ablehnung" (AE-A2).
- [ ] Für jeden der sieben Scopes: der Null-Scope-Prinzipal aus AE-A4 bekommt
      für mindestens einen zugeordneten Eintrag die AE-A3-passende
      Ablehnungsform (Protokollmethode → `InvalidRequest`/Message-Substring;
      Tool-Name → `ToolsCallResult.isError`/`FORBIDDEN_PRINCIPAL`) mit dem
      korrekten Scope-Namen.
- [ ] `connections/list` ist explizit Teil der Matrix (schließt die
      auslösende Lücke) — Protokollmethoden-Form.
- [ ] Mindestens ein Tool-Namen-Eintrag pro der fünf Scopes, die nur
      Tool-Namen mappen (`dmigrate:job:start`, `dmigrate:job:cancel`,
      `dmigrate:data:write`, `dmigrate:artifact:upload`,
      `dmigrate:ai:execute`), beweist die Tool-Namen-Ablehnungsform.
- [ ] `make integration INTEGRATION_TASKS=":test:e2e-cli:test --tests '*McpScopeEnforcementMatrixTest*'"`
      grün.
- [ ] Kein neues Gradle-Modul; genau ein neuer Fixture-Baustein
      (AE-A4/`ScopeMatrixTokenFile.kt`).

## Teil B — Docker-Image-Harness (`examples/mcp-e2e/` <!-- d-check:ignore (Zielbild: entsteht in Teil B; ADR 0011) -->)

### Architektur-Entscheidungen

**AE-B1 — Struktur spiegelt `examples/sample-db/` 1:1.** Eigenes
`docker-compose.yml` (Service `dmigrate` = `${MCP_E2E_DMIGRATE_IMAGE:-d-migrate:dev}`,
Service `postgres` für einen echten `connections/list?checkLive=true`-Beleg),
`.env.example`, `scripts/smoke-*.sh`, `stdio-tokens.yaml` (zwei Einträge:
`admin` mit `dmigrate:admin` + `isAdmin: true`, `restricted` mit
`dmigrate:read` — kein Scope-freier Eintrag nötig, `restricted` reicht als
Negativ-Fall für `connections/list`). Kein Testcontainers, kein
Gradle-Testmodul (Kontext-Begründung oben).

**AE-B2 — Bash-NDJSON statt Python-Probe.** Mein manueller Beleg heute nutzte
Python für Kontrolle/Timeouts; das Projekt-Idiom ist reines Bash+`jq`
(einzige neue Host-Voraussetzung: `jq`, bereits Voraussetzung für
`bi-demo`). Requests werden als NDJSON-Zeilen in `docker compose run --rm -T
dmigrate mcp serve --transport stdio ...` gepiped; das Skript schließt
`stdin` nach der letzten Zeile (Pipe-EOF), der Server beendet sich sauber
(exakt das Muster aus `McpRealCliSubprocessTest`, nur ohne interaktives
Timing — die Requests sind vorab bekannt, keine Zwischenauswertung nötig).

**AE-B3 — Best-Effort-CI, kein Hard-Gate.** Analog
`sample-db-smoke.yml`/`bi-demo-smoke.yml`: `continue-on-error: true`,
`workflow_dispatch` + `push` auf `main` mit Pfad-Filter auf
`examples/mcp-e2e/**`. Grund: Docker-Hub-/Netzwerk-Flakiness soll den
Hauptbuild nicht rot machen — dieselbe Begründung wie bei den bestehenden
Sample-DB-Workflows.

**AE-B4 — `connections/list` mit `checkLive=true` gegen echten Postgres.**
Einziger inhaltlicher Unterschied zu Teil A: Teil B kann (und soll) den
tatsächlichen Live-Check gegen eine echte DB beweisen — dafür der
`postgres`-Service plus ein `.d-migrate.yaml` mit einer echten
`sandbox_pg`-Connection (analog dem manuellen Sandbox-Setup von heute).
`connections/list` ist eine Protokollmethode (nicht `*_start`), daher trifft
sie die `PolicyService`-Fail-closed-Frage aus AE-A2 **nicht** — Teil Bs
Positiv-Fall für `connections/list` ist ein echter Erfolg, kein
`POLICY_DENIED`. Für die fünf `*_start`-Tool-Einträge, die Teil B zusätzlich
zur Scope-Matrix mitprüft, gilt dieselbe AE-A2-Ausnahme: kein
`--policy-file` in `docker-compose.yml` verdrahtet, `POLICY_DENIED` ist dort
das erwartete (nicht das übersprungene) Positiv-Fall-Ergebnis — explizit so
im Smoke-Skript kommentiert, damit es nicht als Fehlschlag missverstanden
wird.

### Neue/geänderte Dateien

- `examples/mcp-e2e/docker-compose.yml`, `.env.example`, `.d-migrate.yaml` <!-- d-check:ignore (Zielbild: entsteht in Teil B; ADR 0011) -->
- `examples/mcp-e2e/stdio-tokens.yaml` <!-- d-check:ignore (Zielbild: entsteht in Teil B; ADR 0011) -->
- `examples/mcp-e2e/scripts/smoke-scope-matrix.sh` — treibt Teil As Matrix <!-- d-check:ignore (Zielbild: entsteht in Teil B; ADR 0011) -->
  (positiv/negativ pro Scope-Bucket) gegen das echte Image; `connections/list`
  zusätzlich mit `checkLive: true` gegen `sandbox_pg`.
- `examples/mcp-e2e/README.md` (analog `sample-db/README.md`) <!-- d-check:ignore (Zielbild: entsteht in Teil B; ADR 0011) -->
- `make/mcp-e2e.mk` (`mcp-e2e-up`/`mcp-e2e-down`/`mcp-e2e-purge`/`mcp-e2e-smoke`),
  `Makefile`-Hilfetext-Ergänzung
- `.github/workflows/mcp-e2e-smoke.yml`

### Nicht-Scope

- Kein Ersatz für `native-e2e-regression-gate.md` (native Binary bleibt
  eigener, noch offener Slice) — Teil B nutzt das JVM-Runtime-Image.
- Keine Abdeckung der HTTP-Transport-Variante in Teil B (Stdio reicht für den
  Docker-Image-Beleg; HTTP ist bereits in Teil A optional möglich).
- Kein voller Business-Erfolgslauf für `dmigrate:ai:execute`/
  `dmigrate:data:write`-Tools (gleiche Positiv-Fall-Definition wie AE-A2).

### Akzeptanzkriterien

- [ ] `make mcp-e2e-smoke` baut/nutzt `d-migrate:dev`, fährt den Stack hoch,
      spielt die Scope-Matrix inkl. `connections/list` durch, räumt aggressiv
      keine Ressourcen weg (Stack bleibt stehen, `mcp-e2e-down`/`-purge`
      analog Sample-DB).
- [ ] `connections/list` mit `checkLive: true` liefert gegen den echten
      `postgres`-Service `REACHABLE`.
- [ ] `admin`-Token: `connections/list` liefert Daten, keine Scope-Ablehnung.
      `restricted`-Token: `connections/list` liefert exakt die
      `dmigrate:admin`-Scope-Ablehnung.
- [ ] `.github/workflows/mcp-e2e-smoke.yml` läuft (best-effort) bei Push auf
      `main` mit Pfadfilter, sowie `workflow_dispatch`.
- [ ] `make docs-check` grün (neue Doku-Querverweise).

## Verifikation (beide Teile)

1. `make integration INTEGRATION_TASKS=":test:e2e-cli:test --tests '*McpScopeEnforcementMatrixTest*'"`.
2. `make docker-build IMAGE_TAG=dev` einmalig, dann `make mcp-e2e-smoke`.
3. `make docs-check` nach den Doku-Änderungen.
4. `make solid-suppression-gate` vor jedem Commit.

## Referenzen

- [`ImpPlan-1.2.0-mcp-policy-file-and-connections-list.md`](../done/ImpPlan-1.2.0-mcp-policy-file-and-connections-list.md)
  — Ursprung der `connections/list`-Implementierung, deren Live-Erreichbarkeit
  hier nachträglich automatisiert abgesichert wird.
- [`native-e2e-regression-gate.md`](native-e2e-regression-gate.md) — verwandter,
  aber unabhängiger offener Slice (native Binary statt JVM-Image).
- `examples/sample-db/README.md`, `examples/bi-demo/README.md` — Vorbild-Muster
  für Teil B.
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/server/McpServerConfig.kt`
  — Scope-Mapping-Autorität.
