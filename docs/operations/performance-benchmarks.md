# Performance-Benchmarks

> **Stand:** 16.06.2026 (Milestone 0.9.9) · **Zielgruppe:** Maintainer/CI.
>
> Dieses Dokument beschreibt die **Methodik** des vorhandenen
> Performance-Frameworks und seine aktuellen Budgets. Es veröffentlicht
> **keine** Mess-Zahlen: die Reports entstehen zur Laufzeit unter `build/` und
> sind kein eingechecktes Artefakt (siehe [§5](#5-reports)). Mit **🔮** markierte
> Punkte (formale Abnahme-Benchmarks) sind 1.0.0-Ziele und **noch nicht**
> validiert (siehe [§8](#8-abnahme-lücke-und-ausblick)).

## 1. Was gemessen wird — und was nicht

d-migrate hat ein **Regressions-/Budget-Framework**, keinen veröffentlichten
Benchmark-Zahlenstand. Es misst die Laufzeit (und teils den Heap-Peak)
ausgewählter Hotpaths gegen feste Budgets und schlägt bei Ausreißern an. Ziel
ist, Performance-Regressionen früh zu fangen — nicht, absolute Durchsatzzahlen zu
publizieren (für belastbare Zahlen fehlt eine definierte, normierte
Hardware-/Container-Umgebung).

## 2. Bausteine

| Baustein | Rolle |
| -------- | ----- |
| `PerfMeasure` | misst eine Operation über mehrere Iterationen und liefert ein `PerfSample` (median/p95 etc.) |
| `PerfSample` | aggregierte Messwerte eines Laufs (`medianMs`, `p95Ms`, …) |
| `PerfReport` | schreibt pro Hotpath einen JSON-Trend-Report nach `build/reports/perf/<hotpath>.json` |

Die Bausteine liegen im Modul `hexagon:profiling` (`…/profiling/perf`). Die
Perf-Specs selbst sind `perf`-getaggte Kotest-Specs in den jeweiligen Modulen.

## 3. Hotpaths und aktuelle Budgets

Jeder Hotpath hat **zwei** Budgets: ein **Smoke-Budget** (Runaway-Guard, wird
immer geprüft) und ein engeres **Baseline-Budget** (Nightly-Ziel, nur als
Gate unter `PERF_GATE=true`, sonst Diagnose — siehe [§7](#7-zwei-budget-modell)).

| Hotpath | Was | Smoke | Baseline |
| ------- | --- | ----- | -------- |
| `diff-planner` | `DiffPlanner.plan` (synthetisches 100-Tabellen-Schema) | 5 000 ms | 250 ms |
| `schema-migrate-render-pipeline` | Render-Pipeline für `schema migrate` | 5 000 ms | 250 ms |
| `rollback-artefact-round-trip` | Rollback-Artefakt bauen + parsen | 5 000 ms | 250 ms |

Daten-/Format-Pfade prüfen zusätzlich ein **Heap-Budget** gegen große Fixtures
(konstanter Speicher beim Streaming):

| Hotpath | Fixture |
| ------- | ------- |
| `format-json-chunk-reader-100mb` | 100-MB-JSON, streaming mit konstantem Speicher |
| `format-yaml-chunk-reader-100k` | 100k-Zeilen-YAML |
| `large-json-pull-spike` | Pull-Reader-Spitzenlast |
| `streaming-importer-reorder` | Importer-Reorder gegen 100-MB-Fixture |

## 4. Large-Schema-Scale

Der Scale-Spec (`test/perf-large-schema`) baut synthetische gemischte Schemas
(Tabellen + Sequenzen + Views + Functions + Trigger) und fährt die volle
`current=leer → desired=schema` Planner+PostgreSQL-Renderer-Pipeline gegen ein
Zeit- **und** Heap-Budget:

| Scale | Zeit-Budget (Smoke) | Heap-Budget |
| ----- | ------------------- | ----------- |
| N = 100 | 30 s | 256 MB |
| N = 1000 | 120 s | 1024 MB |
| N = 10000 | 🔮 zurückgestellt (Nightly-Opt-in, eigener Spec) |  |

Opt-in-Lauf:

```
make docker-perf MODULES=":test:perf-large-schema"
```

## 5. Reports

`PerfReport.write` legt pro Hotpath `build/reports/perf/<hotpath>.json` an —
ein **Laufzeit-Artefakt**, kein eingecheckter Stand. Es dient dem
Nightly-/Trend-Vergleich, nicht als veröffentlichte Benchmark-Zahl.

## 6. Ausführen (`make docker-perf`)

| Aufruf | Wirkung |
| ------ | ------- |
| `make docker-perf` | führt die `perf`-getaggten Specs aus (opt-in, nicht im Standard-Build) |
| `make docker-perf MODULES=":<modul>"` | auf ein Modul einschränken (z. B. `:test:perf-large-schema`) |
| `make docker-perf PERF_GATE=true` | macht das **Baseline-Budget** zum harten Fehler |

Perf-Specs laufen **nie** im Standard-`docker build .` (Tag-Filter
`!integration & !perf`); nur `-Dkotest.tags=perf` bzw. `make docker-perf` zieht
sie. Hintergrund zur Tag-Steuerung: [`../user/quality.md`](../user/quality.md).

## 7. Zwei-Budget-Modell

- **Smoke-Budget** (z. B. 5 000 ms; 30 s/120 s bei Scale): grober Runaway-Guard.
  Wird **immer** geprüft (auch auf geteilter CI), damit pathologische
  Regressionen sofort auffallen.
- **Baseline-Budget** (z. B. 250 ms): das engere Nightly-Ziel. Auf geteilter CI
  nur **diagnostisch** (Drift wird berichtet, bricht den Lauf nicht); erst
  `PERF_GATE=true` macht es zum harten Gate. So flaken geteilte CI-Runner nicht
  an Mess-Schwankungen, während der Nightly-Lauf echte Drift fängt.

## 8. Abnahme-Lücke und Ausblick

Die formalen **Abnahme-Benchmarks aus dem Lastenheft** sind 1.0.0-QA-Ziele und
hier noch **nicht** abgebildet:

- 🔮 **LF 8.1** — „1 Mio. Datensätze Export/Import ohne Datenverlust": es gibt
  heute **keinen** Datenvolumen-Test in dieser Größe (die Format-/Streaming-Specs
  prüfen konstanten Speicher gegen 100-MB-Fixtures, nicht 1 Mio. Zeilen
  end-to-end).
- 🔮 **LF 8.2** — „DDL-Generierung 1 000 Tabellen **< 30 s**": das N=1000-Scale
  läuft heute mit einem **Smoke-Budget von ≤ 120 s**, also bewusst lockerer als
  die 8.2-Schwelle. Die strengere Abnahme-Messung steht noch aus.

Beide Abnahme-Budgets brauchen eine **definierte Mess-Umgebung** — eine absolute
Wandzeit-Schwelle ist nur auf fixierter Kapazität sinnvoll. Das ist in
[ADR 0018](../adr/0018-normalized-perf-measurement-environment.md) (accepted)
ratifiziert: ein drittes **Acceptance-Tier** (LF-8.2-Absolutbudgets) zusätzlich zum
hier beschriebenen Smoke/Baseline-Zwei-Budget-Modell, gemessen unter einer
**Container-Caps-Referenz** (`--cpus=2`/`--memory=4g`) auf einem designierten
Nightly-Runner, mit **Kalibrierungs-Guard** (Off-Spec-Host → Rückfall auf diagnostisch).
Dieser Abschnitt wird beim Bau des Acceptance-Tiers um die konkreten Mess-Tabellen
ergänzt.

Diese beiden Benchmarks (und die SHA-256-Integritätsverifikation, LF/LN-009)
werden mit dem 1.0.0-RC-Zyklus nachgezogen; die Roadmap-1.0.0-Tabelle in
[`../planning/in-progress/roadmap.md`](../planning/in-progress/roadmap.md) führt
die QA-Abnahmeziele.

## Verwandte Dokumentation

- [`../user/quality.md`](../user/quality.md) — detekt, Kover, Test-Tags
- [`job-executor.md`](job-executor.md) — Job-Executor-Sizing/Saturation (Betrieb)
- [`../planning/in-progress/roadmap.md`](../planning/in-progress/roadmap.md) — 1.0.0-QA-Abnahmeziele
