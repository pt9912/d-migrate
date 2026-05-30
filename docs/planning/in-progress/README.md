# Laufende Arbeit

Aktive Roadmap- und Slice-Pläne mit gestartetem Implementierungs-Pfad.
Zwei Typen leben hier:

1. **Top-Level-Aggregatoren** mit sprechenden Namen:
   - [`roadmap.md`](roadmap.md) — Gesamt-Milestone-Sicht
   - [`diffresult-migration-plan-2.md`](diffresult-migration-plan-2.md) —
     0.9.7-Workstream-Aggregator
2. **Per-Feature-Umbrella-Pläne**, die einen mehrphasigen Slice
   tragen, bei dem mindestens ein Commit den Plan referenziert:
   - [`ImpPlan-0.9.7-sqlite-sequence-preserve-current-value.md`](ImpPlan-0.9.7-sqlite-sequence-preserve-current-value.md)
     — 0.9.7-E.3-Folge-Slice, schliesst die SQLite-`preserveCurrentValue`-Lücke
     aus dem `ImpPlan-0.9.7-sequence-preserve-current-value.md`-Plan in `done/`.
   - [`sequence-preserve-atomic-lock-plan.md`](sequence-preserve-atomic-lock-plan.md)
     — Draft für den dialektübergreifenden Atomic-Probe-und-Restore-Pfad
     (Folge-Slice der bestehenden `preserveCurrentValue`-Workstreams).
   - [`quality-coverage-expansion-plan.md`](quality-coverage-expansion-plan.md)
     — QA-/Coverage-Erweiterung über §11 DoD hinaus: Perf-Baseline
     (`PerfMeasure`/`PerfReport`-Lib + drei Hotpaths), Cross-Dialekt-
     Matrix-Sweep, Concurrent-Writer-Race-Reproducer, Large-Schema-Last-
     Tests und Kover-Excludes-Ledger.
   - [`wiring-factory-port-coverage.md`](wiring-factory-port-coverage.md)
     — Folge-Tranche fuer die CLI-Wirings mit eager Hikari-/Adapter-
     Konstruktion; fuehrt Factory-Ports und Fake-Bundles fuer
     modul-isolierte Wiring-Coverage ein.

Lebenszyklus und Verzeichnisstruktur sind in
[`ADR 0004`](../../adr/0004-documentation-and-planning-structure.md)
festgehalten.

## Konvention für Einträge

- Status-Header pflegen: `> Status: In Progress (<refresh-datum>)`
  plus pro abgeschlossene Phase einen Commit-Ref-Hinweis.
- Per-Slice-Closure-Pläne wandern beim Abschluss eines Slice nach
  `../done/ImpPlan-<version>-<slice>.md`. Die Umbrella selbst bleibt
  in `in-progress/`, bis alle Phasen geliefert sind — dann wandert
  sie als Ganzes nach `../done/` mit einer `## Closure`-Sektion am
  Ende, die den finalen Stand zusammenfasst.
- Top-Level-Aggregatoren (`roadmap.md`,
  `diffresult-migration-plan-2.md`) wandern nicht — sie sind dauerhaft
  aktiv.

## Wann **nicht** hierher

- Scope steht, aber kein Implementierungs-Commit existiert →
  `../next/`.
- Trigger ohne Scope → `../open/`.
- Alle Phasen geliefert (für Umbrella-Pläne) → `../done/`.
- Einzelne Sub-Slice-Closure-Notiz → direkt nach
  `../done/ImpPlan-<version>-<slice>.md`, der Umbrella bleibt
  hier.
