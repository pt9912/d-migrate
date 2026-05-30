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
  (`SqliteSchemaReader`, `MysqlDataReader`, `MysqlDriver`, etc.). Die
  Memory-Note `feedback_test_coverage` pinnt explizit „90% pro Modul
  als Ziel; I/O-Glue-Ausreden nicht akzeptieren".

---

## 3. Scope

### 3.1 In Scope

- **Phase A — Performance-Baseline**: per-Hotpath ein Kotest-`PerfSpec`
  mit `kotest.tags=perf`, der wiederholbare Benchmark-Werte (Median,
  P95) für Render-Pipeline, Diff-Planner und Artefakt-Serialisierung
  pinned. CI-Job läuft mit `-Dkotest.tags=perf` opt-in, nicht im
  Standard-Test-Sweep.
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
  Szenario in `test:e2e-cli`, das denselben Live-DB-Pfad über
  `mcp serve`/Job-/Artefakt-Status ausführt. Phase C beweist den
  Carve-out aus `ImpPlan-0.9.7-sqlite-sequence-preserve-current-value.md`
  §6 und liefert die Baseline für den atomaren Folge-Slice.
- **Phase D — Large-Schema-Last-Tests**: synthetische Schemata mit
  N=100/1000/10000 Tabellen/Sequenzen/Views/Triggern; pinnt
  Render-Throughput und Memory-Footprint als Hash-of-Numbers im Report.
- **Phase E — Kover-Excludes-Konsolidierung**: jede aktive
  `kover.excludes`-Klasse braucht eine Begründungs-Zeile in einer
  zentralen `docs/coverage/excludes-ledger.md` (ADR-light), mit Referenz auf
  Refactor-Plan oder explizitem „permanent excluded weil X"-Beleg.
  Memory-Note `feedback_no_suppress_for_size` analog.
- **Phase F — Schliessen**: alle Phasen-DoDs erfüllt; Roadmap-Status
  von `teilerledigt` auf `✅ erledigt` ziehen.

### 3.2 Out of Scope

- Telemetry-/OpenTelemetry-Adapter (eigener Plan
  `next/telemetry-observability-port.md`).
- MCP-Server-Load-Tests (eigene Last-Strategie, gehört zu
  `mcp-server.md`-Vertrag).
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
| `kotest.tags=perf` als Filter-Konvention | ✅ (siehe `build.gradle.kts:91`) |
| Atomar-Lock-Plan für Concurrent-Writer-Pattern | ⚠️ Draft (`sequence-preserve-atomic-lock-plan.md`) |
| Kover-`koverVerify` als CI-Gate | ✅ pro Modul `minBound(90)` |

---

## 5. Architektur

### 5.1 Performance-Benchmarks (Phase A)

```
hexagon/<modul>/src/test/kotlin/...PerfSpec.kt
  @Tags("perf")
  benchmark("SchemaMigrateRenderPipeline.run for 100-op plan") {
      val plan = SyntheticDiffResultGenerator.buildAlterTable(opCount = 100, ...)
      val pipeline = SchemaMigrateRenderPipeline(...)
      measureTimedValue {
          pipeline.run(...)
      }.duration.toMillis() shouldBeLessThan budgetMillis
  }
```

- Budget pro Hotpath als Konstante im Test (`RENDER_BUDGET_MS = 250`,
  `DIFF_BUDGET_MS = 100`). Bei Überschreitung Exit 1; jeder Bump muss
  durch Commit-Message dokumentiert sein.
- Pro Lauf: 5 Warmup + 20 Mess-Iterationen; Report-Output
  Median+P95+P99 in `build/reports/perf/<hotpath>.json`.
- CI-Job läuft tagsüber nicht im PR-Sweep, sondern als nightly
  via separater GitHub-Actions-Workflow oder als manueller
  `make docker-perf`-Trigger.

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

### 5.3 MCP-E2E- und Concurrent-Writer-Tests (Phase C)

**MCP-E2E-Pfad:** `test:e2e-cli` bekommt ein Szenario, das `mcp serve`
über den bestehenden Harness startet, eine Live-DB-Connection aus den
Testcontainers-/SQLite-Fixtures registriert, `schema_migrate` aufruft und
Job-Status plus Artefaktinhalt prüft. Damit ist der QA-Scope nicht nur auf
CLI-/Renderer-Unit-Pfade beschränkt; der MCP-Pfad muss mindestens einen
erfolgreichen Migrate-Lauf und einen Blocker-Lauf gegen Live-DB abdecken.

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

           val outcome = runMigrationPreserveAgainst(
               pg,
               sequenceCurrentValueProbe = gatedProbe,
           )
           writerThread.join()
           val finalValue = pg.queryOne<Long>("SELECT last_value FROM order_seq")
           // Without atomic lock: finalValue is the probed value, NOT the
           // post-writer maximum → reproducer for the documented race.
           finalValue shouldBe outcome.observedProbeValue
       }
```

- Pro Dialekt ein Race-Test, der den dokumentierten Carve-out
  reproduziert. PG/MySQL laufen über Testcontainers; SQLite nutzt eine
  echte Datei-DB mit zwei Connections. Der Test muss den Writer per
  Latches/Barrieren exakt im Probe→Restore-Fenster platzieren; ein
  frei laufender Writer-Thread ist nicht zulässig, weil er nach dem Restore
  weiterdrehen und den stale-restore-Befund maskieren kann.
- Wenn `sequence-preserve-atomic-lock-plan.md` landet,
  ergänzen wir die Tests um „mit atomarem Pfad: finalValue ist
  Post-Writer-Maximum".
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
       data class Scale(val n: Int, val expectedRenderMaxMs: Long, val maxHeapMb: Long)
       forAll(
           Scale(n = 100,   expectedRenderMaxMs = 250,   maxHeapMb = 256),
           Scale(n = 1000,  expectedRenderMaxMs = 2500,  maxHeapMb = 512),
           Scale(n = 10000, expectedRenderMaxMs = 30000, maxHeapMb = 2048),
       ) { scale ->
           val schema = LargeSchemaGenerator.tables(scale.n)
           val budget = HeapBudget.start(scale.maxHeapMb)
           val duration = measureTimedValue { runMigratePipeline(schema) }.duration
           duration.toMillis() shouldBeLessThan scale.expectedRenderMaxMs
           budget.peakUsedMb shouldBeLessThan scale.maxHeapMb
       }
```

- Synthetische Schema-Generator-Library, deterministisch (Seed-basiert).
- Runs gegen JVM-`-XX:+HeapDumpOnOutOfMemoryError`, damit bei Über-
  schreitung ein analysierbarer Heap-Dump entsteht.
- Carve-out: N=10000 ist optional (sehr lange Laufzeit; nur in nightly).

### 5.5 Kover-Excludes-Konsolidierung (Phase E)

- Neue Datei `docs/coverage/excludes-ledger.md` listet pro Modul jede
  aktive `kover.excludes`-Klasse mit:
  - Datum, Begründung, Refactor-Plan-Verweis oder „permanent" + ADR-Ref.
- Bestehende Excludes durchgehen:
  - `dev.dmigrate.driver.sqlite.SqliteSchemaReader` — heute als
    „edge cases requiring exotic real-world schemas" begründet; muss
    in einen Splittungs-Plan überführt werden, parallel zu Phase B
    Cross-Dialekt-Matrix.
  - `dev.dmigrate.driver.mysql.MysqlDataReader`,
    `dev.dmigrate.driver.mysql.MysqlDriver` — als „thin wrappers" gepinnt;
    bleiben permanent excluded, aber mit Ledger-Eintrag.
- Verifikation: `make docker-coverage-gate` grün; neue Excludes brauchen
  einen Commit, der gleichzeitig das Ledger pflegt (Hook prüft das).

---

## 6. Sub-Slice-Schnitt

| Sub-Slice | Inhalt |
|---|---|
| A | `PerfSpec`-Konvention + 1 Hotpath (`SchemaMigrateRenderPipeline.run`) + Budget-Konstante + nightly-Workflow-Skelett |
| B | `test/cross-dialect-matrix/`-Modul + Sweep-Fixture-Lader + erste 5 Workstreams |
| B-Vervollständigung | restliche Workstreams + Carve-out-Registry |
| C-MCP | `test:e2e-cli`-MCP-Szenario gegen Live-DB: `schema_migrate`, Job-Status, Artefaktinhalt, je ein Erfolgs- und Blockerpfad |
| C | `test/integration-concurrency/`-Modul + PG/MySQL/SQLite-Race-Tests + `-PintegrationTests -PconcurrencyTests`-Gating |
| D | `LargeSchemaGenerator` + N=100/1000-Scale-Tests + Heap-Budget |
| D-N10k | N=10000-Scale-Test als nightly-only opt-in |
| E | `docs/coverage/excludes-ledger.md` + Pre-commit-Hook-Skizze + Bestands-Audit |
| F | Roadmap-Status-Flip + Closing |

Jeder Sub-Slice landet als eigener Commit mit Plan-Doc-Referenz; die
Sweep-Sub-Slices (B-Vervollständigung, D-N10k) dürfen nachgereicht
werden, ohne dass A/B/C/E darauf warten.

---

## 7. Akzeptanzkriterien

- [ ] `PerfSpec`-Konvention dokumentiert (KDoc + README in
      `test/perf-*`); mindestens 1 Hotpath mit Budget-Konstante pinned.
- [ ] Nightly-Workflow (oder `make docker-perf`-Target) ist konfiguriert
      und läuft tagsüber **nicht** im PR-Sweep.
- [ ] `test/cross-dialect-matrix/` ist als Gradle-Modul registriert
      und der Sweep-Test deckt alle 22 Workstreams aus
      `diffresult-migration-plan-2.md` §11.2.
- [ ] Carve-out-Registry für nicht-pinnbare Workstream-Dialekt-Paare
      ist im Modul (`fixtures/carve-outs.yaml` o. ä.) und in der
      Plan-Doc-Begründung verlinkt.
- [ ] MCP-E2E-Szenario in `test:e2e-cli` läuft gegen Live-DB und prüft
      `schema_migrate` über `mcp serve` inklusive Job-Status und
      Artefaktinhalt; mindestens ein Erfolgs- und ein Blockerpfad sind
      gepinnt.
- [ ] Concurrent-Writer-Test reproduziert den Sequence-Preserve-Race
      pro Dialekt mit Barrieren im Probe→Restore-Fenster und ist mit
      `make integration INTEGRATION_TASKS="-PintegrationTests -PconcurrencyTests :test:integration-concurrency:test"`
      opt-in lauffähig.
- [ ] Large-Schema-Scale-Tests für N=100 und N=1000 sind grün; N=10000
      ist nightly opt-in.
- [ ] `docs/coverage/excludes-ledger.md` listet jede aktive
      Kover-Exclude-Klasse mit Begründung + Refactor-Plan oder
      „permanent + ADR-Ref".
- [ ] Roadmap-Eintrag „Coverage/QA" trägt nach Sub-Slice F den
      Status `✅ erledigt (<datum>)`.

---

## 8. Risiken

1. **Flaky Perf-Tests in CI**: Container-CI hat variables Timing.
   Mitigation: Perf-Tests laufen nur nightly, Budgets als 99.9-Perzentil
   statt scharfem Median.
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
   stale. Mitigation: Pre-commit-Hook im Plan-Phase-E-Scope.

---

## 9. Out-of-Scope / Folge-Themen

- **Mutation-Testing** (PIT/Stryker) — eigener Folge-Plan, sobald
  Coverage-Baseline stabil ist und Excludes konsolidiert sind.
- **Telemetry-Port** — eigenes Plan-Doc
  `next/telemetry-observability-port.md`.
- **MCP-Server-Last-Tests** — Vertrag liegt in `spec/mcp-server.md`,
  Last-Strategie als separater Folge-Slice.
- **App-Layer-Replay** (für Concurrent-Writer-Tests in der Anwendung)
  — Anwendungssache, nicht d-migrate-Scope.
- **Atomic-Probe + Restore** unter Lock — eigener Plan
  `in-progress/sequence-preserve-atomic-lock-plan.md`; Phase C dieses
  Plans pinnt nur die heute beobachtbare Race.
