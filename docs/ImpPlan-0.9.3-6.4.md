# Implementierungsplan: 0.9.3 - Arbeitspaket 6.4 `Sequence Phase B: MySQL-Generator fuer helper_table`

> **Milestone**: 0.9.3 - Beta: Filter-Haertung und
> MySQL-Sequence-Emulation (Generator)
> **Arbeitspaket**: 6.4 (`Sequence Phase B: MySQL-Generator fuer
> helper_table`)
> **Status**: Draft (2026-04-20)
> **Referenz**: `docs/implementation-plan-0.9.3.md` Abschnitt 4.4,
> Abschnitt 4.5, Abschnitt 4.6, Abschnitt 4.7, Abschnitt 5.4,
> Abschnitt 5.5, Abschnitt 5.6, Abschnitt 6.4, Abschnitt 6.6,
> Abschnitt 7 und Abschnitt 8;
> `docs/mysql-sequence-emulation-plan.md`;
> `docs/ddl-generation-rules.md`;
> `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/AbstractDdlGenerator.kt`;
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt`;
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlColumnConstraintHelper.kt`;
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlRoutineDdlHelper.kt`;
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlTypeMapper.kt`;
> `docs/ImpPlan-0.9.3-6.2.md`;
> `docs/ImpPlan-0.9.3-6.3.md`.

---

## 1. Ziel

Arbeitspaket 6.4 macht den in 6.2 und 6.3 vorbereiteten Vertrag
generatorseitig wirksam:

- `schema generate --target mysql --mysql-named-sequences helper_table`
  erzeugt produktive DDL statt nur `E056`
- benannte Sequences werden ueber kanonische Supportobjekte emuliert:
  - `dmg_sequences`
  - `dmg_nextval`
  - `dmg_setval`
  - generierte `BEFORE INSERT`-Trigger
- der 0.9.2-Phasenschnitt (`PRE_DATA` / `POST_DATA`) bleibt erhalten
- Rollback fuer die neuen Supportobjekte ist explizit und testbar

6.4 liefert bewusst noch nicht:

- Reverse der MySQL-Supportobjekte
- Compare-/Diff-Normalisierung gegen die Hilfsobjekte
- SQLite-Sequence-Emulation

Nach 6.4 soll klar gelten:

- `helper_table` ist ein echter produktiver DDL-Pfad
- `action_required` bleibt weiterhin der konservative Default
- Trigger-, Routine- und Hilfstabellen-Namen sind deterministisch
- Supportobjekte sind vorwaerts und rueckwaerts in fester Reihenfolge
  erzeugbar

---

## 2. Ausgangslage

Nach 6.2 und 6.3 ist der Rahmen vorbereitet, aber der eigentliche
Generatorpfad fehlt noch:

- `MysqlNamedSequenceMode` und der sichtbare CLI-Vertrag sind bekannt
- `SequenceNextVal` ist als neutraler Modelltyp vorbereitet
- `MysqlDdlGenerator.generateSequences(...)` erzeugt heute weiterhin
  nur `ACTION_REQUIRED`/`E056`
- `TypeMapper.toDefaultSql(...)` ist fuer normale Defaults gedacht,
  nicht fuer triggerbasierte Sequence-Emulation; in 6.3 wurde fuer
  MySQL ein defensiver `error("...")`-Guard eingefuegt
- `AbstractDdlGenerator.generateRollback(...)` basiert auf
  Statement-Inversion via `invertStatement()` und versteht keine
  DELIMITER-Bloecke

Das reicht fuer 0.9.3 nicht:

- MySQL hat ohne 6.4 keinen produktiven Pfad fuer benannte Sequences
- Supportobjekte muessen phasenrichtig verteilt werden
- Rollback darf fuer Routinen/Trigger nicht auf generischer
  String-Inversion beruhen
- die Warnings `W114`, `W115`, `W117` muessen an konkreten
  Erzeugungsstellen haengen

### 2.1 Architektonische Restriktionen im bestehenden Generator

Der `AbstractDdlGenerator`-Lifecycle hat fuer 6.4 drei strukturelle
Engpaesse:

**A. `generateSequences` hat keinen Zugriff auf `DdlGenerationOptions`:**

Die abstrakte Signatur (Zeile 95):
```kotlin
abstract fun generateSequences(
    sequences: Map<String, SequenceDefinition>,
    skipped: MutableList<SkippedObject>
): List<DdlStatement>
```
empfaengt kein `options`-Objekt, aber der MySQL-Generator braucht
`options.mysqlNamedSequenceMode` fuer die Modusumschaltung.

**B. Support-Routinen/-Trigger passen nicht in die User-Objekt-Hooks:**

`AbstractDdlGenerator.generate()` ruft auf:
- `generateFunctions(schema.functions, ...)` → POST_DATA
- `generateTriggers(schema.triggers, schema.tables, ...)` → POST_DATA

Beide empfangen User-definierte Objekte. Support-Routinen
(`dmg_nextval`, `dmg_setval`) und Support-Trigger
(BEFORE INSERT pro Spalte) sind aber keine User-Objekte — sie werden
aus `schema.sequences` und den `SequenceNextVal`-Spalten abgeleitet.

**C. `SequenceNextVal`-Spalten erreichen den `error()`-Guard:**

Der Spaltenpfad fuer MySQL:
```
MysqlDdlGenerator.generateTable()
  → MysqlColumnConstraintHelper.generateColumnSql()
    → else → AbstractDdlGenerator.columnSql()
      → typeMapper.toDefaultSql(SequenceNextVal) → error(...)
```
Der `error()`-Guard aus 6.3 feuert, bevor der Generator
intervenieren kann. Es braucht einen Interception-Punkt davor.

---

## 3. Scope fuer 6.4

### 3.1 In Scope

- `MysqlDdlGenerator`-Erweiterung: `generate()` Override mit
  Options-Persistierung und Support-Objekt-Injection
- `MysqlColumnConstraintHelper.generateColumnSql()` Erweiterung
  fuer `SequenceNextVal`-Interception
- generatorinterne Support-Helfer fuer:
  - `dmg_sequences`
  - Seed-Statements
  - `dmg_nextval`
  - `dmg_setval`
  - Trigger-Namensbildung (`MysqlSequenceNaming` Utility)
  - generierte `BEFORE INSERT`-Trigger
- MySQL-spezifische Behandlung von `SequenceNextVal` in beiden Modi
- `W114`, `W115`, `W117` an den vorgesehenen Generatorstellen
- expliziter Rollback-Pfad via `generateRollback()`-Override
- Ledger-Hochstufung der Warning-Codes von `reserved` auf `active`
- Unit-, Golden-Master- und MySQL-Integrationstests fuer
  `helper_table`

### 3.2 Bewusst nicht Teil von 6.4

- PostgreSQL-/SQLite-Modellarbeit aus 6.3
- Reverse/Compare gegen echte Supportobjekte
- frei konfigurierbare Supportobjektnamen
- alternative Emulationsmodi jenseits von `action_required` und
  `helper_table`

Praezisierung:

6.4 loest "wie erzeugt MySQL produktive DDL fuer helper_table?",
nicht "wie werden diese Hilfsobjekte spaeter wieder eingelesen?".

---

## 4. Leitentscheidungen

### 4.1 `action_required` bleibt konservativ, `helper_table` ist opt-in

Verbindliche Folge:

- `ACTION_REQUIRED` behaelt den bisherigen DDL-Charakter:
  - `SequenceDefinition` bleibt `E056`
  - `SequenceNextVal`-Spalten werden ohne `DEFAULT`-Klausel
    erzeugt; stattdessen emittiert der Generator eine strukturierte
    `ACTION_REQUIRED`-Diagnose (E056) mit Hinweis auf
    `--mysql-named-sequences helper_table`
- `HELPER_TABLE` ist der einzige produktive MySQL-Emulationspfad in
  0.9.3

### 4.2 Supportobjekte sind kanonisch und nicht konfigurierbar

Verbindliche Folge:

- feste Support-Namen:
  - `dmg_sequences`
  - `dmg_nextval`
  - `dmg_setval`
  - `dmg_seq_<table16>_<column16>_<hash10>_bi`
- keine Prefix-/Suffix-Konfiguration in 0.9.3
- Namenskollisionen fuehren nicht zu stillen Fallbacks, sondern zu
  strukturierter Diagnose

### 4.3 Der 0.9.2-Phasenvertrag bleibt erhalten

Verbindliche Folge:

- `dmg_sequences` und Seed-Statements liegen in `PRE_DATA`
- `dmg_nextval` / `dmg_setval` liegen in `POST_DATA`
- generierte Support-Trigger liegen in `POST_DATA`
- User-Trigger bleiben danach in derselben relativen Reihenfolge wie
  bisher

### 4.4 Options-Zugriff ueber `generate()`-Override

Die abstrakte Signatur von `generateSequences` wird bewusst **nicht**
geaendert, um den Blast Radius auf den MySQL-Adapter zu begrenzen.

Verbindliche Loesung:

- `MysqlDdlGenerator` ueberschreibt `generate(schema, options)`:
  1. speichert `options` in einem privaten Feld
     `currentOptions: DdlGenerationOptions`
  2. ruft `super.generate(schema, options)` auf
  3. das Feld wird in `generateSequences()`,
     `generateFunctions()`, `generateTriggers()` und
     `generateColumnSql()` gelesen

Begruendung:

- die abstrakte Signatur und alle anderen Dialekte bleiben
  unveraendert
- das Muster ist analog zu `MysqlDdlGenerator.routineHelper`, das
  ebenfalls als Instanzvariable lebt

### 4.5 Support-Objekte werden ueber Override-Hooks injiziert

Die Support-Routinen (`dmg_nextval`, `dmg_setval`) und
Support-Trigger (BEFORE INSERT) sind keine User-Objekte und koennen
nicht aus `schema.functions` oder `schema.triggers` kommen.

Verbindliche Loesung:

- `MysqlDdlGenerator` ueberschreibt `generateFunctions(...)`:
  - im `HELPER_TABLE`-Modus: emittiert zuerst die Support-Routinen
    (`dmg_nextval`, `dmg_setval`), dann delegiert an
    `MysqlRoutineDdlHelper` fuer User-Funktionen
  - im `ACTION_REQUIRED`-Modus: delegiert direkt an
    `MysqlRoutineDdlHelper`
- `MysqlDdlGenerator` ueberschreibt `generateTriggers(...)`:
  - im `HELPER_TABLE`-Modus: emittiert zuerst die Support-Trigger
    fuer alle gesammelten `SequenceNextVal`-Spalten (siehe §4.7),
    dann delegiert an `MysqlRoutineDdlHelper` fuer User-Trigger
  - im `ACTION_REQUIRED`-Modus: delegiert direkt an
    `MysqlRoutineDdlHelper`

Die Phasenzuordnung (`POST_DATA`) ergibt sich automatisch, weil
`AbstractDdlGenerator.generate()` die Rueckgaben von
`generateFunctions()` und `generateTriggers()` bereits mit
`.withPhase(DdlPhase.POST_DATA)` tagged (Zeile 70, 78).

### 4.6 `SequenceNextVal`-Interception in `MysqlColumnConstraintHelper`

Der `error()`-Guard in `MysqlTypeMapper.toDefaultSql()` aus 6.3 darf
nicht erreicht werden. Die Interception muss davor greifen.

Verbindliche Loesung:

`MysqlColumnConstraintHelper.generateColumnSql()` (Zeile 22-28)
bekommt einen neuen Zweig **vor** dem `else`-Fall:

```kotlin
fun generateColumnSql(...): String = when {
    // ... bestehende Zweige (autoIncrement, Enum, Geometry) ...
    col.default is DefaultValue.SequenceNextVal -> columnSequenceNextVal(colName, col, notes)
    else -> columnSql(colName, col, schema)
}
```

Verhalten von `columnSequenceNextVal()`:

- **`HELPER_TABLE`-Modus**: erzeugt die Spalte OHNE `DEFAULT`-Klausel
  und sammelt die Trigger-Metadaten (siehe §4.7). Emittiert `W115`.
- **`ACTION_REQUIRED`-Modus**: erzeugt die Spalte OHNE
  `DEFAULT`-Klausel und emittiert eine `ACTION_REQUIRED`-Diagnose
  (E056) mit Hinweis auf `--mysql-named-sequences helper_table`.
- In beiden Faellen wird `toDefaultSql()` **nicht** aufgerufen.

Damit bleibt der 6.3-`error()`-Guard in `MysqlTypeMapper` als
Sicherheitsnetz bestehen, wird aber im Normalbetrieb nie erreicht.

### 4.7 Spalten-zu-Trigger-Metadatenfluss ueber Mutable State

Waehrend `generateTable()` werden `SequenceNextVal`-Spalten erkannt
(§4.6). Die daraus resultierenden Trigger muessen aber erst spaeter
in `generateTriggers()` (POST_DATA) erzeugt werden.

Verbindliche Loesung:

- `MysqlDdlGenerator` erhaelt eine private mutable Liste:

```kotlin
private val pendingSupportTriggers = mutableListOf<SupportTriggerSpec>()
```

- `SupportTriggerSpec` ist eine interne Datenklasse:

```kotlin
private data class SupportTriggerSpec(
    val tableName: String,
    val columnName: String,
    val sequenceName: String,
)
```

- `columnSequenceNextVal()` in `MysqlColumnConstraintHelper`
  (§4.6) fuegt im `HELPER_TABLE`-Modus einen Eintrag hinzu
  (ueber einen Callback oder direkten Zugriff auf die Liste)
- `generateTriggers()` liest die Liste und erzeugt die
  Support-Trigger
- `generate()` leert die Liste zu Beginn jedes Laufs

### 4.8 Rollback ueber `generateRollback()`-Override

`AbstractDdlGenerator.generateRollback()` (Zeile 84-88) hat keinen
Hook fuer Prepend-Statements.

Verbindliche Loesung:

- `MysqlDdlGenerator` ueberschreibt `generateRollback(schema, options)`:
  1. speichert `options` in `currentOptions` (wie in §4.4)
  2. ruft `super.generateRollback(schema, options)` auf
  3. erzeugt die Support-Objekt-Drops separat via
     `generateSupportObjectRollback(schema)`:
     - `DROP TRIGGER IF EXISTS` fuer jeden Support-Trigger
     - `DROP FUNCTION IF EXISTS dmg_nextval`
     - `DROP FUNCTION IF EXISTS dmg_setval`
     - `DROP TABLE IF EXISTS dmg_sequences`
  4. stellt die Support-Drops **vor** die invertierten regulaeren
     Statements
- im `ACTION_REQUIRED`-Modus wird direkt an
  `super.generateRollback()` delegiert

Feste Drop-Reihenfolge:
1. Trigger
2. Routinen
3. `dmg_sequences`

### 4.9 Warning-Semantik wird an reale Erzeugungspunkte gebunden

Verbindliche Folge:

- `W114` bei Sequence-Definitionen mit gesetztem `cache`
  (in `generateSequences()`)
- `W115` pro betroffener Spalte mit `SequenceNextVal`
  (in `columnSequenceNextVal()`)
- `W117` einmal pro DDL-Lauf im `helper_table`-Modus
  (in `generate()`, nach `super.generate()`)
- `W116` bleibt in 6.4 weiterhin `reserved` im Ledger; er wird erst
  emittiert, wenn die Semantik in einem spaeteren Arbeitspaket
  implementiert wird

Ledger-Hochstufung:

- W114, W115, W117 werden in `ledger/warn-code-ledger-0.9.3.yaml`
  von `status: reserved` auf `status: active` hochgestuft
- `test_path` und `evidence_paths` werden ergaenzt

---

## 5. Zielarchitektur

### 5.1 Generator-Lifecycle fuer `helper_table`

Der Ablauf von `MysqlDdlGenerator.generate()` im
`HELPER_TABLE`-Modus:

```
generate(schema, options)
  ├── currentOptions = options               (§4.4)
  ├── pendingSupportTriggers.clear()         (§4.7)
  ├── super.generate(schema, options)
  │     ├── generateHeader()
  │     ├── generateCustomTypes()
  │     ├── generateSequences()              → PRE_DATA
  │     │     └── HELPER_TABLE: dmg_sequences + Seed + W114
  │     ├── for table in sorted:
  │     │     └── generateTable()
  │     │           └── generateColumnSql()
  │     │                 └── SequenceNextVal? → columnSequenceNextVal()
  │     │                       ├── Spalte OHNE DEFAULT
  │     │                       ├── pendingSupportTriggers += Spec
  │     │                       └── W115
  │     ├── generateFunctions()              → POST_DATA
  │     │     ├── dmg_nextval (Support)
  │     │     ├── dmg_setval (Support)
  │     │     └── User-Funktionen
  │     ├── generateTriggers()               → POST_DATA
  │     │     ├── Support-Trigger (aus pendingSupportTriggers)
  │     │     └── User-Trigger
  │     └── return DdlResult
  ├── W117 an DdlResult anhaengen           (§4.9)
  └── return DdlResult
```

### 5.2 DDL-Vertrag pro Dialekt im 6.4-Kontext

PostgreSQL:

- bleibt fachlich auf nativer Sequence-Unterstuetzung
- ist in 6.4 nur Abgrenzung, nicht Implementierungsziel

MySQL `action_required`:

- `SequenceDefinition` bleibt `E056`
- `SequenceNextVal`-Spalten werden ohne `DEFAULT`-Klausel erzeugt;
  die betroffene Spalte bekommt eine `ACTION_REQUIRED`-Diagnose
  (E056) mit Hinweis auf `--mysql-named-sequences helper_table`
- kein Aufruf von `toDefaultSql()` fuer `SequenceNextVal`

MySQL `helper_table`:

- `SequenceDefinition` → Zeile in `dmg_sequences`
- `SequenceNextVal(seq)` → kanonischer `BEFORE INSERT`-Trigger
- `cache` bleibt Metadatum in `dmg_sequences`, ohne echte
  Preallocation

SQLite:

- bleibt in 0.9.3 ausserhalb des produktiven Generatorpfads

### 5.3 Namens-Utility und Triggervertrag

Naming-Utility:

- neues internes Objekt `MysqlSequenceNaming` im MySQL-Adapter-Modul
- kapselt die Namensregeln aus 6.2 §4.6:
  - `triggerName(tableName, columnName) → String`
  - `normalize(name) → String` (ASCII-lowercase, nicht-alphanumerische
    Zeichen ausser `_` entfernen)
  - `hash10(tableNorm, columnNorm) → String` (SHA-256, erste 10
    lowercase-Hex-Zeichen)
- die Funktionen werden unit-getestet (deterministisch und
  reproduzierbar)
- dieses Utility wird spaeter auch fuer Reverse gebraucht und lebt
  daher als eigenstaendiges Objekt, nicht als private Methode im
  Generator

Trigger-Namensbildung folgt dem bereits eingefrorenen Vertrag:

- `dmg_seq_<table16>_<column16>_<hash10>_bi`
- `table16` / `column16` aus den ersten 16 Zeichen des bereinigten
  ASCII-lowercased Namens
- `hash10` aus den ersten 10 lowercase-Hex-Zeichen von SHA-256 ueber
  `<tableNorm>\u0000<columnNorm>` (ungekuerzte normalisierte Namen)

Trigger-Semantik:

- `BEFORE INSERT`
- fuellt die Zielspalte, wenn kein expliziter Wert vorhanden ist bzw.
  der MySQL-Pfad `NULL` an den Trigger uebergibt
- genau diese lossy-Semantik wird ueber `W115` dokumentiert

### 5.4 Rollback-Vertrag

`MysqlDdlGenerator` ueberschreibt `generateRollback()` (§4.8):

- im `HELPER_TABLE`-Modus:
  - erzeugt Support-Drops via `generateSupportObjectRollback(schema)`
  - ruft `super.generateRollback(schema, options)` auf
  - stellt Support-Drops vor die regulaeren invertierten Statements
- im `ACTION_REQUIRED`-Modus:
  - delegiert direkt an `super.generateRollback(schema, options)`

Support-Drop-Statements:

- direkte `DROP TRIGGER IF EXISTS` (fuer jeden Support-Trigger,
  basierend auf dem Schema-Scan von Spalten mit `SequenceNextVal`)
- direkte `DROP FUNCTION IF EXISTS dmg_nextval`
- direkte `DROP FUNCTION IF EXISTS dmg_setval`
- direktes `DROP TABLE IF EXISTS dmg_sequences`

Pflicht-Akzeptanzkriterien:

- feste Drop-Reihenfolge Trigger → Routinen → `dmg_sequences`
- alle Drops sind idempotent
- kompletter Up→Down-Lauf scheitert nicht an Supportobjekt-Reihenfolge

---

## 6. Konkrete Arbeitsschritte

### 6.1 `MysqlSequenceNaming` Utility anlegen

- `MysqlSequenceNaming` als internes Objekt im MySQL-Adapter-Modul
- `normalize(name): String`
- `hash10(tableNorm, columnNorm): String`
- `triggerName(tableName, columnName): String`
- Unit-Tests in `MysqlSequenceNamingTest`

### 6.2 Options-Persistierung und Mutable State einfuehren

- `MysqlDdlGenerator`: privates Feld `currentOptions`
- `MysqlDdlGenerator`: private Liste `pendingSupportTriggers`
- `SupportTriggerSpec` Datenklasse
- `generate()`-Override: Options speichern, Liste leeren,
  `super.generate()` aufrufen, W117 anhaengen

### 6.3 `generateSequences()` Modusumschaltung

- `ACTION_REQUIRED`: bisheriges E056-Verhalten
- `HELPER_TABLE`:
  - `CREATE TABLE dmg_sequences` mit Spalten fuer Name, Startwert,
    Inkrement, Cache, aktuellen Wert
  - `INSERT INTO dmg_sequences` pro Sequence (Seed-Statements)
  - `W114` fuer Sequences mit gesetztem `cache`

### 6.4 `SequenceNextVal`-Interception im Spaltenbau

- `MysqlColumnConstraintHelper.generateColumnSql()`: neuer Zweig
  fuer `col.default is DefaultValue.SequenceNextVal`
- `MysqlColumnConstraintHelper` braucht dafuer Zugriff auf:
  - den aktuellen Modus (ueber Callback oder Referenz auf
    `currentOptions`)
  - die `pendingSupportTriggers`-Liste (ueber Callback)
- `columnSequenceNextVal()`:
  - `HELPER_TABLE`: Spalte ohne DEFAULT, Trigger-Spec sammeln, W115
  - `ACTION_REQUIRED`: Spalte ohne DEFAULT, E056-Diagnose

### 6.5 Support-Routinen und -Trigger erzeugen

- `generateFunctions()`-Override:
  - `HELPER_TABLE`: `dmg_nextval` + `dmg_setval` emittieren, dann
    `MysqlRoutineDdlHelper` fuer User-Funktionen
  - `ACTION_REQUIRED`: direkt an `MysqlRoutineDdlHelper`
- `generateTriggers()`-Override:
  - `HELPER_TABLE`: Support-Trigger aus `pendingSupportTriggers`
    erzeugen, dann `MysqlRoutineDdlHelper` fuer User-Trigger
  - `ACTION_REQUIRED`: direkt an `MysqlRoutineDdlHelper`

### 6.6 Warning- und Konfliktfaelle verankern

- `W114`, `W115`, `W117` an den in §4.9 festgelegten Stellen
  emittieren
- Konfliktfaelle fuer reservierte Supportnamen erkennen
  (Schema enthaelt bereits Tabelle/Funktion/Trigger mit
  `dmg_sequences`, `dmg_nextval`, `dmg_setval` oder dem generierten
  Triggernamen)
- keine stillen Fallback-Namen oder "best effort"-Umbenennungen

### 6.7 Expliziten Rollback-Pfad nachziehen

- `generateRollback()`-Override (§4.8)
- `generateSupportObjectRollback(schema)`:
  - Schema-Scan fuer `SequenceNextVal`-Spalten (fuer Triggernamen)
  - Drop-Statements in fester Reihenfolge
- feste Drop-Reihenfolge absichern

### 6.8 Ledger-Hochstufung

- W114, W115, W117 in `ledger/warn-code-ledger-0.9.3.yaml` von
  `status: reserved` auf `status: active` aendern
- `test_path` und `evidence_paths` ergaenzen
- W116 bleibt `reserved`

---

## 7. Tests und Verifikation

### 7.1 Unit- und Generator-Tests

- `MysqlSequenceNamingTest`:
  - deterministische Triggernamen fuer bekannte Eingaben
  - Kuerzung bei langen Tabellen-/Spaltennamen
  - Hash-Stabilitaet (gleiche Eingabe → gleicher Hash)
  - Sonderzeichen-Bereinigung
- `MysqlDdlGeneratorTest`:
  - `action_required` behaelt `E056` fuer `SequenceDefinition`
  - `action_required` + `SequenceNextVal`-Spalte: Spalte ohne
    DEFAULT, E056-Diagnose mit helper_table-Hinweis
  - `helper_table` erzeugt Tabelle, Seed, Routinen und Trigger
  - `helper_table` + Spalte mit `SequenceNextVal`: kein DEFAULT
    in der Spalte, Support-Trigger vorhanden
  - `toDefaultSql()`-`error()`-Guard wird nie erreicht
  - Warning-Semantik `W114` / `W115` / `W117`
  - Konfliktfall fuer reservierte Namen
  - Trigger-Namensbildung ist deterministisch
  - Phasen: `dmg_sequences` in PRE_DATA, Routinen/Trigger in
    POST_DATA
  - Rollback: Support-Drops vor regulaeren invertierten Statements,
    feste Drop-Reihenfolge
- Golden Masters:
  - MySQL `action_required` (mit SequenceNextVal-Spalte)
  - MySQL `helper_table`
  - Split `pre-data` / `post-data`
  - Rollback `helper_table`
- `CodeLedgerValidationTest`:
  - W114, W115, W117 sind `active` mit gueltigem `test_path`

### 7.2 MySQL-Integrationstests

Neuer Integrationstest:
`test/integration-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceEmulationIntegrationTest.kt`

Testfaelle:

- `dmg_nextval('invoice_seq')` liefert monoton steigende Werte
- parallele Aufrufe erzeugen keine Duplikate
- Rollback einer Transaktion zieht den Inkrement-Schritt zurueck
  und wird deshalb mit `W117` dokumentiert
- `INSERT` ohne Spaltenwert fuellt die Sequence-Spalte ueber den
  Support-Trigger
- explizites `NULL` triggert denselben Pfad und belegt die
  lossy-Semantik hinter `W115`
- Up→Down-Lauf prueft explizit die feste Drop-Reihenfolge:
  Trigger vor Routinen vor `dmg_sequences`

### 7.3 Akzeptanzkriterien

6.4 gilt als abgeschlossen, wenn gleichzeitig gilt:

- `schema generate --target mysql --mysql-named-sequences helper_table`
  erzeugt produktive DDL statt nur `E056`
- `action_required` + `SequenceNextVal`-Spalte erzeugt E056-Diagnose
  mit Migrationshinweis, ohne den `error()`-Guard zu erreichen
- Supportobjekte und Trigger sind deterministisch benannt
- `PRE_DATA` / `POST_DATA` sind fuer Supportobjekte korrekt getrennt
- Rollback fuer Supportobjekte ist explizit, idempotent und
  reihenfolgesicher
- die vorgesehenen Warnings werden konsistent erzeugt
- W114, W115, W117 sind im Ledger `active` mit Tests

---

## 8. Betroffene Codebasis

Voraussichtlich direkt betroffen (Produktionscode):

- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt`
  — `generate()`-Override, `generateFunctions()`-Override,
  `generateTriggers()`-Override, `generateRollback()`-Override,
  `currentOptions`, `pendingSupportTriggers`, `SupportTriggerSpec`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlColumnConstraintHelper.kt`
  — neuer `SequenceNextVal`-Zweig in `generateColumnSql()`,
  `columnSequenceNextVal()`-Methode
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceNaming.kt`
  — neues Utility-Objekt (Triggernamensbildung, Hash, Normierung)
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlRoutineDdlHelper.kt`
  — ggf. geringfuegige Anpassungen fuer Zusammenspiel mit
  Support-Routinen
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlTypeMapper.kt`
  — `error()`-Guard aus 6.3 bleibt als Sicherheitsnetz, keine
  funktionale Aenderung
- `docs/mysql-sequence-emulation-plan.md`
- `docs/ddl-generation-rules.md`
- `ledger/warn-code-ledger-0.9.3.yaml` — W114/W115/W117 Hochstufung

Nicht betroffen (bewusst):

- `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/AbstractDdlGenerator.kt`
  — keine Signatur- oder Verhaltensaenderung

Voraussichtlich testseitig betroffen:

- `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceNamingTest.kt`
  — neuer Test fuer Naming-Utility
- `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGeneratorTest.kt`
  — umfangreiche Erweiterung fuer beide Modi
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/yaml/DdlGoldenMasterTest.kt`
  — neue Golden Masters
- `test/integration-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceEmulationIntegrationTest.kt`
  — neuer Integrationstest
- `hexagon/core/src/test/kotlin/dev/dmigrate/core/validation/CodeLedgerValidationTest.kt`
  — W114/W115/W117 sind jetzt `active`

---

## 9. Offene Punkte

### 9.1 Reverse/Compare bleiben bewusst ausgespart

6.4 friert den Generatorvertrag ein, nicht die Ruecklese- oder
Vergleichslogik der Hilfsobjekte. Diese Arbeit bleibt fuer 0.9.4
reserviert. `MysqlSequenceNaming` ist bewusst als eigenstaendiges
Utility angelegt, damit Reverse dieselbe Namenslogik nutzen kann.

### 9.2 Der Triggerpfad bleibt bewusst lossy

MySQL kann im Triggerpfad explizites `NULL` nicht sauber von "Wert
ausgelassen" trennen. 0.9.3 dokumentiert diese Grenze ueber `W115`,
statt einen scheinbar perfekten, aber faktisch instabilen Sonderpfad zu
versprechen.

### 9.3 W116 bleibt `reserved`

W116 wird in 6.4 weder emittiert noch auf `active` hochgestuft.
Der Status `reserved` bleibt bestehen, bis ein spaeaeteres
Arbeitspaket die zugehoerige Semantik implementiert.
