# Plan: Harness-Bootstrap auf Regelwerk v1.3.0

> Dokumenttyp: Bootstrap-Plan (Slice-Bündel)
> Status: Entwurf (2026-06-18). Scope skizziert, **noch keine aktive
> Slice-Arbeit** im Code.
> Priorität: **hinter** dem Sample-DB-Harness
> ([`sample-db-integration-harness.md`](../done/sample-db-integration-harness.md)) — bewusst
> nachgeordnet (User-Entscheidung 2026-06-18).
> Auslöser: Regelwerk-Versionssprung **v1.2.0 → v1.3.0** (2026-06-18,
> User-bestätigt). Methodik-Quelle ist extern (`pt9912/ai-harness-course`,
> Release-ZIP `v1.3.0`), nicht im Repo.
> Referenzen: [`ADR 0004`](../../adr/0004-documentation-and-planning-structure.md)
> (Planning-/ADR-Struktur — deckt die Layout-Adaption), [`ADR 0011`](../../adr/0011-d-check-codepaths-scope-und-dauerhafte-ausnahmen.md)
> (Zukunftspfad-Ausnahmen).

## Auslöser / Kontext

v1.3.0 des externen Regelwerks führt **Pflicht-Artefakte** und eine
**Durchsetzungsschicht** ein, die dieses Repo noch nicht trägt. Wichtig für die
Einordnung: die **Verzeichnis-Layout-Unterschiede** (unser `docs/adr/` +
`docs/planning/` statt des v1.3.0-Default `docs/plan/adr/` + `docs/plan/planning/`) <!-- d-check:ignore (v1.3.0-Default-Layout, bewusst NICHT adoptiert; ADR 0004/0011) -->
sind laut Regelwerk **ausdrücklich projektspezifische, legitime Adaptionen** und
durch [ADR 0004](../../adr/0004-documentation-and-planning-structure.md) gedeckt —
**keine** Lücke. Sie müssen aber in einem Konventions-Adaptionsblock **deklariert**
werden, sonst gelten sie in v1.3.0 als „stille Setzung" (dieselbe Harness-Lüge-
Klasse wie ein undeklariertes Gate).

## Bestandsaufnahme — konform vs. Lücke

| Bereich | v1.3.0-Erwartung | Repo-Ist | Bewertung |
| ------- | ---------------- | -------- | --------- |
| ADR-/Planning-Layout | Default `docs/plan/…` | `docs/adr/` + `docs/planning/…` | konform (Adaption gemäß ADR 0004), **deklarationspflichtig** |
| ADR-Immutabilität/Supersede | Accepted = immutable, supersede statt edit | praktiziert (0013→0014) | konform |
| SDP-Referenz-Richtung | nur volatil→stabil, Provenance nur in Historie-Tabelle | praktiziert | konform |
| Source Precedence | geordnete Liste, `docs/user/*.md` als Betriebs-Docs | vorhanden | konform, in `harness/README.md` zu spiegeln |
| Carveout-Tracking | `CO-<NNN>`-Pro-Datei + `Letzte Prüfung` + Audit-Slice | Single-File-Aggregator `carveout.md` (Permanent/Provisional/Promoted/Resolved) | funktional nah, strukturell abweichend → Entscheidung nötig |
| `harness/README.md` | Pflicht-Einstiegspunkt | fehlt | **Lücke** |
| `harness/conventions.md` | Pflicht (Baseline + Adaptions-Block `MR-NNN` + Modus pro Sub-Area) | fehlt | **Lücke** |
| `AGENTS.md` | Agenten-Konventionen als Repo-Datei | fehlt (lebt im Memory) | **Lücke** |
| Durchsetzungsschicht | PreToolUse-/Stop-Hooks + Workflow-Slash-Command | nur `.claude/settings.local.json` | **Lücke** |
| `check-references`-Gate | fail-closed Token-Richtungs-Check | `docs-check` (Codepath-Validierung) deckt anderes ab | Teilabdeckung → prüfen |

## Arbeitspakete (Reihenfolge nach Wert/Risiko)

- **AP1 — `harness/conventions.md`** (höchster Wert, geringstes Risiko). <!-- d-check:ignore (geplantes Pflicht-Artefakt, existiert noch nicht; ADR 0011) -->
  Legt die Baseline (Regelwerk v1.3.0) fest und **deklariert unsere Adaptionen**
  als `MR-NNN`-Einträge: (a) Verzeichnis-Layout `docs/adr` + `docs/planning`
  (Bezug ADR 0004), (b) Single-File-Carveout-Aggregator statt `CO-<NNN>`-Dateien,
  (c) `docs-check` als Doku-Gate statt `check-references`, (d) Source-Precedence-
  Rangwahl. Damit hören unsere Abweichungen auf, „stille Setzungen" zu sein.
- **AP2 — `harness/README.md`** (Repo-Einstiegspunkt). Pflichtgliederung: Purpose, <!-- d-check:ignore (geplantes Pflicht-Artefakt, existiert noch nicht; ADR 0011) -->
  Source precedence (repo-spezifisch), Guides, **Sensors (nur real existierende
  Make-Targets!)**, Traceability rules, Safety/scope boundaries, Minimal agent
  workflow. Keine halluzinierten Gates, kein Lauf-Status in der Sensors-Tabelle.
- **AP3 — Carveout-Modell-Entscheidung.** Per ADR entscheiden: bei unserem
  Single-File-`carveout.md` bleiben (als deklarierte MR-Adaption in AP1) **oder**
  auf das v1.3.0-Pro-Datei-Modell migrieren. Bei Verbleib: `Letzte Prüfung`-
  Frische + Audit-Disziplin nachrüsten (das ist der substanzielle v1.3.0-Mehrwert,
  unabhängig vom Datei-Layout).
- **AP4 — `AGENTS.md`.** Maschinell lesbare Agenten-Konventionen als Repo-Datei
  (Codestil, Tool-Regeln, Layering, Verbote) — Source-Precedence-Rang. Abgrenzung
  zum Memory-System klären (keine Doppelquelle/Drift).
- **AP5 — Durchsetzungsschicht** (optional, höchster Aufwand). PreToolUse-Gate
  (Befehls-Guard), Stop-/Handoff-Gate (Gate-Nachweis über Working-Tree-Hash),
  Workflow-Slash-Command. Bootstrap-aware: erzwingt nur die Gates, die schon
  existieren.
- **AP6 — `check-references`-Gate prüfen.** Abgleichen, ob `docs-check` die
  SDP-Token-Richtung (kein `ADR-`/`slice-` abwärts im Spec-Körper) bereits
  abdeckt; falls nicht, als fail-closed Check ergänzen.

## Akzeptanzkriterien

- `harness/conventions.md` + `harness/README.md` existieren, Pflichtgliederung
  gefüllt, alle realen Adaptionen als `MR-NNN` deklariert; `make docs-check` grün.
- Carveout-Modell-Entscheidung als ADR festgehalten (Verbleib oder Migration),
  Begründung dokumentiert.
- Keine halluzinierten Gates in der Sensors-Tabelle (Abgleich gegen `Makefile`).
- Traceability-Constraint und Source Precedence repo-spezifisch abgebildet.

## Vorbedingungen

- Regelwerk v1.3.0 gesichtet (erledigt 2026-06-18).
- Sample-DB-Harness (Phase 1) hat Vorrang — dieser Bootstrap startet danach.

## Nicht-Ziel

- **Kein** Big-Bang-Umzug `docs/adr` → `docs/plan/adr` etc.; das Layout bleibt <!-- d-check:ignore (v1.3.0-Default-Layout, bewusst NICHT adoptiert; ADR 0004/0011) -->
  (durch ADR 0004 gedeckt), es wird nur **deklariert**.
- Keine Auflösung der inhaltlichen Carveouts selbst — nur das Modell/die
  Audit-Disziplin.
