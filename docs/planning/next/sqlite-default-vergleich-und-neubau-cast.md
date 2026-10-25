# SQLite: gleicher Default gilt als geändert, und der Neubau blockiert an `text(n)` (Issue #54)

> **Status:** Draft mit Scope (2026-10-25), nach Review-Runde 1. Aus Issue #54: `schema migrate` gegen
> SQLite plant für eine unveränderte `integer`-/`boolean`-/`decimal`-Spalte mit `default` ein
> `AlterColumnDefault`; der daraus folgende Tabellenneubau blockiert, sobald die Tabelle eine Spalte trägt,
> die der Neubau als Typumwandlung deutet (`SQLITE_CAST_NOT_WHITELISTED`, Exit 8). Zwei Ursachen, ein
> Symptom. Fehlerklasse wie #51/#52/#53. Schwesterplan: `sqlite-eindeutiger-index-als-constraint.md` (#53).
> **Vorbedingung:** keine. **Aktivierung** (Move nach `../in-progress/`) beim ersten
> Implementierungs-Commit.

## Ziel

1. Ein `default`, den Datei und SQLite-Datenbank gleich ausdrücken (Zahl, Wahrheitswert), erzeugt kein
   `AlterColumnDefault`.
2. Ein Tabellenneubau, der aus einem **anderen** Grund nötig ist, blockiert nicht an einer Spalte, deren
   Typ sich nicht ändert, die SQLite aber enger ablegt, als die Datei sie schreibt.

## Problem

Nachgestellt mit dem SQLite-CLI-Test (`schema generate` → Datenbank → `schema migrate --plan-only`, dasselbe
Schema):

| Spalte in Datei | Operationen | Plan |
| --- | --- | --- |
| `integer, required, default: 0` / `default: 5` / nullable mit `default: 0` | `AlterColumnDefault` | 0 |
| `boolean, default: false` / `default: true` | `AlterColumnDefault` | **8** (`SQLITE_CAST_NOT_WHITELISTED`) |
| `decimal(10,2), default: 0` | `AlterColumnDefault` | **8** (`SQLITE_CAST_NOT_WHITELISTED`) |
| `text, default: "x"`, `float, default: 1.5` | keine | 0 |
| `integer, default: 0` **und** `text, max_length: 32` | `AlterColumnDefault` | **8** |

Der Neubau auf einer Tabelle mit `text(32)` ohne `default` „geht“ beim Reporter nur, weil dort keine Operation
einen Neubau auslöst. **Jeder** Neubau (aus beliebigem Grund) bildet das gelesene `Text(null)` auf das Soll
`Text(32)` ab, und `SqliteCastMatrix.covers(unbounded, bounded)` ist `false`: er würde ebenso blockieren.
Auf PostgreSQL gibt es keine `AlterColumnDefault`.

### Ursachen (am Code bestätigt, in P0 mit der Messung zu belegen)

* **Ursache 1 — Defaultvergleich.** Der SQLite-Reader parst `dflt_value` in
  [`SqliteTypeMapping.parseDefault`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTypeMapping.kt)
  (`toLongOrNull()` → `NumberLiteral(Long)`, `toDoubleOrNull()` → `NumberLiteral(Double)`, `TRUE`/`FALSE` →
  `BooleanLiteral`). Der Datei-Parser nimmt `DefaultValue.NumberLiteral(node.numberValue())`
  (`SchemaNodeStructureParsers`): Jackson liefert `Int` (bzw. `Long`/`BigInteger`) für Ganzzahlen und `Double`
  für Gleitkomma. `NumberLiteral(val value: Number)` ist eine data class — `0` (Int) ≠ `0L` ≠ `0.0`. Der
  Vergleich ist reine `==`-Gleichheit ohne Projektion (`TableComparator.compareColumn`, `left.default ==
  right.default`). Der Float-Fall geht, weil beide Seiten `Double` sind. **Boolean:** `SqliteTypeMapper.toSql`
  rendert `boolean` als `INTEGER`; der Reader sieht also `Integer` mit Default `0`/`1`, nie `BooleanType`. Er
  kann `0` nicht zu `BooleanLiteral(false)` machen.
* **Ursache 2 — Neubau und CAST.** Der Neubau bildet Ist (gelesener Typ) und Soll in
  [`SqliteRebuildPlanner.computeColumnMapping`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteRebuildPlanner.kt)
  über `SqliteCastMatrix.isWhitelisted` ab; bei `false` füllt er `castBlocked`. SQLite rendert eine ganze Reihe
  von Typen als dieselbe Speicherklasse: `Char`, `Date`, `Time`, `DateTime`, `Uuid`, `Json`, `Xml`, `Email`,
  `Array`, `Enum` → `TEXT` (nur `Enum` ist durch `onlyVocabularyDiffers` ausgenommen), `Boolean` → `INTEGER`,
  `Decimal` → `REAL`. Der Reader liest `Text(null)` / `Integer` / `Float`; das Soll trägt den engeren Typ; die
  Matrix hält das für eine nicht erlaubte Umwandlung. Betroffen ist also **jede** Spalte eines dieser Typen,
  nicht nur `text(n)`, `boolean`, `decimal`.
* **Entwurfsentscheid, der betroffen ist.** Der Kommentar an `onlyVocabularyDiffers` sagt ausdrücklich:
  „Bewusst eng auf Paare mit Enum-Beteiligung: SQLite legt auch alle Integer- und alle Textbreiten gleich ab,
  und dort ist die Umwandlung eine Aussage – sie zählt für den Ziel-Dialekt einer späteren Migration“. Eine
  Faltung im Planer dreht diese Entscheidung um und erfasst auch Datei-gegen-Datei-Verengungen (`text(64)` →
  `text(32)`), die heute als Verengung blockieren. Das ist in P1 zu begründen oder zu widerlegen.

## Richtung (nach P0 zu entscheiden)

* **Ursache 1, Zahlentypen:** (A) der Reader vereinheitlicht den Zahlentyp auf das, was der Parser liefert
  (nur Zahlentyp; ändert, was `schema reverse` schreibt) oder (B) der Vergleich gleicht Zahlentypen an.
* **Ursache 1, `0`/`1` ↔ Wahrheitswert:** nur **B** (Vergleich): der Reader hat die Typinformation „boolean“
  nicht. B braucht eine Default-Faltung, die den Spaltentyp kennt; `TargetProjection.type` ist ein reiner
  Typ-Funktor `(NeutralType) -> NeutralType`, es braucht eine neue Projektion `(NeutralType, DefaultValue?)`.
  Betroffen: `SchemaMigrateRunner` (Konstruktion der `TargetProjection`), `SchemaMigrateComparators`,
  `MigrationFingerprint`/`FingerprintValueProjection.defaultValue` (der Abdruck faltet unter derselben
  Projektion, sonst „Drift“ ohne Operation), `CanonicalPayload` (Operationsinhalte). B ändert also den
  **Abdruck-Vertrag**, nicht den Reverse-Vertrag.
* **Ursache 2:** (a) die Abbildung vergleicht Ist und Soll über dieselbe Typprojektion wie der Vergleich
  (`text(32)` und `text` falten auf denselben SQLite-Typ); (b) eine Identität erkennen: ändert sich weder
  Spaltentyp noch Speicherklasse, braucht der Neubau kein `CAST`; (c) die Whitelist erweitern
  (`Text(null) → Text(n)`) — das ist eine echte Verengung für andere Dialekte und hat Kosten.
* **Reichweite der Zahlentyp-Angleichung:** liegt sie im Datei-Parser oder im Comparator, trifft sie alle
  Dialekte (PostgreSQL, MySQL, MSSQL, Oracle). `FingerprintValueProjection`/`CanonicalPayload` rendern
  `num:${value}` (Int und Long gleich, `Double 0.0` nicht). Ein Regressionstest für mindestens einen weiteren
  Dialekt oder ein Argument, dass dort `Int`/`Long` nicht auseinanderlaufen, gehört in die Akzeptanz.

Nicht-Ziel: `AlterColumnDefault` ohne Neubau (SQLite kann `ALTER COLUMN` nicht); echte Typwechsel.

## Phasen

* **P0 Messung** (parametrisierter SQLite-CLI-Test, Ergebnistabelle im Plan-Nachtrag wie bei #51/#52):
  * *Defaults:* Spaltentyp × Default — `integer`/`smallint`/`biginteger`/`boolean`/`decimal(p,s)`/`float`/`text`/
    `date`/`timestamp` × `0`, `5`, `-1`, `1.5`, `1` an `float`, `2.0` an `decimal`, `1e3`, großer Wert (`BigInteger`),
    `true`/`false` (auch `1`/`0` an `boolean`, `true` an `integer`), `"x"`, Funktion (`current_timestamp` ↔
    `datetime('now')`), kein Default, `default: null`; jeweils Reader-Ausgabe (`PRAGMA table_info`, `DefaultValue`)
    und Operationen. Echte Änderung: `0` → `5`, Default entfernt, Default hinzugefügt.
  * *Neubau-CAST:* ein Neubau aus anderem Grund (`AddConstraint`/`AlterColumnNullability`) an einer Tabelle mit
    **je einer** Spalte jedes `NeutralType`-Konstruktors, dessen `toSql`-Ergebnis zurückgelesen einen anderen Typ
    ergibt (`text(n)`, `char(n)`, `date`, `time`, `datetime`, `uuid`, `json`, `xml`, `email`, `array`, `enum`,
    `boolean`, `decimal`), ohne Default. P0 endet, wenn die Tabelle voll ist; neue Fälle gehen in einen Folgeplan.
* **P1 Entscheidung:** je Ursache A/B bzw. (a)–(c); Randbedingung H1 (`0`/`1` nur über B); die Frage des
  Entwurfsentscheids `onlyVocabularyDiffers`; **Fingerabdruck-Tag:** B ändert die Projektion, A den
  Reader-Ausgang; die Nummer vergibt der zuerst gemergte der Schwesterpläne #53/#54 (`v20`), der andere nimmt
  `v21`, jeder führt seinen Absatz am `ALGORITHM`. **ADR 0062 ist Pflicht**, wenn Reader-Ausgang oder Abdruck
  sich ändern (wie bei #52/ADR 0061); Bezüge: ADR 0061 (Präzedenz), ADR 0057; akzeptierte ADRs bleiben im
  Kern unverändert (`doc-immutable`; beim Move nach `in-progress/` den `consulted:`-Pfad nicht nachziehen).
* **P2 Umsetzung** nach P1; die Spec-Regel ändert sich **mit** dem Code: `spec/ddl-generation-rules.md` (SQLite:
  `boolean` → `INTEGER` mit Default `1`/`0`, Zeile um 465; Defaults/`decimal`). Tests: Unit, SQLite-CLI
  (Plan-Only 0 Operationen, `--execute` mit additiver Änderung Exit 0, zweiter Lauf 0, Rückweg), echte
  Defaultänderung bleibt `AlterColumnDefault` (auch „Default entfernt“/„hinzugefügt“), ein echter Typwechsel
  bleibt auf der Whitelist/dem Blocker.
* **P3 Doku:** Handbuch (nur Ist-Zustand), `CHANGELOG.md`.

## Nicht-Scope

* Andere Dialekte (PostgreSQL meldet keine `AlterColumnDefault`); die Zahlentyp-Angleichung darf sie aber nicht
  verändern (Regressionstest).
* Der `SQLITE_CAST_NOT_WHITELISTED`-Blocker für **echte** Typwechsel.

## Akzeptanz

* Jede in P0 als Fehler gemessene Zeile hat einen Test, der ohne die Änderung an der erwarteten Stelle
  fehlschlägt (`AlterColumnDefault`, `SQLITE_CAST_NOT_WHITELISTED`, Exit 8); die neuen Tests werden einmal mit
  absichtlich falscher Zusicherung rot gesehen.
* Jede unveränderte Zeile: `operationsTotal` 0, `--execute` Exit 0, zweiter Lauf 0. Eine echte Defaultänderung
  bleibt eine Operation.
* Regressionstest für mindestens einen weiteren Dialekt (Zahlentyp-Angleichung).
* `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module **und** einmal ohne
  `MODULES=`, `make integration INTEGRATION_TASKS=":test:integration-sqlite:test"`,
  `make solid-suppression-gate`, `make docs-check`, `make doc-immutable RANGE=origin/main..HEAD`.
