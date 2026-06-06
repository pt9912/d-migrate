# Laufende Arbeit

Aktive Roadmap- und Slice-Pläne mit gestartetem Implementierungs-Pfad.
Zwei Typen leben hier:

1. **Top-Level-Aggregatoren** mit sprechenden Namen (dauerhaft
   aktiv, wandern nicht):
   - [`roadmap.md`](roadmap.md) — Gesamt-Milestone-Sicht.
   - [`carveout.md`](carveout.md) — Living Tracker für bewusste
     Scope-Cut-Entscheidungen (Permanent / Provisional / Promoted /
     Resolved) mit Verweis aufs Quelldokument.
2. **Per-Feature-Umbrella-Pläne**, die einen mehrphasigen Slice
   tragen, bei dem mindestens ein Commit den Plan referenziert:
   - [`parquet-productive-cut-a.md`](parquet-productive-cut-a.md)
     — Cut A (Voll-Scope, 0.9.8) auf `feature/parquet-0.9.8`.
     Trägt die produktive Umsetzung nach Scope-/Versions-
     Korrektur 2026-06-06 (AP13 §8 supersededt §5.4/§7;
     Stakeholder-Entscheid e7f3f714 abgelöst): alle neun
     Wiring-Schritte aus AP12 §12 inklusive Single-File
     (S3b/S4/S5b/S9b) plus S10a (Dependency-Hygiene +
     Footprint-Inventar) und S10b (Native-Image-Befund).
     Footprint-Minimierung und Native-Image-Cut sind
     1.0.0-Folge-Aufgabe (AP13 §8.3), nicht Teil des
     Umbrellas. Plan-Doc-Phase (AP1–AP13) ist in `../done/`
     abgeschlossen; dieser Umbrella deckt die Code-Phase ab.

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
- `roadmap.md` und `carveout.md` sind die dauerhaften Top-Level-
  Aggregatoren und wandern nicht. Versions-spezifische Workstream-
  Aggregatoren (z. B.
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
