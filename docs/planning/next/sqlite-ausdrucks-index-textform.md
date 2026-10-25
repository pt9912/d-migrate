# SQLite: ein Ausdrucks-Index gilt gegen die Datenbank als geändert (Folge von #53)

> **Status:** Draft mit Scope (2026-10-26), Ursache gemessen. Befund aus der Umsetzung von
> `sqlite-eindeutiger-index-als-constraint.md` (#53, ADR 0062): für einen Ausdrucks-Index plant `schema migrate` bei
> jedem Lauf `DROP INDEX` + `CREATE INDEX` (ausführbar, Exit 0). **Vorbedingung:** keine. **Aktivierung** (Move nach
> `../in-progress/`) beim ersten Implementierungs-Commit; dann GitHub-Issue anlegen und hier verlinken.

## Ziel

Ein Ausdrucks-Index (auch ein eindeutiger), den Datei und SQLite-Datenbank gleich ausdrücken, erzeugt keine Operation.

## Problem

Gemessen (SQLite, `SqliteSchemaReader` auf einer In-Memory-Datenbank):

| `CREATE INDEX …` | Reader (`IndexColumn.expression`) |
| --- | --- |
| `ON t ((lower(a)))` | `(lower(a))` |
| `ON t (lower(a))` | `lower(a)` |
| `UNIQUE … (lower(a), b)` | `lower(a)` + Spalte `b` |
| `ON t (a, (b + 1) DESC)` | `a` + `(b + 1)`, `DESC` |
| `ON t (a COLLATE NOCASE)` | `a` (`COLLATE` entfällt) |

### Ursache

[`IndexColumn.renderKey`](../../../adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/IndexKeyRendering.kt) schreibt
**jeden** Ausdruck in Klammern (`"($it)"`), weil PostgreSQL und MySQL sie verlangen. Der SQLite-Generator legt für die Datei
`expression: lower(a)` also `CREATE INDEX … ((lower(a)))` an; der Reader gibt den Schlüssel so zurück, wie er im Katalog
steht, `(lower(a))` ([`SqliteIndexKeyScanner`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteIndexKeyScanner.kt));
die Datei trägt `lower(a)`. Der Vergleich sieht zwei Ausdrücke. Dieselbe Klasse wie beim Domain-`CHECK` auf PostgreSQL
(ADR 0061): die Darstellung unterscheidet sich, der Index nicht.

(Der Fall, dass sich ein Schlüssel nicht auflösen lässt, ist mit `R206` gemeldet und nicht Teil dieses Plans.)

## Richtung (nach P0 zu entscheiden)

* **A — Reader-Normalform:** der Reader entfernt **eine** überflüssige äußere Klammer um einen Index-Ausdruck, wenn sie den
  ganzen Ausdruck umschließt (`(lower(a))` → `lower(a)`; `(a) + (b)` bleibt), und kann das dank der Generator-Regel
  (`renderKey` klammert immer) gefahrlos tun. Normalform des **Readers**, kein Falten im Vergleich (ADR 0056 unberührt,
  Präzedenz ADR 0061). Wirkt in `schema reverse`/`schema compare` (Reverse-Vertrag).
* **B — Vergleich:** Index-Ausdrücke zielbewusst falten (`TargetProjection.index`, Abdruck-Vertrag) — größer.
* **C — Generator:** SQLite schreibt keine zusätzlichen Klammern, wo sie nicht nötig sind (ein bloßer Funktionsaufruf):
  ändert die erzeugte DDL und lässt Bestandsdatenbanken (mit `((…))`) weiter abweichen — kein Ersatz für A.

**Zu prüfen (P0):** Reihenfolge der Schlüssel, mehrere Ausdrücke, Ausdruck + Spalte, absteigend, `COLLATE` (das Modell kennt
es nicht; Reader lässt es weg), Prädikat (`WHERE`), Ausdruck mit Klammern im Inneren (`(a) + (b)`, `(a + b) * 2`), Strings mit
Klammern; ob der Fingerabdruck den Ausdruck trägt (Tag-Bump, Rollback-Artefakte), `COLLATE` als eigener Befund.

Nicht-Ziel: `COLLATE` im Modell; Ausdrucks-Indizes auf anderen Dialekten.

## Phasen

* **P0 Messung** (Reader-Unit-Test + SQLite-CLI-Test; Fallliste oben): je Fall Reader-Ausgabe, Operationen gegen die vom
  Generator erzeugte Datenbank, `--execute`, zweiter Lauf. P0 endet, wenn die Tabelle voll ist.
* **P1 Entscheidung:** A (erwartet) oder B; Fingerabdruck-Tag (nächster freier) und ADR (nächste freie Nummer, Präzedenz
  ADR 0061/0062) nur, wenn Reader-Ausgang oder Abdruck sich ändern.
* **P2 Umsetzung:** Unit-Tests (Reader), SQLite-CLI-Tests (Plan-Only `operationsTotal` 0, `--execute` Exit 0, zweiter Lauf 0,
  Rückweg, echte Ausdrucksänderung bleibt eine Operation), Goldens/Reverse-Tests mit Ausdrucks-Index prüfen.
* **P3 Doku:** Handbuch (die Grenze „Ausdrucks-Index" streichen, nur Ist-Zustand), `CHANGELOG.md`; `spec/` zum Index-Ausdruck
  prüfen (Zielbild, Reader-Regel dort festhalten wie bei ADR 0061).

## Akzeptanz

* Jede gemessene Fehlerzeile hat einen Test, der ohne die Änderung an der erwarteten Stelle fehlschlägt
  (`DropIndex` + `AddIndex`); absichtlich falsche Zusicherung einmal rot gesehen.
* Eine echte Änderung des Ausdrucks (`lower(a)` → `upper(a)`) bleibt eine Operation; ein Ausdruck mit inneren Klammern wird
  nicht verstümmelt.
* `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module **und** einmal ohne `MODULES=`,
  `make integration INTEGRATION_TASKS=":test:integration-sqlite:test"`, `make solid-suppression-gate`, `make docs-check`,
  `make doc-immutable RANGE=origin/main..HEAD`.
