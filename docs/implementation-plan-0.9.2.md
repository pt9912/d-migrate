# Implementierungsplan: Milestone 0.9.2 - Beta: DDL-Phasen und importfreundliche Schema-Artefakte

> Dieses Dokument beschreibt den konkreten Implementierungsplan fuer
> Milestone 0.9.2. Es dient als laufend gepflegte Spezifikation und
> Review-Grundlage fuer den sichtbaren DDL-Split von `schema generate`
> sowie fuer die noch offenen Quality-Arbeiten, die laut Roadmap im
> selben Milestone abgeschlossen werden sollen.
>
> Status: Draft (2026-04-18)
> Referenzen: `docs/roadmap.md` Abschnitt "Milestone 0.9.2",
> `docs/ddl-output-split-plan.md`,
> `docs/ddl-generation-rules.md`,
> `docs/cli-spec.md`,
> `docs/quality.md`,
> `docs/implementation-plan-0.9.1.md` Abschnitt 4.6 und 6.3,
> `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/DdlGenerator.kt`,
> `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/ManualActionRequired.kt`,
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunner.kt`,
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt`,
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataImportRunner.kt`,
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateCommand.kt`,
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateHelpers.kt`,
> `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/report/TransformationReportWriter.kt`,
> `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGenerator.kt`,
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt`,
> `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDdlGenerator.kt`.

---

## 1. Ziel

Milestone 0.9.2 fuehrt den in 0.9.1 bewusst zurueckgestellten sichtbaren
Endnutzervertrag fuer importfreundliche DDL-Artefakte ein.

Der Milestone liefert zwei zusammengehoerende Ergebnisse:

- `schema generate` kann optional statt eines einzigen DDL-Stroms zwei
  phasenbezogene Artefakte erzeugen:
  - `pre-data`
  - `post-data`
- die noch offenen Quality-Arbeiten aus `docs/quality.md` werden so weit
  geschlossen, dass der neue Split-Vertrag nicht auf einem unsauberen
  Generator- oder Runner-Unterbau aufsetzt

Der fachliche Hauptnutzen ist operativ:

- MySQL- und SQLite-Workflows bekommen einen sauberen Weg, Trigger,
  Routinen und davon abhaengige Views erst nach einem `data import`
  zu aktivieren
- PostgreSQL kann denselben Vertrag fuer manuelle Rollouts, Tooling und
  spaetere Exportpfade nutzen
- JSON-, Report- und Diagnoseausgaben bleiben im Split-Fall
  maschinenlesbar, ohne den bestehenden Default-Output zu brechen

0.9.2 ist damit bewusst ein Nutzer-Feature-Milestone auf Basis der
0.9.1-Vorarbeiten:

- 0.9.1 durfte den DDL-Modell-Schnitt intern vorbereiten
- 0.9.2 macht daraus einen expliziten CLI- und Output-Vertrag

---

## 2. Ausgangslage

Stand im aktuellen Repo:

- `DdlResult` ist aktuell noch ein flaches Modell aus:
  - `statements: List<DdlStatement>`
  - `skippedObjects: List<SkippedObject>`
  - `notes` werden implizit aus allen Statements abgeleitet
- `DdlStatement`, `TransformationNote` und `SkippedObject` tragen noch
  keinen Phasenbezug
- `SchemaGenerateRequest` kennt noch keinen Split-Modus; der Runner
  rendert nur einen zusammenhaengenden DDL-Text ueber `result.render()`
- `SchemaGenerateRunner` unterstuetzt heute genau drei sichtbare
  Ausgabemodi:
  - SQL an `stdout`
  - SQL-Datei plus Sidecar-Report
  - JSON mit einem einzelnen `ddl`-String
- `TransformationReportWriter` kennt heute weder `split_mode` noch
  phasenbezogene Notes oder `skipped_objects`
- `SchemaGenerateHelpers.formatJsonOutput(...)` serialisiert im
  Erfolgsfall immer genau ein Feld `ddl`
- die Generatoren sind in 0.9.1 bereits auf strukturierte
  `ManualActionRequired`-Metadaten vorbereitet; das Default-Rendering
  emittiert aber noch aus Rueckwaertskompatibilitaetsgruenden
  `-- TODO: ...`-Kommentare
- der in 0.9.1 geplante Port-Schnitt ist im Repo bereits angelegt:
  - `hexagon:ports-common`
  - `hexagon:ports-read`
  - `hexagon:ports-write`
- mehrere vormals offene Quality-Arbeiten sind bereits erledigt und
  gehoeren **nicht** mehr zum 0.9.2-Scope:
  - Routine-Ddl-Helper sind direkt getestet
  - `ports-*`-Module haben explizite Kover-Schwellen
  - PostgreSQL- und SQLite-TODO-Platzhalter sind bereinigt

Noch offen und fuer 0.9.2 relevant bleiben laut `docs/quality.md`:

- systematische Absicherung der DDL-Interpolation von
  Schema-Metadaten
- Zerlegung der langen `executeWithPool()`-Methoden in
  `DataExportRunner` und `DataImportRunner`
- Entschaerfung der breiten `ExportExecutor`-/`ImportExecutor`-
  Signaturen ueber Kontext-DTOs
- Eliminierung der verbleibenden 4 MySQL-`-- TODO`-Plaetze
- systematische Fehlercode-Abdeckung gegen die Validierungsmatrix
- ein echter E2E-Round-Trip-Test ueber den Gesamtpfad

Konsequenz:

- 0.9.2 ist nicht nur "noch ein Flag" fuer `schema generate`
- der Milestone muss Generator-Modell, CLI-Vertrag, JSON-/Report-
  Serialisierung, Generator-Zuordnung und begleitende
  Qualitaetssicherung zusammen liefern

---

## 3. Scope

### 3.1 In Scope (Kernfeature)

- neuer Split-Vertrag fuer `schema generate`:
  - `single`
  - `pre-post`
- phasenbezogenes DDL-Modell fuer:
  - Statements
  - Notes
  - `skipped_objects`
- deterministische Zuordnung der Objektarten zu:
  - `pre-data`
  - `post-data`
- sichtbarer CLI- und Output-Vertrag:
  - SQL-Dateiausgabe fuer Split-Fall
  - JSON-Ausgabe ueber `ddl_parts`
  - Sidecar-Report mit `split_mode` und `phase`
- Rueckwaertskompatibilitaet des heutigen Default-Pfads
- Tests fuer:
  - Golden Masters
  - CLI-Validierung
  - JSON-/Report-Ausgabe
  - Fehlerpfade
  - View-/Routinen-Abhaengigkeiten

### 3.2 In Scope (begleitende Quality-Arbeiten)

Die folgenden Arbeiten sind Teil von 0.9.2, weil sie laut Roadmap im
Milestone liegen und direkt an den neuen DDL-/Importvertrag andocken:

- DDL-Interpolation systematisch absichern:
  - CHECK-Constraints
  - Partition-Ausdruecke
  - Trigger-Bedingungen
  - SpatiaLite-Funktionsaufrufe
  - Umgang mit Routine-/View-Bodies explizit als Trusted-Input-Grenze
- `DataExportRunner.executeWithPool()` und
  `DataImportRunner.executeWithPool()` in kleinere Schrittfunktionen
  zerlegen
- `ExportExecutor` und `ImportExecutor` ueber Kontext-DTOs und
  Callback-Gruppen entschlacken
- die verbleibenden 4 MySQL-`-- TODO`-Plaetze durch rein strukturierte
  `ManualActionRequired`-/`SkippedObject`-Wege ersetzen
- Fehlercodes E006-E121 gegen die dokumentierte Matrix absichern
- einen echten E2E-Round-Trip-Test fuer
  DB -> Export -> Format -> Import -> DB -> Schema-Vergleich nachziehen

### 3.3 Bewusst nicht Teil von 0.9.2

- automatische Ausfuehrung eines Datenimports zwischen `pre-data` und
  `post-data`
- neuer Importmodus fuer Trigger-Deaktivierung auf MySQL oder SQLite
- Rollback-Split im selben Milestone
- weitere DDL-Phasen jenseits von `pre-data`/`post-data`
  (z. B. `post-constraints`, `seed-data`, `tool-only`)
- neuer Tool-Exportvertrag fuer Flyway/Liquibase auf Basis der neuen
  Phasen
- Aenderung des bestehenden Default-Outputs von `schema generate`
  ausserhalb des expliziten Split-Modus
- oeffentlicher Publish-Vertrag oder Artefakt-Neuschnitt fuer 1.0.0

---

## 4. Leitentscheidungen

### 4.1 `single` bleibt der unveraenderte Default

Verbindliche Entscheidung:

- ohne `--split` bleibt `schema generate` fachlich und textuell beim
  bisherigen Verhalten
- bestehende Golden Masters fuer den Single-Pfad duerfen sich nicht
  unbeabsichtigt aendern
- der neue Split ist opt-in und darf keine heimliche Default-
  Umdeutung einfuehren

### 4.2 Der sichtbare Vertrag ist `single|pre-post`

Verbindliche Entscheidung:

- der CLI-Vertrag lautet:
  - `--split single`
  - `--split pre-post`
- Default ist `single`
- andere Modellvarianten bleiben intern offen, werden aber in 0.9.2
  nicht nach aussen exponiert

### 4.3 Der Phasenbezug lebt im DDL-Modell, nicht nur im Renderer

Verbindliche Entscheidung:

- `DdlStatement` traegt eine `phase`
- `TransformationNote` und `SkippedObject` tragen ebenfalls einen
  optionalen oder expliziten `phase`-Bezug
- `DdlResult` bleibt ein einheitlicher Ergebniscontainer; er wird nicht
  nur fuer die Dateiausgabe in zwei unverbundene Strings zerlegt

Begruendung:

- dieselbe Quelle steuert SQL-Rendering, JSON, Report und Diagnose
- phasenbezogene Hinweise koennen spaeter ohne Sonderwege erweitert
  werden
- Inkonsistenzen zwischen SQL, JSON und Report werden reduziert

### 4.4 JSON bleibt rueckwaertskompatibel

Verbindliche Entscheidung:

- im Single-Fall bleibt `ddl` ein String
- im Split-Fall wird **kein** Typwechsel von `ddl` vorgenommen
- stattdessen fuehrt der Split-Fall ein neues Feld `ddl_parts` ein

Nicht zulaessig ist:

- `ddl` bei `single` als String und bei `pre-post` als Objekt
  umzudeuten

### 4.5 SQL-Split braucht einen adressierbaren Outputpfad

Verbindliche Entscheidung:

- `--split pre-post` mit Text-SQL ist nur zusammen mit `--output`
  erlaubt
- `--split pre-post` ohne `--output` endet mit Exit 2, sofern nicht
  `--output-format json` gewaehlt ist
- JSON darf im Split-Fall auch ohne `--output` serialisiert werden,
  weil es ein einzelnes strukturiertes Antwortartefakt ist

### 4.6 Rollback bleibt im Split-Fall fuer 0.9.2 bewusst aus

Verbindliche Entscheidung:

- `--split pre-post --generate-rollback` ist in 0.9.2 nicht
  unterstuetzt
- der Aufruf endet mit Exit 2 und klarer Fehlermeldung

Begruendung:

- ein halbspezifizierter Rollback-Split vergroessert den Vertrag stark,
  ohne den Kernnutzen des Features zu liefern
- `pre-data`/`post-data` fuer Down-Migrationen braucht eine eigene
  fachliche Modellierung

### 4.7 `pre-data` muss fuer sich ausfuehrbar bleiben

Verbindliche Entscheidung:

- `pre-data` darf keine Objekte enthalten, die erst durch
  `post-data`-Objekte ausfuehrbar werden
- Views mit Routine-Abhaengigkeiten wandern nach `post-data`
- wenn die Analyse fuer einen sicheren Split nicht ausreicht, wird der
  Split-Lauf abgebrochen statt ein potenziell defektes Artefakt zu
  erzeugen

### 4.8 Quality-Arbeiten werden nur in einer Form akzeptiert, die den neuen Vertrag staerkt

Verbindliche Entscheidung:

- Runner-Zerlegung ist kein separater Schoenheitsrefactor, sondern muss
  die Verstaendlichkeit der 0.9.2-Feature- und Fehlerpfade messbar
  verbessern
- DDL-Haertung darf sichtbare Generatorsemantik nur dort aendern, wo
  unsichere oder unquotierte Interpolation vorlag
- MySQL-`-- TODO`-Bereinigung darf den Diagnosevertrag nicht
  verschlechtern; strukturierte `ManualActionRequired` muss in Text,
  JSON und Report gleichwertig sichtbar bleiben

---

## 5. Zielarchitektur

### 5.1 DDL-Modell mit explizitem Phasenbezug

Zielstruktur:

- `DdlPhase` mit mindestens:
  - `PRE_DATA`
  - `POST_DATA`
- `DdlStatement(sql, notes, phase)`
- `TransformationNote(..., phase)`
- `SkippedObject(..., phase)`
- `DdlResult` mit:
  - Gesamtliste aller Statements
  - Render-Helfern fuer Gesamt- und Phasensicht
  - konsistenten Aggregationen fuer Notes und Skips

### 5.2 Sichtbarer Output-Vertrag

SQL:

- `single`:
  - ein DDL-Artefakt wie heute
- `pre-post`:
  - `<name>.pre-data.sql`
  - `<name>.post-data.sql`
  - Report bleibt ein einzelnes Sidecar-Artefakt

JSON:

- `single`:
  - `ddl: "<string>"`
- `pre-post`:
  - `split_mode: "pre-post"`
  - `ddl_parts.pre_data`
  - `ddl_parts.post_data`
  - Notes und `skipped_objects` jeweils mit `phase`

Report:

- `split_mode`
- zusammenfassende Kennzahlen fuer beide Phasen
- Notes und `skipped_objects` jeweils mit `phase`

### 5.3 Objektzuordnung

Zielzuordnung in 0.9.2:

- `pre-data`:
  - Header
  - Custom Types
  - Sequences, sofern der Dialekt sie nativ traegt
  - Tabellen
  - zirkulaere FK-Nachzuegler
  - Indizes
  - Views ohne Routine-Abhaengigkeiten
- `post-data`:
  - Functions
  - Procedures
  - Triggers
  - Views mit Function-/Procedure-Abhaengigkeiten

Dialektspezifische Randfaelle:

- MySQL- und SQLite-Sequences bleiben wie heute
  `action_required`/`skipped`
- PostgreSQL-Trigger-Funktionen, die konzeptionell zu einem Trigger
  gehoeren, muessen mit dem Trigger in derselben Phase landen
- SpatiaLite-spezifische DDL bleibt in `pre-data`, muss aber sauber
  gequotet oder explizit als Trusted-Expression-Grenze modelliert
  werden

### 5.4 Runner- und Executor-Zuschnitt

Fuer 0.9.2 soll die Runner-Struktur erkennbar werden:

- `DataExportRunner`:
  - Registry-/Preflight-Aufloesung
  - Tabellen-/Output-Aufloesung
  - Resume-/Checkpoint-Kontext
  - Streaming-Ausfuehrung
  - Finalisierung/Reporting
- `DataImportRunner`:
  - CLI-/Format-Preflight
  - Schema-Preflight
  - Options-/Fingerprint-Build
  - Resume-/Manifest-Kontext
  - Streaming-Ausfuehrung
  - Finalisierung/Reporting
- `ExportExecutor` und `ImportExecutor` sollen stattdessen mit
  kontextgruppierten Parametern arbeiten:
  - Laufzeitkontext
  - Optionen
  - Resume-Kontext
  - Callbacks

---

## 6. Konkrete Arbeitspakete

Abhaengigkeiten und Reihenfolge:

1. **6.1** erweitert den sichtbaren CLI-/Output-Vertrag
2. **6.2** zieht den Phasenbezug in das DDL-Modell
3. **6.3** ordnet Generatorobjekte den Phasen zu
4. **6.4** implementiert Rendering, JSON und Report
5. **6.5** zieht die DDL-Haertung und MySQL-Bereinigung nach
6. **6.6** zerlegt Runner und Executor-Vertraege
7. **6.7** zieht Tests, Fehlercode-Matrix und E2E-Verifikation nach

### 6.1 `schema generate` um den Split-Vertrag erweitern

- `SchemaGenerateRequest` um `splitMode` erweitern
- `SchemaGenerateCommand` um `--split single|pre-post` erweitern
- CLI-Validierung ergaenzen:
  - `pre-post` ohne `--output` und ohne JSON -> Exit 2
  - `pre-post` mit `--generate-rollback` -> Exit 2
- Fehlermeldungen, Help-Text und CLI-Spezifikation angleichen

Ergebnis:

Der Nutzervertrag fuer den Split ist explizit und testbar.

### 6.2 DDL-Modell phasenfaehig machen

- `DdlPhase` einfuehren
- `DdlStatement`, `TransformationNote` und `SkippedObject` um `phase`
  erweitern
- `DdlResult` um Hilfen fuer:
  - Gesamt-Rendering
  - `renderPhase(PRE_DATA)`
  - `renderPhase(POST_DATA)`
  - phasenbezogene Aggregation von Notes/Skips
- bestehende Aufrufer auf den neuen Vertrag umstellen, ohne den
  Single-Renderpfad zu veraendern

Ergebnis:

SQL, JSON und Report koennen denselben Phasenbezug auswerten.

### 6.3 Generator-Zuordnung pro Dialekt und Objekttyp festziehen

- alle drei DDL-Generatoren und ihre Routine-Helfer auf explizite
  Phasenmarkierung umstellen
- Zuordnung fest verdrahten fuer:
  - Header
  - Types
  - Sequences
  - Tables
  - Indices
  - FK-Nachzuegler
  - Views
  - Functions
  - Procedures
  - Triggers
- View-Abhaengigkeiten auf Routinen deterministisch analysieren
- unsicher aufloesbare Split-Faelle mit klarer Fehlermeldung und Exit 2
  abbrechen

Ergebnis:

`pre-data` und `post-data` sind nicht nur unterschiedlich gerenderte
Strings, sondern fachlich getrennte Artefakte.

### 6.4 SQL-, JSON- und Report-Ausgabe fuer den Split nachziehen

- `SchemaGenerateRunner` fuer Split-Ausgabe erweitern
- Dateinamenslogik fuer:
  - `.pre-data.sql`
  - `.post-data.sql`
- `SchemaGenerateHelpers.formatJsonOutput(...)` auf:
  - `split_mode`
  - `ddl_parts`
  - phasenattribuierte Notes/Skips
  umstellen
- `TransformationReportWriter` um:
  - `split_mode`
  - phasenattribuierte Notes/Skips
  erweitern
- der Single-Fall bleibt fuer Text, JSON und Report byte- bzw.
  semantikstabil

Ergebnis:

Der neue Vertrag ist ueber alle Ausgabekanaele konsistent sichtbar.

### 6.5 DDL-Haertung und MySQL-`-- TODO`-Bereinigung nachziehen

- Interpolationsstellen systematisch inventarisieren und absichern:
  - CHECK-Expressions
  - Domain-/Type-Checks
  - Partition-Werte
  - Trigger-Bedingungen
  - SpatiaLite-Aufrufe
- klare Regel pro Feld dokumentieren:
  - identifier-quoted
  - literal-escaped
  - bewusst als Trusted Input durchgereicht
- MySQL-Generator von sichtbaren `-- TODO`-Placeholders befreien:
  - Composite Types
  - Sequences
  - EXCLUDE-Constraints
  - nicht unterstuetzte Index-Typen
- strukturierte `ManualActionRequired`-/`SkippedObject`-Ausgabe in
  SQL, JSON und Report absichern

Ergebnis:

Der neue DDL-Split baut auf einem saubereren und expliziter gesicherten
Generatorvertrag auf.

### 6.6 Runner-Zerlegung und Executor-Kontextschnitt nachziehen

- `DataExportRunner.executeWithPool()` in kleine Schrittfunktionen
  aufteilen
- `DataImportRunner.executeWithPool()` analog schneiden
- `ExportExecutor.execute(...)` durch Kontextobjekte entlasten
- `ImportExecutor.execute(...)` analog schneiden
- bestehende Tests so nachziehen, dass die neuen Schrittfunktionen und
  Kontexte einzeln absicherbar bleiben

Ergebnis:

Die in `docs/quality.md` benannten Hotspots werden kleiner, lesbarer und
fuer 0.9.2-Nacharbeiten stabiler.

### 6.7 Tests, Fehlercode-Matrix und E2E-Round-Trip nachziehen

- bestehende Golden Masters fuer `single` einfrieren
- neue Golden Masters fuer `pre-data` und `post-data` je Dialekt
- CLI-Tests fuer Split-Validierung und Dateinamenslogik
- JSON-/Report-Tests fuer `ddl_parts`, `split_mode` und `phase`
- Fehlercode-Matrix E006-E121 systematisch mit Tests abdecken
- E2E-Round-Trip-Test:
  - Quell-DB erzeugen
  - Daten exportieren
  - in Ziel-DB importieren
  - Schema erneut lesen/vergleichen

Ergebnis:

0.9.2 ist nicht nur funktional implementiert, sondern fachlich und
operativ belastbar belegt.

---

## 7. Verifikationsstrategie

Pflichtfaelle fuer das Kernfeature:

- `schema generate` ohne `--split` bleibt fuer alle bestehenden
  Golden-Master-Faelle unveraendert
- `schema generate --split pre-post --output out/schema.sql` erzeugt:
  - `out/schema.pre-data.sql`
  - `out/schema.post-data.sql`
  - einen Report mit `split_mode`
- `--split pre-post --output-format json` liefert:
  - `split_mode`
  - `ddl_parts`
  - phasenattribuierte `notes`
  - phasenattribuierte `skipped_objects`
- `--split pre-post` ohne `--output` und ohne JSON endet mit Exit 2
- `--split pre-post --generate-rollback` endet mit Exit 2

Pflichtfaelle fuer die DDL-Zuordnung:

- Trigger erscheinen nie in `pre-data`
- Functions und Procedures erscheinen nie in `pre-data`
- Tabellen und Indizes erscheinen nie in `post-data`
- Views ohne Routine-Abhaengigkeit bleiben in `pre-data`
- Views mit Routine-Abhaengigkeit landen in `post-data`
- `pre-data + post-data` ist fachlich aequivalent zum Single-Gesamtbild,
  abgesehen von Datei- und JSON-Struktur

Pflichtfaelle fuer die Quality-Arbeiten:

- DDL-Haertung mit gezielten Negativ- oder Boundary-Tests fuer:
  - CHECK
  - Partition
  - Trigger-WHEN
  - SpatiaLite-Identifier
- keine verbleibenden sichtbaren MySQL-`-- TODO`-Kommentare im
  Generator-Output
- Runner-Refactor bleibt in Exit-Code- und Resume-Semantik stabil
- `ExportExecutor`-/`ImportExecutor`-Aufrufer funktionieren nach
  Kontextschnitt unveraendert
- Fehlercodes E006-E121 sind gegen die Matrix testseitig nachweisbar

Pflichtfall fuer Integrationsverifikation:

- E2E-Round-Trip-Test ueber mindestens einen realen Pfad:
  - Schema erzeugen
  - Daten exportieren
  - Daten importieren
  - Zielschema gegen Erwartung pruefen

Erwuenschte Zusatzfaelle:

- dedizierte Split-Fixtures fuer MySQL- und SQLite-Trigger-Szenarien
- Split-Fixtures mit Routine-abhaengigen Views
- JSON-Contract-Smokes fuer externe Consumer

---

## 8. Betroffene Codebasis

Direkt betroffen:

- `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/DdlGenerator.kt`
- `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/ManualActionRequired.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunner.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateCommand.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateHelpers.kt`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/report/TransformationReportWriter.kt`
- `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGenerator.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt`
- `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDdlGenerator.kt`
- die jeweiligen Routine-Helfer pro Dialekt

Wahrscheinlich mit betroffen:

- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataImportRunner.kt`
- CLI-Tests unter `adapters/driving/cli/src/test/...`
- DDL-Generator-Tests unter `adapters/driven/driver-*/src/test/...`
- Golden Masters unter
  `adapters/driven/formats/src/test/resources/fixtures/ddl/...`
- Integrationstests unter `test/integration-postgresql` und
  `test/integration-mysql`

Dokumentation:

- `docs/roadmap.md`
- `docs/ddl-output-split-plan.md`
- `docs/ddl-generation-rules.md`
- `docs/cli-spec.md`
- `docs/guide.md`
- `docs/architecture.md`

---

## 9. Risiken und offene Punkte

### 9.1 Split ohne belastbare View-/Routinen-Analyse kann defekte `pre-data`-Artefakte erzeugen

Wenn Views in `pre-data` landen, obwohl sie Functions oder Procedures aus
`post-data` benoetigen, ist der Split operativ wertlos.

Mitigation:

- Routine-Abhaengigkeiten explizit analysieren
- unsichere Faelle nicht heuristisch durchwinken
- lieber Exit 2 als defektes Artefakt

### 9.2 JSON- und Report-Vertrag koennen bestehende Consumer brechen

Der Split beruehrt den strukturierten Output von `schema generate`.

Mitigation:

- `ddl` im Single-Fall unveraendert lassen
- `ddl_parts` nur im Split-Fall einfuehren
- Contract-Tests fuer JSON und Report ergaenzen

### 9.3 DDL-Haertung kann sichtbare SQL-Ausgabe unbeabsichtigt veraendern

Sicherheitsverbesserungen an Interpolationsstellen koennen Golden Masters
oder bestehende Handgriffe beeinflussen.

Mitigation:

- fuer jedes betroffene Feld explizit festhalten, ob es gequotet,
  escaped oder bewusst als Trusted Input behandelt wird
- Golden Masters gezielt nur dort anpassen, wo die Aenderung fachlich
  begruendet ist

### 9.4 Runner-Refactor kann Resume-/Checkpoint-Verhalten regressieren

Die langen Runner-Methoden sind zwar wartungskritisch, enthalten aber
viele Semantikdetails.

Mitigation:

- Exit-Code- und Resume-Tests vor dem Umbau stabil halten
- Schrittfunktionen entlang vorhandener Phasen statt quer zur Semantik
  schneiden
- keine fachliche Aenderung ohne explizite Tests

### 9.5 MySQL-`ManualActionRequired` ohne sichtbare SQL-Marker kann Nutzer verwirren

Wenn sichtbare TODO-Kommentare verschwinden, muss die Diagnose trotzdem
operativ sichtbar bleiben.

Mitigation:

- Text-, JSON- und Report-Ausgabe fuer `ACTION_REQUIRED` klar und
  redundant halten
- dokumentieren, dass strukturierte Diagnose die alten TODO-Kommentare
  ersetzt

### 9.6 Der E2E-Round-Trip kann zu breit werden, wenn er zu viele Dialektfaelle zugleich abdeckt

Ein ueberfrachteter Integrationstest verlangsamt den Milestone und wird
instabil.

Mitigation:

- einen schmalen, aber echten End-to-End-Pfad als Pflichtfall
  definieren
- weitere Dialekte oder Sonderfaelle als Folgeabdeckung nachziehen

---

## 10. Entscheidungsempfehlung

Milestone 0.9.2 sollte als klarer Nutzer-Feature-Milestone mit
begleitender Quality-Schliessung umgesetzt werden.

Empfohlener Zuschnitt:

1. `schema generate` bekommt einen expliziten Split-Vertrag
   `single|pre-post`
2. das DDL-Modell wird phasenfaehig gemacht und steuert SQL, JSON und
   Report aus einer gemeinsamen Quelle
3. Generatoren ordnen Trigger, Routinen und davon abhaengige Views
   konsequent `post-data` zu
4. JSON fuehrt `ddl_parts` ein, ohne `ddl` im Default-Fall zu brechen
5. die noch offenen Quality-Arbeiten aus `docs/quality.md` werden im
   selben Milestone geschlossen:
   - DDL-Haertung
   - Runner-Zerlegung
   - Executor-Kontextschnitt
   - MySQL-`-- TODO`-Bereinigung
   - Fehlercode-Matrix
   - E2E-Round-Trip

Damit liefert 0.9.2 nicht nur neue Dateien, sondern einen belastbaren
operativen Vertrag fuer importfreundliche Schema-Artefakte, auf dem
spaetere Tool-Integrationen und 1.0.0-Dokumentation sauber aufsetzen
koennen.
