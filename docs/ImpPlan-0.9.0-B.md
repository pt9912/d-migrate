# Implementierungsplan: Phase B - Checkpoint-Port, Manifest und Konfiguration

> **Milestone**: 0.9.0 - Beta: Resilienz und vollstaendige i18n-CLI
> **Phase**: B (Checkpoint-Port, Manifest und Konfiguration)
> **Status**: Implemented (2026-04-16) — Checkpoint-Port, versioniertes
> Manifest, dateibasierter Adapter mit atomarem Schreibpfad,
> `PipelineConfig`-Erweiterung um `CheckpointConfig`, zentraler
> Merge-Helper (als Helper + Tests), `operationId` als optionales Feld
> in `ProgressEvent.RunStarted`, `ExportResult` und `ImportResult`;
> `StreamingExporter`/`StreamingImporter` setzen die ID in das
> `RunStarted`-Event, wenn sie der Aufrufer mitgibt; Runner erzeugen pro
> Lauf eine UUID und zeigen sie in der stderr-Summary. Vertragstests fuer
> Manifest, Adapter und Merge-Helper. **Bewusst nicht Teil von Phase B**
> (Phase C/D): Runner-Aufruf von `CheckpointConfig.merge(...)`, Nutzung
> von `checkpointDir` aus dem Request, Durchreichen der `operationId`
> durch `ExportExecutor`/`ImportExecutor`, Anzeige im `ProgressRenderer`,
> Manifest-Fortschreibung waehrend des Streams, Wiederaufnahme.
> **Referenz**: `docs/implementation-plan-0.9.0.md` Abschnitt 2 bis 6.2,
> Abschnitt 8.1 bis 8.3; `docs/ImpPlan-0.9.0-A.md`; `docs/design.md`
> Abschnitt 3.2; `docs/architecture.md` Abschnitt 3.3;
> `docs/connection-config-spec.md` (`pipeline.checkpoint.*`);
> `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/PipelineConfig.kt`;
> `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ProgressEvent.kt`;
> `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ExportResult.kt`;
> `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ImportResult.kt`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataImportRunner.kt`

---

## 1. Ziel

Phase B schafft fuer 0.9.0 den technischen Unterbau, auf dem Export- und
Import-Resume spaeter ueberhaupt erst aufsetzen koennen.

Der Teilplan beantwortet bewusst die Infrastrukturfragen zwischen CLI-Vertrag
und Streaming-Implementierung:

- wie ein expliziter Checkpoint-Port im Hexagon aussieht
- welches persistierte Manifestformat Export und Import gemeinsam nutzen
- wie `PipelineConfig` von reinem `chunkSize` auf einen kleinen
  Resilienz-Vertrag erweitert wird
- wie `operationId` und Resume-Metadaten in Progress- und Result-Pfaden
  sichtbar werden (Typvertrag in Phase B; das Durchreichen bis in
  `ProgressEvent.RunStarted` bleibt Phase C/D, siehe §2.2)
- wie dieselbe `operationId` in der nachgelagerten stderr-Summary
  referenzierbar wird
- wie sichtbare CLI-Overrides aus Phase A mit
  `pipeline.checkpoint.*` ueber einen zentralen Merge-Helper
  zusammengefuehrt werden (Aufruf im Runner folgt in Phase C/D)

Phase B liefert damit noch keine vollstaendige Wiederaufnahme im Datenpfad.
Sie schafft aber den gemeinsamen Vertrag, ohne den Phasen C und D entweder
duplizierte Resume-Logik oder widerspruechliche Manifestmodelle bauen
wuerden.

Nach Phase B soll klar und testbar gelten:

- es gibt einen expliziten Checkpoint-Port statt impliziter Dateizugriffe
- es gibt ein versioniertes, serialisierbares Manifest fuer Export und Import
- `PipelineConfig` traegt Checkpoint-Einstellungen als expliziten Typ
  (`CheckpointConfig`); die Verdrahtung im Export-/Import-Runner folgt in
  Phase C/D zusammen mit der Resume-Runtime
- fuer CLI-/Config-Werte existiert ein zentraler Merge-Helper
  (`CheckpointConfig.merge`), der in Phase C/D von beiden Runnern identisch
  aufgerufen wird — Phase B liefert den Helper und seine Tests, noch nicht
  den Runner-Aufruf
- Progress- und Result-Typen koennen einen Lauf stabil ueber
  `operationId` referenzieren
- dieselbe `operationId` ist in der nachgelagerten stderr-Summary
  verfuegbar; die Durchreichung bis in `ProgressEvent.RunStarted` erfolgt
  in Phase C/D (Executor-Signatur-Aenderung)

---

## 2. Ausgangslage

### 2.1 Stand **vor** Phase B

Aktueller Stand im Repo (vor Phase B):

- `docs/design.md` enthaelt mit `MigrationCheckpoint` bereits eine
  Konzeptklasse fuer Resume, die im Produktionscode aber noch nicht
  existiert.
- `docs/architecture.md` skizziert einen `CheckpointStore` in einer
  allgemeinen Streaming-Pipeline, ohne dass dieser Port derzeit im
  Hexagon vorhanden waere.
- `docs/connection-config-spec.md` dokumentiert bereits:
  - `pipeline.checkpoint.enabled`
  - `pipeline.checkpoint.interval`
  - `pipeline.checkpoint.directory`
  obwohl diese Konfiguration zur Laufzeit heute noch nicht wirksam ist.
- `PipelineConfig` transportiert aktuell nur `chunkSize`.
- `ProgressEvent`, `ExportResult` und `ImportResult` kennen derzeit weder
  `operationId` noch Resume-Metadaten.
- `DataExportRunner` und `DataImportRunner` instanziieren `PipelineConfig`
  heute direkt nur mit `chunkSize`.
- Export- und Import-Pfade besitzen damit zwar bereits einen stabilen
  Streaming-Kern, aber noch keinen gemeinsamen Resilienz-Vertrag.

Konsequenz:

- der Repo-Stand besitzt eine dokumentierte Zielidee, aber keinen
  produktiven Checkpoint-Unterbau
- ohne Phase B wuerden Phasen C und D entweder auf informellen Maps/Dateien
  arbeiten oder denselben Vertrag doppelt erfinden
- besonders kritisch ist die vorlaufende Config-Spec:
  sie muss in 0.9.0 von einer Skizze zu einem tatsaechlichen Runtime-Vertrag
  werden, inklusive einer expliziten Entscheidung zur Abbildung zwischen dem
  bestehenden Key `pipeline.checkpoint.interval` und dem neuen
  row-/time-basierten Laufzeitmodell

### 2.2 Stand **nach** Phase B

Mit Abschluss der Phase (Status „Implemented", 2026-04-16) gilt:

- neuer expliziter Checkpoint-Port in `hexagon:ports`:
  [`CheckpointStore`](../hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/checkpoint/CheckpointStore.kt)
  mit `load`/`save`/`list`/`complete` sowie `CheckpointStoreException`
  und `UnsupportedCheckpointVersionException`.
- versioniertes, persistierbares Manifest-Grundmodell
  [`CheckpointManifest`](../hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/checkpoint/CheckpointManifest.kt)
  mit `schemaVersion` (`CURRENT_SCHEMA_VERSION = 1`), `operationId`,
  `operationType`, `createdAt`/`updatedAt`, `format`, `chunkSize`,
  `tableSlices` (`CheckpointTableSlice`) und `optionsFingerprint`.
  `operationSpecific` ist der Erweiterungspunkt fuer Phase C/D (`sealed
  interface CheckpointOperationSpecifics`).
- dateibasierter Erstadapter
  [`FileCheckpointStore`](../adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/checkpoint/FileCheckpointStore.kt)
  mit atomarem `temp -> ATOMIC_MOVE`-Schreibpfad (§4.6), YAML-
  Serialisierung, toleranter `list()` und klaren Fehlern fuer korrupte
  oder inkompatibel versionierte Manifeste.
- `PipelineConfig` um [`CheckpointConfig`](../hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/PipelineConfig.kt)
  erweitert (enabled, rowInterval, maxInterval, directory).
  Name-Mapping zwischen Config-Oberflaeche und Runtime ist explizit:
  `pipeline.checkpoint.interval` -> `rowInterval`;
  neuer Key `pipeline.checkpoint.max_interval` -> `maxInterval`
  (ISO-8601-Duration, Default `PT5M`).
- zentraler Merge-Helper `CheckpointConfig.merge(cliDirectory, config)`
  liefert CLI-Override > Config > Default einheitlich und ist durch
  `PipelineConfigTest` abgesichert. **Noch nicht umgesetzt**: beide Runner
  bauen `PipelineConfig` weiterhin mit `PipelineConfig(chunkSize = ...)`;
  der CLI-Wert `DataExportRequest.checkpointDir` bzw.
  `DataImportRequest.checkpointDir` wird im Request bereits getragen
  (Phase A), im Runner aber noch nicht in den Merge-Aufruf gezogen. Das
  Wiring passiert zusammen mit dem Resume-Runtime-Flow in Phase C/D.
- `ProgressEvent.RunStarted`, `ExportResult` und `ImportResult` tragen
  ein optionales `operationId`-Feld (§4.5). Der Parameter ist fuer
  Pre-Phase-B-Callsites als `null` zulaessig, damit Bestandstests ohne
  Churn bleiben.
- `StreamingExporter.export` und `StreamingImporter.import` nehmen ein
  optionales `operationId` entgegen und setzen es sowohl in das von ihnen
  emittierte `RunStarted`-Event als auch in den zurueckgegebenen Result.
  Bestandstests uebergeben `null` und bleiben unveraendert.
- Beide Runner (`DataExportRunner`, `DataImportRunner`) erzeugen eine
  stabile `UUID`-basierte `operationId` pro Lauf, reichern das Result
  per `.copy(operationId = ...)` an und emittieren die ID in der
  nachgelagerten stderr-Summary (`Run operation id: ...`).
- **Noch offen (Phase C/D)**: `ExportExecutor` und `ImportExecutor`
  haben aktuell keinen `operationId`-Parameter, deshalb gibt der Runner
  die ID heute **nicht** an den Streaming-Pfad weiter. Das
  `RunStarted`-Event traegt die ID damit nur dann, wenn der Aufrufer
  von `StreamingExporter.export`/`StreamingImporter.import` sie
  explizit setzt — im CLI-Pfad ist das erst nach Executor-Signatur-
  Aenderung in Phase C/D der Fall. Zusaetzlich fehlt die Renderer-
  Darstellung im `ProgressRenderer`.
- Vertragstests: `CheckpointManifestTest`, `FileCheckpointStoreTest`
  (Roundtrip, atomaren Schreibpfad, Versionsablehnung, korrupte Dateien,
  tolerantes `list()`, `complete()`), erweiterter `PipelineConfigTest`
  (Default-LN-012-Werte, Validierung, Merge-Vertrag).
- Doku: `docs/connection-config-spec.md` dokumentiert
  `pipeline.checkpoint.interval` + neuen `max_interval`-Key; Phase-A-
  Plan und Masterplan verweisen weiterhin auf denselben
  CLI-/Config-Merge-Vertrag.

**Bewusst noch nicht umgesetzt** (Phasen C/D):

- Runner-seitiger Aufruf von `CheckpointConfig.merge(...)` — beide Runner
  instanziieren `PipelineConfig` derzeit nur mit `chunkSize`, und der
  Request-Wert `checkpointDir` bleibt ungenutzt, bis Phase C/D die
  Resume-Runtime anschliesst
- Schreiben des Manifests waehrend des Streams
- tatsaechliche Wiederaufnahme aus einem vorhandenen Manifest
- CLI-seitiges Durchreichen der `operationId` vom Runner in den
  Streaming-Pfad: `ExportExecutor`/`ImportExecutor` haben den Parameter
  noch nicht, deshalb landet die ID heute nicht im live emittierten
  `RunStarted`-Event (obwohl der Typ und die Streaming-Seite sie
  akzeptieren)
- Darstellung der `operationId` im `ProgressRenderer`
- Signal-Handling (SIGINT/SIGTERM) und Retry-Integration

---

## 3. Scope fuer Phase B

### 3.1 In Scope

- Einfuehrung eines expliziten Checkpoint-Port-Vertrags im Hexagon
- Definition eines versionierten, persistierbaren Manifestmodells fuer
  Export- und Import-Laeufe
- Einfuehrung eines dateibasierten Checkpoint-Store-Adapters fuer 0.9.0
- Erweiterung von `PipelineConfig` um produktive Checkpoint-Einstellungen
- zentraler Merge-Vertrag fuer:
  - CLI-Override(s) aus Phase A
  - `pipeline.checkpoint.*` aus der Config
  - Runtime-Defaults gemaess LN-012
- Verankerung von `operationId` in Progress-/Result-Typen
- Verankerung minimaler Resume-Metadaten in den Rueckgabepfaden
- Tests fuer Port, Manifest, Config-Merge und Dateiadapter

### 3.2 Bewusst nicht Teil von Phase B

- treiberspezifische Export-Resume-Markerlogik
- importseitige Resume-Fortsetzung ueber committed Chunks
- Signalbehandlung bei SIGINT/SIGTERM
- Retry-Orchestrierung
- Parallelisierung oder partition-aware Checkpoints
- Resume fuer `data transfer`
- vollstaendige CLI-Fehlermeldungstexte und Nutzerfuehrung

Praezisierung:

Phase B loest "welcher gemeinsame Vertrag traegt Checkpointing?", nicht
"wie setzen Export und Import diesen Vertrag spaeter konkret um?".

---

## 4. Leitentscheidungen fuer Phase B

### 4.1 Der Checkpoint-Vertrag beginnt als Port, nicht als Dateischema

Phase B fuehrt keinen Dateiadapter als versteckten De-facto-Standard ein,
sondern zuerst einen expliziten Port.

Verbindliche Folge:

- das Hexagon bekommt einen klaren `CheckpointStore`- oder gleichwertigen
  Port fuer:
  - Laden
  - Speichern
  - Auflisten/Finden
  - Abschliessen/Archivieren oder Entfernen
- der dateibasierte Adapter ist in 0.9.0 nur die erste konkrete Umsetzung

Nicht zulaessig ist:

- Dateizugriffe aus Runnern oder Streaming-Klassen direkt zu verteilen
- Manifest-Serialisierung als implizites Detail eines einzelnen Datenpfads
  zu verstecken

### 4.2 Das Manifest ist versioniert und operationstypisch erweiterbar

Phase B fixiert fuer das persistierte Manifest mindestens:

- `schemaVersion`
- `operationId`
- `operationType` (`export` oder `import`)
- Zeitstempel fuer Erstellung und letzte Fortschreibung
- zentrale Laufmetadaten:
  - relevante Tabellen bzw. Input-Slices
  - Format
  - `chunkSize`
  - Resume-relevante Optionen/Fingerprints
- per Tabelle/Input-Slice einen serialisierbaren Resume-Status

Verbindliche Folge:

- das Manifest darf nicht nur aus einem unversionierten
  `lastProcessedKey`-Blob bestehen
- export- und importseitige Spezifika muessen erweiterbar sein, ohne das
  Grundmodell bei jedem Milestone neu aufzureissen

### 4.3 `PipelineConfig` wird klein erweitert, nicht zum Sammelbehaelter

Phase B erweitert `PipelineConfig` produktiv, aber bewusst eng:

- `chunkSize`
- `checkpoint.enabled`
- `checkpoint.rowInterval`
- `checkpoint.maxInterval`
- `checkpoint.directory`

Nicht Bestandteil dieser Phase bleiben:

- Retry-Max-Attempts
- Backoff-Parameter
- Parallelism

Verbindliche Folge:

- die vorlaufende Config-Spec fuer `retry` und `parallelism` darf Phase B
  nicht dazu verleiten, 0.9.0 unnoetig zu verbreitern
- `PipelineConfig` bleibt ein fokussierter Laufzeitvertrag fuer den
  Streaming-/Resume-Pfad

Explizite Namensentscheidung fuer 0.9.0:

- auf der Config-Oberflaeche bleibt der bestehende Key
  `pipeline.checkpoint.interval` der kanonische row-basierte Trigger
- im Runtime-Typ darf dieser Wert intern als `rowInterval` modelliert werden,
  wenn die Abbildung zentral und eindeutig ist
- fuer den zusaetzlichen Zeit-Trigger wird ein eigener zweiter Key in der
  Config-Spec eingefuehrt; Phase B muss dessen finalen Namen explizit
  festziehen und dokumentieren, statt `interval`/`rowInterval`/Zeit-Trigger
  halb parallel stehen zu lassen

### 4.4 CLI-Override und Config-Default muessen zentral gemerged werden

Aus Phase A folgt fuer Phase B:

- sichtbare CLI-Resume-Optionen wie `--checkpoint-dir` duerfen nicht neben
  `pipeline.checkpoint.directory` herlaufen
- der Merge-Vertrag muss zentral und fuer Export/Import identisch sein

Arbeitsvertrag fuer 0.9.0:

- CLI-Override sticht Config
- Config sticht Runtime-Default
- LN-012-Defaultwerte fuer row-/time-trigger bleiben auch ohne Config
  nachvollziehbar und testbar

Nicht zulaessig ist:

- den Merge pro Runner unterschiedlich zu implementieren
- `--checkpoint-dir` nur in einem Command zu ehren
- Defaults still aus der Doku zu implizieren, statt sie in einem echten
  Runtime-Typ zu materialisieren

### 4.5 `operationId` gehoert in Progress-, Result- und stderr-Pfade

Phase B fixiert:

- ein Lauf ist ueber eine stabile `operationId` referenzierbar
- diese ID muss sowohl in Progress-Events als auch in Resultaten
  verfuegbar sein
- die ID muss in den stderr-nahen Progress-/Summary-Pfaden sichtbar oder
  jedenfalls ohne Informationsverlust durchreichbar sein
- die ID ist Teil des Resume-Vertrags, nicht nur Logging-Dekoration

Verbindliche Folge:

- `ProgressEvent.RunStarted` reicht ohne `operationId` nicht mehr aus
- `ExportResult` und `ImportResult` brauchen mindestens eine minimale
  Rueckmeldung darueber, auf welche Operation bzw. welchen Resume-Zustand
  sie sich beziehen
- `ProgressRenderer` sowie die bestehenden Summary-Helfer duerfen die neue
  Referenz nicht unterwegs verlieren
- wenn ein Lauf heute bereits auf `stderr` zusammengefasst wird, muss dort
  dieselbe `operationId` referenzierbar bleiben wie in Events und Resultaten

### 4.6 Dateispeicherung erfolgt atomar oder explizit als fehlgeschlagen

Da Phase B einen dateibasierten Adapter einzieht, muss der Dateivertrag
selbst robust sein.

Phase B fixiert:

- Checkpoint-Dateien/Manifeste werden atomar ersetzt oder als fehlgeschlagen
  behandelt
- partielle Ueberschreibungen duerfen nicht als gueltige Resume-Quelle
  durchrutschen
- Dateinamen, Verzeichnislayout und Lebenszyklus muessen fuer Support und
  Tests nachvollziehbar bleiben

Nicht festgezogen wird in Phase B:

- das endgueltige Housekeeping abgeschlossener Checkpoints
- ein spaeteres Archivierungs- oder GC-Modell

---

## 5. Geplante Arbeitspakete

### 5.1 B1 - Checkpoint-Domaenenmodell und Port definieren

- expliziten Checkpoint-Port im Hexagon einfuehren
- gemeinsames Manifest-Grundmodell fuer Export und Import definieren
- per-Slice/Tabellen-Status als serialisierbare Teilstruktur festlegen
- Versionierungsfeld und Kompatibilitaetsanker festziehen

### 5.2 B2 - `PipelineConfig` und Merge-Helper erweitern

- `PipelineConfig` um Checkpoint-Konfiguration erweitern
- zentralen Merge-Helper zwischen CLI, Config und Defaults **als
  Helper + Tests** definieren — der Runner-seitige Aufruf folgt zusammen
  mit dem Resume-Runtime-Wiring in Phase C/D (siehe §2.2 und
  "Noch offen")
- Beziehung zu `pipeline.checkpoint.directory` klar modellieren
- kanonische Abbildung zwischen Config-Key `pipeline.checkpoint.interval`
  und internem row-basiertem Trigger festziehen
- finalen Config-Key fuer den Zeit-Trigger festlegen und dokumentieren
- `PipelineConfig(chunkSize = ...)`-Instanziierungen in den Runnern
  werden in Phase B bewusst **nicht** umgestellt; das erfolgt erst bei
  der Runner-Verdrahtung in Phase C/D

### 5.3 B3 - Dateibasierten Checkpoint-Store-Adapter bauen

- dateibasierten Adapter fuer Laden/Speichern/Finden/Abschliessen einfuehren
- Manifest-Serialisierung und Dateilayout definieren
- atomaren Schreibpfad und Fehlermodell festziehen

### 5.4 B4 - Progress- und Result-Vertraege erweitern

- `operationId` als optionales Feld in `ProgressEvent.RunStarted`,
  `ExportResult` und `ImportResult` verankern
- `StreamingExporter.export`/`StreamingImporter.import` akzeptieren die
  ID und setzen sie in `RunStarted` und Result, wenn der Aufrufer sie
  mitgibt
- Runner erzeugen pro Lauf eine stabile UUID, haengen sie per `.copy()`
  an das Result an und emittieren sie in der nachgelagerten
  stderr-Summary
- Executor-Seam (`ExportExecutor`/`ImportExecutor`) und
  `ProgressRenderer`-Anzeige bleiben in Phase B bewusst unveraendert —
  das CLI-seitige Durchreichen bis in das live emittierte
  `RunStarted`-Event erfolgt in Phase C/D
- Adapter-/Runner-Nutzer auf den neuen Rueckgabetyp angleichen (defaults
  auf `null`, keine Test-Churn)

### 5.5 B5 - Tests und Spezifikationsabgleich

- Port-/Adapter-Tests fuer Laden, Schreiben und Fehlverhalten
- Manifest-Serialisierungstests inkl. Versionierungs-/Kompatibilitaetsfaellen
- `PipelineConfig`- und Config-Merge-Tests
- Review der betroffenen Doku-Stellen:
  - `docs/connection-config-spec.md`
  - `docs/implementation-plan-0.9.0.md`
  - `docs/ImpPlan-0.9.0-A.md`

---

## 6. Teststrategie fuer Phase B

Phase B braucht vor allem Vertrags-, Serialisierungs- und
Konfigurations-Tests.

Mindestens noetige Tests:

- `PipelineConfig`:
  - bestehende `chunkSize`-Validierung bleibt intakt
  - neue Checkpoint-Defaults sind stabil
  - die Abbildung `pipeline.checkpoint.interval` -> row-basierter Runtime-Wert
    ist eindeutig und testbar
  - ungueltige Werte fuer Intervalle/Verzeichnisse werden sauber abgelehnt
- Manifestmodell:
  - Export- und Import-Manifest lassen sich serialisieren/deserialisieren
  - `schemaVersion` ist Pflicht und wird validiert
  - unbekannte oder inkompatible Versionen werden nicht still akzeptiert
- Dateiadapter:
  - neues Manifest wird geschrieben und wieder geladen
  - atomarer Replace-Pfad funktioniert
  - defekte oder partielle Dateien fuehren zu klaren Fehlern
- Merge-Helper:
  - CLI-Override fuer Checkpoint-Verzeichnis sticht Config
  - Config sticht Runtime-Default
  - der kanonische Config-Key fuer den Row-Trigger ist eindeutig
  - der Helper ist unabhaengig von einem konkreten Runner-Aufruf
    testbar (Runner-Verdrahtung folgt in Phase C/D)
- Progress-/Result-Typen:
  - `operationId` ist als optionales Feld in `RunStarted`, `ExportResult`
    und `ImportResult` vorhanden und wird von
    `StreamingExporter`/`StreamingImporter` in die Events gesetzt, wenn
    der Aufrufer sie mitgibt
  - bestehende stderr-Summary-Pfade verlieren dabei weder Kernstatistiken
    noch die Referenzierbarkeit des Laufs (UUID steht in der Summary)

Explizit nicht Ziel von Phase B:

- Nachweis echter Export-/Import-Wiederaufnahme ueber Datenchunks
- Nachweis, dass der Runner die `operationId` durch den Executor-Seam
  in das live emittierte `RunStarted` reicht (Phase C/D)
- Renderer-Anzeige der `operationId` im `ProgressRenderer` (Phase C/D)
- Signaltests
- Retry-Integration

---

## 7. Datei- und Codebasis-Betroffenheit

Sicher betroffen:

- `docs/implementation-plan-0.9.0.md`
- `docs/ImpPlan-0.9.0-A.md`
- `docs/ImpPlan-0.9.0-B.md`
- `docs/connection-config-spec.md`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/PipelineConfig.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ProgressEvent.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ExportResult.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ImportResult.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataImportRunner.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportHelpers.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/output/ProgressRenderer.kt`

Mit hoher Wahrscheinlichkeit neu:

- Checkpoint-Port-/Modelltypen unter `hexagon/ports/...`
- dateibasierter Checkpoint-Store unter einem getriebenen Adapter
- Serialisierungs-/Manifesttypen fuer Export und Import

Mit hoher Wahrscheinlichkeit betroffen:

- `adapters/driven/streaming/src/test/kotlin/dev/dmigrate/streaming/PipelineConfigTest.kt`
- `adapters/driven/streaming/src/test/kotlin/dev/dmigrate/streaming/StreamingExporterTest.kt`
- `adapters/driven/streaming/src/test/kotlin/dev/dmigrate/streaming/StreamingImporterTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataExportRunnerTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataImportRunnerTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/output/ProgressRendererTest.kt`

---

## 8. Risiken und offene Punkte

### 8.1 Manifestmodell zu frueh zu export-/import-spezifisch

Wenn Phase B das Manifest bereits zu stark an einen einzelnen Datenpfad
koppelt, muessen Phasen C und D spaeter mit Sonderfeldern oder Paralleltypen
arbeiten. Das Grundmodell muss deshalb klein, aber erweiterbar bleiben.

### 8.2 `PipelineConfig`-Aufweichung

Die groesste strukturelle Gefahr ist, `PipelineConfig` in Phase B zu einem
Sammelbehaelter fuer alles Mögliche zu machen. 0.9.0 braucht Checkpointing,
aber nicht gleichzeitig Retry und Parallelisierung in demselben Schritt.

### 8.3 Konfigurationsluecke zwischen CLI und Config-Spec

Sobald `--checkpoint-dir` als sichtbarer Vertrag existiert, muss klar sein,
wie es zu `pipeline.checkpoint.directory` priorisiert ist. Bleibt dieser
Merge uneinheitlich oder implizit, entsteht derselbe Widerspruch erneut in
zwei verschiedenen Konfigurationsoberflaechen.

### 8.4 Namensdrift bei Row-/Time-Triggern

Wenn `pipeline.checkpoint.interval` in der Config-Spec stehen bleibt,
`PipelineConfig` aber nur noch von `rowInterval` und `maxInterval` spricht,
entsteht ohne explizite Mapping-Entscheidung ein unnötiger Vertragsbruch.

### 8.5 Result-/Progress-/stderr-Drift

Wenn `operationId` nur in einzelnen Events oder nur in Resultaten auftaucht,
entsteht wieder ein halber Vertrag. Phase B muss die Referenzierbarkeit eines
Laufs ueber Events, Resultate und stderr-nahe Ausgabewege konsistent
herstellen.

### 8.6 Dateiadapter ohne atomaren Schreibvertrag

Ein Resume-System ist nur so belastbar wie seine Persistenzkante. Wenn der
dateibasierte Adapter partielle Dateien nicht sauber ausschliesst, wird der
gesamte 0.9.0-Resume-Vertrag fragil.

---

## 9. Entscheidungsempfehlung

Phase B sollte den Checkpoint-Unterbau bewusst vor Export- und Import-Resume
stabilisieren.

Das schafft fuer 0.9.0 drei wichtige Effekte:

- Export und Import bauen spaeter auf demselben Manifest- und Portvertrag auf
- die vorlaufende Config-Spec wird in einen echten Runtime-Vertrag ueberfuehrt
- `operationId` und Resume-Metadaten werden frueh als Querschnittsthema
  verankert statt spaeter pro Datenpfad nachgeruestet

Damit reduziert Phase B das technische Risiko der spaeteren Resume-Phasen C
und D deutlich, ohne den Milestone schon mit Retry-, Parallelisierungs- oder
Signalthemen zu ueberladen.
