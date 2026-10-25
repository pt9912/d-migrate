# SQLite: ein gleicher Default gilt als geändert (Issue #54, Ursache 1)

> **Status:** In Arbeit (2026-10-25); umgesetzt, siehe „Nachtrag P0/P1/P2“. Zuvor Draft mit Scope, nach Review-Runde 3. Aus Issue #54: `schema migrate` gegen
> SQLite plant für eine unveränderte `integer`-/`boolean`-/`decimal`-Spalte mit `default` ein
> `AlterColumnDefault`. Das Issue hat zwei unabhängige Ursachen; sie sind auf zwei Pläne geteilt: dieser (Defaults)
> und `sqlite-neubau-cast-gleiche-speicherklasse.md` (der Neubau blockiert an einer Spalte, deren Speicherklasse
> gleich bleibt, `SQLITE_CAST_NOT_WHITELISTED`). Fehlerklasse wie #51/#52/#53. Schwesterplan: `sqlite-eindeutiger-index-als-constraint.md`
> (#53).
> **Vorbedingung:** keine. Aktiviert (nach `in-progress/` verschoben) mit dem ersten
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

Der Exit 8 der Zeilen `boolean`/`decimal`/`text(32)` stammt nicht aus dem Default, sondern aus dem Neubau, den
`AlterColumnDefault` auslöst (Ursache 2, eigener Plan). Der Reporter meldet für PostgreSQL keine `AlterColumnDefault` — **ungemessen**, und es widerspricht dem
Mechanismus unten: die Reader von PostgreSQL und MySQL parsen mit `toLong()`/`toDouble()` ebenso, der
Datei-Parser liefert `Int`, und keine Stelle gleicht Zahlentypen an (`TableComparator` vergleicht per `==`). Die erste
P0-Zeile klärt es; der Plan hat dafür eine Weiche (Richtung).

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
* **Weiche nach der ersten P0-Zeile.** Zeigen PostgreSQL und MySQL dasselbe `AlterColumnDefault`, ist die Reichweite
  dialektübergreifend: der Fix liegt im Vergleich oder im Datei-Parser (B bzw. Parser-Angleichung) für **alle**
  Dialekte, und die Akzeptanz erhält deren Integrationsmodule (`:test:integration-postgresql:test`,
  `:test:integration-mysql:test`). Zeigen sie es nicht, erklärt etwas anderes den Unterschied; die Ursache ist neu zu
  fassen, bevor A oder B gewählt werden.
* **Auswahlregel Zahlentyp:** B (Vergleich/Parser), wenn P0 mehr als SQLite betroffen zeigt oder `Int`↔`Double`
  vorkommt; A (Reader) nur, wenn ausschließlich der SQLite-Reader das Artefakt erzeugt. **Festlegung für B:**
  Zahlenliterale gelten als gleich, wenn sie numerisch gleich sind (`Int(1)` = `Long(1)` = `Double(1.0)`), verglichen
  über `BigDecimal.compareTo`; `NumberLiteral` selbst bleibt unverändert (data class).
* **Reichweite der Angleichung:** liegt sie im Datei-Parser oder im Comparator, trifft sie alle Dialekte; Regressionstest
  mit den Integrationsmodulen von PostgreSQL und MySQL (siehe Weiche).

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
* **P1 Entscheidung:** A oder B nach der Auswahlregel; `0`/`1` ↔ Wahrheitswert nur über B; **Fingerabdruck-Tag:** B ändert die Projektion, A den
  Reader-Ausgang *und* den Abdruck (`0` → `0.0`); die Nummer vergibt der zuerst gemergte der Schwesterpläne
  (`v20`), der nächste nimmt `v21`, jeder führt seinen Absatz am `ALGORITHM`. **Ein ADR ist Pflicht**, sobald Reader-Ausgang oder Abdruck sich ändern (wie bei #52/ADR 0061); Nummer `0062`, dann `0063`, oder ein
  gemeinsamer ADR; Bezüge: ADR 0061 (Präzedenz), ADR 0057; akzeptierte ADRs bleiben im
  Kern unverändert (`doc-immutable`; beim Move nach `in-progress/` den `consulted:`-Pfad nicht nachziehen).
* **P2 Umsetzung** nach P1; die Spec-Regel ändert sich **mit** dem Code: `spec/ddl-generation-rules.md` (SQLite:
  `boolean` → `INTEGER` mit Default `1`/`0`, im Abschnitt zu den SQLite-Typen; Defaults). Tests: Unit, SQLite-CLI
  (Plan-Only 0 Operationen, `--execute` mit additiver Änderung Exit 0, zweiter Lauf 0, Rückweg), echte
  Defaultänderung bleibt `AlterColumnDefault` (auch „Default entfernt“/„hinzugefügt“), 
* **P3 Doku:** Handbuch (nur Ist-Zustand), `CHANGELOG.md`.

## Nicht-Scope

* Andere Dialekte, **soweit P0 sie nicht als betroffen zeigt** (Weiche); die Zahlentyp-Angleichung darf ihr Verhalten
  sonst nicht verändern (Regressionstest).
* Der Neubau-CAST (`sqlite-neubau-cast-gleiche-speicherklasse.md`) und der `SQLITE_CAST_NOT_WHITELISTED`-Blocker.

## Akzeptanz

* Jede in P0 als Fehler gemessene Zeile hat einen Test, der ohne die Änderung an der erwarteten Stelle
  fehlschlägt (`AlterColumnDefault`); die neuen Tests werden einmal mit
  absichtlich falscher Zusicherung rot gesehen.
* Jede unveränderte Zeile: `operationsTotal` 0, `--execute` Exit 0, zweiter Lauf 0. Eine echte Defaultänderung
  bleibt eine Operation.
* Regressionstest für PostgreSQL und MySQL (Integrationsmodule), wenn die Angleichung dialektübergreifend wirkt.
* Die Messtabelle steht als Abschnitt „Nachtrag P0“ in diesem Plan (wie bei #51/#52).
* `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module **und** einmal ohne
  `MODULES=`, `make integration INTEGRATION_TASKS=":test:integration-sqlite:test"`,
  `make solid-suppression-gate`, `make docs-check`, `make doc-immutable RANGE=origin/main..HEAD`.

## Nachtrag P0/P1/P2 (2026-10-25)

**P0 (Messung, ungefixt).** Erste Zeile: dasselbe Schema auf PostgreSQL und MySQL
(`PostgresDefaultCompareIntegrationTest` bzw. `MysqlDefaultCompareIntegrationTest`: Tabelle `t(id, c)` mit
`default`, Soll = gelesene Datenbank mit dem Default in Parser-Form `Int`; Plan-Only). Dann SQLite über den
CLI-Test (`schema generate` → Datenbank → `schema migrate --plan-only` / `--execute` mit zusätzlicher Spalte `d`
→ zweiter Lauf).

| Fall | PostgreSQL (Reader → Ops) | MySQL (Reader → Ops) | SQLite (Reader → Ops; `+d`; Lauf 2) |
| --- | --- | --- | --- |
| `integer`/`int` `0`, `5`, nullable `0` | `Long` → 1 `AlterColumnDefault` | `Long` → 1 | `Long` → 1; Exit 0; Lauf 2: 1 |
| `smallint` `5` | `Long` → 1 | `Long` → 1 | `Long` → 1; Exit 0; Lauf 2: 1 |
| `biginteger` `-1` / `0` | `StringLiteral("-1")` → 1 (Reader-Artefakt) | `Long` → 1 | `0`: `Long` → 1; Exit 0; Lauf 2: 1 |
| `biginteger` `5000000000` (Long beider Seiten) | `StringLiteral` → 1 (Reader-Artefakt) | `Long` → **0** | `Long` → **0**; Exit 0; Lauf 2: 0 |
| `integer` `2147483648` (Long beider Seiten) | — | — | **0**; Exit 0; Lauf 2: 0 |
| `integer` `99999999999999999999` | — | — | Reader `Double 1.0E20` → 1; **`+d` Exit 5**; Lauf 2 nicht möglich |
| `float`/`double` `1` | `Long` → 1 | `Long` → 1 | `Long` (`REAL/1`) → 1; Exit 0; Lauf 2: 1 |
| `float`/`double` `1.5` | `Double` → **0** | `Double` → **0** | `Double` → **0**; Exit 0; Lauf 2: 0 |
| `decimal` `0` | `Long` → 1 | `Double 0.0` → 1 | `Long` (`REAL/0`) → 1; **Exit 8** `SQLITE_CAST_NOT_WHITELISTED` |
| `decimal` `2.0`, `1e3` | `Double` → **0** | `Double` → **0** | **0**; Exit 0; Lauf 2: 0 |
| `boolean` `false`/`true` | `BooleanLiteral` → **0** | `tinyint(1)` → `BooleanLiteral` → **0** | `INTEGER/0`,`/1` → 1; **Exit 8** (Neubau-CAST) |
| `boolean` `default: 1`/`0` oder `integer` `default: true` | — | — | Datei ungültig, Exit 3 (kein Fall) |
| `text` `"x"`, `"0"` | **0** | **0** | **0**; Exit 0; Lauf 2: 0 |
| Funktion `current_timestamp` | — | — | **0** |
| kein Default; `default: null` | — | — | **0** |
| echte Änderung `0` → `5` | — | — | 1 `AlterColumnDefault`; Exit 0 |
| Default entfernt / hinzugefügt (`integer`) | — | — | 1 / 1 `AlterColumnDefault`; Exit 0 |
| `float` `1.5` → `2.5` | — | — | 1 |
| `boolean` `false` → `true` / entfernt | — | — | 1; **Exit 8** (Neubau-CAST, Schwesterplan) |

Befund: PostgreSQL und MySQL zeigen **dasselbe** `AlterColumnDefault` — die Reichweite ist dialektübergreifend
(der Reporter-Befund „auf PostgreSQL nicht“ war ungemessen und trifft nicht zu). Mechanismus: Datei-Parser `Int`
gegen Reader-`Long`/`Double`; sobald beide Seiten denselben Zahlentyp tragen (`1.5`, `2.0`, `5000000000`, Text,
Funktion), ist der Fall grün. Zusätzlich MySQL `decimal(10,2) DEFAULT 0` → `Double 0.0` gegen `Int 0`.
Nebenbefunde, hier nicht behandelt: PostgreSQL liest einen negativen oder großen `bigint`-Default als
`StringLiteral`; eine Ganzzahl über der Genauigkeit von `double` liest SQLite als `Double`.

**P1 (Entscheidung): B** (Vergleich), für alle Dialekte. Auswahlregel des Plans: P0 zeigt mehr als SQLite
betroffen und `Int`↔`Double` (`float`, `decimal`) → B. A (Reader) schiede ohnehin aus: der Datei-Parser wäre
nicht angeglichen, `schema reverse` schriebe `0.0`, und der Reader hat den Typ „boolean“ nicht. `0`/`1` ↔
Wahrheitswert: nur B. **Umsetzung ohne neuen Parameter an `TargetProjection`:** `ColumnDefaultProjection.project`
leitet die Faltung aus der bestehenden Typ-Projektion ab (faltet das Ziel `boolean` auf `integer` — nur SQLite —,
wird `BooleanLiteral` an einer `boolean`-Spalte zu `0`/`1`); `canonicalNumber` (über `BigDecimal`) trägt die
Zahlengleichheit und den Fingerabdruck. `TableComparator` und `MigrationFingerprint` rufen dieselbe Funktion;
`CanonicalPayload` bleibt unverändert (Operations-IDs stabil). Fingerabdruck **`schema-fingerprint-v21`**,
Entscheidung **ADR 0063**. Reader-Ausgang und `schema reverse` unverändert.

Umgeschriebene Tests: die `v20`-Literale in `MigrationFingerprintTest`, `RefTypeVocabularyTest`,
`IndexPredicateCanonicalisationTest` und `MigrationPlanArtifactBuilderTest`. Goldens: keine umgeschrieben.

**P2:** Tests `ColumnDefaultProjectionTest` (hexagon/core: Zahlenpaare, Wahrheitswert gegen `0`/`1`, strikt gegen
Ziel, Fingerabdruck), `CliSchemaMigrateDefaultSqliteTest` (33 Fälle: 21 unveränderte Zeilen mit Plan 0, `+d`
Exit 0, zweiter Lauf 0; 9 echte Änderungen; Ausführen mit Folgelauf; `schema reverse`; `schema rollback`),
`PostgresDefaultCompareIntegrationTest` und `MysqlDefaultCompareIntegrationTest` (je Zeilen unverändert und echte
Änderung). Spec: `spec/ddl-generation-rules.md` §3.2 (Zahlen-Defaults, alle Dialekte) und §3.5 (SQLite `boolean`).
**Rot ohne den Fix** (Vergleich auf `==` zurückgenommen): 14 der 33 CLI-Fälle (alle `integer`/`smallint`/
`biginteger 0`/`float 1`/`decimal 0`/`boolean`-Zeilen, `reverse`, `rollback`, Ausführen mit Folgelauf); die
gleichen Vergleiche liefen in P0 ungefixt auf PostgreSQL und MySQL mit `operationsTotal=1`. In
`ColumnDefaultProjectionTest` sind mit abgeschalteter Zahlen-/Wahrheitswert-Regel 11 Fälle rot. Grün mit dem Fix.

**Offen / abgetrennt:** (1) Der Neubau-CAST bei einer echten `boolean`-/`decimal`-Änderung (Exit 8) gehört zum
Schwesterplan `sqlite-neubau-cast-gleiche-speicherklasse.md`. (2) Ganzzahl über der Genauigkeit von `double`
bleibt ein Unterschied (SQLite-Exit 5 bei `--execute`). (3) PostgreSQL liest negative und große `bigint`-Defaults
als Text.

**Stand der Messung:** Die P0-Zeilen mit Exit 8 (`decimal` `0`, `boolean`) sind vor dem Merge des Schwesterplans
`sqlite-neubau-cast-gleiche-speicherklasse.md` gemessen; die Tests dieses Plans laufen nach dem Rebase auf dessen
Stand grün (Gates unten im Abschlussbericht des Implementierungslaufs, nicht hier).
