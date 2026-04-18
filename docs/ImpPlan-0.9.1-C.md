# Implementierungsplan: Phase C - Port- und Optionsschnitt trennen

> **Milestone**: 0.9.1 - Library-Refactor und Integrationsschnitt
> **Phase**: C (Port- und Optionsschnitt trennen)
> **Status**: Done (2026-04-18) — Kern (5.1-5.3) umgesetzt, Stretch Goals offen
> **Referenz**: `docs/implementation-plan-0.9.1.md` Abschnitt 1 bis 5,
> Abschnitt 6.3, Abschnitt 7, Abschnitt 8 und Abschnitt 9;
> `docs/d-browser-integration-coupling-assessment.md`;
> `docs/hexagonal-port.md`;
> `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/DatabaseDriver.kt`;
> `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/SchemaReader.kt`;
> `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/SchemaReadResult.kt`;
> `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/DataReader.kt`;
> `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/DataWriter.kt`;
> `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/ImportOptions.kt`;
> `hexagon/ports/src/main/kotlin/dev/dmigrate/format/data/DataChunkReaderFactory.kt`;
> `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/DefaultDataChunkReaderFactory.kt`;
> `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/csv/CsvChunkReader.kt`;
> `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/json/JsonChunkReader.kt`;
> `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/yaml/YamlChunkReader.kt`;
> `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingImporter.kt`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataImportRunner.kt`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataTransferRunner.kt`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/ImportOptionsFingerprint.kt`.

---

## 1. Ziel

Phase C zieht den im 0.9.1-Masterplan benannten Kopplungspunkt zwischen
read-orientierten Ports und write-orientierten Importvertraegen fachlich
auseinander.

Der Teilplan beantwortet bewusst zuerst die Struktur- und
Vertragsfragen:

- welche Porttypen fuer reine Lese-Consumer wirklich noetig sind
- welche Typen klar write-/import-orientiert bleiben muessen
- wie die heute gemischte Nutzung von `ImportOptions` in Format-Readern
  und Writer-Pfaden aufgetrennt wird
- wie `StreamingImporter` und `DataImportRunner` nach der Trennung
  explizit mit zwei unterschiedlichen Optionskanaelen arbeiten
- wie dabei Resume-/Fingerprint-Invarianten aus 0.9.0 stabil bleiben
- wie reine Lese-Consumer kuenftig weniger Ballast aus dem
  Portbereich transitiv sehen

Phase C liefert damit keine neue Endnutzerfunktion, sondern einen
saubereren Integrationsschnitt fuer externe Lese-Consumer wie
`d-browser` und fuer spaetere Library-Nutzung.

Nach Phase C soll klar gelten:

- Format-Reader sprechen kein writer-zentriertes `ImportOptions` mehr
- `ImportOptions` traegt nur noch echten Import-/Writer-Vertrag
- read-only Pfade koennen ohne `DataWriter`-,
  `TableImportSession`- und writer-zentrierte Optionen konsumiert
  werden
- `adapters:driven:formats` bzw. ein gleichwertiger read-orientierter
  Consumer exportiert keine write-orientierten Porttypen mehr ueber
  seine API
- CLI-Flags, Resume-Fingerprint und Import-Semantik bleiben fuer
  Nutzer unveraendert

---

## 2. Ausgangslage

Im aktuellen Repo sind die folgenden Kopplungen sichtbar:

- `hexagon:ports` enthaelt heute sowohl read-orientierte als auch
  write-orientierte Vertraege:
  - read-seitig u.a. `SchemaReader`, `SchemaReadResult`,
    `SchemaReadOptions`, `DataReader`, `TableLister`,
    `DataChunkReaderFactory`
  - write-seitig u.a. `DataWriter`, `TableImportSession`,
    `ImportOptions`, `SchemaSync`, `WriteResult`,
    `FinishTableResult`
- `DatabaseDriver` exponiert beide Seiten zugleich
  (`dataReader()`, `schemaReader()`, `tableLister()` sowie
  `dataWriter()`), ist also als Fassade bewusst gemischt
- `ImportOptions` wird heute nicht nur fuer echten Import-/Writer-
  Vertrag verwendet, sondern auch im reinen Format-Lesepfad:
  - `DataChunkReaderFactory.create(..., options: ImportOptions)`
  - `CsvChunkReader`
  - `JsonChunkReader`
  - `YamlChunkReader`
- die read-relevanten Felder in `ImportOptions` sind aktuell:
  - `encoding`
  - `csvNoHeader`
  - `csvNullString`
- die write-/import-relevanten Felder in `ImportOptions` sind aktuell:
  - `triggerMode`
  - `reseedSequences`
  - `disableFkChecks`
  - `truncate`
  - `onConflict`
  - `onError`
- `StreamingImporter.import(...)` nimmt heute genau ein
  `ImportOptions` entgegen und reicht dieselbe Instanz sowohl an
  `readerFactory.create(...)` als auch an `writer.openTable(...)`
  weiter
- `DataImportRunner` baut heute genau ein `ImportOptions` aus den
  CLI-Flags und reicht es in den Streaming-Pfad durch
- `DataTransferRunner` nutzt `ImportOptions` nur writer-seitig; der
  Transfer-Pfad liest direkt per `DataReader` aus der Quelle und nutzt
  keine Format-Reader
- `ImportOptionsFingerprint` ist bereits request-basiert und haengt
  nicht direkt an der `ImportOptions`-Klasse; das reduziert das Risiko
  fuer den Resume-Vertrag bei einer Typtrennung
- `adapters:driven:formats` haengt per `api(project(":hexagon:ports"))`
  an dem gesamten Portmodul und zieht damit heute die write-orientierten
  Typen transitiv mit

Wichtig fuer die Einordnung:

- `SchemaReadResult` ist zwar fuer reine Consumer oft breiter als
  noetig, aber laut Masterplan bewusst nicht der primaere Phase-C-
  Schnitt
- das eigentliche Problem ist aktuell nicht `SchemaReadResult`,
  sondern die Vermischung von read- und write-Vertraegen im
  Daten- und Formatpfad

Konsequenz:

- ein blosser Paketumzug reicht nicht
- die Kopplung laeuft fachlich ueber gemeinsam genutzte Vertragstypen,
  insbesondere ueber `ImportOptions`
- Phase C muss deshalb Portflaeche und Optionsmodelle zugleich
  schneiden

---

## 3. Scope fuer Phase C

### 3.1 In Scope (Kern)

Die folgenden Arbeitspunkte bilden den Scope-Kern und muessen fuer
den Abschluss von Phase C vollstaendig geliefert werden:

- Einfuehrung eines separaten read-orientierten Optionsmodells fuer
  Format-Reader
- Rueckschnitt von `ImportOptions` auf echten Import-/Writer-Vertrag
- Anpassung der folgenden read-orientierten Schnittstellen auf den
  neuen Optionsschnitt:
  - `DataChunkReaderFactory`
  - `CsvChunkReader`
  - `JsonChunkReader`
  - `YamlChunkReader`
- Anpassung der folgenden Import-/Streaming-Pfade auf getrennte
  Reader-/Writer-Optionen:
  - `StreamingImporter`
  - `DataImportRunner`
  - produktives CLI-Wiring rund um den Importpfad
- kompakte Nachzieh-Arbeit in angrenzenden Call-Sites, wenn dort
  bisher `ImportOptions` nur fuer read-orientierte Felder mitgefuehrt
  wurde
- Tests fuer:
  - Format-Reader
  - Streaming-Import
  - CLI-Wiring und Resume-Fingerprint-Vertrag
- Kover-Coverage bleibt pro betroffenem Modul bei dessen bestehendem
  Schwellenwert (z.B. 90 % fuer `formats`, 80 % fuer `cli`)

### 3.2 In Scope (Stretch Goal)

Die folgenden Arbeitspunkte vertiefen den Optionsschnitt auf
Build-Ebene. Sie sind wertvoll, aber nicht Voraussetzung fuer den
Abschluss von Phase C. Falls sie zu breiten Churn erzeugen, werden sie
als Phase C2 nachgezogen (Fallback-Kriterium 8.6):

- Inventarisierung der heutigen Porttypen in:
  - read-orientiert
  - write-orientiert
  - gemeinsam/noetige Basistypen
- expliziter Read-/Write-Modulschnitt im Portbereich:
  - read-orientierte Vertraege in einem eigenen Read-Port-Modul
    (Arbeitsname: `hexagon:ports-read`); haengt per `api` an
    `hexagon:ports-common`
  - write-/import-orientierte Vertraege in einem eigenen Write-Port-
    Modul (Arbeitsname: `hexagon:ports-write`); haengt per `api` an
    `hexagon:ports-common`
  - `adapters:driven:formats` ist selbst ein gemischtes Modul
    (Reader + Writer + SchemaCodec); es exponiert oeffentliche
    Writer-Klassen (`DefaultDataChunkWriterFactory`, `JsonChunkWriter`,
    `CsvChunkWriter`, `YamlChunkWriter`) deren Signaturen
    `DataChunkWriterFactory` und `ExportOptions` aus dem Write-Port
    benoetigen — `implementation(ports-write)` allein wuerde diese
    oeffentliche API brechen. Loesung: entweder `api(ports-write)`
    (verzichtet auf transitive Entkopplung fuer Writer-Consumer) oder
    ein echter Split des formats-Moduls in read-/write-Teilmodule.
    Der konkrete Zuschnitt wird erst bei Umsetzung des Stretch Goals
    festgelegt
  - `adapters:driven:streaming` benoetigt `api` auf
    `hexagon:ports-write`, weil `StreamingExporter` die Typen
    `DataChunkWriterFactory` und `ExportOptions` in seiner
    oeffentlichen API exponiert
- einen read-only Consumer-Fixture, der nur `hexagon:ports-read`
  plus `adapters:driven:formats` einbindet und prueft, dass keine
  write-orientierten Porttypen im transitiven Compile-Graph landen

### 3.3 Bewusst nicht Teil von Phase C

- neuer Endnutzervertrag fuer CLI oder Library-Consumer
- vorschnelle Aufspaltung von `SchemaReadResult`
- Aufspaltung von `adapters:driven:formats` in separate
  read-/write-Teilmodule (die transitive Entkopplung wird stattdessen
  ueber `api` vs. `implementation` Scope geloest)
- kompletter Neuaufbau von `DatabaseDriver`
- Profiling-Extraktion aus den Treiber-Kernmodulen
  (das bleibt Phase D)
- FK-/Topo-Sort-Extraktion (das bleibt Phase E)
- neuer oeffentlicher Publish-Vertrag fuer Library-Koordinaten

Praezisierung:

Phase C loest zuerst "wie wird der read-/write-gemischte Port- und
Optionsschnitt fachlich sauber getrennt?", nicht "wie entwerfen wir die
komplette Treiberflaeche neu?".

---

## 4. Leitentscheidungen fuer Phase C

### 4.1 Die Trennung muss fachlich sein, nicht nur paketkosmetisch

Verbindliche Entscheidung:

- Read-Pfade gelten erst dann als entkoppelt, wenn sie keine
  writer-spezifischen Vertragstypen mehr benoetigen
- ein blosses Verschieben von Klassen in andere Packages ohne
  Vertragsschnitt gilt nicht als ausreichend
- das Erfolgskriterium ist nicht "schickere Paketnamen", sondern
  reduzierte transitive Kopplung fuer read-only Consumer

Nicht zulaessig ist:

- `ImportOptions` umzubenennen, aber weiter unveraendert in Reader- und
  Writer-Pfaden zu nutzen
- read-only Code weiter an `DataWriter`- oder
  `TableImportSession`-Typen zu koppeln

### 4.2 Format-Reader bekommen ein eigenes read-orientiertes Optionsmodell

Verbindliche Entscheidung:

- die heutigen read-relevanten Felder
  `encoding`, `csvNoHeader` und `csvNullString` werden aus
  `ImportOptions` herausgeloest
- Format-Reader und `DataChunkReaderFactory` konsumieren danach ein
  eigenes read-orientiertes Optionsmodell
- dieses Modell lebt fachlich beim Format-Lesepfad, nicht im
  writer-orientierten Datenimportvertrag

Folge:

- `ImportOptions` wird kleiner und semantisch schaerfer
- spaetere reine Reader-Features landen nicht mehr automatisch in
  einem Writer-Datentyp

### 4.3 `FormatReadOptions` lebt im Portbereich

Verbindliche Entscheidung:

- das neue read-orientierte Optionsmodell (`FormatReadOptions`) wird im
  Portbereich definiert, nicht in `adapters:driven:formats`
- im Kern-Scope lebt der Typ in `hexagon:ports`
  (Package `dev.dmigrate.format.data`); bei Umsetzung des Stretch-
  Goal-Modulschnitts wandert er nach `hexagon:ports-read`
- damit bleibt der Vertrag portflaechig und kann von beliebigen
  read-orientierten Consumern konsumiert werden, ohne eine
  Adapter-Abhaengigkeit einzugehen

Nicht zulaessig ist:

- `FormatReadOptions` als adapter-internes Detail zu verstecken, weil
  dann externe Consumer (z.B. `d-browser`) den Typ nicht ohne
  Format-Adapter-Dependency nutzen koennten

### 4.4 Importausfuehrung arbeitet mit zwei expliziten Optionskanaelen

Verbindliche Entscheidung:

- `StreamingImporter` reicht nicht mehr dieselbe Optionsinstanz an
  Reader und Writer weiter
- Reader-Pfad und Writer-Pfad erhalten jeweils ihren eigenen,
  fachlich passenden Vertrag
- die CLI darf dieselben User-Flags weiterhin als Quelle fuer beide
  Vertragstypen verwenden, muss sie aber getrennt materialisieren

Wichtig:

- `truncate`, `triggerMode`, `onConflict`, `onError`,
  `disableFkChecks` und `reseedSequences` bleiben writer-seitig
- Resume-/Truncate-Guard bleibt an writer-seitigem Verhalten haengen

### 4.5 `hexagon:ports` bleibt als Aggregator-Modul erhalten

Verbindliche Entscheidung:

- `hexagon:ports` wird nicht geloescht, sondern bleibt als
  Aggregator-Modul bestehen, das `api`-Abhaengigkeiten auf
  `hexagon:ports-common`, `hexagon:ports-read` und
  `hexagon:ports-write` re-exportiert
- bestehende Consumer, die heute `project(":hexagon:ports")` nutzen,
  muessen dadurch nicht sofort umgestellt werden
- neue oder gezielt entkoppelte Consumer (z.B. `adapters:driven:formats`)
  binden sich direkt an das schmalere Modul

Begruendung:

- ein hartes Aufteilen ohne Aggregator wuerde alle 7 abhaengigen Module
  plus 2 Testmodule plus Root-Kover gleichzeitig anfassen und erzeugt
  dabei unvermeidlichen Churn, der nichts mit dem eigentlichen
  Optionsschnitt zu tun hat
- der Aggregator-Ansatz liefert denselben Entkopplungsnutzen fuer
  read-only Consumer, ohne den gesamten Build auf einmal umzupfluegen
- spaetere Phasen koennen den Aggregator sukzessive aufloesen, wenn
  alle Consumer explizit migriert sind

### 4.6 `DatabaseDriver` und `DatabaseDriverRegistry` verbleiben im Aggregator

Verbindliche Entscheidung:

- `DatabaseDriver` bleibt als bestehende gemischte Top-Level-
  Portflaeche vorerst erhalten, wenn dies breitflaechigen Churn
  vermeidet
- `DatabaseDriverRegistry` ist in seiner API direkt auf
  `DatabaseDriver` typisiert (`register(DatabaseDriver)`,
  `get(): DatabaseDriver`) und kann deshalb nicht in
  `hexagon:ports-common` leben, ohne die gemischte Treiberflaeche
  transitiv mitzuziehen
- beide Typen verbleiben daher im Aggregator-Modul `hexagon:ports`
- der Phase-C-Nutzen kommt primaer ueber kleinere read-orientierte
  Ports und den Optionsschnitt
- read-only Consumer sollen fuer den entkoppelten Pfad nicht auf
  `DatabaseDriver`, sondern auf den Read-Port-Zuschnitt bauen
- wenn ein schmalerer Read-Fassadentyp oder read-/write-spezifische
  Registries spaeter sinnvoll werden, ist das Folgearbeit und kein
  harter Blocker fuer Phase C

Folge:

- Phase C darf gezielt an den konkreten Read-/Write-Porttypen und ihren
  Abhaengigkeiten schneiden, ohne den gesamten Driver-Einstieg neu
  modellieren zu muessen

### 4.7 `SchemaReadResult` bleibt vorerst unveraendert

Verbindliche Entscheidung:

- Phase C fuehrt keinen zweiten schlanken `SchemaReader`-Vertrag ein
- `SchemaReadResult` bleibt der bestehende Reverse-/Diagnostics-
  Envelope
- reine Consumer projizieren ihn bei Bedarf im Adapterraum

Damit bleibt Phase C auf den tatsaechlichen Kopplungspunkt fokussiert.

### 4.8 Resume- und Fingerprint-Vertrag bleibt request-basiert stabil

Verbindliche Entscheidung:

- die Trennung von `ImportOptions` darf nicht dazu fuehren, dass
  `ImportOptionsFingerprint` semantisch neu eingefroren werden muss
- der Fingerprint bleibt auf Ebene des effektiven Requests definiert,
  nicht auf Ebene eines einzelnen DTO-Typs
- ein Refactor der internen Optionsobjekte darf deshalb keinen
  Manifest-Schema-Bump erzwingen

---

## 5. Konkrete Arbeitspakete

Abhaengigkeiten und Reihenfolge:

Kern (Pflicht):

1. **5.1** extrahiert den read-orientierten Optionsvertrag
2. **5.2** passt Streaming-/CLI-Wiring auf den doppelten Optionskanal
   an
3. **5.3** zieht Tests nach

Stretch Goal:

4. **5.4** zieht den fachlichen Zielzuschnitt fuer den Modulschnitt
   fest
5. **5.5** zieht Build-/API-Zuschnitt und Consumer-Fixture nach

### 5.1 Read-orientiertes Optionsmodell fuer Format-Reader extrahieren (Kern)

- ein separates Optionsmodell fuer den Format-Lesepfad einfuehren
  (Arbeitsname: `FormatReadOptions`)
- darin mindestens abbilden:
  - `encoding`
  - `csvNoHeader`
  - `csvNullString`
- `DataChunkReaderFactory.create(...)` auf dieses Modell umstellen
- `CsvChunkReader`, `JsonChunkReader` und `YamlChunkReader` auf das
  neue Modell umstellen
- `ImportOptions` auf writer-/import-orientierte Felder rueckschneiden
- KDoc und Defaults so nachziehen, dass wieder klar ist:
  - Reader-Verhalten lebt im Reader-Modell
  - Writer-/Import-Verhalten lebt in `ImportOptions`

Pragmatische Uebergangsregel:

- falls fuer inkrementelle Umstellung hilfreich, ist ein kleiner
  interner Adapter von altem auf neues Modell zulaessig
- Endzustand ist trotzdem: Format-Reader importieren kein
  `dev.dmigrate.driver.data.ImportOptions` mehr

Ergebnis:

Der eigentliche fachliche Kopplungspunkt ueber `ImportOptions` ist
aufgeloest.

### 5.2 Streaming- und CLI-Pfad auf getrennte Reader-/Writer-Optionen umstellen (Kern)

- `StreamingImporter.import(...)` so schneiden, dass Reader- und
  Writer-Pfad getrennte Optionen erhalten
- `readerFactory.create(...)` konsumiert danach nur noch das neue
  read-orientierte Modell
- `writer.openTable(...)` konsumiert weiterhin das verschlankte
  `ImportOptions`
- `DataImportRunner` baut aus demselben CLI-Request zwei getrennte
  Optionsobjekte:
  - read-orientiert fuer Format-Reader
  - write-orientiert fuer den Writer-/Importpfad
- Resume-/Truncate-Guard bleibt explizit an den writer-seitigen
  Optionen verankert
- `DataTransferRunner` wird nur so weit angepasst, wie Signaturen oder
  Imports dies erzwingen; fachlich bleibt sein Pfad writer-zentriert
- `ImportOptionsFingerprint` bleibt inhaltlich unveraendert und nutzt
  weiter die request-basierten Eingabefelder

Ergebnis:

Der Importlauf hat einen klaren Vertrag: Reader-Parsing und
Writer-Ausfuehrung sind konzeptionell und typseitig getrennt.

### 5.3 Tests nachziehen (Kern)

- alle betroffenen Tests nachziehen:
  - Format-Reader-Tests
  - `StreamingImporterTest`
  - `StreamingImporterSqliteTest`
  - `DataImportRunnerTest` unter `adapters/driving/cli/src/test/...`
  - bestehende `ImportOptionsFingerprint`-Tests unter
    `adapters/driving/cli/src/test/.../DataImportRunnerTest.kt`
  - ggf. compile-/wiring-nahe Tests rund um `DataTransferRunner`

Ergebnis:

Der Optionsschnitt ist testabgesichert; Phase C ist mit 5.1–5.3
abschliessbar.

### 5.4 Portinventur und Zielzuschnitt festziehen (Stretch Goal)

- alle Porttypen in sechs Gruppen klassifizieren:
  - read-orientiert
  - write-orientiert
  - gemeinsame Basistypen
  - gemischt (Aggregator)
  - streaming-orientiert
  - migration-orientiert
- den Zielzuschnitt fuer alle Porttypen festhalten:
  - `hexagon:ports-read`:
    `SchemaReader`, `SchemaReadOptions`, `SchemaReadResult`,
    `SchemaReadNote`, `SchemaReadReportInput`,
    `DataReader`, `ChunkSequence`, `ResumeMarker`,
    `TableLister`, `DataChunkReader`, `DataChunkReaderFactory`,
    `DdlGenerator`, `DdlGenerationOptions`, `DdlResult`,
    `DdlStatement`, `TransformationNote`, `NoteType`,
    `SkippedObject`
  - `hexagon:ports-write`:
    `DataWriter`, `TableImportSession`, `ImportOptions`,
    `SchemaSync`, `FinishTableResult`, `WriteResult`,
    `TargetColumn`, `SequenceAdjustment`,
    `UnsupportedTriggerModeException`,
    `DataChunkWriter`, `DataChunkWriterFactory`, `ExportOptions`
  - gemeinsam (`hexagon:ports-common`):
    `DatabaseDialect`,
    `ConnectionPool`, `ConnectionConfig`, `JdbcUrlBuilder`,
    `PoolSettings`, `DataExportFormat`, `TypeMapper`,
    `SqlIdentifiers`, `SchemaCodec`
  - gemischt (verbleiben im Aggregator `hexagon:ports`):
    `DatabaseDriver`, `DatabaseDriverRegistry` — beide sind
    strukturell auf die gemischte Treiberflaeche typisiert und
    koennen ohne Driver-Redesign keinem der Teilmodule zugeordnet
    werden
  - streaming-orientiert (verbleiben in `hexagon:ports` oder eigenem
    Streaming-Portbereich):
    `ImportInput`, `ImportResult`, `ExportOutput`, `ExportResult`,
    `PipelineConfig`, `CheckpointConfig`, `CheckpointStore`,
    `CheckpointManifest`, `ProgressEvent`, `ProgressOperation`,
    `TableProgressStatus`, `ProgressReporter`,
    `NoOpProgressReporter`
  - migration-orientiert (verbleiben in `hexagon:ports`):
    `MigrationBundle`, `MigrationIdentity`, `MigrationTypes`,
    `ArtifactRelativePath`, `ToolMigrationExporter`,
    `ToolExportResult`

Ergebnis:

Ein dokumentierter Zielzuschnitt, gegen den der eigentliche
Modulschnitt geprueft werden kann.

### 5.5 Build-/API-Zuschnitt und Consumer-Fixture nachziehen (Stretch Goal)

- neue Gradle-Module `hexagon:ports-common`, `hexagon:ports-read` und
  `hexagon:ports-write` anlegen; `hexagon:ports` bleibt als Aggregator
  (Leitentscheidung 4.5)
- interne Modulabhaengigkeiten der neuen Portmodule:
  - `hexagon:ports-read` → `api(project(":hexagon:ports-common"))`,
    weil Read-Ports in ihrer API Common-Typen verwenden
    (`DataChunkReaderFactory` → `DataExportFormat`,
    `DataReader` → `ConnectionPool`/`DatabaseDialect`,
    `SchemaReader` → `DatabaseDialect`)
  - `hexagon:ports-write` → `api(project(":hexagon:ports-common"))`,
    weil Write-Ports dieselben Common-Typen verwenden
    (`DataWriter` → `ConnectionPool`/`DatabaseDialect`)
  - `hexagon:ports` (Aggregator) → `api` auf alle drei Teilmodule
- `adapters:driven:formats` von `api(project(":hexagon:ports"))` auf
  `api(project(":hexagon:ports-read"))` plus
  `implementation(project(":hexagon:ports-write"))` umstellen, damit
  read-only Consumer die write-orientierten Porttypen nicht transitiv
  sehen
- `adapters:driven:streaming` benoetigt `api` auf
  `hexagon:ports-write`, weil `StreamingExporter` die Typen
  `DataChunkWriterFactory` und `ExportOptions` in seiner
  oeffentlichen API exponiert; read-orientierte Porttypen kommen
  transitiv ueber `adapters:driven:driver-common` und
  `adapters:driven:formats`
- einen kleinen read-only Consumer-Fixture anlegen, der nur gegen
  `hexagon:ports-read` plus `adapters:driven:formats` baut und
  prueft, dass `hexagon:ports-write` nicht transitiv im Compile-Graph
  landet
- Doku nachziehen, wo der neue Options- und Portschnitt sichtbar
  beschrieben wird:
  - `docs/implementation-plan-0.9.1.md`
  - `docs/architecture.md`
  - `docs/hexagonal-port.md`

Ergebnis:

Der Refactor ist auch in Build, Tests und Architekturtext konsistent.

---

## 6. Verifikation

Pflichtfaelle (Kern):

- Unit-Tests fuer das neue read-orientierte Reader-Optionsmodell
- Reader-Tests, die zeigen, dass CSV-/JSON-/YAML-Verhalten mit den
  neuen Optionen unveraendert bleibt
- `StreamingImporter`-Tests fuer:
  - Reader-/Writer-Optionen getrennt verdrahtet
  - Truncate-Guard und Resume weiter stabil
  - `onError`-/`onConflict`-/Trigger-Pfade unveraendert writer-seitig
- `DataImportRunner`-Tests fuer:
  - getrennte Erzeugung der beiden Optionsobjekte
  - unveraenderte CLI-Validierung
  - unveraenderter Fingerprint fuer identische Requests
- neue `ImportOptionsFingerprint`-Tests, die bytegleiches Verhalten
  fuer identische Requests und unveraenderte Null-/Tabellen-/Pfad-
  Kodierung absichern

Pflichtfaelle (Stretch Goal, nur bei Modulschnitt):

- ein read-only Consumer-Fixture, der:
  - nur `hexagon:ports-read` plus `adapters:driven:formats` einbindet
  - gegen den neuen Schnitt kompiliert
  - keinen transitiven Compile-Graph auf `hexagon:ports-write` hat
- Build-/Abhaengigkeitspruefung, dass `adapters:driven:formats` die
  write-orientierten Porttypen nur per `implementation`, nicht per
  `api` einbindet

Erwuenschte Zusatzfaelle:

- gezielte Compile-/Regressionstests fuer `DataTransferRunner`, damit
  der writer-zentrierte Direkttransfer durch den Optionsschnitt nicht
  versehentlich regressiert
- ein schlanker Smoke-Test fuer den produktiven Import-Wiring-Pfad

---

## 7. Betroffene Codebasis

Direkt betroffen:

- `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/ImportOptions.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/format/data/DataChunkReaderFactory.kt`
- neue read-orientierte Optionsdatei im Format-/Read-Bereich
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/DefaultDataChunkReaderFactory.kt`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/csv/CsvChunkReader.kt`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/json/JsonChunkReader.kt`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/yaml/YamlChunkReader.kt`
- `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingImporter.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataImportRunner.kt`

Wahrscheinlich mit betroffen:

- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/ImportOptionsFingerprint.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataTransferRunner.kt`
- neue Moduldefinitionen und Source-Sets fuer:
  - `hexagon:ports-common`
  - `hexagon:ports-read`
  - `hexagon:ports-write`
- Testdateien in:
  - `adapters/driven/formats/src/test/...`
  - `adapters/driven/streaming/src/test/...`
  - `adapters/driving/cli/src/test/...` fuer `DataImportRunnerTest`
    (inkl. `ImportOptionsFingerprint`-Abdeckung)
- `settings.gradle.kts` und betroffene `build.gradle.kts` fuer den
  geplanten Read-/Write-Modulschnitt

Dokumentation:

- `docs/implementation-plan-0.9.1.md`
- `docs/architecture.md`
- `docs/hexagonal-port.md`

---

## 8. Risiken und offene Punkte

### 8.1 Ein oberflaechlicher DTO-Schnitt wuerde die echte Kopplung nur verdecken

Wenn nur neue Typnamen entstehen, aber `StreamingImporter` intern weiter
eine gemischte Optionswelt traegt, ist der Refactor nur kosmetisch.

Mitigation:

- Reader- und Writer-Pfad getrennt in Signaturen sichtbar machen
- Erfolg am Import- und Build-Schnitt messen, nicht am Dateinamen

### 8.2 `DatabaseDriver` bleibt zunaechst gemischt und kann den Eindruck eines halben Refactors erzeugen

Auch nach Phase C kann die grobe Driver-Fassade read- und write-lastige
Methoden zugleich exponieren.

Mitigation:

- Phase-C-Ziel klar auf kleinere Porttypen und den Optionsschnitt
  fokussieren
- `DatabaseDriver` bewusst als bestaehende gemischte Top-Level-
  Portflaeche dokumentieren

### 8.3 Resume-/Fingerprint-Vertrag koennte versehentlich mitveraendert werden

Wenn die Typtrennung auf den falschen Abstraktionslevel gezogen wird,
drohen unbemerkte Fingerprint-Aenderungen oder Manifest-Inkompatibilitaet.

Mitigation:

- `ImportOptionsFingerprint` explizit request-basiert belassen
- bestehende Resume-Tests als Pflichtsicherheitsnetz behalten

### 8.4 Build- und API-Zuschnitt erzeugt breiten Churn

Der geplante Read-/Write-Modulschnitt betrifft konkret diese Module,
die heute alle gegen `project(":hexagon:ports")` bauen:

- `hexagon:application` — `implementation`
- `hexagon:profiling` — `implementation`
- `adapters:driven:driver-common` — `api`
- `adapters:driven:formats` — `api`
- `adapters:driven:integrations` — `api`
- `adapters:driven:streaming` — (transitiv ueber `driver-common`)
- `test:integration-postgresql` — `testImplementation`
- `test:integration-mysql` — `testImplementation`
- Root `build.gradle.kts` — Kover-Aggregation

Ohne Aggregator-Ansatz (Leitentscheidung 4.5) wuerde jedes dieser
Module gleichzeitig angepasst werden muessen. Der Aggregator begrenzt
den Pflicht-Churn auf die zwei Module, die gezielt entkoppelt werden
sollen.

Mitigation:

- `hexagon:ports` bleibt als Aggregator (Leitentscheidung 4.5)
- im Stretch Goal werden genau zwei Adaptermodule direkt angefasst:
  - `adapters:driven:formats` → `api(ports-read)` +
    `implementation(ports-write)`
  - `adapters:driven:streaming` → `api(ports-write)` (weil
    `StreamingExporter` write-orientierte Porttypen exponiert)
- compile-nahe Tests und betroffene Build-Dateien frueh mitziehen
- weitere Module werden erst in Folgephasen sukzessive migriert

### 8.5 Ohne Consumer-Fixture bleibt der Integrationsnutzen unbelegt

Interne Unit-Tests allein zeigen noch nicht, dass ein externer
Read-Consumer den schmaleren Schnitt tatsaechlich konsumieren kann.

Mitigation:

- einen dedizierten read-only Consumer-Fixture als Bestandteil des
  Stretch-Goal-Modulschnitts (5.5) einfuehren
- fuer diesen Fixture Compile-Graph und Importoberflaeche explizit
  pruefen

### 8.6 Fallback-Kriterium fuer den Modulschnitt (Stretch Goal)

Der Modulschnitt (5.4 + 5.5) ist als Stretch Goal definiert. Falls er
waehrend der Umsetzung unvermeidlich breiteren Churn erzeugt als durch
den Aggregator-Ansatz gedeckt, gilt:

- Phase C ist mit dem Kern-Optionsschnitt (5.1–5.3) abgeschlossen:
  Format-Reader konsumieren dann kein `ImportOptions` mehr, und der
  Streaming-Pfad hat getrennte Optionskanaele
- der Modulschnitt (`hexagon:ports-read`, `hexagon:ports-write`) wird
  dann als Phase C2 oder als Vorarbeit zu Phase D nachgezogen
- Abbruch-Signal: wenn die Modulaufteilung mehr als drei nicht direkt
  betroffene Module gleichzeitig anfassen muss, ist der
  Aggregator-Ansatz nicht ausreichend und der Modulschnitt sollte
  zurueckgestellt werden

---

## 9. Entscheidungsempfehlung

Phase C sollte in 0.9.1 umgesetzt werden, weil sie einen realen
Integrationsknoten loest, der fuer externe Read-Consumer heute
unnuetigen Ballast erzeugt.

Empfohlener Zuschnitt:

Kern (Pflicht):

1. `FormatReadOptions` im Portbereich einfuehren (Leitentscheidung 4.3)
2. `ImportOptions` auf Writer-/Import-Vertrag rueckschneiden
3. `StreamingImporter` und `DataImportRunner` auf zwei explizite
   Optionskanaele umstellen
4. Tests fuer Optionsschnitt nachziehen

Stretch Goal:

5. `hexagon:ports` als Aggregator erhalten (Leitentscheidung 4.5);
   `hexagon:ports-read` und `hexagon:ports-write` als neue Module
   einfuehren
6. `adapters:driven:formats` auf `api(ports-read)` +
   `implementation(ports-write)` umstellen
7. `adapters:driven:streaming` auf `api(ports-write)` umstellen,
   weil `StreamingExporter` write-orientierte Porttypen exponiert
8. den neuen Schnitt ueber einen read-only Consumer-Fixture
   buildseitig verifizieren

Phase C ist mit den Schritten 1–4 abschliessbar. Die Schritte 5–8
vertiefen den Schnitt auf Build-Ebene und koennen bei zu breitem
Churn als Phase C2 nachgezogen werden (Fallback-Kriterium 8.6).

Damit liefert Phase C einen fachlich sauberen Optionsschnitt, ohne
bereits `SchemaReadResult`, die gesamte `DatabaseDriver`-Fassade
oder `adapters:driven:formats` selbst neu zu entwerfen.
