# SQLite: der Neubau blockiert an einer Spalte, deren Speicherklasse gleich bleibt (Issue #54, Ursache 2)

> **Status:** Draft mit Scope (2026-10-25), nach Review-Runde 2. Aus Issue #54 abgetrennt
> (`sqlite-default-vergleich.md` behandelt Ursache 1). Ein Tabellenneubau auf SQLite blockiert mit
> `MANUAL_ACTION_REQUIRED` / `SQLITE_CAST_NOT_WHITELISTED` (Exit 8), sobald die Tabelle eine Spalte trägt, die
> SQLite enger ablegt, als die Datei sie schreibt — auch wenn die Spalte unverändert bleibt. Ursache 2 ist die
> dringendere: sie blockiert **jeden** Neubau, unabhängig vom `default`. **Vorbedingung:** keine.
> **Aktivierung** (Move nach `../in-progress/`) beim ersten Implementierungs-Commit.

## Ziel

Ein Tabellenneubau, der aus einem anderen Grund nötig ist (`AddConstraint`, `AlterColumnNullability`, …),
blockiert nicht an einer Spalte, deren Typ sich nicht ändert.

## Problem

Messung (SQLite-CLI-Test, Plan-Only): `integer default 0` + `text max_length 32` → `AlterColumnDefault`,
Plan Exit 8; `boolean` und `decimal(10,2)` mit Default ebenso. Der Reporter sieht „der Neubau auf
`text(32)` ohne Default geht“ nur, weil dort kein Neubau ausgelöst wird.

### Ursache (am Code bestätigt, in P0 mit der Messung zu belegen)

Der Neubau bildet Ist (gelesen) und Soll in
[`SqliteRebuildPlanner.computeColumnMapping`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteRebuildPlanner.kt)
über `SqliteCastMatrix.isWhitelisted` ab; bei `false` füllt er `castBlocked`. SQLite rendert viele Typen als
dieselbe Speicherklasse (`SqliteTypeMapper.toSql`): `Char`, `Date`, `Time`, `DateTime`, `Uuid`, `Json`, `Xml`,
`Email`, `Array`, `Enum` → `TEXT`; `Boolean` → `INTEGER`; `Decimal` → `REAL`. Der Reader liest `Text(null)` /
`Integer` / `Float`; das Soll trägt den engeren Typ; die Matrix hält das für eine nicht erlaubte Umwandlung
(`covers(Unbounded, Bounded)` ist `false`). Nur `Enum` ist durch `onlyVocabularyDiffers` ausgenommen.
Betroffen ist also **jede** Spalte eines dieser Typen.

**Der Vergleich hat schon entschieden:** für diese Spalten meldet er keine Typänderung (sonst gäbe es
`AlterColumnType`); der Planer rechnet das aus `source`/`target` ein zweites Mal und kommt zu einem anderen
Ergebnis.

**Entwurfsentscheid, der betroffen ist** (Kommentar an `onlyVocabularyDiffers`): „Bewusst eng auf Paare mit
Enum-Beteiligung: SQLite legt auch alle Integer- und alle Textbreiten gleich ab, und dort ist die Umwandlung
eine Aussage – sie zählt für den Ziel-Dialekt einer späteren Migration“. Die Aussage gilt für
Datei-gegen-Datei-Pläne (`text(64)` → `text(32)` ist eine Verengung). Beim Reverse aus der Datenbank gibt es sie
nie: der Reader liefert nie `text(64)`, `Text(null)` → `Text(32)` ist dort Information, die SQLite nie hatte.

## Richtung (nach P0 zu entscheiden)

* **(a) Quelle aus der Datenbank:** die Faltung (`sql.toSql(source) == sql.toSql(target)` → keine Umwandlung)
  gilt nur, wenn `source` aus dem Reader stammt (oder der Vergleich die Projektion schon gefaltet hat). Die
  Whitelist-Semantik für Datei→Datei bleibt unverändert. **Bevorzugt.**
* **(b) Die Entscheidung des Vergleichs übernehmen:** der Planer behandelt nur die Spalten als Typänderung,
  für die der Vergleich einen Typunterschied meldet. Sauberer, setzt voraus, dass der Planer die Operationen
  oder die Projektion zur Hand hat (P0 prüfen).
* **(c) Whitelist erweitern** (`Text(null) → Text(n)`): **gestrichen** — sie legalisiert eine echte Verengung
  für andere Dialekte, ohne Gewinn gegenüber (a).
* **Wurzel prüfen:** `PRAGMA table_info` liefert den deklarierten Typ-String. Schreibt der Mapper nur
  `TEXT`/`INTEGER`/`REAL`, ist die Information verloren; steht `CHAR(10)`/`DATE`/`BOOLEAN` im `CREATE TABLE`
  (Fremddatenbank), liest der Reader mehr. Die Antwort bestimmt, ob eine Faltung genügt.

Kein Fingerabdruck-Bump und kein ADR, wenn nur der Planer ändert (a)/(b) und weder Reader-Ausgang noch
Projektion; sonst nach den Regeln der Schwesterpläne (Tag `v20`/`v21`, ADR `0062`/`0063`).

Nicht-Ziel: echte Typwechsel (`integer` → `text`), `Date` → `DateTime` (schon auf der Whitelist).

## Phasen

* **P0 Messung** (SQLite-CLI-Test): ein Neubau aus anderem Grund (`AddConstraint`/`AlterColumnNullability`)
  an einer Tabelle mit **je einer** Spalte jedes `NeutralType`-Konstruktors, dessen `toSql`-Ergebnis
  zurückgelesen einen anderen Typ ergibt (`text(n)`, `char(n)`, `date`, `time`, `datetime`, `uuid`, `json`,
  `xml`, `email`, `array`, `enum`, `boolean`, `decimal`), ohne Default; Ergebnis: Exit und `castBlocked`.
  Gegenprobe (muss weiter blocken): `text(64)` → `text(32)` Datei→Datei, `integer` → `text`. P0 endet, wenn
  die Tabelle voll ist.
* **P1 Entscheidung:** (a) oder (b); Begründung zum Entwurfsentscheid `onlyVocabularyDiffers`; Tag/ADR nur bei
  Bedarf.
* **P2 Umsetzung** nach P1: Unit (`SqliteRebuildPlanner`, `SqliteCastMatrix`), SQLite-CLI-Tests (Neubau
  `--execute` Exit 0, Daten erhalten, zweiter Lauf 0, Rückweg), Gegenproben.
* **P3 Doku:** `CHANGELOG.md`; Handbuch nur bei sichtbarer Änderung; `spec/ddl-generation-rules.md` zur
  Whitelist prüfen.

## Nicht-Scope

* Defaults (Schwesterplan), andere Dialekte, echte Typwechsel.

## Akzeptanz

* Jede in P0 als Fehler gemessene Zeile hat einen Test, der ohne die Änderung an der erwarteten Stelle
  fehlschlägt (`SQLITE_CAST_NOT_WHITELISTED`, Exit 8); die neuen Tests werden einmal mit absichtlich falscher
  Zusicherung rot gesehen.
* Die Gegenproben blocken weiter: `text(64)` → `text(32)` (Datei→Datei), `integer` → `text`
  (`SQLITE_CAST_NOT_WHITELISTED`).
* Ein Neubau mit unveränderter `text(n)`-/`boolean`-/`decimal`-/`uuid`-Spalte: `--execute` Exit 0, Daten erhalten,
  zweiter Lauf `operationsTotal` 0.
* `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module **und** einmal ohne `MODULES=`,
  `make integration INTEGRATION_TASKS=":test:integration-sqlite:test"`, `make solid-suppression-gate`,
  `make docs-check`, `make doc-immutable RANGE=origin/main..HEAD`.
