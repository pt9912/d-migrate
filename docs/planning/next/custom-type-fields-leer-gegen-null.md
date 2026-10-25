# Custom Types: leere gegen fehlende `fields` im Vergleich (Issue #51)

> **Status:** Draft mit Scope (2026-10-25). Aus Issue #51: `schema migrate` gegen
> PostgreSQL plant `AlterCustomType` fuer eine unveraenderte Aufzaehlung und blockiert.
> **Vorbedingung:** keine. **Aktivierung** (Move nach `../in-progress/`) beim ersten
> Implementierungs-Commit.

## Ziel

Ein benannter Typ (`custom_types`), der in Datei und Datenbank gleich ist, erzeugt keine
`AlterCustomType`-Operation — gleich, wie die Datei ihn schreibt. `schema migrate` gegen
PostgreSQL fuehrt dann eine rein additive Aenderung (eine Spalte mehr) aus.

## Problem

Nachgestellt mit einem Integrationstest gegen PostgreSQL (Testcontainers): Typ
`ee AS ENUM ('a','b')`, Tabelle `t(id, e ee NOT NULL DEFAULT 'a')`; Soll = dasselbe plus
Spalte `extra`. Zwei Messungen, eine Variable:

| Soll-Typ `ee` | Ergebnis |
| --- | --- |
| `fields = null` (handgebautes Schema) | Exit 0, nur `AddColumn` |
| `fields = emptyMap()` (so liefert es der Datei-Parser) | Exit 8, `AlterCustomType` (`MANUAL_REQUIRED`, nicht gerendert), `customTypesChanged = [fields: null → {}]` |

### Ursache

* Der Datei-Parser setzt `fields = parseColumns(childNode["fields"])`
  (`SchemaNodeStructureParsers.kt`, `parseCustomTypes`) und liefert fuer ein fehlendes
  Feld eine **leere** Map.
* Der PostgreSQL-Reader laesst `fields` bei einem Enum **unbelegt** (`null`).
* `SchemaComparator.compareCustomType` vergleicht `left.fields == right.fields` ohne
  Angleichung; `values` vergleicht es dagegen mit `orEmpty()`. `null != {}` — der Typ gilt als
  geaendert, obwohl nichts anders ist.
* Der Fingerabdruck ist nicht betroffen (`CanonicalPayload` schreibt `fields?.size ?: 0`).

Dieselbe Asymmetrie trifft jeden Typ ohne Felder (Enum, Domain) auf jedem Dialekt, dessen
Reader `fields` leer laesst. SQLite liest keine Typ-Objekte (der Typ kommt nur aus der Datei),
deshalb fiel es dort nicht auf.

### Antwort auf Frage (b) aus dem Issue

`AlterCustomType` ist fuer PostgreSQL **absichtlich** nicht in der Matrix
(`PostgresDiffDdlGenerator`: `OpCategory.UNSUPPORTED`); eine **echte** Aenderung eines
benannten Typs (neue Werte, andere Basis) blockiert dort weiterhin mit Exit 8. Das Handbuch
sagt es bisher nicht.

## Richtung

**A — Vergleich angleichen (gewaehlt).** `compareCustomType` behandelt `fields` wie `values`:
`left.fields.orEmpty() == right.fields.orEmpty()`. Eine Stelle, alle Dialekte, kein
Reader- oder Parser-Vertrag betroffen.

**B — Reader und Parser auf eine Form bringen.** `null` normalisieren (Parser liefert `null`
statt leer, oder Reader leer). Streut ueber mehrere Module, und jede neue Quelle kann die
Asymmetrie neu einfuehren. Verworfen.

Nicht-Ziel: `AlterCustomType` fuer PostgreSQL rendern (eigener Umfang, eigene Entscheidung).

## Phasen

* **P0 Messung:** erledigt (oben). Offen: gibt es weitere `null`-gegen-leer-Felder in
  `compareCustomType`/`compareTable`/`compareColumn` (`ColumnDefinition`, `TableDefinition`
  mit Listen und Maps), die dasselbe Muster haben? Pruefen und, wenn ja, im selben Zug
  angleichen oder als Befund festhalten.
* **P1 Umsetzung:** `compareCustomType`; Unit-Test im Core (`fields` `null` gegen leer gleich,
  `null` gegen gefuellt verschieden, Composite mit echten Feldern unveraendert).
* **P2 Integration:** Test gegen PostgreSQL (Muster `PostgresEnumMigrateConvergenceIntegrationTest`),
  `ref_type`-Enum + additive Spalte: Exit 0, `AddColumn` allein, zweiter Lauf ohne Operationen;
  eine echte Aenderung der Werte blockiert weiter (`AlterCustomType`, Exit 8). Auch Domain.
* **P3 Doku:** Anwenderhandbuch (§ eigene Datentypen): `schema migrate` auf PostgreSQL
  aendert einen benannten Typ nicht (`AlterCustomType` blockiert), unveraenderte Typen sind
  kein Hindernis; `CHANGELOG.md`; `spec/` pruefen, ob eine Regel zu `AlterCustomType` auf
  PostgreSQL steht.

## Nicht-Scope

* `AlterCustomType` auf PostgreSQL rendern (`ALTER TYPE … ADD VALUE`).
* Andere Vergleichsfehler ausser `fields`, soweit P0 sie nicht als gleichartig belegt.

## Akzeptanz

* Test gegen echtes PostgreSQL schlaegt ohne die Aenderung an der erwarteten Stelle fehl
  (`AlterCustomType`, Exit 8, `fields: null → {}`) und wird mit ihr gruen.
* Die neuen Tests werden einmal mit absichtlich falscher Zusicherung rot gesehen.
* `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module,
  `make integration` fuer `:test:integration-postgresql:test`, `make solid-suppression-gate`,
  `make docs-check`.
