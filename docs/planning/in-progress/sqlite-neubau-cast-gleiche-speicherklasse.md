# SQLite: der Neubau blockiert an einer Spalte, deren Speicherklasse gleich bleibt (Issue #54, Ursache 2)

> **Status:** Draft mit Scope (2026-10-25), nach Review-Runde 3. Aus Issue #54 abgetrennt
> (`sqlite-default-vergleich.md` behandelt Ursache 1). Ein Tabellenneubau auf SQLite blockiert mit
> `MANUAL_ACTION_REQUIRED` / `SQLITE_CAST_NOT_WHITELISTED` (Exit 8), sobald die Tabelle eine Spalte trägt, die
> SQLite enger ablegt, als die Datei sie schreibt — auch wenn die Spalte unverändert bleibt. Ursache 2 ist die
> dringendere: sie blockiert jeden Neubau einer Tabelle mit einer betroffenen Spalte, unabhängig vom `default`. **Vorbedingung:** keine.
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

**Der Vergleich faltet sogar die Verengung.** Im Migrate-Pfad plant `schema migrate` über den ziel-bewussten
Vergleich (`targetAwareComparator`, `TargetProjection(type = canonicalizeType)`): `canon(Text(64))` und
`canon(Text(32))` sind beide `Text()`, also meldet er auch `text(64)` → `text(32)` Datei→Datei **nicht** als
`AlterColumnType`. Dass dieser Fall heute blockiert, geschieht nur, weil der Planer ihn bei einem Neubau aus anderem
Grund ein zweites Mal rechnet — ein Zufall, den dieser Plan abstellt. Der strikte Vergleich (`schema compare` ohne
Projektion, Fallback im Runner) meldet `AlterColumnType` für `Text(null)` → `Text(32)`; dort blockt der Planer zu Recht.

**Entwurfsentscheid, der betroffen ist** (Kommentar an `onlyVocabularyDiffers`): „Bewusst eng auf Paare mit
Enum-Beteiligung: SQLite legt auch alle Integer- und alle Textbreiten gleich ab, und dort ist die Umwandlung
eine Aussage – sie zählt für den Ziel-Dialekt einer späteren Migration“. `onlyVocabularyDiffers` ist für Enum-Paare
eine handgebaute Doppelung der Projektion. **Der Reader liefert für eine Fremddatenbank mehr Typinformation:**
`VARCHAR(64)` liest er als `Text(maxLength=64)`, `CHAR(10)` als `Char(10)`, `BOOLEAN` als `BooleanType` —
`text(64)` → `text(32)` ist dort eine echte Verengung. Die Faltung darf diese Fälle nicht übergehen.

## Richtung (nach P0 zu entscheiden)

* **(a) „Quelle aus der Datenbank“ — nicht umsetzbar.** `planRebuild` bekommt `table`, `bucket`, `source`, `target`,
  `sql`, `catalog`, `sourceSchema`, `targetSchema`; `DiffResult` trägt `currentSchema`/`desiredSchema`, aber keine
  Herkunft (die Unterscheidung Datenbank/Datei existiert nur im Runner). Ein Herkunftsfeld quer durch Port,
  `DiffResult` und Generator wäre größer als der Fehler. **Gestrichen.**
* **(b) Die Entscheidung des Vergleichs übernehmen:** der Planer behandelt nur die Spalten als Typänderung, für die der
  Vergleich einen Typunterschied meldet (`AlterColumnType` im `bucket`; der Planer hat den `bucket`, die Machbarkeit
  steht). Tragfähig, **ändert aber die Gegenprobe**: unter (b) lässt der Migrate-Pfad `text(64)` → `text(32)`
  Datei→Datei durch (der ziel-bewusste Vergleich meldet sie nicht). Das ist konsistent mit dem Vergleich und ist in P1
  ausdrücklich zu entscheiden (siehe Gegenproben).
* **(d) Dieselbe Projektion im Planer:** `SqliteNeutralTypeCanonicalizer` (= `reverse(toSql(t))`, die Projektion des
  Vergleichs, im selben Modul) rechnen: `canon(source) == canon(target)` → keine Umwandlung. Trifft die Entscheidung des
  Vergleichs ohne Operationszugriff und ohne Signal; schärfer als `toSql`-Gleichheit in `onlyVocabularyDiffers`.
  Gleiche Folge für `text(64)` → `text(32)` wie (b). Zu prüfen: ob der `bucket` alle Typänderungen der Tabelle trägt
  (nicht in einem per Overlay umbenannten Eimer), und das Verhalten bei einer **Umbenennung** (`source.columns[name]`
  trifft nicht; die Spalte läuft als „hinzugefügt“) — ein Rename plus gefaltete Spalte darf nicht schlechter werden.
* **(c) Whitelist erweitern** (`Text(null) → Text(n)`): **gestrichen** (legalisiert eine echte Verengung für andere
  Dialekte).
* **Wurzel:** `PRAGMA table_info` liefert den deklarierten Typ-String; der Mapper schreibt nur `TEXT`/`INTEGER`/`REAL`,
  eine Fremddatenbank trägt mehr. Eine Faltung genügt für selbst erzeugte Datenbanken.

Kein Fingerabdruck-Bump und kein ADR, wenn nur der Planer ändert ((b)/(d)) und weder Reader-Ausgang noch
Projektion; sonst nach den Regeln der Schwesterpläne (Tag `v20`/`v21`, ADR `0062`/`0063`).

Nicht-Ziel: echte Typwechsel (`integer` → `text`), `Date` → `DateTime` (schon auf der Whitelist).

## Phasen

* **P0 Messung** (SQLite-CLI-Test): ein Neubau aus anderem Grund (`AddConstraint`/`AlterColumnNullability`)
  an einer Tabelle mit **je einer** Spalte jedes `NeutralType`-Konstruktors, dessen `toSql`-Ergebnis
  zurückgelesen einen anderen Typ ergibt (`text(n)`, `char(n)`, `date`, `time`, `datetime`, `uuid`, `json`,
  `xml`, `email`, `array`, `boolean`, `decimal`, `datetime` mit Zeitzone, `fulltext`, `identifier` ohne
  `auto_increment`, `float` mit Parametern, eine **Domain-Spalte** mit einem dieser Basistypen
  (`SqliteRefTypeColumns.withDomainBaseTypes` läuft vor `computeColumnMapping`)), ohne Default; Ergebnis: Exit und
  `castBlocked`. `enum` ist Regressionsprobe (schon ausgenommen), keine Fehlerprobe. Dazu **Fremddatenbank-Fälle**
  (`VARCHAR(64)`, `CHAR(10)`, `DATE`, `BOOLEAN` im `CREATE TABLE` einer handgebauten Datenbank).
  Gegenproben: `integer` → `text` **muss** weiter blocken; `text(64)` → `text(32)` Datei→Datei: Verhalten heute
  und unter (b)/(d) festhalten (P1 entscheidet); in einer Tabelle eine echte Typänderung (`integer` → `text`) neben einer
  gefalteten Spalte (`castBlocked` darf nur die echte enthalten); Rename plus gefaltete Spalte. P0 endet, wenn die
  Tabelle voll ist.
* **P1 Entscheidung:** (a) oder (b); Begründung zum Entwurfsentscheid `onlyVocabularyDiffers`; Tag/ADR nur bei
  Bedarf.
* **P2 Umsetzung** nach P1: Unit (`SqliteRebuildPlanner`, `SqliteCastMatrix`), SQLite-CLI-Tests (Neubau
  `--execute` Exit 0, Daten erhalten, zweiter Lauf 0, Rückweg), Gegenproben.
* **P3 Doku:** `CHANGELOG.md`; Handbuch nur bei sichtbarer Änderung; `spec/ddl-generation-rules.md` wird **mit** dem
  Code geändert, wenn die Whitelist-Semantik sich ändert (nicht erst hier). Bestehende Tests, die die Whitelist
  tragen (`SqliteRebuildRendererTest`, `SqliteRebuildH4Test`, `SqliteRebuildPlanTest`), sind in P1 zu benennen und
  bei Bedarf anzupassen.

## Nicht-Scope

* Defaults ([`sqlite-default-vergleich.md`](sqlite-default-vergleich.md)), andere Dialekte, echte Typwechsel; der
  Cast-Preflight (`SqliteCastPreflightSql` filtert auf `AlterColumnType`, ist nicht betroffen).

## Akzeptanz

* Jede in P0 als Fehler gemessene Zeile hat einen Test, der ohne die Änderung an der erwarteten Stelle
  fehlschlägt (`SQLITE_CAST_NOT_WHITELISTED`, Exit 8); die neuen Tests werden einmal mit absichtlich falscher
  Zusicherung rot gesehen.
* Gegenproben: `integer` → `text` blockt weiter (`SQLITE_CAST_NOT_WHITELISTED`); das Verhalten für `text(64)` →
  `text(32)` Datei→Datei ist in P1 entschieden und getestet; eine echte Typänderung neben einer gefalteten Spalte lässt
  nur die echte in `castBlocked`.
* **Reihenfolge-Pflicht:** P0 (Tabelle im Plan-Nachtrag) vor P1 vor P2.
* Ein Neubau mit unveränderter `text(n)`-/`boolean`-/`decimal`-/`uuid`-Spalte: `--execute` Exit 0, Daten erhalten,
  zweiter Lauf `operationsTotal` 0.
* `make docker-test` (ganzes Repo), `make docker-check` der betroffenen Module **und** einmal ohne `MODULES=`,
  `make integration INTEGRATION_TASKS=":test:integration-sqlite:test"`, `make solid-suppression-gate`,
  `make docs-check`, `make doc-immutable RANGE=origin/main..HEAD`.
