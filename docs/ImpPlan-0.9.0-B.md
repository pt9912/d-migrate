# Implementierungsplan: Phase B - Checkpoint-Port, Manifest und Konfiguration

> **Milestone**: 0.9.0 - Beta: Resilienz und vollstaendige i18n-CLI
> **Phase**: B (Checkpoint-Port, Manifest und Konfiguration)
> **Status**: Planned (2026-04-16)
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
  sichtbar werden
- wie dieselbe `operationId` bis in `stderr`-nahe Progress-/Summary-Ausgaben
  durchgereicht wird
- wie sichtbare CLI-Overrides aus Phase A mit
  `pipeline.checkpoint.*` zusammengefuehrt werden

Phase B liefert damit noch keine vollstaendige Wiederaufnahme im Datenpfad.
Sie schafft aber den gemeinsamen Vertrag, ohne den Phasen C und D entweder
duplizierte Resume-Logik oder widerspruechliche Manifestmodelle bauen
wuerden.

Nach Phase B soll klar und testbar gelten:

- es gibt einen expliziten Checkpoint-Port statt impliziter Dateizugriffe
- es gibt ein versioniertes, serialisierbares Manifest fuer Export und Import
- `PipelineConfig` kann Checkpoint-Einstellungen produktiv tragen
- CLI-/Config-Werte fuer Checkpoint-Verhalten laufen ueber einen klaren,
  zentralen Merge-Vertrag
- Progress- und Result-Typen koennen einen Lauf stabil ueber
  `operationId` referenzieren
- dieselbe `operationId` ist in den stderr-nahen Progress-/Summary-Pfaden
  verfuegbar

---

## 2. Ausgangslage

Aktueller Stand im Repo:

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

### 5.2 B2 - `PipelineConfig` und Merge-Vertrag erweitern

- `PipelineConfig` um Checkpoint-Konfiguration erweitern
- zentralen Merge zwischen CLI, Config und Defaults definieren
- Beziehung zu `pipeline.checkpoint.directory` klar modellieren
- kanonische Abbildung zwischen Config-Key `pipeline.checkpoint.interval`
  und internem row-basiertem Trigger festziehen
- finalen Config-Key fuer den Zeit-Trigger festlegen und dokumentieren
- direkte `PipelineConfig(chunkSize = ...)`-Instanziierungen in Runnern auf
  den erweiterten Vertrag umstellen

### 5.3 B3 - Dateibasierten Checkpoint-Store-Adapter bauen

- dateibasierten Adapter fuer Laden/Speichern/Finden/Abschliessen einfuehren
- Manifest-Serialisierung und Dateilayout definieren
- atomaren Schreibpfad und Fehlermodell festziehen

### 5.4 B4 - Progress- und Result-Vertraege erweitern

- `operationId` in Progress-Pfaden verankern
- Result-Typen um minimale Resume-Metadaten erweitern
- stderr-nahe Progress-/Summary-Pfade auf dieselbe Referenz angleichen
- Adapter-/Runner-Nutzer auf den neuen Rueckgabetyp angleichen

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
- Merge-Vertrag:
  - CLI-Override fuer Checkpoint-Verzeichnis sticht Config
  - Config sticht Runtime-Default
  - der kanonische Config-Key fuer den Row-Trigger ist eindeutig
  - Export und Import nutzen denselben Merge-Pfad
- Progress-/Result-Typen:
  - `operationId` ist in RunStart und Endergebnis sichtbar
  - bestehende stderr-Summary-Pfade verlieren dabei weder Kernstatistiken
    noch die Referenzierbarkeit des Laufs

Explizit nicht Ziel von Phase B:

- Nachweis echter Export-/Import-Wiederaufnahme ueber Datenchunks
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
