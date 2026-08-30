# Laufende Arbeit

Aktive Roadmap- und Slice-Pläne mit gestartetem Implementierungs-Pfad.
Drei Typen leben hier:

1. **Top-Level-Aggregatoren** mit sprechenden Namen (dauerhaft
   aktiv, wandern nicht):
   - [`roadmap.md`](roadmap.md) — Gesamt-Milestone-Sicht.
   - [`carveout.md`](carveout.md) — Living Tracker für bewusste
     Scope-Cut-Entscheidungen (Permanent / Provisional / Promoted /
     Resolved) mit Verweis aufs Quelldokument.
2. **Per-Feature-Umbrella-Pläne**, die einen mehrphasigen Workstream
   tragen, bei dem mindestens ein Commit den Plan referenziert.
3. **Aktive Per-Slice-ImpPlans und Skeletons**, die einen laufenden
   Slice konkretisieren oder Hand-off-Anker aus aktiven Slices
   festhalten. Sie bleiben hier bis zur Slice-Closure; Skeletons sind
   erlaubt, wenn sie explizit als `Pending <Voraussetzung>` markiert
   sind.

Lebenszyklus und Verzeichnisstruktur sind in
[`ADR 0004`](../../adr/0004-documentation-and-planning-structure.md)
festgehalten.

## Konvention für Einträge

- Status-Header pflegen: `> Status: In Progress (<refresh-datum>)`
  plus pro abgeschlossene Phase einen Commit-Ref-Hinweis.
- Per-Slice-ImpPlans wandern beim Abschluss eines Slice nach
  `../done/ImpPlan-<version>-<slice>.md`. Die Umbrella selbst bleibt
  in `in-progress/`, bis alle Phasen geliefert sind — dann wandert
  sie als Ganzes nach `../done/` mit einer `## Closure`-Sektion am
  Ende, die den finalen Stand zusammenfasst.
- Skeletons tragen im Status klar `Skeleton`/`Pending` plus die
  blockierende Voraussetzung und werden beim Start des Slice zu einem
  vollstaendigen ImpPlan erweitert.
- `roadmap.md` und `carveout.md` sind die dauerhaften Top-Level-
  Aggregatoren und wandern nicht. Versions-spezifische Workstream-
  Aggregatoren (z. B.
  der 0.9.7-Aggregator
  [`diffresult-migration-plan-2.md`](../done-archive/diffresult-migration-plan-2.md),
  geschlossen 2026-06-02) wandern beim Milestone-Abschluss nach
  `../done/` und tragen dort eine `## Closure`-Sektion.

## Wann **nicht** hierher

- Scope steht, aber kein Implementierungs-Commit existiert und es gibt
  keinen aktiven Hand-off-Anker aus einem laufenden Slice → `../next/`.
- Trigger ohne Scope → `../open/`.
- Alle Phasen geliefert (für Umbrella-Pläne) → `../done/`.
- Einzelne Sub-Slice-Closure-Notiz → direkt nach
  `../done/ImpPlan-<version>-<slice>.md`, der Umbrella bleibt
  hier.

## Bestand

| Datei | Typ | Gegenstand |
| ----- | --- | ---------- |
| [`roadmap.md`](roadmap.md) | Top-Level-Aggregator | Gesamt-Milestone-Sicht. |
| [`carveout.md`](carveout.md) | Top-Level-Aggregator | Living Tracker fuer bewusste Scope-Cut-Entscheidungen mit Verweis aufs Quelldokument. |
| [`partition-boundary-change-operation.md`](partition-boundary-change-operation.md) | Per-Feature-Umbrella | Eine hinzugekommene oder entfallene Partition wird Operation statt Warnung. Klassifikation signaturbasiert im Hexagon (SQL Server nummeriert Partitionen), Ausfuehrung je Dialekt: PG Kind-Anweisungen, MySQL ADD/REORGANIZE/DROP, SQL Server SPLIT/MERGE. P0-P4 geliefert, P5 (Matrix/Doku) laeuft. |
| [`no-transaction-execution-strategy.md`](no-transaction-execution-strategy.md) | Per-Feature-Umbrella | Anweisungen, die eine Datenbank in offener Transaktion ablehnt, laufen in einem eigenen Abschnitt (`NO_TRANSACTION`): Segmentierung am Scope-Wechsel, Ausfuehrung ohne Rueckrollversuch, `PARTIAL_STATE_POSSIBLE` im Report. Schaltet MSSQL-Volltext frei; PG `CREATE INDEX CONCURRENTLY` bleibt eigener Schnitt. |

Graduierte/geschlossene Slices stehen unter `../done/` bzw. `../done-archive/`
(mit eigener `## Closure`-Sektion), nicht als Verweis hier.
