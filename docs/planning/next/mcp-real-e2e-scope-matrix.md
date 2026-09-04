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
> (`IntegrationFixtures.INTEGRATION_PRINCIPAL`); ein Scope-Regressions-Bug
> (z. B. ein vergessenes `enforceScope(...)` in einem neuen Handler) würde
> von keinem heutigen Test bemerkt.
> **Vorbedingung:** Keine harte Blockade.

## Kontext / Ist-Stand (verifiziert)

- **Scope-Mapping-Autorität**:
  `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/server/McpServerConfig.kt`
  (`DEFAULT_SCOPE_MAPPING`) — acht Scopes, u. a. `dmigrate:read`,
  `dmigrate:job:start`, `dmigrate:job:cancel`, `dmigrate:data:write`,
  `dmigrate:artifact:upload`, `dmigrate:ai:execute`, `dmigrate:admin`
  (nur `connections/list`).
- **Enforcement-Mechanismus**: `enforceScope(method)` in
  `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/protocol/McpServiceImpl.kt:719`
  — wirft bei fehlendem Scope einen `ResponseErrorException` mit
  `ResponseErrorCode.InvalidRequest` und der Nachricht `"principal lacks
  required scope(s) for '<method>': [<scopes>]"`. Jeder Dispatch-Zweig ruft
  das selbst auf — es gibt **keine zentrale, generische Vor-Dispatch-Prüfung**
  über alle Methoden hinweg, d. h. ein neuer Handler kann den Aufruf
  theoretisch vergessen, ohne dass Kompilierung oder ein bestehender Test
  das verhindert.
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
     als echten Kind-Prozess (`RealCliSubprocess.kt`) — durchläuft
     `McpCommand.run()` → `StateDirOwner` → `McpStateDirLock` →
     `McpServerLifecycle`. Unterstützt bereits einen
     `DMIGRATE_CLI_BIN`-Hebel, um statt der Kind-JVM das echte
     GraalVM-Native-Binary zu testen (siehe
     [`native-e2e-regression-gate.md`](native-e2e-regression-gate.md), Status
     Draft, noch nicht CI-verdrahtet — dieser Slice tastet das Dokument
     nicht an).
  3. **Beide Ebenen sind gated**: `test/e2e-cli` läuft nur mit
     `-PintegrationTests` (Kotest `integration`-Tag,
     `test/e2e-cli/build.gradle.kts`) — nicht Teil von
     `make docker-check` ohne diese Property.
- **Testprinzipal-Fixtures**: `IntegrationFixtures.kt` (`INTEGRATION_PRINCIPAL`,
  `freshTransportPrincipal(transport)`) und `StubStdioTokenStore.kt`
  (`forPrincipal(principal, rawToken)`, SHA-256-Fingerprint in-memory, keine
  YAML-Datei nötig) — **beide hart auf `isAdmin: true`/volle Scopes verdrahtet**.
  Kein bestehender Test konstruiert einen Prinzipal mit eingeschränkten
  Scopes; `StubStdioTokenStore.forPrincipal` selbst ist scope-neutral
  wiederverwendbar, nur der Aufrufer muss einen echten Scope-Filter liefern.
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
Scope-Eintrag ist automatisch erfasst, ohne den Test anzufassen.

**AE-A2 — Positiv-Fall prüft NUR "keine Scope-Ablehnung", nicht vollen
Geschäftserfolg.** Für `dmigrate:data:write`/`dmigrate:ai:execute`-Tools
einen vollen erfolgreichen Lauf zu erzwingen bräuchte echte DB-/AI-Backends
für jeden Bucket — das ist nicht das Ziel dieses Slices (der ist Scope-
Gating, keine Funktionsabdeckung). Positiv-Fall: Aufruf mit einem Prinzipal,
der den nötigen Scope trägt, plus bewusst unvollständigen/synthetischen
Argumenten — Assertion ist **"Fehler ist NICHT `principal lacks required
scope(s)`"** (jeder andere Fehler — Validierung, `NOT_FOUND`, etc. — ist ein
Erfolg für diesen Test, weil er beweist, dass die Scope-Prüfung passiert
wurde).

**AE-A3 — Negativ-Fall ist der eigentliche Wert.** Aufruf mit einem
Prinzipal, der **keinen** passenden Scope trägt (leeres `scopes`-Set,
`isAdmin = false`) — Assertion: Fehlercode `InvalidRequest` **und**
Nachricht enthält `"lacks required scope(s) for '<method>'"` **und** den
erwarteten Scope-Namen. Das ist der Test, der heute komplett fehlt.

**AE-A4 — Neuer, minimaler Fixture-Baustein statt neuer Infrastruktur.**
`StubStdioTokenStore.forPrincipal` ist bereits scope-neutral — es fehlt nur
ein zweiter Aufruf-Ort mit einem eingeschränkten `PrincipalContext`
(`scopes = emptySet()`, `isAdmin = false`). Kein neuer Store, kein neues
Fixture-Modul.

### Neue/geänderte Dateien

- `test/e2e-cli/src/test/kotlin/dev/dmigrate/cli/integration/McpScopeEnforcementMatrixTest.kt` <!-- d-check:ignore (Zielbild: entsteht in Teil A; ADR 0011) -->
  (neu) — die Matrix aus AE-A1/AE-A2/AE-A3, gegen den Real-Subprozess
  (`RealCliSubprocess`, analog `McpRealCliSubprocessTest.kt`), Stdio-Transport
  (HTTP als Stretch, falls Zeit reicht — kein Blocker für diesen Slice).
- `test/e2e-cli/src/test/kotlin/dev/dmigrate/cli/integration/IntegrationFixtures.kt`
  — neue Fabrikfunktion `restrictedPrincipal(scopes: Set<String> = emptySet())`
  neben dem bestehenden `INTEGRATION_PRINCIPAL`.

### Akzeptanzkriterien

- [ ] Für jeden Scope in `DEFAULT_SCOPE_MAPPING`: ein Prinzipal **mit**
      diesem Scope löst bei mindestens einem zugeordneten Tool keine
      Scope-Ablehnung aus.
- [ ] Für jeden Scope: ein Prinzipal **ohne** diesen Scope (leere Scopes,
      `isAdmin = false`) bekommt für mindestens ein zugeordnetes Tool exakt
      die `InvalidRequest`/`lacks required scope(s)`-Antwort mit dem
      korrekten Scope-Namen.
- [ ] `connections/list` ist explizit Teil der Matrix (schließt die
      auslösende Lücke).
- [ ] `make integration INTEGRATION_TASKS=":test:e2e-cli:test --tests '*McpScopeEnforcementMatrixTest*'"`
      grün.
- [ ] Kein neues Gradle-Modul, keine neue Fixture-Infrastruktur außer
      AE-A4.

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
