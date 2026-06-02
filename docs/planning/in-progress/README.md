# Laufende Arbeit

Aktive Roadmap- und Slice-Pläne mit gestartetem Implementierungs-Pfad.
Zwei Typen leben hier:

1. **Top-Level-Aggregatoren** mit sprechenden Namen:
   - [`roadmap.md`](roadmap.md) — Gesamt-Milestone-Sicht (dauerhaft
     aktiv)
2. **Per-Feature-Umbrella-Pläne**, die einen mehrphasigen Slice
   tragen, bei dem mindestens ein Commit den Plan referenziert:
   - [`atomic-preserve-followups.md`](atomic-preserve-followups.md)
     — In Progress (Stand 2026-06-01): Backlog-Tracker für die
     6 Code-Review-Findings + Dead-Code-Cleanup zum
     atomic-preserve-Slice
     ([`../done/sequence-preserve-atomic-lock-plan.md`](../done/sequence-preserve-atomic-lock-plan.md),
     geschlossen 2026-06-02). Alle Punkte abgehakt; wandert separat
     nach `../done/`, sobald der Dead-Code-Cleanup-Folge-Slice
     (Probe-Adapter-Entfernung) entweder geliefert oder formal
     verworfen ist.

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
- `roadmap.md` ist der einzige dauerhafte Top-Level-Aggregator und
  wandert nicht. Versions-spezifische Workstream-Aggregatoren (z. B.
  der 0.9.7-Aggregator
  [`diffresult-migration-plan-2.md`](../done/diffresult-migration-plan-2.md),
  geschlossen 2026-06-02) wandern beim Milestone-Abschluss nach
  `../done/` und tragen dort eine `## Closure`-Sektion.

## Wann **nicht** hierher

- Scope steht, aber kein Implementierungs-Commit existiert →
  `../next/`.
- Trigger ohne Scope → `../open/`.
- Alle Phasen geliefert (für Umbrella-Pläne) → `../done/`.
- Einzelne Sub-Slice-Closure-Notiz → direkt nach
  `../done/ImpPlan-<version>-<slice>.md`, der Umbrella bleibt
  hier.
