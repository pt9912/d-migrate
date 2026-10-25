# SQLite: der Text eines Ausdrucks-Index gilt als geändert (Folge von #53)

> **Status:** Draft mit Scope (2026-10-26). Befund aus der Umsetzung von
> `sqlite-eindeutiger-index-als-constraint.md` (#53): der Katalog liefert den Ausdruck eines Index in Klammern
> (`(lower(a))`), die Datei schreibt `lower(a)`; der Vergleich hält beides für verschieden und plant `DROP INDEX` +
> `CREATE INDEX` (ausführbar, aber bei jedem Lauf). **Vorbedingung:** #53 ist auf `main`. **Aktivierung** (Move nach
> `../in-progress/`) beim ersten Implementierungs-Commit. Noch kein GitHub-Issue; beim Aktivieren anlegen.

## Ziel

Ein Ausdrucks-Index (auch ein eindeutiger), den Datei und SQLite-Datenbank gleich ausdrücken, erzeugt keine Operation.

## Problem

Gemessen in #53 (P0): `CREATE [UNIQUE] INDEX ix ON t (lower(a))` → Reader liefert den Ausdruck als `(lower(a))`, die Datei
`lower(a)`; nach `--execute` und zweitem Lauf bleiben 2 Operationen (`DropIndex` + `AddIndex`), Exit 0. Vor #53 ging der
eindeutige Ausdrucks-Index im Reverse ganz verloren; jetzt ist die Lücke sichtbar. Der Reader gibt zudem einen nicht
auflösbaren Ausdrucks-Index still auf (`SqliteMetadataQueries`, `keys.any { it == null }`, ohne Diagnose).

## Richtung (nach P0 zu entscheiden)

* **A — Reader-Normalform:** die äußeren Klammern um den Index-Ausdruck entfernen (wie `PostgresDomainCheckForm` für den
  Domain-`CHECK`, ADR 0061: Normalform des Readers, kein Falten im Vergleich, ADR 0056 unberührt).
* **B — Vergleich:** Index-Ausdrücke zielbewusst falten (`TargetProjection.index`) — größer (Abdruck-Vertrag).
* Diagnose für den nicht auflösbaren Ausdrucks-Index (Notiz statt stillem Verlust).

## Phasen

P0 Messung (SQLite-CLI/Reader-Test: einfache Funktion, mehrere Ausdrücke, Ausdruck + Spalte, absteigend, `COLLATE`,
Prädikat), P1 Entscheidung (Tag/ADR bei Reader-Änderung), P2 Umsetzung (Plan-Only 0 Operationen, `--execute`, zweiter Lauf,
Rückweg, echte Ausdrucksänderung bleibt eine Operation), P3 Doku (Handbuch: die Grenze aus #53 streichen).

## Akzeptanz

* Jede gemessene Fehlerzeile hat einen Test, der ohne die Änderung an der erwarteten Stelle fehlschlägt; absichtlich falsche
  Zusicherung einmal rot gesehen.
* `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module **und** einmal ohne `MODULES=`,
  `make integration INTEGRATION_TASKS=":test:integration-sqlite:test"`, `make solid-suppression-gate`, `make docs-check`,
  `make doc-immutable RANGE=origin/main..HEAD`.
