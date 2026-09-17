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
| [`compare-falsch-positive-cross-dialekt.md`](compare-falsch-positive-cross-dialekt.md) | Per-Feature-Umbrella | Falsch-Positive von `schema compare` zwischen zwei Reverses (gemessen 38/38/30 %). AP1-4 geliefert und sabotage-verifiziert: `NO ACTION` faellt auf `null`, `sourceDialect` und `engine` werden nicht mehr cross-dialekt verglichen, der `custom_types`-Fund behauptet keinen Verlust mehr. Offen: die rohen CHECK-/View-Texte und der strikte Modus selbst — beides braucht eine Eigner-Entscheidung (im Dokument begruendet). |
| [`compare-projektion-und-normalisierung.md`](compare-projektion-und-normalisierung.md) | Per-Slice-Plan | Restfehlalarme und Projektionslücken aus der Konsumentenmessung gegen 1.7.1: Constraint-Funde ohne Vorher/Nachher (P1), zwei Pfad-Vokabulare (P2a/P2b), überflüssige Klammern um `OR`/`AND`-Operanden (P3), zwei fehlende Faltungszweige — Index-Prädikat und Listen-Komma (P5), PG-seitiger Identity-`sequenceName` (P6), die Grenze der Faltung (P8), Casts nur am Vergleich mit dem Spaltentyp (P9), `legacy_serial_syntax` über eine Reverse-Präferenz `serial`/`identity` statt einer Vergleichs-Faltung (P10, im vierten Bauabschnitt umgebaut) und eine Semantik in CLI, `schema_compare` und `schema_compare_start` (P11) samt eigener Artefakt-Art `COMPARE`. P7 übersteuert `ADR 0053` per Statuszeile (ADR 0056). Aktiviert 2026-09-16. **Stand: P1–P11 geliefert**, samt zweitem bis sechstem Bauabschnitt aus Eigner-Entscheidungen, Review und Verifikation, der Abnahme am Repro mit dem Schema des Konsumenten über MCP und CLI (mit und ohne Präferenz), dem Reverse-Report der MCP-Lese-Jobs (eigene Artefakt-Art `REVERSE_REPORT`), strengen Lese-Präferenzen und den E2E-Harnesses in `examples/mcp-e2e` (Roundtrip-Wächter mit Selbstprobe, 5x5-Compare-Matrix über MCP mit gepinnten Erwartungen, Pin-Schutz und rot sichtbarem Workflow); offen bleiben zwei Eigner-Fragen und die Punkte unter „Offen" im Slice (dort auch die Liste, die nach der Graduation einen Ort braucht). **P4** (MySQL-Introducer) steht im Reader-Slice in `../next/`. |
| [`cli-data-seed.md`](cli-data-seed.md) | Per-Feature-Umbrella | `d-migrate data seed`: Testdaten generieren + importieren. Vier Phasen P1-P4; P1 (deterministischer Generator-Kern) und P2 (`--rules`) geliefert (siehe [`ImpPlan-1.3.0-cli-data-seed-p1.md`](../done/ImpPlan-1.3.0-cli-data-seed-p1.md)/[`ImpPlan-1.3.0-cli-data-seed-p2.md`](../done/ImpPlan-1.3.0-cli-data-seed-p2.md) in `done/`); P3 (`--ai-backend`) bleibt geplant. |
| [`action-required-bestimmt-den-ausgang.md`](action-required-bestimmt-den-ausgang.md) | Next-Plan (Weg beta) | `action_required` bekommt eine Bedeutung im Exit-Code-Vertrag: die Ausgangsregel haengt an `SkippedObject`, nicht an der Notiz-Stufe. P0 (sechs echte Luecken geschlossen) und P1 (Ausgangsregel + `--allow-incomplete` gebaut, live belegt) abgeschlossen — `schema generate` bricht jetzt mit Exit 8 ab, wenn ein Objekt fehlt, ausser mit `--allow-incomplete` (Report/stderr vermerken die Unterdrueckung mit W160). Dabei nebenbei gefunden und behoben: `exit_code` im JSON-Output war fest auf 0 verdrahtet. P2 (MCP), P3 (weitere Kommandos), P4 (Doku) offen. |
| [`capability-tables-driver-interface.md`](capability-tables-driver-interface.md) | Per-Feature-Umbrella | Alle fuenf Capability-Tabellen tragen keine `when (dialect)`-Zweige mehr: die Werte liegen je Dialekt im Treibermodul. Zwei Ports statt einem, weil die Werttypen auf zwei Modulebenen wohnen — `DialectCapabilityProvider` in `ports-common`, `DialectReadCapabilityProvider` (erbt davon) in `ports-read`; nicht ueber `DatabaseDriverRegistry`, weil `ports` von `ports-read` abhaengt und das den Modulgraphen umgedreht haette. P0-P5 gebaut, alle 49 Module gruen, je Treibermodul eine Verdrahtungs-Spec. |

Graduierte/geschlossene Slices stehen unter `../done/` bzw. `../done-archive/`
(mit eigener `## Closure`-Sektion), nicht als Verweis hier.
