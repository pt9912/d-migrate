# Implementierungsplan: Milestone 0.9.3 - Beta: Filter-Haertung und MySQL-Sequence-Emulation (Generator)

> Dieses Dokument beschreibt den konkreten Implementierungsplan fuer
> Milestone 0.9.3. Es fokussiert zwei bewusst gekoppelte Arbeiten:
> die sichtbare Haertung des rohen SQL-Filterpfads im Datenexport und
> die generatorseitige, opt-in MySQL-Emulation benannter Sequences.
>
> Status: Draft (2026-04-20)
> Referenzen: `docs/roadmap.md` Abschnitt "Milestone 0.9.3",
> `docs/quality.md`,
> `docs/mysql-sequence-emulation-plan.md`,
> `docs/ddl-generation-rules.md`,
> `docs/neutral-model-spec.md`,
> `docs/cli-spec.md`,
> `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/DefaultValue.kt`,
> `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/SequenceDefinition.kt`,
> `hexagon/core/src/main/kotlin/dev/dmigrate/core/validation/SchemaValidator.kt`,
> `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/DdlGenerationOptions.kt`,
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt`,
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunner.kt`,
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataExportCommand.kt`,
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportHelpers.kt`,
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateCommand.kt`,
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateHelpers.kt`,
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareHelpers.kt`,
> `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SchemaNodeParser.kt`,
> `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SchemaNodeBuilder.kt`,
> `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/report/TransformationReportWriter.kt`,
> `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/AbstractDdlGenerator.kt`,
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt`,
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlRoutineDdlHelper.kt`,
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlTypeMapper.kt`,
> `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMapper.kt`,
> `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTypeMapper.kt`.

---

## 1. Ziel

Milestone 0.9.3 schliesst zwei verbleibende Beta-Luecken, die vor einem
stabilen 1.0-Vertrag nicht offen bleiben sollten:

- der heutige rohe SQL-Exportfilter wird sichtbar als unsicher markiert
- MySQL kann benannte Sequences im DDL-Generator optional ueber
  kanonische Hilfsobjekte emulieren, statt sie immer nur mit `E056`
  zu ueberspringen

Der Milestone liefert damit drei konkrete Nutzerergebnisse:

- `data export` hat einen ehrlicheren, klarer benannten CLI-Vertrag
  fuer Trusted-SQL-Eingaben
- `schema generate --target mysql` bekommt einen opt-in
  `helper_table`-Pfad fuer benannte Sequences
- das neutrale Modell kann Sequence-Nutzung an Spaltendefaults explizit
  ausdruecken, statt dies implizit in generischen Funktionsstrings zu
  verstecken

Bewusst noch nicht Teil dieses Milestones:

- Reverse-Engineering der MySQL-Emulation
- Compare-/Diff-Normalisierung gegen die Hilfsobjekte
- eine eigentliche Filter-DSL

Diese Themen folgen laut Roadmap in 0.9.4 oder spaeter.

---

## 2. Ausgangslage

### 2.1 Filter-Pfad

Stand im aktuellen Repo:

- `data export` bietet heute nur `--filter`
- `DataExportHelpers.resolveFilter(...)` behandelt den Wert bewusst als
  rohes SQL-Fragment und interpoliert ihn unveraendert in `WHERE ...`
- die Trust-Boundary ist zwar dokumentiert, aber nur in Help-Text,
  Kommentaren und Plan-/Quality-Dokumenten sichtbar
- Tests decken die Pass-through-Semantik breit ab
  (`CliDataExportTest`, `DataExportRunnerTest`, `DataExportE2EMysqlTest`)

Konsequenz:

- technisch ist das Verhalten bekannt und absichtlich
- produktseitig ist der Vertrag aber zu weich formuliert; fuer neue
  Nutzer wirkt `--filter` wie ein normaler, sicherer Query-Parameter

### 2.2 MySQL-Sequences

Stand im aktuellen Repo:

- `MysqlDdlGenerator.generateSequences(...)` erzeugt nur `E056`
- `DdlGenerationOptions` kennt noch keine MySQL-Sequence-Option
- `DefaultValue` kennt noch keinen expliziten Sequence-Default-Typ
- `AbstractDdlGenerator.columnSql(...)` delegiert pauschal an
  `TypeMapper.toDefaultSql(...)`
- Parser, Builder, Validator und Compare-Helfer kennen nur:
  - `StringLiteral`
  - `NumberLiteral`
  - `BooleanLiteral`
  - `FunctionCall`

Konsequenz:

- benannte Sequences koennen zwar fachlich im neutralen Schema
  definiert werden
- die Nutzung dieser Sequences durch Spalten ist aber nicht als
  neutraler Vertrag modelliert
- MySQL hat deshalb heute keinen sinnvollen Generatorpfad ausser
  `ACTION_REQUIRED`

### 2.3 Vorarbeit ist bereits vorhanden

Die grobe Produktarchitektur liegt bereits in
`docs/mysql-sequence-emulation-plan.md` vor. Fuer 0.9.3 muss davon
bewusst nur der Generator-Teil umgesetzt werden:

- Phase A: Vertrag schaerfen
- Phase B: Generator und CLI-Option
- Phase C: Tests und Golden Masters

Reverse und Compare bleiben explizit fuer 0.9.4 offen.

---

## 3. Scope

### 3.1 In Scope

- `data export` fuehrt `--unsafe-filter` als kanonischen Namen ein
- `--filter` bleibt in 0.9.3 als deprecated Alias bestehen
- Help-Texte, CLI-Spec und Fehlermeldungen machen den Trusted-SQL-
  Vertrag explizit
- `DefaultValue.SequenceNextVal(sequenceName: String)` wird im
  neutralen Modell eingefuehrt
- YAML-/JSON-Schema-Codecs koennen den neuen Default-Typ lesen und
  schreiben
- `SchemaValidator` validiert Sequence-Defaults gegen existierende
  `schema.sequences`
- `schema generate` bekommt
  `--mysql-named-sequences action_required|helper_table`
- `DdlGenerationOptions` bekommt einen getypten MySQL-Sequence-Modus
- `MysqlDdlGenerator` erzeugt im `helper_table`-Modus:
  - `dmg_sequences`
  - Seed-Statements fuer definierte Sequences
  - `dmg_nextval(...)`
  - `dmg_setval(...)`
  - kanonische `BEFORE INSERT`-Trigger fuer
    `DefaultValue.SequenceNextVal(...)`
- Warning-Codes `W114` bis `W117` werden fuer den neuen Vertrag
  dokumentiert; generatorseitig in 0.9.3 werden davon `W114`, `W115`
  und `W117` emittiert, `W116` bleibt fuer 0.9.4 reserviert
- Unit-Tests, Golden Masters und MySQL-Integrationstests fuer beide
  Modi (`action_required`, `helper_table`)

### 3.2 Bewusst nicht Teil von 0.9.3

- eine echte Filter-DSL
- Entfernung des Legacy-Alias `--filter`
- Reverse von `dmg_sequences`, Routinen oder Support-Triggern
- Compare-/Diff-Normalisierung der MySQL-Hilfsobjekte
- automatische Migration vorhandener, handgeschriebener
  Sequence-Loesungen
- SQLite-Sequence-Emulation
- Tool-Export-spezifische neue Oberflaechenoptionen jenseits des
  bestehenden `schema generate`-Pfads

---

## 4. Leitentscheidungen

### 4.1 `--unsafe-filter` wird eingefuehrt, `--filter` bleibt nur noch als Alt-Alias

Verbindliche Entscheidung:

- der sichtbare, dokumentierte Flag-Name ist ab 0.9.3
  `--unsafe-filter`
- `--filter` bleibt fuer 0.9.x als deprecated Alias erhalten
- beide Flags duerfen nicht gemeinsam gesetzt werden
- die CLI implementiert dafuer **zwei getrennte Optionen**, nicht nur
  einen Clikt-Alias auf denselben Zielwert
- die Mutual-Exclusion-Pruefung laeuft in `DataExportCommand`, bevor ein
  `DataExportRequest` gebaut oder ein Resume-/Fingerprint-Pfad
  vorbereitet wird
- wenn der Legacy-Alias verwendet wird, wird eine klare
  stderr-Warnung ausgegeben
- neue Dokumentation, Beispiele und Tests nutzen nur noch
  `--unsafe-filter`

Begruendung:

- das Milestone-Ziel ist Sichtbarmachung der Unsicherheit, nicht ein
  halbgares Re-Design des Filtermodells
- die Alias-Phase vermeidet unnoetige Script-Breaks mitten in der
  Beta

### 4.2 Resume-/Fingerprint-Semantik bleibt fachlich stabil

Verbindliche Entscheidung:

- der Umstieg von `--filter` auf `--unsafe-filter` aendert die
  Filtersemantik nicht
- `ExportOptionsFingerprint` bleibt fuer denselben effektiven SQL-Text
  bytegleich
- bestehende Checkpoints bleiben dadurch wiederaufnehmbar, solange sich
  der eigentliche Filterinhalt nicht aendert

### 4.3 Der neue neutrale Default-Vertrag ist explizit und nicht pattern-basiert

Verbindliche Entscheidung:

- `DefaultValue` wird um `SequenceNextVal(sequenceName: String)`
  erweitert
- die kanonische Schema-Schreibweise in YAML/JSON ist ein explizites
  Objekt:

```yaml
default:
  sequence_nextval: invoice_number_seq
```

- freie String-Literale bleiben unveraendert String-Literale; ein
  Literal wie `"nextval(invoice_number_seq)"` darf **nicht** implizit
  als `SequenceNextVal` umgedeutet werden
- generische `FunctionCall("nextval(...)")`-Varianten sind **nicht**
  der kanonische Generatorvertrag fuer 0.9.3
- bestehende oder programmgesteuert erzeugte Defaults in der Form
  `FunctionCall("nextval(...)")` bekommen **keinen** stillen Compat-
  Shim; sie werden mit klarer Validierungs-/Generate-Fehlermeldung auf
  die neue Objektform `default.sequence_nextval` verwiesen

Begruendung:

- ein expliziter Subtyp ist fuer Generator, Validator und spaeteren
  Reverse/Compare klarer als das Verstecken in einem freien String
- eine explizite Objektform vermeidet Mehrdeutigkeiten mit bestehenden
  `StringLiteral`-Defaults und macht Round-Trips ueber die Codecs
  verlustfrei

### 4.4 `--mysql-named-sequences` ist ein expliziter MySQL-spezifischer Opt-in

Verbindliche Entscheidung:

- neuer CLI-Vertrag:
  - `--mysql-named-sequences action_required`
  - `--mysql-named-sequences helper_table`
- Default ist `action_required`
- die Option ist nur zusammen mit `--target mysql` zulaessig
- eine explizite Nutzung bei PostgreSQL oder SQLite endet mit Exit 2

Begruendung:

- stilles Ignorieren einer explizit gesetzten Dialektoption ist fuer
  Automatisierungen zu fehlerfreundlich
- der Default bleibt konservativ, bis Reverse und Compare in 0.9.4
  nachgezogen sind

### 4.5 MySQL-Helper-Objekte bleiben kanonisch und nicht konfigurierbar

Verbindliche Entscheidung:

- 0.9.3 nutzt feste Support-Namen:
  - `dmg_sequences`
  - `dmg_nextval`
  - `dmg_setval`
  - `dmg_seq_<table16>_<column16>_<hash10>_bi`
- keine benutzerkonfigurierbaren Prefixe in diesem Milestone
- Namenskollisionen fuehren nicht zu stiller Umbenennung
- der Generator faellt in Konfliktfaellen auf strukturierte
  `ACTION_REQUIRED`-/`E056`-Diagnose statt auf "best effort" zurueck

Begruendung:

- nur ein kanonischer Namespace ist spaeter robust reverse-bar
- konfigurierbare Namen wuerden 0.9.4 unnoetig verkomplizieren

### 4.6 Supportobjekte respektieren den 0.9.2-Phasenvertrag

Verbindliche Entscheidung:

- `dmg_sequences` und zugehoerige Seed-Statements liegen in
  `PRE_DATA`
- Support-Routinen `dmg_nextval` / `dmg_setval` liegen in `POST_DATA`
- generierte Sequence-Support-Trigger liegen in `POST_DATA`
- nutzerdefinierte Trigger bleiben unveraendert nach den Support-
  Triggern im `POST_DATA`-Block

Begruendung:

- die Hilfstabelle ist strukturelle Vorbedingung fuer Routinen und
  Trigger
- importfreundliche `pre-post`-Artefakte bleiben konsistent mit dem
  bestehenden 0.9.2-Vertrag

### 4.7 Warning-Semantik wird jetzt festgezogen, aber nicht ueberladen

Verbindliche Entscheidung:

- `W114`: pro Sequence mit gesetztem `cache`, weil der Wert gespeichert,
  aber nicht als echte Preallocation emuliert wird
- `W115`: pro betroffener Spalte mit `SequenceNextVal`, weil MySQL im
  Triggerpfad explizites `NULL` nicht von "Wert ausgelassen" trennt
- `W117`: einmal pro DDL-Lauf im `helper_table`-Modus als globaler
  Hinweis auf transaktionsgebundene Sequence-Inkremente
- `W116` wird in 0.9.3 nur dokumentiert und im Ledger reserviert; die
  Emission folgt mit Reverse in 0.9.4

---

## 5. Zielarchitektur

### 5.1 Filter-Haertung ohne Funktionsumbau des Streaming-Kerns

Der Datenexport behaelt seinen technischen Kern:

- `DataFilter.WhereClause` bleibt der rohe SQL-Pfad
- `DataExportHelpers.resolveFilter(...)` baut weiter denselben
  Filterbaum
- `AbstractJdbcDataReader` behaelt die bestehende `WHERE`-Integration

Geaendert wird die sichtbare API-Schicht:

- `DataExportCommand` definiert getrennte Optionen fuer
  `--unsafe-filter` und den Legacy-Alias `--filter`
- `DataExportCommand` validiert explizit den Fall "beide gesetzt",
  bevor in den Runner delegiert wird
- `DataExportRequest` und die Runner-/Fingerprint-Pfade verwenden einen
  expliziteren Namen (`unsafeFilter` oder aequivalent), damit der
  kanonische Begriff ab der DTO-Grenze eindeutig ist
- Preflight-Fehler und Help-Texte nennen den Pfad konsistent
  "unsafe"

### 5.2 Neutralmodell und Schema-Codecs

`DefaultValue` bekommt einen neuen Subtyp:

```kotlin
sealed class DefaultValue {
    data class StringLiteral(val value: String) : DefaultValue()
    data class NumberLiteral(val value: Number) : DefaultValue()
    data class BooleanLiteral(val value: Boolean) : DefaultValue()
    data class FunctionCall(val name: String) : DefaultValue()
    data class SequenceNextVal(val sequenceName: String) : DefaultValue()
}
```

Folgen:

- `SchemaNodeParser.parseDefault(...)` mappt
  `default.sequence_nextval: <name>` auf
  `SequenceNextVal("<name>")`
- `SchemaNodeBuilder.buildDefault(...)` schreibt denselben Wert wieder
  als explizites Objekt
- `SchemaValidator.isDefaultCompatible(...)` akzeptiert
  `SequenceNextVal` nur fuer numerische/identifier-aehnliche Spalten
  und nur, wenn die referenzierte Sequence existiert
- `SchemaValidator` oder ein aequivalenter frueher Vorpruefpfad lehnt
  alte `FunctionCall("nextval(...)")`-Defaults explizit ab und liefert
  eine migrationsfaehige Fehlermeldung mit Verweis auf
  `default.sequence_nextval`
- `SchemaCompareHelpers.defaultValueToString(...)` rendert
  `sequence_nextval(<name>)` oder eine aequivalent klar als Sequence-
  Bezug erkennbare Diff-Darstellung; entscheidend ist die eindeutige
  Trennung von freien String-Literalen

Nicht Teil von 0.9.3:

- DB-Reverse von PostgreSQL-`nextval('...')` oder MySQL-Support-
  Triggern auf diesen Subtyp

### 5.3 Generator-Optionen

`DdlGenerationOptions` wird erweitert um:

- `mysqlNamedSequenceMode: MysqlNamedSequenceMode`

Neues Enum:

- `ACTION_REQUIRED`
- `HELPER_TABLE`

CLI- und Runner-Pfad:

- `SchemaGenerateCommand` parst die Option
- `SchemaGenerateRequest` traegt entweder den expliziten Override oder
  den bereits aufgeloesten Enum-Wert
- `SchemaGenerateRunner` validiert die Kombination mit `--target`
- JSON und Report machen den effektiven Modus fuer MySQL sichtbar

Verbindlicher Output-Vertrag:

- die Erweiterung ist additiv; bestehende Felder wie `ddl`,
  `split_mode`, `ddl_parts`, `notes` und `skipped_objects` bleiben
  unveraendert
- das neue Optionsfeld erscheint **nur** bei `target = mysql`
- fuer PostgreSQL und SQLite bleibt das Feld vollstaendig absent,
  nicht `null` und nicht mit einem MySQL-Defaultwert befuellt

Empfohlene Serialisierung fuer MySQL:

- JSON:
  - `generator_options.mysql_named_sequences`
- Report:
  - `target.mysql_named_sequences`

### 5.4 Generatorstruktur fuer `helper_table`

Die Implementierung soll den bestehenden 0.9.2-Phasenschnitt nutzen,
nicht umgehen.

Empfohlene Aufteilung:

- `MysqlDdlGenerator.generateSequences(...)`
  - `action_required`: bisheriges Verhalten
  - `helper_table`: Support-Tabelle + Seed-Statements + W114
- `MysqlDdlGenerator.generateFunctions(...)` oder ein neuer,
  generatorinterner Support-Helfer
  - emittiert `dmg_nextval`
  - emittiert `dmg_setval`
- `MysqlDdlGenerator.generateTriggers(...)`
  - emittiert zuerst generierte Sequence-Support-Trigger fuer
    `SequenceNextVal`
  - emittiert danach die normalen User-Trigger ueber
    `MysqlRoutineDdlHelper`

Bewusste Designgrenze:

- `TypeMapper.toDefaultSql(...)` bleibt fuer Literal- und
  Funktionsdefaults zustaendig
- `SequenceNextVal` wird **nicht** als normaler SQL-Default im
  `TypeMapper` aufgeloest, weil MySQL dafuer Trigger braucht und
  PostgreSQL Identifier-/Regclass-Quoting benoetigt
- die Dialektgeneratoren bzw. deren Column-/Table-Helfer fangen diesen
  Subtyp vorher explizit ab

### 5.5 DDL-Vertrag pro Dialekt

PostgreSQL:

- `SequenceDefinition` -> natives `CREATE SEQUENCE`
- `SequenceNextVal(seq)` -> `DEFAULT nextval('<seq>')`

MySQL `action_required`:

- `SequenceDefinition` bleibt `E056`
- Spalten mit `SequenceNextVal` erzeugen keine stille DEFAULT-Interpolation
- betroffene Spalte erhaelt eine strukturierte `ACTION_REQUIRED`-Note
  mit Bezug auf die fehlende Emulation

MySQL `helper_table`:

- `SequenceDefinition` -> `dmg_sequences`-Zeile
- `SequenceNextVal(seq)` -> kanonischer `BEFORE INSERT`-Trigger
- `cache` bleibt Metadatum in `dmg_sequences`, aber ohne echte
  Preallocation

SQLite:

- bleibt in 0.9.3 auf manuellem Pfad
- `SequenceNextVal` fuehrt wie MySQL `action_required` zu
  strukturierter Diagnose statt zu stiller SQL-Erzeugung

### 5.6 Rollback

Im Single-Output mit `--generate-rollback` muss der neue MySQL-Pfad
vollstaendig rueckwaerts erzeugbar bleiben.

Rollback-Reihenfolge:

1. generierte Sequence-Support-Trigger
2. Support-Routinen `dmg_nextval`, `dmg_setval`
3. Seed-/Supportobjekt `dmg_sequences`

Da `AbstractDdlGenerator.generateRollback(...)` heute rein auf
Statement-Inversion basiert, ist fuer die DELIMITER-verpackten
Routinen/Trigger und fuer die Hilfstabelle eine explizite, getestete
Inversion notwendig.

---

## 6. Konkrete Arbeitspakete

Abhaengigkeiten:

1. `6.1` kann parallel zu den Sequence-Arbeiten laufen
2. `6.2` muss vor `6.3` und `6.4` liegen
3. `6.3` muss vor der eigentlichen MySQL-Generierung fertig sein
4. `6.4` und `6.5` koennen teilweise parallel vorbereitet werden
5. `6.6` schliesst den Milestone ab

### 6.1 Filter-Haertung im Datenexport

- `DataExportCommand` erweitert die CLI um:
  - `--unsafe-filter` als eigene Option
  - `--filter` als separate deprecated Legacy-Option
  - bewusst **kein** Clikt-Alias auf denselben Zielwert
- klare Mutual-Exclusion-Regel, falls beide gesetzt sind
- stderr-Warnung bei Nutzung des Legacy-Alias
- `DataExportRequest`, `DataTransferRunner` und
  `ExportOptionsFingerprint` werden auf den expliziteren
  Begriff umgestellt
- `DataExportHelpers.resolveFilter(...)` und
  `containsLiteralQuestionMark(...)` werden nur semantisch umbenannt,
  nicht fachlich veraendert
- `docs/cli-spec.md` und `docs/quality.md` werden auf den neuen Namen
  aktualisiert

Ergebnis:

- sichtbare Trust-Boundary ist konsistent
- bestehende Exporte bleiben lauffaehig
- Resume-Fingerprints bleiben stabil

### 6.2 Sequence Phase A: Vertrag, Enum und Code-Ledger festziehen

- `MysqlNamedSequenceMode` einfuehren
- `DdlGenerationOptions` erweitern
- `SchemaGenerateCommand` und `SchemaGenerateRunner` verdrahten
  `--mysql-named-sequences`
- Output-Vertrag fuer JSON/Report festziehen
- `docs/ddl-generation-rules.md` und `docs/cli-spec.md` um:
  - die neue MySQL-Option
  - `W114` bis `W117`
  - die `helper_table`-Semantik
  erweitern
- Version-/Header-Vertrag fuer 0.9.3 explizit nachziehen:
  - `AbstractDdlGenerator.getVersion()`
  - Report-Generator-Header in `TransformationReportWriter`
  - ggf. davon abhaengige Golden Masters
- `ledger/warn-code-ledger-0.9.3.yaml` und bei Bedarf
  `ledger/error-code-ledger-0.9.3.yaml` anlegen
- `ledger/code-ledger-0.9.3.schema.json` anlegen **oder**
  `CodeLedgerValidationTest` auf eine versionstolerante Ledger-Aufloesung
  umstellen; in jedem Fall muss die bestehende Ledger-Validierung die
  0.9.3-Dateien aktiv pruefen
- `hexagon/core/src/test/kotlin/dev/dmigrate/core/validation/CodeLedgerValidationTest.kt`
  ist dafuer explizit Teil des Milestones

Ergebnis:

- alle sichtbaren Vertrage sind dokumentiert, bevor der Generatorcode
  folgt

### 6.3 Sequence Phase A: Neutralmodell und Audit aller `DefaultValue`-Verzweigungen

- `DefaultValue.SequenceNextVal` einfuehren
- alle exhaustiven `when (default)`-/`when (dv)`-Stellen anpassen,
  insbesondere:
  - `SchemaValidator.isDefaultCompatible(...)`
  - `SchemaNodeParser.parseDefault(...)`
  - `SchemaNodeBuilder.buildDefault(...)`
  - `SchemaCompareHelpers.defaultValueToString(...)`
  - `MysqlTypeMapper.toDefaultSql(...)`
  - `PostgresTypeMapper.toDefaultSql(...)`
  - `SqliteTypeMapper.toDefaultSql(...)`
  - zugehoerige Tests
- dabei explizit festhalten:
  - PostgreSQL kennt einen nativen Pfad
  - MySQL/SQLite duerfen `SequenceNextVal` nicht still als freie
    Funktion behandeln
  - bestehende `FunctionCall("nextval(...)")`-Faelle werden bewusst
    **hart** mit klarer Migrationsfehlermeldung auf
    `default.sequence_nextval` umgestellt; kein stilles Rewrite, kein
    Warning-only-Pfad
  - TypeMapper-Faelle koennen fuer nicht unterstuetzte Dialekte
    absichtlich `error(...)`/gesonderte Generatorpfade oder
    strukturiertes Abfangen im Aufrufer verwenden

Ergebnis:

- das Repo ist wieder exhaustiv-kompilierbar
- der neue Subtyp ist nicht nur im Generator, sondern in allen
  relevanten Infrastrukturpfaden bekannt

### 6.4 Sequence Phase B: MySQL-Generator fuer `helper_table`

- generatorinterne Support-Helfer fuer:
  - Support-Tabelle
  - Seed-Statements
  - Routinen
  - Trigger-Namensbildung
- `MysqlDdlGenerator.generateSequences(...)` schaltet zwischen
  `ACTION_REQUIRED` und `HELPER_TABLE`
- Spalten mit `SequenceNextVal` werden beim Tabellenbau speziell
  behandelt:
  - PostgreSQL: nativer Default
  - MySQL `helper_table`: kein SQL-DEFAULT, stattdessen Trigger-Spez
  - MySQL `action_required` / SQLite: strukturierte Diagnose
- kanonische Marker-Kommentare und feste Objektform laut
  `docs/mysql-sequence-emulation-plan.md`
- `W114`, `W115`, `W117` werden an den vorgesehenen Stellen erzeugt
- Kollisionen mit reservierten Hilfsnamen erzeugen keinen stillen
  Fallback
- Rollback-Inversion fuer Supportobjekte absichern

Ergebnis:

- `schema generate --target mysql --mysql-named-sequences helper_table`
  erzeugt produktive DDL statt nur `E056`

### 6.5 Doku- und Fixture-Nachzug

- `docs/neutral-model-spec.md` ergaenzen:
  - `default.sequence_nextval: invoice_number_seq`
  - MySQL-Opt-in-Modus
- `docs/schema-reference.md` im selben Zug aktualisieren, weil dort die
  nutzerorientierte `default:`-Syntax beschrieben wird
- `docs/roadmap.md` und ggf. `docs/mysql-sequence-emulation-plan.md`
  mit Verweis auf den umgesetzten Generator-Scope aktualisieren
- neue Schema-Fixtures fuer sequence-basierte Defaults anlegen
- neue Golden Masters fuer:
  - PostgreSQL native `nextval(...)`
  - MySQL `action_required`
  - MySQL `helper_table`
  - Split `pre-data` / `post-data`

### 6.6 Phase C: Tests und Verifikation

Unit-Tests:

- `DataExportRunnerTest`, `CliDataExportTest`:
  - `--unsafe-filter` erfolgreich
  - `--filter` alias mit Warnung
  - beide Flags zusammen -> Exit 2
- `SchemaValidatorTest`:
  - `SequenceNextVal` mit existierender Sequence ok
  - fehlende Sequence -> Validierungsfehler
  - nicht kompatibler Spaltentyp -> Validierungsfehler
  - altes `FunctionCall("nextval(...)")` -> klarer Migrationsfehler mit
    Verweis auf `default.sequence_nextval`
- `YamlSchemaCodecTest`:
  - Round-Trip fuer `default.sequence_nextval`
- `MysqlDdlGeneratorTest`:
  - `action_required` behaelt `E056`
  - `helper_table` erzeugt Tabelle, Seed, Routinen und Trigger
  - Warning-Semantik W114/W115/W117
  - Konfliktfall fuer reservierte Namen
- `SchemaGenerateRunnerTest` / `SchemaGenerateHelpersTest` /
  `TransformationReportWriterTest`:
  - Option validiert nur fuer MySQL
  - JSON/Report enthalten den effektiven Modus nur fuer MySQL
  - JSON/Report bleiben fuer PostgreSQL und SQLite ohne
    `mysql_named_sequences`-Feld
- Golden-Master-/Header-Checks:
  - DDL-Header und Report-Generatorstring zeigen `0.9.3`
- `CodeLedgerValidationTest`:
  - prueft aktiv die 0.9.3-Ledgerdateien bzw. den verallgemeinerten
    Ledger-Resolver

Golden Masters:

- neue DDL-Files fuer MySQL `helper_table`
- Split-Assertions: `dmg_sequences` nur in `pre-data`,
  Routinen/Support-Trigger nur in `post-data`

Integrationstests (MySQL):

- `dmg_nextval('invoice_seq')` liefert monoton steigende Werte
- parallele Aufrufe erzeugen keine Duplikate
- Rollback einer Transaktion zieht den Inkrement-Schritt zurueck
  und wird genau deshalb mit `W117` dokumentiert
- `INSERT` ohne Spaltenwert fuellt die Sequence-Spalte ueber den
  Support-Trigger
- explizites `NULL` triggert denselben Pfad und belegt damit die
  lossy-Semantik hinter `W115`

---

## 7. Verifikationsstrategie

Der Milestone gilt als abgeschlossen, wenn die folgenden Aussagen
gleichzeitig erfuellt sind:

- `data export` dokumentiert und testet nur noch `--unsafe-filter` als
  kanonischen Vertrag
- der Legacy-Alias `--filter` ist getestet, aber sichtbar abgewertet
- `DefaultValue.SequenceNextVal` ist in allen exhaustiven `when`-Pfaden
  beruecksichtigt
- PostgreSQL rendert sequence-basierte Defaults nativ
- MySQL `action_required` bleibt rueckwaertskompatibel
- MySQL `helper_table` erzeugt stabile Golden Masters
- Split-Output trennt Hilfstabelle und Support-Routinen/Trigger
  korrekt nach Phasen
- JSON, Report und stderr-Diagnosen zeigen den neuen Modus und die
  neuen Warnings konsistent

Zusatz fuer Freigabe:

- mindestens ein echter MySQL-Integrationstest muss den atomaren
  `dmg_nextval(...)`-Pfad gegen konkurrierende Aufrufe pruefen
- mindestens ein Integrationstest muss das lossy-`NULL`-Verhalten
  sichtbar belegen

---

## 8. Betroffene Codebasis

Voraussichtlich direkt betroffen:

- `docs/roadmap.md`
- `docs/quality.md`
- `docs/neutral-model-spec.md`
- `docs/schema-reference.md`
- `docs/ddl-generation-rules.md`
- `docs/cli-spec.md`
- `ledger/code-ledger-0.9.3.schema.json`
- `ledger/warn-code-ledger-0.9.3.yaml`
- `ledger/error-code-ledger-0.9.3.yaml` (falls neue Error-Codes noetig)
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/DefaultValue.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/validation/SchemaValidator.kt`
- `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/DdlGenerationOptions.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/ExportOptionsFingerprint.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataTransferRunner.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunner.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataExportCommand.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportHelpers.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateCommand.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateHelpers.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareHelpers.kt`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SchemaNodeParser.kt`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SchemaNodeBuilder.kt`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/report/TransformationReportWriter.kt`
- `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/AbstractDdlGenerator.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlRoutineDdlHelper.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlTypeMapper.kt`
- `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMapper.kt`
- `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTypeMapper.kt`

Voraussichtlich testseitig betroffen:

- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliDataExportTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataExportRunnerTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunnerTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/SchemaGenerateHelpersTest.kt`
- `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGeneratorTest.kt`
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/yaml/YamlSchemaCodecTest.kt`
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/yaml/DdlGoldenMasterTest.kt`
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/report/TransformationReportWriterTest.kt`
- `hexagon/core/src/test/kotlin/dev/dmigrate/core/validation/SchemaValidatorTest.kt`
- `hexagon/core/src/test/kotlin/dev/dmigrate/core/validation/CodeLedgerValidationTest.kt`
- `test/integration-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/...`

---

## 9. Risiken und offene Punkte

### 9.1 Legacy-Alias-Dauer

Offen bleibt nur der Zeitpunkt der spaeteren Entfernung von `--filter`.
Fuer 0.9.3 ist die Alias-Phase richtig; fuer 1.0.0 sollte der Altname
nicht mehr kanonisch in der Hilfe erscheinen.

### 9.2 Schema-Syntax fuer Sequence-Defaults

0.9.3 nutzt bewusst eine explizite Objektform statt eines freien
Strings. Das kostet etwas Kompaktheit, verhindert aber den
Mehrdeutigkeitsfehler zwischen Sequence-Aufruf und String-Literal.
Quoted oder komplexere Sequence-Referenzen koennen spaeter auf dieser
expliziten Objektbasis erweitert werden.

### 9.3 Trigger-Reihenfolge auf MySQL

Der Support-Trigger muss mit eventuell vorhandenen User-Triggern
zusammenspielen. Die 0.9.3-Variante soll deterministische Namen und
Emissionsreihenfolge haben, aber noch kein vollstaendiges
`PRECEDES`-/`FOLLOWS`-Management einfuehren.

### 9.4 Extra-Hilfstabelle im Zielschema

`dmg_sequences` ist im `helper_table`-Modus ein reales Tabellenobjekt.
Das ist fachlich gewollt, muss aber in Doku, Golden Masters und
spaeterem Reverse/Compare exakt als Supportobjekt behandelt werden.

### 9.5 W116 bleibt vorerst nur reserviert

Das ist beabsichtigt und kein Lueckenfehler:

- 0.9.3 liefert den Generatorvertrag
- 0.9.4 liefert den Reverse-Vertrag, der `W116` ueberhaupt erst sauber
  erzeugen kann

---

## 10. Entscheidungsempfehlung

0.9.3 sollte bewusst den kleinen, klaren Schnitt liefern:

- `--unsafe-filter` statt einer halbfertigen DSL
- `DefaultValue.SequenceNextVal` als sauberer Modellanker
- MySQL `helper_table` nur im Generatorpfad und nur opt-in

Diese Kombination schliesst den letzten sichtbaren Security-Footgun im
CLI-Vertrag, liefert echten MySQL-Mehrwert fuer `schema generate` und
haelt gleichzeitig Reverse/Compare-Komplexitaet aus dem Milestone
heraus. Das ist der richtige Umfang fuer einen Beta-Release vor 0.9.4.
