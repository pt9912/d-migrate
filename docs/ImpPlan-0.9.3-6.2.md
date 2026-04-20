# Implementierungsplan: 0.9.3 - Arbeitspaket 6.2 `Sequence Phase A1: Vertrag, Enum und Code-Ledger festziehen`

> **Milestone**: 0.9.3 - Beta: Filter-Haertung und
> MySQL-Sequence-Emulation (Generator)
> **Arbeitspaket**: 6.2 (`Sequence Phase A1: Vertrag, Enum und
> Code-Ledger festziehen`)
> **Status**: Draft (2026-04-20)
> **Referenz**: `docs/implementation-plan-0.9.3.md` Abschnitt 4.3,
> Abschnitt 4.4, Abschnitt 4.5, Abschnitt 4.6, Abschnitt 4.7,
> Abschnitt 5.2, Abschnitt 5.3, Abschnitt 5.3a, Abschnitt 6.2,
> Abschnitt 6.6, Abschnitt 7 und Abschnitt 8;
> `docs/ddl-generation-rules.md`;
> `docs/cli-spec.md`;
> `docs/mysql-sequence-emulation-plan.md`;
> `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/DdlGenerationOptions.kt`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateCommand.kt`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunner.kt`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateHelpers.kt`;
> `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/report/TransformationReportWriter.kt`;
> `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/AbstractDdlGenerator.kt`;
> `hexagon/core/src/test/kotlin/dev/dmigrate/core/validation/CodeLedgerValidationTest.kt`.

---

## 1. Ziel

Arbeitspaket 6.2 zieht den sichtbaren und modellseitigen
Rahmenvertrag fuer die neue MySQL-Sequence-Emulation fest, bevor
Neutralmodell und Generatorcode folgen:

- `schema generate` bekommt den sichtbaren Opt-in-Vertrag
  `--mysql-named-sequences action_required|helper_table`
- `DdlGenerationOptions` transportiert den Modus getypt und
  zielsauber
- JSON, Report und Versionsheader bekommen einen expliziten
  0.9.3-Vertrag
- Warning-/Error-Ledger und deren Validierung kennen die neuen
  0.9.3-Codes und Dateien

6.2 liefert bewusst noch nicht:

- `DefaultValue.SequenceNextVal`
- Schema-Codec-Aenderungen
- die eigentliche MySQL-Supportobjekt-Generierung

Nach 6.2 soll klar gelten:

- der sichtbare CLI-Vertrag fuer MySQL-Sequences ist eingefroren
- Output- und Report-Vertraege sind eindeutig und snapshot-faehig
- die neuen 0.9.3-Codes und Ledgerdateien sind formal abgesichert
- 6.3 und 6.4 koennen auf einem bereits stabilen Rahmen aufsetzen

---

## 2. Ausgangslage

Der aktuelle Stand im Repo ist fuer benannte Sequences noch zu schmal:

- `MysqlDdlGenerator.generateSequences(...)` kennt heute nur
  `ACTION_REQUIRED`/`E056`
- `DdlGenerationOptions` kennt noch keinen MySQL-Sequence-Modus
- `SchemaGenerateCommand` und `SchemaGenerateRunner` haben noch keinen
  sichtbaren Sequence-Opt-in
- JSON/Report kennen noch kein `mysql_named_sequences`
- sichtbare Versionsausgaben sind noch auf `0.9.2` fest verdrahtet
  (`AbstractDdlGenerator.getVersion()`,
  `TransformationReportWriter`,
  `SchemaGenerateHelpers.formatJsonOutput()`)
- die Ledger-Validierung ist bisher auf 0.9.2-orientierte Dateien
  zugeschnitten

Ohne 6.2 waeren die Folgearbeitspakete unsauber aufgesetzt:

- die CLI koennte den MySQL-Opt-in nicht sauber transportieren
- Serializer/Renderer koennten das Feld versehentlich fuer alle Targets
  ausgeben
- neue Warnings waeren dokumentarisch oder testseitig nicht abgesichert
- Golden Masters wuerden an inkonsistenten 0.9.2/0.9.3-Ausgaben haengen

---

## 3. Scope fuer 6.2

### 3.1 In Scope

- `MysqlNamedSequenceMode` als explizites Enum:
  - `ACTION_REQUIRED`
  - `HELPER_TABLE`
- `DdlGenerationOptions.mysqlNamedSequenceMode: MysqlNamedSequenceMode?`
- sichtbare CLI-Option
  `--mysql-named-sequences action_required|helper_table`
- Runner-Validierung: nur zusammen mit `--target mysql`
- JSON-/Report-Vertrag fuer `mysql_named_sequences`
- 0.9.3-Versionsnachzug in DDL-, JSON- und Report-Ausgaben
- Ledger-Arbeit fuer neue 0.9.3-Warn-/ggf. Error-Codes
- Validierung der 0.9.3-Ledgerdateien in `CodeLedgerValidationTest`

### 3.2 Bewusst nicht Teil von 6.2

- `DefaultValue.SequenceNextVal`
- Schema-Parser/-Builder/-Validator fuer Sequence-Defaults
- DDL-Erzeugung fuer `dmg_sequences`, Routinen oder Trigger
- Rollback-Inversion fuer Supportobjekte
- Reverse/Compare-Arbeit

Praezisierung:

6.2 loest "welcher Vertrag und welche sichtbaren Metadaten gelten?",
nicht "wie wird die fachliche Sequence-Unterstuetzung implementiert?".

---

## 4. Leitentscheidungen

### 4.1 Der sichtbare MySQL-Vertrag ist exakt `action_required|helper_table`

Verbindliche Folge:

- `schema generate` akzeptiert:
  - `--mysql-named-sequences action_required`
  - `--mysql-named-sequences helper_table`
- Default ist `action_required`
- eine explizite Nutzung bei PostgreSQL oder SQLite endet mit Exit 2

Begruendung:

- stilles Ignorieren einer gesetzten Dialektoption ist fuer Automation
  zu fehlerfreundlich
- der konservative Default bleibt bis Reverse/Compare in 0.9.4 erhalten

### 4.2 Der Modus lebt als nullable Feld in `DdlGenerationOptions`

Verbindliche Folge:

- `DdlGenerationOptions` wird erweitert um:
  - `mysqlNamedSequenceMode: MysqlNamedSequenceMode?`
- fuer Nicht-MySQL-Ziele bleibt das Feld `null`
- die Default-Aufloesung auf `action_required` passiert erst im
  MySQL-spezifischen Runner-/Generatorpfad, nicht im gemeinsamen
  Options-DTO

Begruendung:

- nur so kann das Feld fuer PostgreSQL/SQLite in JSON und Report
  vollstaendig absent bleiben
- implizite DTO-Defaults wuerden leicht zu nicht gewollten
  Nicht-MySQL-Ausgaben fuehren

### 4.3 JSON und Report sind additiv, aber MySQL-spezifisch

Verbindliche Folge:

- bestehende Felder wie `ddl`, `split_mode`, `ddl_parts`, `notes` und
  `skipped_objects` bleiben unveraendert
- das neue Feld erscheint nur bei `target = mysql`
- fuer PostgreSQL und SQLite bleibt es:
  - absent
  - nicht `null`
  - nicht implizit `action_required`

Empfohlene Serialisierung:

- JSON:
  - `generator_options.mysql_named_sequences`
- Report:
  - `target.mysql_named_sequences`

### 4.4 Warning-Codes werden jetzt fest verankert

Verbindliche Folge:

- `W114`: Cache-Wert wird gespeichert, aber nicht als echte
  Preallocation emuliert
- `W115`: Triggerpfad kann explizites `NULL` nicht von "Wert
  ausgelassen" unterscheiden
- `W117`: globaler Hinweis auf transaktionsgebundene
  Sequence-Inkremente
- `W116` wird in 0.9.3 dokumentiert und im Ledger reserviert, aber
  noch nicht emittiert

Zusatz:

- falls Filter-DSL- oder Sequence-Migrationsfehler in 0.9.3 als
  strukturierte user-visible Diagnosen auftreten, bekommen sie eigene
  0.9.3-Error-Codes statt eines dauerhaften stderr-only-Sonderpfads

### 4.5 Der Versionsnachzug ist ein eigener Vertrag

Verbindliche Folge:

- `AbstractDdlGenerator.getVersion()` liefert `0.9.3`
- `TransformationReportWriter` zeigt `0.9.3`
- `SchemaGenerateHelpers.formatJsonOutput()` bzw. aequivalente
  JSON-Metadaten zeigen `d-migrate 0.9.3`
- davon abhaengige Golden Masters werden im selben Arbeitspaket
  aktualisiert

Akzeptanzregel:

- es verbleibt keine user-visible `0.9.2`-Ausgabe im
  `schema generate`-Pfad

### 4.6 Namensvertrag fuer MySQL-Supportobjekte wird schon jetzt eingefroren

Verbindliche Folge:

- feste Support-Namen:
  - `dmg_sequences`
  - `dmg_nextval`
  - `dmg_setval`
  - `dmg_seq_<table16>_<column16>_<hash10>_bi`
- `table16` und `column16` sind die ersten 16 Zeichen des
  ASCII-lowercased Namens; nicht-alphanumerische Zeichen ausser `_`
  werden vor der Kuerzung entfernt.
- `hash10` sind die ersten 10 lowercase-Hex-Zeichen eines SHA-256 ueber:
  - `tableNorm` = ASCII-lowercased Name von Tabelle mit Entfernen
    nicht-alphanumerischer Zeichen außer `_`
  - `columnNorm` = ASCII-lowercased Name von Spalte mit denselben
    Bereinigungsregeln
  - Hash-Input `<tableNorm>\u0000<columnNorm>`
  - Die Hash-Berechnung nutzt die **nicht gekuerzten** normalisierten Namen
    (nur für den Hash), die Kürzung erfolgt erst bei `table16/column16`
    für die sichtbaren Namen.

Begruendung:

- 6.4 soll keine Namen "erfinden", sondern nur einen bereits
  beschlossenen Vertrag umsetzen
- dieselbe Regel wird spaeter fuer Reverse und Golden Masters gebraucht

---

## 5. Zielarchitektur

### 5.1 CLI- und Runner-Vertrag

Der sichtbare Pfad fuer 6.2 ist:

- `SchemaGenerateCommand` parst `--mysql-named-sequences`
- `SchemaGenerateRequest` transportiert den expliziten Override oder den
  bereits normalisierten Modus
- `SchemaGenerateRunner` validiert die Kombination mit `--target`
- erst im MySQL-Pfad wird `null` fachlich auf `action_required`
  aufgeloest

Fehlervertrag:

- `--mysql-named-sequences` ohne `--target mysql` endet mit Exit 2
- die Fehlermeldung nennt:
  - den ungueltigen Target-Kontext
  - die gueltige Zielbindung an MySQL
  - den zulaessigen Wertebereich

### 5.2 Output- und Renderer-Vertrag

JSON- und Report-Pfade muessen denselben Vertrag sprechen:

- fuer MySQL:
  - effektiver Modus sichtbar
- fuer PostgreSQL/SQLite:
  - Feld komplett absent

Technische Regel:

- `DdlGenerationOptions.mysqlNamedSequenceMode = null` bleibt bis zur
  Renderergrenze `null`
- JSON-Serializer und Report-Renderer unterdruecken `null` konsequent
- kein spaeter Renderer darf selbst einen impliziten
  `action_required`-Default fuer Nicht-MySQL materialisieren

### 5.3 Ledger- und Dokumentationsvertrag

Die 0.9.3-Vertragsarbeit ist nur abgeschlossen, wenn dieselben Regeln
in Code, Ledger und Doku auftauchen:

- `docs/ddl-generation-rules.md`
- `docs/cli-spec.md`
- `ledger/warn-code-ledger-0.9.3.yaml`
- `ledger/error-code-ledger-0.9.3.yaml` (falls noetig)
- `ledger/code-ledger-0.9.3.schema.json`
- `CodeLedgerValidationTest`

6.2 ist damit nicht nur "neue Option einbauen", sondern auch:

- neue sichtbare Codes versionieren
- die Ledger-Dateien aktiv pruefbar machen
- die Spezifikation auf denselben Wortlaut ziehen

---

## 6. Konkrete Arbeitsschritte

### 6.1 Enum und Optionsmodell einfuehren

- `MysqlNamedSequenceMode` anlegen
- `DdlGenerationOptions` um
  `mysqlNamedSequenceMode: MysqlNamedSequenceMode?` erweitern
- Modell-default fuer Nicht-MySQL bewusst `null` lassen

### 6.2 CLI und Runner verdrahten

- `SchemaGenerateCommand` um `--mysql-named-sequences` erweitern
- erlaubte Werte `action_required|helper_table`
- `SchemaGenerateRunner` validiert die Option gegen `--target`
- explizite Exit-2-Diagnosen fuer Nicht-MySQL-Ziele einfuehren

### 6.3 JSON-, Report- und Versionsvertrag nachziehen

- JSON-Ausgabe um `generator_options.mysql_named_sequences` erweitern
- Report-Ausgabe um `target.mysql_named_sequences` erweitern
- `AbstractDdlGenerator.getVersion()` auf `0.9.3`
- `TransformationReportWriter` auf `0.9.3`
- `SchemaGenerateHelpers.formatJsonOutput()` auf `d-migrate 0.9.3`
- Golden Masters/Snapshots fuer diese sichtbaren Aenderungen
  aktualisieren

### 6.4 Ledger und Doku aktualisieren

- `docs/ddl-generation-rules.md` um:
  - neue MySQL-Option
  - `W114` bis `W117`
  - `helper_table`-Semantik
  erweitern
- `docs/cli-spec.md` auf die sichtbare Option ziehen
- 0.9.3-Ledgerdateien anlegen oder die Ledger-Aufloesung in
  `CodeLedgerValidationTest` verallgemeinern
- sicherstellen, dass die 0.9.3-Dateien aktiv validiert werden

---

## 7. Tests und Verifikation

### 7.1 Unit- und Vertragstests

- `SchemaGenerateRunnerTest` / `SchemaGenerateHelpersTest` /
  `TransformationReportWriterTest`:
  - Option validiert nur fuer MySQL
  - JSON/Report enthalten den effektiven Modus nur fuer MySQL
  - JSON/Report bleiben fuer PostgreSQL und SQLite ohne
    `mysql_named_sequences`-Feld
  - `DdlGenerationOptions.mysqlNamedSequenceMode = null` fuehrt bei
    Nicht-MySQL-Ausgaben zu absentem Feld, nicht zu `null` oder
    implizitem `action_required`
- Golden-Master-/Header-Checks:
  - DDL-Header und Report-Generatorstring zeigen `0.9.3`
- `CodeLedgerValidationTest`:
  - prueft aktiv die 0.9.3-Ledgerdateien bzw. den verallgemeinerten
    Ledger-Resolver

### 7.2 Akzeptanzkriterien

6.2 gilt als abgeschlossen, wenn gleichzeitig gilt:

- `schema generate` hat einen expliziten, testbaren
  `--mysql-named-sequences`-Vertrag
- die Option ist nur fuer MySQL zulaessig und liefert sonst Exit 2
- `DdlGenerationOptions` transportiert den Modus nullable und
  target-sicher
- JSON und Report zeigen `mysql_named_sequences` nur fuer MySQL
- `schema generate` zeigt user-visible konsistent `0.9.3`
- die neuen 0.9.3-Ledgerdateien werden aktiv validiert

---

## 8. Betroffene Codebasis

Voraussichtlich direkt betroffen:

- `docs/ddl-generation-rules.md`
- `docs/cli-spec.md`
- `ledger/code-ledger-0.9.3.schema.json`
- `ledger/warn-code-ledger-0.9.3.yaml`
- `ledger/error-code-ledger-0.9.3.yaml` (falls neue Error-Codes noetig)
- `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/DdlGenerationOptions.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateCommand.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunner.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateHelpers.kt`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/report/TransformationReportWriter.kt`
- `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/AbstractDdlGenerator.kt`

Voraussichtlich testseitig betroffen:

- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunnerTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/SchemaGenerateHelpersTest.kt`
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/report/TransformationReportWriterTest.kt`
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/yaml/DdlGoldenMasterTest.kt`
- `hexagon/core/src/test/kotlin/dev/dmigrate/core/validation/CodeLedgerValidationTest.kt`

---

## 9. Offene Punkte

### 9.1 Error-Codes fuer neue strukturierte Diagnosen

Falls Filter- oder Sequence-Migrationsfehler in 0.9.3 als eigene
strukturierte Diagnosen materialisiert werden, muessen sie in diesem
Arbeitspaket noch explizit auf einen 0.9.3-Error-Ledgervertrag gezogen
werden.

### 9.2 6.2 friert nur den Rahmen ein

6.2 entscheidet bewusst nicht ueber das fachliche Verhalten von
`SequenceNextVal` im Generator. Diese Arbeit folgt erst in 6.3 und 6.4.
