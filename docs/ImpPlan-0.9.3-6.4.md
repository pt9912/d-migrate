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
  nicht fuer triggerbasierte Sequence-Emulation
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

---

## 3. Scope fuer 6.4

### 3.1 In Scope

- `MysqlDdlGenerator.generateSequences(...)` fuer
  `ACTION_REQUIRED` vs. `HELPER_TABLE`
- generatorinterne Support-Helfer fuer:
  - `dmg_sequences`
  - Seed-Statements
  - `dmg_nextval`
  - `dmg_setval`
  - Trigger-Namensbildung
  - generierte `BEFORE INSERT`-Trigger
- MySQL-spezifische Behandlung von `SequenceNextVal`
- `W114`, `W115`, `W117` an den vorgesehenen Generatorstellen
- expliziter Rollback-Pfad fuer MySQL-Supportobjekte
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
  - `SequenceNextVal` erzeugt keine stille DEFAULT-Interpolation
  - betroffene Spalten bekommen eine strukturierte Diagnose
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

### 4.4 `SequenceNextVal` wird nicht als normaler SQL-DEFAULT aufgeloest

Verbindliche Folge:

- `TypeMapper.toDefaultSql(...)` bleibt fuer normale Literal- und
  Funktionsdefaults zustaendig
- der Interception-Punkt liegt in `AbstractDdlGenerator.columnSql()`
  bzw. einem aequivalent fruehen Dialekt-Hook
- fuer MySQL `helper_table` gibt der Sequence-Fall bewusst kein
  `DEFAULT ...`-SQL zurueck; der Trigger uebernimmt

### 4.5 Rollback ist ein eigener Generatorpfad

Verbindliche Folge:

- DELIMITER-verpackte Routinen/Trigger werden nicht ueber
  `invertStatement()` invertiert
- `MysqlDdlGenerator` erzeugt Drop-Statements fuer Supportobjekte
  explizit selbst
- feste Drop-Reihenfolge:
  1. Trigger
  2. Routinen
  3. `dmg_sequences`

### 4.6 Warning-Semantik wird an reale Erzeugungspunkte gebunden

Verbindliche Folge:

- `W114` bei Sequence-Definitionen mit gesetztem `cache`
- `W115` pro betroffener Spalte mit `SequenceNextVal`
- `W117` einmal pro DDL-Lauf im `helper_table`-Modus

---

## 5. Zielarchitektur

### 5.1 Generatorstruktur fuer `helper_table`

Empfohlene Aufteilung:

- `MysqlDdlGenerator.generateSequences(...)`
  - `ACTION_REQUIRED`: bisheriges Verhalten
  - `HELPER_TABLE`: Support-Tabelle + Seed-Statements + `W114`
- `MysqlDdlGenerator.generateFunctions(...)` oder generatorinterner
  Support-Helfer
  - emittiert `dmg_nextval`
  - emittiert `dmg_setval`
- `MysqlDdlGenerator.generateTriggers(...)`
  - emittiert zuerst generierte Sequence-Support-Trigger fuer
    `SequenceNextVal`
  - emittiert danach bestehende User-Trigger ueber
    `MysqlRoutineDdlHelper`

### 5.2 DDL-Vertrag pro Dialekt im 6.4-Kontext

PostgreSQL:

- bleibt fachlich auf nativer Sequence-Unterstuetzung
- ist in 6.4 nur Abgrenzung, nicht Implementierungsziel

MySQL `action_required`:

- `SequenceDefinition` bleibt `E056`
- `SequenceNextVal` erzeugt keine stille Default-Interpolation
- betroffene Spalten bekommen strukturierte Diagnose

MySQL `helper_table`:

- `SequenceDefinition` -> Zeile in `dmg_sequences`
- `SequenceNextVal(seq)` -> kanonischer `BEFORE INSERT`-Trigger
- `cache` bleibt Metadatum in `dmg_sequences`, ohne echte
  Preallocation

SQLite:

- bleibt in 0.9.3 ausserhalb des produktiven Generatorpfads

### 5.3 Namens- und Triggervertrag

Trigger-Namensbildung folgt dem bereits eingefrorenen Vertrag:

- `dmg_seq_<table16>_<column16>_<hash10>_bi`
- `table16` / `column16` aus den ersten 16 Zeichen des bereinigten
  ASCII-lowercased Namens
- `hash10` aus den ersten 10 lowercase-Hex-Zeichen von SHA-256 ueber
  `<table-lower>\u0000<column-lower>`

Trigger-Semantik:

- `BEFORE INSERT`
- fuellt die Zielspalte, wenn kein expliziter Wert vorhanden ist bzw.
  der MySQL-Pfad `NULL` an den Trigger uebergibt
- genau diese lossy-Semantik wird ueber `W115` dokumentiert

### 5.4 Rollback-Vertrag

Da `invertStatement()` fuer DELIMITER-Bloecke nicht ausreicht, erzeugt
`MysqlDdlGenerator` einen dedizierten Rollback-Pfad, z.B.
`generateSupportObjectRollback(...)`:

- direkte `DROP TRIGGER IF EXISTS`
- direkte `DROP FUNCTION IF EXISTS`
- direktes `DROP TABLE IF EXISTS`
- Einfuegung dieser Statements **vor** den invertierten regulaeren
  Statements in den Rollback-Block

Pflicht-Akzeptanzkriterien:

- feste Drop-Reihenfolge Trigger -> Routinen -> `dmg_sequences`
- alle Drops sind idempotent
- kompletter Up->Down-Lauf scheitert nicht an Supportobjekt-Reihenfolge

---

## 6. Konkrete Arbeitsschritte

### 6.1 Support-Helfer und Modusumschaltung einfuehren

- generatorinterne Helfer fuer Support-Tabelle, Seed-Statements,
  Routinen und Trigger-Namensbildung anlegen
- `MysqlDdlGenerator.generateSequences(...)` zwischen
  `ACTION_REQUIRED` und `HELPER_TABLE` umschalten

### 6.2 `SequenceNextVal` im MySQL-Generatorpfad behandeln

- MySQL-spezifischen Tabellenbau fuer `SequenceNextVal` nachziehen
- `helper_table`: kein SQL-DEFAULT, Trigger-Spez
- `action_required`: strukturierte Diagnose statt stiller SQL-Erzeugung
- keine stille Weitergabe an normale Funktions-/Stringpfade

### 6.3 Support-Routinen und Trigger erzeugen

- `dmg_nextval`
- `dmg_setval`
- generierte `BEFORE INSERT`-Trigger fuer alle betroffenen Spalten
- Reihenfolge im `POST_DATA`-Block absichern

### 6.4 Warning- und Konfliktfaelle verankern

- `W114`, `W115`, `W117` an den vorgesehenen Stellen emittieren
- Konfliktfaelle fuer reservierte Supportnamen erkennen
- keine stillen Fallback-Namen oder "best effort"-Umbenennungen

### 6.5 Expliziten Rollback-Pfad nachziehen

- dedizierte Erzeugung der Supportobjekt-Drops in `MysqlDdlGenerator`
- feste Drop-Reihenfolge absichern
- Rollback-Statements vor regulaerer Statement-Inversion einhaengen

---

## 7. Tests und Verifikation

### 7.1 Unit- und Generator-Tests

- `MysqlDdlGeneratorTest`:
  - `action_required` behaelt `E056`
  - `helper_table` erzeugt Tabelle, Seed, Routinen und Trigger
  - Warning-Semantik `W114` / `W115` / `W117`
  - Konfliktfall fuer reservierte Namen
  - Trigger-Namensbildung ist deterministisch
- Golden Masters:
  - MySQL `action_required`
  - MySQL `helper_table`
  - Split `pre-data` / `post-data`

### 7.2 MySQL-Integrationstests

- `dmg_nextval('invoice_seq')` liefert monoton steigende Werte
- parallele Aufrufe erzeugen keine Duplikate
- Rollback einer Transaktion zieht den Inkrement-Schritt zurueck
  und wird deshalb mit `W117` dokumentiert
- `INSERT` ohne Spaltenwert fuellt die Sequence-Spalte ueber den
  Support-Trigger
- explizites `NULL` triggert denselben Pfad und belegt die
  lossy-Semantik hinter `W115`
- Up->Down-Lauf prueft explizit die feste Drop-Reihenfolge:
  Trigger vor Routinen vor `dmg_sequences`

### 7.3 Akzeptanzkriterien

6.4 gilt als abgeschlossen, wenn gleichzeitig gilt:

- `schema generate --target mysql --mysql-named-sequences helper_table`
  erzeugt produktive DDL statt nur `E056`
- `action_required` bleibt unveraendert konservativ
- Supportobjekte und Trigger sind deterministisch benannt
- `PRE_DATA` / `POST_DATA` sind fuer Supportobjekte korrekt getrennt
- Rollback fuer Supportobjekte ist explizit, idempotent und
  reihenfolgesicher
- die vorgesehenen Warnings werden konsistent erzeugt

---

## 8. Betroffene Codebasis

Voraussichtlich direkt betroffen:

- `docs/mysql-sequence-emulation-plan.md`
- `docs/ddl-generation-rules.md`
- `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/AbstractDdlGenerator.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlRoutineDdlHelper.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlTypeMapper.kt`

Voraussichtlich testseitig betroffen:

- `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGeneratorTest.kt`
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/yaml/DdlGoldenMasterTest.kt`
- `test/integration-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/...`

---

## 9. Offene Punkte

### 9.1 Reverse/Compare bleiben bewusst ausgespart

6.4 friert den Generatorvertrag ein, nicht die Ruecklese- oder
Vergleichslogik der Hilfsobjekte. Diese Arbeit bleibt fuer 0.9.4
reserviert.

### 9.2 Der Triggerpfad bleibt bewusst lossy

MySQL kann im Triggerpfad explizites `NULL` nicht sauber von "Wert
ausgelassen" trennen. 0.9.3 dokumentiert diese Grenze ueber `W115`,
statt einen scheinbar perfekten, aber faktisch instabilen Sonderpfad zu
versprechen.
