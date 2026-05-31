# Formats-PerfTest-Migration auf PerfMeasure (Folge-Fix zu Phase A)

- **Status**: Draft (Trigger registriert, kein Scope-Schnitt)
- **Trigger**: Post-Closure-Review des
  [`quality-coverage-expansion-plan`](../done/quality-coverage-expansion-plan.md)
  am 2026-05-31 hat festgestellt, dass zwei perf-getaggte Tests in
  `adapters/driven/formats` noch ad-hoc Heap-Messung verwenden und
  nicht auf die `PerfMeasure`/`PerfReport`-Lib migriert wurden — der
  Plan-DoD §7 Z. 642 sagt aber explizit „bestehende `*PerfTest`-Specs
  in `adapters/driven/formats` und `adapters/driven/streaming` sind
  auf die Lib migriert, sodass kein Parallel-Pattern bleibt".
- **Aktivierungsbedingung**: Wenn die DoD wieder als Gate auditiert
  wird (z. B. vor 1.0.0-RC), oder wenn jemand das Parallel-Pattern
  bei einer Aenderung am Reader-Code als ueberraschend empfindet.

## Befund-Snapshot (2026-05-31)

- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/data/json/JsonChunkReaderPerfTest.kt:24`
  und
  `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/data/yaml/YamlChunkReaderPerfTest.kt:24`
  nutzen weder `PerfMeasure` noch `PerfReport`. Beide messen heute
  ad-hoc per `Runtime.getRuntime().totalMemory()`-Delta oder
  `System.nanoTime()`-Differenz.
- Streaming-Adapter ist bereits migriert
  (`adapters/driven/streaming/.../*PerfTest`-Specs nutzen `PerfMeasure`)
  und in der Phase-A-Vervollständigung als Beweis dafuer gelandet,
  dass die Lib auch fuer Bestands-Specs taugt.

## Skizzierte Arbeit

- `JsonChunkReaderPerfTest` + `YamlChunkReaderPerfTest` auf
  `PerfMeasure.run(warmup=..., iterations=...) { ... }`-Pattern
  umstellen, Smoke-/Baseline-Werte ueber `PerfReport.write(...)`
  pinnen.
- Heap-Messung optional als zweite Phase: `HeapBudget`-Pattern aus
  `test/perf-large-schema` kopieren oder die Heap-Erwartung
  weglassen, wenn der Test heute primaer Latenz misst.
- Pruefen, ob ein „kein Parallel-Pattern"-Gate (Detekt-Rule, Grep-
  Lint) sich rentiert, um den Drift kuenftig zu blocken.

## Nicht-Ziel

- Keine neuen Perf-Specs in `formats` (nur Migration des
  Bestands).
- Keine Aenderung an `PerfMeasure`/`PerfReport`-Lib selbst.
