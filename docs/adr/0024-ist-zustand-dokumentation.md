---
status: accepted
date: 2026-06-28
decision-makers: pt9912
consulted: spec/design.md (Ist/Soll-Mix), docs/planning/in-progress/roadmap.md (Milestone-Ist), CHANGELOG.md
informed: spec/ (alle Zielbild-Verträge), docs/adr/0004-documentation-and-planning-structure.md
---

# Ist-Zustand-Dokumentation: keine Prosa-Ist-Doku — `spec/` ist reines Zielbild, Ist lebt in roadmap/CHANGELOG/ADR/Code

> **Status: accepted (2026-06-28).** Der architektonische **Ist-Zustand** wird
> **nicht** als eigenes Prosa-Dokument geführt. `spec/` beschreibt
> ausschließlich das **Zielbild** (Soll), ohne Status/Phasen/Milestone-Provenienz
> im normativen Text. Der Ist-Zustand wird aus den bestehenden Quellen gelesen:
> `roadmap.md` (✅-Milestone-Tabellen = Feature-Ist), `CHANGELOG.md` (Change),
> ADRs (getroffene Entscheidungen), Code + Tests (Ground Truth).

## Kontext und Problemstellung

Das Regelwerk-Prinzip ist eindeutig: **Specs sind Zielbilder** (Stable
Dependencies Principle — die stabilste Schicht trägt keine Status-/Phasen-
/Versions-Provenienz). In der Praxis vermischte `spec/design.md` jedoch
Zielbild und Ist: das Dokument war als **Living Design** mit expliziten
„Heutiger Ist-Zustand" ↔ „Soll-Zustand (spätere Milestones)"-Kontrastpaaren
gebaut.

Bevor `design.md` bereinigt werden kann, ist eine Vorfrage zu klären: **Wo und
wie wird der architektonische Ist-Zustand überhaupt dokumentiert?** Ohne diese
Antwort ist unklar, ob der Ist-Inhalt aus `design.md` *umzuziehen* oder
*wegzulassen* ist.

Bestandsaufnahme der heutigen Ist-Quellen:

| Quelle | erfasst | Granularität |
| --- | --- | --- |
| `CHANGELOG.md` | was sich pro Version änderte | chronologisch |
| `roadmap.md` (✅-Tabellen + Datum) | was pro Milestone geliefert wurde | Feature-/Task-Ist |
| ADRs | getroffene Architektur-Entscheidungen (+ Status) | Entscheidungs-Ist |
| Code + Tests | tatsächliches Verhalten | Ground Truth |
| `docs/operations/` | Betrieb, Benchmarks, Playbooks | operativ |

Es existiert **kein** dedizierter „Architektur-Ist"-Prosa-Doc; genau diese
Lücke füllte `design.md` informell.

## Entscheidungstreiber

- **SDP / „Specs sind Zielbilder":** der Spec-Body trägt keine Ist-/Status-
  /Milestone-Aussagen. Dass andere Dokumente (z. B. das Lastenheft) heute noch
  Provenienz tragen, ist **Ist-Stand, kein Prinzip** — und keine Begründung,
  Provenienz in Specs zu belassen.
- **Drift-Vermeidung:** ein zusätzliches Prosa-Ist-Dokument dupliziert Code und
  Roadmap und veraltet bei jedem Feature; es bräuchte ein eigenes Pflege-Gate.
- **Ein Concern pro Ort:** Soll → `spec/`; Plan/Status → `roadmap.md`; Change →
  `CHANGELOG.md`; Entscheidung → ADR; Wahrheit → Code/Tests.

## Betrachtete Optionen

- **Option 1 — kein dedizierter Ist-Prosa-Doc.** Ist = roadmap-✅ + CHANGELOG +
  ADR + Code/Tests.
- **Option 2 — dediziertes Implementierungsstand-Dokument** (etwa unter
  `docs/operations/`). Erhält den narrativen Ist-Überblick, ist aber drift-
  anfällig und pflege-/gate-pflichtig.
- **Option 3 — `roadmap.md` als designierte Ist-Quelle ausbauen** (knappe
  architektonische Notizen pro Milestone), sonst wie Option 1.

## Entscheidung

**Gewählt: Option 1.** Kein dedizierter Ist-Prosa-Doc. Der Ist-Zustand wird aus
`roadmap.md` (✅), `CHANGELOG.md`, ADRs und Code/Tests gelesen. `spec/` bleibt
reines Zielbild.

Konsequenz für `spec/design.md`: Bei der Umsetzung stellte sich `design.md` als
**veralteter, redundanter** Living-Ist/Soll-Overview heraus (superseded by
`architecture.md §3.3`, das die als „Soll" markierte Pipeline bereits real
trägt). Statt In-Place-De-Ist wird `design.md` daher **retired**; sein
einzigartiger Rest (v. a. das KI-Integrations-Design) bekommt eine eigene
Zielbild-Heimat. Ist-Annotationen fallen weg (redundant zu roadmap/Code), die
Soll-Inhalte sind bereits in `architecture.md`/`cli-spec.md`/`profiling.md`
gedeckt.

`spec/profiling.md` ist von dieser Sonderbehandlung **nicht** betroffen: es
beschreibt *eine* Ziel-Architektur (Domänenmodell/Ports/Adapter), ist also
Zielbild-kompatibel und bleibt in `spec/` (Milestone-Refs bereits entstempelt).

Option 2 wurde verworfen (Duplikation + Drift + Pflegelast). Option 3 ist von
Option 1 nur graduell verschieden; falls je ein zusammenhängender Ist-Überblick
gebraucht wird, ist die `roadmap.md`-Erweiterung der bevorzugte Weg — bis dahin
gilt Option 1 ohne neuen Doc-Typ.

## Konsequenzen

- **Gut:** `spec/` wird konsequent Zielbild; keine drift-anfällige Parallel-Ist-
  Doku.
- **Gut:** klare Zuständigkeit pro Concern; Leser wissen, wo Ist (roadmap/Code)
  vs. Soll (spec) steht.
- **Preis:** Es gibt keinen zusammenhängenden Prosa-Überblick der „heutigen
  Architektur"; er ist aus roadmap-✅ + Code zu rekonstruieren.
- **Folgearbeit:** `design.md` retiren (eigener Sub-Slice
  [`design-md-retire.md`](../planning/next/design-md-retire.md)); der
  Milestone-Hygiene-Slice bereinigt nur die verbleibenden Zielbild-Verträge.
  Das Lastenheft trägt dieselbe Provenienz-Schuld; seine Bereinigung ist
  separat zu schneiden, nicht in diesem Slice.

## Bestätigung

- `make docs-check` grün.
- Grep-Beleg: in `spec/` keine „Heutiger Ist-Zustand"/„Living Design"/SNAPSHOT-
  /„in Arbeit"-Marker und keine d-migrate-Milestone-Stempel im normativen Text
  mehr (erlaubt: Engine-Versions-Fakten, versionierte Dateinamen,
  Abschnittsnummern).
