# Implementierungsplan: Quality- und Coverage-Expansion (Perf / Last / E2E)

> Status: Entwurf (2026-05-29)
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
  P95) für Render-Pipeline, Diff-Planner und Artefakt-Serialisierung
  erfasst. Harte Failure-Budgets gelten nur fuer runaway-Smoke-Grenzen
  oder dedizierte Perf-Runner; normale Nightly-Laeufe schreiben Trend-
  Reports und blocken PRs nicht wegen Container-Timing. Phase A schliesst
  zuerst die Gradle-Bruecke, damit ein explizites
  `-Dkotest.tags=perf` in die forked Test-JVM weitergereicht wird; erst danach
  gilt der CI-/Nightly-Job als nutzbar. Der Lauf bleibt opt-in, nicht Teil des
  Standard-Test-Sweeps. Phase A darf inkrementell starten, ist aber erst
  abgeschlossen, wenn alle drei Hotpaths jeweils einen Smoke-Guard und
  einen Baseline-Reportwert haben.
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
  Kover-Exclude-Regel (`classes`, `packages`, Wildcards und kuenftige
  weitere Selector-Typen) braucht eine Begründungs-Zeile in einer
  zentralen `docs/coverage/excludes-ledger.md` (ADR-light), mit Referenz auf
  Refactor-Plan oder explizitem „permanent excluded weil X"-Beleg.
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
| `kotest.tags=perf` als Filter-Konvention | ⚠️ Default-Exclude existiert; explizites Forwarding in die Test-JVM ist Phase-A-Arbeit |
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

- Vor dem ersten neuen `PerfSpec`: Root-`build.gradle.kts` so anpassen,
  dass ein explizites `System.getProperty("kotest.tags")` nicht nur den
  Default `!perf` unterdrueckt, sondern per
  `systemProperty("kotest.tags", explicitKotestTags)` an die forked Test-JVM
  weitergereicht wird.
- Budget pro Hotpath als zwei getrennte Grenzen:
  - `*_SMOKE_MAX_MS` ist ein grosszuegiger runaway guard und darf in jedem
    opt-in Perf-Lauf failen, wenn Median **oder** P95 nach Warmup deutlich
    ausserhalb der erwarteten Groessenordnung liegen. Beide Kennzahlen werden
    separat reported; ein Smoke-Guard-Bruch in einer von beiden ist ein
    Runaway-Signal.
  - `*_BASELINE_MS` ist ein Nightly-/dedicated-runner-Wert im JSON-Report.
    Er blockt nur auf Runnern mit explizitem `-PperfGate=true` oder
    Workflow-Label `perf-stable-runner`; auf Shared-Container-CI wird er
    als Regression-Diagnose reported, nicht als PR-Gate.
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
- Fixtures liegen in `test/cross-dialect-matrix/fixtures/` als
  YAML-Schema-Paare; der Sweep lädt sie deterministisch.
- Carve-out-Beispiel: PG `EXCLUDE` hat keinen MySQL-/SQLite-Positivpfad
  (siehe F.5 Vollscheibe) — das Carve-out-File listet den Verzicht
  mit Plan-Doc-Verweis.
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
  eine Operational-Harness-Variante oder startet ueber `McpServeWiring`, die
  `components = AiMcpRegistries.defaultComponents(AiMcpWiring(OperationalMcpWiring(...)))`
  in den Bootstrap gibt.
- `OperationalMcpWiring` nutzt `McpCoreJobWorkerFactory`, nicht den
  `PassthroughJobWorkerFactory`. Der Test muss beweisen, dass ein Worker
  Artefakte publiziert und der Job terminal wird; ein nur angelegter
  `QUEUED`-Job reicht nicht.
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
- Der opt-in Nachweis laeuft mindestens ueber
  `make integration INTEGRATION_TASKS="-PintegrationTests :test:e2e-cli:test"`
  oder einen engeren `--tests`-Filter fuer das neue Live-DB-Szenario.

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
  verlangt.

### 5.4 Large-Schema-Last-Tests (Phase D)

```
test/perf-large-schema/
  └─ LargeSchemaScaleSpec.kt
       @Tags("perf", "large-schema")
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
```

- Synthetische Schema-Generator-Library, deterministisch (Seed-basiert).
  Der Standard-Generator fuer diesen Plan erzeugt nicht nur Tabellen, sondern
  pro Scale auch Sequenzen, Views und Trigger. Ein reiner Tabellen-Generator
  darf nur als zusaetzlicher Diagnosefall laufen, nicht als Phase-D-DoD.
- Runs gegen JVM-`-XX:+HeapDumpOnOutOfMemoryError`, damit bei Über-
  schreitung ein analysierbarer Heap-Dump entsteht.
- Carve-out: N=10000 ist optional (sehr lange Laufzeit; nur in nightly).

### 5.5 Kover-Excludes-Konsolidierung (Phase E)

- Neue Datei `docs/coverage/excludes-ledger.md` listet pro Modul jede
  aktive Kover-Exclude-Regel aus allen
  `build.gradle.kts`-Bloecken mit:
  - Selector-Typ (`classes`, `packages`, Wildcard-Pattern; spaetere
    Kover-Selectoren analog), Wert und Modulpfad.
  - Datum, Begründung, Refactor-Plan-Verweis oder „permanent" + ADR-Ref.
- Phase E startet mit einer generierten Vollinventur, nicht mit einer
  handgepflegten Beispielmenge. Der Audit durchsucht alle
  `kover { reports { filters { excludes { ... } } } }`-Bloecke und
  extrahiert mindestens `classes(...)` und `packages(...)`; der Parser
  failt geschlossen, wenn er einen unbekannten Exclude-Selector findet,
  bis der Ledger-Vertrag um diesen Selector erweitert ist. Beispiele fuer
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
    thin wrappers bewerten und ledgern.
  - `dev.dmigrate.driver.mysql.MysqlDataReader`,
    `dev.dmigrate.driver.mysql.MysqlDriver` — als „thin wrappers" gepinnt;
    bleiben permanent excluded, aber mit Ledger-Eintrag.
- Verifikation: `make docker-coverage-gate` grün; zusätzlich prüft ein
  Repo-Script/CI-Hook, dass jede Kover-Exclude-Regel aus den
  Gradle-Dateien im Ledger vorkommt, inklusive `classes(...)`,
  `packages(...)` und Wildcards. Neue Excludes brauchen einen Commit, der
  gleichzeitig das Ledger pflegt; unbekannte Selector-Typen blocken den
  Hook, statt still ignoriert zu werden.
- Phase E normalisiert ausserdem die bestehende `:test:*`-Modul-Landschaft:
  jedes Test-/Runner-Modul, das im Root-Kover-Aggregat oder in
  Coverage-Modules-Listen auftaucht, bekommt eine explizite Entscheidung
  (`minBound(0)`, produktionscodehaltiges 90%-Gate oder begruendeter
  Aggregate-Carve-out). Das gilt fuer Bestandsmodule und neue Module
  gleichermassen, damit §5.0 nicht nur zukuenftige Dateien regelt.

---

## 6. Sub-Slice-Schnitt

| Sub-Slice | Inhalt |
|---|---|
| A | `PerfSpec`-Konvention + Root-Forwarding fuer explizites `kotest.tags` + erster Hotpath (`SchemaMigrateRenderPipeline.run`) + getrennte Smoke-/Baseline-Budgets + nightly-Workflow-/`make docker-perf`-Skelett |
| A-Vervollständigung | Diff-Planner- und Artefakt-Serialisierungs-PerfSpecs mit denselben Smoke-/Baseline-Vertraegen; Phase A ist erst nach allen drei Hotpaths schliessbar |
| B | `test/cross-dialect-matrix/`-Modul + §5.0-Build-/Docker-/Kover-Einbindung + Sweep-Fixture-Lader + erste 5 Workstreams |
| B-Vervollständigung | restliche Workstreams + Carve-out-Registry |
| C-MCP | `test:e2e-cli`-MCP-Szenario gegen Live-DB mit bestehenden Tools: `schema_reverse_start`/`schema_compare_start`, Operational-Harness oder `McpServeWiring` statt Runtime-only Harness, `McpCoreJobWorkerFactory`, testbarem `ConnectionSecretResolver`, terminalem Job-Status, Artefaktinhalt, separatem `mcp serve`-Subprocess-Smoke, je ein Erfolgs- und Validierungs-/Policy-Blockerpfad, konkretem `make integration ... :test:e2e-cli:test`-Nachweis |
| C | `test/integration-concurrency/`-Modul + §5.0-Build-/Docker-/Kover-Einbindung + PG/MySQL/SQLite-Concurrency-Coverage mit genau einem aktiven Gate passend zum Implementierungszustand (Legacy-`knownRace=true` vor Atomic-Slice, `finalValue >= postWriterMaximum` nach Atomic-Slice) + `-PintegrationTests -PconcurrencyTests`-Gating |
| D | `test/perf-large-schema/`-Modul + §5.0-Build-/Docker-/Kover-Einbindung + `LargeSchemaGenerator` + N=100/1000-Scale-Tests + Heap-Smoke-Guard + Baseline-Report |
| D-N10k | N=10000-Scale-Test als nightly-only opt-in |
| E | `docs/coverage/excludes-ledger.md` + generierte Vollinventur aller Gradle-Excludes (`classes`, `packages`, Wildcards, unbekannte Selector-Typen fail-closed) + Repo-Script/CI-Hook-Skizze + Bestands-Audit |
| F | Roadmap-Status-Flip + Closing |

Jeder Sub-Slice landet als eigener Commit mit Plan-Doc-Referenz. Die
Vervollstaendigungs-Slices duerfen nach dem jeweiligen Start-Slice landen,
ohne parallele Phasen zu blockieren; Sub-Slice F darf aber erst schliessen,
wenn A-Vervollständigung, B-Vervollständigung, C/C-MCP, D fuer N=100/1000
und E erfuellt sind. D-N10k bleibt nightly-only opt-in und muss nicht im
Standard-Opt-in laufen.

---

## 7. Akzeptanzkriterien

- [ ] `PerfSpec`-Konvention dokumentiert (KDoc + README in
      `test/perf-*`); Render-Pipeline, Diff-Planner und
      Artefakt-Serialisierung sind jeweils mit getrenntem
      `*_SMOKE_MAX_MS`-Runaway-Guard und `*_BASELINE_MS`-Reportwert
      gepinnt. Das Dokument benennt, welche Werte auf Shared-CI nur
      Diagnose sind und welche auf dedizierten Perf-Runnern als Gate gelten.
- [ ] Root-Test-Konfiguration reicht explizites `-Dkotest.tags=perf` an
      die forked Test-JVM weiter; ein Gegenlauf belegt, dass der Perf-Lauf
      tagged Tests ausfuehrt und untagged Tests nicht versehentlich mitnimmt.
- [ ] Nightly-Workflow (oder `make docker-perf`-Target) ist konfiguriert
      und läuft tagsüber **nicht** im PR-Sweep.
- [ ] Jedes neue Testmodul aus diesem Plan ist voll in den Build eingebunden:
      `settings.gradle.kts`, Dockerfile-`deps`-`COPY`-Liste, Make-/CI-Opt-in
      und Kover-Entscheidung (`minBound(0)` fuer reine Testmodule oder
      begruendeter Ausschluss aus Aggregate-/Coverage-Modules-Listen).
- [ ] `test/cross-dialect-matrix/` ist als Gradle-Modul registriert
      und der Sweep-Test deckt alle 22 Workstreams aus
      `diffresult-migration-plan-2.md` §11.2.
- [ ] Carve-out-Registry für nicht-pinnbare Workstream-Dialekt-Paare
      ist im Modul (`fixtures/carve-outs.yaml` o. ä.) und in der
      Plan-Doc-Begründung verlinkt.
- [ ] MCP-E2E-Szenario in `test:e2e-cli` läuft gegen Live-DB und prüft
      `schema_reverse_start`/`schema_compare_start` ueber die
      MCP-Client-Oberflaeche mit Operational-Harness oder `McpServeWiring`,
      nicht mit einem Runtime-only Harness. `OperationalMcpWiring` gibt
      `AiMcpRegistries.defaultComponents(...)` in den Bootstrap und nutzt
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
- [ ] Concurrent-Writer-Coverage hat genau ein aktives Korrektheits-Gate
      passend zum Implementierungszustand: vor dem Atomic-Slice beobachtet
      ein opt-in Legacy-Reproducer den heutigen Sequence-Preserve-Race pro
      Dialekt mit Barrieren im Probe→Restore-Fenster; nach dem Atomic-Slice
      verlangt das aktive Gate `finalValue >= postWriterMaximum`. Der
      Legacy-Reproducer ist explizit als `knownRace=true` markiert,
      quarantined oder aus dem Gate entfernt, sobald der atomare Pfad aktiv
      ist. Der opt-in Lauf ist mit
      `make integration INTEGRATION_TASKS="-PintegrationTests -PconcurrencyTests :test:integration-concurrency:test"`
      opt-in lauffähig.
- [ ] Large-Schema-Scale-Tests für N=100 und N=1000 sind im
      Standard-Opt-in gegen die Smoke-Guards grün, erzeugen pro Scale
      Tabellen, Sequenzen, Views und Trigger und schreiben
      Baseline-Werte in den Report; Baseline-Gates laufen nur auf
      dedizierten Perf-Runnern oder Nightly-Konfigurationen. N=10000 ist
      nightly opt-in. Das Modul ist als reines Perf-/Testmodul mit
      `minBound(0)` oder begruendetem Kover-Aggregate-Carve-out markiert.
- [ ] `docs/coverage/excludes-ledger.md` listet jede aktive
      Kover-Exclude-Regel aus allen `build.gradle.kts`-Dateien mit
      Selector-Typ (`classes`, `packages`, Wildcard; weitere Selector-Typen
      analog), Wert, Modul, Begründung + Refactor-Plan oder
      „permanent + ADR-Ref"; ein Repo-Script/CI-Hook vergleicht alle
      Gradle-Excludes gegen das Ledger und blockt unbekannte Selector-Typen.
- [ ] Alle bestehenden und neuen `:test:*`-Module mit Kover-Bezug haben eine
      explizite Coverage-Entscheidung: `minBound(0)` fuer reine Runner,
      `minBound(90)` wenn produktiver Code im Modul lebt, oder einen
      dokumentierten Aggregate-/Coverage-Modules-Carve-out.
- [ ] Roadmap-Eintrag „Coverage/QA" trägt nach Sub-Slice F den
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
   stale. Mitigation: Pre-commit-/CI-Hook im Plan-Phase-E-Scope, der alle
   Kover-Exclude-Selectoren (`classes`, `packages`, Wildcards, kuenftige
   Selector-Typen) gegen das Ledger prueft und unbekannte Selector-Typen
   fail-closed behandelt.
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
