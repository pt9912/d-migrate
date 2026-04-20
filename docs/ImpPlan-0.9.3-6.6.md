# Implementierungsplan: 0.9.3 - Arbeitspaket 6.6 `Phase C: Tests und Verifikation`

> **Milestone**: 0.9.3 - Beta: Filter-Haertung und
> MySQL-Sequence-Emulation (Generator)
> **Arbeitspaket**: 6.6 (`Phase C: Tests und Verifikation`)
> **Status**: Draft (2026-04-20)
> **Referenz**: `docs/implementation-plan-0.9.3.md` Abschnitt 6.6,
> Abschnitt 7 und Abschnitt 8;
> `docs/ImpPlan-0.9.3-6.1.md`;
> `docs/ImpPlan-0.9.3-6.2.md`;
> `docs/ImpPlan-0.9.3-6.3.md`;
> `docs/ImpPlan-0.9.3-6.4.md`;
> `docs/ImpPlan-0.9.3-6.5.md`;
> `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliDataExportTest.kt`;
> `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataExportRunnerTest.kt`;
> `hexagon/application/src/test/kotlin/dev/dmigrate/cli/commands/FilterDslParserTest.kt`;
> `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/DataExportE2EMysqlTest.kt`;
> `hexagon/core/src/test/kotlin/dev/dmigrate/core/validation/SchemaValidatorTest.kt`;
> `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/yaml/YamlSchemaCodecTest.kt`;
> `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGeneratorTest.kt`;
> `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunnerTest.kt`;
> `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/SchemaGenerateHelpersTest.kt`;
> `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/report/TransformationReportWriterTest.kt`;
> `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/yaml/DdlGoldenMasterTest.kt`;
> `test/integration-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceEmulationIntegrationTest.kt`;
> `hexagon/core/src/test/kotlin/dev/dmigrate/core/validation/CodeLedgerValidationTest.kt`.

---

## 1. Ziel

Arbeitspaket 6.6 schliesst den Milestone 0.9.3 test- und
freigabeseitig ab:

- alle in 6.1 bis 6.5 definierten Vertraege werden in konkrete Tests
  ueberfuehrt
- die Cross-Cutting-Risiken zwischen Filter-DSL, Neutralmodell,
  MySQL-Generator, Ledger und Doku werden gemeinsam verifiziert
- Freigabekriterien werden so festgezogen, dass 0.9.3 nicht nur
  "kompiliert", sondern als Produktvertrag belastbar ist

6.6 liefert bewusst keine neue Fachlogik. Es ist der
Qualitaetsabschluss des Milestones.

Nach 6.6 soll klar gelten:

- alle neuen 0.9.3-Vertraege sind testbar und getestet
- Golden Masters, Ledger und Integrationstests spiegeln denselben
  Stand
- bekannte Restriktionen wie `W115` und der Breaking Change fuer
  historische `nextval(...)`-Notationen sind sichtbar abgesichert

---

## 2. Ausgangslage

Nach 6.1 bis 6.5 sind mehrere neue Vertraege gleichzeitig aktiv:

- sichere `--filter`-DSL statt Raw SQL
- `SequenceNextVal` als neuer neutraler Modelltyp
- `--mysql-named-sequences action_required|helper_table`
- neue MySQL-Supportobjekte und Rollback-Pfade
- neue 0.9.3-Ledgerdateien und ggf. neue Status-/Code-Regeln
- neue Doku-, Fixture- und Golden-Master-Artefakte

Ohne 6.6 blieben mehrere Risiken offen:

- einzelne Arbeitspakete sind lokal getestet, aber nicht als
  Gesamtsystem gegeneinander validiert
- JSON-/Report-/Golden-Master-Vertraege koennten auseinanderlaufen
- der MySQL-Generatorpfad koennte nur unitseitig, aber nicht
  transaktional/integrationell abgesichert sein
- Breaking-Change-Migrationspfade koennten dokumentiert, aber nicht
  testbar verifiziert sein

6.6 ist deshalb bewusst kein "Restetest", sondern eine konsolidierte
Abnahmephase ueber alle 0.9.3-Teilplaene.

---

## 3. Scope fuer 6.6

### 3.1 In Scope

- Unit-Tests fuer:
  - Filter-DSL
  - Schema-Codec/Validator
  - MySQL-Generator
  - Schema-Generate-Output/Report
  - Ledger-Validierung
- E2E-Test fuer den Exportpfad
- MySQL-Integrationstests fuer `helper_table`
- Golden-Master- und Header-Checks
- abschliessende Freigabekriterien ueber alle 0.9.3-Vertraege

### 3.2 Bewusst nicht Teil von 6.6

- neue Produktfeatures
- neue CLI-Optionen
- Reverse-/Compare-Arbeit fuer 0.9.4
- Last-/Performance-Tuning jenseits der benoetigten Korrektheitschecks

Praezisierung:

6.6 loest "ist 0.9.3 verifiziert und freigabefaehig?", nicht "welche
zusaetzlichen Features koennte man noch anhängen?".

---

## 4. Leitentscheidungen

### 4.1 Die Testmatrix folgt den Vertragsgrenzen, nicht nur Dateigrenzen

Verbindliche Folge:

- Filter-, Modell-, Generator- und Output-Vertraege werden jeweils
  separat und anschliessend gemeinsam verifiziert
- Tests werden nicht nur nach Klassen, sondern nach Nutzervertrag
  strukturiert gelesen

### 4.2 Golden Masters sind Freigabeartefakte, keine Nebensache

Verbindliche Folge:

- neue 0.9.3-Golden-Masters sind Teil der Abnahme
- Header-/Versionsstrings muessen in denselben Checks sichtbar
  mitlaufen
- Split-Artefakte fuer `pre-data` / `post-data` sind explizit Teil der
  Verifikation

### 4.3 Der Breaking Change fuer `nextval(...)` muss testbar bleiben

Verbindliche Folge:

- historische `FunctionCall("nextval(...)")`-Faelle werden nicht nur
  dokumentiert, sondern auch explizit als Migrationsfehler getestet
- derselbe Fehler darf nicht in einem Pfad still durchrutschen und in
  einem anderen hart scheitern

### 4.4 MySQL `helper_table` braucht echte Integration, nicht nur Unit-Tests

Verbindliche Folge:

- mindestens ein echter MySQL-Integrationstest prueft den atomaren
  `dmg_nextval(...)`-Pfad unter Parallelitaet
- mindestens ein Test belegt die lossy-`NULL`-Semantik hinter `W115`
- mindestens ein Up->Down-Lauf prueft die Rollback-Reihenfolge fuer
  Supportobjekte

---

## 5. Testarchitektur

### 5.1 Filter-DSL-Tests

Betroffene Tests:

- `FilterDslParserTest`
- `DataExportRunnerTest`
- `CliDataExportTest`
- `DataExportE2EMysqlTest`

Pflichtabdeckung:

- gueltige DSL -> `ParameterizedClause` / `Compound`
- leerer oder whitespace-only `--filter` -> Exit 2
- nicht erlaubte Raw-SQL-Formen -> Exit 2
- Resume-/Fingerprint-Stabilitaet fuer kanonisch gleiche DSL
- Resume mit altem Raw-SQL-Checkpoint -> Exit 2 mit Migrationshilfe
- Kombination `--filter` + `--since`
- Token-/Literal-Fehler:
  - ungueltige Identifier
  - kaputte Strings
  - `= null` / `!= null` / `IN (null)`
  - unerlaubte Operatoren
  - `OR` / Klammern / Funktionsaufrufe

### 5.2 Neutralmodell- und Codec-Tests

Betroffene Tests:

- `SchemaValidatorTest`
- `YamlSchemaCodecTest`
- Compare-/TypeMapper-nahe Tests

Pflichtabdeckung:

- `SequenceNextVal` mit existierender Sequence ok
- fehlende Sequence -> Validierungsfehler
- inkompatibler Spaltentyp -> Validierungsfehler
- historisches `FunctionCall("nextval(...)")` -> Migrationsfehler mit
  Verweis auf `default.sequence_nextval`
- Round-Trip fuer `default.sequence_nextval`
- unbekannte Default-Map-Schluessel -> Parse-Fehler
- Compare-Kurzform als Lesedarstellung, nicht als Eingabevertrag

### 5.3 Generator- und Output-Tests

Betroffene Tests:

- `MysqlDdlGeneratorTest`
- `SchemaGenerateRunnerTest`
- `SchemaGenerateHelpersTest`
- `TransformationReportWriterTest`

Pflichtabdeckung:

- `action_required` behaelt `E056`
- `helper_table` erzeugt Tabelle, Seed, Routinen und Trigger
- Warning-Semantik `W114` / `W115` / `W117`
- Konfliktfall fuer reservierte Namen
- Option validiert nur fuer MySQL
- JSON/Report enthalten den effektiven Modus nur fuer MySQL
- JSON/Report bleiben fuer PostgreSQL und SQLite ohne
  `mysql_named_sequences`
- `mysqlNamedSequenceMode = null` fuehrt zu absentem Feld, nicht zu
  `null` oder implizitem `action_required`
- DDL-Header und Report-Generatorstring zeigen `0.9.3`

### 5.4 Golden-Master- und Ledger-Tests

Betroffene Tests:

- `DdlGoldenMasterTest`
- `CodeLedgerValidationTest`

Pflichtabdeckung:

- PostgreSQL native `nextval(...)`
- MySQL `action_required`
- MySQL `helper_table`
- Split `pre-data` / `post-data`
- aktive Pruefung der 0.9.3-Ledgerdateien bzw. des
  verallgemeinerten Resolvers

### 5.5 MySQL-Integrationstests

Betroffene Tests:

- `MysqlSequenceEmulationIntegrationTest` (neu)
- ggf. bestehende MySQL-Integrationen im Modul `test/integration-mysql`

Pflichtabdeckung:

- `dmg_nextval('invoice_seq')` liefert fuer erfolgreich
  committete serielle Aufrufe monoton steigende Werte
- parallele committete Aufrufe erzeugen keine Duplikate
- Rollback einer Transaktion zieht den Inkrement-Schritt zurueck
- `INSERT` ohne Spaltenwert fuellt ueber den Support-Trigger
- explizites `NULL` triggert denselben Pfad und belegt `W115`
- Up->Down-Lauf prueft Trigger -> Routinen -> `dmg_sequences`

---

## 6. Konkrete Arbeitsschritte

### 6.1 Testmatrix gegen 6.1 bis 6.5 spiegeln

- jeden Vertragspunkt aus 6.1 bis 6.5 einer Testklasse oder einem
  Golden-Master zuordnen
- fehlende Zuordnungen als Luecken schliessen

### 6.2 Unit- und E2E-Tests vervollstaendigen

- Filter-DSL-Tests
- Validator-/Codec-Tests
- Schema-Generate-/Report-Tests

### 6.3 Golden Masters und Ledger pruefbar machen

- 0.9.3-Golden-Masters einhaengen
- Header-/Versionsstrings stabilisieren
- Ledgerdateien aktiv validieren

### 6.4 MySQL-Integrationen abschliessen

- `helper_table`-Pfad end-to-end gegen echte MySQL-Instanz pruefen
- Parallelitaet, `NULL`-Semantik und Rollback-Reihenfolge absichern

### 6.5 Freigabekriterien abnehmen

- alle Pflichtchecks gruen
- keine Doku-/Output-Widersprueche gegen den Vertrag
- keine offenen Luecken bei Breaking-Change-Kommunikation und
  Abweisungsfaellen

---

## 7. Verifikationsstrategie und Akzeptanzkriterien

### 7.1 Verifikationsstrategie

Der Milestone gilt erst dann als konsistent, wenn alle folgenden
Aussagen gleichzeitig wahr sind:

- `data export` dokumentiert und testet `--filter` nur noch als sichere,
  parameterisierte DSL
- eine eigene `FilterDslParser`-Einheit existiert inkl. eigenem
  Parse-Error-Typ und AST
- der CLI-Pfad baut fuer `--filter` keine rohen `WhereClause`-Fragmente
  mehr aus Nutzereingaben
- Resume mit alten Raw-SQL-Checkpoints scheitert gezielt,
  migrationsfaehig und reproduzierbar
- `DefaultValue.SequenceNextVal` ist in allen exhaustiven
  `DefaultValue`-Pfaden beruecksichtigt
- PostgreSQL rendert sequence-basierte Defaults nativ
- MySQL `action_required` bleibt konservativ
- MySQL `helper_table` erzeugt stabile Golden Masters
- Split-Output trennt Hilfstabelle und Support-Routinen/Trigger korrekt
- JSON, Report und stderr-Diagnosen zeigen den neuen Modus und die
  neuen Warnings konsistent

### 7.2 Zusatz fuer Freigabe

- mindestens ein echter MySQL-Integrationstest prueft den atomaren
  `dmg_nextval(...)`-Pfad gegen konkurrierende Aufrufe
- mindestens ein Integrationstest belegt das lossy-`NULL`-Verhalten
- mindestens ein Integrationstest prueft den Up->Down-Lauf mit fester
  Drop-Reihenfolge

### 7.3 Akzeptanzkriterien

6.6 gilt als abgeschlossen, wenn gleichzeitig gilt:

- alle relevanten Unit-, E2E-, Golden-Master- und Integrationstests
  fuer 0.9.3 sind gruen
- die 0.9.3-Ledgerdateien werden aktiv validiert
- die user-visible 0.9.3-Ausgaben sind konsistent ueber DDL, JSON,
  Report und Doku
- kein Vertragspunkt aus 6.1 bis 6.5 bleibt ungetestet oder nur
  implizit abgesichert

---

## 8. Betroffene Codebasis

Voraussichtlich testseitig betroffen:

- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliDataExportTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataExportRunnerTest.kt`
- `hexagon/application/src/test/kotlin/dev/dmigrate/cli/commands/FilterDslParserTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/DataExportE2EMysqlTest.kt`
- `hexagon/core/src/test/kotlin/dev/dmigrate/core/validation/SchemaValidatorTest.kt`
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/yaml/YamlSchemaCodecTest.kt`
- Compare-/TypeMapper-nahe Tests
- `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGeneratorTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunnerTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/SchemaGenerateHelpersTest.kt`
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/report/TransformationReportWriterTest.kt`
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/yaml/DdlGoldenMasterTest.kt`
- `hexagon/core/src/test/kotlin/dev/dmigrate/core/validation/CodeLedgerValidationTest.kt`
- `test/integration-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceEmulationIntegrationTest.kt`

Artefaktseitig betroffen:

- neue oder aktualisierte 0.9.3-Golden-Masters
- neue oder aktualisierte Schema-Fixtures
- 0.9.3-Ledgerdateien

---

## 9. Offene Punkte

### 9.1 Testlauf-Umgebung fuer MySQL muss reproduzierbar bleiben

Falls die MySQL-Integrationen Container oder spezielle Umgebung
brauchen, muss der Ausfuehrungspfad fuer 0.9.3 stabil dokumentiert und
im Team reproduzierbar sein.

### 9.2 6.6 ist Abschluss, nicht Erweiterung

Wenn in 6.6 neue erhebliche Fachluecken sichtbar werden, gehoeren sie
nicht still in die Testphase "hineinrepariert", sondern muessen als
Ruecksprung in 6.1 bis 6.5 sichtbar gemacht werden.
