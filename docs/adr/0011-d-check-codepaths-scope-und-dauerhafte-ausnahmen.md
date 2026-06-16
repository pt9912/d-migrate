---
status: accepted
date: 2026-06-16
decision-makers: pt9912
consulted: ADR-0010 (Done-Archiv), Regelwerk Modul 7 (Carveout-Management)
informed: d-check-Gate-Pflege; Doku-Autoren
---

# d-check-`codepaths`-Scope und dauerhafte Pfad-Ausnahmen

## Kontext und Problemstellung

Das Modul `codepaths` (d-check v0.9.0) prüft Inline-Code-Pfade in der
Doku auf Existenz. Über `docs/` und `spec/` laufend, trifft es auf drei
Klassen von Treffern, die **keine** behebbare Drift sind und auch nie
werden:

1. **Nutzer-CWD-Pfade** — `./.d-migrate.yaml`, `./export`: Pfade im <!-- d-check:ignore (Beispiel-Pfad dieser ADR, illustriert die ausgenommene Klasse; ADR 0011) -->
   Arbeitsverzeichnis des Nutzers, kein Repo-Artefakt.
2. **Build-Ausgaben** — `adapters/driving/cli/build/release`: entstehen <!-- d-check:ignore (Beispiel-Pfad dieser ADR, illustriert die ausgenommene Klasse; ADR 0011) -->
   erst zur Build-Zeit, liegen nicht im Repo.
3. **Externe-Repo-Verweise** — z. B. `spec/spezifikation.md` des <!-- d-check:ignore (Beispiel-Pfad dieser ADR, externer Repo-Verweis; ADR 0011) -->
   d-check-Repos: Pfade in einem anderen Repository.

Daneben gibt es **Zukunftspfade**: Doku, die einen noch nicht gebauten
Code-Pfad benennt (künftige Adapter, geplante Handler/Tests). Regelwerk
Modul 7 warnt davor, einen Cluster gleichartiger Ausnahmen als Kaskade
einzeln zu markieren — gleichartige Strukturgründe gehören als eine
Sub-Area-Entscheidung gebündelt.

## Entscheidung

`codepaths` läuft mit `scope.roots: [docs, spec]` (Root-`*.md` wie
`CHANGELOG.md` sind ausgenommen — unveränderliche Historie, Pfade galten
zur Eintragszeit). Die Treffer-Klassen werden so behandelt:

### Dauerhafte Nicht-Repo-Pfade → gezielter Marker

Nutzer-CWD-Pfade, Build-Ausgaben und Externe-Repo-Verweise tragen einen
`<!-- d-check:ignore (<Grund>; ADR 0011) -->`-Marker auf der Zeile. Der
Auflösungs-Trigger ist **dauerhaft** — diese Pfade werden nie Repo-
Artefakte; deshalb ADR statt Carveout (Regelwerk Modul 7: Trigger nie
erreichbar → Architekturentscheidung, kein temporärer Carveout).

### Homogene Zukunftsfläche → `scope.ignore` + Graduations-Trigger

Reine **Zielbild-Specs noch nicht gebauter Adapter** —
`spec/jsqlparser-adapter.md`, `spec/grpc-service.md`,
`spec/rest-service.md` — referenzieren durchgängig künftige Code-Pfade.
Statt jeder Zeile ein Marker werden diese Dateien als **eine**
Sub-Area-Entscheidung über `codepaths.scope.ignore` ausgenommen.

**Graduations-Trigger:** Wird der jeweilige Adapter real gebaut, wird
der `scope.ignore`-Eintrag für seine Spec entfernt — ab dann prüft
`codepaths` die nun existierenden Pfade.

### Verstreute Zukunftspfade → gezielter Marker mit Trigger

Einzelne künftige Pfade in sonst aktuellen Dokumenten (geplante
`schema_migrate`-Handler/Tests, `observability-jsonl`-Modul, Phase-E2-
Migrations-SQL) tragen je einen `d-check:ignore`-Marker mit konkretem
Sub-Slice-/Milestone-Trigger.

**Kurzregel:** homogene Zukunftsfläche → `scope.ignore` + ADR-Trigger;
einzelne verstreute Zukunftspfade → gezielter `d-check:ignore` mit
Trigger.

### Eingefrorene Historie

`docs/planning/done-archive/**` ist bereits global vom Scan ausgenommen
([`ADR 0010`](0010-done-archive-und-gate-scan-ausschluss.md)); `codepaths`
erbt das über seinen Modul-Scope (`done-archive` zusätzlich in
`codepaths.scope.ignore`). Historische Beispiel-Pfade in der
unveränderlichen [`ADR 0004`](0004-documentation-and-planning-structure.md)
(Stand 2026-05) tragen einen Marker.

## Konsequenzen

- Kein stilles Absenken des Gates: jede Ausnahme nennt Grund und (wo
  zutreffend) Trigger; permanente Klassen sind hier als
  Architekturentscheidung benannt statt als lügende Carveouts verstreut.
- `codepaths` deckt echte Drift in lebender Doku ab (verschobene/
  gelöschte Repo-Pfade), ohne an Zielbild- und Nutzer-Pfaden zu
  rauschen.
- Beim Bau eines der Zukunfts-Adapter ist der `scope.ignore`-Eintrag
  seiner Spec zu entfernen (Graduation) — der dann existierende Pfad
  wird geprüft.

## Weitere Informationen

- [`ADR 0010`](0010-done-archive-und-gate-scan-ausschluss.md) — Done-Archiv-Scan-Ausschluss.
- [`.d-check.yml`](../../.d-check.yml) — `codepaths.scope` und Modul-Liste.
- Treiber: [`docs/planning/done/d-check-module-adoption.md`](../planning/done/d-check-module-adoption.md) (Entscheidungen D1/D2).
