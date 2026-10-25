# SQLite: eine Domain-Spalte über boolean, decimal oder uuid gilt bei jedem Lauf als geändert (Folge von #54)

> **Status:** Draft mit Scope (2026-10-26). Befund aus der Umsetzung von
> `sqlite-neubau-cast-gleiche-speicherklasse.md` (#54): Domain-Spalten über `boolean`, `decimal` oder `uuid`
> planen bei **jedem** `schema migrate` ein `AlterColumnType` und blockieren damit (über die Cast-Matrix) den
> Neubau, auch ohne jede Änderung. Domain über `varchar`/`integer` ist nicht betroffen.
> **Vorbedingung:** keine. **Aktivierung** (Move nach `../in-progress/`) beim ersten Implementierungs-Commit.
> Noch kein GitHub-Issue; beim Aktivieren anlegen und hier verlinken.

## Ziel

Eine Domain-Spalte, die Datei und SQLite-Datenbank gleich ausdrücken, erzeugt keine Operation — auch nicht über
`boolean`, `decimal` oder `uuid`.

## Problem (gemessen in #54, P0)

Die Datei trägt `Enum(refType = <domain>)`; die Datenbank enthält die Domain nicht als Typ-Objekt, der Reader liest die
Spalte als `Integer` (`boolean` → `INTEGER`), `Float` (`decimal` → `REAL`) oder `Text` (`uuid` → `TEXT`). Der
ziel-bewusste Vergleich (`TargetProjection.type`, `SqliteNeutralTypeCanonicalizer`) projiziert die Datei-Spalte nicht auf
den Basistyp der Domain und sieht deshalb `Integer` gegen `Enum(refType)` als Typunterschied. Bei `varchar`/`integer` fällt
der Unterschied durch die Projektion weg.

### Zu prüfen (P0)

* Wo die Projektion einer Domain-Spalte fehlt: `SqliteNeutralTypeCanonicalizer`/`TargetProjection` kennen die Custom Types
  des Schemas nicht; `RefTypeVocabulary.resolve` löst nur Enums auf (ADR 0060).
* Welche Basistypen betroffen sind (je `NeutralType`, dessen `toSql` nicht auf sich selbst zurückliest).
* Verhalten für `file:`-Ziele (Datei gegen Datei).

## Richtung (nach P0 zu entscheiden)

* **A — Vergleich:** die Projektion löst eine Domain-Spalte auf ihren Basistyp auf (analog `RefTypeVocabulary`), nur SQLite;
  der Abdruck faltet unter derselben Projektion → Fingerabdruck-Tag (nächster freier) und ADR (nächste freie Nummer),
  Anknüpfung ADR 0060.
* **B — Reader:** nicht tragfähig (die Datenbank trägt die Domain nicht).

Nicht-Ziel: der Neubau-CAST selbst (erledigt in #54 Ursache 2).

## Phasen

P0 Messung (SQLite-CLI-Test, je Basistyp), P1 Entscheidung (ADR-/Tag-Bedarf), P2 Umsetzung mit Tests (Plan-Only 0
Operationen, `--execute`, zweiter Lauf, Rückweg, echte Änderung des Basistyps bleibt), P3 Doku.

## Akzeptanz

* Jede gemessene Fehlerzeile hat einen Test, der ohne die Änderung an der erwarteten Stelle fehlschlägt; absichtlich falsche
  Zusicherung einmal rot gesehen.
* `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module **und** einmal ohne `MODULES=`,
  `make integration INTEGRATION_TASKS=":test:integration-sqlite:test"`, `make solid-suppression-gate`, `make docs-check`,
  `make doc-immutable RANGE=origin/main..HEAD`.
