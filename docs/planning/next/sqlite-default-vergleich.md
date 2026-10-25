# SQLite: ein gleicher Default gilt als geändert (Issue #54, Ursache 1)

> **Status:** Draft mit Scope (2026-10-25), nach Review-Runde 2. Aus Issue #54: `schema migrate` gegen
> SQLite plant für eine unveränderte `integer`-/`boolean`-/`decimal`-Spalte mit `default` ein
> `AlterColumnDefault`. Das Issue hat zwei unabhängige Ursachen; sie sind auf zwei Pläne geteilt: dieser (Defaults)
> und `sqlite-neubau-cast-gleiche-speicherklasse.md` (der Neubau blockiert an einer Spalte, deren Speicherklasse
> gleich bleibt, `SQLITE_CAST_NOT_WHITELISTED`). Fehlerklasse wie #51/#52/#53. Schwesterplan: `sqlite-eindeutiger-index-als-constraint.md`
> (#53).
> **Vorbedingung:** keine. **Aktivierung** (Move nach `../in-progress/`) beim ersten
> Implementierungs-Commit.

## Ziel

Ein `default`, den Datei und SQLite-Datenbank gleich ausdrücken (Zahl, Wahrheitswert), erzeugt kein
`AlterColumnDefault`.

## Problem

Nachgestellt mit dem SQLite-CLI-Test (`schema generate` → Datenbank → `schema migrate --plan-only`, dasselbe
Schema):

| Spalte in Datei | Operationen | Exit |
| --- | --- | --- |
| `integer, required, default: 0` / `default: 5` / nullable mit `default: 0` | `AlterColumnDefault` | 0 |
| `boolean, default: false` / `default: true` | `AlterColumnDefault` | **8** (`SQLITE_CAST_NOT_WHITELISTED`) |
| `decimal(10,2), default: 0` | `AlterColumnDefault` | **8** (`SQLITE_CAST_NOT_WHITELISTED`) |
| `text, default: "x"`, `float, default: 1.5` | keine | 0 |
| `integer, default: 0` **und** `text, max_length: 32` | `AlterColumnDefault` | **8** |

Der Exit 8 der Zeilen `boolean`/`decimal`/`text(32)` stammt nicht aus dem Default, sondern aus dem Neubau, den
`AlterColumnDefault` auslöst (Ursache 2, eigener Plan). Auf PostgreSQL meldet der Reporter keine
`AlterColumnDefault` — **das ist ungemessen** und widerspricht dem Mechanismus unten (die Reader von PostgreSQL
und MySQL parsen mit `toLong()`/`toDouble()` ebenso); die erste P0-Zeile klärt es.

### Ursache (am Code bestätigt, in P0 mit der Messung zu belegen)

* **Defaultvergleich.** Der SQLite-Reader parst `dflt_value` in
  [`SqliteTypeMapping.parseDefault`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTypeMapping.kt)
  (`toLongOrNull()` → `NumberLiteral(Long)`, `toDoubleOrNull()` → `NumberLiteral(Double)`, `TRUE`/`FALSE` →
  `BooleanLiteral`). Der Datei-Parser nimmt `DefaultValue.NumberLiteral(node.numberValue())`
  (`SchemaNodeStructureParsers`): Jackson liefert `Int` (bzw. `Long`/`BigInteger`) für Ganzzahlen und `Double`
  für Gleitkomma. `NumberLiteral(val value: Number)` ist eine data class — `0` (Int) ≠ `0L` ≠ `0.0`. Der
  Vergleich ist reine `==`-Gleichheit ohne Projektion (`TableComparator.compareColumn`, `left.default ==
  right.default`). Der Float-Fall geht, weil beide Seiten `Double` sind. **Boolean:** `SqliteTypeMapper.toSql`
  rendert `boolean` als `INTEGER`; der Reader sieht also `Integer` mit Default `0`/`1`, nie `BooleanType`. Er
  kann `0` nicht zu `BooleanLiteral(false)` machen.

## Richtung (nach P0 zu entscheiden)

* **Zahlentypen:** (A) der Reader vereinheitlicht den Zahlentyp auf das, was der Parser liefert — ändert, was
  `schema reverse` schreibt, und ist **nicht abdruckneutral**: liefert der Reader künftig `Double`, rendert
  `num:${value}` `0.0` statt `0`. (B) der Vergleich gleicht Zahlentypen an (`Int`/`Long`/`BigInteger` gleich,
  `Double` getrennt oder nach Wert).
* **`0`/`1` ↔ Wahrheitswert:** nur **B**: der Reader hat die Typinformation „boolean“ nicht
  (`SqliteTypeMapper.toSql` → `INTEGER`). Eine Default-Faltung braucht den Spaltentyp; `TargetProjection.type`
  ist ein reiner Typ-Funktor `(NeutralType) -> NeutralType`, es braucht eine neue Projektion
  `(NeutralType, DefaultValue?)`. Betroffen: `SchemaMigrateRunner` (Konstruktion der `TargetProjection`),
  `SchemaMigrateComparators`, `MigrationFingerprint`/`FingerprintValueProjection.defaultValue` (der Abdruck
  faltet unter derselben Projektion, sonst „Drift“ ohne Operation), `CanonicalPayload`. B ändert den
  **Abdruck-Vertrag**, nicht den Reverse-Vertrag.
* **Reichweite:** liegt die Zahlentyp-Angleichung im Datei-Parser oder im Comparator, trifft sie alle Dialekte.
  Ein Regressionstest für mindestens einen weiteren Dialekt oder das Argument, dass dort `Int`/`Long` nicht
  auseinanderlaufen, gehört in die Akzeptanz.

Nicht-Ziel: `AlterColumnDefault` ohne Neubau (SQLite kann `ALTER COLUMN` nicht); echte Typwechsel; der
Neubau-CAST (Schwesterplan).

## Phasen

* **P0 Messung** (parametrisierter SQLite-CLI-Test, Ergebnistabelle im Plan-Nachtrag wie bei #51/#52). **Erste
  Zeile:** dasselbe Schema auf PostgreSQL und MySQL (Integrationsmodule) — zeigt es dort dasselbe
  `AlterColumnDefault`, ist die Reichweite dialektübergreifend; wenn nicht, erklärt etwas anderes den
  Unterschied, und die Ursache ist neu zu fassen. Dann je Mechanismus eine Zeile statt der vollen Matrix:
  `Int`↔`Long` (`integer`, `smallint`, `biginteger` mit `0`, `5`, `-1`, großer Wert/`BigInteger`),
  `Int`↔`Double` (`float` mit `1`, `decimal` mit `2.0`/`1e3`), `0`/`1`↔Wahrheitswert (`boolean` mit
  `true`/`false`/`1`/`0`, `integer` mit `true`), Funktion (`current_timestamp` ↔ `datetime('now')`), String,
  kein Default, `default: null`. Je Zeile Reader-Ausgabe (`PRAGMA table_info`, `DefaultValue`) und Operationen.
  Echte Änderung: `0` → `5`, Default entfernt, Default hinzugefügt. P0 endet, wenn die Tabelle voll ist.
* **P1 Entscheidung:** je Ursache A/B bzw. (a)–(c); Randbedingung H1 (`0`/`1` nur über B); die Frage des
  Entwurfsentscheids `onlyVocabularyDiffers`; **Fingerabdruck-Tag:** B ändert die Projektion, A den
  Reader-Ausgang *und* den Abdruck (`0` → `0.0`); die Nummer vergibt der zuerst gemergte der Schwesterpläne
  (`v20`), der nächste nimmt `v21`, jeder führt seinen Absatz am `ALGORITHM`. **Ein ADR ist Pflicht**, wenn
  Reader-Ausgang oder Abdruck sich ändern (Nummer `0062`, dann `0063`, oder ein gemeinsamer ADR) (wie bei #52/ADR 0061); Bezüge: ADR 0061 (Präzedenz), ADR 0057; akzeptierte ADRs bleiben im
  Kern unverändert (`doc-immutable`; beim Move nach `in-progress/` den `consulted:`-Pfad nicht nachziehen).
* **P2 Umsetzung** nach P1; die Spec-Regel ändert sich **mit** dem Code: `spec/ddl-generation-rules.md` (SQLite:
  `boolean` → `INTEGER` mit Default `1`/`0`, im Abschnitt zu den SQLite-Typen; Defaults). Tests: Unit, SQLite-CLI
  (Plan-Only 0 Operationen, `--execute` mit additiver Änderung Exit 0, zweiter Lauf 0, Rückweg), echte
  Defaultänderung bleibt `AlterColumnDefault` (auch „Default entfernt“/„hinzugefügt“), ein echter Typwechsel
  bleibt auf der Whitelist/dem Blocker.
* **P3 Doku:** Handbuch (nur Ist-Zustand), `CHANGELOG.md`.

## Nicht-Scope

* Andere Dialekte (PostgreSQL meldet keine `AlterColumnDefault`); die Zahlentyp-Angleichung darf sie aber nicht
  verändern (Regressionstest).
* Der Neubau-CAST (`sqlite-neubau-cast-gleiche-speicherklasse.md`) und der `SQLITE_CAST_NOT_WHITELISTED`-Blocker.

## Akzeptanz

* Jede in P0 als Fehler gemessene Zeile hat einen Test, der ohne die Änderung an der erwarteten Stelle
  fehlschlägt (`AlterColumnDefault`); die neuen Tests werden einmal mit
  absichtlich falscher Zusicherung rot gesehen.
* Jede unveränderte Zeile: `operationsTotal` 0, `--execute` Exit 0, zweiter Lauf 0. Eine echte Defaultänderung
  bleibt eine Operation.
* Regressionstest für mindestens einen weiteren Dialekt (Zahlentyp-Angleichung).
* `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module **und** einmal ohne
  `MODULES=`, `make integration INTEGRATION_TASKS=":test:integration-sqlite:test"`,
  `make solid-suppression-gate`, `make docs-check`, `make doc-immutable RANGE=origin/main..HEAD`.
