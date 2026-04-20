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

### 2.1 Sequentielle Abhaengigkeit von 6.2 und 6.3

6.4 setzt voraus, dass 6.2 und 6.3 **vollstaendig abgeschlossen**
sind. Auf dem aktuellen HEAD fehlen die folgenden Voraussetzungen,
die in 6.2/6.3 geliefert werden:

- `DefaultValue.SequenceNextVal` existiert noch nicht
  (`DefaultValue.kt` kennt nur 4 Subtypen)
- `DdlGenerationOptions.mysqlNamedSequenceMode` existiert noch nicht
- `MysqlNamedSequenceMode` Enum existiert noch nicht
- `SchemaGenerateCommand` kennt `--mysql-named-sequences` noch nicht
- `MysqlTypeMapper.toDefaultSql()` hat noch keinen `SequenceNextVal`-
  Zweig (weder Guard noch Implementierung)
- 0.9.3-Ledgerdateien existieren noch nicht
  (`ledger/warn-code-ledger-0.9.3.yaml`, `code-ledger-0.9.3.schema.json`)
- `CodeLedgerValidationTest` kennt noch keinen Status `reserved`

All diese Artefakte werden in 6.2 bzw. 6.3 geliefert. 6.4 darf
**nicht** parallel zu 6.2/6.3 gestartet werden.

### 2.2 Verbleibende Luecken nach 6.2/6.3

Nach 6.2 und 6.3 ist der Rahmen vorbereitet, aber der eigentliche
Generatorpfad fehlt noch:

- `MysqlDdlGenerator.generateSequences(...)` erzeugt weiterhin nur
  `ACTION_REQUIRED`/`E056`
- `TypeMapper.toDefaultSql(...)` ist fuer normale Defaults gedacht,
  nicht fuer triggerbasierte Sequence-Emulation; der 6.3-`error()`-Guard
  in `MysqlTypeMapper` ist ein reines Sicherheitsnetz
- die Warnings `W114`, `W115`, `W117` sind im Ledger `reserved`,
  aber noch nicht emittiert

### 2.3 Architektonische Restriktionen im bestehenden Generator

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

**C. `SequenceNextVal`-Spalten muessen vor `toDefaultSql()` abgefangen werden:**

Der Masterplan (`implementation-plan-0.9.3.md`, Zeile 693-705)
spezifiziert den Interception-Punkt **in `AbstractDdlGenerator.columnSql()`**
ueber eine neue ueberschreibbare Methode `resolveSequenceDefault()`.
Damit sieht `TypeMapper.toDefaultSql()` den Fall **nie** — fuer keinen
Dialekt (siehe §4.6).

---

## 3. Scope fuer 6.4

### 3.1 In Scope

- `AbstractDdlGenerator.columnSql()`: zentraler
  `resolveSequenceDefault()`-Hook (Masterplan §5.4, Zeile 693)
- `MysqlDdlGenerator`-Erweiterung: `generate()` Override mit
  Options-Persistierung und Support-Objekt-Injection
- generatorinterne Support-Helfer fuer:
  - `dmg_sequences`
  - Seed-Statements
  - `dmg_nextval`
  - `dmg_setval`
  - Trigger-Namensbildung (`MysqlSequenceNaming` Utility)
  - generierte `BEFORE INSERT`-Trigger
- MySQL-spezifische Behandlung von `SequenceNextVal` in beiden Modi
- `W114`, `W115`, `W117` an den vorgesehenen Generatorstellen
- **E124**: Support-Namenskollision als dedizierter Error-Code
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
- Namenskollisionen fuehren zu einem strukturierten Fehler (siehe
  §4.10)

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
     `resolveSequenceDefault()` gelesen

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

### 4.6 `SequenceNextVal`-Interception zentral in `AbstractDdlGenerator.columnSql()`

Der Masterplan (`implementation-plan-0.9.3.md`, Zeile 693-705)
spezifiziert den Interception-Punkt zentral in
`AbstractDdlGenerator.columnSql()`, damit `TypeMapper.toDefaultSql()`
den Fall `SequenceNextVal` **fuer keinen Dialekt** zu sehen bekommt.

Verbindliche Loesung:

`AbstractDdlGenerator.columnSql()` (Zeile 171-179) wird erweitert:

```kotlin
protected fun columnSql(
    tableName: String, colName: String, col: ColumnDefinition, schema: SchemaDefinition
): String {
    val parts = mutableListOf<String>()
    parts += quoteIdentifier(colName)
    parts += typeMapper.toSql(col.type)
    if (col.required) parts += "NOT NULL"
    if (col.default != null) {
        val seqDefault = col.default as? DefaultValue.SequenceNextVal
        if (seqDefault != null) {
            val resolved = resolveSequenceDefault(tableName, colName, col, seqDefault)
            if (resolved != null) parts += resolved
        } else {
            parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, col.type)}"
        }
    }
    if (col.unique) parts += "UNIQUE"
    return parts.joinToString(" ")
}

/** Dialect-specific resolution of SequenceNextVal defaults.
 *  Returns a complete DEFAULT clause or null (= suppress DEFAULT).
 *  Side effects (warnings, trigger metadata) are managed via
 *  dialect-specific mutable state, not via return value. */
protected open fun resolveSequenceDefault(
    tableName: String,
    colName: String,
    col: ColumnDefinition,
    seqDefault: DefaultValue.SequenceNextVal,
): String? = null  // Base: suppress DEFAULT
```

Signaturaenderung `columnSql()`:

- `columnSql()` erhaelt einen neuen ersten Parameter `tableName: String`
- alle Aufrufer von `columnSql()` muessen den Tabellennamen
  durchreichen:
  - `AbstractDdlGenerator.generate()` → `generateTable()` kennt den
    Tabellennamen bereits
  - `MysqlColumnConstraintHelper` erhaelt den Tabellennamen als
    zusaetzlichen Parameter im Konstruktor-Callback oder pro Aufruf

Dialekt-Overrides:

- `PostgresDdlGenerator.resolveSequenceDefault(...)`:
  - gibt `"DEFAULT nextval('${seqDefault.sequenceName}')"` zurueck
  - keine Side-Effects
- `MysqlDdlGenerator.resolveSequenceDefault(...)`:
  - `HELPER_TABLE`: gibt `null` zurueck (kein DEFAULT, Trigger
    uebernimmt), sammelt Trigger-Metadaten in
    `pendingSupportTriggers` (§4.7), sammelt `W115` in
    `pendingSequenceNotes` (§4.7)
  - `ACTION_REQUIRED`: gibt `null` zurueck, sammelt
    `ACTION_REQUIRED`-Diagnose (E056) in `pendingSequenceNotes`
- `SqliteDdlGenerator` erbt die Base-Implementierung (`null`)

Warning-Emission:

Da `resolveSequenceDefault()` nur `String?` zurueckgibt, werden
Warnings und Notes ueber Mutable State auf `MysqlDdlGenerator`
gesammelt:

```kotlin
private val pendingSequenceNotes = mutableListOf<TransformationNote>()
```

- `resolveSequenceDefault()` fuegt W115 bzw. E056-Notes zur Liste hinzu
- die Notes werden nach der Spaltenverarbeitung an das
  `DdlStatement` der Tabelle uebergeben (siehe §4.7)

Schutz gegen `MysqlColumnConstraintHelper`-Spezialpfade:

`MysqlColumnConstraintHelper.generateColumnSql()` (Zeile 22-28) hat
Spezialpfade fuer `autoIncrement`, `Enum`/`Domain` und `Geometry`,
die `toDefaultSql()` direkt aufrufen (Zeile 32, 59, 67) und damit
den `resolveSequenceDefault()`-Hook umgehen. Der `error()`-Guard in
`MysqlTypeMapper` wuerde in diesen Pfaden fuer `SequenceNextVal`
feuern.

Verbindliche Absicherung: Die Kombination `autoIncrement +
SequenceNextVal` ist semantisch widerspruechlich (MySQL's
`AUTO_INCREMENT` ist selbst ein Sequence-Mechanismus). **6.3 muss
diese Kombination als Validierungsfehler ablehnen** — entweder ueber
eine Erweiterung von `isDefaultCompatible` (SequenceNextVal ist
nicht kompatibel mit `Identifier(autoIncrement=true)`) oder ueber
eine dedizierte Pruefung in `validateSequenceDefaultReference`.
Die uebrigen Spezialpfade (`Enum`, `Domain`, `Geometry`) sind bereits
durch den bestehenden Validator abgesichert, da `SequenceNextVal`
nur mit numerischen/identifier-aehnlichen Typen kompatibel ist (6.3
§5.2).

Falls 6.3 diese Validierung nicht enthaelt, muss sie in 6.4
nachgezogen werden.

Konsequenz fuer 6.3-Artefakte:

- die `error()`-Guards in `MysqlTypeMapper.toDefaultSql()` und
  `SqliteTypeMapper.toDefaultSql()` aus 6.3 werden nie erreicht,
  bleiben aber als Sicherheitsnetz bestehen
- der `SequenceNextVal`-Zweig in `PostgresTypeMapper.toDefaultSql()`
  aus 6.3 wird ebenfalls nie erreicht (die Aufloesung passiert in
  `resolveSequenceDefault()`); er bleibt als Defense-in-Depth

Hinweis: Falls 6.3 den Masterplan-Hook in `columnSql()` bereits
implementiert hat, entfaellt dieser Schritt in 6.4. Falls 6.3 statt
des Hooks nur TypeMapper-Zweige eingefuehrt hat, muss 6.4 den Hook
nachziehen.

### 4.7 Mutable State fuer Trigger-Metadaten und Notes

Waehrend `generateTable()` werden `SequenceNextVal`-Spalten erkannt
(§4.6, in `resolveSequenceDefault()`). Die daraus resultierenden
Trigger muessen aber erst spaeter in `generateTriggers()` (POST_DATA)
erzeugt werden. Gleichzeitig fallen Warnings (W115) und Diagnosen
(E056) an, die `resolveSequenceDefault()` nicht direkt zurueckgeben
kann (die Methode gibt nur `String?` zurueck).

Verbindliche Loesung — drei mutable Felder auf `MysqlDdlGenerator`:

```kotlin
private val pendingSupportTriggers = mutableListOf<SupportTriggerSpec>()
private val pendingSequenceNotes = mutableListOf<TransformationNote>()
```

- `SupportTriggerSpec` ist eine interne Datenklasse:

```kotlin
private data class SupportTriggerSpec(
    val tableName: String,
    val columnName: String,
    val sequenceName: String,
)
```

Befuellung und Konsumierung:

- `resolveSequenceDefault()` in `MysqlDdlGenerator`:
  - `HELPER_TABLE`: fuegt `SupportTriggerSpec` zu
    `pendingSupportTriggers` hinzu und `W115`-Note zu
    `pendingSequenceNotes`
  - `ACTION_REQUIRED`: fuegt E056-Note zu `pendingSequenceNotes`
- `MysqlDdlGenerator` ueberschreibt `generateTable()` als
  Wrapper, der nach `super.generateTable()` die gesammelten
  `pendingSequenceNotes` an die Notes des letzten
  Table-Statements anhaengt und die Liste danach leert
- `generateTriggers()` liest `pendingSupportTriggers` und erzeugt
  die Support-Trigger
- `generate()` leert beide Listen zu Beginn jedes Laufs

### 4.8 Rollback nutzt den bestehenden `invertStatement()`-Mechanismus

Der bestehende `MysqlDdlGenerator.invertStatement()` (Zeile 292-316)
behandelt DELIMITER-verpackte `CREATE FUNCTION`, `CREATE PROCEDURE`
und `CREATE TRIGGER` bereits korrekt und erzeugt die zugehoerigen
`DROP ... IF EXISTS`-Statements. Fuer `CREATE TABLE` delegiert
`invertStatement()` an `super.invertStatement()`, das den
`StatementInverter` nutzt.

Da die Support-Objekte (`dmg_nextval`, `dmg_setval`,
Support-Trigger) im selben DELIMITER-Format erzeugt werden wie
User-Routinen und User-Trigger, greift `invertStatement()` fuer
sie automatisch.

`AbstractDdlGenerator.generateRollback()` (Zeile 84-88) kehrt die
Statement-Reihenfolge um und invertiert jedes Statement. Daraus
ergibt sich die korrekte Drop-Reihenfolge natuerlich:

- Forward: `dmg_sequences` (PRE_DATA) → Tabellen → `dmg_nextval`/
  `dmg_setval` (POST_DATA) → Support-Trigger (POST_DATA) →
  User-Trigger (POST_DATA)
- Reversed + invertiert: User-Trigger-Drops → Support-Trigger-Drops →
  Routine-Drops → Tabellen-Drops → `DROP TABLE dmg_sequences`

Verbindliche Folge:

- `MysqlDdlGenerator` ueberschreibt `generateRollback()` **nicht**
- der bestehende Mechanismus wird wiederverwendet
- die Support-DDL muss dem bestehenden DELIMITER-Format von
  `MysqlRoutineDdlHelper` folgen, damit `invertStatement()` greift
- Tests verifizieren die korrekte Drop-Reihenfolge explizit

### 4.9 Warning-Semantik wird an reale Erzeugungspunkte gebunden

Verbindliche Folge:

- `W114` bei Sequence-Definitionen mit gesetztem `cache`
  (in `generateSequences()`)
- `W115` pro betroffener Spalte mit `SequenceNextVal`
  (in `resolveSequenceDefault()`)
- `W117` einmal pro DDL-Lauf im `helper_table`-Modus
  (in `generate()`, nach `super.generate()`)
- `W116` bleibt in 6.4 weiterhin `reserved` im Ledger; er wird erst
  emittiert, wenn die Semantik in einem spaeteren Arbeitspaket
  implementiert wird

Ledger-Hochstufung:

- W114, W115, W117 werden in `ledger/warn-code-ledger-0.9.3.yaml`
  von `status: reserved` auf `status: active` hochgestuft
- `test_path` und `evidence_paths` werden ergaenzt
- Voraussetzung: 6.2 hat die 0.9.3-Ledgerdateien und das erweiterte
  JSON-Schema (mit `reserved`-Status) bereits angelegt. Falls nicht,
  muss 6.4 diese Dateien selbst anlegen (siehe §6.8).

### 4.10 Support-Namenskollisionen bekommen E124

Wenn das neutrale Schema bereits ein Objekt mit einem reservierten
Support-Namen enthaelt (`dmg_sequences`, `dmg_nextval`, `dmg_setval`,
oder einen Trigger nach dem kanonischen `dmg_seq_*`-Schema), darf
`helper_table` **nicht** still generieren.

Verbindliche Folge:

- **E124**: "Support object name collision: '<name>' already exists
  in the neutral schema. Rename the existing object or use
  `--mysql-named-sequences action_required`."
- der Fehler wird vor der Generierung der Support-Objekte geprueft
  (in `generateSequences()` fuer Tabellen/Funktionen, in
  `generateTriggers()` fuer Trigger)
- der Fehler fuehrt zu einem `ACTION_REQUIRED`-Eintrag im Output,
  nicht zu einem harten Abbruch — andere Tabellen/Sequences werden
  weiterhin erzeugt
- E124 wird im 0.9.3-Error-Ledger eingetragen (`status: active`)

Begruendung:

- das Diagnostic-Modell des Repos ist code-getrieben
  (`ManualActionRequired` mit Code, Typ, Name, Reason, Hint)
- der Masterplan (`mysql-sequence-emulation-plan.md`, Zeile 205-215)
  verlangt explizit einen eigenen Code fuer diesen Konfliktpfad

---

## 5. Zielarchitektur

### 5.1 Generator-Lifecycle fuer `helper_table`

Der Ablauf von `MysqlDdlGenerator.generate()` im
`HELPER_TABLE`-Modus:

```
generate(schema, options)
  ├── currentOptions = options                        (§4.4)
  ├── pendingSupportTriggers.clear()                  (§4.7)
  ├── pendingSequenceNotes.clear()                    (§4.7)
  ├── super.generate(schema, options)
  │     ├── generateHeader()
  │     ├── generateCustomTypes()
  │     ├── generateSequences()                       → PRE_DATA
  │     │     └── HELPER_TABLE: dmg_sequences + Seed + W114
  │     ├── for table in sorted:
  │     │     └── generateTable()  [MySQL-Override]
  │     │           ├── super.generateTable()
  │     │           │     └── columnSql(tableName, ...)
  │     │           │           └── SequenceNextVal? → resolveSequenceDefault()
  │     │           │                 ├── return null (kein DEFAULT)
  │     │           │                 ├── pendingSupportTriggers += Spec
  │     │           │                 └── pendingSequenceNotes += W115
  │     │           └── pendingSequenceNotes → an Table-Statement anhaengen + leeren
  │     ├── generateFunctions()                       → POST_DATA
  │     │     ├── dmg_nextval (Support)
  │     │     ├── dmg_setval (Support)
  │     │     └── User-Funktionen
  │     ├── generateTriggers()                        → POST_DATA
  │     │     ├── Support-Trigger (aus pendingSupportTriggers)
  │     │     └── User-Trigger
  │     └── return DdlResult
  ├── W117 an DdlResult anhaengen                    (§4.9)
  └── return DdlResult
```

### 5.2 DDL-Vertrag pro Dialekt im 6.4-Kontext

PostgreSQL:

- bleibt fachlich auf nativer Sequence-Unterstuetzung
- `resolveSequenceDefault()` gibt
  `"DEFAULT nextval('<seq>')"` zurueck
- ist in 6.4 nur Abgrenzung, nicht Implementierungsziel

MySQL `action_required`:

- `SequenceDefinition` bleibt `E056`
- `resolveSequenceDefault()` gibt `null` zurueck und emittiert
  `ACTION_REQUIRED`-Diagnose (E056) mit `helper_table`-Hinweis
- kein Aufruf von `toDefaultSql()` fuer `SequenceNextVal`

MySQL `helper_table`:

- `SequenceDefinition` → Zeile in `dmg_sequences`
- `resolveSequenceDefault()` gibt `null` zurueck, sammelt
  Trigger-Metadaten
- `SequenceNextVal(seq)` → kanonischer `BEFORE INSERT`-Trigger
- `cache` bleibt Metadatum in `dmg_sequences`, ohne echte
  Preallocation

SQLite:

- bleibt in 0.9.3 ausserhalb des produktiven Generatorpfads
- erbt die Base-Implementierung von `resolveSequenceDefault()` (`null`)

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

Der bestehende Rollback-Mechanismus wird wiederverwendet (§4.8):

- `generateRollback()` in `AbstractDdlGenerator` kehrt die Statements
  um und ruft `invertStatement()` pro Statement auf
- `MysqlDdlGenerator.invertStatement()` (Zeile 292-316) behandelt
  DELIMITER-verpackte `CREATE FUNCTION/PROCEDURE/TRIGGER` bereits
  korrekt
- `StatementInverter` (via `super.invertStatement()`) behandelt
  `CREATE TABLE` korrekt
- die natuerliche Umkehr-Reihenfolge ergibt den korrekten Drop-Pfad

Pflicht-Akzeptanzkriterien:

- feste Drop-Reihenfolge Trigger → Routinen → `dmg_sequences`
  (ergibt sich aus der Umkehr der Erzeugungsreihenfolge)
- alle Drops sind idempotent (`IF EXISTS`)
- kompletter Up→Down-Lauf scheitert nicht an Supportobjekt-Reihenfolge

Voraussetzung: Support-DDL muss dem bestehenden DELIMITER-Format
von `MysqlRoutineDdlHelper` folgen.

---

## 6. Konkrete Arbeitsschritte

### 6.1 `resolveSequenceDefault()`-Hook in `AbstractDdlGenerator`

- `columnSql()` Signatur um `tableName: String` als ersten
  Parameter erweitern
- alle Aufrufer von `columnSql()` anpassen (inkl.
  `MysqlColumnConstraintHelper`-Callback-Signatur)
- vor `toDefaultSql()` pruefen, ob der Default ein
  `SequenceNextVal` ist; falls ja, an `resolveSequenceDefault()`
  delegieren
- neue `protected open fun resolveSequenceDefault(tableName,
  colName, col, seqDefault)` mit Base-Implementierung `null`
- `PostgresDdlGenerator`: Override mit nativem
  `"DEFAULT nextval('...')"`
- `MysqlDdlGenerator`: Override mit Modus-Verzweigung (§4.6)
- `SqliteDdlGenerator`: erbt Base-Implementierung

### 6.2 `MysqlSequenceNaming` Utility anlegen

- `MysqlSequenceNaming` als internes Objekt im MySQL-Adapter-Modul
- `normalize(name): String`
- `hash10(tableNorm, columnNorm): String`
- `triggerName(tableName, columnName): String`
- Unit-Tests in `MysqlSequenceNamingTest`

### 6.3 Options-Persistierung und Mutable State einfuehren

- `MysqlDdlGenerator`: privates Feld `currentOptions`
- `MysqlDdlGenerator`: private Listen `pendingSupportTriggers` und
  `pendingSequenceNotes`
- `SupportTriggerSpec` Datenklasse
- `generate()`-Override: Options speichern, beide Listen leeren,
  `super.generate()` aufrufen, W117 anhaengen
- `generateTable()`-Override als Wrapper: `super.generateTable()`
  aufrufen, `pendingSequenceNotes` an das Table-Statement
  anhaengen, `pendingSequenceNotes` leeren

### 6.4 `generateSequences()` Modusumschaltung

- `ACTION_REQUIRED`: bisheriges E056-Verhalten
- `HELPER_TABLE`:
  - `CREATE TABLE dmg_sequences` mit Spalten fuer Name, Startwert,
    Inkrement, Cache, aktuellen Wert
  - `INSERT INTO dmg_sequences` pro Sequence (Seed-Statements)
  - `W114` fuer Sequences mit gesetztem `cache`
  - E124-Pruefung fuer Namenskollision `dmg_sequences`

### 6.5 Support-Routinen und -Trigger erzeugen

- `generateFunctions()`-Override:
  - `HELPER_TABLE`: `dmg_nextval` + `dmg_setval` emittieren
    (DELIMITER-Format von `MysqlRoutineDdlHelper`), dann
    `MysqlRoutineDdlHelper` fuer User-Funktionen
  - `ACTION_REQUIRED`: direkt an `MysqlRoutineDdlHelper`
  - E124-Pruefung fuer Namenskollision `dmg_nextval`/`dmg_setval`
- `generateTriggers()`-Override:
  - `HELPER_TABLE`: Support-Trigger aus `pendingSupportTriggers`
    erzeugen (DELIMITER-Format), dann `MysqlRoutineDdlHelper` fuer
    User-Trigger
  - `ACTION_REQUIRED`: direkt an `MysqlRoutineDdlHelper`
  - E124-Pruefung fuer Namenskollisionen bei Triggernamen

### 6.6 Warning- und Konfliktfaelle verankern

- `W114`, `W115`, `W117` an den in §4.9 festgelegten Stellen
  emittieren
- E124-Pruefungen an den in §4.10 und §6.4/§6.5 festgelegten Stellen

### 6.7 Ledger-Arbeit

- W114, W115, W117 in `ledger/warn-code-ledger-0.9.3.yaml` von
  `status: reserved` auf `status: active` aendern
- `test_path` und `evidence_paths` ergaenzen
- W116 bleibt `reserved`
- E124 in `ledger/error-code-ledger-0.9.3.yaml` eintragen mit
  `status: active`, `test_path` und `evidence_paths`
- falls die 0.9.3-Ledgerdateien oder das erweiterte JSON-Schema
  (mit `reserved`-Status) aus 6.2 noch nicht existieren, muessen
  sie in diesem Schritt angelegt werden (siehe 6.2 Plan §6.4 fuer
  die vollstaendige Spezifikation)

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
  - `toDefaultSql()` wird fuer `SequenceNextVal` nie aufgerufen
  - Warning-Semantik `W114` / `W115` / `W117`
  - E124 bei Schema mit bereits vorhandenem `dmg_sequences`
  - Trigger-Namensbildung ist deterministisch
  - Phasen: `dmg_sequences` in PRE_DATA, Routinen/Trigger in
    POST_DATA
  - Rollback: invertierte Reihenfolge ergibt korrekten Drop-Pfad
    (Trigger-Drops vor Routine-Drops vor `DROP TABLE dmg_sequences`)
- Golden Masters:
  - MySQL `action_required` (mit SequenceNextVal-Spalte)
  - MySQL `helper_table`
  - Split `pre-data` / `post-data`
  - Rollback `helper_table`
- `CodeLedgerValidationTest`:
  - W114, W115, W117 sind `active` mit gueltigem `test_path`
  - E124 ist `active` mit gueltigem `test_path`

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
  mit Migrationshinweis
- `TypeMapper.toDefaultSql()` wird fuer `SequenceNextVal` bei keinem
  Dialekt erreicht (zentraler Hook in `columnSql()`)
- Supportobjekte und Trigger sind deterministisch benannt
- `PRE_DATA` / `POST_DATA` sind fuer Supportobjekte korrekt getrennt
- Rollback nutzt den bestehenden `invertStatement()`-Mechanismus und
  ergibt die korrekte Drop-Reihenfolge
- E124 feuert bei Namenskollisionen
- die vorgesehenen Warnings werden konsistent erzeugt
- W114, W115, W117 sind im Ledger `active` mit Tests
- E124 ist im Ledger `active` mit Tests

---

## 8. Betroffene Codebasis

Voraussichtlich direkt betroffen (Produktionscode):

- `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/AbstractDdlGenerator.kt`
  — `columnSql()` Signatur um `tableName`, neue
  `resolveSequenceDefault()` Methode
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt`
  — `generate()`-Override, `generateTable()`-Override (Notes-Wrapper),
  `generateFunctions()`-Override, `generateTriggers()`-Override,
  `resolveSequenceDefault()`-Override, `currentOptions`,
  `pendingSupportTriggers`, `pendingSequenceNotes`,
  `SupportTriggerSpec`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlColumnConstraintHelper.kt`
  — `columnSql`-Callback-Signatur um `tableName` erweitern (keine
  neue fachliche Logik, nur Signaturanpassung)
- `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGenerator.kt`
  — `resolveSequenceDefault()`-Override (nativer Pfad)
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
- `ledger/error-code-ledger-0.9.3.yaml` — E124

Voraussichtlich testseitig betroffen:

- `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceNamingTest.kt`
  — neuer Test fuer Naming-Utility
- `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGeneratorTest.kt`
  — umfangreiche Erweiterung fuer beide Modi
- `adapters/driven/driver-common/src/test/kotlin/dev/dmigrate/driver/AbstractDdlGeneratorTest.kt`
  — `resolveSequenceDefault()`-Verhalten im Base-Fall
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/yaml/DdlGoldenMasterTest.kt`
  — neue Golden Masters
- `test/integration-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceEmulationIntegrationTest.kt`
  — neuer Integrationstest
- `hexagon/core/src/test/kotlin/dev/dmigrate/core/validation/CodeLedgerValidationTest.kt`
  — W114/W115/W117 sind jetzt `active`, E124 ist `active`

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
Der Status `reserved` bleibt bestehen, bis ein spaeteres
Arbeitspaket die zugehoerige Semantik implementiert.

### 9.4 Konsistenz mit 6.3 bei `resolveSequenceDefault()`

Falls 6.3 den `resolveSequenceDefault()`-Hook bereits in
`AbstractDdlGenerator.columnSql()` implementiert hat (wie vom
Masterplan vorgesehen), entfaellt Schritt 6.1 in 6.4. Falls 6.3
stattdessen nur TypeMapper-Zweige eingefuehrt hat, muss 6.4 den
Hook nachziehen und die TypeMapper-Zweige werden zu reinem
Defense-in-Depth. In beiden Faellen ist das Endergebnis identisch.
