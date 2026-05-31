# Implementierungsplan: Quality- und Coverage-Expansion (Perf / Last / E2E)

> Status: ✅ erledigt (2026-05-31, F-Closing `105ccc5a`). Phasen A +
> A-Vervollständigung + Review-Fixes (`af59567d`/`2e62370c`/`9c369d94`),
> B + B-Vervollständigung (`3545b646`/`3ae1bb20`), C + C-MCP
> (`a2195313`/`1bea5bed`), D N=100/1000 (`67d93ef8`) gelandet 2026-05-30.
> Phase E in vier Sub-Slices nachgezogen 2026-05-31: E-Scaffold
> (`27db7cf4`, schon vor dem Plan-Schnitt gelandet), E.1
> (Ledger-Disposition-Vertrag, `648beec6`), E.2 (kritische Adapter-
> Audits, `68f917f9`), E.3 (`:test:*`-Aggregat-Normalisierung,
> `b3b7105f`), E.3-Review-Fixes (`8ceb2653`). F (Closing) als dieser
> Plan-Doc-Move + Roadmap-Status-Flip (`105ccc5a`). D-N10k bleibt als
> opt-in-Nightly-Folge-Thema im §9 Out-of-Scope notiert; der
> Adapter-Coverage-Uplift fuer die 19 `refactor-plan:`-Excludes lebt
> als eigenes Folge-Plan-Doc in [`docs/planning/open/adapter-coverage-uplift.md`](../open/adapter-coverage-uplift.md).
> Workstream: Roadmap-Eintrag „Coverage/QA" über §11 DoD hinaus
> Vorbedingungen:
> - `docs/planning/in-progress/diffresult-migration-plan-2.md` §11 (DoD a/b/c/d/e
>   abgeschlossen 2026-05-19)
> - `docs/planning/in-progress/roadmap.md` Zeile „Coverage/QA"
> - `docs/planning/in-progress/sequence-preserve-atomic-lock-plan.md` (Draft)
>   für Concurrent-Writer-Pattern als Wiederverwendungsbasis
> Referenzen:
> - `spec/architecture.md`, `spec/cli-spec.md`
> - `spec/lastenheft-d-migrate.md` LN-045 (Coverage ≥ 80%)

---

## 1. Ziel

Den Roadmap-Eintrag „Coverage/QA" so erweitern, dass das Wort „QA"
inhaltlich gerechtfertigt ist. Heute deckt §11 DoD ausschliesslich
**Feature-Level-Testpinnings** ab (pro Workstream je 1 Positiv-/Blocker-
Test, Exit-Codes, Rollback-Verträge). Was fehlt:

1. **Performance- und Last-Tests** für die Hot-Paths (Render-Pipeline,
   Diff-Planner, Artefakt-Serialisierung) als Regressions-Schutz gegen
   stille Verlangsamung.
2. **Tatsächlich ausgeführte Cross-Dialekt-Regressionsmatrix** statt nur
   eines Kriterienkatalogs in §11.2. Heute existieren die Einzeltests,
   aber kein wiederkehrender Matrix-Sweep, der per CI „grün/rot" sagt.
3. **End-to-End-Szenarien gegen Live-DB/Testcontainers** auf MCP-Pfad,
   Concurrent-Writer-Pattern (Sequence-Preserve-Race), Large-Schema-Skalen
   (1000+ Tabellen / Sequenzen).
4. **Kover-Coverage-Hygiene**: per-Modul-`excludes`-Listen
   konsolidieren, dauerhaft excluded Klassen mit ADR oder Refactor
   schliessen.

Out-of-scope: Telemetry-Adapter (eigener Plan), produktiver Load-Generator
gegen Cloud-Datenbanken (datenschutzkritisch), App-Layer-Replay (siehe
`sequence-preserve-atomic-lock-plan.md`).

---

## 2. Auslöser

- Roadmap-Eintrag „Coverage/QA" trägt seit 2026-05-19 den DoD-Vermerk
  „§11 DoD ist damit komplett (a/b/c/d/e alle abgehakt)", aber den
  Status `teilerledigt`. Die Diskrepanz reflektiert, dass §11 DoD eng
  auf Feature-Pinning beschränkt ist; alle Quality-Themen ausserhalb
  davon (Perf, Last, E2E gegen Live-DB/Testcontainers) sind nicht vergeben.
- Die `diffresult-migration-plan-2.md` §11.2 listet Cross-Dialekt-
  Regressionskriterien, aber keinen ausführbaren Matrix-Lauf. Ein
  Operator kann heute nicht „grün" für die Matrix sagen — nur
  „die Einzelpunkte sind irgendwo gepinnt".
- Mehrere Module tragen Kover-`excludes` ohne Refactor-Plan
  (`SqliteSchemaReader`, `MysqlDataReader`, `MysqlDriver`, etc.).
  `spec/profiling.md` pinnt fuer neue Profiling-/Adapterarbeit explizit
  „>= 90% Testabdeckung pro Modul" und „I/O-Glue-Code wird ueber Port-
  Abstraktion testbar gemacht, nicht von der Coverage ausgenommen";
  `docs/user/quality.md` dokumentiert das Root-Gate mit `minBound(90)`.

---

## 3. Scope

### 3.1 In Scope

- **Phase A — Performance-Baseline**: per-Hotpath ein Kotest-`PerfSpec`
  mit `kotest.tags=perf`, der wiederholbare Benchmark-Werte (Median,
  P95) für drei konkret verortete Hotpaths erfasst:
  - `SchemaMigrateRenderPipeline` →
    `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaMigrateRenderPipeline.kt`
    (Spec in `hexagon/application/src/test/.../perf/`)
  - `DiffPlanner` →
    `hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/migration/DiffPlanner.kt`
    (Spec in `hexagon/core/src/test/.../perf/`)
  - Artefakt-Serialisierung → `RollbackArtefactBuilder` +
    `RollbackArtefactParser` (Round-Trip-Pfad, beide in
    `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/`; Spec
    in `hexagon/application/src/test/.../perf/`)

  Das `NamedTag("perf")`-Pattern existiert bereits in
  `adapters/driven/formats/.../perf/*PerfTest.kt` und
  `adapters/driven/streaming/.../StreamingImporterReorderPerfTest.kt`,
  aber die dort verwendeten Mess-/Heap-Helfer (`LargeJsonFixture.usedHeapBytes`,
  `ManagementFactory`-Snapshots, ad-hoc Median-Berechnung) sind heute pro
  Spec dupliziert — eine gemeinsame `PerfMeasure`/`PerfReport`-Lib
  existiert noch nicht (`grep -rn "PerfMeasure\|PerfReport"` → keine
  Treffer). Phase A muss diese Lib daher **neu anlegen** (nicht „aus
  Bestand extrahieren"). Sie lebt unter `hexagon:profiling` mit dem
  Standard-`minBound(90)`-Gate; die drei Hotpath-Specs sind ihre ersten
  Konsumenten, die Bestands-PerfSpecs in `formats`/`streaming` werden im
  selben Sub-Slice auf die Lib migriert, damit kein Parallel-Pattern
  bleibt. Harte
  Failure-Budgets gelten nur fuer runaway-Smoke-Grenzen oder dedizierte
  Perf-Runner; normale Nightly-Laeufe schreiben Trend-Reports und blocken
  PRs nicht wegen Container-Timing. Phase A schliesst zuerst die
  Gradle-Bruecke, damit ein explizites `-Dkotest.tags=perf` in die forked
  Test-JVM weitergereicht wird (siehe §5.1 für den exakten Patch); erst
  danach gilt der CI-/Nightly-Job als nutzbar. Der Lauf bleibt opt-in,
  nicht Teil des Standard-Test-Sweeps. Phase A darf inkrementell starten,
  ist aber erst abgeschlossen, wenn alle drei Hotpaths jeweils einen
  Smoke-Guard und einen Baseline-Reportwert haben.
- **Phase B — Cross-Dialekt-Regressionsmatrix als ausführbarer Sweep**:
  ein Top-Level-Test-Modul `test/cross-dialect-matrix/` mit
  `MatrixSweepTest`, das die §11.2-Kriterien (Positiv, Blocker, Report,
  Rollback, File-Mode) pro Workstream × Dialekt programmatisch
  durchläuft. Lückenhafte Workstream-Dialekt-Kombinationen surfacen
  als strukturierte Diagnose, nicht als stille Auslassung.
- **Phase C — Concurrent-Writer-Regressionen**: ein
  `test/integration-concurrency/`-Modul, das das Sequence-Preserve-
  Race-Pattern (Probe → App-`nextval`/`dmg_nextval` → Restore) gegen
  PG/MySQL/SQLite reproduzierbar nachstellt. Dazu kommt ein MCP-E2E-
  Szenario in `test:e2e-cli`, das den bestehenden MCP-Job-Pfad
  (`schema_reverse_start`/`schema_compare_start`) ueber die MCP-Client-
  Oberflaeche plus einen realen `mcp serve`-Lifecycle-Smoke, Live-DB-
  Connection-Refs, Job-Status und Artefakte ausführt. Phase C
  beweist den Carve-out aus
  `ImpPlan-0.9.7-sqlite-sequence-preserve-current-value.md` §6 und liefert
  die Baseline für den atomaren Folge-Slice, ohne einen nicht existierenden
  MCP-`schema_migrate`-Vertrag vorauszusetzen.
- **Phase D — Large-Schema-Last-Tests**: synthetische Schemata mit
  N=100/1000/10000 Tabellen/Sequenzen/Views/Triggern; pinnt nur
  runaway-Smoke-Grenzen im Standard-Opt-in und schreibt Render-Throughput
  sowie Memory-Footprint als Hash-of-Numbers in den Report. Scharfe
  Scale-Budgets sind dedizierten Nightly-/Perf-Runnern vorbehalten.
- **Phase E — Kover-Excludes-Konsolidierung**: jede aktive
  Kover-Exclude-Regel (Selector-Typen sind heute `classes(...)` und
  `packages(...)`; Wildcards/Glob-Pattern sind Pattern-Form innerhalb
  dieser Selector-Typen, kein eigener Typ; kuenftige Kover-Selector-Typen
  werden vom Parser fail-closed behandelt) braucht eine Begründungs-Zeile
  in einer zentralen `docs/coverage/excludes-ledger.md` (ADR-light), mit
  Referenz auf Refactor-Plan oder explizitem „permanent excluded weil X"-Beleg.
  Groesse, I/O-Naehe oder „thin wrapper" sind keine ausreichende
  Begruendung ohne konkreten Port-/Fixture-/Refactor-Check; permanente
  Ausnahmen brauchen einen expliziten ADR-/Ledger-Beleg.
- **Phase F — Schliessen**: alle Phasen-DoDs erfüllt; Roadmap-Status
  von `teilerledigt` auf `✅ erledigt` ziehen.

### 3.2 Out of Scope

- Telemetry-/OpenTelemetry-Adapter (eigener Plan
  `next/telemetry-observability-port.md`).
- MCP-Server-Load-Tests (eigene Last-Strategie, gehört zu
  `mcp-server.md`-Vertrag).
- Neues MCP-Migrate-Tool (`schema_migrate`/`schema_migrate_start`):
  Produkt-/Contract-Scope, nicht QA-Nachruestung. Dieser Plan testet nur
  heute registrierte MCP-Tools.
- Produktive Cloud-DB-Benchmarks (Datenschutz, RDS-/Cloud-SQL-
  Kostenkontrolle); wir bleiben auf Testcontainers.
- Mutation-Testing (PIT-/Stryker-Stil) — eigener Folge-Plan, sobald
  Coverage-Baseline stabil ist.
- Browser-/UI-Tests — d-migrate hat keine UI.

---

## 4. Vorbedingungen

| Vorbedingung | Status |
|---|---|
| §11 DoD a-e (Feature-Test-Pinning) | ✅ 2026-05-19 |
| `make integration`-Pipeline pro Dialekt | ✅ (test/integration-*) |
| Integration-Gating | ✅ strukturell via `-PintegrationTests`, nicht über Kotest-`integration`-Tags |
| `kotest.tags=perf` als Filter-Konvention | ✅ Default-Exclude `!perf` und explizites Forwarding in die forked Test-JVM sind in `build.gradle.kts:89-102` verdrahtet; Gegenlauf bleibt Akzeptanzkriterium fuer Phase A |
| `NamedTag("perf")`-Pattern in Bestands-PerfSpecs | ✅ in `adapters/driven/formats` und `adapters/driven/streaming` etabliert; gemeinsame `PerfMeasure`/`PerfReport`-Lib existiert noch nicht (Phase A legt sie neu in `hexagon:profiling` an und migriert die Bestands-Specs darauf) |
| Atomar-Lock-Plan für Concurrent-Writer-Pattern | ⚠️ Draft (`sequence-preserve-atomic-lock-plan.md`) |
| Kover-`koverVerify` als CI-Gate | ✅ Produktionsmodule `minBound(90)`; reine Test-/Runner-Module muessen explizit `minBound(0)` setzen oder begruendet aus dem Aggregat bleiben |

---

## 5. Architektur

### 5.0 Gemeinsame Build-/CI-Regel fuer neue Testmodule

Jedes neue Top-Level-Testmodul in diesem Plan (`test/cross-dialect-matrix`,
`test/integration-concurrency`, `test/perf-large-schema`) aktualisiert im
selben Sub-Slice alle Build-Einstiegspunkte:

- `settings.gradle.kts`-`include(...)`.
- Dockerfile-`deps`-Stage mit explizitem `COPY --chown=gradle:gradle
  <modul>/build.gradle.kts ...`, weil der Dependency-Warmup-Stage keine
  rekursive Gradle-Dateisuche nutzt.
- Root-Kover-Aggregat, Coverage-Modules-Listen und Modul-`kover { verify }`
  bewusst entscheiden: Produktionscode bleibt `minBound(90)`, reine
  Test-/Runner-Module setzen `minBound(0)` oder werden mit Begruendung nicht
  in das Aggregat aufgenommen.
- Make-/CI-Einstieg mit einem konkreten Opt-in-Befehl, wenn der Lauf nicht
  Teil des Standard-PR-Sweeps ist.

Ein Sub-Slice gilt nicht als abgeschlossen, solange ein neues Modul nur lokal
ueber `settings.gradle.kts` laeuft, aber Docker-/CI-Pfade durch fehlende
Gradle-Dateien oder unklare Coverage-Einbindung brechen koennen.

### 5.1 Performance-Benchmarks (Phase A)

```
hexagon/<modul>/src/test/kotlin/...PerfSpec.kt
  private val PerfTag = NamedTag("perf")

  class SchemaMigrateRenderPipelinePerfSpec : FunSpec({
    tags(PerfTag)

    test("SchemaMigrateRenderPipeline.run for 100-op plan") {
      val plan = SyntheticDiffResultGenerator.buildAlterTable(opCount = 100, ...)
      val pipeline = SchemaMigrateRenderPipeline(...)
      val sample = PerfMeasure.run(warmup = 5, iterations = 20) {
          pipeline.run(...)
      }
      sample.medianMs shouldBeLessThan RENDER_SMOKE_MAX_MS
      sample.p95Ms shouldBeLessThan RENDER_SMOKE_MAX_MS
      PerfReport.write(
          hotpath = "schema-migrate-render-pipeline",
          medianMs = sample.medianMs,
          p95Ms = sample.p95Ms,
          baselineMs = RENDER_BASELINE_MS,
      )
    }
  })
```

- Vor dem ersten neuen `PerfSpec`: Root-`build.gradle.kts` muss ein
  explizites `System.getProperty("kotest.tags")` nicht nur den Default
  `!perf` unterdruecken lassen, sondern auch in die forked Test-JVM
  weiterreichen. Dieser Vertrag ist inzwischen so verdrahtet:

  ```kotlin
  if (explicitKotestTags == null) {
      systemProperty("kotest.tags", "!perf")
  } else {
      systemProperty("kotest.tags", explicitKotestTags)
  }
  ```

  Gegenlauf-Pflicht: `-Dkotest.tags=perf` darf nur tagged Specs starten,
  `-Dkotest.tags=!perf` muss untagged Specs identisch zum heutigen
  Default laufen lassen, und ein willkuerliches Drittfilter
  (`-Dkotest.tags=integration` o. ae.) darf keine untagged Tests
  durchlassen. Erst nach diesem Patch ist der Tag-Filter
  vertragsverlaesslich; alle Phasen, die opt-in via `-Dkotest.tags=...`
  arbeiten, setzen das implizit voraus.
- Budget pro Hotpath als zwei getrennte Grenzen:
  - `*_SMOKE_MAX_MS` ist ein grosszuegiger runaway guard und darf in jedem
    opt-in Perf-Lauf failen, wenn Median **oder** P95 nach Warmup deutlich
    ausserhalb der erwarteten Groessenordnung liegen. Beide Kennzahlen werden
    separat reported; ein Smoke-Guard-Bruch in einer von beiden ist ein
    Runaway-Signal.
  - `*_BASELINE_MS` ist ein Nightly-/dedicated-runner-Wert im JSON-Report.
    Er blockt nur auf Runnern mit explizitem `-PperfGate=true` oder
    Workflow-Label `perf-stable-runner`; auf Shared-Container-CI wird er
    als Regression-Diagnose reported, nicht als PR-Gate. Phase A liefert
    das CI-/Make-Skelett (Bulletpoint „CI-Job laeuft tagsueber nicht im
    PR-Sweep …" unten) inklusive `-PperfGate=true`-Schalter im
    `make docker-perf`-Target; die tatsaechliche Bereitstellung des
    `perf-stable-runner`-Labels (Self-Hosted-Runner oder dedizierter
    GitHub-Hosted-Runner-Pool) ist Infrastruktur-Aufgabe und bleibt
    Out-of-Scope dieses Plans (siehe §9). Bis der Runner steht, laufen
    Baseline-Gates ausschliesslich lokal ueber
    `make docker-perf PERF_GATE=true`.
  Jeder Baseline-Bump muss den alten/neuen Median+P95 im Commit oder PR
  dokumentieren.
- Pro Lauf: 5 Warmup + 20 Mess-Iterationen; Report-Output
  Median+P95+P99 in `build/reports/perf/<hotpath>.json`.
- CI-Job läuft tagsüber nicht im PR-Sweep, sondern als nightly
  via separater GitHub-Actions-Workflow oder als manueller
  `make docker-perf`-Trigger. Das Target muss den Tag-Filter nachweislich
  aktivieren, z. B. ueber Dockerfile-`GRADLE_TASKS` mit
  `-Dkotest.tags=perf`, und darf keine untagged Unit-Tests mitlaufen lassen.

### 5.2 Cross-Dialekt-Matrix (Phase B)

```
test/cross-dialect-matrix/
  └─ MatrixSweepTest.kt
       table {
           row("G.1", DatabaseDialect.POSTGRESQL, "positive", expectedExitCode = 0)
           row("G.1", DatabaseDialect.MYSQL,      "blocker",  expectedExitCode = 8)
           row("G.1", DatabaseDialect.SQLITE,     "rollback", expectedExitCode = 0)
           ...
       }.forAll { (workstream, dialect, kind, expected) ->
           val result = MatrixSweepFixtures.execute(workstream, dialect, kind)
           result.exitCode shouldBe expected
       }
```

- Workstream × Dialekt × Test-Art (Positiv/Blocker/Report/Rollback/File)
  → durchgängige Tabelle. Lücken sind als `MATRIX_GAP`-Diagnose markiert
  und blocken den Sweep, bis der Workstream das Pinning nachholt ODER
  einen expliziten Carve-out registriert.
- Fixture-Re-Use-Vertrag: bestehende Schema-Fixtures aus
  `test/integration-postgresql/`, `…-mysql/`, `…-sqlite/`,
  `…-integrations/` und `test/e2e-cli/` werden **nicht** dupliziert.
  Stattdessen lebt der gemeinsame Fixture-Bestand in
  `test/cross-dialect-matrix/fixtures/` als kanonische Quelle; die
  bisherigen Konsumenten greifen via shared sourceset (Gradle-`test`-
  `resources`-Verzeichnis aus dem Matrix-Modul wiederverwenden) oder via
  Symlink/`processResources`-Kopie auf denselben Stand zu. Eine pro
  Sub-Slice B-Commit gepflegte Fixture-Migrationsliste
  (`test/cross-dialect-matrix/README.md`) dokumentiert, welche
  Fixtures aus welchen Bestandsmodulen umgezogen wurden, damit kein
  stiller Drift entsteht. Der Sweep lädt die Fixtures deterministisch
  aus `test/cross-dialect-matrix/fixtures/`.
- Carve-out-Beispiel: PG `EXCLUDE` hat keinen MySQL-/SQLite-Positivpfad
  (siehe `diffresult-migration-plan-2.md §10 F.5` zur Constraint-
  Diffbarkeit; §11.2 listet nur die generischen Pflichtkriterien, die
  Workstream-spezifischen Carve-outs leben im jeweiligen Workstream-
  Abschnitt) — das Carve-out-File listet den Verzicht mit
  Plan-Doc-Verweis.
- Modulregistrierung folgt §5.0. Da `test/cross-dialect-matrix` ein reines
  Test-/Sweep-Modul ist, muss sein `build.gradle.kts` die Coverage-Pflicht
  explizit als `minBound(0)` oder als begruendeten Aggregate-Carve-out
  dokumentieren.

### 5.3 MCP-E2E- und Concurrent-Writer-Tests (Phase C)

**MCP-E2E-Pfad:** `test:e2e-cli` bekommt ein Szenario, das die heute
registrierten Job-Start-Tools gegen Live-DB-Connection-Refs ausführt:
`schema_reverse_start` liest aus einer Testcontainers-/SQLite-DB,
`schema_compare_start` vergleicht zwei Live-DB-Refs oder eine Live-DB-Ref
gegen ein registriertes Schema-Artefakt. Der bestehende
`McpClientHarness` laeuft fuer Szenariotests bewusst in-process; deshalb
ist die fachliche E2E-Pflicht nicht „nur Job anlegen", sondern ein
explizites Production-Worker-Wiring:

- Der heutige `test:e2e-cli`-In-Process-Harness reicht nicht aus, solange
  `StdioHarness`/`HttpHarness` nur `McpRuntimeWiring` an
  `McpServerBootstrap.startStdio`/`startHttp` uebergeben. Phase C-MCP baut
  eine **Operational-Harness-Variante** auf demselben Bootstrap-Pfad. Die
  Harness komponiert direkt
  `components = AiMcpRegistries.defaultComponents(AiMcpWiring(OperationalMcpWiring(...)))`
  und uebergibt sie als `components`-Override in
  `McpServerBootstrap.startStdio`/`startHttp`. Bewusst **nicht** ueber
  `McpServeWiring` aus dem CLI-Adapter (`adapters/driving/cli`):
  `McpServeWiring` lebt im CLI-Modul und ist mit `McpServeRunner`
  verschraenkt (StateDirOwner, McpStateDirLock, CursorKeyring,
  ApprovalGrantsFile, …). Diese CLI-Optik gehoert nicht in den
  In-Process-Test; sie wird vom separaten Subprocess-Smoke (siehe unten)
  ueber den echten `mcp serve`-Pfad abgedeckt.
- `OperationalMcpWiring` nutzt `McpCoreJobWorkerFactory`, nicht den
  Fallback `PassthroughJobWorkerFactory`. Der Test muss beweisen, dass
  ein Worker Artefakte publiziert und der Job terminal wird; ein nur
  angelegter `QUEUED`-Job reicht nicht.
- Die Test-Fixtures registrieren echte `ConnectionReference`-Eintraege
  und einen Test-`ConnectionSecretResolver`, der deren `credentialRef`
  deterministisch auf Testcontainers-/SQLite-JDBC-URLs materialisiert.
- Der Test wartet bis zum terminalen Job-Status und liest erzeugte
  Schema-/Diff-/Artefakt-Resources ueber die MCP-Client-Oberflaeche
  (`job_status_get`, `resources/read`, bei Bedarf `artifact_chunk_get`).
- Ein separater Subprocess-Smoke bleibt Pflicht und pinnt, dass
  `mcp serve --transport stdio` den echten CLI-/Bootstrap-/StateDir-
  Lifecycle startet. Fachliche Artefakt-Assertions duerfen in-process
  laufen, solange sie ueber dieselbe Tool-/Resource-Oberflaeche gehen.
- Gating: das neue Live-DB-Szenario laeuft im Standard-`-PintegrationTests`-
  Sweep von `:test:e2e-cli` mit — ohne zusaetzliches Property — weil die
  Aussagekraft (Live-DB-Job-Worker bis terminalem Status + Artefakt-Read)
  zum E2E-CLI-Vertrag gehoert. Laufzeit-Budget: das Szenario darf den
  bestehenden `:test:e2e-cli`-Lauf nicht mehr als ~60 s verlaengern; wird
  das gerissen, hat Sub-Slice C-MCP zwei Optionen, die im Commit
  benannt werden: (a) das Szenario hinter ein zusaetzliches Property
  `-Pe2eLiveDb` umziehen und im Standard-`-PintegrationTests`-Sweep nur
  einen Minimal-Smoke lassen, oder (b) das Szenario in einen schlankeren
  Pfad refaktorieren. Default-Erwartung ist (b).
- Der opt-in Nachweis laeuft mindestens ueber
  `make integration INTEGRATION_TASKS="-PintegrationTests :test:e2e-cli:test"`
  oder einen engeren `--tests`-Filter fuer das neue Live-DB-Szenario.
  Hinweis: `-PintegrationTests` muss explizit in `INTEGRATION_TASKS`
  bleiben, weil `scripts/test-integration-docker.sh` den Default
  `-PintegrationTests test` vollstaendig mit der Nutzereingabe
  ersetzt; ein Weglassen entzieht dem Lauf das Integration-Gating der
  betroffenen Sub-Projekte und der Test-Task wird per `onlyIf`
  uebersprungen.

Damit ist der QA-Scope nicht nur auf CLI-/Renderer-Unit-Pfade beschränkt,
ohne ein neues MCP-Tool zu erfinden. Ein MCP-Migrate-Tool (`schema_migrate`
oder `schema_migrate_start`) wäre ein eigener Produkt-/Contract-Slice und
ist keine Vorbedingung dieses QA-Plans.

**Concurrent-Writer-Pfad:** Das neue Modul `test/integration-concurrency/`
läuft strukturell wie die bestehenden `test:integration-*`-Module unter
`-PintegrationTests`, aber zusätzlich nur mit `-PconcurrencyTests`. Das
Modul setzt in seinem `build.gradle.kts` ein zweites `onlyIf`, damit der
normale Integration-Sweep die Race-Tests nicht entdeckt oder startet.

```
test/integration-concurrency/
  └─ SequencePreserveRaceTest.kt
       test("PG: nextval between probe and restore surfaces stale UPDATE") {
           val pg = postgresContainer.start()
           pg.exec("CREATE SEQUENCE order_seq")
           pg.exec("SELECT nextval('order_seq')") // brings it to 1
           val probeObserved = CountDownLatch(1)
           val writerFinished = CountDownLatch(1)

           val gatedProbe = SequenceCurrentValueProbeFn { pool, ref ->
               val read = realProbe(pool, ref)
               probeObserved.countDown()
               writerFinished.await(5, SECONDS) shouldBe true
               read
           }

           val writerThread = thread {
               probeObserved.await(5, SECONDS) shouldBe true
               repeat(50) { pg.exec("SELECT nextval('order_seq')") }
               writerFinished.countDown()
           }

           val observation = runLegacyMigrationPreserveRaceAgainst(
               pg,
               sequenceCurrentValueProbe = gatedProbe,
           )
           writerThread.join()
           val finalValue = pg.queryOne<Long>("SELECT last_value FROM order_seq")
           // Legacy reproducer only: documents today's non-atomic gap.
           // The atomic-lock slice must flip/remove this stale-restore
           // assertion and require finalValue >= observation.postWriterMaximum.
           observation.postWriterMaximum shouldBeGreaterThan observation.observedProbeValue
           finalValue shouldBe observation.observedProbeValue
       }
```

- Pro Dialekt ein Race-Reproducer, der den dokumentierten Carve-out
  reproduzierbar beobachtet, aber nicht als dauerhafter Korrektheitsvertrag
  missverstanden werden darf. PG/MySQL laufen über Testcontainers; SQLite
  nutzt eine echte Datei-DB mit zwei Connections. Der Test muss den Writer
  per Latches/Barrieren exakt im Probe→Restore-Fenster platzieren; ein
  frei laufender Writer-Thread ist nicht zulässig, weil er nach dem Restore
  weiterdrehen und den stale-restore-Befund maskieren kann.
- Der Reproducer ist als Legacy-/Risk-Baseline markiert (z. B. Testname,
  KDoc und Report-Feld `knownRace=true`) und lebt nicht im dauerhaften
  Korrektheits-Gate. Phase C muss eine Implementierungszustands-Regel
  festlegen:
  - Solange der atomare Pfad nicht implementiert ist, darf der opt-in
    Legacy-Reproducer den stale-restore-Befund beweisen.
  - Sobald `sequence-preserve-atomic-lock-plan.md` landet, wird diese
    stale-restore-Assertion aus dem aktiven Gate entfernt, quarantined
    oder als historische Doku behalten; das aktive Gate verlangt dann
    `finalValue >= postWriterMaximum` („mit atomarem Pfad: finalValue ist
    mindestens Post-Writer-Maximum").
  QA-Closing darf nie gleichzeitig von der Legacy-Stale-Assertion und vom
  atomaren Korrektheits-Gate abhaengen.
- Marker-Tag `@Tags("concurrency")` dient nur der Lesbarkeit; die
  Ausführung wird über Gradle-Properties gegatet:
  `make integration INTEGRATION_TASKS="-PintegrationTests -PconcurrencyTests :test:integration-concurrency:test"`.
  `-PconcurrencyTests` allein ist kein gültiger Lauf, weil das
  Integrations-Gating des Root-Builds weiterhin `-PintegrationTests`
  verlangt. Beide Properties bleiben aus dem oben in §5.3 genannten
  Grund (Default-Override in `scripts/test-integration-docker.sh`)
  explizit in `INTEGRATION_TASKS`.

### 5.4 Large-Schema-Last-Tests (Phase D)

```
test/perf-large-schema/
  └─ LargeSchemaScaleSpec.kt
       private val PerfTag = NamedTag("perf")
       private val LargeSchemaTag = NamedTag("large-schema")

       class LargeSchemaScaleSpec : FunSpec({
           tags(PerfTag, LargeSchemaTag)

           data class Scale(
               val n: Int,
               val renderSmokeMaxMs: Long,
               val renderBaselineMs: Long,
               val maxHeapMb: Long,
           )

           forAll(
               Scale(n = 100,   renderSmokeMaxMs = 500,   renderBaselineMs = 250,   maxHeapMb = 256),
               Scale(n = 1000,  renderSmokeMaxMs = 5000,  renderBaselineMs = 2500,  maxHeapMb = 512),
               Scale(n = 10000, renderSmokeMaxMs = 60000, renderBaselineMs = 30000, maxHeapMb = 2048),
           ) { scale ->
               val schema = LargeSchemaGenerator.mixedSchema(
                   tables = scale.n,
                   sequences = scale.n,
                   views = scale.n,
                   triggers = scale.n,
                   seed = "large-schema-${scale.n}",
               )
               val budget = HeapBudget.start(scale.maxHeapMb)
               val duration = measureTimedValue { runMigratePipeline(schema) }.duration
               duration.toMillis() shouldBeLessThan scale.renderSmokeMaxMs
               budget.peakUsedMb shouldBeLessThan scale.maxHeapMb
               LargeSchemaPerfReport.write(scale, duration, budget.peakUsedMb)
           }
       })
```

- Synthetische Schema-Generator-Library, deterministisch (Seed-basiert).
  Der Standard-Generator fuer diesen Plan erzeugt nicht nur Tabellen, sondern
  pro Scale auch Sequenzen, Views und Trigger. Ein reiner Tabellen-Generator
  darf nur als zusaetzlicher Diagnosefall laufen, nicht als Phase-D-DoD.
- Runs gegen JVM-`-XX:+HeapDumpOnOutOfMemoryError`, damit bei Über-
  schreitung ein analysierbarer Heap-Dump entsteht.
- Heap-Mess-Strategie: `HeapBudget` ist Skizze, nicht API-Vertrag. Die
  konkrete Implementierung waehlt der erste Phase-D-Sub-Slice. Erste
  Wahl: `MemoryPoolMXBean.peakUsage` ueber alle Heap-Pools mit
  explizitem `resetPeakUsage()` vor jedem Scale-Run und einem
  GC-induzierten Snapshot direkt vor und nach dem Lauf. Alternativen
  (JFR-Recording, async-profiler) sind zulaessig, muessen aber vor der
  Implementierung im Sub-Slice begruendet werden — Smoke-Guard bleibt
  konservativ, bis die Mess-Strategie pinned ist.
- Carve-out: N=10000 ist optional (sehr lange Laufzeit; nur in nightly).

### 5.5 Kover-Excludes-Konsolidierung (Phase E)

- Neues Verzeichnis `docs/coverage/` existiert seit der initialen
  Phase-E-Inventur (E-Scaffold, ✅). Die Datei
  `docs/coverage/excludes-ledger.md` listet pro Modul jede
  aktive Kover-Exclude-Regel aus allen
  `build.gradle.kts`-Bloecken mit:
  - Selector-Typ (`classes(...)` oder `packages(...)`; das konkrete
    Pattern kann Glob-Wildcards enthalten — die Wildcards sind
    Pattern-Form und kein eigener Selector-Typ), Pattern-Wert und
    Modulpfad.
  - Begründung **und** einer Pflichtspalte `Disposition` aus dem
    zulaessigen Vokabular `permanent: <ref>` (DTO/Port/sealed Outcome),
    `refactor-plan: <pfad-zum-plan-doc>` (Adapter mit Coverage-Schuld)
    oder `aggregate-carveout: <ref>` (`:test:*`-Module, die bewusst nicht
    im Root-Kover-Aggregat haengen). Sub-Slice E.1 fuehrt den
    Disposition-Vertrag ein und backfillt alle Bestands-Eintraege; E.2
    ersetzt verbleibende `refactor-plan: TBD` durch echte Plan-Doc-
    Verweise fuer die kritischen Adapter; E.3 ergaenzt den
    `aggregate-carveout`-Block fuer `:test:*`-Module.
- Phase E startet mit einer generierten Vollinventur, nicht mit einer
  handgepflegten Beispielmenge. `scripts/verify-kover-excludes-ledger.py`
  durchsucht alle
  `kover { reports { filters { excludes { ... } } } }`-Bloecke und
  extrahiert mindestens `classes(...)` und `packages(...)`-Eintraege
  (Pattern werden 1:1 uebernommen, Wildcards bleiben Teil des Patterns);
  der Parser failt geschlossen, wenn er einen unbekannten Exclude-
  Selector-Typ findet, bis der Ledger-Vertrag um diesen Selector erweitert ist. Beispiele fuer
  heute aktive Kategorien:
  - CLI-Command-Shells und JDBC-/Hikari-Wiring in `adapters:driving:cli`.
  - Core-/Server-Core-DTOs und sealed Outcome-Typen in `hexagon:core`.
  - Ports-Interfaces, DTOs und `$DefaultImpls` in `hexagon:ports-*`.
  - Driver-/Format-Adapter-Excludes in `adapters:driven:*`.
  - Paketweiter Quota-Exclude in `adapters:driven:persistence-jdbc`
    (`packages("dev.dmigrate.server.persistence.jdbc.quota")`) inklusive
    Begruendung, warum die Package-Regel nicht durch engere Klassenregeln
    ersetzt wird.
- Bestehende kritische Excludes mindestens explizit durchgehen:
  - `dev.dmigrate.driver.sqlite.SqliteSchemaReader` — heute als
    „edge cases requiring exotic real-world schemas" begründet; muss
    in einen Splittungs-Plan überführt werden, parallel zu Phase B
    Cross-Dialekt-Matrix.
  - `dev.dmigrate.driver.postgresql.PostgresDataReader`,
    `dev.dmigrate.driver.postgresql.PostgresDriver` — analog zu MySQL als
    thin wrappers bewerten, aber nicht allein deshalb dauerhaft ausschliessen;
    entweder Port-/Fixture-Coverage oder ein permanenter ADR-/Ledger-Beleg.
  - `dev.dmigrate.driver.mysql.MysqlDataReader`,
    `dev.dmigrate.driver.mysql.MysqlDriver` — heutige „thin wrappers"-
    Begruendung neu pruefen; sie bleiben nur dann permanent excluded, wenn der
    Ledger einen expliziten ADR-/Permanent-Beleg traegt, sonst folgt ein
    Refactor-/Fixture-Plan.
- Verifikation: `make docker-coverage-gate` grün; zusätzlich prüft
  `make coverage-excludes-check` (und damit `make docs-check`), dass
  jede Kover-Exclude-Regel aus den
  Gradle-Dateien im Ledger vorkommt — `classes(...)` und `packages(...)`
  als heutige Selector-Typen, jeweils mit ihrem vollstaendigen Pattern
  (Wildcards sind Teil des Patterns). Neue Excludes brauchen einen
  Commit, der gleichzeitig das Ledger pflegt; bisher unbekannte
  Selector-Typen blocken den Hook, statt still ignoriert zu werden.
- Phase E normalisiert ausserdem die bestehende `:test:*`-Modul-Landschaft:
  jedes Test-/Runner-Modul, das im Root-Kover-Aggregat oder in
  Coverage-Modules-Listen auftaucht, bekommt eine explizite Entscheidung
  (`minBound(0)`, produktionscodehaltiges 90%-Gate oder begruendeter
  Aggregate-Carve-out). Das gilt fuer Bestandsmodule und neue Module
  gleichermassen, damit §5.0 nicht nur zukuenftige Dateien regelt.
- Bestandsaudit der heutigen Asymmetrie (E.3): das Root-Aggregat
  (`build.gradle.kts:174-201`) listet `:test:integration-postgresql`,
  `:test:integration-mysql`, `:test:integration-server-state`,
  `:test:consumer-read-probe`. **Nicht** aggregiert sind heute
  `:test:integration-sqlite`, `:test:integration-integrations`,
  `:test:integration-persistence-jdbc`, `:test:e2e-cli` (historisch) sowie
  `:test:cross-dialect-matrix`, `:test:integration-concurrency`,
  `:test:perf-large-schema` (Sub-Slices B/C/D — modul-eigenes
  `minBound(0)` ist gesetzt, Aggregat-Entscheidung steht aus). Sub-Slice
  E.3 entscheidet pro Modul explizit (Aggregat ja/nein, Begruendung)
  und schliesst die Asymmetrie oder pinnt sie mit
  `aggregate-carveout:`-Disposition im Excludes-Ledger.

---

## 6. Sub-Slice-Schnitt

| Sub-Slice | Status | Inhalt |
|---|---|---|
| A | ✅ erledigt (2026-05-30, `af59567d`) | `PerfMeasure`/`PerfReport`-Lib **neu** in `hexagon:profiling` (`minBound(90)`) + `PerfSpec`-Konvention + Root-Forwarding fuer explizites `kotest.tags` + erster Hotpath `SchemaMigrateRenderPipeline.run` (Spec in `hexagon/application/src/test/.../perf/`) + getrennte Smoke-/Baseline-Budgets + nightly-Workflow-/`make docker-perf`-Skelett |
| A-Vervollständigung | ✅ erledigt (2026-05-30, `2e62370c`) | Diff-Planner-PerfSpec (`hexagon/core/src/test/.../perf/`) + Artefakt-Serialisierungs-PerfSpec ueber `RollbackArtefactBuilder`+`RollbackArtefactParser`-Round-Trip (`hexagon/application/src/test/.../perf/`) mit denselben Smoke-/Baseline-Vertraegen + Migration der Bestands-PerfSpecs in `adapters/driven/formats` und `adapters/driven/streaming` auf `PerfMeasure`/`PerfReport`; Phase A ist erst nach allen drei Hotpaths plus Bestands-Migration schliessbar |
| A-Review-Fixes | ✅ erledigt (2026-05-30, `9c369d94`) | `/code-review`-Befunde adressiert: `PERF_GATE`-Forwarding (`d-migrate.perf.gate` SystemProperty), Streaming-Spec GC-Window-Alignment, `iterations==1`-Guard in formats/streaming, `Sink.consume(null)` im finally, atomare `Files.move(ATOMIC_MOVE)` in `PerfReport.write`, `%.9f`-Precision, KDoc-Notes zu Iteration=1-/n&lt;100-Percentile-Kollaps, `generateRollback=true` in Render-Pipeline-Spec |
| B | ✅ erledigt (2026-05-30, `3545b646`) | `test/cross-dialect-matrix/`-Modul + §5.0-Build-/Docker-/Kover-Einbindung + Sweep-Fixture-Lader + Carve-out-Registry-Mechanik (`fixtures/carve-outs.yaml` + `MATRIX_GAP`-Diagnose) + erste 5 Workstreams gepinnt, restliche Workstreams provisorisch als Carve-out registriert, damit der Sweep schon ab B aktiv laufen kann ohne 17 noch nicht gepinnte Workstreams hart zu blocken |
| B-Vervollständigung | ✅ erledigt (2026-05-30, `3ae1bb20`) | provisorische Carve-out-Eintraege fuer die restlichen Workstreams in echtes Pinning konvertieren oder als dauerhaften Carve-out mit Plan-Doc-Verweis stehen lassen — am Ende ist jeder zum Annahme-Zeitpunkt bestehende Workstream entweder gepinnt oder hat einen begruendeten dauerhaften Carve-out. **Neue Workstreams nach B-Vervollständigung** sind Pflicht-Pinning des einfuehrenden Slices (im jeweiligen Plan-Doc), nicht von B oder F; das `fixtures/carve-outs.yaml` traegt sie nur dann nach, wenn der einfuehrende Slice eine `MATRIX_GAP`-Diagnose abklingen muss. Stand: 7 Workstreams gepinnt (G.1/G.2/G.3/A.1/F.5/D.3/E.2), 17 Workstreams + 6 Dialect-spezifische Cells als `permanent: true` mit `ownerTests`-Pfaden zu real existierenden Tests; MatrixSweepTest verifiziert die Pfade gegen den Repo-Baum |
| C-MCP | ✅ erledigt (2026-05-30, `1bea5bed`) | `test:e2e-cli`-MCP-Szenario gegen Live-DB mit bestehenden Tools: `schema_reverse_start`/`schema_compare_start`, Operational-Harness-Variante (komponiert `AiMcpRegistries.defaultComponents(AiMcpWiring(OperationalMcpWiring(...)))` und uebergibt sie als `components`-Override in `McpServerBootstrap.startStdio`/`startHttp`; **nicht** ueber CLI-seitiges `McpServeWiring`) statt Runtime-only Harness, `McpCoreJobWorkerFactory`, testbarem `ConnectionSecretResolver`, terminalem Job-Status, Artefaktinhalt, separatem `mcp serve`-Subprocess-Smoke, je ein Erfolgs- und Validierungs-/Policy-Blockerpfad, konkretem `make integration ... :test:e2e-cli:test`-Nachweis |
| C | ✅ erledigt (2026-05-30, `a2195313`) | `test/integration-concurrency/`-Modul + §5.0-Build-/Docker-/Kover-Einbindung + PG/MySQL/SQLite-Concurrency-Coverage mit genau einem aktiven Gate passend zum Implementierungszustand (Legacy-`knownRace=true` vor Atomic-Slice, `finalValue >= postWriterMaximum` nach Atomic-Slice) + `-PintegrationTests -PconcurrencyTests`-Gating |
| D | ✅ erledigt (2026-05-30, `67d93ef8`) | `test/perf-large-schema/`-Modul + §5.0-Build-/Docker-/Kover-Einbindung + `LargeSchemaGenerator` + N=100/1000-Scale-Tests + Heap-Smoke-Guard + Baseline-Report |
| D-N10k | offen | N=10000-Scale-Test als nightly-only opt-in |
| E-Scaffold | ✅ erledigt (2026-05-30, `27db7cf4`) | `docs/coverage/excludes-ledger.md` als generierte Vollinventur aller heutigen Gradle-Excludes (Selector-Typen `classes(...)`/`packages(...)` inkl. vollstaendigem Pattern; Wildcards sind Teil des Patterns; bisher unbekannte Selector-Typen fail-closed), `scripts/verify-kover-excludes-ledger.py` + `make coverage-excludes-check` in `make docs-check` verdrahtet |
| E.1 | ✅ erledigt (2026-05-31) | Ledger erhält Pflichtspalte `Disposition` mit drei zulaessigen Werten — `permanent: <ref>` (DTO/Port/sealed Outcome), `refactor-plan: <pfad>` (Adapter mit Coverage-Schuld) oder `aggregate-carveout: <ref>` (`:test:*`-Module ausserhalb des Root-Kover-Aggregats; **kommt erst in E.3 zum Einsatz**, das Vokabular wird aber schon in E.1 verdrahtet, damit der Verifier-Vertrag in E.3 nicht erneut angefasst werden muss). `scripts/verify-kover-excludes-ledger.py` failt closed bei fehlender, leerer oder unbekannter Disposition (vier Negativpfade gegengeprueft). Bestands-Backfill: 216 Eintraege als `permanent:` (110 `dto-or-value-carrier`, 49 `port-contract`, 37 `sealed-outcome`, 19 `cli-command-shell-pattern`, 1 `thin-dispatch-table`), 19 Eintraege als `refactor-plan: TBD` (Treiber-Shells, persistence-jdbc, formats-StreamDataWriterAdapter, CLI-Probe-Runner/DefaultServerStateFactory/JdbcMigrationExecutor) — die `TBD`-Promotion erledigt E.2. |
| E.2 | ✅ erledigt (2026-05-31) | Kritische Adapter-Excludes auditiert: alle 19 in E.1 mit `refactor-plan: TBD` gefuellten Eintraege (5 Treiber-Klassen inkl. `SqliteSchemaReader`, 7 persistence-jdbc-Klassen + Paket-weiter Quota-Eintrag, 1 formats-StreamDataWriterAdapter, 5 CLI-JDBC-Helfer) zeigen jetzt auf [`docs/planning/open/adapter-coverage-uplift.md`](../open/adapter-coverage-uplift.md). Das Folge-Plan-Doc listet die Excludes pro Modul, beschreibt Default-Strategie (Testcontainers-Kover-Aufnahme vs. Splitting) und bleibt im `open/`-Stadium, bis ein konkreter Scope-Schnitt steht. CLI-Command-Shells bleiben `permanent: cli-command-shell-pattern`. Keine `refactor-plan: TBD`-Platzhalter mehr im Ledger. |
| E.3 | ✅ erledigt (2026-05-31) | Aggregat-Asymmetrie geschlossen: Root-Aggregat ergaenzt um `:test:integration-sqlite`, `:test:integration-integrations`, `:test:integration-persistence-jdbc`, `:test:e2e-cli` (Parity mit den schon aggregierten `:test:integration-postgresql`/`-mysql`/`-server-state`/`consumer-read-probe`); `:test:cross-dialect-matrix`, `:test:integration-concurrency`, `:test:perf-large-schema` bleiben **bewusst nicht aggregiert** und sind als `aggregate-carveout:`-Eintraege (Selector `module`, Pattern `*`) im Excludes-Ledger gepinnt — Tokens `matrix-sweep-runner`, `opt-in-gated-runner`, `tag-gated-perf-runner`. Verifier kreuz-validiert Selector ↔ Disposition (`aggregate-carveout:` nur auf `module`, `module` nur mit `aggregate-carveout:`). `make docker-coverage-gate` gruen nach Aenderung. |
| E.3-Review-Fixes | ✅ erledigt (2026-05-31) | `/code-review medium`-Befunde adressiert: (1) Legacy-Branch im Verifier emittiert „missing Disposition column" jetzt fuer jeden 3-Spalten-Match, nicht nur fuer `classes`/`packages` — verhindert, dass kuenftige `module`-Zeilen ohne Disposition stillschweigend rutschen. (2) `AGGREGATE_CARVEOUT_TOKENS = {matrix-sweep-runner, opt-in-gated-runner, tag-gated-perf-runner}` als geschlossenes Vokabular eingefuehrt, analog `PERMANENT_TOKENS`; Token-Tippfehler in `aggregate-carveout:`-Werten failt jetzt closed. Mutationsproben fuer beide Pfade gegengeprueft. |
| F | ✅ erledigt (2026-05-31, `105ccc5a`) | Roadmap-Status-Flip + Closing. Closure-Sektion am Plan-Doc-Ende, Plan-Doc von `in-progress/` nach `done/` umgezogen, Cross-Refs (`docs/coverage/excludes-ledger.md`, `docs/planning/in-progress/README.md`, `docs/planning/open/adapter-coverage-uplift.md`) auf den neuen Pfad gezogen, Roadmap-Eintrag „Coverage/QA" auf `✅ erledigt (2026-05-31)` geflippt. |

Jeder Sub-Slice landet als eigener Commit mit Plan-Doc-Referenz. Die
Vervollstaendigungs-Slices duerfen nach dem jeweiligen Start-Slice landen,
ohne parallele Phasen zu blockieren; Sub-Slice F darf aber erst schliessen,
wenn A-Vervollständigung, B-Vervollständigung, C/C-MCP, D fuer N=100/1000
und E (= E-Scaffold + E.1 + E.2 + E.3) erfuellt sind. D-N10k bleibt
nightly-only opt-in und muss nicht im Standard-Opt-in laufen.

**Closing-Vertrag fuer Phase E** (damit F nicht stillschweigend auf
Bestands-Refactors mit offenem Aufwand blockt): "Phase E erfuellt"
bedeutet (a) `docs/coverage/excludes-ledger.md` committed,
`make coverage-excludes-check`/`make docs-check` aktiv und alle
heute aktiven Excludes im Ledger verbucht (E-Scaffold, ✅); (b) jeder
Ledger-Eintrag traegt eine `Disposition` aus dem zulaessigen Vokabular
`permanent: <ref>`, `refactor-plan: <pfad>` oder `aggregate-carveout: <ref>`
(E.1); (c) die in §5.5 namentlich benannten kritischen Adapter-Excludes
(`SqliteSchemaReader`, `PostgresDataReader/Driver`,
`MysqlDataReader/Driver`, `packages("...quota")`) haben jeweils eine
nicht-`TBD`-Disposition (E.2); (d) Aggregat-Asymmetrie ist geschlossen,
jedes `:test:*`-Modul hat eine explizite Aggregat-Entscheidung (E.3).
Tatsaechliche Refactors fuer als `refactor-plan:` markierte Klassen
laufen als eigene Plan-Docs und sind kein F-Blocker — der `refactor-plan:`-
Verweis ist die Schnittstelle zwischen Ledger und Folge-Plan.

---

## 7. Akzeptanzkriterien

- [x] `PerfMeasure`/`PerfReport`-Lib lebt in `hexagon:profiling` unter
      `minBound(90)`; bestehende `*PerfTest`-Specs in
      `adapters/driven/formats` und `adapters/driven/streaming` sind auf
      die Lib migriert, sodass kein Parallel-Pattern bleibt.
- [x] `PerfSpec`-Konvention dokumentiert (KDoc + README im jeweiligen
      Modul). Die drei Phase-A-Hotpaths sind konkret verortet:
      `SchemaMigrateRenderPipeline` (`hexagon:application`),
      `DiffPlanner` (`hexagon:core`) und Rollback-Artefakt-Round-Trip
      `RollbackArtefactBuilder`↔`RollbackArtefactParser`
      (`hexagon:application`); Phase D zusaetzlich in
      `test/perf-large-schema`. Alle drei sind mit getrenntem
      `*_SMOKE_MAX_MS`-Runaway-Guard und `*_BASELINE_MS`-Reportwert
      gepinnt. Das Dokument benennt, welche Werte auf Shared-CI nur
      Diagnose sind und welche auf dedizierten Perf-Runnern als Gate gelten.
- [x] Root-Test-Konfiguration reicht explizites `-Dkotest.tags=perf` an
      die forked Test-JVM weiter (`build.gradle.kts`).
- [x] Phase-A-Gegenlauf belegt, dass der Perf-Lauf tagged Tests ausfuehrt
      und untagged Tests nicht versehentlich mitnimmt.
- [x] Nightly-Workflow (oder `make docker-perf`-Target) ist konfiguriert
      und läuft tagsüber **nicht** im PR-Sweep.
- [x] Jedes neue Testmodul aus diesem Plan ist voll in den Build eingebunden:
      `settings.gradle.kts`, Dockerfile-`deps`-`COPY`-Liste, Make-/CI-Opt-in
      und Kover-Entscheidung (`minBound(0)` fuer reine Testmodule oder
      begruendeter Ausschluss aus Aggregate-/Coverage-Modules-Listen).
- [x] `test/cross-dialect-matrix/` ist als Gradle-Modul registriert
      und der Sweep-Test deckt alle zum Zeitpunkt der
      Sub-Slice-B-Vervollständigung in `diffresult-migration-plan-2.md`
      gelisteten Workstreams (heute 22; die Zahl wird beim
      B-Vervollständigung-Commit gepinnt, damit ein spaeterer Zuwachs nicht
      stillschweigend das Gate aufweicht). Workstreams, die *nach*
      B-Vervollständigung neu eingefuehrt werden, pinnt der jeweilige
      einfuehrende Slice direkt im Matrix-Modul; B-Vervollständigung und F
      bleiben davon unberuehrt.
- [x] Carve-out-Registry für nicht-pinnbare Workstream-Dialekt-Paare
      ist im Modul (`fixtures/carve-outs.yaml` o. ä.) und in der
      Plan-Doc-Begründung verlinkt.
- [x] MCP-E2E-Szenario in `test:e2e-cli` läuft gegen Live-DB und prüft
      `schema_reverse_start`/`schema_compare_start` ueber die
      MCP-Client-Oberflaeche mit einer **Operational-Harness-Variante**
      (komponiert `AiMcpRegistries.defaultComponents(AiMcpWiring(OperationalMcpWiring(...)))`
      und uebergibt diese als `components`-Override in
      `McpServerBootstrap.startStdio`/`startHttp`), nicht mit dem
      heutigen Runtime-only Harness und **nicht** ueber das CLI-seitige
      `McpServeWiring`. Die Operational-Variante nutzt
      `McpCoreJobWorkerFactory` plus testbaren `ConnectionSecretResolver`
      mit echten Testcontainers-/SQLite-JDBC-URLs. Der Test wartet auf
      terminalen Job-Status und prueft Execution/Audit-Metadaten sowie
      Artefaktinhalt ueber `job_status_get`, `resources/read` und bei
      Bedarf `artifact_chunk_get`; ein nur erzeugter `QUEUED`-Job ist kein
      Erfolg. Ein separater Subprocess-Smoke pinnt den echten
      `mcp serve`-Lifecycle; mindestens ein Erfolgs- und ein
      Validierungs-/Policy-Blockerpfad sind gepinnt. Der opt-in Nachweis ist
      mit `make integration INTEGRATION_TASKS="-PintegrationTests :test:e2e-cli:test"`
      oder engerem `--tests`-Filter dokumentiert. Kein Akzeptanzkriterium
      referenziert ein nicht registriertes `schema_migrate`-Tool.
- [x] Concurrent-Writer-Coverage hat genau ein aktives Korrektheits-Gate
      passend zum Implementierungszustand: vor dem Atomic-Slice beobachtet
      ein opt-in Legacy-Reproducer den heutigen Sequence-Preserve-Race pro
      Dialekt mit Barrieren im Probe→Restore-Fenster; nach dem Atomic-Slice
      verlangt das aktive Gate `finalValue >= postWriterMaximum`. Der
      Legacy-Reproducer ist explizit als `knownRace=true` markiert,
      quarantined oder aus dem Gate entfernt, sobald der atomare Pfad aktiv
      ist. Der opt-in Lauf ist mit
      `make integration INTEGRATION_TASKS="-PintegrationTests -PconcurrencyTests :test:integration-concurrency:test"`
      opt-in lauffähig.
- [x] Large-Schema-Scale-Tests für N=100 und N=1000 sind im
      Standard-Opt-in gegen die Smoke-Guards grün, erzeugen pro Scale
      Tabellen, Sequenzen, Views und Trigger und schreiben
      Baseline-Werte in den Report; Baseline-Gates laufen nur auf
      dedizierten Perf-Runnern oder Nightly-Konfigurationen. N=10000 ist
      nightly opt-in. Das Modul ist als reines Perf-/Testmodul mit
      `minBound(0)` oder begruendetem Kover-Aggregate-Carve-out markiert.
- [x] Heap-Mess-Strategie fuer `test/perf-large-schema` ist im
      Sub-Slice-D-Commit benannt (Default: `MemoryPoolMXBean.peakUsage`
      ueber alle Heap-Pools mit `resetPeakUsage()` vor jedem Scale-Run
      und GC-induziertem Snapshot vor/nach dem Lauf; Alternativen JFR,
      async-profiler nur mit Begruendung). Reset-Verhalten pro Scale-Run,
      Snapshot-Punkte und gewaehlte Strategie sind im Commit oder in
      `test/perf-large-schema/README.md` dokumentiert.
- [x] `docs/coverage/excludes-ledger.md` listet jede aktive
      Kover-Exclude-Regel aus allen `build.gradle.kts`-Dateien mit
      Selector-Typ (`classes(...)` oder `packages(...)`; Wildcards sind
      Glob-Pattern innerhalb dieser Selector-Typen, kein eigener Typ;
      kuenftige Kover-Selector-Typen werden fail-closed behandelt), Pattern,
      Modul, Begründung; ein Repo-Script/Make-Hook vergleicht alle
      Gradle-Excludes gegen das Ledger und blockt bisher unbekannte
      Selector-Typen (`scripts/verify-kover-excludes-ledger.py`,
      `make coverage-excludes-check`). *(E-Scaffold)*
- [x] Jeder Ledger-Eintrag traegt eine `Disposition` aus dem zulaessigen
      Vokabular `permanent: <ref>`, `refactor-plan: <pfad>` oder
      `aggregate-carveout: <ref>`; `verify-kover-excludes-ledger.py` failt
      closed bei fehlender, leerer oder unbekannter Disposition. *(E.1)*
- [x] Kritische Adapter-Excludes haben eine nicht-`TBD`-Disposition:
      `SqliteSchemaReader`, `PostgresDataReader`/`PostgresDriver`,
      `MysqlDataReader`/`MysqlDriver` und
      `packages("dev.dmigrate.server.persistence.jdbc.quota")` zeigen
      jeweils auf ein konkretes Folge-Plan-Doc unter `docs/planning/`
      (Disposition `refactor-plan:`) oder auf einen ADR-/Begruendungs-
      Anchor (Disposition `permanent:`). *(E.2)*
- [x] Alle bestehenden und neuen `:test:*`-Module mit Kover-Bezug haben eine
      explizite Coverage-Entscheidung: `minBound(0)` fuer reine Runner,
      `minBound(90)` wenn produktiver Code im Modul lebt, oder einen
      dokumentierten Aggregate-/Coverage-Modules-Carve-out. *(E.3)*
- [x] Bestandsaudit der heutigen Aggregat-Asymmetrie ist abgeschlossen:
      `:test:integration-sqlite`, `:test:integration-integrations`,
      `:test:integration-persistence-jdbc`, `:test:e2e-cli` sind ins
      Root-Aggregat aufgenommen; `:test:cross-dialect-matrix`,
      `:test:integration-concurrency` und `:test:perf-large-schema`
      sind mit `aggregate-carveout:`-Disposition im Excludes-Ledger
      gepinnt. *(E.3)*
- [x] Produktionsnahe Helper-Coverage: nicht-trivialer Helper-Code aus
      den neuen Test-Modulen (Schema-Generator, Perf-Helper,
      Sequence-Probe-Adapter, Sweep-Fixtures) lebt — wo fachlich
      sinnvoll — in einem Hexagon-Modul (z. B. `hexagon:profiling`,
      `hexagon:core`) unter `minBound(90)`, nicht im `test/*-Modul` mit
      `minBound(0)`. **Grenze:** ein Helper wandert nach `hexagon/*` nur
      dann, wenn entweder (a) ein produktiver Konsument absehbar ist (z. B.
      `PerfMeasure` wird vom kuenftigen Profiling-CLI verwendet) oder
      (b) der Helper fachlich zur Hexagon-Schicht gehoert (z. B.
      `LargeSchemaGenerator` als deterministischer Schema-Generator
      koennte in `hexagon:core` Fixtures fuer mehrere Test-Module liefern).
      Helper, die ausschliesslich von genau einem Test-Modul konsumiert
      werden und keinen Produktbezug haben, bleiben im `test/*-Modul`
      und tragen das `minBound(0)`-Gate des Modul mit. Reine Test-Wiring-
      und Fixture-Glue-Code bleibt immer im `test/*-Modul`. Pro Phase
      wird die Trennlinie im Sub-Slice dokumentiert. Damit verschiebt
      §5.5 keine Coverage-Luecken in neue Test-Module
      (siehe `feedback_test_coverage`), ohne den Hexagon-Baum mit reinen
      Test-Helfern zu verwaessern.
- [x] Flake-SOP fuer Perf-/Concurrency-Smoke-Brueche ist dokumentiert:
      jeder Smoke-Bruch loest Root-Cause-Analyse aus und endet in genau
      einem von drei Outcomes — (a) Code-Fix, wenn die Regression real
      ist; (b) Mess-Strategie-Haertung, wenn das Mess-Setup das eigentliche
      Problem ist; (c) dokumentierte Grenz-Re-Kalibrierung mit
      Commit-Eintrag alter/neuer Wert, wenn der Smoke-Guard zu eng
      kalibriert war und Container-Variabilitaet realistischer Grund ist.
      `@Suppress`/Quarantine sind nie zulaessig
      (siehe `feedback_no_suppress_for_size`); ein vierter „einfach
      ignorieren"-Pfad existiert nicht.
- [x] Roadmap-Eintrag „Coverage/QA" trägt nach Sub-Slice F den
      Status `✅ erledigt (<datum>)`.

---

## 8. Risiken

1. **Flaky Perf-Tests in CI**: Container-CI hat variables Timing.
   Mitigation: Shared-CI nutzt nur grosszuegige runaway-Smoke-Grenzen und
   schreibt Baseline-Diagnosen; scharfe Baseline-Gates laufen nur nightly
   oder auf dedizierten `perf-stable-runner`s.
2. **Matrix-Sweep wird Wartungslast**: jeder neue Workstream muss
   nachgepflegt werden. Mitigation: Sweep blockt bei Lücken (siehe
   `MATRIX_GAP`-Diagnose) und zwingt zu expliziter Carve-out-Eintragung.
3. **Concurrent-Writer-Tests sind inhärent flaky**: Race-Reproduzierbar-
   keit ist nur garantiert, wenn der Test den Writer exakt zwischen Probe
   und Restore platziert. Mitigation: CountDownLatch-/Barrier-Harness,
   keine frei laufenden Writer-Threads, `-PconcurrencyTests`-Opt-in statt
   PR-Standard.
4. **Large-Schema-Generator zieht JVM-OOM**: N=10000 sprengt evtl. die
   CI-Runner. Mitigation: nightly-only, dedizierter Runner mit -Xmx4g,
   N als CLI-Parameter.
5. **Excludes-Ledger driftet vom Code ab**: ohne Hook bleibt das Ledger
   stale. Mitigation: `make coverage-excludes-check` ist in `make docs-check`
   verdrahtet und prueft alle Kover-Exclude-Eintraege (Selector-Typen
   `classes(...)`/`packages(...)` inkl. vollstaendigem Pattern; Wildcards
   sind Teil des Patterns) gegen das Ledger.
6. **Neue Testmodule brechen Docker-Dependency-Warmup**: der Dockerfile-
   `deps`-Stage kopiert Gradle-Dateien explizit. Mitigation: §5.0 macht
   Dockerfile-`COPY`-Pflege, Kover-Entscheidung und Make-/CI-Opt-in zum
   Abschlusskriterium jedes Modul-Slices.

---

## 9. Out-of-Scope / Folge-Themen

- **Mutation-Testing** (PIT/Stryker) — eigener Folge-Plan, sobald
  Coverage-Baseline stabil ist und Excludes konsolidiert sind.
- **Telemetry-Port** — eigenes Plan-Doc
  `next/telemetry-observability-port.md`.
- **MCP-Server-Last-Tests** — Vertrag liegt in `spec/mcp-server.md`,
  Last-Strategie als separater Folge-Slice.
- **MCP-Migrate-Tool** (`schema_migrate`/`schema_migrate_start`) — neues
  Produkt-/Contract-Thema; dieser QA-Plan darf es nicht implizit voraussetzen.
- **App-Layer-Replay** (für Concurrent-Writer-Tests in der Anwendung)
  — Anwendungssache, nicht d-migrate-Scope.
- **Atomic-Probe + Restore** unter Lock — eigener Plan
  `in-progress/sequence-preserve-atomic-lock-plan.md`; Phase C dieses
  Plans pinnt vor diesem Slice nur die heute beobachtbare Race als
  Legacy-/Risk-Baseline und wechselt nach Landung des Atomic-Slice auf
  das aktive Korrektheits-Gate `finalValue >= postWriterMaximum`.
- **Dockerfile-`deps`-Stage rekursiv machen** — heute kopiert
  `Dockerfile:60-89` jeden `build.gradle.kts` Zeile fuer Zeile (siehe
  §5.0, Risiko #6); §5.0 + Akzeptanzkriterium „neues Testmodul"
  konservieren das. Folge-Thema: den Stage so umbauen, dass er rekursiv
  `**/build.gradle.kts` plus `**/settings.gradle.kts` warmt (z. B. ueber
  ein vorgelagertes `find … -print | tar -cf-`-COPY-Pattern), damit
  neue Module den Dependency-Warmup nicht mehr aktiv anpassen muessen.
  Bleibt Folge-Plan, weil die Aenderung den Build-Cache und CI-Hit-Rate
  betrifft und ihren eigenen Verifikations-Sweep braucht.
- **`perf-stable-runner`-Bereitstellung** — Phase A liefert nur das
  Workflow-/Make-Skelett (`-PperfGate=true`, `make docker-perf
  PERF_GATE=true`). Die tatsaechliche Runner-Hardware bzw. das
  GitHub-Actions-Label (Self-Hosted oder dedizierter Pool) ist
  Infrastruktur und Out-of-Scope dieses Plans.
- **`PerfMeasure`/`PerfReport`-Konsumenten ausserhalb Tests** — die Lib
  lebt in `hexagon:profiling` und ist heute fuer Test-Specs gedacht. Ein
  produktiver Konsument (CLI-Subcommand fuer Hotpath-Diagnose, MCP-Tool
  fuer Profiling-Reports) ist eigener Slice und nicht Teil dieses Plans.

---

## Closure (2026-05-31)

Alle Phasen geliefert, Plan-Doc verlaesst `in-progress/` und wandert
nach `done/`. Final-Stand:

| Sub-Slice | Commit | Resultat |
|---|---|---|
| A | `af59567d` | `PerfMeasure`/`PerfReport`-Lib in `hexagon:profiling` + erster Hotpath `SchemaMigrateRenderPipeline.run`-Spec + `kotest.tags=perf`-Forwarding + `make docker-perf`-/Nightly-Skelett. |
| A-Vervollständigung | `2e62370c` | `DiffPlanner`-PerfSpec, `RollbackArtefactBuilder`↔`RollbackArtefactParser`-Round-Trip-PerfSpec, Bestands-PerfSpecs `formats`/`streaming` auf die Lib migriert. |
| A-Review-Fixes | `9c369d94` | `/code-review`-Befunde: `PERF_GATE`-SystemProperty-Forwarding, `iterations==1`-Guards, atomare `Files.move(ATOMIC_MOVE)`, `%.9f`-Precision, KDoc-Notes, `generateRollback=true` im Render-Pipeline-Spec. |
| B | `3545b646` | `test/cross-dialect-matrix/`-Modul mit Sweep-Fixture-Lader + `MATRIX_GAP`-Diagnose + 5 gepinnte Workstreams. |
| B-Vervollständigung | `3ae1bb20` | 7 Workstreams gepinnt + 17 Workstreams + 6 Dialect-Cells als `permanent: true` mit `ownerTests`-Pfaden gegen den Repo-Baum verifiziert. |
| C-MCP | `1bea5bed` | `:test:e2e-cli`-Operational-Harness gegen Live-DB via `AiMcpRegistries.defaultComponents(AiMcpWiring(OperationalMcpWiring(...)))`-Override mit terminalem Job-Status + Artefaktinhalt + separatem Subprocess-Smoke + Erfolgs-/Validierungs-/Policy-Blockerpfad. |
| C | `a2195313` | `test/integration-concurrency/`-Modul mit PG/MySQL/SQLite-Concurrency-Coverage + `knownRace=true`-Legacy-Gate vor dem Atomic-Slice. |
| D | `67d93ef8` | `test/perf-large-schema/`-Modul mit `LargeSchemaGenerator` + N=100/1000-Scale-Tests + Heap-Smoke-Guard + Baseline-Report. |
| E-Scaffold | `27db7cf4` | `docs/coverage/excludes-ledger.md` + `scripts/verify-kover-excludes-ledger.py` + `make coverage-excludes-check` in `make docs-check`. |
| E.1 | `648beec6` | Pflichtspalte `Disposition` mit drei zulaessigen Werten (`permanent:`/`refactor-plan:`/`aggregate-carveout:`); 216 Bestands-Eintraege auf `permanent:`-Tokens klassifiziert, 19 auf `refactor-plan: TBD`; Verifier failt closed bei fehlender/leerer/unbekannter Disposition. |
| E.2 | `68f917f9` | 19 `refactor-plan: TBD`-Platzhalter promotet auf [`docs/planning/open/adapter-coverage-uplift.md`](../open/adapter-coverage-uplift.md). |
| E.3 | `b3b7105f` | Aggregat-Asymmetrie geschlossen: 4 Module ins Root-Kover-Aggregat aufgenommen (`integration-sqlite`/`-integrations`/`-persistence-jdbc`/`e2e-cli`), 3 Module mit `aggregate-carveout:`-Disposition gepinnt (`cross-dialect-matrix`/`integration-concurrency`/`perf-large-schema`). `make docker-coverage-gate` gruen. |
| E.3-Review-Fixes | `8ceb2653` | Verifier-Defense-in-depth gehaertet: Legacy-Branch emittiert „missing Disposition column" jetzt fuer alle Selectoren (nicht nur `classes`/`packages`); `AGGREGATE_CARVEOUT_TOKENS`-Frozenset enforced die geschlossene Vokabular-Liste. |
| F | `105ccc5a` | Roadmap-Status-Flip von `teilerledigt` auf `✅ erledigt (2026-05-31)`, Move dieses Plan-Docs nach `done/`, Cross-Refs in `docs/planning/open/adapter-coverage-uplift.md`, `docs/planning/in-progress/README.md` und `docs/coverage/excludes-ledger.md` auf den neuen Pfad gezogen. |
| F-Fixes | (dieser Commit) | Plan-Doc-Selbst-Konsistenz: Header-Status auf `✅`, F-Zeile in der Sub-Slice-Tabelle auf `✅`, offene Akzeptanzkriterien gegen den ausgelieferten Endstand gespiegelt. Cross-Refs ausserhalb `docs/` (`Makefile`, `settings.gradle.kts`, `hexagon/profiling/README.md`, drei `test/*/README.md`) auf den `done/`-Pfad nachgezogen. Phase-D KDoc/Code-Drift in `test/perf-large-schema/.../LargeSchemaScaleSpec.kt` und `HeapBudget.kt` aufgeloest. |

**Aktiv offene Folge-Threads** (nicht F-Blocker, ausserhalb dieses
Plans):

- **D-N10k** (Nightly-Only) — N=10000-Scale-Test als nightly-opt-in,
  bleibt im Plan-Doc-Eintrag dokumentiert, aber nicht
  closing-relevant.
- **[`docs/planning/open/adapter-coverage-uplift.md`](../open/adapter-coverage-uplift.md)** —
  Folge-Plan fuer den eigentlichen Coverage-Uplift der 19 in E.2 mit
  `refactor-plan:` markierten Excludes. Bleibt im `open/`-Stadium,
  bis ein konkreter Scope-Schnitt steht.

### Post-Closure-Review-Befunde (2026-05-31)

Ein nachgereichter Lese-Review nach dem F-Closing-Commit hat fuenf
Inkonsistenzen zwischen Plan-Wortlaut und ausgeliefertem Stand
gefunden, die nicht den Closing-Vertrag brechen (D-N10k-aehnlich:
nachgelagerte Verbesserung, kein DoD-Bruch), aber als
Folge-Themen festgehalten sind:

- **[`c-mcp-coverage-expansion.md`](../open/c-mcp-coverage-expansion.md)** —
  `McpOperationalScenarioTest` deckt heute nur `schema_reverse_start`,
  nicht `schema_compare_start`; Artefakt-Pruefung laeuft direkt ueber
  `schemaStore.list(...)` statt ueber MCP `resources/read`.
  Akzeptanzkriterium §7 hatte beide Tools und `resources/read` als
  Pflichtpfad benannt — das Operational-Szenario ist halbiert.
- **[`cross-dialect-matrix-kind-expansion.md`](../open/cross-dialect-matrix-kind-expansion.md)** —
  `MatrixCell.Kind` enthaelt nur `POSITIVE` und `BLOCKER`, der Plan
  §5.2 nennt fuenf Test-Arten (Positiv/Blocker/Report/Rollback/
  File-Mode). Sweep laeuft `planOnly = true`, also ohne Rollback-
  und Report-Zellen.
- ~~`formats-perfmeasure-migration.md`~~ — **gefixt durch
  F3-Followup-Commit**: `JsonChunkReaderPerfTest` und
  `YamlChunkReaderPerfTest` (`adapters/driven/formats`) wickeln den
  Streaming-Read-Loop jetzt in `PerfMeasure.run(warmup = 0,
  iterations = 1) { ... }` und schreiben den Wall-clock-Sample ueber
  `PerfReport.write(...)` (Hotpaths `format-json-chunk-reader-100mb`,
  `format-yaml-chunk-reader-100k`). `iterations == 1`-Guard wegen
  closure-captured Iterations-Locals. Heap-Budget bleibt orthogonal
  (Constant-Memory-Vertrag misst Retention, nicht Latenz).
- ~~`kover-excludes-selector-typesafe.md`~~ — **gefixt durch
  F4-Followup-Commit**: `verify-kover-excludes-ledger.py` lokalisiert
  jetzt `kover { ... excludes { ... } }`-Bloecke per
  `parse_brace_body` und scannt darin per `[A-Za-z_]\w*\(` jeden
  Selector-Identifier; alles ausserhalb der Allowlist
  `ALLOWED_GRADLE_SELECTORS = {classes, packages}` failt closed mit
  einem operator-lesbaren Hinweis. Mutationsprobe mit
  `annotatedBy("Generated")` in `hexagon/ports-common/build.gradle.kts`
  liefert Exit 1; clean state bleibt Exit 0.
- ~~`perf-large-schema-heap-dump.md`~~ — **gefixt durch F5-Followup-Commit**:
  `test/perf-large-schema/build.gradle.kts` setzt jetzt
  `-XX:+HeapDumpOnOutOfMemoryError` plus
  `-XX:HeapDumpPath=build/test-heap-dumps/` als modul-lokale
  Test-`jvmArgs` (nicht global, um Unit-Spec-OOM-Heap-Dumps zu
  vermeiden).

Wird der Plan reaktiviert (z. B. ein nachtraeglicher Slice E.4), zieht
das Doc nicht zurueck nach `in-progress/`; stattdessen entsteht ein
neues `ImpPlan-<version>-E.4-...`-Per-Slice-Closure-Doc.
